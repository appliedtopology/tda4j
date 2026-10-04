package org.appliedtopology.tda4j

import scala.collection.mutable

/** Persistent homology of a cubical grid by union-find instead of matrix reduction (after Le Breton, Szustakowski and
  * Piraud, arXiv:2606.04801, extended here to any coefficient field and to representatives): degree 0 by union-find on
  * the vertices and edges, the top degree `d - 1` (`d` the grid's dimension) by union-find on the dual graph. In 2-D
  * those cover everything; in dimension 3 and up the degrees in between are computed by the cohomology engine on the
  * grid without its top cells. Requires `d >= 2`.
  *
  * The dual graph: the top cells (pixels) are vertices, the codimension-1 cells (facets) edges between the one or two
  * top cells containing them, with one extra vertex `∞`, at value `+Infinity`, on the far side of every facet on the
  * boundary of the grid. By Alexander duality, degree `d - 1` of the sublevel filtration is degree 0 of the dual
  * graph's superlevel filtration: union-find in decreasing value with the elder rule, each merge giving the primal bar
  * with birth and death swapped. `∞`'s component never dies.
  *
  * Representatives: each top cell carries a sign in its dual component, kept consistent as components merge (the sign
  * flip is solved from the merging facet's two boundary coefficients, both `±1`) so that interior facets cancel; when a
  * component dies, its representative is the boundary of its signed sum of top cells. A merge costs nearly constant
  * time; a representative costs time proportional to its region, and only reported bars get one.
  */
