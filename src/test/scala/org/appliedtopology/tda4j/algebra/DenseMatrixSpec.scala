package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.SpecThrows.throwWith
import org.appliedtopology.tda4j.DenseFixtures.*
import org.specs2.mutable

import scala.util.Random

/** The dense matrix type and its two decompositions. Every property here needs no reference implementation: the SVD is
  * checked as `A = U S V^T` with orthonormal `U`, `V` and descending `S`; the LU solver by residuals and by systems
  * with a known answer. (`DenseLinearAlgebraOracleSpec`, JVM only, compares with commons-math3.)
  */
class DenseMatrixSpec extends mutable.Specification:

  "the matrix type" should {
    val a = DenseMatrix(Array(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0)))
    "know its shape and entries" in {
      (a.rows, a.cols) must beEqualTo((2, 3))
      a(1, 2) must beEqualTo(6.0)
      a.row(1) must beEqualTo(DenseVector(4.0, 5.0, 6.0))
      a.column(1) must beEqualTo(DenseVector(2.0, 5.0))
    }
    "transpose, multiply by matrices and by vectors, subtract" in {
      a.transpose must beEqualTo(DenseMatrix(Array(Array(1.0, 4.0), Array(2.0, 5.0), Array(3.0, 6.0))))
      (a * a.transpose) must beEqualTo(DenseMatrix(Array(Array(14.0, 32.0), Array(32.0, 77.0))))
      (a * DenseVector(1.0, 0.0, -1.0)) must beEqualTo(DenseVector(-2.0, -2.0))
      (a - a) must beEqualTo(DenseMatrix.zeros(2, 3))
      DenseMatrix.identity(3) * DenseVector(7.0, 8.0, 9.0) must beEqualTo(DenseVector(7.0, 8.0, 9.0))
    }
    "be a value: equal entries mean equal matrices with equal hash codes" in {
      val b = DenseMatrix(Array(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0)))
      a must beEqualTo(b)
      a.hashCode must beEqualTo(b.hashCode)
      a must not(beEqualTo(a.transpose))
      a must not(beEqualTo(DenseMatrix(Array(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.5)))))
    }
    "copy the array it is built from, and hand out copies" in {
      val source = Array(Array(1.0, 2.0), Array(3.0, 4.0))
      val m = DenseMatrix(source)
      source(0)(0) = 99.0
      m(0, 0) must beEqualTo(1.0)
      m.toArray(1)(1) = 99.0
      m(1, 1) must beEqualTo(4.0)
    }
    "refuse ragged rows, mismatched products and entries out of range" in {
      DenseMatrix(Array(Array(1.0, 2.0), Array(3.0))) must throwAn[IllegalArgumentException]
      (a * a) must throwAn[IllegalArgumentException]
      (a - a.transpose) must throwAn[IllegalArgumentException]
      (a * DenseVector(1.0)) must throwAn[IllegalArgumentException]
      a(2, 0) must throwAn[IndexOutOfBoundsException]
      a(0, 3) must throwAn[IndexOutOfBoundsException]
      // Not a different cell of the same row-major array:
      a(0, -1) must throwAn[IndexOutOfBoundsException]
    }
  }

  "the LU decomposition (partial pivoting)" should {
    "solve a system whose answer is known" in {
      val a = DenseMatrix(Array(Array(2.0, 1.0, 1.0), Array(1.0, 3.0, 2.0), Array(1.0, 0.0, 0.0)))
      val x = DenseVector(1.0, 2.0, 3.0)
      val lu = a.lu()
      lu.isNonSingular must beTrue
      val solved = lu.solve(a * x)
      (solved - x).norm must beLessThan(1e-14)
    }
    "pivot: a zero in the leading position is no obstacle" in {
      val a = DenseMatrix(Array(Array(0.0, 1.0), Array(1.0, 0.0)))
      val lu = a.lu()
      lu.isNonSingular must beTrue
      lu.solve(DenseVector(3.0, 5.0)) must beEqualTo(DenseVector(5.0, 3.0))
    }
    "pivot on the largest entry, so that a tiny leading pivot does not spoil the answer" in {
      // With no pivoting the answer is wrong by O(1) (the textbook example); partial pivoting gives it to rounding.
      val a = DenseMatrix(Array(Array(1e-20, 1.0), Array(1.0, 1.0)))
      val x = a.lu().solve(DenseVector(1.0, 2.0))
      math.abs(x(0) - 1.0) must beLessThan(1e-12)
      math.abs(x(1) - 1.0) must beLessThan(1e-12)
    }
    "have a small residual on random well-conditioned systems of every size up to 12" in {
      val rng = Random(11)
      val problems = for
        n <- 1 to 12
        trial <- 0 until 20
        shift = 2.0 * math.sqrt(n)
        a = DenseMatrix.tabulate(n, n)((i, j) => rng.nextGaussian() + (if i == j then shift else 0.0))
        b = DenseVector.tabulate(n)(_ => rng.nextGaussian())
        lu = a.lu()
        if lu.isNonSingular
        residual = (a * lu.solve(b) - b).norm / math.max(1.0, b.norm)
        if residual > 1e-11
      yield s"n=$n trial=$trial residual=$residual"
      problems must beEmpty
    }
    "report a singular matrix, and a nonsingular one, by the size of the pivots" in {
      DenseMatrix(Array(Array(1.0, 2.0), Array(2.0, 4.0))).lu().isNonSingular must beFalse
      DenseMatrix(Array(Array(1.0, 2.0), Array(3.0, 4.0))).lu().isNonSingular must beTrue
      DenseMatrix.zeros(3, 3).lu().isNonSingular must beFalse
      DenseMatrix.zeros(3, 3).lu(singularityThreshold = 0.0).isNonSingular must beFalse
      // A row repeated exactly: a zero pivot.
      DenseMatrix(Array(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0), Array(1.0, 2.0, 3.0)))
        .lu()
        .isNonSingular must beFalse
    }
    "call a matrix singular exactly when a pivot is below the threshold" in {
      // After pivoting on the 2, the second pivot is 2 - 0.5 * 4.0000001 = -5e-8.
      val a = DenseMatrix(Array(Array(1.0, 2.0), Array(2.0, 4.0000001)))
      a.lu(singularityThreshold = 1e-6).isNonSingular must beFalse
      a.lu(singularityThreshold = 1e-9).isNonSingular must beTrue
    }
    "refuse to solve with a singular matrix, saying what to do" in {
      val lu = DenseMatrix(Array(Array(1.0, 2.0), Array(2.0, 4.0))).lu()
      lu.solve(DenseVector(1.0, 2.0)) must throwAn[IllegalArgumentException].like { case e =>
        // (not `throwWith[...]("singular")`: specs2 matches a message differently on Scala.js)
        e.getMessage.contains("singular") must beTrue
      }
    }
    "refuse a matrix that is not square, and a right-hand side of the wrong length" in {
      DenseMatrix.zeros(2, 3).lu() must throwAn[IllegalArgumentException]
      DenseMatrix.identity(3).lu().solve(DenseVector(1.0, 2.0)) must throwAn[IllegalArgumentException]
    }
    "handle a 1x1 matrix" in {
      DenseMatrix(Array(Array(4.0))).lu().solve(DenseVector(2.0)) must beEqualTo(DenseVector(0.5))
      DenseMatrix(Array(Array(0.0))).lu().isNonSingular must beFalse
    }
  }

  "the singular value decomposition" should {
    "be a compact SVD (A = U S V^T, orthonormal U and V, descending S) of random matrices of every shape" in {
      val rng = Random(7)
      val problems = for
        (m, n) <- shapes
        trial <- 0 until 6
        problem <- svdProblems(gaussian(rng, m, n))
      yield s"${m}x$n trial $trial: $problem"
      problems must beEmpty
    }
    "return U with p = min(m, n) columns and V with p columns, swapping the roles for wide matrices" in {
      val tall = gaussian(Random(1), 7, 3).svd
      (tall.u.rows, tall.u.cols, tall.v.rows, tall.v.cols, tall.singularValues.length) must beEqualTo((7, 3, 3, 3, 3))
      val wide = gaussian(Random(2), 3, 7).svd
      (wide.u.rows, wide.u.cols, wide.v.rows, wide.v.cols, wide.singularValues.length) must beEqualTo((3, 3, 7, 3, 3))
    }
    "satisfy the SVD of the transpose: same singular values, U and V exchanged" in {
      val a = gaussian(Random(3), 5, 8)
      val s = a.svd.singularValues
      val st = a.transpose.svd.singularValues
      s.zip(st).map((x, y) => math.abs(x - y)).max must beLessThan(1e-13)
    }
    "know the singular values of simple matrices" in {
      diagonal(Seq(3.0, 4.0)).svd.singularValues must beEqualTo(Seq(4.0, 3.0))
      // sign and order of a diagonal do not matter, only magnitudes
      diagonal(Seq(-1.0, 5.0, 0.25)).svd.singularValues must beEqualTo(Seq(5.0, 1.0, 0.25))
      val ones = DenseMatrix(Array(Array(1.0, 1.0), Array(1.0, 1.0))).svd.singularValues
      math.abs(ones(0) - 2.0) must beLessThan(1e-15)
      ones(1) must beLessThan(1e-15)
    }
    "have the invariants sigma_1 sigma_2 = |det A| and sigma_1^2 + sigma_2^2 = |A|_F^2 for [[1,2],[3,4]]" in {
      val s = DenseMatrix(Array(Array(1.0, 2.0), Array(3.0, 4.0))).svd.singularValues
      math.abs(s(0) * s(1) - 2.0) must beLessThan(1e-14)
      math.abs(s(0) * s(0) + s(1) * s(1) - 30.0) must beLessThan(1e-13)
      s(0) must beGreaterThan(s(1))
    }
    "keep widely graded singular values to high relative accuracy" in {
      val s = diagonal(Seq(1e10, 1.0, 1e-10)).svd.singularValues
      math.abs(s(0) / 1e10 - 1.0) must beLessThan(1e-15)
      math.abs(s(1) - 1.0) must beLessThan(1e-15)
      math.abs(s(2) / 1e-10 - 1.0) must beLessThan(1e-12)
    }
    "handle the zero matrix, a zero column and a repeated column, with U and V still orthonormal" in {
      val shapesWithZeros = Seq(
        DenseMatrix.zeros(3, 3),
        DenseMatrix.zeros(4, 2),
        DenseMatrix.zeros(2, 4),
        DenseMatrix(Array(Array(1.0, 0.0, 2.0), Array(3.0, 0.0, 4.0), Array(5.0, 0.0, 6.0))),
        DenseMatrix(Array(Array(1.0, 1.0, 2.0), Array(3.0, 3.0, 4.0), Array(5.0, 5.0, 6.0), Array(7.0, 7.0, 8.0))),
        DenseMatrix(Array(Array(0.0), Array(0.0)))
      )
      val problems = for
        (a, i) <- shapesWithZeros.zipWithIndex
        problem <- svdProblems(a)
      yield s"matrix #$i (${a.rows}x${a.cols}): $problem"
      problems must beEmpty
      DenseMatrix.zeros(3, 3).svd.singularValues must beEqualTo(Seq(0.0, 0.0, 0.0))
    }
    "handle rank-deficient matrices: the trailing singular values vanish and the rank is found" in {
      val rng = Random(5)
      val problems = for
        (m, n, r) <- Seq((6, 6, 2), (8, 4, 3), (4, 8, 3), (10, 10, 1), (7, 5, 4), (20, 20, 7))
        trial <- 0 until 5
        a = lowRank(rng, m, n, r)
        svd = a.svd
        wrongRank = Option.when(svd.rank != r)(s"rank ${svd.rank}, expected $r (values ${svd.singularValues})")
        notZero = Option.when(svd.singularValues.drop(r).exists(_ > 1e-12 * svd.singularValues.head))("trailing not 0")
        problem <- svdProblems(a) ++ wrongRank.toSeq ++ notZero.toSeq
      yield s"${m}x$n rank $r trial $trial: $problem"
      problems must beEmpty
    }
    "handle 1x1 matrices, positive and negative, with the sign carried by U or V" in {
      val problems = for
        x <- Seq(3.0, -3.0, 0.0, 1e-300, -2.5e7)
        problem <- svdProblems(DenseMatrix(Array(Array(x))))
      yield s"1x1 [$x]: $problem"
      problems must beEmpty
      DenseMatrix(Array(Array(-3.0))).svd.singularValues must beEqualTo(Seq(3.0))
    }
    "work on an empty matrix" in {
      val svd = DenseMatrix.zeros(0, 0).svd
      svd.singularValues must beEmpty
      DenseMatrix.zeros(3, 0).svd.singularValues must beEmpty
    }
    "give orthonormal U also for a wide matrix whose rows are dependent (the null directions of the points)" in {
      // The shape `Hyperplane.from` uses: the transpose of the differences of the points of a facet. Two points in the
      // plane have a null direction in R^3 that must come out of the decomposition as a unit vector.
      val differences = DenseMatrix(Array(Array(0.0, 0.0, 0.0), Array(1.0, 0.0, 0.0), Array(0.0, 1.0, 0.0)))
      val svd = differences.transpose.svd
      svd.singularValues(2) must beLessThan(1e-15)
      val nullDirection = svd.u.column(2)
      math.abs(nullDirection.norm - 1.0) must beLessThan(1e-14)
      math.abs(math.abs(nullDirection(2)) - 1.0) must beLessThan(1e-14)
      math.abs(nullDirection(0)) must beLessThan(1e-14)
      math.abs(nullDirection(1)) must beLessThan(1e-14)
    }
  }

  "the pseudo-inverse solution V S^+ U^T b" should {
    "solve a square nonsingular system" in {
      val a = DenseMatrix(Array(Array(2.0, 1.0), Array(1.0, 3.0)))
      val x = DenseVector(1.5, -2.0)
      (a.svd.solve(a * x) - x).norm must beLessThan(1e-14)
    }
    "recover the exact solution of a consistent overdetermined system of full column rank" in {
      val rng = Random(13)
      val problems = for
        (m, n) <- Seq((4, 2), (10, 3), (30, 7), (6, 6))
        trial <- 0 until 10
        a = gaussian(rng, m, n)
        x = DenseVector.tabulate(n)(_ => rng.nextGaussian())
        error = (a.svd.solve(a * x) - x).norm / x.norm
        if error > 1e-10
      yield s"${m}x$n trial $trial: error $error"
      problems must beEmpty
    }
    "satisfy the normal equations for an inconsistent system (the least squares solution)" in {
      val rng = Random(17)
      val problems = for
        (m, n) <- Seq((5, 2), (12, 4), (40, 6))
        trial <- 0 until 10
        a = gaussian(rng, m, n)
        b = DenseVector.tabulate(m)(_ => rng.nextGaussian())
        x = a.svd.solve(b)
        gradient = a.transpose * (a * x - b)
        if gradient.norm > 1e-10 * math.max(1.0, b.norm * a.frobeniusNorm)
      yield s"${m}x$n trial $trial: A^T(Ax - b) = ${gradient.norm}"
      problems must beEmpty
    }
    "return the minimum norm solution of an underdetermined system" in {
      val a = DenseMatrix(Array(Array(1.0, 1.0, 0.0)))
      (a.svd.solve(DenseVector(2.0)) - DenseVector(1.0, 1.0, 0.0)).norm must beLessThan(1e-14)
      // And in general it lies in the row space: it is orthogonal to every null vector w of A.
      val rng = Random(19)
      val wide = gaussian(rng, 3, 8)
      val b = DenseVector(1.0, 2.0, 3.0)
      val x = wide.svd.solve(b)
      (wide * x - b).norm must beLessThan(1e-12)
      val t = DenseVector.tabulate(8)(_ => rng.nextGaussian())
      val w = t - wide.svd.solve(wide * t)
      (wide * w).norm must beLessThan(1e-12)
      w.norm must beGreaterThan(0.1)
      math.abs(x.dot(w)) must beLessThan(1e-12)
    }
    "treat a rank-deficient matrix by the pseudo-inverse: the minimum norm least squares solution" in {
      // x1 + x2 = 2 twice over: the minimum norm solution is (1, 1).
      val a = DenseMatrix(Array(Array(1.0, 1.0), Array(1.0, 1.0)))
      (a.svd.solve(DenseVector(2.0, 2.0)) - DenseVector(1.0, 1.0)).norm must beLessThan(1e-14)
      // An inconsistent right-hand side is projected: (1, 3) -> the least squares answer for x1 + x2 = 2 is (1, 1).
      (a.svd.solve(DenseVector(1.0, 3.0)) - DenseVector(1.0, 1.0)).norm must beLessThan(1e-14)
      // The zero matrix has the zero solution, not a division by zero.
      DenseMatrix.zeros(2, 2).svd.solve(DenseVector(1.0, 1.0)) must beEqualTo(DenseVector(0.0, 0.0))
    }
    "cut off singular values below max(m, n) * sigma_1 * 2^-52, as the rank does" in {
      // sigma = (1, 1e-17): the second is below 2 * 2^-52 ~ 4.4e-16 and is dropped, not inverted into 1e17.
      val a = diagonal(Seq(1.0, 1e-17))
      val svd = a.svd
      svd.rank must beEqualTo(1)
      svd.tolerance must beCloseTo(2.0 * math.pow(2.0, -52), 1e-30)
      (svd.solve(DenseVector(1.0, 1.0)) - DenseVector(1.0, 0.0)).norm must beLessThan(1e-14)
      // sigma = (1, 1e-10) is above the cutoff and is inverted.
      val b = diagonal(Seq(1.0, 1e-10)).svd
      b.rank must beEqualTo(2)
      math.abs(b.solve(DenseVector(1.0, 1.0))(1) - 1e10) must beLessThan(1e-2)
    }
    "refuse a right-hand side of the wrong length" in {
      gaussian(Random(1), 4, 3).svd.solve(DenseVector(1.0, 2.0)) must throwAn[IllegalArgumentException]
    }
  }
