package org.appliedtopology.tda4j

import scala.collection.immutable.ArraySeq

/** Distances along a weighted graph, as a metric space for the Vietoris-Rips and Cech machinery: the geodesic distance
  * of a graph that records which points are near each other (a nearest-neighbour graph on a manifold, a network, a
  * mesh).
  */
object GraphMetric:

  /** The shortest-path distance between every two vertices of the undirected graph on `0 until n` with the given
    * `(a, b, weight)` edges (weights `>= 0`; the lightest of repeated edges counts). Vertices in different components
    * are at distance `Infinity`, which the Vietoris-Rips complex reads as "never joined". The distances are computed by
    * Dijkstra's algorithm from every vertex and held in an `n x n` table.
    */
  def geodesic(n: Int, edges: Iterable[(Int, Int, Double)]): ExplicitMetricSpace =
    require(n >= 0, s"GraphMetric: the number of vertices must be at least 0, got $n")
    val adjacency = Array.fill(n)(Vector.newBuilder[(Int, Double)])
    for (a, b, w) <- edges do
      require(0 <= a && a < n && 0 <= b && b < n, s"GraphMetric: edge ($a, $b) has an end outside 0 until $n")
      require(w >= 0.0 && !w.isNaN, s"GraphMetric: edge ($a, $b) has weight $w, which is not a number >= 0")
      if a != b then
        adjacency(a) += ((b, w))
        adjacency(b) += ((a, w))
    val neighbours = adjacency.map(_.result())
    val rows = Array.tabulate(n)(source => shortestPaths(neighbours, source))
    ExplicitMetricSpace(rows.toIndexedSeq.map(ArraySeq.unsafeWrapArray))

  private def shortestPaths(neighbours: Array[Vector[(Int, Double)]], source: Int): Array[Double] =
    val distance = Array.fill(neighbours.length)(Double.PositiveInfinity)
    distance(source) = 0.0
    val queue = new java.util.PriorityQueue[(Double, Int)](16, (x, y) => java.lang.Double.compare(x._1, y._1))
    queue.add((0.0, source))
    while !queue.isEmpty do
      val (d, v) = queue.poll()
      if d <= distance(v) then
        for (u, w) <- neighbours(v) do
          if d + w < distance(u) then
            distance(u) = d + w
            queue.add((distance(u), u))
    distance

  /** The geodesic distances of the `k`-nearest-neighbour graph of `points`, an edge weighing the Euclidean distance of
    * its ends (the graph of Isomap). Two points are joined when either is among the other's `k` nearest. Points the
    * graph does not connect are at distance `Infinity`; raise `k` until the graph is connected.
    */
  def nearestNeighbours(points: PointCloud, k: Int): ExplicitMetricSpace =
    val n = points.size
    require(1 <= k && k < n, s"GraphMetric: k must be between 1 and ${n - 1}, got $k")
    val space = points.metricSpace
    val query = VantagePointQuery(space)
    val edges = for
      v <- 0 until n
      u <- query.nearestNeighbors(v, k + 1)
      if u != v
    yield (v, u, space.distance(v, u))
    geodesic(n, edges)
end GraphMetric
