package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}

import org.appliedtopology.tda4j.barcode.PersistenceBar

import scala.collection.mutable

/** '''The production Ripser persistent-cohomology engine''' -- as of `.claude/WORKLOG-ripser-profiling.md`'s
  * cursor-redesign session, this is what `Tda4j.scala`'s public `engine="ripser"` MATLAB-facing option actually calls,
  * not `RipserCohomologyContext`. `RipserCohomologyContext` (`Homology.scala`) stays in the codebase deliberately, but
  * ONLY as this class's cross-validation test oracle -- see that class's own doc for why its remaining value is
  * narrower than "a second production option" (it catches representation-specific bugs in `DiameterIndex`'s index-only
  * `equals`/`hashCode` and this class's index-keyed `basis`/`generators`/`cleared` maps that no other spec would; it
  * does NOT independently validate the Ripser algorithm itself, since both engines share `SimplexIndexing` -- that job
  * belongs to `SimplicialHomologyContext`, a genuinely different algorithm). Measured faster and dramatically leaner on
  * memory than `RipserCohomologyContext` on real paper data (see that worklog's own comparison table) -- not merely
  * "the same, but a different representation."
  *
  * Originally built as a parallel implementation keyed on a packed `(Double, Long)` diameter/combinatorial-index pair
  * instead of a materialized `Simplex[Int]`/`SortedSet[Int]` -- Ripser's own `diameter_index_t` representation,
  * deliberately NOT adopted by `RipserCohomologyContext` (see `DiameterSimplex`'s own doc in `Homology.scala`, which
  * flags this as a live option for a future session at the time it was written). Built to test, not assume, whether
  * eliminating `SortedSet[Int]` as the thing carried/hashed/compared through `Chain.reduceBy`'s reduction closes some
  * of the ~20µs/simplex constant-factor tax measured against real `ripser.cpp` in
  * `.claude/WORKLOG-ripser-comparison.md` -- see `.claude/WORKLOG-packed-ripser-engine.md` for the measurement that
  * first confirmed it did. A genuinely SEPARATE file from `Homology.scala` (which holds the four canonical persistence
  * algorithms per CLAUDE.md, `RipserCohomologyContext` among them) on purpose, kept that way even now that this is the
  * production path: this representation is specific to Vietoris-Rips/`SimplexIndexing`, not a general `OrderedCell`
  * engine the way (1)-(3) are, and `Homology.scala` is already large enough that a clearly-demarcated separate file
  * keeps this from blending into the generic engines.
  *
  * Implements `RipserCohomologyContext`'s ALREADY-FIXED `maxDimension` semantics (top homological degree reported, not
  * top simplex dimension built -- `.claude/WORKLOG-maxdim-semantics-fix.md`) from the start, method for method, rather
  * than re-deriving the algorithm independently -- a faithful re-keying, not a redesign.
  *
  * '''Why `DiameterIndex`, not a bare `(Double, Long)` tuple''': a bare tuple risks Scala's own stdlib
  * `Ordering.Tuple2` (discoverable via `Ordering`'s companion object during implicit search for
  * `Ordering[(Double, Long)]`) competing with this class's own intended ordering (ascending diameter, ties broken by
  * DESCENDING index -- Definition 3.2/Proposition 3.9's "lexicographically refined" tie-break, the opposite of a
  * tuple's natural ascending-both-fields order). A small named carrier, exactly `DiameterSimplex`'s own pattern one
  * class up, sidesteps the ambiguity risk entirely rather than relying on Scala 3's given-priority rules to resolve it
  * the intended way.
  *
  * '''Why `DiameterIndex` overrides `equals`/`hashCode` to consider only `index`, not `diameter`''':
  * `DiameterSimplex`'s own doc warns "do not use `DiameterSimplex` as a Set/Map key anywhere -- its case-class equality
  * includes the Double diameter, so two carriers for the textually-same simplex could compare unequal on floating-point
  * noise" and mandates keying `cleared`/`basis`/`generators` by `.simplex` alone instead. This class's
  * `basis`/`generators` are genuinely keyed by the FULL `DiameterIndex` (required: `Chain.reduceBy`'s
  * `basis: Map[CellT, Chain[CellT, CoefficientT]]` parameter's key type must match the chain's own cell type, and a
  * pivot extracted via `chain.leadingCell` is a `DiameterIndex`, not a bare `Long`) -- so instead of requiring callers
  * to strip the diameter by discipline, `DiameterIndex.equals`/`.hashCode` are overridden to depend on `index` alone,
  * making "same combinatorial index = same key" true BY CONSTRUCTION regardless of which floating-point path computed
  * the diameter. (For the record: `insertionDiameter`'s `math.max` over already-computed distances is in fact
  * order-independent for finite, non-NaN values, so this class's own diameters ARE bit-reproducible across the
  * different faces that could enumerate the same cofacet -- but the equals/hashCode override means this class's
  * correctness never has to rest on that argument.)
  */
