package org.appliedtopology.tda4j

import scala.util.Random

/** The shared helpers of `NeighbourQueriesSpec` (both platforms) and `NeighbourQueriesEnginesSpec` (JVM). */
object NeighbourQueriesFixtures:
  // beyond the distance cache (8,192 points), where the shortcut takes over from the pass
  val big = EuclideanMetricSpace.MaxCachedPoints + 8

  // the pass over all pairs, as the engines run it where the space has no shortcut
  def pass(space: FiniteMetricSpace[Int], threshold: Double, maxEntries: Long = Long.MaxValue) =
    NeighbourLists.within(space.size, threshold, maxEntries)(space.distance(_, _))

  // Arrays.equals on doubles compares their bits: the same distances to the last bit
  def same(a: NeighbourLists, b: NeighbourLists): Boolean =
    java.util.Arrays.equals(a.offsets, b.offsets) && java.util.Arrays.equals(a.targets, b.targets) &&
      java.util.Arrays.equals(a.distances, b.distances)

  // a Euclidean space seen through the trait alone: the same distances, no shortcut
  def noShortcut(points: Array[Array[Double]]): FiniteMetricSpace[Int] =
    new FiniteMetricSpace[Int]:
      private val base = EuclideanMetricSpace(points, cacheDistances = false)
      def distance(x: Int, y: Int): Double = base.distance(x, y)
      def size: Int = base.size
      def elements: Iterable[Int] = base.elements
      def contains(x: Int): Boolean = base.contains(x)

  // 3-D uniform points; the last twenty repeat the first twenty, so there are pairs at distance 0
  def cloudWithRepeats(seed: Long, n: Int): Array[Array[Double]] =
    val rng = Random(seed)
    val fresh = Array.fill(n - 20)(Array.fill(3)(rng.nextDouble()))
    fresh ++ fresh.take(20).map(_.clone())

  // about `k` neighbours per point in the unit cube
  def radiusFor(k: Double, n: Int): Double = math.cbrt(3.0 * k / (4 * math.Pi * n))
