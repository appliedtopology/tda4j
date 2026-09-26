package org.appliedtopology.tda4j
package homology

/** Lenstra--Lenstra--Lovász lattice basis reduction on a Gram matrix, following Scoccola, Gakhar, Bush,
  * Schonsheck, Rask, Zhou, Perea, "Toroidal Coordinates: Decorrelating Circular Coordinates With Lattice
  * Reduction" (arXiv:2212.07201), Algorithm 4: given the Gram matrix `G` of `k` linearly independent generators
  * under some inner product (`CircularCoordinates.computeToroidal` uses the paper's own dSMV form -- the plain
  * unweighted sum-over-edges dot product of harmonic cocycles), factor `G = C C^T` (Cholesky), run LLL on the
  * rows of `C` (a concrete `R^k` stand-in for the abstract generators, chosen purely so LLL has actual vectors to
  * work with -- any such stand-in gives the same answer, since every quantity LLL consults is an inner product,
  * and `C`'s rows reproduce `G`'s inner products exactly by construction), and return the resulting unimodular
  * integer change of basis.
  *
  * This is the fix for the ambiguity Edelsbrunner raised in the original circular-coordinates Q&A: given `k`
  * independent generators of a cohomology class's rank-`k` free abelian group, ANY unimodular integer
  * combination of them is an equally valid set of generators (same subgroup, different basis) -- so "the"
  * generators a cohomology computation hands back are arbitrary, not canonical. LLL picks the combination that's
  * shortest and most nearly orthogonal under the given inner product, a principled, deterministic criterion
  * instead of whatever a reduction algorithm's pivot order happened to produce.
  *
  * '''Deliberately not a port of `scikit-tda/DREiMac`'s own `toroidalcoords.py`''' (the reference implementation
  * of the same paper): its own `_gram_schmidt` projects each new vector onto the ORIGINAL input basis vectors
  * instead of the already-orthogonalized ones -- a real bug, invisible for exactly `k=2` (this library's own
  * headline "two circles -> one torus" case, where there is only ever one projection step and it trivially
  * agrees either way) but corrupting the orthogonalization for `k>=3`, confirmed by direct numerical repro, not
  * just by reading the code -- see `.claude/BUGS-IN-REFERENCES.md`. This is a textbook implementation instead
  * (standard Gram-Schmidt, projecting onto the running orthogonalized vectors), cross-checked against Wikipedia's
  * own independently-stated algorithm and worked example (`LatticeReductionSpec`).
  */
