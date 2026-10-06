package org.appliedtopology.tda4j

import scala.collection.mutable
import scala.compiletime.asMatchable

// The diameter of `sigma` plus vertex `v`: max of sigma's diameter and the distances from `v` to sigma's vertices
// (Ripser's cofacet recurrence, O(d)). `vertices` is sigma's vertex array, decoded once per enumeration by the caller.
private[tda4j] def insertionDiameter(
  metricSpace: FiniteMetricSpace[Int],
  vertices: Array[Int],
  sigmaFv: Double,
  v: Int
): Double =
  var maxD = sigmaFv
  var i = 0
  while i < vertices.length do
    val d = metricSpace.distance(vertices(i), v)
    if d > maxD then maxD = d
    i += 1
  maxD

/** Persistent cohomology of the Vietoris-Rips complex of a metric space by Bauer's Ripser algorithm (arXiv:1908.02518):
  * the fast engine for Vietoris-Rips, and the MATLAB/CLI default there. Cells are packed as (diameter, combinatorial
  * index) pairs ([[DiameterIndex]], Ripser's `diameter_index_t`); clearing, apparent pairs and the enclosing-radius
  * cutoff as in Ripser.
  *
  * Reports degrees `0 .. maxDimension`, each bar with its representative cocycle over `DiameterIndex` cells: decode a
  * cell of a degree-`k` bar with `si.decodeToArray(cell.index, k + 1)`. `maxFiltrationValue` defaults to the minimum
  * enclosing radius, past which nothing new is born. `useApparentPairs = false` turns off the apparent-pair shortcut,
  * which changes no output (for benchmarking).
  *
  * Under a threshold that keeps few of the pairs (a large point cloud with a small `maxFiltrationValue`), cofacets are
  * found from each vertex's list of neighbours within the threshold instead of by trying every vertex, as Ripser does
  * with a sparse distance matrix. The lists are built when at most a quarter of all pairs are within the threshold
  * (`neighbourLists = Some(true)` or `Some(false)` decides it instead); the output is the same either way.
  */
