package org.appliedtopology.tda4j

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

/** Persistent homology of a cubical grid by union-find instead of matrix reduction (after Le Breton, Szustakowski and
  * Piraud, arXiv:2606.04801, extended here to any coefficient field and to representatives): degree 0 by union-find on
  * the vertices and edges, the top degree `d - 1` (`d` the grid's dimension) by union-find on the dual graph. In 2-D
  * those cover everything; in dimension 3 and up the degrees in between are computed by the cohomology engine on the
  * grid without its top cells. Requires `d >= 2`. A NaN (masked) pixel is not in the complex (see
  * [[CubicalGridStream]]).
  *
  * The dual graph: the top cells (pixels) are vertices, the codimension-1 cells (facets) edges between the one or two
  * top cells containing them, with one extra vertex `∞`, at value `+Infinity`, on the far side of every facet on the
  * boundary of the grid. By Alexander duality, degree `d - 1` of the sublevel filtration is degree 0 of the dual
  * graph's superlevel filtration: union-find in decreasing value with the elder rule, each merge giving the primal bar
  * with birth and death swapped. `∞`'s component never dies. A masked pixel is in the complement from the start, as `∞`
  * is, and so is a facet between two masked pixels or a masked pixel and the outside: a component holding one is elder
  * to every other but `∞`'s, and when it dies at a facet of value `v` the primal class is born at `v` and never dies.
  *
  * Representatives: each top cell carries a sign in its dual component, kept consistent as components merge (the sign
  * flip is solved from the merging facet's two boundary coefficients, both `±1`) so that interior facets cancel; when a
  * component dies, its representative is the boundary of its signed sum of top cells. A merge costs nearly constant
  * time; a representative costs time proportional to its region, and only reported bars get one.
  */
