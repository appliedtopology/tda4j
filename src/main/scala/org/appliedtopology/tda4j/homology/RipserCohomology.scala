package org.appliedtopology.tda4j

import scala.collection.mutable

/** Persistent cohomology of the Vietoris-Rips complex of a metric space by Bauer's Ripser algorithm (arXiv:1908.02518),
  * on materialized `Simplex[Int]` cells indexed by the combinatorial number system ([[SimplexIndexing]]). A reference
  * implementation: [[PackedRipserCohomologyEngine]] runs the same algorithm on packed cells and is the one to use; this
  * one exists to cross-check it.
  *
  * Reports degrees `0 .. maxDimension`, each bar with its representative cocycle. Uses clearing (required for
  * correctness here, not only speed) and skips the reduction of apparent pairs (Definition 3.2), which changes no
  * output; `useApparentPairs = false` turns that off for benchmarking.
  *
  * `maxFiltrationValue` defaults to the minimum enclosing radius, past which the complex is a cone and nothing new is
  * born, so the barcode is the same as untruncated. `memoizeFiltrationValue` caches every diameter it computes (faster,
  * more memory); off by default.
  */
class RipserCohomologyEngine[CoefficientT: Field](
  metricSpace: FiniteMetricSpace[Int],
  maxDimension: Int,
  useApparentPairs: Boolean = true,
  // None: the minimum enclosing radius (a default cannot refer to `metricSpace` directly).
  maxFiltrationValue: Option[Double] = None,
  memoizeFiltrationValue: Boolean = false
):

  private val resolvedMaxFiltrationValue: Double =
    maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)

  private val rawFiltrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  /** The diameter of a simplex (its filtration value), cached if `memoizeFiltrationValue`. */
  val filtrationValue: PartialFunction[Simplex[Int], Double] =
    if memoizeFiltrationValue then
      new PartialFunction[Simplex[Int], Double]:
        private val cache: mutable.HashMap[Simplex[Int], Double] = mutable.HashMap.empty
        def isDefinedAt(spx: Simplex[Int]): Boolean = rawFiltrationValue.isDefinedAt(spx)
        def apply(spx: Simplex[Int]): Double = cache.getOrElseUpdate(spx, rawFiltrationValue(spx))
    else rawFiltrationValue

  private val fr = summon[CoefficientT is Field]

  // Ascending by filtration value; on a tie the larger combinatorial index counts as older. Shared by both orderings
  // below so they cannot disagree on a tie.
  private def compareFvThenIndex(xFv: Double, xIdx: Long, yFv: Double, yIdx: Long): Int =
    val fc = java.lang.Double.compare(xFv, yFv)
    if fc != 0 then fc else java.lang.Long.compare(yIdx, xIdx)

  /** The cohomology order: ascending by diameter, ties broken so a larger combinatorial index is older (the refinement
    * Definition 3.2 relies on). A chain's leading cell is its minimum under this order, so the pivot of a reduced
    * coboundary is its oldest cofacet.
    */
  given cohomologyOrdering: Ordering[Simplex[Int]]:
    def compare(x: Simplex[Int], y: Simplex[Int]): Int =
      compareFvThenIndex(filtrationValue(x), si(x), filtrationValue(y), si(y))

  // A simplex with its diameter, carried through enumeration so it is never recomputed (Ripser's diameter_index_t, but
  // with the simplex materialized). Never a Set/Map key: equality includes the Double; key by `.simplex`.
  private final case class DiameterSimplex(diameter: Double, simplex: Simplex[Int])

  private val diameterSimplexOrdering: Ordering[DiameterSimplex] =
    (x: DiameterSimplex, y: DiameterSimplex) => compareFvThenIndex(x.diameter, si(x.simplex), y.diameter, si(y.simplex))

  // The cofacets of `sigma` that have it as their canonical facet (insert a vertex above sigma's largest), within
  // `maxFiltrationValue`: every (d+1)-simplex arises from exactly one d-simplex, so a dimension is assembled from the
  // previous one with no duplicates. This is where the threshold excludes simplices from the complex. A cofacet's
  // diameter is `insertionDiameter`: max(sigma's diameter, distances from the new vertex to sigma's), O(d).
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

  /** The coboundary of `sigma` in the complex truncated at `maxFiltrationValue`. Defined up to `sigma.dim ==
    * maxDimension`: the `(maxDimension + 1)`-simplices are enumerated here as needed, so the top reported degree is
    * resolved correctly, but never reduced as columns of their own. The sign of `tau = sigma + v` is `(-1)^k`, `k` the
    * number of vertices of `sigma` below `v` (dual to [[Simplex]]'s boundary).
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

  /** The coboundary of a chain, extending `coboundaryOf` linearly: `coboundaryOfChain(rep).isZero()` checks that an
    * essential bar's representative is a cocycle.
    */
  def coboundaryOfChain(c: Chain[Simplex[Int], CoefficientT]): Chain[Simplex[Int], CoefficientT] =
    Chain.from(c.rawEntries.flatMap { case (cell, coeff) =>
      coboundaryOf(cell).rawEntries.map { case (tau, sign) => (tau, fr.times(coeff, sign)) }
    })

  // The oldest cofacet of `sigma` with the same diameter (largest combinatorial index), if any: Definition 3.2. Ranges
  // over all cofacets, not only those adding a vertex above sigma's largest. The cursor's index decreases strictly, so
  // the first tie found is the answer (as ripser.cpp's get_zero_pivot_cofacet).
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

  // The youngest facet of `tau` with the same diameter (smallest combinatorial index), if any. A facet's diameter is at
  // most its coface's, so no threshold check is needed. The facet cursor's index increases strictly, so the first tie
  // found is the answer. Facet diameters are recomputed (removing a vertex has no cheap update rule).
  private def zeroPivotFacet(tau: Simplex[Int]): Option[Simplex[Int]] =
    val d = filtrationValue(tau)
    val cur = si.facetCursor(si(tau), tau.size)
    while cur.hasNext do
      val sigma = (tau.underlying - cur.vertex).asSimplex
      if filtrationValue(sigma) == d then return Some(sigma)
      cur.advance()
    None

  // `Some(tau)` iff (sigma, tau) is an apparent pair (Definition 3.2): tau is sigma's oldest same-diameter cofacet and
  // sigma is tau's youngest same-diameter facet. Then sigma is the first column whose unreduced coboundary can have tau
  // as pivot, so its reduction can be skipped.
  private def zeroApparentCofacet(sigma: Simplex[Int]): Option[Simplex[Int]] =
    for
      tau <- zeroPivotCofacet(sigma)
      partner <- zeroPivotFacet(tau)
      if partner == sigma
    yield tau

  // The same pair seen from tau: used when another column's reduction reaches tau as a pivot and needs the apparent
  // partner's column. Recomputed every time, as Ripser does: the pair is determined by diameters and indices alone.
  private def zeroApparentFacet(tau: Simplex[Int]): Option[Simplex[Int]] =
    for
      sigma <- zeroPivotFacet(tau)
      partner <- zeroPivotCofacet(sigma)
      if partner == tau
    yield sigma

  private var _substitutionCount: Int = 0

  /** How many times the last `persistentCohomology()` substituted an apparent pair's column during another column's
    * reduction (for tests).
    */
  def substitutionCount: Int = _substitutionCount

  private var _totalSimplexCount: Int = 0

  /** How many simplices the last `persistentCohomology()` assembled, over all dimensions (for tests). */
  def totalSimplexCount: Int = _totalSimplexCount

  private var _apparentPairCount: Int = 0

  /** How many simplices the last run paired directly as apparent pairs, without reducing their coboundary. */
  // Distinct from `substitutionCount`, which counts the lazy fallback when another column needs an apparent pair's
  // column as a pivot (WORKLOG-o3-1024-fractal-r-session-2026-09-25.md).
  def apparentPairCount: Int = _apparentPairCount

  /** Every bar of degree `0 .. maxDimension`, each with its representative cocycle; zero-length bars only if
    * `includeZeroLength`.
    */
  def persistentCohomology(
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    // Summoned here, where `cohomologyOrdering` is in scope (see CellularHomologyEngine's implementation note).
    val chainRM = summon[Chain[Simplex[Int], CoefficientT] is RingModule]
    import chainRM.*

    _substitutionCount = 0
    _totalSimplexCount = 0
    _apparentPairCount = 0
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
            _apparentPairCount += 1
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

    PersistenceBar.dropZeroLength(bars, includeZeroLength)
