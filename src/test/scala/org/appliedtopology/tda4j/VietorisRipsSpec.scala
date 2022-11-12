package org.appliedtopology.tda4j

import org.specs2.{Specification, mutable as s2mutable}
import org.specs2.specification.core.Fragment
import org.specs2.execute.Result

import scala.collection.immutable.{Seq, Set}
import scala.collection.mutable
import scala.math.{cos,sin}


class VietorisRipsSpec extends s2mutable.Specification {
  "This is a specification of the Vietoris-Rips simplex stream implementation".txt

  Fragment.foreach(List(new BronKerbosch[Int](), new ZomorodianIncremental[Int]()) : List[CliqueFinder[Int]])(method =>
    s"Vietoris-Rips streams with ${method.className} should" >> {
    val pts: Seq[Seq[Double]] = Range(0, 10).map(i => Seq(cos(i / 10.0), sin(i / 10.0)))
    val metricSpace: FiniteMetricSpace[Int] = EuclideanMetricSpace(pts)
    val simplexStream: SimplexStream[Int, Double] = VietorisRips[Int](metricSpace, 0.75, cliqueFinder=method)
    "have simplices" >> {
      simplexStream.iterator.length must beGreaterThan(0)
    }
    "have simplices appear in filtration order" >> {
      given Ordering[Int] = Ordering.Int
      simplexStream.iterator.map(simplexStream.filtrationValue).to(Seq) must beSorted
    }
    "have subsimplices appear before supersimplices" >> {
      val seen : mutable.Set[AbstractSimplex[Int]] = mutable.Set(AbstractSimplex())
      simplexStream.iterator.take(100).foreach(spx => {
        seen += spx
        spx.to(AbstractSimplex).subsets().foreach(face => seen must contain(face))
      })
    }
  })
}
