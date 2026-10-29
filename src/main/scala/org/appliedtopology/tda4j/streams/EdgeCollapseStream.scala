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

  /** Up to this many vertices the collapse keeps its graph as an `n x n` matrix of values (at most 134 MB), with the
    * common neighbours of an edge found by a bitwise AND and every value read in one step; above it, in a sorted map
    * per vertex (memory proportional to the edges). Both decide every edge the same way.
    */
  private val DenseLimit = 4096

  /** The collapsed Vietoris-Rips graph of `metricSpace`, considering the edges up to `maxFiltrationValue` (default: the
    * minimum enclosing radius). Assumes the elements are `0 until size` (wrap in `IntMetricSpace` otherwise).
    */
  def collapse(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Option[Double] = None
  ): EdgeCollapsedMetricSpace = collapseWith(metricSpace, maxFiltrationValue, DenseLimit)

  // `denseLimit` is the vertex count up to which the matrix form is used: the tests set it to 0 to run the map form on
  // small inputs and compare the two.
  private[tda4j] def collapseWith(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Option[Double],
    denseLimit: Int
  ): EdgeCollapsedMetricSpace =
    val n = metricSpace.size
    val bound = maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

    // The edges within the bound, `(i, j, d)` with `i < j`, in ascending order of `i` and then `j`.
    val firsts = new LayeredInorderSimplexStream.IntBuffer
    val seconds = new LayeredInorderSimplexStream.IntBuffer
    val values = new LayeredInorderSimplexStream.DoubleBuffer
    var i = 0
    while i < n do
      var j = i + 1
      while j < n do
        val d = metricSpace.distance(i, j)
        // `d.isFinite &&`, not just `d <= bound`, is load-bearing whenever `bound` is itself `Double.PositiveInfinity`
        // (the untruncated default, or an explicit caller choice): IEEE-754's `Infinity <= Infinity` is true, so a
        // plain `<=` would silently readmit every already-excluded pair -- for a FRESH metric space that just means
        // "no truncation, include everything" (harmless, since nothing is legitimately Infinity yet), but for
        // RE-COLLAPSING an already-`EdgeCollapsedMetricSpace` input, a `+Infinity` entry specifically means "already
        // removed by domination" and must stay excluded even under an unbounded second pass -- exactly the same
        // hazard, and the same fix, `SheehyRipsSimplexStream`'s own `keptByThresholdAndCriterion` already
        // documents (`CLAUDE.md`'s Sheehy section) for the identical reason.
        if d.isFinite && d <= bound then
          firsts += i
          seconds += j
          values += d
        j += 1
      i += 1
    val (as, bs, ds) = (firsts.result, seconds.result, values.result)
    val edgesBefore = ds.length

    // ONE pass, in DESCENDING order of each edge's own ORIGINAL filtration value (ties broken deterministically
    // by vertex-pair, since this port has no access to whatever tie-break the reference's own `std::sort`
    // happens to produce, and none is documented as mattering for correctness) -- each of the n*(n-1)/2 original
    // pairs is decided EXACTLY ONCE, so the value of the pair currently being resolved is always still its own
    // original value `f0` at the moment this iteration reaches it (nothing else ever writes that specific
    // entry) -- only OTHER edges (between u or v and a common neighbor, or between two common neighbors) may
    // already reflect an earlier-in-this-pass (strictly larger original value) resolution, or may still be at
    // their own raw original value (not yet reached this pass, strictly smaller original value) -- precisely the
    // mixed live state the reference algorithm itself reads.
    val order =
      val endpoints = new Array[Int](2 * edgesBefore)
      val negated = new Array[Double](edgesBefore)
      var k = 0
      while k < edgesBefore do
        endpoints(2 * k) = as(k)
        endpoints(2 * k + 1) = bs(k)
        negated(k) = -ds(k)
        k += 1
      RipserCofaceSimplexStream.layerOrder(2, negated, endpoints, colex = false)

    val graph =
      if n <= denseLimit then new DenseCollapse(n, as, bs, ds).run(order)
      else collapseWithMaps(n, as, bs, ds, order)
    EdgeCollapsedMetricSpace(metricSpace, graph, bound, Stats(edgesBefore, graph.edgeCount))

  // The reference form: a sorted map per vertex, every lookup a tree search.
  private def collapseWithMaps(
    n: Int,
    as: Array[Int],
    bs: Array[Int],
    ds: Array[Double],
    order: Array[Int]
  ): CollapsedGraph =
    // Closed neighborhoods (self at -Infinity, matching the reference implementation's own convention): this
    // makes "is x adjacent to w by time t" trivially true for x == w, with no special-casing at every call site.
    val neighbors: Array[mutable.TreeMap[Int, Double]] = Array.fill(n)(mutable.TreeMap.empty[Int, Double])
    for i <- 0 until n do neighbors(i)(i) = Double.NegativeInfinity
    for k <- ds.indices do
      neighbors(as(k))(bs(k)) = ds(k)
      neighbors(bs(k))(as(k)) = ds(k)

    def tOf(nu: mutable.TreeMap[Int, Double], nv: mutable.TreeMap[Int, Double])(w: Int): Double =
      math.max(nu(w), nv(w))

    // Is e's link still a cone at time t, given the CURRENT (live, progressively-mutating) graph? `active` =
    // common neighbors already present by t; a dominator must itself be active and (closed-)adjacent to every
    // member of `active` by t. Reads `neighbors` live and DELIBERATELY so -- see the class doc: this is a
    // faithful port of the reference algorithm's own Gauss-Seidel structure, not an independent design.
    def dominatedAt(common: Vector[Int], tOf: Int => Double, t: Double): Boolean =
      val active = common.filter(w => tOf(w) <= t)
      active.exists(w => active.forall(x => neighbors(w).getOrElse(x, Double.PositiveInfinity) <= t))

    for k <- order do
      val (u, v, f0) = (as(k), bs(k), ds(k))
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

    val rowStart = new Array[Int](n + 1)
    val cols = new LayeredInorderSimplexStream.IntBuffer
    val vals = new LayeredInorderSimplexStream.DoubleBuffer
    for i <- 0 until n do
      neighbors(i).foreach { (j, value) =>
        if j != i then
          cols += j
          vals += value
      }
      rowStart(i + 1) = cols.size
    CollapsedGraph(rowStart, cols.result, vals.result)

  /** The same decisions on an `n x n` matrix of values, `+Infinity` where there is no edge and `-Infinity` on the
    * diagonal, and a bit set of neighbours per vertex. The common neighbours of an edge are the AND of two rows of
    * bits. The map form tests an edge against each candidate time in turn, rebuilding the active set and testing every
    * pair of it each time (cubic in the common neighbours); here the active set grows as the time does, and the vertex
    * that dominated at one time is tested only against the vertices that joined since.
    */
  private final class DenseCollapse(n: Int, as: Array[Int], bs: Array[Int], ds: Array[Double]):
    private val words = (n + 63) >>> 6
    private val matrix = new Array[Double](n * n)
    private val present = new Array[Long](n * words)
    // The common neighbours of the edge being decided, their values `t(w)` (the later of the two edges to them), the
    // order of those by `t`, and the active ones.
    private var common = new Array[Int](64)
    private var times = new Array[Double](64)
    private var order = new Array[Int](64)
    private var spare = new Array[Int](64)
    private var active = new Array[Int](64)

    locally {
      java.util.Arrays.fill(matrix, Double.PositiveInfinity)
      var i = 0
      while i < n do
        matrix(i * n + i) = Double.NegativeInfinity
        i += 1
      var edge = 0
      while edge < ds.length do
        val (u, v) = (as(edge), bs(edge))
        matrix(u * n + v) = ds(edge)
        matrix(v * n + u) = ds(edge)
        present(u * words + (v >>> 6)) |= 1L << (v & 63)
        present(v * words + (u >>> 6)) |= 1L << (u & 63)
        edge += 1
    }

    def run(processing: Array[Int]): CollapsedGraph =
      var k = 0
      while k < processing.length do
        val (u, v, f0) = (as(processing(k)), bs(processing(k)), ds(processing(k)))
        val decided = decide(u, v, f0)
        if decided.isNaN then
          matrix(u * n + v) = Double.PositiveInfinity
          matrix(v * n + u) = Double.PositiveInfinity
          present(u * words + (v >>> 6)) &= ~(1L << (v & 63))
          present(v * words + (u >>> 6)) &= ~(1L << (u & 63))
        else if decided != f0 then
          matrix(u * n + v) = decided
          matrix(v * n + u) = decided
        k += 1
      val rowStart = new Array[Int](n + 1)
      val cols = new LayeredInorderSimplexStream.IntBuffer
      val vals = new LayeredInorderSimplexStream.DoubleBuffer
      var i = 0
      while i < n do
        var w = 0
        while w < words do
          var bits = present(i * words + w)
          while bits != 0L do
            val j = (w << 6) + java.lang.Long.numberOfTrailingZeros(bits)
            cols += j
            vals += matrix(i * n + j)
            bits &= bits - 1L
          w += 1
        rowStart(i + 1) = cols.size
        i += 1
      CollapsedGraph(rowStart, cols.result, vals.result)

    // The time the edge `{u, v}`, born at `f0`, moves to: `f0` itself if it stays, `NaN` if it is removed. The first
    // candidate time (`f0`, then each later value of `t`) at which the active common neighbours have no dominator.
    private def decide(u: Int, v: Int, f0: Double): Double =
      var count = 0
      var w = 0
      while w < words do
        var bits = present(u * words + w) & present(v * words + w)
        while bits != 0L do
          if count == common.length then grow()
          common(count) = (w << 6) + java.lang.Long.numberOfTrailingZeros(bits)
          count += 1
          bits &= bits - 1L
        w += 1
      if count == 0 then f0
      else
        var earliest = Double.PositiveInfinity
        var i = 0
        while i < count do
          val t = math.max(matrix(u * n + common(i)), matrix(v * n + common(i)))
          times(i) = t
          if t < earliest then earliest = t
          i += 1
        // No common neighbour is there at `f0`: the first candidate time has an empty active set, which has no dominator.
        if earliest > f0 then f0
        else scan(count, f0)

    // The positions `0 until count` of the common neighbours in ascending order of `t` (equal values in any order), in
    // `order`: insertion sort of short runs, then merges of runs of doubling length.
    private def sortByTime(count: Int): Unit =
      var i = 0
      while i < count do
        order(i) = i
        i += 1
      val run = 8
      var start = 0
      while start < count do
        val end = math.min(start + run, count)
        var k = start + 1
        while k < end do
          val moving = order(k)
          var j = k - 1
          while j >= start && times(order(j)) > times(moving) do
            order(j + 1) = order(j)
            j -= 1
          order(j + 1) = moving
          k += 1
        start = end
      var width = run
      var from = order
      var into = spare
      while width < count do
        var low = 0
        while low < count do
          val middle = math.min(low + width, count)
          val high = math.min(low + 2 * width, count)
          var left = low
          var right = middle
          var out = low
          while left < middle && right < high do
            if times(from(right)) < times(from(left)) then
              into(out) = from(right)
              right += 1
            else
              into(out) = from(left)
              left += 1
            out += 1
          while left < middle do
            into(out) = from(left)
            left += 1
            out += 1
          while right < high do
            into(out) = from(right)
            right += 1
            out += 1
          low = high
        val swap = from
        from = into
        into = swap
        width *= 2
      if from ne order then System.arraycopy(from, 0, order, 0, count)

    private def scan(count: Int, f0: Double): Double =
      sortByTime(count)
      var members = 0
      var time = f0
      // The active vertex (an index into `active`) known to dominate at `time`, or -1. A dominator at one time still
      // dominates at a later one if it is adjacent, by then, to the vertices that joined since: the earlier ones were
      // adjacent by an earlier, smaller time.
      var dominator = -1
      var result = Double.NaN
      var decided = false
      while !decided do
        val joining = members
        while members < count && times(order(members)) <= time do
          active(members) = common(order(members))
          members += 1
        var x = joining
        while dominator >= 0 && x < members do
          if matrix(active(dominator) * n + active(x)) > time then dominator = -1
          x += 1
        if dominator < 0 then dominator = findDominator(members, time)
        if dominator < 0 then
          result = time
          decided = true
        else if members == count then decided = true
        else time = times(order(members))
      result

    // An active vertex adjacent by `time` to every active vertex (itself included), the latest to join first, each
    // tested against the latest to join first: the vertices that fail do so on one of the last few.
    private def findDominator(members: Int, time: Double): Int =
      var w = members - 1
      while w >= 0 do
        val row = active(w) * n
        var x = members - 1
        while x >= 0 && matrix(row + active(x)) <= time do x -= 1
        if x < 0 then return w
        w -= 1
      -1

    private def grow(): Unit =
      val size = 2 * common.length
      common = java.util.Arrays.copyOf(common, size)
      times = java.util.Arrays.copyOf(times, size)
      order = java.util.Arrays.copyOf(order, size)
      spare = java.util.Arrays.copyOf(spare, size)
      active = java.util.Arrays.copyOf(active, size)

