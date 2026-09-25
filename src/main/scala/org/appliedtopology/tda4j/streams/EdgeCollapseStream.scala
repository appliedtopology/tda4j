package org.appliedtopology.tda4j
package streams

import scala.collection.mutable

/** Flag-complex edge collapse (Boissonnat-Pritam, "Edge Collapse and Persistence of Flag Complexes," SoCG 2020;
  * Glisse-Pritam, "Swap, Shift and Trim to Edge Collapse a Filtration," SoCG 2022): reduces a Vietoris-Rips
  * filtration's own 1-skeleton to a smaller weighted graph whose flag complex has the SAME persistent homology at
  * every filtration level, using only the graph itself (no higher simplices ever built). `.claude/WORKLOG-
  * mainstream-feature-gap-analysis.md` item 5. Definitions verified directly against GUDHI's own edge-collapse
  * module (`Gudhi::collapse`, the reference implementation, co-authored by the same Pritam/Glisse -- fetched and
  * read directly via a throwaway clone of `GUDHI/gudhi-devel`, not recalled from memory, matching this codebase's
  * own io-module verification ethos; the actual arXiv/Dagstuhl PDFs were unreachable from this session's network
  * policy), not the mainstream-feature-gap-analysis worklog's own first-draft phrasing -- see this file's own
  * worklog for the one correction that came out of that (the operation removes dominated EDGES, not dominated
  * VERTICES; vertex domination is a *different* construction, "strong collapse," `arXiv:1809.10945`).
  *
  * '''Dominated edge''' (GUDHI's own definition, restated for a flag complex where it depends only on the graph):
  * an edge `e = {u, v}` is dominated by a vertex `w ∉ e` iff every vertex adjacent to BOTH `u` and `v` is also
  * adjacent to `w` -- equivalently, `w`'s closed neighborhood contains every common neighbor of `u` and `v`. An
  * elementary edge collapse removes a dominated edge (and, implicitly, every simplex containing it) from the flag
  * complex; domination is a simple sufficient condition for this removal to be an elementary simplicial collapse
  * (a strong deformation retraction, hence a simple-homotopy equivalence).
  *
  * '''Across a whole filtration''' (not one fixed threshold): an edge dominated at its own birth may stop being
  * dominated once the filtration admits more vertices -- so instead of an outright removal, its own entry time is
  * pushed forward to the largest time at which it remains dominated (by, in general, a succession of different
  * dominating vertices as new common neighbors arrive) -- GUDHI's own doc states this plainly: "an edge collapse
  * may translate into an increase of the filtration value of an edge, or its removal if it already had the
  * largest filtration value." If no such largest time exists (the edge stays dominated all the way through the
  * complex), it is removed outright. The resulting smaller weighted graph is, again, a flag complex, and its
  * persistent homology (every bar, at every dimension) agrees with the original's exactly.
  *
  * '''Representatives transfer for free through inclusion''' (this codebase's own design principle requires
  * representatives from every engine, so this matters, and is not addressed by the papers' own barcode-only
  * framing): at every filtration level `t`, the collapsed complex is a literal SUBCOMPLEX of the original (an
  * edge collapse only ever removes cells or defers their entry to a later `t`, never adds or identifies any) --
  * so the inclusion of the collapsed complex into the original is a well-defined chain map at every level, and a
  * cycle/cocycle representative computed on the SMALLER complex is automatically a valid representative of the
  * SAME homology class in the ORIGINAL complex, simply by reinterpreting the same chain as living in the bigger
  * complex. No separate lifting machinery is needed (contrast the MST-based simplicial-set collapse this same
  * worklog item considered and declined -- see the worklog -- where the analogous complex is a QUOTIENT, not a
  * subcomplex, and lifting a representative back is a real additional step).
  *
  * '''This implementation IS a faithful port of GUDHI's own single-pass, descending-filtration-value sweep'''
  * (`process_edges`/`common_neighbors`/`is_dominated_by` in `Flag_complex_edge_collapser.h`) -- a first attempt at
  * an independently-designed "iterate a batch-scan resolution to a whole-graph fixed point" alternative was tried
  * and CONFIRMED WRONG (`WORKLOG-edge-collapse.md`): it over-collapsed a 5-point fixture, permanently removing
  * every chord of a pentagon whose flag complex genuinely has a persistent (non-zero-persistence) H¹ class,
  * silently turning that class essential. The bug was letting an edge's own "dominated forever, remove" decision
  * stand permanently even after a LATER decision (elsewhere in the graph) invalidated the very common-neighbor
  * edge that removal decision had relied on -- a removed edge is never reconsidered, so nothing could ever correct
  * it. GUDHI's own SPECIFIC processing order (descending by ORIGINAL filtration value, one pass, each edge decided
  * exactly once against the live, progressively-mutating graph) is not an arbitrary implementation choice, as this
  * class's own first (wrong) draft assumed -- it is what the published algorithm actually requires, and this port
  * follows it exactly rather than re-deriving a substitute. This session could not fetch the actual proof (the
  * arXiv/Dagstuhl hosts were unreachable from this session's network policy -- see the worklog) justifying WHY
  * that specific order is sound; this implementation trusts the verified reference implementation's own structure
  * on that point, the same way this codebase trusts a fetched GUDHI/DREiMac docstring or worked example elsewhere,
  * and instead cross-validates the OUTPUT empirically (barcode agreement against plain, uncollapsed VR, across
  * property-tested random point clouds, a tie-heavy fixture, and hand-built fixtures) rather than the algorithm's
  * own internal reasoning -- see the spec.
  *
  * GUDHI's own doc notes the single pass does not necessarily produce a MINIMAL filtration -- applying `collapse`
  * again to its own output (this class's `EdgeCollapsedMetricSpace` is itself a `FiniteMetricSpace[Int]`, so this
  * needs no special support) may simplify it further; neither this class nor GUDHI's own promises idempotence in
  * one application.
  */
