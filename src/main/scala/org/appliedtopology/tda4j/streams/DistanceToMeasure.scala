package org.appliedtopology.tda4j

/** The empirical distance to measure (Chazal, Cohen-Steiner, Mérigot, "Geometric inference for probability measures",
  * Found. Comput. Math. 11, 2011) with `k` neighbours:
  * {{{
  * f(x) = ( (1/k) sum over the k nearest neighbours y of x of d(x, y)^q )^(1/q)
  * }}}
  * As in GUDHI, `x` counts among its own neighbours (so `k = 1` gives `f = 0`) and `q` defaults to `2`.
  *
  * The `SpatialQuery` chooses the nearest-neighbour search. `BruteForce` works for any distances; `JVPTree` requires
  * the triangle inequality, which an [[ExplicitMetricSpace]] does not check.
  */
object DistanceToMeasure:
  def apply(
    metricSpace: FiniteMetricSpace[Int],
    spatialQuery: SpatialQuery[Int],
    k: Int,
    q: Double = 2.0
  ): IndexedSeq[Double] =
    require(1 <= k && k <= metricSpace.size, s"k must be between 1 and ${metricSpace.size}, got $k")
    require(q > 0.0, s"q must be positive, got $q")
    metricSpace.elements.toIndexedSeq.sorted.map { x =>
      val sumPow = spatialQuery
        .nearestNeighbors(x, k)
        .iterator
        .map(y => math.pow(metricSpace.distance(x, y), q))
        .sum
      math.pow(sumPow / k, 1.0 / q)
    }

  /** The distance to measure of every point, by brute-force neighbour search (valid for any distances). */
  def apply(metricSpace: FiniteMetricSpace[Int], k: Int, q: Double): IndexedSeq[Double] =
    apply(metricSpace, BruteForce(metricSpace), k, q)

  def apply(metricSpace: FiniteMetricSpace[Int], k: Int): IndexedSeq[Double] =
    apply(metricSpace, BruteForce(metricSpace), k, 2.0)
end DistanceToMeasure
