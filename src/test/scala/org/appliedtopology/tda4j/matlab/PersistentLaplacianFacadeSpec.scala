package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

/** `TDA4j.persistentLaplacianFromPoints` and `...FromDistanceMatrix`: the library's persistent Laplacian of the
  * Vietoris-Rips complex of the same points, with arrays in and out.
  */
class PersistentLaplacianFacadeSpec extends Specification:

  private val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
  private val diagonal = math.sqrt(2.0)

  private def distances(points: Array[Array[Double]]) =
    Array.tabulate(points.length, points.length)((i, j) =>
      math.hypot(points(i)(0) - points(j)(0), points(i)(1) - points(j)(1))
    )

  "persistentLaplacianFromPoints" should {
    "be the library's: the square's loop is there at 1 and gone at the diagonal, and its matrix is symmetric" in {
      val alive = TDA4j.persistentLaplacianFromPoints(square, 1, 1.0, 1.0)
      val gone = TDA4j.persistentLaplacianFromPoints(square, 1, 1.0, diagonal)
      val library = PersistentLaplacian(
        VietorisRips(EuclideanMetricSpace(square), maxDimension = 1, maxFiltrationValue = 1.0),
        1,
        1.0,
        1.0
      )
      val matrix = alive.matrix()
      (alive.bettiNumber() must beEqualTo(1)) and (gone.bettiNumber() must beEqualTo(0)) and
        (alive.size() must beEqualTo(4)) and (alive.cells().length must beEqualTo(4)) and
        (alive.eigenvalues().toSeq must beEqualTo(library.eigenvalues.toSeq)) and
        (matrix.indices.forall(i =>
          matrix.indices.forall(j => math.abs(matrix(i)(j) - matrix(j)(i)) < 1e-12)
        ) must beTrue) and
        (alive.hasSpectralGap() must beTrue) and (alive.spectralGap() must beGreaterThan(0.0)) and
        ((alive.start(), alive.stop(), alive.dimension()) must beEqualTo((1.0, 1.0, 1)))
    }

    "take a matrix of distances, and say what is wrong with the arguments" in {
      val byMatrix = TDA4j.persistentLaplacianFromDistanceMatrix(distances(square), 1, 1.0, diagonal)
      (byMatrix.bettiNumber() must beEqualTo(0)) and
        (TDA4j.persistentLaplacianFromPoints(square, 1, 2.0, 1.0) must throwAn[IllegalArgumentException]) and
        (TDA4j.persistentLaplacianFromPoints(square, -1, 0.0, 1.0) must throwAn[IllegalArgumentException]) and
        (TDA4j.persistentLaplacianFromPoints(Array.empty[Array[Double]], 1, 0.0, 1.0) must throwAn[
          IllegalArgumentException
        ])
    }

    "have no spectral gap when every eigenvalue is zero" in {
      // five isolated points: the Laplacian on the vertices is the zero matrix
      val far = Array.tabulate(5)(i => Array(10.0 * i, 0.0))
      val result = TDA4j.persistentLaplacianFromPoints(far, 0, 0.0, 1.0)
      (result.bettiNumber() must beEqualTo(5)) and (result.hasSpectralGap() must beFalse) and
        (result.spectralGap().isNaN must beTrue)
    }
  }
end PersistentLaplacianFacadeSpec
