package org.appliedtopology.tda4s

import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalatest.matchers.should.Matchers
import org.scalacheck.Gen

class MetricSpaceProperties extends AnyPropSpec
  with ScalaCheckPropertyChecks
  with Matchers {

  // Lp space property testing for a few fixed p-values
  property("Lp distances satisfy the metric space properties for p >= 0") {
    val pValues = List(0.0, 1.0, 2.0, 3.0, Double.PositiveInfinity) // Hamming, Manhattan, Euclidean, L3, L∞ distances
    forAll(vectorGen3) { (x: Array[Double], y: Array[Double], z: Array[Double]) =>
      whenever(x.length == y.length && x.length == z.length) {
        for (p <- pValues) {
          withClue(s"Lp distance for p = $p:") {
            val lpDistance = VectorMetricSpace.lp(p)

            // Non-negativity
            lpDistance(x, y) should be >= 0.0

            // Identity of indiscernibles
            if (x sameElements y) lpDistance(x, y) shouldBe 0.0
            else lpDistance(x, y) should be > 0.0

            lpDistance(x, x) shouldBe 0.0

            // Symmetry
            lpDistance(x, y) shouldBe lpDistance(y, x)

            // Triangle inequality
            lpDistance(x, z) should be <= (lpDistance(x, y) + lpDistance(y, z))
          }
        }
      }
    }
  }

  // Cosine distance properties (not a true metric space, but validated as a pseudo-metric)
  property("Cosine distance satisfies symmetry and non-negativity") {
    forAll(vectorGen3) { (x: Array[Double], y: Array[Double], _) =>
      val cosineDistance = VectorMetricSpace.cosine

      // Non-negativity
      cosineDistance(x, y) should be >= 0.0

      // Symmetry
      cosineDistance(x, y) shouldBe cosineDistance(y, x)
    }
  }

  // Test for DistanceMatrixMetricSpace
  property("DistanceMatrixMetricSpace satisfies metric space properties") {
    forAll(euclideanDistanceMatrixGen) { matrix =>
      val distanceMatrixSpace = new DistanceMatrixMetricSpace(matrix)

      for (i <- matrix.indices; j <- matrix.indices; k <- matrix.indices) {
        // Non-negativity
        distanceMatrixSpace.distance(i, j) should be >= 0.0

        // Identity of indiscernibles
        if (i == j) distanceMatrixSpace.distance(i, j) shouldBe 0.0
        else distanceMatrixSpace.distance(i, j) should be > 0.0

        // Symmetry
        distanceMatrixSpace.distance(i, j) shouldBe distanceMatrixSpace.distance(j, i)

        // Triangle inequality
        distanceMatrixSpace.distance(i, k) should be <= distanceMatrixSpace.distance(i, j) + distanceMatrixSpace.distance(j, k)
      }
    }
  }

  // Generators for inputs
  val vectorGen3: Gen[(Array[Double], Array[Double], Array[Double])] = for {
    size <- Gen.choose(1, 10)
    els1 <- Gen.containerOfN[Array, Double](size, Gen.choose(-100.0, 100.0))
    els2 <- Gen.containerOfN[Array, Double](size, Gen.choose(-100.0, 100.0))
    els3 <- Gen.containerOfN[Array, Double](size, Gen.choose(-100.0, 100.0))
  } yield (els1, els2, els3)

  val euclideanDistanceMatrixGen: Gen[Array[Array[Double]]] = for {
    size <- Gen.choose(2, 10) // Generate size of the matrix
    dim <- Gen.choose(1,10) // Pick dimension of vectors generating the metric
    points <- Gen.containerOfN[List, Array[Double]](size, Gen.containerOfN[Array,Double](dim, Gen.choose(-100.0, 100.0))) // Generate `size` random vectors
  } yield {
    val euclideanDistance : ((Array[Double], Array[Double]) => Double) = VectorMetricSpace.euclidean

    // Create a distance matrix from the points
    val matrix = Array.tabulate(size, size) { (i, j) =>
      if (i == j) 0.0
      else euclideanDistance(points(i), points(j))
    }

    matrix
  }
}