object EdgeCollapse:

  /** `edgesBefore`/`edgesAfter` count DISTINCT unordered pairs (not the `2x` directed entries this class's own
    * internal adjacency structure keeps).
    */
  case class Stats(edgesBefore: Int, edgesAfter: Int):
    def edgesRemoved: Int = edgesBefore - edgesAfter

  /** Collapses `metricSpace`'s own Vietoris-Rips 1-skeleton, up to `maxFiltrationValue` (defaulting to
    * `metricSpace.minimumEnclosingRadius`, this codebase's usual truncation convention) -- edges with an ORIGINAL
    * distance beyond that bound are excluded from consideration entirely, exactly as
    * `EnumeratingCofaceSimplexStream`'s own default does, not merely computed-and-then-discarded.
    *
    * `metricSpace` must use contiguous vertex ids `0 until metricSpace.size` -- the same silent assumption every
    * other VR-consuming construction in this codebase already makes (`SimplexIndexing`'s combinatorial decode,
    * `WitnessMetricSpace`'s own local landmark indices); wrap in `IntMetricSpace` first if it does not already.
    */
  def collapse(metricSpace: FiniteMetricSpace[Int], maxFiltrationValue: Option[Double] = None): EdgeCollapsedMetricSpace =
    val n = metricSpace.size
    val bound = maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

    // Closed neighborhoods (self at -Infinity, matching the reference implementation's own convention): this
    // makes "is x adjacent to w by time t" trivially true for x == w, with no special-casing at every call site.
    val neighbors: Array[mutable.TreeMap[Int, Double]] = Array.fill(n)(mutable.TreeMap.empty[Int, Double])
    for i <- 0 until n do neighbors(i)(i) = Double.NegativeInfinity
    val initialEdges = mutable.ArrayBuffer.empty[(Int, Int, Double)]
    for
      i <- 0 until n
      j <- (i + 1) until n
      d = metricSpace.distance(i, j)
      // `d.isFinite &&`, not just `d <= bound`, is load-bearing whenever `bound` is itself `Double.PositiveInfinity`
      // (the untruncated default, or an explicit caller choice): IEEE-754's `Infinity <= Infinity` is true, so a
      // plain `<=` would silently readmit every already-excluded pair -- for a FRESH metric space that just means
      // "no truncation, include everything" (harmless, since nothing is legitimately Infinity yet), but for
      // RE-COLLAPSING an already-`EdgeCollapsedMetricSpace` input, a `+Infinity` entry specifically means "already
      // removed by domination" and must stay excluded even under an unbounded second pass -- exactly the same
      // hazard, and the same fix, `SheehyRipsSimplexStream`'s own `keptByThresholdAndCriterion` already
      // documents (`CLAUDE.md`'s Sheehy section) for the identical reason.
      if d.isFinite && d <= bound
    do
      neighbors(i)(j) = d
      neighbors(j)(i) = d
      initialEdges += ((i, j, d))
    val edgesBefore = initialEdges.size

    def tOf(nu: mutable.TreeMap[Int, Double], nv: mutable.TreeMap[Int, Double])(w: Int): Double =
      math.max(nu(w), nv(w))

    // Is e's link still a cone at time t, given the CURRENT (live, progressively-mutating) graph? `active` =
    // common neighbors already present by t; a dominator must itself be active and (closed-)adjacent to every
    // member of `active` by t. Reads `neighbors` live and DELIBERATELY so -- see the class doc: this is a
    // faithful port of the reference algorithm's own Gauss-Seidel structure, not an independent design.
    def dominatedAt(common: Vector[Int], tOf: Int => Double, t: Double): Boolean =
      val active = common.filter(w => tOf(w) <= t)
      active.exists(w => active.forall(x => neighbors(w).getOrElse(x, Double.PositiveInfinity) <= t))

    // ONE pass, in DESCENDING order of each edge's own ORIGINAL filtration value (ties broken deterministically
    // by vertex-pair, since this port has no access to whatever tie-break the reference's own `std::sort`
    // happens to produce, and none is documented as mattering for correctness) -- each of the n*(n-1)/2 original
    // pairs is decided EXACTLY ONCE, so `neighbors(u)(v)` for the pair currently being resolved is always still
    // its own original value `f0` at the moment this iteration reaches it (nothing else ever writes that specific
    // entry) -- only OTHER edges (between u or v and a common neighbor, or between two common neighbors) may
    // already reflect an earlier-in-this-pass (strictly larger original value) resolution, or may still be at
    // their own raw original value (not yet reached this pass, strictly smaller original value) -- precisely the
    // mixed live state the reference algorithm itself reads.
    val processingOrder = initialEdges.sortBy((i, j, d) => (-d, i, j))
    for (u, v, f0) <- processingOrder do
      val nu = neighbors(u)
      val nv = neighbors(v)
      val common: Vector[Int] = nu.keysIterator.filter(w => w != u && w != v && nv.contains(w)).toVector
      if common.nonEmpty then
        val t = tOf(nu, nv)
        val candidateTimes: Vector[Double] = (f0 +: common.map(t)).distinct.filter(_ >= f0).sorted
        candidateTimes.find(time => !dominatedAt(common, t, time)) match
          case Some(newTime) =>
            if newTime != f0 then
              neighbors(u)(v) = newTime
              neighbors(v)(u) = newTime
          case None =>
            // Dominated at every candidate time, including the largest (every common neighbor active by then)
            // -- nothing can ever join `common` again (see this file's own class doc), so this is dominated all
            // the way to +Infinity: remove outright, matching GUDHI's own `dead`/`remove_neighbor`.
            neighbors(u).remove(v)
            neighbors(v).remove(u)

    val edgesAfter = neighbors.iterator.zipWithIndex.map((m, i) => m.keysIterator.count(_ > i)).sum
    EdgeCollapsedMetricSpace(metricSpace, neighbors, bound, Stats(edgesBefore, edgesAfter))

