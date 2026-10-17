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
