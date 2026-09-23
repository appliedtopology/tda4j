package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}

import org.appliedtopology.tda4j.barcode.PersistenceBar

import scala.collection.mutable
import scala.compiletime.asMatchable

/** '''The production Ripser persistent-cohomology engine''' -- this is what `TDA4j.scala`'s public `engine="ripser"`
  * MATLAB-facing option actually calls, not `RipserCohomologyContext`. `RipserCohomologyContext` (`Homology.scala`)
  * stays in the codebase deliberately, but ONLY as this class's cross-validation test oracle -- see that class's own
  * doc for why its remaining value is narrower than "a second production option" (it catches representation-specific
  * bugs in `DiameterIndex`'s index-only `equals`/`hashCode` and this class's index-keyed `basis`/`generators`/
  * `cleared` maps that no other spec would; it does NOT independently validate the Ripser algorithm itself, since both
  * engines share `SimplexIndexing` -- that job belongs to `SimplicialHomologyContext`, a genuinely different
  * algorithm). Measured faster and dramatically leaner on memory than `RipserCohomologyContext` on real paper data.
  *
  * Keyed on a packed `(Double, Long)` diameter/combinatorial-index pair instead of a materialized
  * `Simplex[Int]`/`SortedSet[Int]` -- Ripser's own `diameter_index_t` representation, deliberately NOT adopted by
  * `RipserCohomologyContext` (see `DiameterSimplex`'s own doc in `Homology.scala`). Eliminating `SortedSet[Int]` as the
  * thing carried/hashed/compared through `Chain.reduceBy`'s reduction closes most of the constant-factor tax measured
  * against real `ripser.cpp` (`.claude/WORKLOG-ripser-comparison.md`). A genuinely SEPARATE file from `Homology.scala`
  * on purpose: this representation is specific to Vietoris-Rips/`SimplexIndexing`, not a general `OrderedCell` engine
  * the way the other engines are.
  *
  * Implements `RipserCohomologyContext`'s `maxDimension` semantics (top homological degree reported, not top simplex
  * dimension built -- `.claude/WORKLOG-maxdim-semantics-fix.md`) method for method, rather than re-deriving the
  * algorithm independently -- a faithful re-keying, not a redesign.
  *
  * '''Why `DiameterIndex`, not a bare `(Double, Long)` tuple''': a bare tuple risks Scala's own stdlib
  * `Ordering.Tuple2` competing with this class's own intended ordering (ascending diameter, ties broken by DESCENDING
  * index -- Definition 3.2/Proposition 3.9's "lexicographically refined" tie-break, the opposite of a tuple's natural
  * ascending-both-fields order). A small named carrier, exactly `DiameterSimplex`'s own pattern one class up, sidesteps
  * the ambiguity entirely.
  *
  * '''Why `DiameterIndex` overrides `equals`/`hashCode` to consider only `index`, not `diameter`''':
  * `DiameterSimplex`'s own doc warns against using it as a Set/Map key for exactly this reason -- case-class equality
  * including the Double diameter means two carriers for the textually-same simplex could compare unequal on
  * floating-point noise. This class's `basis`/`generators` are genuinely keyed by the FULL `DiameterIndex` (required:
  * `Chain.reduceBy`'s basis map key type must match the chain's own cell type, and a pivot extracted via
  * `chain.leadingCell` is a `DiameterIndex`, not a bare `Long`), so instead of requiring callers to strip the diameter
  * by discipline, `equals`/`hashCode` depend on `index` alone, making "same combinatorial index = same key" true by
  * construction regardless of which floating-point path computed the diameter.
  */
/** Ripser's own cofacet-diameter recurrence: `sigma`'s cofacet diameter after inserting vertex `v` is the max of
  * `sigma`'s own diameter and `v`'s distance to every one of `sigma`'s vertices -- an O(d) formula needing `sigma`'s
  * materialized vertex set, with no index-only shortcut. Shared by `PackedRipserCohomologyContext` and
  * `RipserCohomologyContext` (`Homology.scala`) -- `metricSpace` is threaded explicitly, rather than each engine's own
  * field, so one function serves both.
  *
  * Takes `sigma`'s vertex set as an already-materialized `Array[Int]`, not a `Simplex[Int]`/`SortedSet[Int]`:
  * `sigma.underlying.iterator` (a persistent red-black tree with no cheaper iteration path) allocates a fresh
  * `KeysIterator`/`TreeIterator`/`Tree[]` DFS-stack on every call. `sigma` is fixed across every candidate vertex
  * considered within one enumeration call, so each caller decodes/materializes its vertex array exactly ONCE and passes
  * the same array to every `insertionDiameter` call in that enumeration.
  */