class FastCubicalHomologyEngine[CoefficientT: Field]:
  private val fr = summon[CoefficientT is Field]
  given Ordering[Cube] = cubeOrdering

  /** Every bar of the image's sublevel (or superlevel) filtration, with representatives; zero-length bars (a plateau's
    * cells pairing off at one value) only if `includeZeroLength`.
    */
  def persistentHomology(
    stream: CubicalGridStream,
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    compute(stream, includeZeroLength, representatives = true)

  /** The same bars without the top degree's representatives (their annotation is `None`): only for measuring what
    * representatives cost (`bench/`). Every public path computes them.
    */
  private[tda4j] def barsWithoutTopRepresentatives(
    stream: CubicalGridStream,
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    compute(stream, includeZeroLength, representatives = false)

  private def compute(
    stream: CubicalGridStream,
    includeZeroLength: Boolean,
    representatives: Boolean
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    require(
      stream.ambientDim >= 2,
      s"FastCubicalHomologyEngine requires ambient dimension >= 2, got ${stream.ambientDim}"
    )
    val bars =
      if stream.ambientDim == 2 then
        computeH0(stream, includeZeroLength) ++ computeDualTopDimension(stream, includeZeroLength, representatives)
      else
        computeMiddleDimensions(stream, includeZeroLength) ++
          computeDualTopDimension(stream, includeZeroLength, representatives)
    bars

  // -------------------------------------------------------------------------------------------------------------
  // d >= 3's "middle" degrees (1 <= k <= d-2) have no duality shortcut. They go to the cohomology engine on a view
  // that hides the top-dimensional cells (so the largest dimension never enters a reduction); the view declares
  // homologyDegreeLimit = d - 2, so its artificial degree d-1 classes are skipped by the involution, and we keep
  // degrees <= d-2. H_0 comes out of the same computation. Cohomology rather than chunks: with chunks the 3-D hybrid
  // was slower than plain cohomology on the whole image (.claude/WORKLOG-fast-cubical-representatives.md).
  // .claude/DESIGN-fast-engines-hybrid-middle-dimensions.md has the derivation of the hybrid itself.
  // -------------------------------------------------------------------------------------------------------------
  private def computeMiddleDimensions(
    stream: CubicalGridStream,
    includeZeroLength: Boolean
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    val truncated = LimitedCubicalGridStream(stream, stream.ambientDim - 1)
    CellularCohomologyEngine[Cube, CoefficientT, Double]()
      .persistentHomology(truncated, includeZeroLength)
      .filter(_.dim <= stream.ambientDim - 2)

  private def endpoint(lower: Boolean)(v: Double): BarcodeEndpoint[Double] =
    if !lower && v == Double.PositiveInfinity then PositiveInfinity()
    else if lower then ClosedEndpoint(v)
    else OpenEndpoint(v)

  // -------------------------------------------------------------------------------------------------------------
  // H_0: ordinary primal union-find, ascending value order, elder rule -- the dimension-0-only portion of
  // CellularPersistenceInChunksEngine.unionFindDim01's own already-validated pattern (no dimension-1
  // cycle-tracking needed here, since H_1 comes from the dual mechanism below instead).
  // -------------------------------------------------------------------------------------------------------------
  // Row-major strides of `shape`.
  private def strides(shape: Array[Int]): Array[Int] =
    val s = new Array[Int](shape.length)
    s(shape.length - 1) = 1
    for i <- shape.length - 2 to 0 by -1 do s(i) = s(i + 1) * shape(i + 1)
    s

  // Weights turning a cube's doubled-coordinate encoding (coordinate i in 0 .. 2 shape(i)) into a Long ordered like
  // the encoding itself, lexicographically.
  private def encodingWeights(shape: Array[Int]): Array[Long] =
    val w = new Array[Long](shape.length)
    w(shape.length - 1) = 1L
    for i <- shape.length - 2 to 0 by -1 do w(i) = w(i + 1) * (2L * shape(i + 1) + 1L)
    w

  // The top cells' values, row-major.
  private def pixelValues(stream: CubicalGridStream): Array[Double] =
    val shape = stream.shape.toArray
    val pstride = strides(shape)
    Array.tabulate(shape.product)(p =>
      stream.topCellValue(IndexedSeq.tabulate(shape.length)(i => (p / pstride(i)) % shape(i)))
    )

  private def computeH0(
    stream: CubicalGridStream,
    includeZeroLength: Boolean
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    // Flat arrays throughout: vertices are numbered in mixed radix over the (shape(i) + 1)-point grid, an edge is
    // (axis, lower vertex), and values come straight from the pixels (in the T-construction a cell's value is the
    // minimum over the pixels containing it). Cubes are built only for the bars' representatives. The edges are
    // processed in exactly the stream's order -- value ascending, then the cube's doubled-coordinate encoding
    // DESCENDING (`filtrationOrdering.reverse`) -- and essential classes are listed in the stream's vertex order, so
    // the bars equal what a union-find over the stream's own cells gives, term for term.
    val d = stream.ambientDim
    val shape = stream.shape.toArray
    val vdims = shape.map(_ + 1)
    val vstride = new Array[Int](d)
    vstride(d - 1) = 1
    for i <- d - 2 to 0 by -1 do vstride(i) = vstride(i + 1) * vdims(i + 1)
    val numVertices = vdims.foldLeft(1)(_ * _)
    val encWeight = encodingWeights(shape)
    val pixels = pixelValues(stream)
    def pixel(coords: Array[Int]): Double =
      var p = 0
      for i <- 0 until d do p = p * shape(i) + coords(i)
      pixels(p)

    def vertexCoords(v: Int): Array[Int] =
      val c = new Array[Int](d)
      var rem = v
      for i <- 0 until d do
        c(i) = rem / vstride(i)
        rem = rem % vstride(i)
      c

    // The minimum over the pixels containing the cell with lower corner `a` and non-degenerate axis `axis` (-1: none).
    val choice = new Array[Int](d)
    def cellValue(a: Array[Int], axis: Int): Double =
      var best = Double.PositiveInfinity
      val free = (0 until d).filter(_ != axis)
      for mask <- 0 until (1 << free.size) do
        var ok = true
        for (i, b) <- free.zipWithIndex do
          val v = a(i) - ((mask >> b) & 1)
          choice(i) = v
          if v < 0 || v >= shape(i) then ok = false
        if axis >= 0 then choice(axis) = a(axis)
        if ok then best = math.min(best, pixel(choice))
      best

    val vertexValue = new Array[Double](numVertices)
    val vertexKey = new Array[Long](numVertices)
    for v <- 0 until numVertices do
      val a = vertexCoords(v)
      vertexValue(v) = cellValue(a, -1)
      var key = 0L
      for i <- 0 until d do key += 2L * a(i) * encWeight(i)
      vertexKey(v) = key

    // Edges: id = axis * numVertices + lower vertex, kept only where the edge lies in the grid.
    val edgeBuf = mutable.ArrayBuilder.make[Int]
    for axis <- 0 until d; v <- 0 until numVertices do
      if (v / vstride(axis)) % vdims(axis) < shape(axis) then edgeBuf += axis * numVertices + v
    val edges = edgeBuf.result()
    val edgeValue = new Array[Double](edges.length)
    val edgeKey = new Array[Long](edges.length)
    for k <- edges.indices do
      val axis = edges(k) / numVertices
      val v = edges(k) % numVertices
      edgeValue(k) = cellValue(vertexCoords(v), axis)
      edgeKey(k) = vertexKey(v) + encWeight(axis)

    // Stream order: value ascending, then encoding descending.
    def streamOrder(values: Array[Double], keys: Array[Long], n: Int): Array[Int] =
      SortIndices.sort(
        n,
        (x, y) =>
          val c = java.lang.Double.compare(values(x), values(y))
          if c != 0 then c < 0 else keys(x) > keys(y)
      )

    val parent = Array.range(0, numVertices)
    def find(i: Int): Int =
      var root = i
      while parent(root) != root do root = parent(root)
      var cur = i
      while parent(cur) != root do
        val next = parent(cur)
        parent(cur) = root
        cur = next
      root

    def vertexCube(v: Int): Cube = Cube(vertexCoords(v).toIndexedSeq, Set.empty)
    val bars = mutable.ArrayBuffer.empty[PersistenceBar[Double, Chain[Cube, CoefficientT]]]
    for k <- streamOrder(edgeValue, edgeKey, edges.length) do
      val axis = edges(k) / numVertices
      val lower = edges(k) % numVertices
      val upper = lower + vstride(axis)
      // The edge's boundary lists its upper endpoint first; the elder rule keeps that side on a tie.
      val r0 = find(upper)
      val r1 = find(lower)
      if r0 != r1 then
        val (youngRoot, oldRoot) = if vertexValue(r0) <= vertexValue(r1) then (r1, r0) else (r0, r1)
        parent(youngRoot) = oldRoot
        val (birth, death) = (vertexValue(youngRoot), edgeValue(k))
        if includeZeroLength || birth != death then
          bars += new PersistenceBar(
            0,
            endpoint(true)(birth),
            endpoint(false)(death),
            Some(Chain(vertexCube(youngRoot)))
          )
    for v <- streamOrder(vertexValue, vertexKey, numVertices) do
      if find(v) == v then
        bars += new PersistenceBar(0, endpoint(true)(vertexValue(v)), PositiveInfinity(), Some(Chain(vertexCube(v))))
    bars.toList

  // -------------------------------------------------------------------------------------------------------------
  // H_{d-1} (= H_1 at d=2): the dual union-find. See the class doc and .claude/DESIGN-fast-cubical-engine.md for
  // the derivation this implements term-for-term.
  // -------------------------------------------------------------------------------------------------------------
  private def computeDualTopDimension(
    stream: CubicalGridStream,
    includeZeroLength: Boolean,
    representatives: Boolean
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    // Flat arrays, as in computeH0: top cells (pixels) are numbered row-major, a facet is (degenerate axis, lower
    // corner) on the grid of facets, and cubes are built only for the representatives of reported bars. The events are
    // processed in the same order as a sort of the cells themselves: value DESCENDING, top cells before facets at a
    // tied value (load-bearing, see the design note), then top cells by coordinates and facets by their
    // doubled-coordinate encoding, both lexicographically ascending.
    val d = stream.ambientDim
    val shape = stream.shape.toArray
    val pixels = pixelValues(stream)
    val numTop = pixels.length
    val infinityId = numTop // one past the last real top-cell id
    val pstride = strides(shape)
    val encWeight = encodingWeights(shape)
    def topValue(id: Int): Double = if id == infinityId then Double.PositiveInfinity else pixels(id)

    // Facets: for each axis, the grid of lower corners with that axis's coordinate in 0 .. shape and the others in
    // 0 until shape. `facetA`/`facetB` are the top cells on the low and high side along the axis (`infinityId` past
    // the grid's boundary).
    val facetAxis = mutable.ArrayBuilder.make[Int]
    val facetA = mutable.ArrayBuilder.make[Int]
    val facetB = mutable.ArrayBuilder.make[Int]
    val facetKey = mutable.ArrayBuilder.make[Long]
    val facetLoneBelow = mutable.ArrayBuilder.make[Boolean] // a boundary facet's one top cell lies below it
    val corner = new Array[Int](d)
    for axis <- 0 until d do
      val fdims = Array.tabulate(d)(i => if i == axis then shape(i) + 1 else shape(i))
      val count = fdims.foldLeft(1)(_ * _)
      for f <- 0 until count do
        var rem = f
        for i <- d - 1 to 0 by -1 do
          corner(i) = rem % fdims(i)
          rem = rem / fdims(i)
        var base = 0 // the pixel with these coordinates, the axis coordinate set to 0
        var key = 0L
        for i <- 0 until d do
          if i != axis then base += corner(i) * pstride(i)
          key += (if i == axis then 2L * corner(i) else 2L * corner(i) + 1L) * encWeight(i)
        val k = corner(axis)
        val below = if k - 1 >= 0 then base + (k - 1) * pstride(axis) else -1
        val above = if k < shape(axis) then base + k * pstride(axis) else -1
        facetAxis += axis
        // The same order as the cube-based construction: the lower pixel first, `∞` last.
        if below >= 0 && above >= 0 then
          facetA += below; facetB += above
        else
          facetA += (if below >= 0 then below else above); facetB += infinityId
        facetKey += key
        facetLoneBelow += (above < 0)
    val fAxis = facetAxis.result()
    val fA = facetA.result()
    val fB = facetB.result()
    val fKey = facetKey.result()
    val fLoneBelow = facetLoneBelow.result()
    val numFacets = fAxis.length
    val facetValue = Array.tabulate(numFacets)(f => math.min(topValue(fA(f)), topValue(fB(f))))

    // Events 0 until numTop are top cells, numTop until numTop + numFacets facets.
    val order = SortIndices.sort(
      numTop + numFacets,
      (x, y) =>
        val vx = if x < numTop then pixels(x) else facetValue(x - numTop)
        val vy = if y < numTop then pixels(y) else facetValue(y - numTop)
        val c = java.lang.Double.compare(vy, vx) // descending
        if c != 0 then c < 0
        else if (x < numTop) != (y < numTop) then x < numTop
        else if x < numTop then x < y
        else fKey(x - numTop) < fKey(y - numTop)
    )

    // The coefficient of a facet in the boundary of a top cell containing it (`cubeIsOrderedCell`'s rule: along axis
    // `a`, the upper face carries +1 for even `a` and -1 for odd `a`, the lower face the opposite sign). The facet is
    // the upper face of the top cell below it and the lower face of the one above.
    val plus = fr.one
    val minus = fr.negate(fr.one)
    def upperSign(axis: Int): CoefficientT = if axis % 2 == 0 then plus else minus
    def coeffToward(f: Int, top: Int): CoefficientT =
      val axis = fAxis(f)
      if top == fA(f) && fB(f) != infinityId then upperSign(axis) // `top` is below the facet
      else if top == fB(f) then fr.negate(upperSign(axis)) // `top` is above the facet
      else if fLoneBelow(f) then upperSign(axis) // a boundary facet at the top of the grid along `axis`
      else fr.negate(upperSign(axis)) // a boundary facet at coordinate 0

    // The boundary of `root`'s region, each top cell taken with `flip` times its orientation: the facets are summed by
    // their encoding (interior ones cancel), and cubes are built only for the facets that remain. The same products
    // as `cubeIsOrderedCell`'s boundary formula, so the coefficients are identical to summing the top cells' own
    // boundaries.
    val bases = shape.map(n => 2 * n + 1)
    def cubeOfKey(key: Long): Cube =
      Cube.fromVector(Vector.tabulate(d)(i => ((key / encWeight(i)) % bases(i)).toInt))
    def regionBoundary(uf: SignedUnionFind[CoefficientT], root: Int, flip: CoefficientT): Chain[Cube, CoefficientT] =
      val acc = mutable.LongMap.empty[CoefficientT]
      def add(key: Long, x: CoefficientT): Unit =
        acc.updateWith(key) {
          case Some(y) => Some(fr.plus(y, x))
          case None    => Some(x)
        }
      for (id, sign) <- uf.members(root) do
        val c = fr.times(flip, sign)
        var pixelKey = 0L
        for i <- 0 until d do pixelKey += (2L * ((id / pstride(i)) % shape(i)) + 1L) * encWeight(i)
        for a <- 0 until d do
          add(pixelKey + encWeight(a), fr.times(c, upperSign(a)))
          add(pixelKey - encWeight(a), fr.times(c, fr.negate(upperSign(a))))
      Chain.from(acc.iterator.collect { case (k, x) if !fr.isEqual(x, fr.zero) => (cubeOfKey(k), x) }.toSeq)

    // Union-find over `0 to numTop` (numTop itself = infinityId). `birthOf(root)` is the value at which the CURRENT
    // root's own component was seeded (the root is always the OLDEST -- i.e. largest-value -- member). Orientations
    // live in `uf`; a dying region is assembled from it only for a bar that is reported (see SignedUnionFind).
    val uf = SignedUnionFind[CoefficientT](numTop + 1)
    val birthOf: Array[Double] = Array.fill(numTop + 1)(Double.NaN)
    birthOf(infinityId) = Double.PositiveInfinity
    val bars = mutable.ArrayBuffer.empty[PersistenceBar[Double, Chain[Cube, CoefficientT]]]

    for ev <- order do
      if ev < numTop then birthOf(ev) = pixels(ev)
      else
        val f = ev - numTop
        val (a, b, v) = (fA(f), fB(f), facetValue(f))
        val ra = uf.find(a)
        val rb = uf.find(b)
        if ra != rb then
          // `infinityId` is the unconditional elder of any merge it takes part in: a real top cell can also have the
          // value +Infinity (a permanently missing pixel) and tie against it, so it is special-cased rather than
          // left to the birth comparison.
          val (youngRoot, oldRoot) =
            if ra == infinityId then (rb, ra)
            else if rb == infinityId then (ra, rb)
            else if birthOf(ra) <= birthOf(rb) then (ra, rb)
            else (rb, ra)
          val (youngTopId, oldTopId) = if youngRoot == ra then (a, b) else (b, a)
          require(youngTopId != infinityId, "an engine bug: the infinity dual vertex was treated as the younger side")
          // Solve flip * orient(young) * coeff(young) + orient(old) * coeff(old) = 0; every factor is +-1. Test the
          // RESOLVED root: `oldTopId` can be a real cell already merged into infinity's component, which is never summed.
          val flip: CoefficientT =
            if oldRoot == infinityId then fr.one
            else
              fr.negate(
                fr.times(
                  fr.times(uf.orientation(oldTopId), coeffToward(f, oldTopId)),
                  fr.times(uf.orientation(youngTopId), coeffToward(f, youngTopId))
                )
              )
          if includeZeroLength || v != birthOf(youngRoot) then
            bars += new PersistenceBar(
              d - 1,
              endpoint(true)(v),
              endpoint(false)(birthOf(youngRoot)),
              Option.when(representatives)(regionBoundary(uf, youngRoot, flip))
            )
          uf.union(youngRoot, oldRoot, flip)
    bars.toList
