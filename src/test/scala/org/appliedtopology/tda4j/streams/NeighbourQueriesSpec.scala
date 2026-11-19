package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import scala.util.Random

import NeighbourQueriesFixtures.*

/** The neighbour lists of a Euclidean cloud above the distance cache, found by one tree query per point
  * (`neighbourListsWithin`, #313), against the pass over all pairs they replace (`NeighbourLists.within`).
  *
  * The pass IS the specification: the same lists entry for entry (ascending, a point never its own neighbour, a pair at
  * distance exactly the threshold in) and the same distances to the last bit. A shortcut that gives a different list at
  * a threshold equal to a distance, keeps a point as its own neighbour, pairs a distance with the wrong index, counts
  * the entry cap one off, or never gives up where the queries stop paying, fails here. The engines that read the lists
  * are in `NeighbourQueriesEnginesSpec` (JVM only: a Ripser run on 8,200 points is more than a Node heap holds).
  */
class NeighbourQueriesSpec extends Specification:
  sequential

  "The range query with distances finds what the plain query finds, with each distance beside its index" >> {
    val rng = Random(313)
    val points = Vector.fill(600)(Array.fill(3)(rng.nextDouble()))
    val tree = VantagePointTree(
      points,
      (x: Array[Double], y: Array[Double]) => math.sqrt(x.zip(y).map((a, b) => (a - b) * (a - b)).sum)
    )
    val queries = Seq.fill(12)(Array.fill(3)(rng.nextDouble())) ++ points.take(3)
    val wrong = for
      q <- queries
      radius <- Seq(0.0, 0.05, 0.2, 0.7, 5.0)
      (indices, distances) = tree.indicesAndDistancesWithin(q, radius, Long.MaxValue).get
      expected = tree.indicesWithinDistance(q, radius)
      if !(indices.toSeq == expected && distances.toSeq.map(_.toString) == expected
        .map(i => tree.distance(q, points(i)).toString))
    yield (q.toSeq, radius)
    wrong must beEmpty
  }

  "The range query gives up past its measurement cap, and finds nothing for a negative or NaN radius" >> {
    val rng = Random(314)
    val points = Vector.fill(600)(Array.fill(2)(rng.nextDouble()))
    val tree = VantagePointTree(points, (x: Array[Double], y: Array[Double]) => math.hypot(x(0) - y(0), x(1) - y(1)))
    val q = Array(0.5, 0.5)
    (tree.indicesAndDistancesWithin(q, 5.0, 10L) must beNone) and
      (tree.indicesAndDistancesWithin(q, 5.0, 600L).map(_._1.length) must beSome(600)) and
      (tree.indicesAndDistancesWithin(q, -1.0, 0L).map(_._1.length) must beSome(0)) and
      (tree.indicesAndDistancesWithin(q, Double.NaN, 0L).map(_._1.length) must beSome(0))
  }

  "Neighbour lists by queries are the pass's lists, to the last bit, at every threshold, boundary included" >> {
    val space = EuclideanMetricSpace(cloudWithRepeats(8200, big), cacheDistances = false)
    val sparse = radiusFor(3, big)
    val base = pass(space, sparse).get
    // two thresholds equal to a distance in the lists (the boundary), and the ones just below
    val boundary = Seq(base.distances(100), base.distances(base.distances.length / 2))
    val thresholds =
      TestPlatform.perPlatform(
        jvm = Seq(sparse, 1.5 * sparse, 0.0, -1.0, Double.NaN) ++ boundary ++ boundary.map(math.nextDown),
        js = Seq(sparse, 0.0) ++ boundary.take(1) ++ boundary.take(1).map(math.nextDown)
      )
    val wrong = thresholds.filter { t =>
      !(space.neighbourListsWithin(t, Long.MaxValue) match
        case Some(lists) => same(lists, pass(space, t).get)
        case None        => false
      )
    }
    // the repeated points are at distance 0: twenty pairs, forty entries
    (wrong must beEmpty) and (space.neighbourListsWithin(0.0, Long.MaxValue).map(_.entryCount) must beSome(40))
  }

  "A point is never its own neighbour, and its neighbours come in ascending order" >> {
    val space = EuclideanMetricSpace(cloudWithRepeats(2, big), cacheDistances = false)
    val lists = space.neighbourListsWithin(radiusFor(6, big), Long.MaxValue).get
    val ownRow = (0 until big).filter { v =>
      (lists.offsets(v) until lists.offsets(v + 1)).exists(lists.targets(_) == v)
    }
    val unordered = (0 until big).filter { v =>
      (lists.offsets(v) + 1 until lists.offsets(v + 1)).exists(k => lists.targets(k - 1) >= lists.targets(k))
    }
    (ownRow must beEmpty) and (unordered must beEmpty)
  }

  "The queries are given up where they stop paying, and where there are more entries than allowed" >> {
    val space = EuclideanMetricSpace(cloudWithRepeats(7, big), cacheDistances = false)
    val sparse = radiusFor(3, big)
    val entries = pass(space, sparse).get.entryCount
    (space.neighbourListsWithin(0.5, Long.MaxValue) must beNone) and // half of the points in reach of a point
      (space.neighbourListsWithin(sparse, entries.toLong).map(_.entryCount) must beSome(entries)) and
      (space.neighbourListsWithin(sparse, entries - 1L) must beNone) and
      (space.neighbourListsWithin(sparse, 0L) must beNone)
  }

  "Only a cloud above the distance cache has the shortcut" >> {
    val rng = Random(5)
    // under the cache size and sparse: the queries could answer, but the space leaves the pass to the caller
    val small = EuclideanMetricSpace(Array.fill(2000)(Array.fill(3)(rng.nextDouble())))
    val t = radiusFor(4, 2000)
    val matrix = ExplicitMetricSpace(Seq.tabulate(5, 5)((i, j) => math.abs(i - j).toDouble))
    (small.neighbourListsWithin(t, Long.MaxValue) must beNone) and
      (matrix.neighbourListsWithin(2.0, Long.MaxValue) must beNone) and
      ((small.neighbourListsByQueries(t, Long.MaxValue).exists(same(_, pass(small, t).get))) must beTrue)
  }