private[homology] def insertionDiameter(
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

class PackedRipserCohomologyContext[CoefficientT: Field](
  metricSpace: FiniteMetricSpace[Int],
  maxDimension: Int,
  useApparentPairs: Boolean = true,
  // None means "not explicitly set," resolved to metricSpace.minimumEnclosingRadius just below -- see
  // RipserCohomologyContext's identical parameter for the full derivation of why Option, not a NaN sentinel.
  maxFiltrationValue: Option[Double] = None
):
  import org.appliedtopology.tda4j.barcode.*

  private val resolvedMaxFiltrationValue: Double =
    maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

  /** Not `private`, as of `TDA4j.scala` routing `engine="ripser"` through this class instead of
    * `RipserCohomologyContext`: a caller decoding a bar's `DiameterIndex` cells back to vertex arrays (e.g.
    * `PersistenceResult.cycleVertices`) needs this same `SimplexIndexing` instance -- constructing a fresh one from
    * `metricSpace.size` would work too (the class is a pure function of vertex count), but would rebuild
    * `binomialEntry`'s lazily-grown cache from scratch rather than reusing the one this context already populated
    * during its own reduction.
    */
  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)
  private val fr = summon[CoefficientT is Field]

  /** A simplex carried purely as (diameter, combinatorial index) -- see the class doc above for why equality
    * deliberately ignores `diameter`. Not `private`: it's the actual `Chain` cell type `persistentCohomology()` returns
    * bars over, so callers need to be able to name the type (e.g. to decode a bar's cells back to `Simplex[Int]` via
    * `SimplexIndexing.apply(index, size)` for display or cross-validation).
    */
  final case class DiameterIndex(diameter: Double, index: Long):
    // `.asMatchable` is a compile-time-only cast (satisfies Scala 3's Matchable safety check on an `Any`
    // selector -- `equals` must take `Any`, which isn't itself `Matchable`), not a runtime operation -- zero
    // added cost on this hot path (`equals`/`hashCode` run on every `basis`/`generators`/`cleared` map/set
    // operation in `persistentCohomology`). No `@unchecked` needed here, unlike `Chain.equals`: `DiameterIndex`
    // has no type parameters to erase.
    override def equals(other: Any): Boolean = other.asMatchable match
      case that: DiameterIndex => this.index == that.index
      case _                   => false
    override def hashCode(): Int = index.hashCode()

  /** Ascending by diameter; ties broken so a LARGER index sorts as OLDER (smaller under this ordering) -- the exact
    * same tie-break `RipserCohomologyContext.compareFvThenIndex`/`cohomologyOrdering` use, just operating directly on
    * the already-carried pair with no `si(x)`/`si(y)` ENCODE step at all (that engine's `si(simplex): Long` is an O(d
    * log d) sort-and-sum, paid TWICE per comparator call -- here the index is already sitting in the pair being
    * compared).
    */
  private def compareDiamThenIndex(x: DiameterIndex, y: DiameterIndex): Int =
    val fc = java.lang.Double.compare(x.diameter, y.diameter)
    if fc != 0 then fc else java.lang.Long.compare(y.index, x.index)

  given packedOrdering: Ordering[DiameterIndex] = compareDiamThenIndex(_, _)

  /** Plain O(d^2) pairwise-maximum over an already-decoded `Array[Int]` -- the array-based equivalent of
    * `FiniteMetricSpace.MaximumDistanceFiltrationValue.apply(Simplex[Int])`, used ONLY by `zeroPivotFacet` below. That
    * method has no incremental shortcut (removing a vertex, unlike inserting one via `insertionDiameter`, admits no
    * O(d) recurrence -- see its own doc), so each candidate facet's filtration value must be recomputed from scratch
    * regardless; this exists so that recompute can work directly off `decodeToArray`'s cheap array decode instead of
    * needing a `Simplex[Int]`. `MaximumDistanceFiltrationValue` itself is untouched; every other caller of it is
    * unaffected.
    */
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

  /** The canonical (insert-above-own-maximum) cofacets of `sigma`, one per higher simplex that has `sigma` as its own
    * canonical facet -- packed analogue of `RipserCohomologyContext.sparseCofacets`. `size` is `sigma`'s own vertex
    * count (dimension + 1); a `DiameterIndex` doesn't carry its own dimension the way a `Simplex[Int]` does, so callers
    * thread `size` explicitly instead (constant within one outer-loop iteration -- see `persistentCohomology`).
    *
    * Returns `Iterator[DiameterIndex]`, not `Seq[DiameterIndex]`, even though both call sites immediately materialize
    * the flattened result: `Seq` would force every SOURCE simplex's own cofacet list to be fully built before
    * flattening, where `Iterator` keeps only one source simplex's cofacets live at a time -- real memory pressure at
    * this class's own scale. Built directly on `CofacetCursor` (not `cofacetIteratorWithVertex`), so this keeps the
    * allocation win -- no `(Int, Long)` tuple per candidate -- while keeping the lazy `Iterator` contract.
    */
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

  /** Packed analogue of `RipserCohomologyContext.coboundaryOf`: decodes `sigma` exactly ONCE (never decodes any `tau`
    * -- each cofacet's diameter comes from `insertionDiameter`, its identity from the index `CofacetCursor` already
    * produces). Guard mirrors the fixed `maxDimension` semantics: empty only past `maxDimension + 1` (`size - 1 >
    * maxDimension`, i.e. `sigma`'s own dimension exceeds what's requested), not AT it -- see
    * `.claude/WORKLOG-maxdim-semantics-fix.md`.
    *
    * Built directly on `CofacetCursor`, not `cofacetIteratorWithVertex`: the latter allocates a fresh `(Int, Long)`
    * tuple on every candidate vertex considered, once the largest identified allocation cost in this class.
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
          // `vertices` is sorted ascending (from `TreeSet.toArray`), so counting entries below `cur.vertex` is a
          // plain linear scan, not `decoded.underlying.count(_ < v)` -- see `insertionDiameter`'s doc above for why
          // a `SortedSet` operation here allocates an iterator on every single candidate `v`.
          var position = 0
          while position < vertices.length && vertices(position) < cur.vertex do position += 1
          val sign = if position % 2 == 0 then fr.one else fr.negate(fr.one)
          buffer += ((DiameterIndex(tauFv, cur.index), sign))
        cur.advance()
      Chain.from(buffer.toSeq)

  /** A hand-rolled `while` loop over `CofacetCursor` directly, not `.filter(...).maxByOption(_.index)` or
    * `cofacetIteratorWithVertex`: `maxByOption` boxes every `Long` comparison, and an `Iterator[(Int, Long)]` allocates
    * a fresh tuple per candidate on top of that. Since every candidate that survives the `tauFv == sigma.diameter`
    * filter shares the SAME diameter (`sigma.diameter`), the winning `DiameterIndex` can be reconstructed from just the
    * best `index` seen, tracked as a primitive `var` -- no boxing per candidate considered, only for the single final
    * result.
    */
  private def zeroPivotCofacet(sigma: DiameterIndex, size: Int): Option[DiameterIndex] =
    if size - 1 > maxDimension then None
    else
      val vertices = si.decodeToArray(sigma.index, size)
      val cur = si.cofacetCursor(sigma.index, size, allCofacets = true)
      var bestIdx: Long = -1L
      var found = false
      while cur.hasNext do
        val tauFv = insertionDiameter(metricSpace, vertices, sigma.diameter, cur.vertex)
        if tauFv == sigma.diameter && (!found || cur.index > bestIdx) then
          bestIdx = cur.index
          found = true
        cur.advance()
      if found then Some(DiameterIndex(sigma.diameter, bestIdx)) else None

  /** `tau`'s facet tied at `tau`'s own value with the smallest index. No incremental shortcut exists for removing a
    * vertex's DIAMETER (same scope boundary `RipserCohomologyContext.zeroPivotFacet` documents -- `maxPairwiseDistance`
    * must still be fully recomputed per candidate), but `FacetCursor` yields the removed vertex directly, so the
    * candidate's own VERTEX SET is built by array-removal from `tau`'s already-decoded vertices rather than a fresh
    * `decodeToArray(facetIdx, size - 1)` call -- verified sound by `SimplexIndexingSpec`'s `FacetCursor` correctness
    * property (`decodeToArray(cur.index, size-1).toSet == decodeToArray(startIndex, size).toSet - cur.vertex`).
    * Hand-rolled `while` loop for the same reason as `zeroPivotCofacet` above.
    */
  private def zeroPivotFacet(tau: DiameterIndex, size: Int): Option[DiameterIndex] =
    val tauVertices = si.decodeToArray(tau.index, size)
    val candidate = new Array[Int](size - 1)
    val cur = si.facetCursor(tau.index, size)
    var bestIdx: Long = Long.MaxValue
    var found = false
    while cur.hasNext do
      var w = 0
      var r = 0
      while w < tauVertices.length do
        if tauVertices(w) != cur.vertex then
          candidate(r) = tauVertices(w)
          r += 1
        w += 1
      val fv = maxPairwiseDistance(candidate)
      if fv == tau.diameter && (!found || cur.index < bestIdx) then
        bestIdx = cur.index
        found = true
      cur.advance()
    if found then Some(DiameterIndex(tau.diameter, bestIdx)) else None

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

  def persistentCohomology(): List[PersistenceBar[Double, Chain[DiameterIndex, CoefficientT]]] =
    val chainRM = summon[Chain[DiameterIndex, CoefficientT] is RingModule]
    import chainRM.*

    _substitutionCount = 0
    _totalSimplexCount = 0
    val bars = mutable.ArrayDeque.empty[PersistenceBar[Double, Chain[DiameterIndex, CoefficientT]]]

    // Rotating per-dimension cleared set, keyed by bare Long index -- NOT a single set accumulated across all
    // dimensions the way RipserCohomologyContext's Simplex[Int]-keyed `cleared` safely is. A combinatorial-
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

    bars.toList