class PackedRipserCohomologyContext[CoefficientT: Field](
  metricSpace: FiniteMetricSpace[Int],
  maxDimension: Int,
  useApparentPairs: Boolean = true,
  maxFiltrationValue: Double = Double.NaN
):
  import org.appliedtopology.tda4j.barcode.*

  private val resolvedMaxFiltrationValue: Double =
    if maxFiltrationValue.isNaN then metricSpace.minimumEnclosingRadius else maxFiltrationValue

  /** Not `private`, as of `Tda4j.scala` routing `engine="ripser"` through this class instead of
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
    override def equals(other: Any): Boolean = other match
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

  /** Ripser's own cofacet-diameter recurrence, identical to `RipserCohomologyContext.insertionDiameter` -- genuinely
    * needs `sigma`'s materialized vertex set (there is no index-only shortcut for this specific O(d) formula), so
    * `sigma` is decoded via `si(sigma.index, size)` exactly once per enumeration step, never per comparison -- see
    * `.claude/WORKLOG-packed-ripser-engine.md` for the confirmation that this decode cost is NOT on the reduction hot
    * path this class exists to avoid.
    */
  /** Takes `sigma`'s vertex set as an already-materialized `Array[Int]`, not a `Simplex[Int]`/`SortedSet[Int]`, as of a
    * later follow-up session (see `.claude/WORKLOG-ripser-profiling.md`'s "iterator allocation, not accumulator churn"
    * section): the closure-allocation fix that first replaced `.map(...).max` with this `while` loop (see git history)
    * still called `sigma.underlying.iterator` -- and `TreeSet.iterator()` itself allocates a `KeysIterator` wrapping a
    * `TreeIterator` (which needs its own `Tree[]` DFS-stack array), FRESH on every call, since `sigma.underlying` is a
    * persistent red-black tree with no cheaper iteration path. Re-profiling after the closure fix found this was
    * actually the single LARGEST allocation source in this class -- 23.8% of total weight (13.11% `KeysIterator` +
    * 10.72% `Tree[]`) on real `sphere3_96` data, bigger than `Chain.reduceLoop`'s own persistent-map churn this session
    * set out to fix (confirmed at ~6.9% once accurately attributed -- the "48%" figure earlier in this worklog
    * conflated three unrelated sources sharing a `RedBlackTree` class-name prefix). `sigma` is fixed across every
    * candidate vertex in one enumeration call, so each caller decodes/materializes `sigma.underlying.toArray` exactly
    * ONCE and passes the same array to every `insertionDiameter` call in that enumeration -- eliminating the repeated
    * iterator allocation rather than making it cheaper. Same fix applied identically to
    * `RipserCohomologyContext.insertionDiameter` (`Homology.scala`) -- the two are no longer byte-for-byte identical
    * (this class decodes `sigma` from a packed index and hoists the array at each of three call sites below;
    * `Homology.scala` hoists it from an already-materialized `Simplex[Int]` at its own three call sites), but the
    * array-indexing body of `insertionDiameter` itself stays identical between them.
    */
  private def insertionDiameter(vertices: Array[Int], sigmaFv: Double, v: Int): Double =
    var maxD = sigmaFv
    var i = 0
    while i < vertices.length do
      val d = metricSpace.distance(vertices(i), v)
      if d > maxD then maxD = d
      i += 1
    maxD

  /** Plain O(d^2) pairwise-maximum over an already-decoded `Array[Int]` -- the array-based equivalent of
    * `FiniteMetricSpace.MaximumDistanceFiltrationValue.apply(Simplex[Int])`, used ONLY by `zeroPivotFacet` below. That
    * method has no incremental shortcut (removing a vertex, unlike inserting one via `insertionDiameter`, admits no
    * O(d) recurrence -- see its own doc), so each candidate facet's filtration value must be recomputed from scratch
    * regardless; this exists so that recompute can work directly off `decodeToArray`'s cheap array decode instead of
    * needing a `Simplex[Int]` (which `MaximumDistanceFiltrationValue.apply` requires, and which would need the
    * `apply`/`SimplexOps.$plus`-based decode this session's other fixes moved away from). Measured as the single
    * largest remaining allocation category after the `decodeToArray`/boxing fixes above (~34% of a much-smaller total,
    * `.claude/WORKLOG-ripser-profiling.md`) -- `MaximumDistanceFiltrationValue` itself is untouched; every other caller
    * of it is unaffected.
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
    * Returns `Iterator[DiameterIndex]`, not `Seq[DiameterIndex]`, even though both call sites (`persistentCohomology`'s
    * `simplicesAtD.iterator.flatMap(sparseCofacets(_, size)).toSeq`) immediately materialize the flattened result -- an
    * `advisor()` catch before this cursor-redesign session shipped: `Seq` would force every SOURCE simplex's own
    * cofacet list to be fully built before flattening, where the current `Iterator` keeps only one source simplex's
    * cofacets live at a time. Since this is exactly the workload the memory-comparison deliverable in this same session
    * measures, trading that streaming behavior away for a marginally simpler hand-rolled `Iterator` wasn't worth it.
    * `CofacetCursor` is still used directly (not `cofacetIteratorWithVertex`), so this keeps the allocation win -- no
    * `(Int, Long)` tuple per candidate -- while keeping the lazy `Iterator` contract.
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
            val tauFv = insertionDiameter(vertices, sigma.diameter, cur.vertex)
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
    * Built directly on `CofacetCursor`, not `cofacetIteratorWithVertex`, as of the cursor-redesign session
    * (`.claude/WORKLOG-ripser-profiling.md`): the old `Iterator[(Int, Long)]` allocated a fresh tuple on every
    * candidate vertex considered, measured as ~49.8% of this class's own total allocation weight after every earlier
    * fix in that arc -- the largest remaining identified cost, bigger than everything fixed before it combined.
    */
  def coboundaryOf(sigma: DiameterIndex, size: Int): Chain[DiameterIndex, CoefficientT] =
    if size - 1 > maxDimension then Chain.empty
    else
      val vertices = si.decodeToArray(sigma.index, size)
      val cur = si.cofacetCursor(sigma.index, size, allCofacets = true)
      val buffer = mutable.ArrayBuffer.empty[(DiameterIndex, CoefficientT)]
      while cur.hasNext do
        val tauFv = insertionDiameter(vertices, sigma.diameter, cur.vertex)
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

  /** A hand-rolled `while` loop over `CofacetCursor` directly, not `.filter(...).maxByOption(_.index)` (fixed in an
    * earlier follow-up session) or `cofacetIteratorWithVertex` (fixed in this cursor-redesign session, `.claude/
    * WORKLOG-ripser-profiling.md`): `maxByOption` boxed every `Long` comparison, and the old `Iterator[(Int, Long)]`
    * allocated a fresh tuple per candidate on top of that. Since every candidate that survives the
    * `tauFv == sigma.diameter` filter shares the SAME diameter (`sigma.diameter`), the winning `DiameterIndex` can be
    * reconstructed from just the best `index` seen, tracked as a primitive `var` -- no `Option`/`DiameterIndex`/ tuple
    * boxing per candidate considered, only for the single final result.
    */
  private def zeroPivotCofacet(sigma: DiameterIndex, size: Int): Option[DiameterIndex] =
    if size - 1 > maxDimension then None
    else
      val vertices = si.decodeToArray(sigma.index, size)
      val cur = si.cofacetCursor(sigma.index, size, allCofacets = true)
      var bestIdx: Long = -1L
      var found = false
      while cur.hasNext do
        val tauFv = insertionDiameter(vertices, sigma.diameter, cur.vertex)
        if tauFv == sigma.diameter && (!found || cur.index > bestIdx) then
          bestIdx = cur.index
          found = true
        cur.advance()
      if found then Some(DiameterIndex(sigma.diameter, bestIdx)) else None

  /** `tau`'s facet tied at `tau`'s own value with the smallest index. No incremental shortcut exists for removing a
    * vertex's DIAMETER (same scope boundary `RipserCohomologyContext.zeroPivotFacet` documents -- `maxPairwiseDistance`
    * must still be fully recomputed per candidate), but as of the cursor-redesign session `FacetCursor` yields the
    * removed vertex directly, so the candidate's own VERTEX SET is built by array-removal from `tau`'s already-decoded
    * vertices rather than a fresh `decodeToArray(facetIdx, size - 1)` call -- verified sound by `SimplexIndexingSpec`'s
    * `FacetCursor` correctness property (`decodeToArray(cur.index, size-1).toSet == decodeToArray(startIndex,
    * size).toSet - cur.vertex`) before relying on it here. Hand-rolled `while` loop for the same reason as
    * `zeroPivotCofacet` above -- `.minByOption(_.index)` boxed every `Long` comparison, and `facetIterator` allocated a
    * boxed `Long` per step on top of that.
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
      // `HashMap.growTable`/`HashSet.growTable` entirely for the common case (measured as a real, if modest, CPU
      // cost in `.claude/WORKLOG-ripser-profiling.md`'s cursor-redesign follow-up) rather than the default
      // capacity forcing one or more table-doubling rehashes as each dimension's collections fill up. Pure
      // capacity hint, no behavior change: `HashMap`/`HashSet`'s own default `.empty` already returns this same
      // underlying type, and none of these three collections is ever iterated in an order-dependent way below
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
              log.items.foldLeft(Chain(sigma)) { case (acc, (pivot, coeff)) =>
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
