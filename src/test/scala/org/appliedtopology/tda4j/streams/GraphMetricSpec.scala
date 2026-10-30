package org.appliedtopology.tda4j

import scala.language.experimental.modularity

import org.specs2.mutable.Specification

class GraphMetricSpec extends Specification:

  "geodesic" should {
    "take the shortest path, not the lightest edge" >> {
      // 0 - 1 (1), 1 - 2 (2) and a heavy direct edge 0 - 2 (10): the path through 1 costs 3
      val space = GraphMetric.geodesic(3, Seq((0, 1, 1.0), (1, 2, 2.0), (0, 2, 10.0)))
      (space.distance(0, 2) must beEqualTo(3.0)) and (space.distance(2, 0) must beEqualTo(3.0)) and
        (space.distance(1, 1) must beEqualTo(0.0))
    }

    "put different components at distance infinity" >> {
      val space = GraphMetric.geodesic(4, Seq((0, 1, 1.0), (2, 3, 1.0)))
      (space.distance(0, 3) must beEqualTo(Double.PositiveInfinity)) and (space.distance(2, 3) must beEqualTo(1.0))
    }

    "satisfy the triangle inequality on a random graph" >> {
      val rng = new scala.util.Random(3)
      val edges = for a <- 0 until 12; b <- a + 1 until 12 if rng.nextDouble() < 0.3
      yield (a, b, 0.5 + rng.nextDouble())
      val space = GraphMetric.geodesic(12, edges)
      val violations = for
        x <- 0 until 12
        y <- 0 until 12
        z <- 0 until 12
        if space.distance(x, z) > space.distance(x, y) + space.distance(y, z) + 1e-12
      yield (x, y, z)
      violations must beEmpty
    }

    "reject a negative weight and an edge outside the graph" >> {
      (GraphMetric.geodesic(2, Seq((0, 1, -1.0))) must throwAn[IllegalArgumentException]) and
        (GraphMetric.geodesic(2, Seq((0, 2, 1.0))) must throwAn[IllegalArgumentException])
    }

    "give a barcode a disconnected graph: one essential bar for each component" >> {
      val space = GraphMetric.geodesic(4, Seq((0, 1, 1.0), (2, 3, 1.0)))
      Persistence(space, maxDimension = 0).dim(0).essential.size must beEqualTo(2)
    }
  }

  "nearestNeighbours" should {
    "measure along the circle: antipodal points are pi apart, not 2" >> {
      val n = 100
      val circle =
        PointCloud(Array.tabulate(n)(i => Array(math.cos(2 * math.Pi * i / n), math.sin(2 * math.Pi * i / n))))
      val space = GraphMetric.nearestNeighbours(circle, k = 2)
      space.distance(0, n / 2) must beCloseTo(math.Pi, 1e-3)
    }

    "reject k outside 1 to n - 1" >> {
      val tiny = PointCloud(Array(Array(0.0), Array(1.0), Array(2.0)))
      (GraphMetric.nearestNeighbours(tiny, k = 0) must throwAn[IllegalArgumentException]) and
        (GraphMetric.nearestNeighbours(tiny, k = 3) must throwAn[IllegalArgumentException])
    }
  }
