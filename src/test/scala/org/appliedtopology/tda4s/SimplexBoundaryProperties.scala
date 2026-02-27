package org.appliedtopology.tda4s

import org.scalacheck.Properties
import org.scalacheck.Prop.forAll

import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalatest.matchers.should.Matchers

class SimplexBoundaryProperties extends AnyPropSpec
  with ScalaCheckPropertyChecks
  with Matchers:

  import Field.given
  
  property("Boundary of a boundary is zero (∂² = 0)") {
    forAll { (vertices: List[Int]) =>
      val vertexSet = vertices.toSet
      val simplex = Simplex(vertexSet)

      val boundary1 = simplex.∂[Double]
      val boundary2 = boundary1.flatMap((spx : Simplex) => spx.∂[Double])

      boundary2.isZero
    }
  }

  property("Boundary operator is linear") {
    forAll {
      (vertices1: List[Int], vertices2: List[Int], alpha: Double, beta: Double) =>
        val simplex1 = Simplex(vertices1.toSet)
        val simplex2 = Simplex(vertices2.toSet)

        val combined = MapFormalLinearCombination(Map(simplex1 -> alpha, simplex2 -> beta))

        val combinedBoundary = combined.flatMap(_.∂[Double])

        val individualBoundaries =
          simplex1.∂[Double].scale(alpha) + simplex2.∂[Double].scale(beta)

        combinedBoundary == individualBoundaries
    }
  }

  property("Boundary coefficients alternate correctly") {
    forAll { (vertices: List[Int]) =>
      val vertexSet = vertices.toSet
      val simplex = Simplex(vertexSet)

      val boundary = simplex.∂[Double]
      boundary.support.forall { case (face) =>
        val coeff: Double = boundary.coefficient(face).getOrElse(0.0)
        val verticesRemoved = (simplex.vertices -- face.vertices).toList
        verticesRemoved.size == 1 && verticesRemoved.headOption.exists { removedVertex =>
          val index = simplex.vertices.toList.sorted.indexOf(removedVertex)
          if ((index >= 0) && (index % 2 == 0))
            (coeff == 1.0)
          else
            (coeff == -1.0)
        }
      }
    }
  }