/** The surviving edges of a collapse, in rows: the neighbours of vertex `i` are
  * `cols(rowStart(i) until rowStart(i + 1))` in ascending order, with their values in `vals`. Each edge is in the rows
  * of both its ends.
  */
// Restricted: it is the collapse's own result type, read through `EdgeCollapsedMetricSpace`.
private[tda4j] final class CollapsedGraph(val rowStart: Array[Int], val cols: Array[Int], val vals: Array[Double]):
  /** The value of the edge `{i, j}`, `+Infinity` if it is not there. */
  def value(i: Int, j: Int): Double =
    val at = java.util.Arrays.binarySearch(cols, rowStart(i), rowStart(i + 1), j)
    if at >= 0 then vals(at) else Double.PositiveInfinity

  /** The number of edges. */
  def edgeCount: Int = cols.length / 2

  /** The edges `(i, j, value)` with `i < j`, ascending by `i` then `j`. */
  def edges: Iterator[(Int, Int, Double)] =
    Iterator.range(0, rowStart.length - 1).flatMap { i =>
      Iterator.range(rowStart(i), rowStart(i + 1)).filter(at => cols(at) > i).map(at => (i, cols(at), vals(at)))
    }

private[tda4j] object CollapsedGraph:
  def apply(rowStart: Array[Int], cols: Array[Int], vals: Array[Double]): CollapsedGraph =
    new CollapsedGraph(rowStart, cols, vals)

