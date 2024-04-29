package org.appliedtopology.tda4j

import org.specs2.{mutable as s2mutable, Specification}
import org.specs2.specification.core.Fragment
import org.specs2.execute.Result

import scala.collection.immutable.{Seq, Set}
import scala.collection.mutable
import scala.math.{cos, sin}

class VietorisRipsSpec extends s2mutable.Specification {
  "This is a specification of the Vietoris-Rips simplex stream implementation\n\n".txt

  val N = 50
  val maxF = 0.75
  val maxD = 3

  given Ordering[Int] = Ordering.Int

  Fragment.foreach(
    List(new BronKerbosch[Int](), new ZomorodianIncremental[Int]()): List[
      CliqueFinder[Int]
    ]
  )(method =>
    s"Vietoris-Rips streams with ${method.className} should" >> {
      val pts: Seq[Seq[Double]] =
        Range(0, N).map(i => Seq(cos(i / N.toDouble), sin(i / N.toDouble)))
      val metricSpace: FiniteMetricSpace[Int] = EuclideanMetricSpace(pts)
      val simplexStream: SimplexStream[Int, Double] =
        VietorisRips[Int](metricSpace, maxF, maxD, cliqueFinder = method)
      given Ordering[AbstractSimplex[Int]] =
        CliqueFinder.simplexOrdering(metricSpace)

      "have simplices" >> {
        simplexStream.iterator.length must beGreaterThan(0)
      }
      "have simplices appear in filtration order" >> {
        simplexStream.iterator.to(Seq) must beSorted
      }
    }
  )

  s"Lazy Vietoris-Rips streams should" >> {
    val pts: Seq[Seq[Double]] =
      Range(0, N).map(i => Seq(cos(i / N.toDouble), sin(i / N.toDouble)))
    val metricSpace: FiniteMetricSpace[Int] = EuclideanMetricSpace(pts)
    given Ordering[AbstractSimplex[Int]] =
      CliqueFinder.simplexOrdering(metricSpace)
    val lazyStream: LazyList[AbstractSimplex[Int]] =
      LazyVietorisRips[Int](metricSpace, maxF, maxD)
    "have simplices" >> {
      lazyStream.iterator.length must beGreaterThan(0)
    }
    "have simplices appear in filtration order" >> {
      lazyStream.iterator.to(Seq) must beSorted
    }
  }

  "First 100 simplices from Lazy and non-Lazy Vietoris-Rips streams should agree" >> {
    val N = 50
    val pts: Seq[Seq[Double]] =
      Range(0, N).map(i => Seq(cos(i / N.toDouble), sin(i / N.toDouble)))
    val metricSpace: FiniteMetricSpace[Int] = EuclideanMetricSpace(pts)
    given Ordering[AbstractSimplex[Int]] =
      CliqueFinder.simplexOrdering(metricSpace)
    val lazyStream: LazyList[AbstractSimplex[Int]] =
      LazyVietorisRips[Int](metricSpace, maxF, maxD)
    val strictStream: SimplexStream[Int,Double] =
      VietorisRips[Int](metricSpace, maxF, maxD, ZomorodianIncremental[Int]())

    val lazy100 = lazyStream.take(100).toList
    val strict100 = strictStream.iterator.take(100).toList

    lazy100 should containTheSameElementsAs(strict100)
  }
}
