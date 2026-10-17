package org.appliedtopology.tda4j

/** The conjugate gradient method for `A x = b` with `A` symmetric positive definite, given only as a function: the
  * harmonic smoothing of [[CircularCoordinates]] solves a graph Laplacian system this way, never forming the matrix.
  */
object ConjugateGradient:

  /** The residual did not get small enough within the iteration limit. */
  open class NotConvergedException(message: String) extends RuntimeException(message)

  /** Raised when checking is on and the operator is not symmetric, or has non-positive curvature. */
  open class NotSymmetricPositiveDefiniteException(message: String) extends IllegalArgumentException(message)

  /** The solution of `operator(x) = b` for a symmetric positive definite `operator`, stopping at the first iterate
    * whose residual `b - operator(x)` has norm at most `tolerance * |b|`.
    *
    * With `check` on, the operator is tested for symmetry once (two extra applications: `<u, A v>` against `<v, A u>`
    * for two fixed vectors) and every step for positive curvature (`<p, A p> > 0`); either failure raises
    * [[NotSymmetricPositiveDefiniteException]] saying which. The tests catch an operator that is plainly not symmetric
    * positive definite; they do not prove that it is. With `check` off a bad operator typically ends in
    * [[NotConvergedException]].
    *
    * @param operator
    *   `x => A x`, for the symmetric positive definite `A` of the system. It is given a fresh vector each time.
    * @param b
    *   the right-hand side.
    * @param maxIterations
    *   the most iterations to run; [[NotConvergedException]] if the tolerance is not reached by then. One iteration is
    *   one application of `operator`.
    * @param tolerance
    *   relative residual to reach, `|b - A x| <= tolerance * |b|`.
    * @param check
    *   whether to test the operator as described above.
    * @param initial
    *   where to start (default: the zero vector, which also saves the application of `operator` to it).
    */
  def solve(
    operator: DenseVector => DenseVector,
    b: DenseVector,
    maxIterations: Int = 1000,
    tolerance: Double = 1e-10,
    check: Boolean = true,
    initial: Optional[DenseVector] = Optional.empty
  ): DenseVector =
    require(maxIterations >= 0, s"maxIterations cannot be negative, got $maxIterations")
    require(tolerance >= 0.0, s"tolerance cannot be negative, got $tolerance")
    val n = b.size
    def applyOperator(entries: Array[Double]): Array[Double] =
      val result = operator(DenseVector.fromArray(entries))
      require(result.size == n, s"the operator returned a vector of length ${result.size}, expected $n")
      result.toArray
    def dot(x: Array[Double], y: Array[Double]): Double =
      var sum = 0.0
      var i = 0
      while i < n do
        sum += x(i) * y(i)
        i += 1
      sum

    val x = initial.toOption.fold(new Array[Double](n)) { guess =>
      require(guess.size == n, s"the initial guess has length ${guess.size}, expected $n")
      guess.toArray
    }
    val bEntries = b.toArray
    val r = initial.toOption.fold(bEntries.clone()) { _ =>
      val ax = applyOperator(x)
      Array.tabulate(n)(i => bEntries(i) - ax(i))
    }
    val rmax = tolerance * b.norm
    var rnorm = math.sqrt(dot(r, r))
    if rnorm <= rmax then DenseVector.wrap(x)
    else
      if check then requireSymmetric(applyOperator, bEntries, dot)
      val p = new Array[Double](n)
      var rhoPrevious = 0.0
      var iteration = 0
      var done = false
      while !done do
        if iteration >= maxIterations then
          throw NotConvergedException(
            s"conjugate gradient stopped after $iteration iterations with residual ${rnorm / b.norm} of |b|, above the " +
              s"tolerance $tolerance: raise maxIterations (now $maxIterations) or the tolerance, or check that the " +
              "operator is symmetric positive definite"
          )
        iteration += 1
        val rho = dot(r, r)
        if iteration == 1 then System.arraycopy(r, 0, p, 0, n)
        else
          val beta = rho / rhoPrevious
          var i = 0
          while i < n do
            p(i) = beta * p(i) + r(i)
            i += 1
        val q = applyOperator(p)
        val curvature = dot(p, q)
        if check && curvature <= 0.0 then
          throw NotSymmetricPositiveDefiniteException(
            s"the operator is not positive definite: <p, A p> = $curvature at iteration $iteration for the search " +
              "direction p. Conjugate gradient needs a symmetric positive definite operator"
          )
        val alpha = rho / curvature
        var i = 0
        while i < n do
          x(i) += alpha * p(i)
          r(i) -= alpha * q(i)
          i += 1
        rhoPrevious = rho
        rnorm = math.sqrt(dot(r, r))
        done = rnorm <= rmax
      DenseVector.wrap(x)

  /** `<u, A v>` against `<v, A u>`, for `u = b` and a fixed vector `v` that is not a multiple of it in general. */
  private def requireSymmetric(
    applyOperator: Array[Double] => Array[Double],
    b: Array[Double],
    dot: (Array[Double], Array[Double]) => Double
  ): Unit =
    val n = b.length
    val v = Array.tabulate(n)(i => 1.0 + ((i.toLong * 7919L) % 13L) / 13.0)
    val au = applyOperator(b)
    val av = applyOperator(v)
    val uav = dot(b, av)
    val vau = dot(v, au)
    val scale = math.sqrt(dot(b, b) * dot(av, av)) + math.sqrt(dot(v, v) * dot(au, au))
    if math.abs(uav - vau) > 1e-6 * scale then
      throw NotSymmetricPositiveDefiniteException(
        s"the operator is not symmetric: <u, A v> = $uav but <v, A u> = $vau for two test vectors. Conjugate " +
          "gradient needs a symmetric positive definite operator"
      )