/** The collapsed graph as a metric space on the same vertices: `Infinity` for a removed pair. Not a metric (the
  * triangle inequality fails where edges were removed), so use it only with the combinatorial Vietoris-Rips
  * constructions. Its `minimumEnclosingRadius` is the bound the collapse used, so the default cutoff downstream is the
  * same as for the original space.
  */
// The constructor stays `private[tda4j]`: it takes the collapse's own graph without copying, and `validUpTo` is a claim
// about it only `EdgeCollapse` can make. The graph is readable through `edges`.
class EdgeCollapsedMetricSpace private[tda4j] (
  val originalMetricSpace: FiniteMetricSpace[Int],
  private val graph: CollapsedGraph,
  val validUpTo: Double,
  val stats: EdgeCollapse.Stats
) extends FiniteMetricSpace[Int]:
  def size: Int = originalMetricSpace.size
  def elements: Iterable[Int] = originalMetricSpace.elements
  def contains(x: Int): Boolean = originalMetricSpace.contains(x)

  def distance(x: Int, y: Int): Double =
    if x == y then 0.0 else graph.value(x, y)

  /** The edges that survived the collapse, `(i, j, value)` with `i < j`, ascending by `i` then `j`. */
  def edges: Iterator[(Int, Int, Double)] = graph.edges

  override lazy val minimumEnclosingRadius: Double = validUpTo
