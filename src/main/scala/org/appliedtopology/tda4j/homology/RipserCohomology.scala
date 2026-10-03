package org.appliedtopology.tda4j

import scala.collection.mutable

/** '''Test/reference oracle only -- not a production engine, and not what `TDA4j.scala`'s `engine="ripser"` calls.'''
  * `PackedRipserCohomologyEngine` (`PackedRipserCohomology.scala`) is the production Ripser engine: same algorithm,
  * method for method, keyed on a packed `(Double, Long)` pair instead of a materialized `Simplex[Int]`, measured faster
  * and dramatically leaner on memory on real paper data. This class's remaining job is narrower than "a slower
  * production alternative": it's what `PackedRipserCohomologySpec` cross-validates the packed engine's
  * `DiameterIndex`-specific machinery against (its index-only `equals`/`hashCode`, its index-keyed
  * `basis`/`generators`/`cleared` maps) -- representation bugs no other spec would catch. It does NOT independently
  * validate the Ripser ALGORITHM itself (both engines share `SimplexIndexing`); that job belongs to
  * `SimplicialHomologyEngine`, a genuinely different, boundary-based algorithm with no shared code path.
  *
  * Persistent cohomology via Ulrich Bauer's Ripser algorithm (arXiv:1908.02518), specialized to `Simplex[Int]`
  * Vietoris-Rips/clique complexes via the combinatorial number system (`SimplexIndexing`) -- a deliberate narrowing
  * from `CellularHomologyEngine`'s generic `CellT: OrderedCell`, agreed with the project lead (`.claude/
  * WORKLOG-naive-homology.md`). One-shot: computes the full barcode in a single pass, no incremental querying.
  *
  * Includes clearing (see the `cleared` set in `persistentCohomology`): NOT merely a performance optimization on top of
  * an already-correct baseline -- a version without it produces spurious essential classes from dimension-d simplices
  * already claimed as pivots one dimension down, violating Proposition 3.1's "not a pivot anywhere" clause
  * (`.claude/WORKLOG-cohomology.md`). Do not "simplify" this against intuition without rereading that derivation.
  *
  * Also includes a PARTIAL apparent-pairs optimization (see `zeroApparentCofacet` and its use in
  * `persistentCohomology`) -- partial, and that qualifier matters: what's implemented is Definition 3.2 apparent-pair
  * identification wired into the reduction loop to SKIP `Chain.reduceBy`'s reduction pass for an apparent sigma (it's
  * guaranteed to be a no-op by Proposition 3.9/Lemma 3.3), NOT Ripser's further, larger optimization of never building
  * an apparent sigma's coboundary at all unless some other column's reduction actually needs it. `coboundaryOf(sigma)`
  * is still called in full on the shortcut path -- `basis(tau)` needs the COMPLETE reduced column (all of sigma's
  * cofacets, not just tau), not a truncated single-term stand-in. Measured a 1.35x-1.8x wall-clock win on n=12-20 point
  * random VR complexes at maxDimension=2 (growing with n): `Chain.reduceBy`'s recursive per-pivot `SortedMap` fold, not
  * the coboundary enumeration, dominates this loop's cost, so skipping just the reduction pass is still worthwhile.
  * Unlike Ripser's own C++ implementation, this shortcut does NOT need Ripser's `assemble_columns_to_reduce` exclusion
  * step or its `compute_pairs` on-the-fly substitution fallback -- this engine never removes a simplex from the set it
  * iterates over at a given dimension, so the true (mutual) Definition 3.2 apparent-pair check alone is sufficient for
  * THIS optimization to be safe. Getting the larger, lazy optimization would require also changing how
  * `persistentCohomology` enumerates each dimension's simplices in the first place (currently an eager
  * `(0 until binomial(n, d+1))` over the whole complex, unlike Ripser's own incrementally- assembled
  * `columns_to_reduce`) -- a separate, larger, not-yet-attempted project. See `.claude/WORKLOG-cohomology.md` for the
  * full derivation, grounded directly in Ripser's own `ripser.cpp` source and arXiv:1908.02518's Definition 3.2/3.11
  * and Proposition 3.9/Lemma 3.3.
  *
  * `useApparentPairs` (default `true`) exists so `ApparentPairsBenchmarkSpec` can isolate this optimization's own
  * effect: with it `false`, `persistentCohomology` always falls through to the ordinary `Chain.reduceBy` path, byte for
  * byte the same output as when it's `true` -- see `zeroApparentCofacet`'s doc for why the shortcut is provably a
  * no-op. Not intended as a user-facing tuning knob outside benchmarking.
  */
/** `maxFiltrationValue` defaults to `metricSpace.minimumEnclosingRadius`, not `Double.PositiveInfinity`: beyond that
  * radius, every vertex is within range of every other, so the complex is a cone from that point on and provably
  * contributes no further homology (Ripser paper, p. 412). Cutting there computes the exact same barcode over fewer
  * simplices -- confirmed against `RipserCohomologySpec`'s "thresholded barcode = untruncated barcode restricted to [0,
  * t]" test machinery -- unlike a genuine finite `maxFiltrationValue` below the enclosing radius, which DOES drop real
  * bars and remains an explicit, deliberate truncation the caller opts into. Still matches the production Ripser
  * engines' sparse-Rips convention, still NOT `AlphaShapeDQP`'s always-untruncated one. Pass `Double.PositiveInfinity`
  * explicitly for the old always-unbounded behavior.
  *
  * `memoizeFiltrationValue` (default `false`) is unaffected by this: Ripser's own historical design goal was memory
  * frugality, not raw speed, and a global cache of every filtration value ever touched runs directly against that on
  * large complexes. The actual replacement is `insertionDiameter`'s incremental diameter formula (below), which
  * eliminates the O(d^2) `MaximumDistanceFiltrationValue` recomputation for cofacet enumeration entirely, rather than
  * paying for it once and caching the answer.
  */