object LatticeReduction:

  /** @param basisChange
    *   `U`, `k x k`, integer, unimodular (`|det U| = 1`, checked internally -- a failure here indicates a bug in
    *   `reduce` itself, not a caller error). Column `c` gives the coefficients of the `c`-th REDUCED generator as
    *   a combination of the ORIGINAL generators: `reduced_c = sum_r U(r)(c) * original_r`. Apply the SAME `U` to
    *   any other linear stand-in for the original generators -- the harmonic cocycles themselves, or the
    *   circular coordinates they produce -- to get the reduced version of that thing (both are linear in the
    *   choice of generator).
    * @param reducedGram
    *   `U^T G U`: the reduced generators' own Gram matrix. Diagonal entries are the reduced generators' own
    *   squared norms, off-diagonal their pairwise correlation under the inner product `G` was built from --
    *   smaller/more-diagonal than `G` is the whole point of reduction, and is what a caller checks to confirm it
    *   actually helped on real data.
    */
  case class Result(basisChange: Array[Array[Int]], reducedGram: Array[Array[Double]])

  /** @param gram
    *   `k x k`, symmetric (checked up to a loose relative tolerance, then symmetrized by averaging -- absorbs
    *   ordinary floating-point noise from however a caller accumulated it, e.g. summing many small terms in two
    *   different orders for `(i,j)` vs `(j,i)`) and positive DEFINITE. Positive-semi-definite (linearly DEPENDENT
    *   generators -- e.g. the same cohomology class selected twice) is a caller error, reported as an
    *   [[IllegalArgumentException]] naming the failing pivot rather than a cryptic NaN from a failed Cholesky
    *   step.
    * @param delta
    *   the Lovász condition parameter, required in `(1/4, 1]` for LLL's own termination guarantee. `3/4` is the
    *   universal textbook default (also DREiMac's own default; the paper itself does not pin a specific value)
    *   and this library's own default; smaller trades reduction quality for speed, `1.0` is the strongest
    *   (slowest) guarantee.
    */
  def reduce(gram: Array[Array[Double]], delta: Double = 0.75): Result =
    val n = gram.length
    require(n > 0, "gram matrix must be non-empty")
    require(gram.forall(_.length == n), s"gram matrix must be square, got ${gram.length} rows of lengths ${gram.map(_.length).mkString(",")}")
    require(delta > 0.25 && delta <= 1.0, s"delta must be in (1/4, 1], got $delta")
    val sym = symmetrize(gram)

    val basis: Array[Array[Double]] = cholesky(sym) // row i = a concrete R^n stand-in vector for generator i
    val (_, change) = lll(basis, delta)

    val basisChange: Array[Array[Int]] = Array.tabulate(n, n)((i, k) => math.round(change(i)(k)).toInt)
    val det = determinant(basisChange.map(_.map(_.toDouble)))
    require(
      math.abs(math.abs(det) - 1.0) < 1e-6,
      s"internal error: LLL basis change matrix is not unimodular (det=$det) -- this is a bug in " +
        "LatticeReduction.reduce, not a caller error"
    )

    Result(basisChange, congruence(basisChange, sym))

  /** Whether `gram` is already LLL-reduced (size-reduced and satisfying the Lovász condition) at the given
    * `delta`, i.e. whether calling [[reduce]] on it would leave it alone (`basisChange` the identity, up to
    * `reduce`'s own internal tie-breaking). Recomputes the same Cholesky-factor/Gram-Schmidt machinery `reduce`
    * itself uses, purely as a read-only diagnostic -- a caller (or a test) uses this to confirm a Gram matrix
    * that came from `reduce` or from anywhere else is genuinely reduced, not merely to re-run `reduce` and check
    * the identity showed up (which would only prove THIS implementation's own idea of "reduced", not the
    * textbook definition independently).
    */
  def isReduced(gram: Array[Array[Double]], delta: Double = 0.75, tol: Double = 1e-6): Boolean =
    val n = gram.length
    require(n > 0, "gram matrix must be non-empty")
    if n == 1 then true
    else
      val basis = cholesky(symmetrize(gram))
      val star = gramSchmidt(basis)
      def mu(i: Int, j: Int): Double = dot(basis(i), star(j)) / dot(star(j), star(j))
      val sizeReduced = (1 until n).forall(i => (0 until i).forall(j => math.abs(mu(i, j)) <= 0.5 + tol))
      val lovasz = (1 until n).forall { k =>
        val mkk1 = mu(k, k - 1)
        dot(star(k), star(k)) >= (delta - mkk1 * mkk1) * dot(star(k - 1), star(k - 1)) - tol * (1.0 + dot(
          star(k - 1),
          star(k - 1)
        ))
      }
      sizeReduced && lovasz

  private def symmetrize(gram: Array[Array[Double]]): Array[Array[Double]] =
    val n = gram.length
    Array.tabulate(n, n) { (i, j) =>
      require(
        math.abs(gram(i)(j) - gram(j)(i)) <= 1e-6 * (1.0 + math.abs(gram(i)(j)) + math.abs(gram(j)(i))),
        s"gram matrix must be symmetric, entries ($i,$j)=${gram(i)(j)} vs ($j,$i)=${gram(j)(i)} disagree by more " +
          "than floating-point noise"
      )
      (gram(i)(j) + gram(j)(i)) / 2.0
    }

  /** Cholesky-Banachiewicz, row by row -- hand-rolled (mirroring `alpha.CholeskyWorkspace`'s own precedent of not
    * reaching for `commons-math3`'s `CholeskyDecomposition` here) so a linearly-dependent input fails with an
    * actionable message pinned to the failing pivot, not whatever `commons-math3`'s own symmetry/PD thresholds
    * happen to do with a matrix assembled from many small floating-point-summed terms.
    */
  private def cholesky(gram: Array[Array[Double]]): Array[Array[Double]] =
    val n = gram.length
    val L = Array.ofDim[Double](n, n)
    for i <- 0 until n do
      for j <- 0 to i do
        var sum = gram(i)(j)
        for k <- 0 until j do sum -= L(i)(k) * L(j)(k)
        if i == j then
          require(
            sum > 1e-9,
            s"gram matrix is not positive definite (the chosen generators are linearly dependent) at row $i " +
              s"(pivot=$sum) -- choose linearly independent cohomology classes"
          )
          L(i)(j) = math.sqrt(sum)
        else L(i)(j) = sum / L(j)(j)
    L

  /** Standard (textbook) Gram-Schmidt, without normalization: `star(0) = basis(0)`, `star(i) = basis(i) -
    * sum_{j<i} mu(i,j) * star(j)` -- projecting onto the running ORTHOGONALIZED vectors `star(j)`, not the
    * original `basis(j)` (the bug documented on the class -- see `.claude/BUGS-IN-REFERENCES.md`).
    */
  private def gramSchmidt(basis: Array[Array[Double]]): Array[Array[Double]] =
    val n = basis.length
    val star = Array.ofDim[Double](n, if n > 0 then basis(0).length else 0)
    if n > 0 then
      star(0) = basis(0).clone()
      for i <- 1 until n do
        val vi = basis(i).clone()
        for j <- 0 until i do
          val c = dot(basis(i), star(j)) / dot(star(j), star(j))
          for t <- vi.indices do vi(t) -= c * star(j)(t)
        star(i) = vi
    star

  private def dot(a: Array[Double], b: Array[Double]): Double =
    var s = 0.0
    for t <- a.indices do s += a(t) * b(t)
    s

  /** The LLL main loop (Wikipedia's own statement of the algorithm, and DREiMac's `_lll` main loop, agree on this
    * part -- only `_gram_schmidt` itself differs here). `basisIn`'s rows are the input vectors; returns the
    * reduced rows alongside `change`, tracked by mirroring every elementary column operation applied to the
    * working basis onto an initially-identity matrix -- `change(r)(c)` always equals the coefficient of the
    * ORIGINAL row `r` in the CURRENT row `c`, an invariant maintained by construction regardless of whether the
    * guiding Gram-Schmidt vectors are exact, since it only records which operations were actually performed.
    */
  private def lll(basisIn: Array[Array[Double]], delta: Double): (Array[Array[Double]], Array[Array[Double]]) =
    val n = basisIn.length
    val basis = basisIn.map(_.clone())
    val change = Array.tabulate(n, n)((i, j) => if i == j then 1.0 else 0.0)
    var star = gramSchmidt(basis)

    def mu(i: Int, j: Int): Double = dot(basis(i), star(j)) / dot(star(j), star(j))

    var k = 1
    while k < n do
      for j <- (k - 1) to 0 by -1 do
        val m = mu(k, j)
        if math.abs(m) > 0.5 then
          val r = math.round(m).toDouble
          for t <- basis(k).indices do basis(k)(t) -= r * basis(j)(t)
          for t <- change.indices do change(t)(k) -= r * change(t)(j)
          star = gramSchmidt(basis)

      val mkk1 = mu(k, k - 1)
      if dot(star(k), star(k)) >= (delta - mkk1 * mkk1) * dot(star(k - 1), star(k - 1)) then k += 1
      else
        val tmpB = basis(k); basis(k) = basis(k - 1); basis(k - 1) = tmpB
        for t <- change.indices do
          val tmpC = change(t)(k); change(t)(k) = change(t)(k - 1); change(t)(k - 1) = tmpC
        star = gramSchmidt(basis)
        k = math.max(k - 1, 1)

    (basis, change)

  private def determinant(m: Array[Array[Double]]): Double =
    val n = m.length
    if n == 1 then m(0)(0)
    else
      var det = 0.0
      var sign = 1.0
      for col <- 0 until n do
        val minor = Array.tabulate(n - 1, n - 1)((i, j) => m(i + 1)(if j < col then j else j + 1))
        det += sign * m(0)(col) * determinant(minor)
        sign = -sign
      det

  /** `U^T G U` -- the Gram matrix of the basis change `U` applied to whatever generators `G` is the Gram matrix
    * of.
    */
  private def congruence(u: Array[Array[Int]], g: Array[Array[Double]]): Array[Array[Double]] =
    val n = u.length
    Array.tabulate(n, n) { (a, b) =>
      var s = 0.0
      for i <- 0 until n; j <- 0 until n do s += u(i)(a).toDouble * g(i)(j) * u(j)(b).toDouble
      s
    }
