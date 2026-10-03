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
  */
class PackedRipserCohomologyEngine[CoefficientT: Field](
  metricSpace: FiniteMetricSpace[Int],
  maxDimension: Int,
  useApparentPairs: Boolean = true,
  // None: the minimum enclosing radius.
  maxFiltrationValue: Option[Double] = None
):

  private val resolvedMaxFiltrationValue: Double =
    maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

  /** The combinatorial number system of this metric space's simplices, for decoding the cells of returned chains. */
  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)
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
  private def compareDiamThenIndex(x: DiameterIndex, y: DiameterIndex): Int =
    val fc = java.lang.Double.compare(x.diameter, y.diameter)
    if fc != 0 then fc else java.lang.Long.compare(y.index, x.index)

  given packedOrdering: Ordering[DiameterIndex] = compareDiamThenIndex(_, _)

  // The diameter of a decoded vertex array, O(d^2): for facets, where removing a vertex has no cheap update rule.
  private def maxPairwiseDistance(vertices: Array[Int]): Double =
    var maxD = 0.0
    var i = 0
    while i < vertices.length do
      var j = i + 1
      while j < vertices.length do
        val d = metricSpace.distance(vertices(i), vertices(j))
        if d > maxD then maxD = d
        j += 1
      i += 1
    maxD

  // The canonical cofacets of `sigma` (insert a vertex above its largest) within `maxFiltrationValue`; `size` is sigma's
  // vertex count (a DiameterIndex does not know its dimension). Lazy, so one source simplex's cofacets are live at a
  // time.
  private def sparseCofacets(sigma: DiameterIndex, size: Int): Iterator[DiameterIndex] =
    if size > maxDimension then Iterator.empty
    else
      val vertices = si.decodeToArray(sigma.index, size)
      val cur = si.cofacetCursor(sigma.index, size, allCofacets = false)
      new Iterator[DiameterIndex]:
        private var pending: DiameterIndex = DiameterIndex(0.0, 0L)
        private var havePending: Boolean = false
        private def step(): Unit =
          havePending = false
          while !havePending && cur.hasNext do
            val tauFv = insertionDiameter(metricSpace, vertices, sigma.diameter, cur.vertex)
            if tauFv <= resolvedMaxFiltrationValue then
              pending = DiameterIndex(tauFv, cur.index)
              havePending = true
            cur.advance()
        step()
        def hasNext: Boolean = havePending
        def next(): DiameterIndex =
          if !havePending then throw new NoSuchElementException("next on empty iterator")
          val result = pending
          step()
          result

  /** The coboundary of `sigma` (with `size` vertices) in the complex truncated at `maxFiltrationValue`, as in
    * [[RipserCohomologyEngine.coboundaryOf]]. Defined up to dimension `maxDimension`.
    */
  def coboundaryOf(sigma: DiameterIndex, size: Int): Chain[DiameterIndex, CoefficientT] =
    if size - 1 > maxDimension then Chain.empty
    else
      val vertices = si.decodeToArray(sigma.index, size)
      val cur = si.cofacetCursor(sigma.index, size, allCofacets = true)
      val buffer = mutable.ArrayBuffer.empty[(DiameterIndex, CoefficientT)]
      while cur.hasNext do
        val tauFv = insertionDiameter(metricSpace, vertices, sigma.diameter, cur.vertex)
        if tauFv <= resolvedMaxFiltrationValue then
          // `vertices` is sorted ascending: a plain scan counts the entries below `cur.vertex`.
          var position = 0
          while position < vertices.length && vertices(position) < cur.vertex do position += 1
          val sign = if position % 2 == 0 then fr.one else fr.negate(fr.one)
          buffer += ((DiameterIndex(tauFv, cur.index), sign))
        cur.advance()
      Chain.from(buffer.toSeq)

  // The oldest same-diameter cofacet (largest index): the cursor's index decreases strictly, so the first tie is it.
  private def zeroPivotCofacet(sigma: DiameterIndex, size: Int): Option[DiameterIndex] =
    if size - 1 > maxDimension then None
    else
      val vertices = si.decodeToArray(sigma.index, size)
      val cur = si.cofacetCursor(sigma.index, size, allCofacets = true)
      while cur.hasNext do
        val tauFv = insertionDiameter(metricSpace, vertices, sigma.diameter, cur.vertex)
        if tauFv == sigma.diameter then return Some(DiameterIndex(sigma.diameter, cur.index))
        cur.advance()
      None

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

  /** Every bar of degree `0 .. maxDimension`, each with its representative cocycle (over [[DiameterIndex]] cells;
    * decode with `si`); zero-length bars only if `includeZeroLength`.
    */
  def persistentCohomology(
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[Double, Chain[DiameterIndex, CoefficientT]]] =
    val chainRM = summon[Chain[DiameterIndex, CoefficientT] is RingModule]
    import chainRM.*

    _substitutionCount = 0
    _totalSimplexCount = 0
    val bars = mutable.ArrayDeque.empty[PersistenceBar[Double, Chain[DiameterIndex, CoefficientT]]]

    // Rotating per-dimension cleared set, keyed by bare Long index -- NOT a single set accumulated across all
    // dimensions the way RipserCohomologyEngine's Simplex[Int]-keyed `cleared` safely is. A combinatorial-
    // number-system index is only unique WITHIN one fixed size (index 5 at dimension 1 and index 5 at dimension
    // 2 are different simplices), so a stale entry from two dimensions ago could otherwise cause a false-positive
    // clear. `activeCleared` holds this iteration's dimension-d clears (populated during the PREVIOUS iteration);
    // `nextCleared` (below, inside the loop) accumulates dimension-(d+1) clears as they're discovered THIS
    // iteration, then rotates in.
    var activeCleared: mutable.Set[Long] = mutable.Set.empty

    // Dimension-0 candidates: every vertex, diameter 0.0 -- index IS the vertex id (SimplexIndexing.apply's own
    // d==0 base case: a single vertex v decodes to/from index v directly).
    var currentLevel: Seq[DiameterIndex] =
      (0 until metricSpace.size).map(v => DiameterIndex(0.0, v.toLong))

    for d <- 0 to maxDimension do
      val size = d + 1
      val simplicesAtD: Seq[DiameterIndex] = currentLevel.sorted(using packedOrdering.reverse)
      _totalSimplexCount += simplicesAtD.size

      // Capacity-hinted, not `mutable.Map.empty`/`mutable.Set.empty` (which default to `HashMap`/`HashSet` at
      // their own built-in starting capacity regardless of how many entries this dimension will actually hold) --
      // `basis`/`generators` each get AT MOST one entry per simplex in `simplicesAtD` (exactly one write per
      // `sigma` processed below, to at most one of the two branches), and `nextCleared` likewise at most one
      // entry per `sigma`, so `simplicesAtD.size` is a real upper bound on final size, not a guess. Sized to avoid
      // `HashMap.growTable`/`HashSet.growTable` entirely for the common case, rather than the default capacity
      // forcing one or more table-doubling rehashes as each dimension's collections fill up. Pure capacity hint,
      // no behavior change: none of these three collections is ever iterated in an order-dependent way below
      // (only `.get`/`.contains`/`+=`/`.getOrElse`, all point operations).
      val loadFactor = mutable.HashMap.defaultLoadFactor
      val capacity = (simplicesAtD.size / loadFactor).toInt + 1
      val basis: mutable.Map[DiameterIndex, Chain[DiameterIndex, CoefficientT]] =
        new mutable.HashMap(capacity, loadFactor)
      val generators: mutable.Map[DiameterIndex, Chain[DiameterIndex, CoefficientT]] =
        new mutable.HashMap(capacity, loadFactor)
      var nextCleared: mutable.Set[Long] = new mutable.HashSet(capacity, loadFactor)

      // tau's own size (one more than sigma's) -- captured here, per-dimension, because a bare DiameterIndex
      // doesn't know its own dimension the way a Simplex[Int] does; zeroApparentFacet needs it to decode tau's
      // facets correctly.
      val coboundarySize = size + 1
      val basisFallback: DiameterIndex => Option[Chain[DiameterIndex, CoefficientT]] =
        if useApparentPairs then
          (tau: DiameterIndex) =>
            zeroApparentFacet(tau, coboundarySize).map { sigma =>
              _substitutionCount += 1
              coboundaryOf(sigma, size)
            }
        else (_: DiameterIndex) => None

      for sigma <- simplicesAtD if !activeCleared.contains(sigma.index) do
        val sigmaFv = sigma.diameter
        (if useApparentPairs then zeroApparentCofacet(sigma, size) else None) match
          case Some(tau) =>
            val vcol = Chain[DiameterIndex, CoefficientT](sigma)
            generators(tau) = vcol
            nextCleared += tau.index
            bars.append(PersistenceBar(d, ClosedEndpoint(sigmaFv), OpenEndpoint(tau.diameter), Some(vcol)))
          case None =>
            val z = coboundaryOf(sigma, size)
            val (reduced, log) = Chain.reduceBy(z, basis, Chain.empty, basisFallback)
            val vcol: Chain[DiameterIndex, CoefficientT] =
              log.rawEntries.foldLeft(Chain(sigma)) { case (acc, (pivot, coeff)) =>
                acc - coeff ⊠ generators.getOrElse(
                  pivot,
                  throw new IllegalStateException(s"pivot $pivot has a basis entry but no generators entry")
                )
              }
            vcol.collapseAll()
            if reduced.isZero() then
              bars.append(PersistenceBar(d, ClosedEndpoint(sigmaFv), PositiveInfinity(), Some(vcol)))
            else
              val pivot = reduced.leadingCell.get
              basis(pivot) = reduced
              generators(pivot) = vcol
              nextCleared += pivot.index
              bars.append(PersistenceBar(d, ClosedEndpoint(sigmaFv), OpenEndpoint(pivot.diameter), Some(vcol)))

      if d < maxDimension then currentLevel = simplicesAtD.iterator.flatMap(sparseCofacets(_, size)).toSeq

      activeCleared = nextCleared

    PersistenceBar.dropZeroLength(bars, includeZeroLength)
