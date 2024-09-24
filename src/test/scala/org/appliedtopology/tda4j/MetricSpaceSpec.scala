package org.appliedtopology.tda4j

import org.scalacheck.{Arbitrary, Gen}
import org.specs2.{mutable, ScalaCheck, Specification}
import org.specs2.execute.{AsResult, Result}

import math.{cos, sin}
import scala.collection.immutable.Range

class MetricSpaceSpec extends mutable.Specification with ScalaCheck {
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
    "distance from a point to itself must be 0" >> {
      AsResult {
        prop { (x: Int) =>
          metricSpace.distance(x, x) must be_==(0.0)
        }
      }
    }
    "distances must follow triangle inequality" >> {
      AsResult {
        prop { (x: Int, y: Int, z: Int) =>
          metricSpace.distance(x, y) + metricSpace.distance(y, z) must
            beGreaterThanOrEqualTo(metricSpace.distance(x, z))
        }
      }
    }
    "JVP-trees and brute force should find the same neighborhoods" >> {
      val jvp = JVPTree(metricSpace)
      val bf = BruteForce(metricSpace)
      AsResult {
        prop { (x: Int, y: Double) =>
          jvp.neighbors(x, y).toSeq must containTheSameElementsAs(bf.neighbors(x,y).toSeq)
        }
      }
    }
  }
}