/** The collapsed graph, reified as a `FiniteMetricSpace[Int]` over the SAME vertex ids as `originalMetricSpace` --
  * exactly the pattern `WitnessMetricSpace` already established for a non-metric, collapse-derived weighted graph,
  * so it slots directly into `EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream` unchanged. `+Infinity`
  * for a collapsed-away (or never-present) pair, matching `SparseMetricSpace`'s own "+Infinity past the cutoff,
  * not excluded" convention.
  *
  * '''Not a real metric''': collapsed edges can violate the triangle inequality freely (that is the entire point --
  * a shortcut through a dominating vertex is exactly what gets removed). Never hand this to `JVPTree`,
  * `SparseMetricSpace`, `RecursiveStackVietorisRipsSimplexStream`, or the `alpha` package, same restriction
  * `WitnessMetricSpace`'s own doc states for the identical reason.
  *
  * '''The enclosing-radius hazard''' (`.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 5's own flagged
  * risk, confirmed real): `FiniteMetricSpace`'s default `minimumEnclosingRadius` is `elements.map(x =>
  * elements.map(y => distance(x,y)).max).min` -- computed against a graph that now has genuine `+Infinity`
  * entries, that formula can itself evaluate to `+Infinity` the moment every vertex has at least one collapsed-away
  * incident pair, silently disabling truncation for any downstream consumer that relies on the `None` default.
  * Fixed structurally, not by caller discipline: `minimumEnclosingRadius` is overridden here to `validUpTo` (the
  * bound `EdgeCollapse.collapse` actually used, itself defaulted from `originalMetricSpace`'s own OWN enclosing
  * radius when the caller passed no explicit bound) -- so a downstream `maxFiltrationValue = None` is always safe
  * by construction, with nothing for a caller to remember.
  */
class EdgeCollapsedMetricSpace private[streams] (
  val originalMetricSpace: FiniteMetricSpace[Int],
  private val neighbors: Array[mutable.TreeMap[Int, Double]],
  val validUpTo: Double,
  val stats: EdgeCollapse.Stats
) extends FiniteMetricSpace[Int]:
  def size: Int = originalMetricSpace.size
  def elements: Iterable[Int] = originalMetricSpace.elements
  def contains(x: Int): Boolean = originalMetricSpace.contains(x)

  def distance(x: Int, y: Int): Double =
    if x == y then 0.0 else neighbors(x).getOrElse(y, Double.PositiveInfinity)

  override lazy val minimumEnclosingRadius: Double = validUpTo