open class FastCubicalHomologyEngine[CoefficientT: Field]:
  private val fr = summon[CoefficientT is Field]
  given Ordering[Cube] = cubeOrdering

  /** Every bar of the image's sublevel (or superlevel) filtration up to degree `maxDimension` (default: all, up to
    * `d - 1`), with representatives; zero-length bars (a plateau's cells pairing off at one value) only if
    * `includeZeroLength`. Below the top degree only the cells of dimension `maxDimension + 1` and lower are built, as
    * for any other engine, and the dual graph is walked only when the top degree is asked for.
    */
  def persistentHomology(
    stream: CubicalGridStream,
    includeZeroLength: Boolean = false,
    maxDimension: Optional[Int] = Optional.empty
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    compute(stream, includeZeroLength, representatives = true, maxDimension)

  // `private[tda4j]` on purpose: every public way to compute persistence returns a representative with every bar (the
  // library's design principle), and this measurement hook does not.
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
    representatives: Boolean,
    maxDimension: Optional[Int] = Optional.empty
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    require(
      stream.ambientDim >= 2,
      s"FastCubicalHomologyEngine requires ambient dimension >= 2, got ${stream.ambientDim}"
    )
    val top = stream.ambientDim - 1
    val cap = math.min(maxDimension.getOrElse(top), top)
    require(cap >= 0, s"maxDimension must be at least 0, got $cap")
    val grid = GridRanks(stream.shape, stream.topCellValues)
    // Representatives are packed on every grid whose cells have Int indices (every grid that fits in memory); one
    // decoder per run, shared by all of them.
    val cubes = Option.when(GridCubes.fits(grid.shape))(GridCubes(grid.shape))
    val dual = if cap == top then computeDualTopDimension(grid, includeZeroLength, representatives, cubes) else Nil
    if stream.ambientDim == 2 then computeH0(grid, includeZeroLength, cubes) ++ dual
    else computeMiddleDimensions(stream, grid, includeZeroLength, math.min(cap, top - 1)) ++ dual

  // -------------------------------------------------------------------------------------------------------------
  // d >= 3's "middle" degrees (1 <= k <= d-2) have no duality shortcut. They go to cohomology on the grid without its
  // top-dimensional cells (so the largest dimension never enters a reduction), degrees <= d-2, cycles by the
  // involution; H_0 comes out of the same computation. The packed grid engine gives exactly what
  // CellularCohomologyEngine gives on a LimitedCubicalGridStream (PackedCubicalCohomologySpec), which is what this
  // used before (.claude/WORKLOG-cubical-performance.md). Cohomology rather than chunks: with chunks the 3-D hybrid
  // was slower than plain cohomology on the whole image (.claude/WORKLOG-fast-cubical-representatives.md).
  // .claude/DESIGN-fast-engines-hybrid-middle-dimensions.md has the derivation of the hybrid itself.
  // -------------------------------------------------------------------------------------------------------------
  private def computeMiddleDimensions(
    stream: CubicalGridStream,
    grid: GridRanks,
    includeZeroLength: Boolean,
    upTo: Int
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    new PackedCubicalCohomologyEngine[CoefficientT](stream, upTo + 1, grid)
      .persistentHomology(includeZeroLength)
      .filter(_.dim <= upTo)

  private def endpoint(lower: Boolean)(v: Double): BarcodeEndpoint[Double] =
    if !lower && v == Double.PositiveInfinity then PositiveInfinity()
    else if lower then ClosedEndpoint(v)
    else OpenEndpoint(v)

  // -------------------------------------------------------------------------------------------------------------
  // Both passes order cells by RANK (GridRanks: a cell's rank is the smallest rank of the pixels containing it), with a
  // stable counting sort fed in the order of the tie-break, and compare VALUES (`distinct(rank)`) wherever the elder
  // rule or a zero-length test does: the order is java.lang.Double.compare's, the comparisons IEEE's (-0.0 == 0.0),
  // exactly as when both were sorts and tests on the values themselves. The bars, their order and their
  // representatives equal the cube-based reference's term for term (FastRepresentativesSpec);
  // .claude/WORKLOG-cubical-performance.md has the measurements.
  // -------------------------------------------------------------------------------------------------------------

  // -------------------------------------------------------------------------------------------------------------
  // H_0: ordinary primal union-find, ascending value order, elder rule -- the dimension-0-only portion of
  // CellularPersistenceInChunksEngine.unionFindDim01's own already-validated pattern (no dimension-1
  // cycle-tracking needed here, since H_1 comes from the dual mechanism below instead).
  // -------------------------------------------------------------------------------------------------------------
  private def computeH0(
    grid: GridRanks,
    includeZeroLength: Boolean,
    cubes: Option[GridCubes]
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    // Vertices are numbered in mixed radix over the (shape(i) + 1)-point grid, an edge is (axis, lower vertex) with id
    // axis * numVertices + lower vertex. The edges are processed in exactly the stream's order -- value ascending, then
    // the cube's doubled-coordinate encoding DESCENDING (`filtrationOrdering.reverse`) -- so the bars equal what a
    // union-find over the stream's own cells gives, term for term.
    val d = grid.d
    val shape = grid.shape
    val distinct = grid.distinct
    val masked = grid.masked // the rank of a vertex or edge every pixel around which is masked: not in the complex
    val vdims = shape.map(_ + 1)
    val vstride = GridRanks.strides(vdims)
    val numVertices = GridRanks.checkedProduct(vdims, "FastCubicalHomologyEngine")
    require(d.toLong * numVertices <= Int.MaxValue, s"FastCubicalHomologyEngine: ${d.toLong * numVertices} edge slots")

    // Ranks over the vertex grid: a vertex takes the smallest rank of the pixels whose closure contains it, an edge
    // along `axis` (at its lower vertex) the smallest over the pixels containing it. Separable: put each pixel's rank at
    // its lower corner, then along each axis take the minimum with the lower neighbour -- along every axis for a
    // vertex, every axis but `axis` for an edge.
    def embedded(): Array[Int] =
      val a = new Array[Int](numVertices)
      java.util.Arrays.fill(a, Int.MaxValue)
      val pstride = GridRanks.strides(shape)
      val rowLength = shape(d - 1)
      val rows = grid.size / rowLength
      var row = 0
      while row < rows do
        val p0 = row * rowLength
        var v0 = 0
        var i = 0
        while i < d - 1 do
          v0 += ((p0 / pstride(i)) % shape(i)) * vstride(i)
          i += 1
        System.arraycopy(grid.rank, p0, a, v0, rowLength)
        row += 1
      a
    def minWithLowerNeighbour(a: Array[Int], axis: Int): Unit =
      val stride = vstride(axis)
      val len = vdims(axis)
      val block = stride * len
      var base0 = 0
      while base0 < a.length do
        var t = len - 1
        while t >= 1 do
          val base = base0 + t * stride
          var lo = 0
          while lo < stride do
            val lower = a(base - stride + lo)
            if lower < a(base + lo) then a(base + lo) = lower
            lo += 1
          t -= 1
        base0 += block
    val edgeRank: Array[Array[Int]] = Array.tabulate(d) { axis =>
      val a = embedded()
      for other <- 0 until d if other != axis do minWithLowerNeighbour(a, other)
      a
    }
    val vertexRank = edgeRank(0).clone()
    minWithLowerNeighbour(vertexRank, 0)

    // The edges in DESCENDING encoding order, as (axis, lower vertex): a prefix of the doubled grid with no odd
    // coordinate holds the edges along the last axis (odd last coordinate), a prefix with one odd coordinate the edges
    // along that axis (even last coordinate); vertex coordinates are the doubled ones halved, rounded down.
    val last = d - 1
    inline def foreachEdgeDescending(inline f: (Int, Int) => Unit): Unit =
      DoubledGrid.foreachPrefix(shape, ascending = false) { c =>
        var odd = 0
        var oddAxis = -1
        var vbase = 0
        var i = 0
        while i < last do
          if (c(i) & 1) == 1 then
            odd += 1
            oddAxis = i
          vbase += (c(i) >> 1) * vstride(i)
          i += 1
        if odd == 0 then
          var t = shape(last) - 1
          while t >= 0 do
            f(last, vbase + t)
            t -= 1
        else if odd == 1 then
          var t = shape(last)
          while t >= 0 do
            f(oddAxis, vbase + t)
            t -= 1
      }

    // Stable counting sort by rank of the edges taken in descending encoding order: value ascending, encoding
    // descending. Afterwards `bucketEnd(r)` is the end of rank r's run in `sorted`.
    val bucketEnd = new Array[Int](distinct.length + 1)
    foreachEdgeDescending { (axis, v) =>
      val rank = edgeRank(axis)(v)
      if rank != masked then bucketEnd(rank + 1) += 1
    }
    var r = 1
    while r <= distinct.length do
      bucketEnd(r) += bucketEnd(r - 1)
      r += 1
    val numEdges = bucketEnd(distinct.length)
    val sorted = new Array[Int](numEdges)
    foreachEdgeDescending { (axis, v) =>
      val rank = edgeRank(axis)(v)
      if rank != masked then
        sorted(bucketEnd(rank)) = axis * numVertices + v
        bucketEnd(rank) += 1
    }

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

    val boxes = CubeBoxes(shape)
    def vertexCube(v: Int): Cube =
      val lattice = GridRanks.coordinates(vdims, v)
      boxes.cube(i => 2 * lattice(i))
    // A vertex as a one-term chain: packed by its doubled-grid index, or a heap chain when indices do not fit.
    val oneBox = fr.one.asInstanceOf[AnyRef]
    val cellWeight = if cubes.isDefined then GridRanks.strides(shape.map(n => 2 * n + 1)) else Array.empty[Int]
    def vertexChain(v: Int): Chain[Cube, CoefficientT] = cubes match
      case Some(decoder) =>
        val lattice = GridRanks.coordinates(vdims, v)
        var key = 0
        var i = 0
        while i < d do
          key += 2 * lattice(i) * cellWeight(i)
          i += 1
        Chain.packed(Array(key), Array(oneBox), decoder)
      case None => Chain(vertexCube(v))
    val bars = mutable.ArrayBuffer.empty[PersistenceBar[Double, Chain[Cube, CoefficientT]]]
    var rank = 0
    var k = 0
    while k < numEdges do
      while bucketEnd(rank) <= k do rank += 1
      val id = sorted(k)
      val axis = id / numVertices
      val lower = id - axis * numVertices
      val upper = lower + vstride(axis)
      // The edge's boundary lists its upper endpoint first; the elder rule keeps that side on a tie.
      val r0 = find(upper)
      val r1 = find(lower)
      if r0 != r1 then
        val upperIsOlder = distinct(vertexRank(r0)) <= distinct(vertexRank(r1))
        val youngRoot = if upperIsOlder then r1 else r0
        val oldRoot = if upperIsOlder then r0 else r1
        parent(youngRoot) = oldRoot
        val birth = distinct(vertexRank(youngRoot))
        val death = distinct(rank)
        if includeZeroLength || birth != death then
          bars += new PersistenceBar(
            0,
            endpoint(true)(birth),
            endpoint(false)(death),
            Some(vertexChain(youngRoot))
          )
      k += 1
    // One essential class per component left: one without a mask, several when masked pixels cut the image apart, none
    // when every pixel is masked. Listed as the stream lists vertices, oldest first: value ascending, then the encoding
    // (here the vertex number) descending.
    val roots = mutable.ArrayBuffer.empty[Int]
    var v = numVertices - 1
    while v >= 0 do
      if vertexRank(v) != masked && find(v) == v then roots += v
      v -= 1
    for root <- roots.sortBy(vertexRank(_)) do // a stable sort: the vertex number stays descending within a rank
      bars += new PersistenceBar(
        0,
        endpoint(true)(distinct(vertexRank(root))),
        PositiveInfinity(),
        Some(vertexChain(root))
      )
    bars.toList

  // -------------------------------------------------------------------------------------------------------------
  // H_{d-1} (= H_1 at d=2): the dual union-find. See the class doc and .claude/DESIGN-fast-cubical-engine.md for
  // the derivation this implements term-for-term.
  // -------------------------------------------------------------------------------------------------------------
  private def computeDualTopDimension(
    grid: GridRanks,
    includeZeroLength: Boolean,
    representatives: Boolean,
    cubes: Option[GridCubes]
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    // Top cells (pixels) are numbered row-major. A facet is coded (pixel * d + axis) * 3 + side: side 0 for an interior
    // facet, coded by the pixel above it along `axis`; side 1 for a facet on the grid's lower boundary, coded by its
    // one pixel, above it; side 2 for one on the upper boundary, coded by its one pixel, below it. Facets are processed
    // in the same order as a sort of the cells themselves: value DESCENDING, then the doubled-coordinate encoding
    // ascending. The pixels' own events are left out: a facet's value is the smaller of its pixels', so both pixels
    // always come before it, and a component's birth is its root pixel's value.
    val d = grid.d
    val shape = grid.shape
    val pixels = grid.pixels
    val pixelRank = grid.rank
    val distinct = grid.distinct
    val numTop = pixels.length
    val infinityId = numTop // one past the last real top-cell id
    // A masked pixel is not in the complex, so it is in the complement from the start, like `∞`; so is a facet every
    // pixel of which is masked (rank `masked`, the largest, so the descending sweep takes these facets first).
    val masked = grid.masked
    require(3L * d * numTop <= Int.MaxValue, s"FastCubicalHomologyEngine: ${3L * d * numTop} facet codes")
    val pstride = GridRanks.strides(shape)
    val encWeight = GridRanks.encodingWeights(shape)
    def birthOf(id: Int): Double = if id == infinityId then Double.PositiveInfinity else pixels(id)

    // The facets in ASCENDING encoding order, as (code, rank): a prefix of the doubled grid with no even coordinate
    // holds the facets across the last axis (even last coordinate), a prefix with one even coordinate the facets across
    // that axis (odd last coordinate).
    val last = d - 1
    inline def foreachFacetAscending(inline f: (Int, Int) => Unit): Unit =
      DoubledGrid.foreachPrefix(shape, ascending = true) { c =>
        var even = 0
        var evenAxis = -1
        var pbase = 0 // the pixel with these coordinates, the even axis's coordinate set to 0
        var i = 0
        while i < last do
          if (c(i) & 1) == 0 then
            even += 1
            evenAxis = i
          else pbase += (c(i) >> 1) * pstride(i)
          i += 1
        if even == 0 then
          // Across the last axis, at lattice coordinate k: pixels k - 1 (below) and k (above) along it.
          val n = shape(last)
          var k = 0
          while k <= n do
            if k == 0 then f(((pbase * d) + last) * 3 + 1, pixelRank(pbase))
            else if k == n then f(((pbase + k - 1) * d + last) * 3 + 2, pixelRank(pbase + k - 1))
            else f(((pbase + k) * d + last) * 3, math.min(pixelRank(pbase + k - 1), pixelRank(pbase + k)))
            k += 1
        else if even == 1 then
          // Across `evenAxis`, at lattice coordinate k = c(evenAxis) / 2, the last axis running over the pixels.
          val axis = evenAxis
          val k = c(axis) >> 1
          val n = shape(axis)
          val s = pstride(axis)
          var t = 0
          while t < shape(last) do
            val p = pbase + t
            if k == 0 then f((p * d + axis) * 3 + 1, pixelRank(p))
            else if k == n then f(((p + (k - 1) * s) * d + axis) * 3 + 2, pixelRank(p + (k - 1) * s))
            else f(((p + k * s) * d + axis) * 3, math.min(pixelRank(p + (k - 1) * s), pixelRank(p + k * s)))
            t += 1
      }

    // Stable counting sort by DESCENDING rank of the facets taken in ascending encoding order. Afterwards
    // `bucketEnd(r)` is the end of rank r's run in `sorted`, the runs from the largest rank down.
    val m = distinct.length
    val bucketEnd = new Array[Int](m + 1)
    foreachFacetAscending((_, rank) => bucketEnd(rank) += 1)
    var acc = 0
    var r = m
    while r >= 0 do
      val c = bucketEnd(r)
      bucketEnd(r) = acc
      acc += c
      r -= 1
    val numFacets = acc
    val sorted = new Array[Int](numFacets)
    foreachFacetAscending { (code, rank) =>
      sorted(bucketEnd(rank)) = code
      bucketEnd(rank) += 1
    }

    // The coefficient of a facet in the boundary of a top cell containing it (`cubeIsOrderedCell`'s rule: along axis
    // `a`, the upper face carries +1 for even `a` and -1 for odd `a`, the lower face the opposite sign). The facet is
    // the upper face of the top cell below it and the lower face of the one above. Every sign below is an integer +-1,
    // mapped to the field only when a representative is written (`field`): the integers map to any field by a ring
    // homomorphism, so the coefficients are the ones the field arithmetic gives.
    def upperSign(axis: Int): Int = if axis % 2 == 0 then 1 else -1
    val one = fr.one
    val two = fr.plus(one, one)
    def field(n: Int): CoefficientT = n match
      case 1  => one
      case -1 => fr.negate(one)
      case 2  => two
      case -2 => fr.negate(two)
      case _  => throw new IllegalStateException(s"an engine bug: a facet of a region boundary has coefficient $n")

    // The boundary of `root`'s region, each top cell taken with `flip` times its sign: the facets are summed by their
    // encoding (interior ones cancel), and cubes are built only for the facets that remain. The same products as
    // `cubeIsOrderedCell`'s boundary formula, so the coefficients are identical to summing the top cells' own
    // boundaries.
    val bases = shape.map(n => 2 * n + 1)
    val boxes = CubeBoxes(shape)
    def cubeOfKey(key: Long): Cube =
      boxes.cube(i => ((key / encWeight(i)) % bases(i)).toInt)
    // A packed representative stores references to these: the field's images of -2 .. 2, made once.
    val coefficientBox = Array.tabulate(5)(i => if i == 2 then null else field(i - 2).asInstanceOf[AnyRef])
    val keepCoefficient = Array.tabulate(5)(i => i != 2 && !fr.isEqual(field(i - 2), fr.zero))
    val sums = FacetSums()
    def heapRegionBoundary(uf: UnitSignedUnionFind, root: Int, flip: Int): Chain[Cube, CoefficientT] =
      uf.foreachMember(root) { (id, sign) =>
        val c = flip * sign
        var pixelKey = 0L
        var rem = id
        var i = d - 1
        while i >= 0 do
          pixelKey += (2L * (rem % shape(i)) + 1L) * encWeight(i)
          rem /= shape(i)
          i -= 1
        var a = 0
        while a < d do
          sums.add(pixelKey + encWeight(a), c * upperSign(a))
          sums.add(pixelKey - encWeight(a), -c * upperSign(a))
          a += 1
      }
      val terms = mutable.ArrayBuffer.empty[(Cube, CoefficientT)]
      var j = 0
      while j < sums.size do
        val n = sums.sumAt(j)
        if n != 0 then
          val x = field(n)
          if !fr.isEqual(x, fr.zero) then terms += ((cubeOfKey(sums.keyAt(j)), x))
        j += 1
      sums.clear()
      Chain.from(ArraySeq.untagged.from(terms))

    // The same boundary for packed representatives, where every doubled-grid index is an `Int`: the sums, the sort and
    // the arithmetic on the way to them stay in `Int`. On Scala.js a `Long` is two ints in an object and a `Long` array
    // an array of objects, and `java.util.Arrays.sort` of a range allocates a scratch array as long as the WHOLE array
    // (so once one big region has grown the scratch, each later region pays for it): `Parallel.sortInts` is the seam.
    // This was a third of the time of a noisy 2048² image there (.claude/WORKLOG-js-scaling.md).
    val intWeight = GridRanks.strides(bases)
    val intSums = IntFacetSums()
    // Reused from one region to the next: a region's facets before they cancel (interior ones included), which is
    // several times the boundary that is kept.
    var keptKeys = new Array[Int](1024)
    def packedRegionBoundary(
      uf: UnitSignedUnionFind,
      root: Int,
      flip: Int,
      decoder: GridCubes
    ): Chain[Cube, CoefficientT] =
      uf.foreachMember(root) { (id, sign) =>
        val c = flip * sign
        var pixelKey = 0
        var rem = id
        var i = d - 1
        while i >= 0 do
          pixelKey += (2 * (rem % shape(i)) + 1) * intWeight(i)
          rem /= shape(i)
          i -= 1
        var a = 0
        while a < d do
          intSums.add(pixelKey + intWeight(a), c * upperSign(a))
          intSums.add(pixelKey - intWeight(a), -c * upperSign(a))
          a += 1
      }
      if keptKeys.length < intSums.size then keptKeys = new Array[Int](math.max(intSums.size, 2 * keptKeys.length))
      val kept = keptKeys
      var count = 0
      var j = 0
      while j < intSums.size do
        val n = intSums.sumAt(j)
        if n != 0 then
          if n < -2 || n > 2 then
            throw new IllegalStateException(s"an engine bug: a facet of a region boundary has coefficient $n")
          if keepCoefficient(n + 2) then
            kept(count) = intSums.keyAt(j)
            count += 1
        j += 1
      // Ascending key is the cubes' order (`cubeOrdering` is the doubled grid's row-major order).
      Parallel.sortInts(kept, 0, count)
      val keys = java.util.Arrays.copyOf(kept, count)
      val coefficients = new Array[AnyRef](count)
      var i = 0
      while i < count do
        coefficients(i) = coefficientBox(intSums.sumOf(keys(i)) + 2)
        i += 1
      intSums.clear()
      Chain.packed(keys, coefficients, decoder)

    def regionBoundary(uf: UnitSignedUnionFind, root: Int, flip: Int): Chain[Cube, CoefficientT] = cubes match
      case Some(decoder) => packedRegionBoundary(uf, root, flip, decoder)
      case None          => heapRegionBoundary(uf, root, flip)

    // Union-find over `0 to numTop` (numTop itself = infinityId). A root is always the OLDEST -- i.e. largest-value --
    // member of its component, so its value is the component's birth. Signs live in `uf`; a dying region is assembled
    // from it only for a bar that is reported (see SignedUnionFind).
    val uf = UnitSignedUnionFind(numTop + 1)
    val bars = mutable.ArrayBuffer.empty[PersistenceBar[Double, Chain[Cube, CoefficientT]]]

    // Which side of a merge is elder: `∞`, then a component holding a masked pixel (its root: a root is its component's
    // eldest member), then by birth.
    def seniority(root: Int): Int = if root == infinityId then 2 else if pixelRank(root) == masked then 1 else 0

    var rank = m
    var k = 0
    while k < numFacets do
      while bucketEnd(rank) <= k do rank -= 1
      val code = sorted(k)
      val side = code       % 3
      val axis = (code / 3) % d
      val p = code / 3 / d
      // `a` and `b` as the facet lists them: the lower pixel first, `∞` last.
      val a = if side == 0 then p - pstride(axis) else p
      val b = if side == 0 then p else infinityId
      val v = if rank == masked then Double.NaN else distinct(rank)
      val ra = uf.find(a)
      val rb = uf.find(b)
      if ra != rb then
        // `infinityId` is the unconditional elder of any merge it takes part in, and a masked component of any merge
        // with a real one: a real top cell can also have the value +Infinity and tie against either, so they are
        // special-cased rather than left to the birth comparison.
        val sa = seniority(ra)
        val sb = seniority(rb)
        val aIsYounger =
          if sa != sb then sa < sb
          else if sa == 1 then true // two masked regions, both there from the start: the facet's lower side dies
          else birthOf(ra) <= birthOf(rb)
        val youngRoot = if aIsYounger then ra else rb
        val oldRoot = if aIsYounger then rb else ra
        val youngTopId = if aIsYounger then a else b
        val oldTopId = if aIsYounger then b else a
        require(youngTopId != infinityId, "an engine bug: the infinity dual vertex was treated as the younger side")
        // The coefficient of the facet in `top`'s boundary: the facet is `top`'s upper face if `top` lies below it.
        def coeffToward(top: Int): Int =
          val below = side match
            case 0 => top == a
            case 1 => false
            case _ => true
          if below then upperSign(axis) else -upperSign(axis)
        // Solve flip * orient(young) * coeff(young) + orient(old) * coeff(old) = 0; every factor is +-1. Test the
        // RESOLVED root: `oldTopId` can be a real cell already merged into infinity's component, which is never summed.
        val flip: Int =
          if oldRoot == infinityId then 1
          else
            -(uf.orientation(oldTopId) * coeffToward(oldTopId) * uf.orientation(youngTopId) * coeffToward(youngTopId))
        // A facet outside the complex joins masked regions (and `∞`) before anything enters: no class. A masked region
        // that dies at a facet in the complex is a class born there that never dies, so never a zero-length one.
        if rank != masked then
          if seniority(youngRoot) == 1 then
            bars += new PersistenceBar(
              d - 1,
              endpoint(true)(v),
              PositiveInfinity(),
              Option.when(representatives)(regionBoundary(uf, youngRoot, flip))
            )
          else if includeZeroLength || v != birthOf(youngRoot) then
            bars += new PersistenceBar(
              d - 1,
              endpoint(true)(v),
              endpoint(false)(birthOf(youngRoot)),
              Option.when(representatives)(regionBoundary(uf, youngRoot, flip))
            )
        uf.union(youngRoot, oldRoot, flip)
      k += 1
    bars.toList

/** Integer sums keyed by a facet's encoding, for one region boundary at a time: open addressing over primitive keys,
  * reused from one region to the next.
  */
// File-private scratch of one method (`computeDualTopDimension`), with no meaning outside it.
private final class FacetSums:
  private var keys: Array[Long] = Array.fill(64)(-1L)
  private var sums: Array[Int] = new Array[Int](64)
  // The occupied slots, in insertion order.
  private var used: Array[Int] = new Array[Int](32)
  private var count: Int = 0

  private def slot(key: Long, ks: Array[Long]): Int =
    val mask = ks.length - 1
    val h = key * 0x9e3779b97f4a7c15L
    var s = (h ^ (h >>> 32)).toInt & mask
    while ks(s) != -1L && ks(s) != key do s = (s + 1) & mask
    s

  def add(key: Long, n: Int): Unit =
    if 2 * (count + 1) > keys.length then grow()
    val s = slot(key, keys)
    if keys(s) == -1L then
      keys(s) = key
      sums(s) = n
      if count == used.length then used = java.util.Arrays.copyOf(used, 2 * count)
      used(count) = s
      count += 1
    else sums(s) += n

  /** The number of keys, which `keyAt` and `sumAt` index in the order they first came. */
  def size: Int = count
  def keyAt(i: Int): Long = keys(used(i))
  def sumAt(i: Int): Int = sums(used(i))

  def clear(): Unit =
    var i = 0
    while i < count do
      keys(used(i)) = -1L
      i += 1
    count = 0

  private def grow(): Unit =
    val oldKeys = keys
    val oldSums = sums
    keys = Array.fill(oldKeys.length * 2)(-1L)
    sums = new Array[Int](oldKeys.length * 2)
    var i = 0
    while i < count do
      val s = slot(oldKeys(used(i)), keys)
      keys(s) = oldKeys(used(i))
      sums(s) = oldSums(used(i))
      used(i) = s
      i += 1

/** [[FacetSums]] for keys that are `Int`s (doubled-grid indices, so never negative): the packed representatives' case.
  */
// File-private scratch of one method (`computeDualTopDimension`), with no meaning outside it.
private final class IntFacetSums:
  private var keys: Array[Int] = Array.fill(64)(-1)
  private var sums: Array[Int] = new Array[Int](64)
  // The occupied slots, in insertion order.
  private var used: Array[Int] = new Array[Int](32)
  private var count: Int = 0

  private def slot(key: Int, ks: Array[Int]): Int =
    val mask = ks.length - 1
    val h = key * 0x9e3779b9
    var s = (h ^ (h >>> 16)) & mask
    while ks(s) != -1 && ks(s) != key do s = (s + 1) & mask
    s

  def add(key: Int, n: Int): Unit =
    if 2 * (count + 1) > keys.length then grow()
    val s = slot(key, keys)
    if keys(s) == -1 then
      keys(s) = key
      sums(s) = n
      if count == used.length then used = java.util.Arrays.copyOf(used, 2 * count)
      used(count) = s
      count += 1
    else sums(s) += n

  /** The number of keys, which `keyAt` and `sumAt` index in the order they first came. */
  def size: Int = count
  def keyAt(i: Int): Int = keys(used(i))
  def sumAt(i: Int): Int = sums(used(i))

  /** The sum of a key that was added. */
  def sumOf(key: Int): Int = sums(slot(key, keys))

  def clear(): Unit =
    var i = 0
    while i < count do
      keys(used(i)) = -1
      i += 1
    count = 0

  private def grow(): Unit =
    val oldKeys = keys
    val oldSums = sums
    keys = Array.fill(oldKeys.length * 2)(-1)
    sums = new Array[Int](oldKeys.length * 2)
    var i = 0
    while i < count do
      val s = slot(oldKeys(used(i)), keys)
      keys(s) = oldKeys(used(i))
      sums(s) = oldSums(used(i))
      used(i) = s
      i += 1

// `Vector.tabulate`'s builder left a 32-slot array and one box per coordinate behind every cube: at 2048² noise the
// representatives hold 18.5M facets (.claude/WORKLOG-cubical-performance.md).
/** Cubes of one grid built cheaply: a `Vector[Int]` holds its coordinates boxed, so every coordinate's box is made once
  * per grid (doubled coordinates run up to `2 shape(i)`), and the vector is made directly at its size. The result is an
  * ordinary `Vector[Int]`, equal to and hashing like `Vector.tabulate`'s; a cube costs a vector and an array.
  */
open class CubeBoxes(shape: Array[Int]):
  private val boxes: Array[AnyRef] = Array.tabulate(2 * shape.max + 1)(i => Integer.valueOf(i))

  /** The cube with doubled coordinates `coordinate(0 until d)`. */
  inline def cube(inline coordinate: Int => Int): Cube =
    val a = new Array[AnyRef](shape.length)
    var i = 0
    while i < a.length do
      a(i) = boxes(coordinate(i))
      i += 1
    // `Vector.from` keeps an `ArraySeq.ofRef[AnyRef]` of at most 32 elements as the vector's own array.
    Cube.fromVector(Vector.from(ArraySeq.unsafeWrapArray(a)).asInstanceOf[Vector[Int]])
