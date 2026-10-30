package org.appliedtopology.tda4j

import scala.collection.mutable

/** A weighted undirected graph on the vertices `0 until n`, held as sorted neighbour lists: `n + 2 m` numbers for `m`
  * edges, where a distance matrix holds `n^2`. It is a metric space in the sense the Vietoris-Rips machinery needs: the
  * distance of two vertices is `0` for the same vertex, the weight of the edge between them, and `Infinity` where there
  * is none (the triangle inequality does not hold; this is the combinatorial reading, the flag complex of the listed
  * edges). `Persistence(graph)` computes that flag complex with [[FlagComplex]], without ever forming a matrix.
  *
  * {{{
  * val graph = WeightedGraph(4, Seq((0, 1, 1.0), (1, 2, 1.5), (2, 3, 1.0), (3, 0, 2.0)))
  * Persistence(graph).dim(1)   // the square is a loop: born when its last edge enters, at 2.0, never filled
  * }}}
  *
  * @see
  *   [[GraphMetric.geodesic]] for the shortest-path distances of a graph, which are a metric
  */
// Not `open`: the constructor takes the sorted neighbour rows without checking them (invariant-bearing); the checked way
// to make one is `WeightedGraph(n, edges)`.
class WeightedGraph private (
  val n: Int,
  private val rowStart: Array[Int],
  private val columns: Array[Int],
  private val weights: Array[Double]
) extends FiniteMetricSpace[Int]:

  def size: Int = n
  def elements: Iterable[Int] = Range(0, n)
  def contains(x: Int): Boolean = 0 <= x && x < n

  /** The weight of the edge between `x` and `y`, `0` if they are the same vertex, `Infinity` if there is no edge. */
  def distance(x: Int, y: Int): Double =
    if x == y then 0.0
    else
      val at = java.util.Arrays.binarySearch(columns, rowStart(x), rowStart(x + 1), y)
      if at >= 0 then weights(at) else Double.PositiveInfinity

  /** The number of edges. */
  def edgeCount: Int = columns.length / 2

  /** The neighbours of `v` with the weights of their edges, ascending by neighbour. */
  def neighbours(v: Int): Iterator[(Int, Double)] =
    Iterator.range(rowStart(v), rowStart(v + 1)).map(at => (columns(at), weights(at)))

  /** The edges `(a, b, weight)` with `a < b`, ascending by `a` and then `b`. */
  def edges: Iterator[(Int, Int, Double)] =
    Iterator.range(0, n).flatMap { a =>
      Iterator
        .range(rowStart(a), rowStart(a + 1))
        .filter(at => columns(at) > a)
        .map(at => (a, columns(at), weights(at)))
    }

  // The neighbours of `v` above `v` and their weights, as the range of positions in the rows: what the flag complex
  // grows a simplex by.
  private[tda4j] def rowRange(v: Int): (Int, Int) = (rowStart(v), rowStart(v + 1))
  private[tda4j] def columnAt(at: Int): Int = columns(at)
  private[tda4j] def weightAt(at: Int): Double = weights(at)

  /** The longest edge: past it, no simplex enters. `0` for a graph with no edges. */
  override protected def scanEnclosingRadius: Double = if weights.isEmpty then 0.0 else weights.max

  override def toString: String = s"WeightedGraph($n vertices, $edgeCount edges)"
end WeightedGraph

object WeightedGraph:

  /** The graph on `0 until n` with the given `(a, b, weight)` edges, in either direction. Weights are numbers `>= 0`;
    * an edge listed several times counts at its lightest weight, an edge of weight `Infinity` is no edge, and a loop
    * `(a, a, w)` is ignored, since a vertex is at distance 0 from itself.
    */
  def apply(n: Int, edges: Iterable[(Int, Int, Double)]): WeightedGraph =
    require(n >= 0, s"WeightedGraph: the number of vertices must be at least 0, got $n")
    val lightest = mutable.HashMap.empty[Long, Double]
    for (a, b, w) <- edges do
      require(0 <= a && a < n && 0 <= b && b < n, s"WeightedGraph: edge ($a, $b) has an end outside 0 until $n")
      require(w >= 0.0 && !w.isNaN, s"WeightedGraph: edge ($a, $b) has weight $w, which is not a number >= 0")
      if a != b && !w.isInfinite then
        lightest.updateWith(math.min(a, b).toLong * n + math.max(a, b)) {
          case Some(old) if old <= w => Some(old)
          case _                     => Some(w)
        }
    val degree = new Array[Int](n + 1)
    for key <- lightest.keysIterator do
      degree((key / n).toInt) += 1
      degree((key % n).toInt) += 1
    val rowStart = new Array[Int](n + 1)
    var v = 0
    while v < n do
      rowStart(v + 1) = rowStart(v) + degree(v)
      v += 1
    val fill = rowStart.clone()
    val columns = new Array[Int](rowStart(n))
    val weights = new Array[Double](rowStart(n))
    for (key, w) <- lightest do
      val (a, b) = ((key / n).toInt, (key % n).toInt)
      columns(fill(a)) = b
      weights(fill(a)) = w
      fill(a) += 1
      columns(fill(b)) = a
      weights(fill(b)) = w
      fill(b) += 1
    // Each row ascending by neighbour, the weights moving with their neighbours.
    v = 0
    while v < n do
      val (first, end) = (rowStart(v), rowStart(v + 1))
      val order = (first until end).sortBy(columns(_))
      val sortedColumns = order.map(columns(_))
      val sortedWeights = order.map(weights(_))
      order.indices.foreach { i =>
        columns(first + i) = sortedColumns(i)
        weights(first + i) = sortedWeights(i)
      }
      v += 1
    new WeightedGraph(n, rowStart, columns, weights)

  /** The graph on `0 until n` with the given edges, every one of weight `1`: its flag complex is the clique complex,
    * every simplex entering at `1`.
    */
  def unweighted(n: Int, edges: Iterable[(Int, Int)]): WeightedGraph =
    apply(n, edges.map((a, b) => (a, b, 1.0)))
end WeightedGraph
