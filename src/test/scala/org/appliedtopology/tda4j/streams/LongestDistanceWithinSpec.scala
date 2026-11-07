package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import scala.util.Random

/** `FiniteMetricSpace.longestDistanceWithin`, the pass that finds the longest edge a `Persistence(points, ...)` diagram
  * reports: the neighbour queries of `EuclideanMetricSpace` and its all-pairs fallback against a plain scan of
  * `distance`, and the longest edge the verb reports against hand-derived values.
  *
  * The scan IS the specification (`d <= threshold`, the boundary included, `0.0` for no pair). A shortcut that answers
  * a different number at a threshold equal to a distance, or that always gives up to the scan, or never gives up where
  * the queries stop paying, fails here.
  */
class LongestDistanceWithinSpec extends Specification:
  sequential

  private def scan(space: FiniteMetricSpace[Int], threshold: Double): Double =
    var longest = 0.0
    val n = space.size
    var i = 0
    while i < n do
      var j = i + 1
      while j < n do
        val d = space.distance(i, j)
        if d <= threshold && d > longest then longest = d
        j += 1
      i += 1
    longest

  private def cloud(rng: Random, n: Int, d: Int): Array[Array[Double]] = Array.fill(n)(Array.fill(d)(rng.nextDouble()))

  // beyond the distance cache (8,192 points), where the override takes over from the plain pass
  private val big = EuclideanMetricSpace.MaxCachedPoints + 8

  "The default pass, on a space given by its distance matrix" >> {
    // points 0, 1, 3, 6 on a line: the distances are 1, 3, 6, 2, 5, 3
    val xs = Seq(0.0, 1.0, 3.0, 6.0)
    val line = ExplicitMetricSpace(xs.map(x => xs.map(y => math.abs(x - y))))
    val thresholds = Seq(-1.0, 0.0, 0.5, 1.0, 2.0, 2.9, 3.0, 5.0, 6.0, 100.0, Double.PositiveInfinity)
    thresholds.map(line.longestDistanceWithin) must beEqualTo(
      Seq(0.0, 0.0, 0.0, 1.0, 2.0, 2.0, 3.0, 5.0, 6.0, 6.0, 6.0)
    )
    line.longestDistanceWithin(Double.NaN) must beEqualTo(0.0)
  }

  "Neighbour queries and the all-pairs pass give the scan's value, at every threshold, boundary included" >> {
    val rng = Random(312)
    val space = EuclideanMetricSpace(cloud(rng, 400, 3))
    val all = (for i <- 0 until 400; j <- i + 1 until 400 yield space.distance(i, j)).sorted
    // thresholds equal to a distance in the data (the boundary), just below it, and the extremes
    val thresholds = Seq(0.0, -1.0, Double.NaN, all.head / 2, Double.PositiveInfinity) ++
      Seq(all.head, all(10), all(100), all(1000), all(all.length / 2), all.last).flatMap(d => Seq(d, math.nextDown(d)))
    val wrong = thresholds.flatMap { t =>
      val expected = scan(space, t)
      val queried = space.longestDistanceByQueries(t)
      val rows = space.longestDistanceByRows(t)
      val sequentialRows = Parallel.sequentially(space.longestDistanceByRows(t))
      Seq(
        Option.when(!queried.forall(_ == expected))(s"queries at $t: expected $expected, got $queried"),
        Option.when(rows != expected)(s"rows at $t: expected $expected, got $rows"),
        Option.when(sequentialRows != expected)(s"sequential rows at $t: expected $expected, got $sequentialRows"),
        Option.when(space.longestDistanceWithin(t) != expected)(s"longestDistanceWithin at $t")
      ).flatten
    }
    wrong.take(3) must beEmpty
  }

  "Neighbour queries are used where the threshold keeps few pairs and given up where it keeps many" >> {
    val n = 2000
    val rng = Random(2026)
    val space = EuclideanMetricSpace(cloud(rng, n, 3))
    // about four neighbours per point in the unit cube: far under the n / 4 = 500 distances a query may measure
    val sparse = math.cbrt(3.0 * 4 / (4 * math.Pi * n))
    // about half of the other points in reach of a typical point
    val dense = 0.5
    space.longestDistanceByQueries(sparse) must beSome(scan(space, sparse))
    space.longestDistanceByQueries(dense) must beNone
  }

  "Points on a line: the longest edge is the threshold itself when it is a distance, else the one below" >> {
    // integer coordinates, so every distance is an integer: the values are the hand-derived ones
    val line = EuclideanMetricSpace(Array.tabulate(big)(i => Array(i.toDouble)))
    line.longestDistanceWithin(3.0) must beEqualTo(3.0)
    line.longestDistanceWithin(2.5) must beEqualTo(2.0)
    line.longestDistanceByQueries(3.0) must beSome(3.0)
    line.longestDistanceByQueries(0.5) must beSome(0.0)
    line.longestDistanceWithin(0.5) must beEqualTo(0.0)
    line.longestDistanceWithin(-1.0) must beEqualTo(0.0)
  }

  "Above the distance cache, a sparse and a dense cloud both give the scan's value" >> {
    val rng = Random(8200)
    val space = EuclideanMetricSpace(cloud(rng, big, 3), cacheDistances = false)
    // about three neighbours per point in the unit cube
    val sparse = math.cbrt(3.0 * 3 / (4 * math.Pi * big))
    val dense = 0.5
    space.longestDistanceWithin(sparse) must beEqualTo(scan(space, sparse))
    space.longestDistanceByQueries(sparse) must beSome(scan(space, sparse))
    space.longestDistanceByQueries(dense) must beNone
    // a scan of 33 million distances is a few seconds on Scala.js; the dense fallback is also the example below
    if TestPlatform.perPlatform(jvm = true, js = false) then
      space.longestDistanceWithin(dense) must beEqualTo(scan(space, dense))
    success
  }

  "Coincident points: every query is given up, and the pass over the pairs finds the distance" >> {
    // all pairs within reach: queries cost more than the pass over the pairs, which is what the cap is for
    val same = EuclideanMetricSpace(Array.fill(big)(Array(0.25, 0.5)), cacheDistances = false)
    same.longestDistanceByQueries(0.5) must beNone
    // two far points among many coincident ones, at distances 5 and 6 from them and sqrt(13) from each other
    val points = Array.fill(big - 2)(Array(0.0, 0.0)) ++ Array(Array(3.0, 4.0), Array(0.0, 6.0))
    val mixed = EuclideanMetricSpace(points, cacheDistances = false)
    mixed.longestDistanceWithin(5.0) must beEqualTo(5.0)
    mixed.longestDistanceWithin(6.0) must beEqualTo(6.0)
    // each call is a pass over 33 million pairs: the other thresholds on the JVM only
    if TestPlatform.perPlatform(jvm = true, js = false) then
      same.longestDistanceWithin(0.5) must beEqualTo(0.0)
      mixed.longestDistanceWithin(4.9) must beEqualTo(math.sqrt(13.0))
      mixed.longestDistanceWithin(5.5) must beEqualTo(5.0)
      mixed.longestDistanceWithin(10.0) must beEqualTo(6.0)
    success
  }

  "The diagram's last filtration value is the longest edge within the threshold, on both Ripser engines" >> {
    // the points 0, 1, ..., n - 1 on a line with threshold 2.5: edges of length 1 and 2, nothing longer. The call sites
    // are the same at any size; the JVM runs one above the distance cache too.
    val n = TestPlatform.perPlatform(jvm = big, js = 600)
    val line = Array.tabulate(n)(i => Array(i.toDouble))
    val engines = Seq(Persistence.Engine.Ripser, Persistence.Engine.ParallelRipser)
    val reported = engines.map { engine =>
      Persistence(line, maxDimension = 1, maxFiltrationValue = 2.5, engine = engine).lastFiltrationValue
    }
    reported must beEqualTo(Seq(2.0, 2.0))
  }
