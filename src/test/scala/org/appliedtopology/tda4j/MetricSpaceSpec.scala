package org.appliedtopology.tda4j

import org.specs2.{mutable, Specification}
import org.specs2.execute.Result

import math.{sin,cos}
import scala.collection.immutable.Range

class MetricSpaceSpec extends mutable.Specification {
  "This is a specification for the implementation of metric spaces".txt

  "A Metric Space should" >> {
    val pts: Seq[Seq[Double]] = Range(0, 10).map(i => Seq(cos(i / 10.0), sin(i / 10.0)))
    val metricSpace: FiniteMetricSpace[Int] = EuclideanMetricSpace(pts)
    "have points" >> {
      metricSpace.elements.isEmpty must beFalse
    }
    "have as many points as was inputted" >> {
      metricSpace.elements.size must be_==(pts.size)
    }
    "distance from a point to itself must be 0" >> {
      val i = metricSpace.elements.head
      metricSpace.distance(i,i) must be_==(0.0)
    }
    "distances must follow triangle inequality" >> {
      val it = metricSpace.elements.iterator
      val i = it.next()
      val j = it.next()
      val k = it.next()
      metricSpace.distance(i,j) + metricSpace.distance(j,k) must beGreaterThanOrEqualTo(metricSpace.distance(i,k))
    }
  }
}
