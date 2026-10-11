package org.appliedtopology.tda4j

import scala.collection.mutable

/** Edge collapse of a Vietoris-Rips filtration (Boissonnat and Pritam, "Edge Collapse and Persistence of Flag
  * Complexes", SoCG 2020; Glisse and Pritam, "Swap, Shift and Trim to Edge Collapse a Filtration", SoCG 2022): a
  * smaller weighted graph whose flag complex has the same persistent homology at every scale, computed from the graph
  * alone.
  *
  * An edge `{u, v}` is dominated by a vertex `w` when every common neighbour of `u` and `v` is a neighbour of `w`;
  * removing a dominated edge (and the simplices containing it) from a flag complex is a collapse. Over a filtration, an
  * edge's entry is postponed to the last scale at which it is still dominated, or the edge is removed if it stays
  * dominated to the end. This is a port of GUDHI's single pass (`Flag_complex_edge_collapser.h`): edges in decreasing
  * order of their original value, each decided once against the graph as it stands. The order matters: deciding the
  * edges independently and iterating to a fixed point removes too much. One pass is not necessarily minimal, and
  * collapsing the output again may shrink it further.
  *
  * At every scale the collapsed complex is a subcomplex of the original, so a representative computed on it is a
  * representative of the same class in the original complex.
  */
object EdgeCollapse:

  /** `edgesBefore`/`edgesAfter` count DISTINCT unordered pairs (not the `2x` directed entries this class's own internal
    * adjacency structure keeps).
    */
  case class Stats(edgesBefore: Int, edgesAfter: Int):
    def edgesRemoved: Int = edgesBefore - edgesAfter

  /** The collapsed Vietoris-Rips graph of `metricSpace`, considering the edges up to `maxFiltrationValue` (default: the
    * minimum enclosing radius). Assumes the elements are `0 until size` (wrap in `IntMetricSpace` otherwise).
    */
  def collapse(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Option[Double] = None
  ): EdgeCollapsedMetricSpace =
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

/** The collapsed graph as a metric space on the same vertices: `Infinity` for a removed pair. Not a metric (the
  * triangle inequality fails where edges were removed), so use it only with the combinatorial Vietoris-Rips
  * constructions. Its `minimumEnclosingRadius` is the bound the collapse used, so the default cutoff downstream is the
  * same as for the original space.
  */
// The constructor stays `private[tda4j]`: it takes the collapse's own mutable neighbour maps without copying, and
// `validUpTo` is a claim about them only `EdgeCollapse` can make. The graph is readable through `edges`.
class EdgeCollapsedMetricSpace private[tda4j] (
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

  /** The edges that survived the collapse, `(i, j, value)` with `i < j`, ascending by `i` then `j`. */
  def edges: Iterator[(Int, Int, Double)] =
    neighbors.iterator.zipWithIndex.flatMap((m, i) => m.iteratorFrom(i + 1).map((j, value) => (i, j, value)))

  override lazy val minimumEnclosingRadius: Double = validUpTo