class PackedRipserCohomologyEngine[CoefficientT: Field](
  metricSpace: FiniteMetricSpace[Int],
  maxDimension: Int,
  useApparentPairs: Boolean = true,
  // None: the minimum enclosing radius.
  maxFiltrationValue: Option[Double] = None,
  // None: decided by how many pairs are within the threshold.
  neighbourLists: Option[Boolean] = None
):

  private val resolvedMaxFiltrationValue: Double =
    maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

  /** The combinatorial number system of this metric space's simplices, for decoding the cells of returned chains. */
  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)

  // A simplex must get one diameter whichever path computes it: the heap working column combines equal cells only if
  // they carry the same diameter, and otherwise grows without end. Euclidean distances are exactly symmetric and
  // `ExplicitMetricSpace` reads one triangle, so those are read as given; any other metric is read as d(max, min),
  // the lower triangle, like `ExplicitMetricSpace`.
  // Reading every metric that way cost ~15% on o3_1024: the row-wise walk of the distance cache is lost
  // (`.claude/WORKLOG-vr-working-column.md`).
  // Called through `FiniteMetricSpace[Int]`, `distance` erases to (Object, Object) and boxes both vertices on every
  // call; the two concrete spaces are called through their own type, which takes `Int`s.
  private val euclidean: EuclideanMetricSpace = metricSpace match
    case e: EuclideanMetricSpace => e
    case _                       => null
  private val explicitMatrix: ExplicitMetricSpace = metricSpace match
    case e: ExplicitMetricSpace => e
    case _                      => null
  private inline def distance(x: Int, y: Int): Double =
    if euclidean != null then euclidean.distance(x, y)
    else if explicitMatrix != null then explicitMatrix.distance(x, y)
    else metricSpace.distance(math.max(x, y), math.min(x, y))

  // Each vertex's neighbours within the threshold, when they are few enough to pay: at most a quarter of all ordered
  // pairs (and at most 2^28 entries, 3 GB), so a dense threshold keeps the cheaper all-vertex scan. Every distance is
  // the one `distance` above returns, so cofacet diameters are bit-identical to the scan's.
  private val lists: Option[NeighbourLists] =
    val n = metricSpace.size
    val dense = n.toLong * (n - 1)
    neighbourLists match
      case Some(false) => None
      case Some(true)  => NeighbourLists.within(n, resolvedMaxFiltrationValue, Long.MaxValue)(distance(_, _))
      case None        =>
        NeighbourLists.within(n, resolvedMaxFiltrationValue, math.min(dense / 4, 1L << 28))(distance(_, _))

  /** Whether cofacets come from neighbour lists (diagnostics and tests). */
  private[tda4j] def usesNeighbourLists: Boolean = lists.isDefined

  // Every cofacet of `sigma` (with `size` vertices) within the threshold, vertex and index strictly decreasing, as
  // (diameter, index, number of sigma's vertices below the added one); `f` returns whether to go on. Only the canonical
  // ones (added vertex above sigma's largest) when `allCofacets` is false.
  private inline def eachCofacet(sigma: DiameterIndex, size: Int, allCofacets: Boolean)(
    inline f: (Double, Long, Int) => Boolean
  ): Unit =
    val vertices = si.decodeToArray(sigma.index, size)
    lists match
      case Some(nl) =>
        val cur = si.sparseCofacetCursor(sigma.index, vertices, allCofacets, nl)
        var go = true
        while go && cur.hasNext do
          val tauFv = if cur.maxDistance > sigma.diameter then cur.maxDistance else sigma.diameter
          go = f(tauFv, cur.index, cur.position)
          cur.advance()
      case None =>
        val cur = si.cofacetCursor(sigma.index, size, allCofacets)
        var go = true
        while go && cur.hasNext do
          val tauFv = cofacetDiameter(vertices, sigma.diameter, cur.vertex)
          if tauFv <= resolvedMaxFiltrationValue then
            // `vertices` is sorted ascending: a plain scan counts the entries below `cur.vertex`.
            var position = 0
            while position < vertices.length && vertices(position) < cur.vertex do position += 1
            go = f(tauFv, cur.index, position)
          cur.advance()

  /** `insertionDiameter` with every distance read through `distance` above. */
  private def cofacetDiameter(vertices: Array[Int], sigmaFv: Double, v: Int): Double =
    var maxD = sigmaFv
    var i = 0
    while i < vertices.length do
      val d = distance(vertices(i), v)
      if d > maxD then maxD = d
      i += 1
    maxD
  private val fr = summon[CoefficientT is Field]

  /** A simplex as its (diameter, combinatorial index): the cell type of the returned chains. Equality and hash use the
    * index alone, so the same simplex reached by different floating-point paths is one key.
    */
  final case class DiameterIndex(diameter: Double, index: Long):
    // `.asMatchable` only satisfies the Matchable check on an `Any` selector; no runtime cost.
    override def equals(other: Any): Boolean = other.asMatchable match
      case that: DiameterIndex => this.index == that.index
      case _                   => false
    override def hashCode(): Int = index.hashCode()

  // Ascending by diameter; on a tie the larger index is older (as RipserCohomologyEngine.cohomologyOrdering).
  // Same-index pairs compare equal before the diameter is looked at, so this agrees with `DiameterIndex.equals`
  // (index only) even when a non-symmetric `distance` gives one index two diameters; otherwise `basis` (keyed by
  // equals) and a chain's TreeMap (keyed by this order) disagree and `Chain.reduceLoop` never terminates
  // (WORKLOG-o3-1024-fractal-r-session-2026-09-25.md).
  private def compareDiamThenIndex(x: DiameterIndex, y: DiameterIndex): Int =
    if x.index == y.index then 0
    else
      val fc = java.lang.Double.compare(x.diameter, y.diameter)
      if fc != 0 then fc else java.lang.Long.compare(y.index, x.index)

  given packedOrdering: Ordering[DiameterIndex] = compareDiamThenIndex(_, _)

  /** A reduced column: its pivot first, with its combined non-zero coefficient; the other terms in no particular order
    * and not combined (equal cells may repeat, sums may cancel). Adding it to a working column combines them there.
    */
  private final class Column(val diameters: Array[Double], val indices: Array[Long], val coefficients: Array[Any]):
    def leadingCoefficient: CoefficientT = coefficients(0).asInstanceOf[CoefficientT]
    def isEmpty: Boolean = indices.isEmpty

  // Replaces `Chain.reduceBy`'s `TreeMap` working column, whose lookups were most of the engine's time
  // (`.claude/WORKLOG-vr-working-column.md`); `PackedWorkingColumnSpec` holds it to the old reduction term for term.
  /** The column being reduced, as Ripser keeps it: a binary min-heap of (diameter, index, coefficient) entries in
    * primitive arrays. Adding a column only pushes its entries; equal cells are combined lazily, when they reach the
    * top. Ordered by `packedOrdering` (diameter ascending, then the larger index first) for the cohomology reduction,
    * by its reverse for the cycles. Equal indices meet at the top together because a simplex has one diameter (every
    * distance is read in one order, `distance`).
    */
  private final class WorkingColumn(youngestFirst: Boolean):
    private var diam = new Array[Double](64)
    private var idx = new Array[Long](64)
    private var coef = new Array[Any](64)
    private var size = 0
    // The current pivot, valid after `pivot()` returned true.
    var pivotDiameter: Double = 0.0
    var pivotIndex: Long = 0L
    var pivotCoefficient: CoefficientT = fr.zero

    private def less(i: Int, j: Int): Boolean =
      val c = java.lang.Double.compare(diam(i), diam(j))
      if youngestFirst then if c != 0 then c > 0 else idx(i) < idx(j)
      else if c != 0 then c < 0
      else idx(i) > idx(j)
    private def swap(i: Int, j: Int): Unit =
      val d = diam(i); diam(i) = diam(j); diam(j) = d
      val x = idx(i); idx(i) = idx(j); idx(j) = x
      val c = coef(i); coef(i) = coef(j); coef(j) = c

    def push(d: Double, i: Long, c: CoefficientT): Unit =
      if size == diam.length then
        diam = java.util.Arrays.copyOf(diam, 2 * size)
        idx = java.util.Arrays.copyOf(idx, 2 * size)
        coef = java.util.Arrays.copyOf(coef.asInstanceOf[Array[AnyRef]], 2 * size).asInstanceOf[Array[Any]]
      diam(size) = d; idx(size) = i; coef(size) = c
      var k = size
      size += 1
      while k > 0 && less(k, (k - 1) / 2) do
        swap(k, (k - 1) / 2)
        k = (k - 1) / 2

    private def popTop(): Unit =
      size -= 1
      if size > 0 then
        diam(0) = diam(size); idx(0) = idx(size); coef(0) = coef(size)
        var k = 0
        var done = false
        while !done do
          val l = 2 * k + 1
          val r = l + 1
          var m = k
          if l < size && less(l, m) then m = l
          if r < size && less(r, m) then m = r
          if m == k then done = true
          else
            swap(k, m)
            k = m

    /** Finds the leading term, combining equal cells and dropping those that cancel; false if the column is zero. The
      * pivot stays in the column.
      */
    def pivot(): Boolean =
      var found = false
      while !found && size > 0 do
        val d = diam(0)
        val i = idx(0)
        var sum = coef(0).asInstanceOf[CoefficientT]
        popTop()
        while size > 0 && idx(0) == i do
          sum = fr.plus(sum, coef(0).asInstanceOf[CoefficientT])
          popTop()
        if !fr.isEqual(sum, fr.zero) then
          push(d, i, sum)
          pivotDiameter = d
          pivotIndex = i
          pivotCoefficient = sum
          found = true
      found

    def addScaled(column: Column, factor: CoefficientT): Unit =
      var k = 0
      while k < column.indices.length do
        push(
          column.diameters(k),
          column.indices(k),
          fr.times(factor, column.coefficients(k).asInstanceOf[CoefficientT])
        )
        k += 1

    /** Empties the column into a reduced column: empty if it is zero, else the pivot (combined) first, the rest as they
      * lie in the heap. No sorting: the order of the other terms never matters.
      */
    def drain(): Column =
      if !pivot() then Column(Array.empty, Array.empty, Array.empty)
      else
        // `pivot()` left the combined pivot at the top of the heap, slot 0.
        val column = Column(
          java.util.Arrays.copyOf(diam, size),
          java.util.Arrays.copyOf(idx, size),
          java.util.Arrays.copyOf(coef.asInstanceOf[Array[AnyRef]], size).asInstanceOf[Array[Any]]
        )
        size = 0
        column

  // The diameter of a decoded vertex array, O(d^2): for facets, where removing a vertex has no cheap update rule.
  private def maxPairwiseDistance(vertices: Array[Int]): Double =
    var maxD = 0.0
    var i = 0
    while i < vertices.length do
      var j = i + 1
      while j < vertices.length do
        val d = distance(vertices(i), vertices(j))
        if d > maxD then maxD = d
        j += 1
      i += 1
    maxD

  /** The coboundary of `sigma` (with `size` vertices) in the complex truncated at `maxFiltrationValue`, as in
    * [[RipserCohomologyEngine.coboundaryOf]]. Defined up to dimension `maxDimension`.
    */
  def coboundaryOf(sigma: DiameterIndex, size: Int): Chain[DiameterIndex, CoefficientT] =
    val buffer = mutable.ArrayBuffer.empty[(DiameterIndex, CoefficientT)]
    forEachCofacet(sigma, size)((diameter, index, sign) => buffer += ((DiameterIndex(diameter, index), sign)))
    Chain.from(buffer.toSeq)

  /** Each cofacet of `sigma` up to the threshold, as (diameter, index, sign); inlined, so no term is allocated. */
  private inline def forEachCofacet(sigma: DiameterIndex, size: Int)(
    inline f: (Double, Long, CoefficientT) => Unit
  ): Unit =
    if size - 1 <= maxDimension then
      eachCofacet(sigma, size, allCofacets = true) { (tauFv, index, position) =>
        f(tauFv, index, if position % 2 == 0 then fr.one else fr.negate(fr.one))
        true
      }

  // The oldest same-diameter cofacet (largest index): the cursor's index decreases strictly, so the first tie is it.
  private def zeroPivotCofacet(sigma: DiameterIndex, size: Int): Option[DiameterIndex] =
    if size - 1 > maxDimension then None
    else
      var found: Option[DiameterIndex] = None
      eachCofacet(sigma, size, allCofacets = true) { (tauFv, index, _) =>
        if tauFv == sigma.diameter then found = Some(DiameterIndex(sigma.diameter, index))
        found.isEmpty
      }
      found

  // The youngest same-diameter facet (smallest index): the facet cursor's index increases strictly, so the first tie is
  // it. Facet diameters are recomputed from the vertex array.
  private def zeroPivotFacet(tau: DiameterIndex, size: Int): Option[DiameterIndex] =
    val tauVertices = si.decodeToArray(tau.index, size)
    val candidate = new Array[Int](size - 1)
    val cur = si.facetCursor(tau.index, size)
    while cur.hasNext do
      var w = 0
      var r = 0
      while w < tauVertices.length do
        if tauVertices(w) != cur.vertex then
          candidate(r) = tauVertices(w)
          r += 1
        w += 1
      val fv = maxPairwiseDistance(candidate)
      if fv == tau.diameter then return Some(DiameterIndex(tau.diameter, cur.index))
      cur.advance()
    None

  private def zeroApparentCofacet(sigma: DiameterIndex, size: Int): Option[DiameterIndex] =
    for
      tau <- zeroPivotCofacet(sigma, size)
      partner <- zeroPivotFacet(tau, size + 1)
      if partner == sigma
    yield tau

  private def zeroApparentFacet(tau: DiameterIndex, size: Int): Option[DiameterIndex] =
    for
      sigma <- zeroPivotFacet(tau, size)
      partner <- zeroPivotCofacet(sigma, size - 1)
      if partner == tau
    yield sigma

  private var _substitutionCount: Int = 0
  def substitutionCount: Int = _substitutionCount

  private var _totalSimplexCount: Int = 0
  def totalSimplexCount: Int = _totalSimplexCount

  private var _apparentPairCount: Int = 0

  /** How many simplices the last run paired directly as apparent pairs, without reducing their coboundary. */
  // Distinct from `substitutionCount`, which counts the lazy fallback when another column needs an apparent pair's
  // column as a pivot (WORKLOG-o3-1024-fractal-r-session-2026-09-25.md).
  def apparentPairCount: Int = _apparentPairCount

  /** Every bar of degree `0 .. maxDimension`, each with its representative cocycle (over [[DiameterIndex]] cells;
    * decode with `si`); zero-length bars only if `includeZeroLength`.
    */
  def persistentCohomology(
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[Double, Chain[DiameterIndex, CoefficientT]]] =
    val p = pairing(cocycles = true, zeroLengthCocycles = includeZeroLength)
    List.tabulate(p.size)(identity).collect { case k if includeZeroLength || !p.isZeroLength(k) => p.bar(k) }

  /** The same bars with '''cycles''' as representatives (over [[DiameterIndex]] cells): the pairing comes from the
    * cohomology computation, and only the boundary columns of the death simplices are reduced ([[Involution]]).
    */
  def persistentHomology(
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[Double, Chain[DiameterIndex, CoefficientT]]] =
    val rows = pairing(cocycles = false, zeroLengthCocycles = false)
    val cycles = involutedCycles(rows, k => includeZeroLength || !rows.isZeroLength(k))
    List.tabulate(rows.size)(identity).collect {
      case k if includeZeroLength || !rows.isZeroLength(k) =>
        val bar = rows.bar(k)
        new PersistenceBar(bar.dim, bar.lower, bar.upper, Some(cycles(k.toLong)))
    }

  /** Pushes `boundaryOf(tau, dim)` onto `working` without building it: the facets come from the facet cursor, the
    * diameters from the same `maxPairwiseDistance`, and removing the vertex at (ascending) position `k` has sign
    * `(-1)^k`.
    */
  private def pushBoundary(tau: DiameterIndex, dim: Int, working: WorkingColumn): Unit =
    if dim > 0 then
      val size = dim + 1
      val vertices = si.decodeToArray(tau.index, size)
      val facet = new Array[Int](dim)
      val cur = si.facetCursor(tau.index, size)
      var k = size - 1
      while cur.hasNext do
        var w = 0
        var r = 0
        while w < size do
          if w != k then
            facet(r) = vertices(w)
            r += 1
          w += 1
        working.push(maxPairwiseDistance(facet), cur.index, if k % 2 == 0 then fr.one else fr.negate(fr.one))
        k -= 1
        cur.advance()

  /** A reduction's V-column, `seed - Σ c V(pivot)` over its `log`, from the V-columns of earlier columns: each is
    * recorded as its seed and log (keyed by pivot index) and expanded only when needed, depth-first without recursion,
    * into `memo`. Equal to the eager fold over V-columns built for every column: the field is exact.
    */
  private def expandV(
    seed: DiameterIndex,
    log: Array[(DiameterIndex, CoefficientT)],
    seedOf: Long => DiameterIndex,
    logOf: Long => Array[(DiameterIndex, CoefficientT)],
    memo: mutable.LongMap[Chain[DiameterIndex, CoefficientT]]
  ): Chain[DiameterIndex, CoefficientT] =
    val chainRM = summon[Chain[DiameterIndex, CoefficientT] is RingModule]
    import chainRM.*
    def fold(seed: DiameterIndex, log: Array[(DiameterIndex, CoefficientT)]) =
      val v = log.foldLeft(Chain(seed)) { case (acc, (pivot, coeff)) => acc - coeff ⊠ memo(pivot.index) }
      v.collapseAll()
      v
    val stack = mutable.Stack.empty[Long]
    log.foreach((pivot, _) => stack.push(pivot.index))
    while stack.nonEmpty do
      val s = stack.top
      if memo.contains(s) then stack.pop()
      else
        val sLog = logOf(s)
        val missing = sLog.iterator.map(_._1.index).filterNot(memo.contains).toList
        if missing.nonEmpty then missing.foreach(stack.push)
        else
          memo(s) = fold(seedOf(s), sLog)
          stack.pop()
    fold(seed, log)

  // `Involution.cycles` specialised to packed cells; must return exactly what it returns (`PackedWorkingColumnSpec`).
  /** One cycle per `reported` row, as [[Involution.cycles]] computes them under `packedOrdering.reverse`: death columns
    * reduced on a heap working column, oldest death first per dimension; a finite bar's cycle is its reduced column.
    * Every death column is reduced and every pivot checked, zero-length ones included (they are pivots for the others),
    * but an apparent pair's column is not kept: its boundary is already reduced, so it is rebuilt from the death
    * simplex when another column needs it. The V-columns are needed only for essential bars, so only non-empty
    * reduction logs are kept, and a V-column is expanded when an essential bar refers to it.
    */
  private def involutedCycles(
    rows: Pairing,
    reported: Int => Boolean
  ): mutable.LongMap[Chain[DiameterIndex, CoefficientT]] =
    val result = mutable.LongMap.empty[Chain[DiameterIndex, CoefficientT]]
    val working = WorkingColumn(youngestFirst = true)
    val rebuilt = WorkingColumn(youngestFirst = true)
    val noLog: Array[(DiameterIndex, CoefficientT)] = Array.empty
    // Per death dimension, keyed by the birth (pivot) index: the row it closes, the reduced column unless the row is
    // an apparent pair, the reduction log when it is not empty.
    val rowByDim = mutable.Map.empty[Int, LongIntMap]
    val basisByDim = mutable.Map.empty[Int, mutable.LongMap[Column]]
    val logByDim = mutable.Map.empty[Int, mutable.LongMap[Array[(DiameterIndex, CoefficientT)]]]
    val vByDim = mutable.Map.empty[Int, mutable.LongMap[Chain[DiameterIndex, CoefficientT]]]

    def columnOf(pivot: Long, deathDim: Int): Option[Column] =
      basisByDim.getOrElseUpdate(deathDim, mutable.LongMap.empty).get(pivot).orElse {
        val byBirth = rowByDim.getOrElseUpdate(deathDim, LongIntMap())
        Option.when(byBirth.contains(pivot) && rows.apparent(byBirth(pivot))) {
          val row = byBirth(pivot)
          pushBoundary(DiameterIndex(rows.deathD(row), rows.deathI(row)), deathDim, rebuilt)
          rebuilt.drain()
        }
      }

    def reduce(seed: DiameterIndex, deathDim: Int): Array[(DiameterIndex, CoefficientT)] =
      pushBoundary(seed, deathDim, working)
      val log = mutable.ArrayBuffer.empty[(DiameterIndex, CoefficientT)]
      var reducing = working.pivot()
      while reducing do
        columnOf(working.pivotIndex, deathDim) match
          case None         => reducing = false
          case Some(column) =>
            val redCoeff = fr.divide(working.pivotCoefficient, column.leadingCoefficient)
            working.addScaled(column, fr.negate(redCoeff))
            log += ((DiameterIndex(working.pivotDiameter, working.pivotIndex), redCoeff))
            reducing = working.pivot()
      if log.isEmpty then noLog else log.toArray

    // Finite rows by death dimension, each dimension's in packedOrdering of the death cell (oldest death first).
    val dimsPresent = (0 until rows.size).iterator.filter(rows.deathI(_) >= 0).map(rows.dims(_) + 1).toSet.toSeq.sorted
    for deathDim <- dimsPresent do
      val finite =
        val b = mutable.ArrayBuilder.make[Int]
        var k = 0
        while k < rows.size do
          if rows.deathI(k) >= 0 && rows.dims(k) + 1 == deathDim then b += k
          k += 1
        b.result()
      val order = SortIndices.sort(
        finite.length,
        (a, b) =>
          val ra = finite(a)
          val rb = finite(b)
          rows.deathI(ra) != rows.deathI(rb) && {
            val c = java.lang.Double.compare(rows.deathD(ra), rows.deathD(rb))
            if c != 0 then c < 0 else rows.deathI(ra) > rows.deathI(rb)
          }
      )
      val basis = basisByDim.getOrElseUpdate(deathDim, mutable.LongMap.empty)
      val byBirth = LongIntMap(finite.length)
      rowByDim(deathDim) = byBirth
      val logs = logByDim.getOrElseUpdate(deathDim, mutable.LongMap.empty)
      for o <- order do
        val row = finite(o)
        val sigmaIndex = rows.birthI(row)
        val tau = DiameterIndex(rows.deathD(row), rows.deathI(row))
        val log = reduce(tau, deathDim)
        if !working.pivot() || working.pivotIndex != sigmaIndex then
          throw new IllegalStateException(
            s"Involution: the boundary of $tau does not reduce to its paired birth cell ${rows.birth(row)} " +
              "(the pairing and the order disagree)"
          )
        val column = working.drain()
        if !rows.apparent(row) || log.nonEmpty then basis(sigmaIndex) = column
        byBirth(sigmaIndex) = row
        if log.nonEmpty then logs(sigmaIndex) = log
        if reported(row) then
          val cycle = Chain.from(
            column.indices.indices.map(k =>
              (DiameterIndex(column.diameters(k), column.indices(k)), column.coefficients(k).asInstanceOf[CoefficientT])
            )
          )
          cycle.collapseAll()
          result(row.toLong) = cycle

    for row <- 0 until rows.size if rows.deathI(row) < 0 && reported(row) do
      val dim = rows.dims(row)
      val sigma = rows.birth(row)
      result(row.toLong) =
        if dim == 0 then Chain(sigma)
        else
          val log = reduce(sigma, dim)
          if working.pivot() then
            throw new IllegalStateException(
              s"Involution: the boundary of the essential cell $sigma does not reduce to zero"
            )
          val byBirth = rowByDim.getOrElseUpdate(dim, LongIntMap())
          val logs = logByDim.getOrElseUpdate(dim, mutable.LongMap.empty)
          expandV(
            sigma,
            log,
            s => DiameterIndex(rows.deathD(byBirth(s)), rows.deathI(byBirth(s))),
            s =>
              if !byBirth.contains(s) then
                throw new IllegalStateException(s"pivot $s has a basis entry but no V-column")
              logs.getOrElse(s, noLog)
            ,
            vByDim.getOrElseUpdate(dim, mutable.LongMap.empty)
          )
    result

  /** The boundary of a `dim`-simplex: the facet without its `i`-th smallest vertex, with sign `(-1)^i`. */
  private[tda4j] def boundaryOf(tau: DiameterIndex, dim: Int): Seq[(DiameterIndex, CoefficientT)] =
    if dim == 0 then Seq.empty
    else
      val vertices = si.decodeToArray(tau.index, dim + 1).sorted
      vertices.indices.map { i =>
        val facet = vertices.patch(i, Nil, 1)
        val cell = DiameterIndex(maxPairwiseDistance(facet), si(Simplex(facet*)))
        (cell, if i % 2 == 0 then fr.one else fr.negate(fr.one))
      }

  /** The pairing of one run in report order, one row per bar in primitive arrays (a run can pair tens of millions of
    * simplices, nearly all at zero length): `PersistenceBar`s are built only for the bars that are returned.
    */
  private final class Pairing:
    var size: Int = 0
    var dims: Array[Int] = new Array[Int](1024)
    var birthD: Array[Double] = new Array[Double](1024)
    var birthI: Array[Long] = new Array[Long](1024)
    var deathD: Array[Double] = new Array[Double](1024)
    var deathI: Array[Long] = new Array[Long](1024) // -1: essential
    var apparent: Array[Boolean] = new Array[Boolean](1024)
    // Cocycles, by row, only for the rows that asked for one.
    val cocycles: mutable.LongMap[Chain[DiameterIndex, CoefficientT]] = mutable.LongMap.empty

    /** Room for `count` more rows in one allocation (a degree adds at most one row per simplex). */
    def reserve(count: Int): Unit =
      val needed = math.min(size.toLong + count, Int.MaxValue.toLong - 8).toInt
      if needed > dims.length then resize(needed)

    private def resize(grown: Int): Unit =
      dims = java.util.Arrays.copyOf(dims, grown)
      birthD = java.util.Arrays.copyOf(birthD, grown)
      birthI = java.util.Arrays.copyOf(birthI, grown)
      deathD = java.util.Arrays.copyOf(deathD, grown)
      deathI = java.util.Arrays.copyOf(deathI, grown)
      apparent = java.util.Arrays.copyOf(apparent, grown)

    def add(dim: Int, birth: DiameterIndex, deathDiameter: Double, deathIndex: Long, isApparent: Boolean): Int =
      if size == dims.length then resize(math.min(2L * size + 16, Int.MaxValue.toLong - 8).toInt)
      dims(size) = dim
      birthD(size) = birth.diameter
      birthI(size) = birth.index
      deathD(size) = deathDiameter
      deathI(size) = deathIndex
      apparent(size) = isApparent
      size += 1
      size - 1

    def birth(k: Int): DiameterIndex = DiameterIndex(birthD(k), birthI(k))
    def death(k: Int): Option[DiameterIndex] = Option.when(deathI(k) >= 0)(DiameterIndex(deathD(k), deathI(k)))
    // As PersistenceBar.isZeroLength on the bar below (a total-order comparison of the endpoints).
    def isZeroLength(k: Int): Boolean = deathI(k) >= 0 && java.lang.Double.compare(deathD(k), birthD(k)) == 0
    def bar(k: Int): PersistenceBar[Double, Chain[DiameterIndex, CoefficientT]] =
      PersistenceBar(
        dims(k),
        ClosedEndpoint(birthD(k)),
        if deathI(k) >= 0 then OpenEndpoint(deathD(k)) else PositiveInfinity(),
        cocycles.get(k.toLong)
      )
    def pair(k: Int): Involution.Pair[DiameterIndex] = Involution.Pair(dims(k), birth(k), death(k))

  /** Every bar, zero-length ones included, with the cells that open and close it. Cocycles are built only where asked
    * (`representative` is `None` elsewhere): with `cocycles = false` none, with `zeroLengthCocycles = false` none for
    * zero-length bars. The pairing never depends on them.
    */
  private[tda4j] def pairedCohomology(
    cocycles: Boolean = true,
    zeroLengthCocycles: Boolean = true
  ): List[(PersistenceBar[Double, Chain[DiameterIndex, CoefficientT]], Involution.Pair[DiameterIndex])] =
    val p = pairing(cocycles, zeroLengthCocycles)
    List.tabulate(p.size)(k => (p.bar(k), p.pair(k)))

  private def pairing(cocycles: Boolean, zeroLengthCocycles: Boolean): Pairing =
    _substitutionCount = 0
    _totalSimplexCount = 0
    _apparentPairCount = 0
    val rows = Pairing()
    val noLog: Array[(DiameterIndex, CoefficientT)] = Array.empty

    // Rotating per-dimension cleared set, keyed by bare Long index -- NOT a single set accumulated across all
    // dimensions the way RipserCohomologyEngine's Simplex[Int]-keyed `cleared` safely is. A combinatorial-
    // number-system index is only unique WITHIN one fixed size (index 5 at dimension 1 and index 5 at dimension
    // 2 are different simplices), so a stale entry from two dimensions ago could otherwise cause a false-positive
    // clear. `activeCleared` holds this iteration's dimension-d clears (populated during the PREVIOUS iteration);
    // `nextCleared` (below, inside the loop) accumulates dimension-(d+1) clears as they're discovered THIS
    // iteration, then rotates in.
    // Collected while a degree is reduced, then sorted once: the next degree only asks `contains`.
    var activeCleared: ClearedSet = ClearedSet()

    // Dimension-0 candidates: every vertex, diameter 0.0 -- index IS the vertex id (SimplexIndexing.apply's own
    // d==0 base case: a single vertex v decodes to/from index v directly). Each level is kept as primitive
    // (diameter, index) arrays: a degree can have tens of millions of simplices.
    var level = Level(metricSpace.size)
    for v <- 0 until metricSpace.size do level.add(0.0, v.toLong)

    for d <- 0 to maxDimension do
      val size = d + 1
      // packedOrdering.reverse: diameter descending, then the smaller index first (indices are distinct in a level).
      val levelD = level.diameters
      val levelI = level.indices
      val order = SortIndices.sort(
        level.size,
        (a, b) =>
          levelI(a) != levelI(b) && {
            val c = java.lang.Double.compare(levelD(a), levelD(b))
            if c != 0 then c > 0 else levelI(a) < levelI(b)
          }
      )
      _totalSimplexCount += level.size
      rows.reserve(level.size)

      // Capacity-hinted: `basis` gets at most one entry per simplex of this dimension.
      val loadFactor = mutable.HashMap.defaultLoadFactor
      val capacity = (level.size / loadFactor).toInt + 1
      val basis = new mutable.LongMap[Column](capacity)
      // V-columns, for the bars that report a cocycle: a pivot's seed is the birth of the row it closes (`rowOfDeath`),
      // its reduction log is kept only when it is not empty.
      val rowOfDeath = if cocycles then LongIntMap(level.size) else LongIntMap()
      val logs = mutable.LongMap.empty[Array[(DiameterIndex, CoefficientT)]]
      val pending = mutable.ArrayBuffer.empty[(Int, Array[(DiameterIndex, CoefficientT)])]
      def report(
        sigma: DiameterIndex,
        deathDiameter: Double,
        deathIndex: Long,
        log: Array[(DiameterIndex, CoefficientT)],
        isApparent: Boolean = false
      ): Unit =
        val row = rows.add(d, sigma, deathDiameter, deathIndex, isApparent)
        val zeroLength = deathIndex >= 0 && deathDiameter == sigma.diameter
        if cocycles && (zeroLengthCocycles || !zeroLength) then pending += ((row, log))
        if cocycles && deathIndex >= 0 then
          rowOfDeath(deathIndex) = row
          if log.nonEmpty then logs(deathIndex) = log
      val nextCleared = ClearedSet()

      // tau's own size (one more than sigma's) -- captured here, per-dimension, because a bare DiameterIndex
      // doesn't know its own dimension the way a Simplex[Int] does; zeroApparentFacet needs it to decode tau's
      // facets correctly.
      val coboundarySize = size + 1
      val fallbackColumn = WorkingColumn(youngestFirst = false)
      val basisFallback: DiameterIndex => Option[Column] =
        if useApparentPairs then
          (tau: DiameterIndex) =>
            zeroApparentFacet(tau, coboundarySize).map { sigma =>
              _substitutionCount += 1
              forEachCofacet(sigma, size)(fallbackColumn.push)
              fallbackColumn.drain()
            }
        else (_: DiameterIndex) => None
      val working = WorkingColumn(youngestFirst = false)

      for o <- order if !activeCleared.contains(levelI(o)) do
        val sigma = DiameterIndex(levelD(o), levelI(o))
        val sigmaFv = sigma.diameter
        (if useApparentPairs then zeroApparentCofacet(sigma, size) else None) match
          case Some(tau) =>
            _apparentPairCount += 1
            nextCleared += tau.index
            report(sigma, tau.diameter, tau.index, noLog, isApparent = true)
          case None =>
            forEachCofacet(sigma, size)(working.push)
            // Reduce: while the pivot is some earlier column's pivot (or an apparent pair's), subtract that column.
            val log = mutable.ArrayBuffer.empty[(DiameterIndex, CoefficientT)]
            var reducing = working.pivot()
            while reducing do
              val pivotCell = DiameterIndex(working.pivotDiameter, working.pivotIndex)
              basis.get(working.pivotIndex).orElse(basisFallback(pivotCell)) match
                case None         => reducing = false
                case Some(column) =>
                  val redCoeff = fr.divide(working.pivotCoefficient, column.leadingCoefficient)
                  working.addScaled(column, fr.negate(redCoeff))
                  if cocycles then log += ((pivotCell, redCoeff))
                  reducing = working.pivot()
            val reduced = working.drain()
            val reductionLog = if log.isEmpty then noLog else log.toArray
            if reduced.isEmpty then report(sigma, Double.PositiveInfinity, -1L, reductionLog)
            else
              val pivot = DiameterIndex(reduced.diameters(0), reduced.indices(0))
              basis(pivot.index) = reduced
              nextCleared += pivot.index
              report(sigma, pivot.diameter, pivot.index, reductionLog)

      val memo = mutable.LongMap.empty[Chain[DiameterIndex, CoefficientT]]
      def seedOf(pivot: Long): DiameterIndex = rows.birth(rowOfDeath(pivot))
      def logOf(pivot: Long): Array[(DiameterIndex, CoefficientT)] =
        if !rowOfDeath.contains(pivot) then
          throw new IllegalStateException(s"pivot $pivot has a basis entry but no V-column")
        logs.getOrElse(pivot, noLog)
      for (row, log) <- pending do rows.cocycles(row.toLong) = expandV(rows.birth(row), log, seedOf, logOf, memo)

      // The next degree's simplices: the canonical cofacets (added vertex above the largest) within the threshold.
      if d < maxDimension then
        val next = Level(level.size)
        var k = 0
        while k < level.size do
          eachCofacet(DiameterIndex(levelD(k), levelI(k)), size, allCofacets = false) { (diameter, index, _) =>
            next.add(diameter, index)
            true
          }
          k += 1
        level = next

      nextCleared.seal()
      activeCleared = nextCleared

    rows

