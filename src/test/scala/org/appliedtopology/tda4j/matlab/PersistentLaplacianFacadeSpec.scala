package org.appliedtopology.tda4j
package matlab

import SpecThrows.throwWith

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

  private def near(a: Array[Array[Double]], b: Array[Array[Double]]): Boolean =
    a.length == b.length && a.indices.forall(i =>
      a(i).length == b(i).length && a(i).indices.forall(j => math.abs(a(i)(j) - b(i)(j)) < 1e-9)
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

    "give the kernel as rows over the cells: the loop of the four sides, each side with the same weight" in {
      val alive = TDA4j.persistentLaplacianFromPoints(square, 1, 1.0, 1.0)
      val kernel = alive.kernel()
      val matrix = alive.matrix()
      val image = matrix.map(row => row.zip(kernel.head).map(_ * _).sum)
      (kernel.length must beEqualTo(1)) and (kernel.head.length must beEqualTo(4)) and
        (kernel.head.map(math.abs).forall(x => math.abs(x - 0.5) < 1e-9) must beTrue) and
        (image.forall(x => math.abs(x) < 1e-9) must beTrue) and
        (TDA4j.persistentLaplacianFromPoints(square, 1, 1.0, diagonal).kernel() must beEmpty)
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
  "persistentLaplacianOfMapFromPoints" should {
    "be the persistent Laplacian of the points for the identity map of a cloud to itself, entry for entry" in {
      val identity = Array.tabulate(4)(i => i)
      val found = TDA4j.persistentLaplacianOfMapFromPoints(square, square, identity, 1, 1.0, diagonal)
      val expected = TDA4j.persistentLaplacianFromPoints(square, 1, 1.0, diagonal)
      (found.cells().map(_.toSeq).toSeq must beEqualTo(expected.cells().map(_.toSeq).toSeq)) and
        (near(found.matrix(), expected.matrix()) must beTrue) and
        (found.weights().toSeq must beEqualTo(Seq.fill(4)(1.0))) and
        ((found.start(), found.stop(), found.dimension()) must beEqualTo((1.0, diagonal, 1))) and
        (found.bettiNumber() must beEqualTo(0))
    }

    "fold the square onto a segment: the two sides that land on it are one cell of weight 2" in {
      // the vertices 0, 1, 2, 3 go to 0, 1, 1, 0: the sides 12 and 03 are squeezed, 01 and 23 land on the segment
      // (23 reversed). The matrix is [2], computed by hand from the definition in numpy.
      val segment = Array(Array(0.0, 0.0), Array(1.0, 0.0))
      val found = TDA4j.persistentLaplacianOfMapFromPoints(square, segment, Array(0, 1, 1, 0), 1, 1.0, 1.0)
      (found.cells().map(_.toSeq).toSeq must beEqualTo(Seq(Seq(0, 1)))) and
        (found.weights().toSeq must beEqualTo(Seq(2.0))) and
        (near(found.matrix(), Array(Array(2.0))) must beTrue) and
        (found.bettiNumber() must beEqualTo(0)) and
        (found.kernel() must beEmpty)
    }

    "take matrices of distances, and say what is wrong with the arguments" in {
      val identity = Array.tabulate(4)(i => i)
      val byMatrices = TDA4j.persistentLaplacianOfMapFromDistanceMatrices(
        distances(square),
        distances(square),
        identity,
        1,
        1.0,
        1.0
      )
      (byMatrices.bettiNumber() must beEqualTo(1)) and
        // a vertex map of the wrong length, one that leaves the target, and one that is not simplicial
        (TDA4j.persistentLaplacianOfMapFromPoints(square, square, Array(0, 1), 1, 1.0, 1.0) must
          throwWith[IllegalArgumentException]("entries")) and
        (TDA4j.persistentLaplacianOfMapFromPoints(square, square, Array(0, 1, 2, 4), 1, 1.0, 1.0) must
          throwWith[IllegalArgumentException]("sends point 3 to 4")) and
        (TDA4j.persistentLaplacianOfMapFromPoints(square, square, identity, 1, 1.0, 0.5) must
          throwWith[IllegalArgumentException]("simplicial map"))
    }
  }
end PersistentLaplacianFacadeSpec
