package org.appliedtopology.tda4j

import scala.util.Random

/** Seeded matrices and the checks the dense linear algebra specs share (the shared specs and the JVM-only oracle specs
  * against commons-math3 both use them). Seeded, so a failure reproduces on both platforms.
  */
object DenseFixtures:

  /** An `m x n` matrix of independent standard normal entries. */
  def gaussian(rng: Random, m: Int, n: Int): DenseMatrix =
    DenseMatrix.tabulate(m, n)((_, _) => rng.nextGaussian())

  /** An `m x n` matrix of rank `r` (almost surely): a product of two Gaussian factors. */
  def lowRank(rng: Random, m: Int, n: Int, r: Int): DenseMatrix =
    gaussian(rng, m, r) * gaussian(rng, r, n)

  def diagonal(entries: Seq[Double]): DenseMatrix =
    DenseMatrix.tabulate(entries.length, entries.length)((i, j) => if i == j then entries(i) else 0.0)

  /** The shapes the SVD specs sweep: every small shape (tall, wide, square, single row and column), then bigger ones.
    */
  val shapes: Seq[(Int, Int)] =
    (for m <- Seq(1, 2, 3, 4, 5, 7); n <- Seq(1, 2, 3, 4, 5, 7) yield (m, n)) ++
      Seq((20, 5), (5, 20), (30, 30), (50, 3), (3, 50), (12, 12))

  def maxAbs(a: DenseMatrix): Double =
    var best = 0.0
    for i <- 0 until a.rows; j <- 0 until a.cols do best = math.max(best, math.abs(a(i, j)))
    best

  /** `max |Q^T Q - I|`: how far the columns of `q` are from orthonormal. */
  def orthonormalityError(q: DenseMatrix): Double =
    maxAbs(q.transpose * q - DenseMatrix.identity(q.cols))

  /** `U diag(s) V^T` of a decomposition. */
  def reconstruct(svd: DenseMatrix.SingularValueDecomposition): DenseMatrix =
    svd.u * diagonal(svd.singularValues) * svd.v.transpose

  /** What is wrong with `a.svd` as a compact SVD, in words; empty if nothing. */
  def svdProblems(a: DenseMatrix, tolerance: Double = 1e-12): Seq[String] =
    val svd = a.svd
    val m = a.rows
    val n = a.cols
    val p = math.min(m, n)
    val scale = math.max(1.0, a.frobeniusNorm)
    val shape =
      if svd.u.rows != m || svd.u.cols != p then Seq(s"U is ${svd.u.rows}x${svd.u.cols}, expected ${m}x$p")
      else if svd.v.rows != n || svd.v.cols != p then Seq(s"V is ${svd.v.rows}x${svd.v.cols}, expected ${n}x$p")
      else if svd.singularValues.length != p then Seq(s"${svd.singularValues.length} singular values, expected $p")
      else Nil
    if shape.nonEmpty then shape
    else
      val s = svd.singularValues
      val order =
        if s.exists(_ < 0) then Seq(s"a negative singular value in $s") else Nil
      val descending =
        if s.zip(s.drop(1)).exists((x, y) => x < y) then Seq(s"singular values not descending: $s") else Nil
      val uError = orthonormalityError(svd.u)
      val vError = orthonormalityError(svd.v)
      val reconstructionError = (reconstruct(svd) - a).frobeniusNorm / scale
      order ++ descending ++
        Option.when(uError > tolerance)(s"U^T U - I is $uError").toSeq ++
        Option.when(vError > tolerance)(s"V^T V - I is $vError").toSeq ++
        Option.when(reconstructionError > tolerance)(s"|A - U S V^T| / |A| is $reconstructionError").toSeq