/** Indices added in any order, then `seal`ed (sorted once) and queried by binary search. */
private[tda4j] final class ClearedSet:
  private var values: Array[Long] = new Array[Long](16)
  private var count: Int = 0
  private var isSealed: Boolean = false

  def +=(value: Long): Unit =
    if count == values.length then
      values = java.util.Arrays.copyOf(values, math.min(2L * count, Int.MaxValue.toLong - 8).toInt)
    values(count) = value
    count += 1

  def seal(): Unit =
    java.util.Arrays.sort(values, 0, count)
    isSealed = true

  def contains(value: Long): Boolean =
    count > 0 && {
      if !isSealed then throw new IllegalStateException("ClearedSet queried before seal()")
      java.util.Arrays.binarySearch(values, 0, count, value) >= 0
    }

/** The simplices of one degree as parallel (diameter, index) arrays, growable. */
private[tda4j] final class Level(initialCapacity: Int):
  var diameters: Array[Double] = new Array[Double](math.max(initialCapacity, 16))
  var indices: Array[Long] = new Array[Long](math.max(initialCapacity, 16))
  var size: Int = 0

  def add(diameter: Double, index: Long): Unit =
    if size == indices.length then
      val grown = math.min(2L * size, Int.MaxValue.toLong - 8).toInt
      if grown <= size then throw new IllegalStateException(s"more than $size simplices in one degree")
      diameters = java.util.Arrays.copyOf(diameters, grown)
      indices = java.util.Arrays.copyOf(indices, grown)
    diameters(size) = diameter
    indices(size) = index
    size += 1

