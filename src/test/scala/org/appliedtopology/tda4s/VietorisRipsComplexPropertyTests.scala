package org.appliedtopology.tda4s

import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalatest.matchers.should.Matchers
import org.scalacheck.Gen
import org.scalacheck.Arbitrary

class VietorisRipsComplexProperties[VRC <: VietorisRipsComplexGenerator](vrComplex : MetricSpace[Int] => VRC) extends AnyPropSpec
  with ScalaCheckPropertyChecks 
  with Matchers {

  import scala.math.sqrt

  // Generators for predefined small metric spaces
  def smallDistanceMatrix: Array[Array[Double]] = Array(
    Array(0.0, 1.0, 2.0),
    Array(1.0, 0.0, 1.5),
    Array(2.0, 1.5, 0.0)
  )

  def smallVectorSpace: Seq[Array[Double]] = Seq(
    Array(0.0, 0.0),
    Array(1.0, 0.0),
    Array(0.0, 1.0)
  )

  // Randomized test cases for metric spaces from generators
  given Arbitrary[MetricSpace[Int]] = Arbitrary {
    Gen.oneOf(
      MetricSpace.fromDistanceMatrix(smallDistanceMatrix),
      MetricSpace.fromPoints(smallVectorSpace)
    )
  }

  // Basic tests for predefined simple distance matrix
  property("0-simplices are generated correctly for a small metric space") {
    val metricSpace = MetricSpace.fromDistanceMatrix(smallDistanceMatrix)
    val zeroSimplices = vrComplex(metricSpace).generateSimplices(0)

    zeroSimplices should contain theSameElementsAs Seq(
      (Simplex(0), 0.0),
      (Simplex(1), 0.0),
      (Simplex(2), 0.0)
    )
  }

  property("1-simplices are generated correctly for a small metric space") {
    val metricSpace = MetricSpace.fromDistanceMatrix(smallDistanceMatrix)
    val oneSimplices = vrComplex(metricSpace).generateSimplices(1)

    oneSimplices should contain theSameElementsAs Seq(
      (Simplex(0, 1), 1.0), // (0 -> 1)
      (Simplex(1, 2), 1.5), // (1 -> 2)
      (Simplex(0, 2), 2.0)  // (0 -> 2)
    )
  }

  property("2-simplices are generated correctly for a small metric space") {
    val metricSpace = MetricSpace.fromDistanceMatrix(smallDistanceMatrix)
    val twoSimplices = vrComplex(metricSpace).generateSimplices(2)

    twoSimplices should contain theSameElementsAs Seq(
      (Simplex(0, 1, 2), 2.0) // Triangle: {0, 1, 2}
    )
  }

  // Randomized property tests
  property("Filtration values of simplices are ascending for any metric space") {
    forAll { (metricSpace: MetricSpace[Int]) =>
      val oneSimplices = vrComplex(metricSpace).generateSimplices(1)
      val filtrationValues = oneSimplices.map(_._2)
      filtrationValues shouldEqual filtrationValues.sorted
    }
  }

  property("2-simplices are constructed from valid 1-simplices for any metric space") {
    forAll { (metricSpace: MetricSpace[Int]) =>
      val oneSimplices : Seq[WeightedSimplex] = vrComplex(metricSpace).generateSimplices(1)
      val twoSimplices : Seq[WeightedSimplex] = vrComplex(metricSpace).generateSimplices(2)

      val allEdges : Set[Set[Int]] = oneSimplices.map(_._1.vertices).toSet
      twoSimplices.foreach { (simplex : WeightedSimplex) =>
        val edges = simplex._1.vertices.subsets(2).toSet
        edges.subsetOf(allEdges) shouldEqual true
      }
    }
  }

  property("Correct number of 0-simplices is generated for any metric space") {
    forAll { (metricSpace: MetricSpace[Int]) =>
      val zeroSimplices = vrComplex(metricSpace).generateSimplices(0)
      zeroSimplices.size shouldEqual metricSpace.elements.size
    }
  }

  property("Higher simplicial filtration values match edge distances") {
    forAll { (metricSpace: MetricSpace[Int]) =>
      val twoSimplices = vrComplex(metricSpace).generateSimplices(2)
      twoSimplices.foreach { case (simplex, filtrationValue) =>
        val edges = simplex.vertices.toSeq.combinations(2).map {
          case Seq(u, v) => metricSpace.distance(u, v)
        }
        filtrationValue shouldEqual edges.max
      }
    }
  }

  property("Vietoris-Rips simplices from vector spaces follow Euclidean distances") {
    val metricSpace = MetricSpace.fromPoints(smallVectorSpace)
    val vietorisRipsComplex = vrComplex(metricSpace)
    val zeroSimplices = vietorisRipsComplex.generateSimplices(0)
    val oneSimplices = vietorisRipsComplex.generateSimplices(1)

    zeroSimplices should contain theSameElementsAs Seq(
      (Simplex(Set(0)), 0.0),
      (Simplex(Set(1)), 0.0),
      (Simplex(Set(2)), 0.0)
    )

    oneSimplices should contain theSameElementsAs Seq(
      (Simplex(Set(0, 1)), 1.0),                  // Distance: 1.0
      (Simplex(Set(0, 2)), 1.0),                  // Distance: 1.0
      (Simplex(Set(1, 2)), sqrt(2.0))             // Distance: sqrt(2)
    )
  }
}

class MemoryEfficientVietorisRipsComplexTest
  extends VietorisRipsComplexProperties[MemoryEfficientVietorisRipsComplex](MemoryEfficientVietorisRipsComplex(_))

class BruteForceVietorisRipsComplexTest
  extends VietorisRipsComplexProperties[BruteForceVietorisRipsComplex](BruteForceVietorisRipsComplex(_))
