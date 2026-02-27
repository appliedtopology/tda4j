package org.appliedtopology.tda4s

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class HomologyComputationSpec extends AnyFunSuite with Matchers {

  test("Persistent homology computation for the boundary of a 3-simplex") {

    // Example: the boundary of a 3-simplex consists of 4 faces (triangles)
    // which themselves are bounded by their 3 edges (simplices of lower dimension).
    val simplicialComplex : Seq[WeightedSimplex] = Seq(
        // 0-simplices
        Simplex(0),
        Simplex(1),
        Simplex(2),
        Simplex(3),

        // 1-simplices
        Simplex(0, 1),
        Simplex(0, 2),
        Simplex(0, 3),
        Simplex(1, 2),
        Simplex(1, 3),
        Simplex(2, 3),

        // 2-simplices (faces of the tetrahedron)
        Simplex(0, 1, 2),
        Simplex(0, 1, 3),
        Simplex(0, 2, 3),
        Simplex(1, 2, 3)
      ).zipWithIndex.map((si) => (si._1,si._2.toDouble))

    // Placeholder barcode result:
    // Seq((dimension, birth, death)) describing persistent homology
    val expectedBarcode = Seq(
      (0, 0.0, Double.PositiveInfinity), // One connected component (0-dimensional homology)
      (0, 1.0, 4.0),
      (0, 2.0, 5.0),
      (0, 3.0, 6.0),
      (1, 7.0, 10.0),
      (1, 8.0, 11.0),
      (1, 9.0, 12.0),
      (2, 13.0, Double.PositiveInfinity) // One void enclosed by the boundary of the tetrahedron
    )

    // API for homology computation -- this will need to be implemented later
    val homologyComputation = HomologyComputation()
    val computedBarcode = homologyComputation.computePersistentHomology(simplicialComplex)

    // Verify the result matches the expected barcode
    computedBarcode shouldEqual expectedBarcode
  }
}