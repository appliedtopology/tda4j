package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.DenseFixtures.*
import org.apache.commons.math3.linear.{
  ConjugateGradient as CommonsConjugateGradient,
  LUDecomposition,
  MatrixUtils,
  NonPositiveDefiniteOperatorException,
  RealLinearOperator,
  RealVector,
  SingularValueDecomposition
}
import org.apache.commons.math3.special.Erf as CommonsErf
import org.specs2.mutable

import scala.util.Random

/** The dense linear algebra against commons-math3, which the library used before it had its own. JVM only:
  * commons-math3 has no Scala.js build. The tolerances are the measured agreement, not hopes (see the worklog).
  */
class DenseLinearAlgebraOracleSpec extends mutable.Specification:

  private def commons(a: DenseMatrix) = MatrixUtils.createRealMatrix(a.toArray)
  private def commons(v: DenseVector): RealVector = MatrixUtils.createRealVector(v.toArray)
  private def ours(v: RealVector): DenseVector = DenseVector.fromArray(v.toArray)

  "singular values" should {
    "agree with commons-math3 to 1e-13 of the largest (5.7e-15 measured), on random matrices of every shape" in {
      val rng = Random(101)
      val worst = (for
        (m, n) <- shapes
        trial <- 0 until 20
        a = gaussian(rng, m, n)
        mine = a.svd.singularValues
        theirs = SingularValueDecomposition(commons(a)).getSingularValues
      yield mine.zip(theirs).map((x, y) => math.abs(x - y)).max / theirs.head).max
      worst must beLessThan(1e-13)
    }
    "agree on rank-deficient matrices, including the rank found by commons' own tolerance" in {
      val rng = Random(103)
      val problems = for
        (m, n, r) <- Seq((6, 6, 2), (8, 4, 3), (4, 8, 3), (10, 10, 1), (7, 5, 4), (20, 20, 7))
        trial <- 0 until 10
        a = lowRank(rng, m, n, r)
        theirs = SingularValueDecomposition(commons(a))
        mine = a.svd
        if mine.rank != theirs.getRank ||
          mine.singularValues
            .zip(theirs.getSingularValues)
            .map((x, y) => math.abs(x - y))
            .max > 1e-12 * theirs.getSingularValues.head
      yield s"${m}x$n rank $r trial $trial: ours ${mine.rank}, theirs ${theirs.getRank}"
      problems must beEmpty
    }
    "agree on the singular values the alpha code counts above epsilon: the rank of a point set at 1e-5" in {
      // `rankAtEpsilon`: near-degenerate point sets, jittered at 1e-7 and at 1e-3.
      val rng = Random(107)
      val problems = for
        (n, jitter) <- Seq((4, 1e-7), (4, 1e-3), (6, 1e-7), (6, 1e-3), (3, 0.0))
        trial <- 0 until 20
        flat = gaussian(rng, 8, 2)
        a = DenseMatrix.tabulate(8, n)((i, j) => (if j < 2 then flat(i, j) else 0.0) + jitter * rng.nextGaussian())
        mine = a.svd.singularValues.count(_.abs > 1e-5)
        theirs = SingularValueDecomposition(commons(a)).getSingularValues.count(_.abs > 1e-5)
        if mine != theirs
      yield s"n=$n jitter=$jitter trial $trial: ${a.svd.singularValues}"
      problems must beEmpty
    }
  }

  "singular vectors" should {
    "agree with commons-math3 up to sign for singular values that are separated" in {
      val rng = Random(109)
      val problems = for
        (m, n) <- Seq((3, 3), (5, 3), (3, 5), (8, 8), (12, 4), (4, 12))
        trial <- 0 until 20
        a = gaussian(rng, m, n)
        mine = a.svd
        theirs = SingularValueDecomposition(commons(a))
        s = theirs.getSingularValues
        i <- s.indices
        separated = (i == 0 || s(i - 1) - s(i) > 1e-3 * s(0)) && (i == s.length - 1 || s(i) - s(i + 1) > 1e-3 * s(0))
        if separated
        u = mine.u.column(i)
        v = mine.v.column(i)
        uTheirs = ours(theirs.getU.getColumnVector(i))
        vTheirs = ours(theirs.getV.getColumnVector(i))
        uError = math.min((u - uTheirs).norm, (u + uTheirs).norm)
        vError = math.min((v - vTheirs).norm, (v + vTheirs).norm)
        // the signs of u and v go together: u_i = A v_i / s_i
        consistent = (u.dot(uTheirs) > 0) == (v.dot(vTheirs) > 0)
        if uError > 1e-9 || vError > 1e-9 || !consistent
      yield s"${m}x$n trial $trial column $i: |du|=$uError |dv|=$vError consistent=$consistent"
      problems must beEmpty
    }
    "give the same null direction as commons-math3 for the facets that Hyperplane.from decomposes, up to sign" in {
      val rng = Random(113)
      val problems = for
        d <- 2 to 6
        trial <- 0 until 20
        points = Seq.fill(d)(DenseVector.tabulate(d)(_ => rng.nextGaussian()))
        differences = DenseMatrix.tabulate(d, d)((i, j) => points(i)(j) - points.head(j))
        mine = differences.transpose.svd
        theirs = SingularValueDecomposition(commons(differences).transpose())
        n1 = mine.u.column(d - 1)
        n2 = ours(theirs.getU.getColumnVector(d - 1))
        if math.min((n1 - n2).norm, (n1 + n2).norm) > 1e-9
      yield s"d=$d trial $trial"
      problems must beEmpty
    }
  }

  "the pseudo-inverse solution" should {
    "agree with commons-math3's solver to 1e-12 (5e-14 measured), for the least squares systems of Hypersphere.apply" in {
      val rng = Random(127)
      val problems = for
        (m, n) <- Seq((3, 2), (6, 3), (10, 4), (15, 5), (7, 7), (4, 8), (30, 6))
        trial <- 0 until 20
        a = gaussian(rng, m, n)
        b = DenseVector.tabulate(m)(_ => rng.nextGaussian())
        theirs = ours(SingularValueDecomposition(commons(a)).getSolver.solve(commons(b)))
        mine = a.svd.solve(b)
        error = (mine - theirs).norm / math.max(1.0, theirs.norm)
        if error > 1e-12
      yield s"${m}x$n trial $trial: $error"
      problems must beEmpty
    }
    "agree with commons-math3 on rank-deficient systems (the same cutoff)" in {
      val rng = Random(131)
      val problems = for
        (m, n, r) <- Seq((6, 6, 2), (8, 4, 3), (4, 8, 3), (10, 10, 1), (12, 7, 4))
        trial <- 0 until 20
        a = lowRank(rng, m, n, r)
        b = DenseVector.tabulate(m)(_ => rng.nextGaussian())
        theirs = ours(SingularValueDecomposition(commons(a)).getSolver.solve(commons(b)))
        mine = a.svd.solve(b)
        error = (mine - theirs).norm / math.max(1.0, theirs.norm)
        if error > 1e-9
      yield s"${m}x$n rank $r trial $trial: $error"
      problems must beEmpty
    }
  }

  "the LU decomposition" should {
    "solve bit for bit as commons-math3 does (the same arithmetic in the same order), and agree on singularity" in {
      val rng = Random(137)
      val problems = for
        n <- 1 to 10
        trial <- 0 until 30
        a = gaussian(rng, n, n)
        // some of the matrices are made singular, some nearly so
        singular = trial % 5 == 0 && n > 1
        withDependentRow =
          if singular then DenseMatrix.tabulate(n, n)((i, j) => if i == n - 1 then a(0, j) * 2.0 else a(i, j)) else a
        b = DenseVector.tabulate(n)(_ => rng.nextGaussian())
        threshold <- Seq(1e-14, 1e-11)
        theirs = LUDecomposition(commons(withDependentRow), threshold).getSolver
        mine = withDependentRow.lu(threshold)
        agreement = theirs.isNonSingular == mine.isNonSingular
        error =
          if agreement && mine.isNonSingular then
            (mine.solve(b) - ours(theirs.solve(commons(b)))).norm / math.max(1.0, b.norm)
          else 0.0
        if !agreement || error > 0.0
      yield s"n=$n trial $trial threshold $threshold: singular agree=$agreement error=$error"
      problems must beEmpty
    }
  }

  "conjugate gradient" should {

    /** commons-math3's operator for a matrix. */
    def operatorOf(a: DenseMatrix): RealLinearOperator =
      val matrix = commons(a)
      new RealLinearOperator:
        override def getRowDimension: Int = matrix.getRowDimension
        override def getColumnDimension: Int = matrix.getColumnDimension
        override def operate(x: RealVector): RealVector = matrix.operate(x)

    "give commons-math3's answer on symmetric positive definite systems, bit for bit (the same recurrence)" in {
      val rng = Random(139)
      val problems = for
        n <- Seq(2, 3, 5, 8, 13, 21, 40)
        trial <- 0 until 10
        g = gaussian(rng, n, n)
        gram = g.transpose * g
        a = DenseMatrix.tabulate(n, n)((i, j) => gram(i, j) + (if i == j then 1.0 else 0.0))
        b = DenseVector.tabulate(n)(_ => rng.nextGaussian())
        theirs = ours(CommonsConjugateGradient(1000, 1e-10, true).solve(operatorOf(a), commons(b)))
        mine = ConjugateGradient.solve(a * _, b, maxIterations = 1000, tolerance = 1e-10, check = true)
        error = (mine - theirs).norm / math.max(1.0, theirs.norm)
        if error > 0.0
      yield s"n=$n trial $trial: $error"
      problems must beEmpty
    }
    "fail where commons-math3 fails: an indefinite operator, with checking on" in {
      val a = DenseMatrix(Array(Array(2.0, 0.0), Array(0.0, -1.0)))
      CommonsConjugateGradient(1000, 1e-10, true).solve(operatorOf(a), commons(DenseVector(1.0, 1.0))) must
        throwA[NonPositiveDefiniteOperatorException]
      ConjugateGradient.solve(a * _, DenseVector(1.0, 1.0), check = true) must
        throwA[ConjugateGradient.NotSymmetricPositiveDefiniteException]
    }
  }

  "erf" should {
    "agree with commons-math3's to 1.2e-15 absolute over [-7, 7] (1.0e-15 measured: commons' erf is itself off by ~2e-16)" in {
      val rng = Random(149)
      val points = Seq.fill(200000)(rng.nextDouble() * 14.0 - 7.0) ++
        Seq(0.0, 1e-10, 0.46875, 0.4687500001, 4.0, 4.0000001, 6.0, 1.0, 2.0, 3.0).flatMap(x => Seq(x, -x))
      val worst = points.map(x => math.abs(Erf.erf(x) - CommonsErf.erf(x))).max
      worst must beLessThan(1.2e-15)
    }
    "agree with commons-math3's erfc to a relative 1e-12 in the tail (commons' erfc is itself off by 2e-14 there)" in {
      val rng = Random(151)
      val points = Seq.fill(100000)(rng.nextDouble() * 20.0 + 0.5)
      val worst = points.map(x => math.abs(Erf.erfc(x) / CommonsErf.erfc(x) - 1.0)).max
      worst must beLessThan(1e-12)
    }
  }
