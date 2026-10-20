package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.DenseFixtures.*
import org.specs2.mutable

import scala.util.Random

class ConjugateGradientSpec extends mutable.Specification:

  /** The operator of a matrix, counting how often it was applied. */
  private class Counting(a: DenseMatrix) extends (DenseVector => DenseVector):
    var applications = 0
    def apply(x: DenseVector): DenseVector =
      applications += 1
      a * x

  /** The tridiagonal second-difference matrix: symmetric positive definite, eigenvalues in (0, 4). */
  private def laplacian(n: Int): DenseMatrix =
    DenseMatrix.tabulate(n, n)((i, j) => if i == j then 2.0 else if math.abs(i - j) == 1 then -1.0 else 0.0)

  private def spd(rng: Random, n: Int): DenseMatrix =
    val b = gaussian(rng, n, n)
    val gram = b.transpose * b
    DenseMatrix.tabulate(n, n)((i, j) => gram(i, j) + (if i == j then 1.0 else 0.0))

  "conjugate gradient" should {
    "solve a small symmetric positive definite system to the default tolerance" in {
      val a = DenseMatrix(Array(Array(4.0, 1.0, 0.0), Array(1.0, 3.0, 1.0), Array(0.0, 1.0, 2.0)))
      val x = DenseVector(1.0, 2.0, 3.0)
      val solved = ConjugateGradient.solve(a * _, a * x)
      (solved - x).norm must beLessThan(1e-9)
    }
    "stop when the residual is at most tolerance * |b|: the residual of its answer is" in {
      val a = laplacian(40)
      val b = DenseVector.tabulate(40)(i => math.sin(i.toDouble))
      val residuals = for tolerance <- Seq(1e-4, 1e-8, 1e-12) yield
        val x = ConjugateGradient.solve(a * _, b, tolerance = tolerance, maxIterations = 200)
        (tolerance, (a * x - b).norm / b.norm)
      // Reached: within the tolerance (the true residual differs from the recursive one by rounding only).
      residuals.forall((tolerance, relative) => relative <= tolerance * (1 + 1e-6) + 1e-13) must beTrue
    }
    "not over-solve: a loose tolerance stops early, near the tolerance, not at machine precision" in {
      // A diagonal operator with eigenvalues spread over [1, 100]: the residual falls steadily, about 0.8 per step.
      val n = 200
      val diagonal = DenseVector.tabulate(n)(i => 1.0 + 99.0 * i / (n - 1))
      val b = DenseVector.tabulate(n)(_ => 1.0)
      val x = ConjugateGradient.solve(v => DenseVector.tabulate(n)(i => diagonal(i) * v(i)), b, tolerance = 1e-4)
      val relative = DenseVector.tabulate(n)(i => diagonal(i) * x(i) - b(i)).norm / b.norm
      relative must beLessThanOrEqualTo(1e-4 * (1 + 1e-6))
      relative must beGreaterThan(1e-6)
    }
    "need no more than n steps (plus rounding) on an n x n system with distinct eigenvalues" in {
      val a = DenseMatrix(Array(Array(4.0, 1.0, 0.0), Array(1.0, 3.0, 1.0), Array(0.0, 1.0, 2.0)))
      val counting = Counting(a)
      ConjugateGradient.solve(counting, a * DenseVector(1.0, 2.0, 3.0), check = false)
      // one application per step (the zero start needs none for the first residual)
      counting.applications must beLessThanOrEqualTo(3 + 1)
    }
    "solve the identity in one step" in {
      val counting = Counting(DenseMatrix.identity(5))
      val b = DenseVector(1.0, 2.0, 3.0, 4.0, 5.0)
      ConjugateGradient.solve(counting, b, maxIterations = 1, check = false) must beEqualTo(b)
    }
    "agree with a direct solve on random symmetric positive definite systems" in {
      val rng = Random(23)
      val problems = for
        n <- Seq(2, 3, 5, 8, 13, 21)
        trial <- 0 until 5
        a = spd(rng, n)
        b = DenseVector.tabulate(n)(_ => rng.nextGaussian())
        direct = a.lu().solve(b)
        iterative = ConjugateGradient.solve(a * _, b, maxIterations = 500, tolerance = 1e-12)
        error = (iterative - direct).norm / math.max(1.0, direct.norm)
        if error > 1e-8
      yield s"n=$n trial=$trial error=$error"
      problems must beEmpty
    }
    "start from the initial guess when given one, and stop at once when it already solves the system" in {
      val a = laplacian(10)
      val x = DenseVector.tabulate(10)(i => i * 0.5)
      val counting = Counting(a)
      val solved = ConjugateGradient.solve(counting, a * x, initial = x)
      solved must beEqualTo(x)
      counting.applications must beEqualTo(1)
      // A bad guess still converges.
      val farther = ConjugateGradient.solve(a * _, a * x, initial = DenseVector.tabulate(10)(_ => 100.0))
      (farther - x).norm must beLessThan(1e-7)
    }
    "return the zero vector for the zero right-hand side without iterating" in {
      val counting = Counting(laplacian(6))
      ConjugateGradient.solve(counting, DenseVector.zeros(6)) must beEqualTo(DenseVector.zeros(6))
      counting.applications must beLessThanOrEqualTo(1)
    }
    "work on an empty system" in {
      ConjugateGradient.solve(x => x, DenseVector()) must beEqualTo(DenseVector())
    }
  }

  "the failure modes" should {
    "throw when the tolerance is not reached within maxIterations, naming both and what to do" in {
      val a = laplacian(60)
      val b = DenseVector.tabulate(60)(i => 1.0 + i)
      ConjugateGradient.solve(a * _, b, maxIterations = 5) must
        throwA[ConjugateGradient.NotConvergedException].like { case e =>
          (e.getMessage.contains("5") && e.getMessage.contains("maxIterations")) must beTrue
        }
    }
    "allow exactly the iterations it is given: one is enough for the identity, none is not" in {
      val b = DenseVector(1.0, 2.0)
      ConjugateGradient.solve(x => x, b, maxIterations = 1, check = false) must beEqualTo(b)
      ConjugateGradient
        .solve(x => x, b, maxIterations = 0, check = false) must throwA[ConjugateGradient.NotConvergedException]
    }
    "with checking on, refuse an indefinite operator when it sees the negative curvature" in {
      // diag(2, -1): p^T A p is positive at first and negative at the second step.
      val a = DenseMatrix(Array(Array(2.0, 0.0), Array(0.0, -1.0)))
      ConjugateGradient.solve(a * _, DenseVector(1.0, 1.0), check = true) must
        throwA[ConjugateGradient.NotSymmetricPositiveDefiniteException]
    }
    "with checking on, refuse a negative definite operator at the first step" in {
      val a = DenseMatrix(Array(Array(-2.0, 0.0), Array(0.0, -3.0)))
      ConjugateGradient.solve(a * _, DenseVector(1.0, 1.0), check = true) must
        throwA[ConjugateGradient.NotSymmetricPositiveDefiniteException]
    }
    "with checking on, refuse an operator that is not symmetric, saying so" in {
      val a = DenseMatrix(Array(Array(1.0, 2.0), Array(0.0, 1.0)))
      ConjugateGradient.solve(a * _, DenseVector(1.0, 1.0), check = true) must
        throwA[ConjugateGradient.NotSymmetricPositiveDefiniteException].like { case e =>
          e.getMessage.contains("symmetric") must beTrue
        }
    }
    "with checking on, accept a symmetric operator whose rounding makes it symmetric only to ~1e-15" in {
      val rng = Random(29)
      val a = spd(rng, 12)
      val noisy = DenseMatrix.tabulate(12, 12)((i, j) => a(i, j) * (1.0 + (if i > j then 1e-15 else 0.0)))
      val b = DenseVector.tabulate(12)(_ => rng.nextGaussian())
      val x = ConjugateGradient.solve(noisy * _, b, check = true, maxIterations = 500)
      (noisy * x - b).norm must beLessThan(1e-8)
    }
    "with checking off, leave the operator unexamined" in {
      val a = DenseMatrix(Array(Array(1.0, 2.0), Array(0.0, 1.0)))
      ConjugateGradient.solve(a * _, DenseVector(1.0, 1.0), check = false, maxIterations = 3) must
        throwA[ConjugateGradient.NotConvergedException]
    }
    "refuse an operator that returns a vector of the wrong length, and a guess of the wrong length" in {
      ConjugateGradient.solve(_ => DenseVector(1.0), DenseVector(1.0, 2.0)) must throwAn[IllegalArgumentException]
      ConjugateGradient.solve(x => x, DenseVector(1.0, 2.0), initial = DenseVector(1.0)) must
        throwAn[IllegalArgumentException]
    }
    "refuse a negative tolerance and a negative iteration limit" in {
      ConjugateGradient.solve(x => x, DenseVector(1.0), tolerance = -1.0) must throwAn[IllegalArgumentException]
      ConjugateGradient.solve(x => x, DenseVector(1.0), maxIterations = -1) must throwAn[IllegalArgumentException]
    }
  }
