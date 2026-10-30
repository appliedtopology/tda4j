package org.appliedtopology.tda4j

import org.scalacheck.{Arbitrary, Gen}
import org.specs2.{mutable, ScalaCheck, Specification}
import org.specs2.execute.{AsResult, Result}

import math.{cos, sin}
import scala.collection.immutable.Range

class MetricSpaceSpec extends mutable.Specification with ScalaCheck:
  "This is a specification for the implementation of metric spaces".txt

  "A Metric Space should" >> {
    val pts: Seq[Seq[Double]] =
      Range(0, 10).map(i => Seq(cos(i / 10.0), sin(i / 10.0)))
    val metricSpace: FiniteMetricSpace[Int] = EuclideanMetricSpace(pts)

    val metricSpaceGen = Gen.oneOf(metricSpace.elements)
    val metricSpaceArb = Arbitrary(metricSpaceGen)
    given Arbitrary[Int] = metricSpaceArb

    "have points" >> {
      metricSpace.elements.isEmpty must beFalse
    }
    "have as many points as was inputted" >> {
      metricSpace.elements.size must be_==(pts.size)
    }
    "distance from a point to itself must be 0" >>
      AsResult {
        prop { (x: Int) =>
          metricSpace.distance(x, x) must be_==(0.0)
        }
      }
    "distances must follow triangle inequality" >>
      AsResult {
        prop { (x: Int, y: Int, z: Int) =>
          metricSpace.distance(x, y) + metricSpace.distance(y, z) must
            beGreaterThanOrEqualTo(metricSpace.distance(x, z))
        }
      }
    "Vantage-point trees and brute force should find the same neighborhoods" >> {
      val vpq = VantagePointQuery(metricSpace)
      val bf = BruteForce(metricSpace)
      AsResult {
        prop { (x: Int, y: Double) =>
          vpq.neighbors(x, y).toSeq must containTheSameElementsAs(bf.neighbors(x, y).toSeq)
        }
      }
    }
    // Both break ties by the order of `metricSpace.elements` (BruteForce is a stable sort), so the neighbours
    // themselves agree, not just their distances.
    "Vantage-point trees and brute force should find the same nearest neighbours" >> {
      val vpq = VantagePointQuery(metricSpace)
      val bf = BruteForce(metricSpace)
      AsResult {
        prop { (x: Int, kRaw: Int) =>
          val k = 1 + (((kRaw % metricSpace.size) + metricSpace.size) % metricSpace.size)
          val vpqNeighbours = vpq.nearestNeighbors(x, k)
          (vpqNeighbours.size must be_==(k)) and (vpqNeighbours must be_==(bf.nearestNeighbors(x, k)))
        }
      }
    }
    "a point's own nearest neighbour (k = 1) is itself, at distance 0" >>
      AsResult {
        prop { (x: Int) =>
          BruteForce(metricSpace).nearestNeighbors(x, 1) must be_==(IndexedSeq(x))
        }
      }
    // DistanceToMeasure defaults to BruteForce specifically because the tree's pruning assumes the triangle
    // inequality, which coincident points don't violate but do stress -- this fixture duplicates a point so both
    // implementations have to agree on a genuinely tied nearest-neighbour set, not just a generic one.
    "Vantage-point trees and brute force agree on the nearest neighbours with coincident points" >> {
      val dupPts: Seq[Seq[Double]] = pts ++ Seq(pts.head, pts.head)
      val dupSpace: FiniteMetricSpace[Int] = EuclideanMetricSpace(dupPts)
      val vpq = VantagePointQuery(dupSpace)
      val bf = BruteForce(dupSpace)
      (0 until dupSpace.size).forall { x =>
        (1 to dupSpace.size).forall { k =>
          vpq.nearestNeighbors(x, k) == bf.nearestNeighbors(x, k)
        }
      } must beTrue
    }
    "a Euclidean space finds the indices of every point within a radius of an arbitrary query, repeats included" >> {
      val shared = Array(0.5, 0.5)
      val cloud = Array(shared, Array(0.0, 0.0), shared, Array(0.5, 0.5), Array(2.0, 2.0))
      val space = EuclideanMetricSpace(cloud)
      (space.neighbors(Array(0.5, 0.5), 0.0) must be_==(Seq(0, 2, 3))) and
        (space.neighbors(Array(0.0, 0.0), math.sqrt(0.5)) must be_==(Seq(0, 1, 2, 3))) and
        (space.neighbors(Array(0.0, 0.0), 0.5) must be_==(Seq(1)))
    }
    "reject ragged and non-finite coordinates, naming the point and the dimension it was measured against" >> {
      val ragged = Array(Array(0.0, 0.0), Array(1.0), Array(2.0, 2.0))
      (new EuclideanMetricSpace(ragged) must throwAn[IllegalArgumentException].like { case e =>
        e.getMessage must contain("the first has 2, point 1 has 1")
      }) and
        (new EuclideanMetricSpace(Array(Array(0.0), Array(Double.NaN))) must throwAn[IllegalArgumentException].like {
          case e => e.getMessage must contain("point 1 has a coordinate that is NaN or infinite")
        }) and
        (EuclideanMetricSpace(Array(Array(0.0))).pointSqDistance(Array(0.0), Array(0.0, 1.0)) must
          throwAn[IllegalArgumentException])
    }
    "own a stable snapshot of coordinates, whether distances are cached lazily or computed on demand" >> {
      // Everything is changed before the first read, so a lazily filled cache, radius or tree would see the new values.
      def readAfterMutation(cached: Boolean) =
        val first = Array(0.0, 0.0)
        val second = Array(3.0, 4.0)
        val input = Array(first, second)
        val space = new EuclideanMetricSpace(input, cacheDistances = cached)
        first(0) = 100.0
        second(1) = 100.0
        input(1) = Array(1000.0, 1000.0)
        val all = space.pts
        all(0)(1) = -100.0
        all(1) = Array(-1000.0, -1000.0)
        space.point(1)(0) = -1000.0
        (space.distance(0, 1), space.minimumEnclosingRadius, space.neighbors(Array(0.0, 0.0), 5.0))
      (readAfterMutation(cached = true) must be_==((5.0, 5.0, Seq(0, 1)))) and
        (readAfterMutation(cached = false) must be_==((5.0, 5.0, Seq(0, 1))))
    }
    "read one point's coordinates without the others" >> {
      val space = EuclideanMetricSpace(Array(Array(0.0, 1.0), Array(2.0, 3.0)))
      (space.point(1).toSeq must be_==(Seq(2.0, 3.0))) and (space.pts.map(_.toSeq).toSeq must be_==(
        Seq(Seq(0.0, 1.0), Seq(2.0, 3.0))
      ))
    }
  }

  "An explicit distance matrix is read from its lower triangle, as Ripser and GUDHI read it" >> {
    // The lower triangle is a square with diagonals 2 (one loop, born at 1, dying at 2); the upper triangle has every
    // distance 1 (no loop). Reading the upper triangle would report no H_1 bar.
    val m = Seq(
      Seq(0.0, 1.0, 1.0, 1.0),
      Seq(1.0, 0.0, 1.0, 1.0),
      Seq(2.0, 1.0, 0.0, 1.0),
      Seq(1.0, 2.0, 1.0, 0.0)
    )
    val space = ExplicitMetricSpace(m)
    val h1 = Persistence(space, maxDimension = 1, maxFiltrationValue = 3.0).triples.filter(_._1 == 1)
    (space.distance(0, 2) must be_==(2.0)) and (space.distance(2, 0) must be_==(2.0)) and
      (space.distance(1, 3) must be_==(2.0)) and (h1 must be_==(List((1, 1.0, 2.0))))
  }

  "A Euclidean metric space past the cache limit computes distances on demand (50,000 points: n^2 overflows Int)" >> {
    val n = 50000
    val space = EuclideanMetricSpace(Array.tabulate(n)(i => Array(i.toDouble, 0.0)))
    (space.distance(0, n - 1) must be_==((n - 1).toDouble)) and (space.distance(n - 1, 2) must be_==((n - 3).toDouble))
  }

  "The minimum enclosing radius" >> {
    // Its definition: the smallest, over the points, of the largest distance from that point.
    def definition(space: FiniteMetricSpace[Int]): Double =
      space.elements.map(x => space.elements.map(y => space.distance(x, y)).max).min
    def cloud(seed: Int, n: Int, dim: Int): Array[Array[Double]] =
      val rnd = new scala.util.Random(seed)
      Array.tabulate(n)(_ => Array.fill(dim)(rnd.nextDouble() * 10 - 5))
    def matrix(space: FiniteMetricSpace[Int]) =
      ExplicitMetricSpace(Seq.tabulate(space.size)(x => Seq.tabulate(space.size)(y => space.distance(x, y))))
    val inf = Double.PositiveInfinity

    "equal the definition on random clouds of every size and dimension, cached or not, and as a distance matrix" >> {
      val problems = for
        seed <- 0 until 30
        n = 1 + seed
        dim = 1 + seed % 4
        pts = cloud(seed, n, dim)
        space = EuclideanMetricSpace(pts)
        (name, other) <- Seq(
          "cached" -> space,
          "on demand" -> new EuclideanMetricSpace(pts, cacheDistances = false),
          "matrix" -> matrix(space)
        )
        if other.minimumEnclosingRadius != definition(space)
      yield s"$name, $n points in $dim-space: ${other.minimumEnclosingRadius} instead of ${definition(space)}"
      problems must beEmpty
    }
    "not depend on the order of the points (the best centre may come last)" >> {
      def radius(xs: Double*) = EuclideanMetricSpace(xs.map(Seq(_))).minimumEnclosingRadius
      (radius(0.0, 10.0, 5.0) must be_==(5.0)) and (radius(5.0, 0.0, 10.0) must be_==(5.0)) and
        (radius(10.0, 5.0, 0.0) must be_==(5.0)) and (radius(7.0) must be_==(0.0))
    }
    "be the largest listed distance when every point has an unlisted (infinite) one" >> {
      // a 4-cycle without its diagonals
      val cycle = ExplicitMetricSpace(
        Seq(Seq(0.0, 1.0, inf, 1.0), Seq(1.0, 0.0, 1.0, inf), Seq(inf, 1.0, 0.0, 1.0), Seq(1.0, inf, 1.0, 0.0))
      )
      cycle.minimumEnclosingRadius must be_==(1.0)
    }
    "ignore the infinite distances of points that are not the centre" >> {
      // point 0 reaches both others; 1 and 2 do not reach each other
      val star = ExplicitMetricSpace(Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, inf), Seq(2.0, inf, 0.0)))
      star.minimumEnclosingRadius must be_==(2.0)
    }
    "stop looking at a point once its largest distance can no longer beat the best so far" >> {
      class Counting(inner: EuclideanMetricSpace) extends FiniteMetricSpace[Int]:
        var calls = 0L
        def distance(x: Int, y: Int): Double =
          calls += 1
          inner.distance(x, y)
        def size = inner.size
        def elements = inner.elements
        def contains(x: Int) = inner.contains(x)
      val pts = cloud(5, 600, 3)
      val counted = Counting(EuclideanMetricSpace(pts))
      val radius = counted.minimumEnclosingRadius
      // all pairs would be 360,000 distances
      (radius must be_==(definition(EuclideanMetricSpace(pts)))) and (counted.calls must beLessThan(36000L))
    }
    "fail on a space without points, which has no centre" >> {
      ExplicitMetricSpace(Seq.empty[Seq[Double]]).minimumEnclosingRadius must throwA[UnsupportedOperationException]
    }
  }
