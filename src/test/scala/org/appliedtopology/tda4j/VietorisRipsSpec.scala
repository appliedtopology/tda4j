package org.appliedtopology.tda4j

import org.specs2.{Specification, mutable as s2mutable}
import org.specs2.execute.Result

import scala.collection.immutable.{Seq, Set}
import scala.collection.mutable
import scala.math.{cos,sin}


class VietorisRipsSpec extends s2mutable.Specification {
  "This is a specification of the Vietoris-Rips simplex stream implementation".txt

  "Vietoris-Rips streams should" >> {
    val pts: Seq[Seq[Double]] = Range(0, 10).map(i => Seq(cos(i / 10.0), sin(i / 10.0)))
    val metricSpace: FiniteMetricSpace[Int] = EuclideanMetricSpace(pts)
    val simplexStream: SimplexStream[Int, Double] = VietorisRips[Int](metricSpace, 0.75)
    "have simplices" >> {
      simplexStream.length must beGreaterThan(0)
    }
    "have simplices appear in filtration order" >> {
      simplexStream.map(simplexStream.filtrationValue) must beSorted
    }
    "have subsimplices appear before supersimplices" >> {
      val seen : mutable.Set[AbstractSimplex[Int]] = mutable.Set(AbstractSimplex())
      simplexStream.take(100).foreach(spx => {
        seen += spx
        spx.to(AbstractSimplex).subsets().foreach(face => seen must contain(face))
      })
    }
  }
}
