package org.appliedtopology.tda4j
package streams

/** The empirical distance-to-measure function (Chazal, Cohen-Steiner & Merigot, "Geometric inference for
  * probability measures", Foundations of Computational Mathematics 11:733-751, 2011): for a point set X with the
  * uniform empirical measure and mass parameter `k` (equivalently `m0 = k/|X|`),
  *
  * {{{ f(x) = ( (1/k) * sum over the k nearest neighbours y of x (including x itself) of d(x,y)^q )^(1/q) }}}
  *
  * `q` defaults to 2, matching GUDHI's own `gudhi.point_cloud.dtm.DistanceToMeasure` default. Self-inclusion (`x`
  * counts as one of its own `k` neighbours, at distance 0) is likewise GUDHI's own convention -- confirmed both
  * from that class's own docstring ("k: number of neighbors (possibly including the point itself)") and by
  * reproducing its worked doctest byte-for-byte (`DistanceToMeasureSpec`; see `.claude/WORKLOG-dtm-filtrations.md`
  * for the full derivation and every oracle value checked against). One consequence: `k = 1` always gives `f = 0`
  * everywhere (a point's own nearest neighbour, itself, is at distance 0) -- the degenerate case every consumer of
  * this function (`DtmRipsSimplexStream`, DTM-weighted `alpha.PowerDistance`) should reduce to its un-weighted
  * construction at.
  *
  * Takes a `SpatialQuery`, not a `FiniteMetricSpace` alone, so callers choose the k-NN strategy: `BruteForce` is
  * the safe default (`apply`'s own convenience overload) since VP-tree pruning assumes the triangle inequality,
  * which not every `FiniteMetricSpace` in this codebase actually satisfies (`ExplicitMetricSpace` enforces
  * nothing -- GUDHI's own docs feed correlation-derived "distance" matrices through exactly this class). `JVPTree`
  * is only safe over a genuine metric.
  */
object DistanceToMeasure:
  def apply(metricSpace: FiniteMetricSpace[Int], spatialQuery: SpatialQuery[Int], k: Int, q: Double = 2.0): IndexedSeq[Double] =
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

  /** Convenience overload defaulting to `BruteForce` -- see the class doc for why that, not `JVPTree`, is the safe
    * default over an arbitrary `FiniteMetricSpace`.
    */
  def apply(metricSpace: FiniteMetricSpace[Int], k: Int, q: Double): IndexedSeq[Double] =
    apply(metricSpace, BruteForce(metricSpace), k, q)

  def apply(metricSpace: FiniteMetricSpace[Int], k: Int): IndexedSeq[Double] =
    apply(metricSpace, BruteForce(metricSpace), k, 2.0)
end DistanceToMeasure