/** `maxDimension` means "top HOMOLOGICAL DEGREE reported," not "top simplex dimension built" -- fixed at the source
  * (previously only worked around at the MATLAB facade layer, which built `requestedMaxDimension + 1` internally and
  * filtered the extra dimension back out; see `.claude/WORKLOG-maxdim-semantics-fix.md`). Before this fix,
  * `coboundaryOf`/`zeroPivotCofacet` refused to look past `sigma.dim + 1 > maxDimension`, i.e. `sigma.dim ==
  * maxDimension` always got a trivially-empty coboundary and therefore always came out essential -- a well-known
  * truncation artifact (H_k needs (k+1)-chains to resolve correctly), not real information about H_maxDimension. Fixed
  * by relaxing that guard to `sigma.dim > maxDimension`: a real `(maxDimension + 1)`-simplex is now enumerated on the
  * fly, transiently, whenever needed to resolve a dimension-`maxDimension` pairing -- never materialized into its own
  * `currentLevel`/reduced as its own column, so `totalSimplexCount` and the main loop's own bounds are unchanged; only
  * the two guards moved. Any external caller previously passing `maxDimension + 1` and filtering out
  * `dim == maxDimension + 1` bars itself should now pass the real requested degree directly and drop that workaround.
  */
class RipserCohomologyEngine[CoefficientT: Field](
  metricSpace: FiniteMetricSpace[Int],
  maxDimension: Int,
  useApparentPairs: Boolean = true,
  // None means "not explicitly set," resolved to metricSpace.minimumEnclosingRadius just below -- an ordinary
  // Option default, not a magic-value sentinel: None is a constant, so it doesn't hit Scala 3's restriction on
  // a default referencing an earlier same-list parameter (metricSpace) the way a literal
  // `= metricSpace.minimumEnclosingRadius` default would. See the class doc above this class for why the
  // resolved default itself changed from Double.PositiveInfinity to metricSpace.minimumEnclosingRadius.
  maxFiltrationValue: Option[Double] = None,
  memoizeFiltrationValue: Boolean = false
):

  private val resolvedMaxFiltrationValue: Double =
    maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)

  private val rawFiltrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  /** Optionally-memoized wrapper around `rawFiltrationValue`: `MaximumDistanceFiltrationValue.apply` recomputes an
    * O(d^2) max over pairwise vertex distances from scratch on every call, with no caching of its own. Gated behind
    * `memoizeFiltrationValue` (default `false` -- see the class doc above for why): the enumeration/assembly path
    * (`insertionDiameter`, `sparseCofacets`, `persistentCohomology`'s own per-dimension loop) never goes through this
    * at all, carrying diameters incrementally instead, so this field's remaining callers are `cohomologyOrdering`
    * (consulted on every `SortedMap`/`PriorityQueue` comparison inside `Chain.reduceBy`'s reduction machinery) plus the
    * handful of once-per-simplex lookups in `coboundaryOf`/`zeroPivotCofacet`/`zeroPivotFacet`/bar-endpoint reporting.
    * See `.claude/WORKLOG-lazy-enumeration.md` for the measured cost of leaving this `false`.
    */
  val filtrationValue: PartialFunction[Simplex[Int], Double] =
    if memoizeFiltrationValue then
      new PartialFunction[Simplex[Int], Double]:
        private val cache: mutable.HashMap[Simplex[Int], Double] = mutable.HashMap.empty
        def isDefinedAt(spx: Simplex[Int]): Boolean = rawFiltrationValue.isDefinedAt(spx)
        def apply(spx: Simplex[Int]): Double = cache.getOrElseUpdate(spx, rawFiltrationValue(spx))
    else rawFiltrationValue

  private val fr = summon[CoefficientT is Field]

  /** Shared comparator logic for "ascending by filtration value, ties broken so a LARGER combinatorial index sorts as
    * OLDER (smaller)" -- factored out so a diameter-CARRYING ordering (`diameterSimplexOrdering`, below) can reuse the
    * exact same tie-break instead of being a second, independently-written comparator that might silently disagree on a
    * tie (the trap this codebase's own history -- `NOTES-for-guides.md` item 3 -- flags explicitly). Takes raw
    * `(filtrationValue, combinatorialIndex)` pairs rather than `Simplex[Int]` so callers who already have the
    * filtration value in hand (i.e. don't need to call `filtrationValue` at all) can avoid it.
    */
  private def compareFvThenIndex(xFv: Double, xIdx: Long, yFv: Double, yIdx: Long): Int =
    val fc = java.lang.Double.compare(xFv, yFv)
    if fc != 0 then fc else java.lang.Long.compare(yIdx, xIdx)

  /** Ascending by filtration value; ties broken so a LARGER combinatorial index sorts as OLDER (smaller) -- the
    * lexicographically-refined tie-break Definition 3.2/Proposition 3.9 rely on. `Chain`'s `leadingCell` is the MINIMUM
    * under whatever `Ordering` is supplied (`Chain.from` builds its `PriorityQueue` with `ord.reverse`), and persistent
    * cohomology's pivot is the OLDEST cofacet in the reduced coboundary chain -- so this ascending ordering, not a
    * reversed one, is what the coboundary-side `Chain`/`RingModule` machinery needs in scope. This is the opposite
    * convention from `CellularHomologyEngine`'s `stream.filtrationOrdering`, which is deliberately reversed so ITS
    * `leadingCell` means youngest -- see WORKLOG-cohomology.md for the full "transpose + reverse filtration order"
    * derivation from the paper. Safe to declare at class scope (unlike the `chainRM` hazard documented on
    * `CellularHomologyEngine`): this ordering is self-contained, built directly from `filtrationValue`/`si` rather than
    * by summoning some other ambient `Ordering`, so there is no stream-not-yet-available timing issue to worry about
    * here.
    */
  given cohomologyOrdering: Ordering[Simplex[Int]]:
    def compare(x: Simplex[Int], y: Simplex[Int]): Int =
      compareFvThenIndex(filtrationValue(x), si(x), filtrationValue(y), si(y))

  /** A simplex paired with its ALREADY-KNOWN filtration value, carried through enumeration/assembly so it never needs
    * to be recomputed (the `insertionDiameter` incremental formula below, not `filtrationValue`/`MaximumDistance
    * FiltrationValue`'s O(d^2) recompute, is how a cofacet's `diameter` field gets produced in the first place). This
    * is this codebase's analogue of Ripser's own `diameter_index_t` -- deliberately NOT the same compact
    * `(Double, Int)` representation Ripser actually uses (Ripser stores a combinatorial index, not a materialized
    * `Simplex[Int]`/`SortedSet[Int]`): carrying the full `Simplex[Int]` is a simplicity/speed choice made AGAINST the
    * project's stated memory goal, not an oversight -- flagged here as a live option for a future session, not
    * something to silently "fix" by trying to swap in a raw-index representation without re-deriving what else that
    * would touch (every downstream consumer currently expects a `Simplex[Int]`).
    *
    * WARNING: do not use `DiameterSimplex` as a `Set`/`Map` key anywhere -- its case-class equality includes the
    * `Double` diameter, so two carriers for the textually-same simplex could compare unequal on floating-point noise.
    * `cleared`/`basis`/`generators` are and must stay keyed by `.simplex` directly, never by a `DiameterSimplex`.
    */
  private final case class DiameterSimplex(diameter: Double, simplex: Simplex[Int])

  private val diameterSimplexOrdering: Ordering[DiameterSimplex] =
    (x: DiameterSimplex, y: DiameterSimplex) => compareFvThenIndex(x.diameter, si(x.simplex), y.diameter, si(y.simplex))

  /** Ripser's actual cofacet-diameter recurrence (`simplex_coboundary_enumerator` in `ripser.cpp`): a cofacet formed by
    * inserting vertex `v` into `sigma` has diameter `max(sigma's own diameter, max over sigma's vertices of the
    * distance to v)` -- O(d) given `sigma`'s already-known diameter, vs. `MaximumDistanceFiltrationValue.apply`'s
    * O(d^2) full pairwise recompute from scratch, which is ignorant of any already-known partial answer. This is what
    * makes carrying `DiameterSimplex` through enumeration strictly better than caching: the expensive computation is
    * eliminated, not paid for once and reused. Valid for inserting ANY vertex, not just ones satisfying the "above
    * sigma's own max" canonical-cofacet convention `sparseCofacets` uses below -- so this is also used inside
    * `coboundaryOf`/`zeroPivotCofacet`, which need to consider cofacets from inserting vertices in general.
    *
    * The actual array-indexing loop is `PackedRipserCohomology.scala`'s top-level `insertionDiameter`, shared with
    * `PackedRipserCohomologyEngine`: this class hoists `sigma`'s vertex array from an already-materialized
    * `Simplex[Int]` at each of its own call sites, where the packed engine decodes it from a combinatorial index first,
    * but the loop itself is the same function.
    */
  /** The canonical cofacets of `sigma` -- one per higher simplex that has `sigma` as ITS canonical facet (the facet
    * obtained by removing its own maximum vertex) -- generated by inserting a vertex strictly greater than `sigma`'s
    * own maximum, the same convention `SimplexIndexing.topCofacetIterator`/`cofacetIterator(..., allCofacets = false)`
    * already uses and `SimplexIndexingSpec` already verifies against the paper's worked examples. This generates each
    * dimension-(d+1) simplex from EXACTLY one dimension-d source, so no deduplication is needed when assembling a whole
    * dimension's worth of candidates from the previous dimension's simplices (`persistentCohomology`'s
    * `currentLevel.iterator.flatMap(sparseCofacets)`) -- see WORKLOG-lazy-enumeration.md's uniqueness argument.
    *
    * Deliberately built by direct `SortedSet` insertion (`sigma.simplex.underlying + v`), NOT by routing through
    * `SimplexIndexing.cofacetIterator` + `si(idx, ...)` decode: the combinatorial-index round-trip costs O(d log n) per
    * candidate for information (the inserted vertex) this method already has directly from the loop variable, where a
    * direct `SortedSet` insertion is O(d). `maxFiltrationValue` is enforced here -- this is the one place in the engine
    * that actually EXCLUDES a simplex from existing in the complex at all, as opposed to `coboundaryOf`'s
    * within-an-existing-simplex's-coboundary filtering.
    */
  private def sparseCofacets(sigma: DiameterSimplex): Iterator[DiameterSimplex] =
    if sigma.simplex.dim + 1 > maxDimension then Iterator.empty
    else
      val vertices = sigma.simplex.underlying.toArray
      val maxVertex = vertices(vertices.length - 1)
      (maxVertex + 1 until metricSpace.size).iterator
        .map { v =>
          DiameterSimplex(
            insertionDiameter(metricSpace, vertices, sigma.diameter, v),
            (sigma.simplex.underlying + v).asSimplex
          )
        }
        .filter(_.diameter <= resolvedMaxFiltrationValue)

  /** Coboundary of sigma, implicitly restricted to the maxFiltrationValue-thresholded complex. `maxDimension` means
    * "top homological degree reported," not "top simplex dimension built" (see
    * `.claude/WORKLOG-maxdim-semantics-fix.md` for the full derivation of this distinction and why it matters):
    * correctly resolving whether a dimension- `maxDimension` class is finite or essential needs a REAL coboundary
    * against genuine `(maxDimension + 1)`-simplices, so this method only refuses to look past `maxDimension + 1`
    * (`sigma.dim > maxDimension`), not `maxDimension` itself. Those `(maxDimension + 1)`-simplices are enumerated here
    * on the fly, via `si.cofacetIterator`, and never separately materialized into `currentLevel`/`simplicesAtD` --
    * `persistentCohomology`'s own loop never runs a `d == maxDimension + 1` iteration, so nothing above `maxDimension`
    * is ever independently reduced as its own column; it exists only transiently, as a pairing target for the
    * dimension-`maxDimension` column that needs it. A candidate cofacet past `maxFiltrationValue` is filtered out via
    * `insertionDiameter`'s O(d) incremental formula (one `filtrationValue(sigma)` call for `sigma` itself, not one per
    * candidate) rather than `filtrationValue(tau)`'s O(d^2) full recompute per candidate -- this is the one place
    * `coboundaryOf` genuinely needs a diameter it doesn't already have (it considers ALL of sigma's cofacets, not just
    * tied ones, unlike `zeroPivotCofacet` below). Sign convention dual to `Simplex.scala`'s boundary: `(-1)^`(number of
    * sigma's vertices smaller than the inserted vertex).
    */
  /** Built directly on `SimplexIndexing.CofacetCursor`, not `si.cofacetIterator` + `si(idx, ...)` decode
    * (`.claude/WORKLOG-ripser-profiling.md`): the vertex-less `cofacetIterator` would force every candidate to be fully
    * decoded back into a `Simplex[Int]` and then linearly scanned just to recover the ONE vertex `CofacetCursor`
    * already hands over directly as `cur.vertex`, on top of `cofacetIteratorWithVertex`'s own `(Int, Long)` tuple
    * allocation per candidate -- once the single largest remaining allocation source measured in this engine. `tau` is
    * built by inserting `cur.vertex` directly into `sigma`'s own vertex set (`sigma.underlying + v`, O(log d)), the
    * same incremental-insertion `sparseCofacets` above already uses.
    */
  def coboundaryOf(sigma: Simplex[Int]): Chain[Simplex[Int], CoefficientT] =
    if sigma.dim > maxDimension then Chain.empty
    else
      val sigmaFv = filtrationValue(sigma)
      val vertices = sigma.underlying.toArray
      val cur = si.cofacetCursor(si(sigma), sigma.size, allCofacets = true)
      val buffer = mutable.ArrayBuffer.empty[(Simplex[Int], CoefficientT)]
      while cur.hasNext do
        val v = cur.vertex
        if insertionDiameter(metricSpace, vertices, sigmaFv, v) <= resolvedMaxFiltrationValue then
          val tau = (sigma.underlying + v).asSimplex
          // `vertices` is sorted ascending, so this is a plain scan, not `sigma.underlying.count(_ < v)` -- see
          // `insertionDiameter`'s doc above for why a `SortedSet` operation here allocates an iterator on every
          // single candidate.
          var position = 0
          while position < vertices.length && vertices(position) < v do position += 1
          val sign = if position % 2 == 0 then fr.one else fr.negate(fr.one)
          buffer += ((tau, sign))
        cur.advance()
      Chain.from(buffer.toSeq)

  /** Coboundary of a whole chain, linearly extending `coboundaryOf`. Unlike `Chain.scala`'s `.boundary` extension
    * (intrinsic to a cell), a coboundary is extrinsic -- it depends on which higher-dimensional simplices exist in this
    * (possibly truncated) complex -- so it lives here rather than as a general-purpose `Chain` extension. Used by tests
    * to check that a representative essential cocycle genuinely has zero coboundary -- genuinely meaningful at every
    * dimension up to and including `maxDimension` (not vacuously true at the top dimension anymore, since
    * `coboundaryOf` now computes a real coboundary there too; see its own doc).
    */
  def coboundaryOfChain(c: Chain[Simplex[Int], CoefficientT]): Chain[Simplex[Int], CoefficientT] =
    Chain.from(c.rawEntries.flatMap { case (cell, coeff) =>
      coboundaryOf(cell).rawEntries.map { case (tau, sign) => (tau, fr.times(coeff, sign)) }
    })

  /** `sigma`'s cofacet tied at `sigma`'s own filtration value with the LARGEST combinatorial index, i.e. the "oldest
    * cofacet" in Definition 3.2/3.11's sense (`cohomologyOrdering` sorts a larger index as older). Built directly
    * against `si.cofacetIterator`'s full (unrestricted) enumeration, NOT a vertex-insertion-restricted one like
    * `Cofacets.scala`'s `apparentVertex` -- see CLAUDE.md: restricting to cofacets formed by inserting a vertex
    * strictly greater than sigma's own maximum silently misses a real tied cofacet whenever sigma already contains
    * `vertexCount - 1` (a false negative, not merely an inefficiency). Guarded at `sigma.dim > maxDimension`, the same
    * boundary `coboundaryOf` uses and for the same reason (see its doc): a real tied cofacet at
    * `sigma.dim == maxDimension` must be found too, or the apparent-pairs shortcut would silently miss genuine pairs at
    * the requested top dimension. `SimplexIndexing`'s raw iterators have no notion of any dimension cap on their own,
    * hence the explicit guard. Tau's diameter is computed via `insertionDiameter`'s O(d) incremental formula, not
    * `filtrationValue(tau)`'s O(d^2) recompute. No SEPARATE `maxFiltrationValue` guard is needed here (unlike
    * `coboundaryOf`, which considers every cofacet, not just tied ones): this method only ever selects a tau tied at
    * `sigma`'s own value, and `sigma` is only ever called with here if it's already within the threshold (guaranteed by
    * construction -- see `sparseCofacets`), so any tied tau is automatically within threshold too.
    */
  /** A hand-rolled `while` loop over `CofacetCursor` directly (`.claude/WORKLOG-ripser-profiling.md`), not
    * `.filter(...).maxByOption((tau, _) => si(tau))`: `maxByOption` boxes every `Long` key comparison, and `si(tau)`
    * would RE-ENCODE a simplex whose combinatorial index (`idx`, from `si.cofacetIterator`) is already in hand, a fully
    * redundant round trip through `searchRow`/`binomialEntry` (`SimplexIndexingSpec`'s round-trip property confirms
    * decode-then-encode is the identity, so reusing `idx` directly is safe). This method only ever needs the SINGLE
    * winning candidate, so `tau` is never built until the loop finishes -- only `bestVertex`/`bestIdx` (primitives) are
    * tracked per candidate, and `sigma.underlying + bestVertex` runs once, for the winner.
    */
  /** Returns on the FIRST candidate tied at `d`, not a full sweep tracking a running max index -- sound (not just
    * faster) because `CofacetCursor.index` is STRICTLY DECREASING across successive `advance()` calls
    * (`SimplexIndexingSpec`'s own property test pins this), so the first tied candidate encountered already has the
    * maximum index among every candidate that will ever tie. Matches real `ripser.cpp`'s own `get_zero_pivot_cofacet`,
    * which returns on first match for the same reason. Found via the `o3_1024` compute-server JFR profile
    * (`.claude/WORKLOG-packed-ripser-engine.md`): this method's own full, UNCONDITIONAL sweep (every candidate vertex,
    * every simplex in the complex, regardless of threshold) was the largest remaining driver of `insertionDiameter`
    * calls once the metric-space distance cache removed the earlier dominant cost.
    */
  private def zeroPivotCofacet(sigma: Simplex[Int]): Option[Simplex[Int]] =
    if sigma.dim > maxDimension then None
    else
      val d = filtrationValue(sigma)
      val vertices = sigma.underlying.toArray
      val cur = si.cofacetCursor(si(sigma), sigma.size, allCofacets = true)
      while cur.hasNext do
        val tauFv = insertionDiameter(metricSpace, vertices, d, cur.vertex)
        if tauFv == d then return Some((sigma.underlying + cur.vertex).asSimplex)
        cur.advance()
      None

  /** `tau`'s facet tied at `tau`'s own filtration value with the SMALLEST combinatorial index, i.e. the "youngest
    * facet" in Definition 3.2/3.11's sense. No `maxDimension` guard needed: a facet is always one dimension lower than
    * `tau`, which is already within the truncated complex by construction (it only exists as some `sigma`'s candidate
    * cofacet, already dimension-checked by `zeroPivotCofacet` above). Likewise no `maxFiltrationValue` guard: by
    * Vietoris-Rips monotonicity a facet's diameter can only be <= its coface's, so if `tau` is within threshold every
    * one of its facets automatically is too. Candidate facets' diameters are NOT computed incrementally here, unlike
    * the cofacet direction (`insertionDiameter`) -- removing a vertex doesn't admit the same cheap O(d) recurrence
    * (whether the diameter changes at all depends on whether the removed vertex realized `tau`'s own maximum pairwise
    * distance, which isn't tracked) -- so this remains a `filtrationValue(sigma)` call per candidate. Left as a scope
    * boundary, not an oversight: see `.claude/WORKLOG-lazy-enumeration.md`.
    */
  /** Hand-rolled for the same two reasons as `zeroPivotCofacet` above: `.minByOption(sigma => si(sigma))` boxes every
    * `Long` comparison, AND `si(sigma)` re-encodes a simplex just decoded from `idx`, which already IS `sigma`'s own
    * index. The ONE genuinely necessary encode call, `si(tau)` (seeding `facetIterator` with `tau`'s own index, since a
    * facet iterator needs to know what it's removing a vertex FROM), stays -- it happens once per `zeroPivotFacet`
    * call, not once per candidate, so it was never part of either cost.
    */
  /** Built on `FacetCursor` directly: `FacetCursor.vertex` is the vertex REMOVED to reach `.index` (verified against
    * `decodeToArray` independently by `SimplexIndexingSpec`'s `FacetCursor` property), so each candidate's vertex set
    * is built by removing ONE vertex from `tau`'s own already-materialized `underlying` (`tau.underlying - v`, O(log
    * d)) rather than a full combinatorial decode. `filtrationValue(sigma)` itself still needs the full candidate vertex
    * set on every candidate, unlike the cofacet direction's `insertionDiameter` -- no incremental shortcut exists for
    * removing a vertex's diameter contribution, so `sigma` can't be deferred to just the winner the way
    * `zeroPivotCofacet` defers `tau`.
    */
  /** Returns on the FIRST tied candidate for the same reason `zeroPivotCofacet` above does, mirrored: `FacetCursor.
    * index` is STRICTLY INCREASING across successive `advance()` calls (same property test), so the first tied
    * candidate already has the MINIMUM index -- the direction this method wants.
    */
  private def zeroPivotFacet(tau: Simplex[Int]): Option[Simplex[Int]] =
    val d = filtrationValue(tau)
    val cur = si.facetCursor(si(tau), tau.size)
    while cur.hasNext do
      val sigma = (tau.underlying - cur.vertex).asSimplex
      if filtrationValue(sigma) == d then return Some(sigma)
      cur.advance()
    None

  /** `Some(tau)` iff `(sigma, tau)` is a genuine (mutual) Definition 3.2 apparent pair: tau is sigma's oldest tied
    * cofacet, AND sigma is, symmetrically, tau's youngest tied facet. Verified against the hand-derived
    * `threePointLine` fixture in `RipserCohomologySpec` (`{0,2}` paired with the triangle `{0,1,2}`, both born at 3.0).
    * Deliberately just the mutual check, not Ripser's own broader "emergent pair" condition (`ripser.cpp`'s
    * `init_coboundary_and_get_pivot`, which also has to guard against a *different*, non-apparent simplex racing for
    * the same tau) -- that broader condition exists in Ripser only because Ripser additionally removes tau from the
    * pool of simplices it ever separately reduces, which forces it to also handle tau turning up as some *other*
    * column's intermediate pivot via a substitution fallback. This engine does neither: `persistentCohomology`'s loop
    * below still visits every non-cleared simplex, so the mutual pair's `sigma` is *always* the first (youngest,
    * smallest-index) simplex whose raw, unreduced coboundary can possibly have `tau` as its leading term under
    * `cohomologyOrdering` -- any tied-diameter cofacet of any simplex necessarily is that simplex's chain minimum, by
    * Vietoris-Rips monotonicity -- so `tau` can never already be claimed in `basis` by anything else by the time
    * `sigma`'s turn comes up. See `.claude/WORKLOG-cohomology.md` for the full derivation, including why this makes the
    * shortcut in `persistentCohomology` provably behavior-preserving rather than merely empirically-checked.
    */
  private def zeroApparentCofacet(sigma: Simplex[Int]): Option[Simplex[Int]] =
    for
      tau <- zeroPivotCofacet(sigma)
      partner <- zeroPivotFacet(tau)
      if partner == sigma
    yield tau

  /** Mirror of `zeroApparentCofacet`, entered from the other side: `Some(sigma)` iff `tau` has a facet `sigma` such
    * that `(sigma, tau)` is a genuine (mutual) Definition 3.2 apparent pair. This is the lookup Ripser's
    * `compute_pairs` performs when some OTHER column's reduction reaches `tau` as an unresolved pivot: the substitution
    * is a fresh recomputation every time, with NO cache anywhere in Ripser's own implementation. Mirrored here for the
    * same reason, not out of caution: `zeroApparentCofacet`'s soundness proof only establishes that `sigma` is the
    * first simplex whose RAW, unreduced coboundary can reach `tau` -- it says nothing about whether some other column's
    * own mid-cascade, already-partially-reduced working chain could reach `tau` as an intermediate pivot before
    * `sigma`'s own turn in the outer sweep. A map recording "who claimed this pair first" would need to answer that
    * question to be trustworthy; a pure recomputation from Definition 3.2 doesn't, because `sigma` is the mutual
    * apparent partner of `tau` as a fact about filtration values and combinatorial indices alone, independent of when
    * or how `tau` was reached -- a recorded `tau -> sigma` map was considered and rejected for exactly this reason. See
    * `.claude/WORKLOG-lazy-enumeration.md`.
    */
  private def zeroApparentFacet(tau: Simplex[Int]): Option[Simplex[Int]] =
    for
      sigma <- zeroPivotFacet(tau)
      partner <- zeroPivotCofacet(sigma)
      if partner == tau
    yield sigma

  private var _substitutionCount: Int = 0

  /** How many times the on-the-fly substitution above actually fired during the most recent `persistentCohomology()`
    * call -- i.e. how many times some OTHER column's reduction reached an apparent pair's tau as an unresolved pivot
    * and had to recompute that pair's coboundary on the fly. Exposed purely for testing: WORKLOG-lazy-enumeration.md's
    * whole point is that this should be RARE (most apparent pairs are never looked up by anyone else's reduction) -- a
    * test that only checks the final barcode is unchanged cannot distinguish "the fallback fired and computed
    * correctly" from "the fallback never fired at all," so a discriminating test needs this counter, not just the bars.
    */
  def substitutionCount: Int = _substitutionCount

  private var _totalSimplexCount: Int = 0

  /** Total number of simplices actually assembled across all dimensions during the most recent `persistentCohomology()`
    * call -- exposed for testing. NOT `Σ binomial(n, d+1)`: that formula assumes every combinatorially-possible subset
    * exists, which is only true at `maxFiltrationValue = +Infinity`. For a genuinely thresholded complex most subsets
    * never get generated at all (see `sparseCofacets`), so the `finite*2 + essential == totalSimplices` structural
    * invariant `RipserCohomologySpec` checks needs THIS count, not the binomial formula, once a finite threshold is in
    * play.
    */
  def totalSimplexCount: Int = _totalSimplexCount

  def persistentCohomology(): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    // Summoned here, not any earlier -- see class doc above and CellularHomologyEngine's class doc for
    // why a Chain[...] is RingModule instance's summon-time Ordering[CellT] scoping matters.
    val chainRM = summon[Chain[Simplex[Int], CoefficientT] is RingModule]
    import chainRM.*

    _substitutionCount = 0
    _totalSimplexCount = 0
    val bars = mutable.ArrayDeque.empty[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]]

    // Clearing: a d-simplex that was already claimed as the PIVOT of some (d-1)-simplex's reduction
    // (i.e. it's the death side of a bar already recorded one dimension down) MUST NOT be independently
    // considered when reducing dimension d's own coboundary matrix -- carried across dimensions, not
    // reset. This is NOT an optional speedup here (see WORKLOG-cohomology.md's "clearing is required for
    // correctness" finding, confirmed by hand-deriving H^1 of a 3-cycle graph and catching this exact
    // engine reporting 2-3 spurious essential classes instead of the correct 0-1): Proposition 3.1 defines
    // essential indices as {i | R_i = 0 AND i is not a pivot anywhere}, and skipping that second condition
    // is precisely the bug this set exists to prevent. A cleared simplex contributes no bar at all (its
    // bar was already recorded when it was claimed as a pivot).
    val cleared: mutable.Set[Simplex[Int]] = mutable.Set.empty

    // On-the-fly apparent-pair substitution (Ripser's `compute_pairs`, no cache -- see zeroApparentFacet's
    // doc and `.claude/WORKLOG-lazy-enumeration.md`). Consulted by `Chain.reduceBy` below ONLY when a
    // working chain's leading pivot has no `basis` entry -- which, since apparent pairs never write one
    // (see the `Some(tau)` branch below), is exactly the case for an apparent pair's tau whenever some
    // OTHER column's reduction happens to reach it. This is what lets `persistentCohomology` skip
    // `coboundaryOf(sigma)` ENTIRELY for every apparent pair nobody else's reduction ever touches, not
    // merely skip the reduction pass while still eagerly computing `coboundaryOf(sigma)` to populate
    // `basis(tau)` "just in case."
    val basisFallback: Simplex[Int] => Option[Chain[Simplex[Int], CoefficientT]] =
      if useApparentPairs then
        (tau: Simplex[Int]) =>
          zeroApparentFacet(tau).map { sigma =>
            _substitutionCount += 1
            coboundaryOf(sigma)
          }
      else (_: Simplex[Int]) => None

    // Dimension-0 candidates: every vertex, diameter 0.0 by convention (matches
    // MaximumDistanceFiltrationValue's own `spx.dim <= 0 then 0.0`). This, not `(0 until binomial(n,
    // d+1))`-style direct combinatorial indexing, is the seed of the incrementally-assembled candidate
    // list every higher dimension is built from -- see `.claude/WORKLOG-lazy-enumeration.md`.
    var currentLevel: Seq[DiameterSimplex] =
      (0 until metricSpace.size).map(v => DiameterSimplex(0.0, Simplex(v)))

    for d <- 0 to maxDimension do
      // Youngest first: Algorithm 1 processes columns in increasing [matrix] order, which under the
      // reversed-order coboundary matrix means decreasing real filtration order. Structural, not a
      // performance tweak -- see WORKLOG-cohomology.md. Sorted via `diameterSimplexOrdering` (sharing
      // `compareFvThenIndex` with `cohomologyOrdering`, so this is provably the same tie-break, not a
      // second independently-written comparator) over the CARRIED diameters, not recomputed ones.
      val simplicesAtD: Seq[DiameterSimplex] = currentLevel.sorted(using diameterSimplexOrdering.reverse)
      _totalSimplexCount += simplicesAtD.size

      // Pivot (dimension d+1 simplex) -> reduced coboundary column, reset per dimension: dimension d's
      // coboundary matrix delta: C^d -> C^{d+1} is reduced independently of every other dimension's.
      val basis: mutable.Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]] = mutable.Map.empty
      // Pivot -> the V-column (a dimension-d chain) of whichever d-simplex claimed that pivot.
      val generators: mutable.Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]] = mutable.Map.empty

      for ds <- simplicesAtD if !cleared.contains(ds.simplex) do
        val sigma = ds.simplex
        // sigma's own diameter is already known (this dimension's assembly just computed it) -- using it
        // directly for bar-endpoint reporting below avoids yet another filtrationValue(sigma) recompute.
        val sigmaFv = ds.diameter
        (if useApparentPairs then zeroApparentCofacet(sigma) else None) match
          case Some(tau) =>
            // Apparent pair shortcut (Definition 3.2/Proposition 3.9): sigma's raw, UNREDUCED coboundary
            // already has tau as its leading term under cohomologyOrdering, and no earlier-processed
            // simplex can have already claimed tau in `basis` -- see `zeroApparentCofacet`'s doc and
            // WORKLOG-cohomology.md's "Apparent pairs: resolved" section for why. So `Chain.reduceBy`
            // is GUARANTEED to be a no-op here (log = Nil, reduced = z unchanged) and can be skipped.
            //
            // `coboundaryOf(sigma)` is NOT computed here at all, and `basis(tau)` is deliberately NEVER
            // written. If some OTHER column's reduction later reaches tau as an unresolved pivot,
            // `basisFallback` above recomputes `coboundaryOf(sigma)` fresh at that point instead --
            // Ripser's own `compute_pairs` substitution, no cache (see zeroApparentFacet's doc and
            // `.claude/WORKLOG-lazy-enumeration.md`). This is what turns the apparent-pairs shortcut from
            // "skip the reduction pass, still pay for the full coboundary enumeration" (a measured
            // 1.35x-1.8x win on its own) into "skip the coboundary enumeration too, for every apparent
            // pair nobody else's reduction ever reaches."
            //
            // generators(tau) MUST still be written here even though basis(tau) is not: it's read back
            // by ANY later column whose reduction log has a `tau` entry, whether tau was reached via
            // ordinary `basis` or via `basisFallback` -- omitting it reintroduces the "pivot has a basis
            // entry but no generators entry" throw below.
            val vcol = Chain[Simplex[Int], CoefficientT](sigma)
            generators(tau) = vcol
            cleared += tau
            bars.append(
              PersistenceBar(d, ClosedEndpoint(sigmaFv), OpenEndpoint(filtrationValue(tau)), Some(vcol))
            )
          case None =>
            val z = coboundaryOf(sigma)
            // Chain.reduceBy (SortedMap-based), not hand-rolled reduction over raw Chain arithmetic -- see
            // WORKLOG-naive-homology.md's "critical performance bug" for why that silently reintroduces
            // superlinear blowup on real VR streams. `basisFallback` (see above) supplies the on-the-fly
            // apparent-pair substitution when this reduction's own working chain hits an unclaimed pivot.
            val (reduced, log) = Chain.reduceBy(z, basis, Chain.empty, basisFallback)
            // V-column: by construction (Algorithm 1's V_j, updated in lockstep with R_j = delta(V_j)
            // throughout), this is itself a genuine cocycle whenever reduced is zero -- no separate
            // cocycle-reconstruction step needed, unlike the naive engine's homology case. Collapsed
            // explicitly, and BEFORE either write below, for the same reason as CellularHomologyEngine:
            // generators entries get read back into later cells' own vcol folds.
            val vcol: Chain[Simplex[Int], CoefficientT] =
              log.rawEntries.foldLeft(Chain(sigma)) { case (acc, (pivot, coeff)) =>
                acc - coeff ⊠ generators.getOrElse(
                  pivot,
                  throw new IllegalStateException(s"pivot $pivot has a basis entry but no generators entry")
                )
              }
            vcol.collapseAll()
            if reduced.isZero() then
              // sigma's coboundary fully cancelled, AND (since we didn't skip it above) sigma was never
              // claimed as anyone's pivot: a genuine essential class born at sigma (dimension d). Emitted
              // unconditionally, including alongside a zero-length finite bar elsewhere in the list -- see
              // WORKLOG-cohomology.md on why zero-length bars must not be silently dropped at this stage.
              bars.append(PersistenceBar(d, ClosedEndpoint(sigmaFv), PositiveInfinity(), Some(vcol)))
            else
              // reduced is nonzero: its leading cell (the OLDEST cofacet remaining, per cohomologyOrdering)
              // is sigma's death partner. Birth = sigma (dimension d, the column); death = that pivot
              // (dimension d+1, the row) -- see WORKLOG-cohomology.md's birth/death/dimension derivation.
              val pivot = reduced.leadingCell.get
              basis(pivot) = reduced
              generators(pivot) = vcol
              cleared += pivot
              bars.append(
                PersistenceBar(
                  d,
                  ClosedEndpoint(sigmaFv),
                  OpenEndpoint(filtrationValue(pivot)),
                  Some(vcol)
                )
              )

      // Assemble the NEXT dimension's candidates from every dimension-d simplex, cleared ones included --
      // see `.claude/WORKLOG-lazy-enumeration.md`, confirmed from ripser.cpp's own
      // `assemble_columns_to_reduce`: `next_simplices.push_back(...)` runs unconditionally, BEFORE the
      // `is_in_zero_apparent_pair`/already-a-pivot exclusion checks that shrink `columns_to_reduce`.
      // Clearing controls which simplices get independently REDUCED at a dimension, never which simplices
      // are a valid source for generating the next dimension's cofacets -- a cleared simplex's own higher
      // cofacets still genuinely exist in the complex. Getting this backwards would silently omit real
      // simplices from every dimension above the first one with a cleared/apparent-paired member.
      if d < maxDimension then currentLevel = simplicesAtD.iterator.flatMap(sparseCofacets).toSeq

    bars.toList