/** A map from non-negative `Long` keys to `Int` values, open addressing over primitive arrays (no boxing). */
private[tda4j] final class LongIntMap(expected: Int = 0):
  // Capacity: a power of two at least 4/3 of the expected size (load at most 0.75 without growing).
  private var keys: Array[Long] =
    Array.fill(Integer.highestOneBit(math.max(16, (expected.toLong * 4 / 3 + 1).min(1L << 29).toInt)) * 2)(-1L)
  private var values: Array[Int] = new Array[Int](keys.length)
  private var count: Int = 0

  private def slot(key: Long, ks: Array[Long]): Int =
    val mask = ks.length - 1
    var h = (key * 0x9e3779b97f4a7c15L) ^ (key >>> 29)
    var s = (h ^ (h >>> 32)).toInt & mask
    while ks(s) != -1L && ks(s) != key do s = (s + 1) & mask
    s

  def update(key: Long, value: Int): Unit =
    if 4L * (count + 1) > 3L * keys.length then grow()
    val s = slot(key, keys)
    if keys(s) == -1L then count += 1
    keys(s) = key
    values(s) = value

  def contains(key: Long): Boolean = keys(slot(key, keys)) == key

  def apply(key: Long): Int =
    val s = slot(key, keys)
    if keys(s) != key then throw new NoSuchElementException(s"key not found: $key")
    values(s)

  def size: Int = count

  private def grow(): Unit =
    val oldKeys = keys
    val oldValues = values
    keys = Array.fill(oldKeys.length * 2)(-1L)
    values = new Array[Int](oldKeys.length * 2)
    var i = 0
    while i < oldKeys.length do
      if oldKeys(i) != -1L then
        val s = slot(oldKeys(i), keys)
        keys(s) = oldKeys(i)
        values(s) = oldValues(i)
      i += 1
