package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}

import org.appliedtopology.tda4j.barcode.PersistenceBar

import scala.collection.mutable

/** A parallel implementation of `RipserCohomologyContext` (`Homology.scala`), keyed on a packed `(Double, Long)`
  * diameter/combinatorial-index pair instead of a materialized `Simplex[Int]`/`SortedSet[Int]` -- Ripser's own
  * `diameter_index_t` representation, deliberately NOT adopted by the original engine (see `DiameterSimplex`'s own doc
  * in `Homology.scala`, which flags this as a live option for a future session). Built to test, not assume, whether
  * eliminating `SortedSet[Int]` as the thing carried/hashed/compared through `Chain.reduceBy`'s reduction closes some
  * of the ~20µs/simplex constant-factor tax measured against real `ripser.cpp` in
  * `.claude/WORKLOG-ripser-comparison.md` -- see `.claude/WORKLOG-packed-ripser-engine.md` for the measurement this
  * session actually ran. A genuinely SEPARATE file from `Homology.scala` (which already holds all four canonical
  * persistence algorithms per CLAUDE.md) on purpose: this is an experimental parallel engine, not a fifth member of
  * that canonical set, and `Homology.scala` is already large enough that a clearly-demarcated separate file keeps the
  * experiment from blending into the reference implementations it's being measured against.
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

  private val si: SimplexIndexing = SimplexIndexing(metricSpace.size)
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
  /** A plain `while` loop, not `sigma.underlying.iterator.map(u => metricSpace.distance(u, v)).max`, as of
    * `.claude/WORKLOG-ripser-profiling.md`'s follow-up session: the `.map(...)` closure captures `v`/`this` and is
    * allocated FRESH on every single call -- and every candidate cofacet vertex considered by
    * `sparseCofacets`/`coboundaryOf`/`zeroPivotCofacet`/`zeroApparentCofacet` calls this once, `O(vertexCount)` times
    * per simplex -- measured as this class's own single largest allocation source after the `binomial`/ cache/iterator
    * fixes above (7.5% of total allocation weight on real `sphere3_96` data, all under this one closure). `.max` on the
    * boxed-`Double` iterator this produced added its own boxing on top. Same fix applied identically to
    * `RipserCohomologyContext.insertionDiameter` (`Homology.scala`) -- the two were already byte-for-byte identical
    * implementations before this fix, and stay byte-for-byte identical after it.
    */
  private def insertionDiameter(sigma: Simplex[Int], sigmaFv: Double, v: Int): Double =
    var maxD = sigmaFv
    val it = sigma.underlying.iterator
    while it.hasNext do
      val d = metricSpace.distance(it.next(), v)
      if d > maxD then maxD = d
    maxD

  private val rawFiltrationValue = FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  /** The canonical (insert-above-own-maximum) cofacets of `sigma`, one per higher simplex that has `sigma` as its own
    * canonical facet -- packed analogue of `RipserCohomologyContext.sparseCofacets`. `size` is `sigma`'s own vertex
    * count (dimension + 1); a `DiameterIndex` doesn't carry its own dimension the way a `Simplex[Int]` does, so callers
    * thread `size` explicitly instead (constant within one outer-loop iteration -- see `persistentCohomology`).
    */
  private def sparseCofacets(sigma: DiameterIndex, size: Int): Iterator[DiameterIndex] =
    if size > maxDimension then Iterator.empty
    else
      val decoded = si(sigma.index, size)
      si.cofacetIteratorWithVertex(sigma.index, size, allCofacets = false)
        .map((v, tauIdx) => DiameterIndex(insertionDiameter(decoded, sigma.diameter, v), tauIdx))
        .filter(_.diameter <= resolvedMaxFiltrationValue)

  /** Packed analogue of `RipserCohomologyContext.coboundaryOf`: decodes `sigma` exactly ONCE (never decodes any `tau`
    * -- each cofacet's diameter comes from `insertionDiameter`, its identity from the index `cofacetIteratorWithVertex`
    * already produced). Guard mirrors the fixed `maxDimension` semantics: empty only past `maxDimension + 1` (`size - 1
    * > maxDimension`, i.e. `sigma`'s own dimension exceeds what's requested), not AT it -- see
    * `.claude/WORKLOG-maxdim-semantics-fix.md`.
    */
  def coboundaryOf(sigma: DiameterIndex, size: Int): Chain[DiameterIndex, CoefficientT] =
    if size - 1 > maxDimension then Chain.empty
    else
      val decoded = si(sigma.index, size)
      Chain.from(
        si.cofacetIteratorWithVertex(sigma.index, size, allCofacets = true)
          .flatMap { (v, tauIdx) =>
            val tauFv = insertionDiameter(decoded, sigma.diameter, v)
            if tauFv > resolvedMaxFiltrationValue then None
            else
              val position = decoded.underlying.count(_ < v)
              val sign = if position % 2 == 0 then fr.one else fr.negate(fr.one)
              Some((DiameterIndex(tauFv, tauIdx), sign))
          }
          .toSeq
      )

  private def zeroPivotCofacet(sigma: DiameterIndex, size: Int): Option[DiameterIndex] =
    if size - 1 > maxDimension then None
    else
      val decoded = si(sigma.index, size)
      si.cofacetIteratorWithVertex(sigma.index, size, allCofacets = true)
        .map((v, tauIdx) => DiameterIndex(insertionDiameter(decoded, sigma.diameter, v), tauIdx))
        .filter(_.diameter == sigma.diameter)
        .maxByOption(_.index)

  /** `tau`'s facet tied at `tau`'s own value with the smallest index. No incremental shortcut exists for removing a
    * vertex (same scope boundary `RipserCohomologyContext.zeroPivotFacet` documents), so each candidate facet is
    * decoded and its filtration value fully recomputed via the same `MaximumDistanceFiltrationValue` this codebase's
    * stream-based engines already use -- reused directly rather than reimplemented.
    */
  private def zeroPivotFacet(tau: DiameterIndex, size: Int): Option[DiameterIndex] =
    si.facetIterator(tau.index, size)
      .map { facetIdx =>
        val facetSimplex = si(facetIdx, size - 1)
        DiameterIndex(rawFiltrationValue(facetSimplex), facetIdx)
      }
      .filter(_.diameter == tau.diameter)
      .minByOption(_.index)

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
    // `nextCleared` accumulates dimension-(d+1) clears as they're discovered THIS iteration, then rotates in.
    var activeCleared: mutable.Set[Long] = mutable.Set.empty
    var nextCleared: mutable.Set[Long] = mutable.Set.empty

    // Dimension-0 candidates: every vertex, diameter 0.0 -- index IS the vertex id (SimplexIndexing.apply's own
    // d==0 base case: a single vertex v decodes to/from index v directly).
    var currentLevel: Seq[DiameterIndex] =
      (0 until metricSpace.size).map(v => DiameterIndex(0.0, v.toLong))

    for d <- 0 to maxDimension do
      val size = d + 1
      val simplicesAtD: Seq[DiameterIndex] = currentLevel.sorted(using packedOrdering.reverse)
      _totalSimplexCount += simplicesAtD.size

      val basis: mutable.Map[DiameterIndex, Chain[DiameterIndex, CoefficientT]] = mutable.Map.empty
      val generators: mutable.Map[DiameterIndex, Chain[DiameterIndex, CoefficientT]] = mutable.Map.empty

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
      nextCleared = mutable.Set.empty

    bars.toList
