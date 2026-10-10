package org.appliedtopology.tda4j

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

/** Persistent homology of a cubical grid by union-find instead of matrix reduction (after Le Breton, Szustakowski and
  * Piraud, arXiv:2606.04801, extended here to any coefficient field and to representatives): degree 0 by union-find on
  * the vertices and edges, the top degree `d - 1` (`d` the grid's dimension) by union-find on the dual graph. In 2-D
  * those cover everything; in dimension 3 and up the degrees in between are computed by the cohomology engine on the
  * grid without its top cells. Requires `d >= 2`, and refuses an image with a NaN value.
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
open class FastCubicalHomologyEngine[CoefficientT: Field]:
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
    representatives: Boolean
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    require(
      stream.ambientDim >= 2,
      s"FastCubicalHomologyEngine requires ambient dimension >= 2, got ${stream.ambientDim}"
    )
    val grid = GridRanks(stream.shape, stream.topCellValues)
    // Representatives are packed on every grid whose cells have Int indices (every grid that fits in memory); one
    // decoder per run, shared by all of them.
    val cubes = Option.when(GridCubes.fits(grid.shape))(GridCubes(grid.shape))
    val bars =
      if stream.ambientDim == 2 then
        computeH0(grid, includeZeroLength, cubes) ++
          computeDualTopDimension(grid, includeZeroLength, representatives, cubes)
      else
        computeMiddleDimensions(stream, grid, includeZeroLength) ++
          computeDualTopDimension(grid, includeZeroLength, representatives, cubes)
    bars

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
    includeZeroLength: Boolean
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    new PackedCubicalCohomologyEngine[CoefficientT](stream, stream.ambientDim - 1, grid)
      .persistentHomology(includeZeroLength)
      .filter(_.dim <= stream.ambientDim - 2)

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
    val vdims = shape.map(_ + 1)
    val vstride = GridRanks.strides(vdims)
    val numVertices = GridRanks.checkedProduct(vdims, "FastCubicalHomologyEngine")
    require(d.toLong * numVertices <= Int.MaxValue, s"FastCubicalHomologyEngine: ${d.toLong * numVertices} edge slots")

    // Ranks over the vertex grid: a vertex takes the smallest rank of the pixels whose closure contains it, an edge
    // along `axis` (at its lower vertex) the smallest over the pixels containing it. Separable: put each pixel's rank at
    // its lower corner, then along each axis take the minimum with the lower neighbour -- along every axis for a
    // vertex, every axis but `axis` for an edge.
    def embedded(): Array[Int] =
      val a = Array.fill(numVertices)(Int.MaxValue)
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
    foreachEdgeDescending((axis, v) => bucketEnd(edgeRank(axis)(v) + 1) += 1)
    var r = 1
    while r <= distinct.length do
      bucketEnd(r) += bucketEnd(r - 1)
      r += 1
    val numEdges = bucketEnd(distinct.length)
    val sorted = new Array[Int](numEdges)
    foreachEdgeDescending { (axis, v) =>
      val rank = edgeRank(axis)(v)
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
        val (youngRoot, oldRoot) =
          if distinct(vertexRank(r0)) <= distinct(vertexRank(r1)) then (r1, r0) else (r0, r1)
        parent(youngRoot) = oldRoot
        val (birth, death) = (distinct(vertexRank(youngRoot)), distinct(rank))
        if includeZeroLength || birth != death then
          bars += new PersistenceBar(
            0,
            endpoint(true)(birth),
            endpoint(false)(death),
            Some(vertexChain(youngRoot))
          )
      k += 1
    // Every vertex and edge of the grid is in, so one component is left: the one essential class.
    val root = find(0)
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
    val bucketEnd = new Array[Int](m)
    foreachFacetAscending((_, rank) => bucketEnd(rank) += 1)
    var acc = 0
    var r = m - 1
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
    def regionBoundary(uf: UnitSignedUnionFind, root: Int, flip: Int): Chain[Cube, CoefficientT] =
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
      cubes match
        case Some(decoder) =>
          // Key in the high half, sum in the low: sorted by key, which is the cubes' order (`cubeOrdering` is the
          // doubled grid's row-major order).
          val sorted = new Array[Long](sums.size)
          var count = 0
          var j = 0
          while j < sums.size do
            val n = sums.sumAt(j)
            if n != 0 then
              if n < -2 || n > 2 then
                throw new IllegalStateException(s"an engine bug: a facet of a region boundary has coefficient $n")
              if keepCoefficient(n + 2) then
                sorted(count) = (sums.keyAt(j) << 32) | (n & 0xffffffffL)
                count += 1
            j += 1
          sums.clear()
          java.util.Arrays.sort(sorted, 0, count)
          val keys = new Array[Int](count)
          val coefficients = new Array[AnyRef](count)
          var i = 0
          while i < count do
            keys(i) = (sorted(i) >>> 32).toInt
            coefficients(i) = coefficientBox(sorted(i).toInt + 2)
            i += 1
          Chain.packed(keys, coefficients, decoder)
        case None =>
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

    // Union-find over `0 to numTop` (numTop itself = infinityId). A root is always the OLDEST -- i.e. largest-value --
    // member of its component, so its value is the component's birth. Signs live in `uf`; a dying region is assembled
    // from it only for a bar that is reported (see SignedUnionFind).
    val uf = UnitSignedUnionFind(numTop + 1)
    val bars = mutable.ArrayBuffer.empty[PersistenceBar[Double, Chain[Cube, CoefficientT]]]

    var rank = m - 1
    var k = 0
    while k < numFacets do
      while bucketEnd(rank) <= k do rank -= 1
      val code = sorted(k)
      val side = code       % 3
      val axis = (code / 3) % d
      val p = code / 3 / d
      // `a` and `b` as the facet lists them: the lower pixel first, `∞` last.
      val (a, b) = side match
        case 0 => (p - pstride(axis), p)
        case _ => (p, infinityId)
      val v = distinct(rank)
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
        if includeZeroLength || v != birthOf(youngRoot) then
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
