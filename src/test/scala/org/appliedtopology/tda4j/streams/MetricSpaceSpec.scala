package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

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
    "JVP-trees and brute force should find the same neighborhoods" >> {
      val jvp = JVPTree(metricSpace)
      val bf = BruteForce(metricSpace)
      AsResult {
        prop { (x: Int, y: Double) =>
          jvp.neighbors(x, y).toSeq must containTheSameElementsAs(bf.neighbors(x, y).toSeq)
        }
      }
    }
    // Compares the DISTANCES returned, not the point identities: jvptree's own SpatialIndex doc says tie order
    // ("multiple points have the same distance") is undefined, and this fixture (points on a circle) is generic
    // enough that ties are vanishingly unlikely but not something to assert against.
    "JVP-trees and brute force should find the same nearest-neighbour distances" >> {
      val jvp = JVPTree(metricSpace)
      val bf = BruteForce(metricSpace)
      AsResult {
        prop { (x: Int, kRaw: Int) =>
          val k = 1 + (((kRaw % metricSpace.size) + metricSpace.size) % metricSpace.size)
          val jvpDistances = jvp.nearestNeighbors(x, k).map(metricSpace.distance(x, _))
          val bfDistances = bf.nearestNeighbors(x, k).map(metricSpace.distance(x, _))
          (jvpDistances.size must be_==(k)) and (jvpDistances must be_==(bfDistances))
        }
      }
    }
    "a point's own nearest neighbour (k = 1) is itself, at distance 0" >>
      AsResult {
        prop { (x: Int) =>
          BruteForce(metricSpace).nearestNeighbors(x, 1) must be_==(IndexedSeq(x))
        }
      }
    // streams.DistanceToMeasure defaults to BruteForce specifically because JVPTree's pruning assumes the
    // triangle inequality, which coincident points don't violate but do stress (jvptree's own PartitionException
    // is a real, documented failure mode on degenerate configurations) -- this fixture duplicates a point so both
    // implementations have to agree on a genuinely tied nearest-neighbour set, not just a generic one.
    "JVP-trees and brute force agree on nearest-neighbour distances with coincident points" >> {
      val dupPts: Seq[Seq[Double]] = pts ++ Seq(pts.head, pts.head)
      val dupSpace: FiniteMetricSpace[Int] = EuclideanMetricSpace(dupPts)
      val jvp = JVPTree(dupSpace)
      val bf = BruteForce(dupSpace)
      (0 until dupSpace.size).forall { x =>
        (1 to dupSpace.size).forall { k =>
          jvp.nearestNeighbors(x, k).map(dupSpace.distance(x, _)) ==
            bf.nearestNeighbors(x, k).map(dupSpace.distance(x, _))
        }
      } must beTrue
    }
  }
