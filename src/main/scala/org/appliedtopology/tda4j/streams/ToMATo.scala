package org.appliedtopology.tda4j

/** ToMATo, Topological Mode Analysis Tool (Chazal, Guibas, Oudot, Skraba, "Persistence-based clustering in Riemannian
  * manifolds", J. ACM 60, 2013): clustering by the peaks of a density, where a peak counts only if it is persistent.
  *
  * Take a function `f` (a density estimate: large at the centre of a cluster) on the vertices of a graph (which
  * vertices are neighbours). Walk through the vertices from the highest value down. A vertex with no higher neighbour
  * is a '''peak''' and starts a cluster; any other vertex joins the cluster of its highest neighbour, and if it also
  * touches another cluster, the two merge when the lower of their peaks is less than `tau` above the vertex, that is,
  * when the valley between them is shallower than `tau`. So `tau` is a depth: peaks that rise more than `tau` above
  * every pass that joins them stay separate clusters.
  *
  * The depths of all the valleys are the finite bars of the degree-0 persistent homology of `f` on the graph, which
  * [[diagram]] returns; choose `tau` in a gap of that diagram, between the depths of the peaks you want and the depths
  * of the noise (`Plot.barcode(ToMATo.diagram(...))` shows it).
  *
  * Bars are in the library's sublevel convention for a superlevel filtration: a peak of height `h` is born at `-h`, so
  * a bar's persistence is the depth of its valley.
  */
object ToMATo:

  /** The clusters ToMATo found: `labels(v)` is the cluster of vertex `v`, numbered from `0` in order of decreasing peak
    * height, and `peaks(c)` is the vertex that is the peak of cluster `c`.
    */
  final case class Clustering(labels: IndexedSeq[Int], peaks: IndexedSeq[Int])

  /** The merge tree of one run: the peak each vertex belongs to, and the bars of the merges that took place. */
  private final case class Run(peakOf: Array[Int], bars: List[PersistenceBar[Double, Nothing]], peaks: IndexedSeq[Int])

  private def run(density: IndexedSeq[Double], neighbours: Int => Iterable[Int], tau: Double): Run =
    val n = density.length
    require(tau >= 0.0 && !tau.isNaN, s"ToMATo: tau must be a number >= 0 (infinity merges everything), got $tau")
    require(density.forall(!_.isNaN), "ToMATo: the density has a NaN entry")
    // Undirected adjacency: an edge named from either end counts.
    val adjacency = Array.fill(n)(Set.newBuilder[Int])
    for i <- 0 until n; j <- neighbours(i) do
      require(0 <= j && j < n, s"ToMATo: vertex $i has the neighbour $j, outside 0 until $n")
      if j != i then
        adjacency(i) += j
        adjacency(j) += i
    // Highest density first; equal densities in index order, so the run does not depend on the input's order of ties.
    val order = (0 until n).sortBy(i => (-density(i), i)).toArray
    val rank = new Array[Int](n)
    for r <- order.indices do rank(order(r)) = r
    val parent = Array.tabulate(n)(identity)
    def find(x: Int): Int =
      var root = x
      while parent(root) != root do root = parent(root)
      var v = x
      while parent(v) != root do
        val next = parent(v)
        parent(v) = root
        v = next
      root
    val bars = List.newBuilder[PersistenceBar[Double, Nothing]]
    for i <- order do
      val higher = adjacency(i).result().filter(j => rank(j) < rank(i))
      if higher.nonEmpty then
        parent(i) = find(higher.minBy(rank))
        for j <- higher do
          val (ri, rj) = (find(i), find(j))
          if ri != rj then
            val (upper, lower) = if rank(ri) < rank(rj) then (ri, rj) else (rj, ri)
            // The lower peak is dominated by the other one: it dies at the height of this vertex when the valley between
            // the peaks is shallower than tau.
            if density(lower) < density(i) + tau then
              parent(lower) = upper
              if density(lower) > density(i) then bars += PersistenceBar[Double](0, -density(lower), -density(i))
    val peakOf = Array.tabulate(n)(find)
    val roots = order.filter(i => parent(i) == i).toIndexedSeq
    val essential = roots.map(r => PersistenceBar[Double](0, -density(r)))
    Run(peakOf, bars.result() ++ essential, roots)

  /** The persistence diagram of `density` on the graph: one finite bar for every valley that joins two peaks, with
    * persistence the depth of the valley, and one essential bar for every connected component of the graph (for its
    * highest peak). The graph is given by `neighbours(v)`, the vertices joined to `v`; an edge named from one end
    * counts at both.
    */
  def diagram(density: IndexedSeq[Double], neighbours: Int => Iterable[Int]): List[PersistenceBar[Double, Nothing]] =
    run(density, neighbours, Double.PositiveInfinity).bars

  /** The ToMATo clusters of `density` on the graph for the merging depth `tau` (`>= 0`; `0` leaves every local maximum
    * of the graph a cluster of its own, infinity gives one cluster per connected component).
    */
  def clusterGraph(density: IndexedSeq[Double], neighbours: Int => Iterable[Int], tau: Double): Clustering =
    val r = run(density, neighbours, tau)
    val index = r.peaks.zipWithIndex.toMap
    Clustering(r.peakOf.toIndexedSeq.map(index), r.peaks)

  /** The graph ToMATo uses on a point cloud: each point joined to its `k` nearest neighbours. */
  def neighbourGraph(points: PointCloud, k: Int): IndexedSeq[IndexedSeq[Int]] =
    require(1 <= k && k < points.size, s"ToMATo: k must be between 1 and ${points.size - 1}, got $k")
    val space = points.metricSpace
    val query = VantagePointQuery(space)
    // The nearest neighbours of a point include the point itself.
    IndexedSeq.tabulate(points.size)(v => query.nearestNeighbors(v, k + 1).filter(_ != v))

  /** The density ToMATo uses on a point cloud: minus the distance to measure with `k` nearest neighbours (a point is
    * its own first neighbour, as in GUDHI), so a point in a dense region is high and a bar's persistence is a distance.
    */
  def density(points: PointCloud, k: Int): IndexedSeq[Double] =
    DistanceToMeasure(points.metricSpace, VantagePointQuery(points.metricSpace), k).map(-_)

  /** Clusters the points by ToMATo on the `k`-nearest-neighbour graph, with minus the distance to measure as density
    * (`neighbourGraph` and `density`; call [[clusterGraph]] with your own for anything else). Choose `tau` from
    * `ToMATo.diagram(ToMATo.density(points, k), ToMATo.neighbourGraph(points, k))`.
    */
  def cluster(points: PointCloud, k: Int, tau: Double): Clustering =
    clusterGraph(density(points, k), neighbourGraph(points, k), tau)
end ToMATo
