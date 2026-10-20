package org.appliedtopology.tda4j

import cats.Show

/** A matrix of `Double`s, stored by rows, with the two decompositions the geometric code needs: [[svd]] (singular
  * values, null directions, least squares solutions) and [[lu]] (square solves). Small and dense on purpose: it serves
  * the matrices of a handful of points in a few dimensions, and the matrices of finitely presented persistence modules;
  * for the large sparse reductions of persistent homology see [[Chain]].
  *
  * Like [[DenseVector]] a matrix is a value: it never changes, and equal entries mean equal matrices.
  */
// `final` because it is a value type: a subclass could add state that `equals` and `hashCode` below do not see.
final class DenseMatrix private (val rows: Int, val cols: Int, private val data: Array[Double]):

  /** Entry `(i, j)`, counting from 0. */
  def apply(i: Int, j: Int): Double =
    if i < 0 || i >= rows || j < 0 || j >= cols then
      throw IndexOutOfBoundsException(s"entry ($i, $j) of a matrix with $rows rows and $cols columns")
    data(i * cols + j)

  /** Row `i` as a vector. */
  def row(i: Int): DenseVector =
    if i < 0 || i >= rows then throw IndexOutOfBoundsException(s"row $i of a matrix with $rows rows")
    DenseVector.wrap(java.util.Arrays.copyOfRange(data, i * cols, (i + 1) * cols))

  /** Column `j` as a vector. */
  def column(j: Int): DenseVector =
    if j < 0 || j >= cols then throw IndexOutOfBoundsException(s"column $j of a matrix with $cols columns")
    DenseVector.wrap(Array.tabulate(rows)(i => data(i * cols + j)))

  /** The rows as a new array of arrays (changing it does not change the matrix). */
  def toArray: Array[Array[Double]] =
    Array.tabulate(rows)(i => java.util.Arrays.copyOfRange(data, i * cols, (i + 1) * cols))

  def transpose: DenseMatrix =
    val out = new Array[Double](data.length)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do
        out(j * rows + i) = data(i * cols + j)
        j += 1
      i += 1
    new DenseMatrix(cols, rows, out)

  /** The matrix product; the columns of this must match the rows of `that`. */
  def *(that: DenseMatrix): DenseMatrix =
    require(cols == that.rows, s"cannot multiply a matrix with $cols columns by one with ${that.rows} rows")
    val out = new Array[Double](rows * that.cols)
    var i = 0
    while i < rows do
      var k = 0
      while k < cols do
        val a = data(i * cols + k)
        var j = 0
        while j < that.cols do
          out(i * that.cols + j) += a * that.data(k * that.cols + j)
          j += 1
        k += 1
      i += 1
    new DenseMatrix(rows, that.cols, out)

  /** The matrix-vector product; the columns of this must match the length of `v`. */
  def *(v: DenseVector): DenseVector =
    require(cols == v.size, s"cannot multiply a matrix with $cols columns by a vector of length ${v.size}")
    DenseVector.wrap(Array.tabulate(rows) { i =>
      var sum = 0.0
      var j = 0
      while j < cols do
        sum += data(i * cols + j) * v(j)
        j += 1
      sum
    })

  def -(that: DenseMatrix): DenseMatrix =
    require(
      rows == that.rows && cols == that.cols,
      s"cannot subtract a ${that.rows}x${that.cols} matrix from a ${rows}x$cols one"
    )
    new DenseMatrix(rows, cols, Array.tabulate(data.length)(i => data(i) - that.data(i)))

  /** The square root of the sum of the squares of the entries. */
  def frobeniusNorm: Double =
    var sum = 0.0
    var i = 0
    while i < data.length do
      sum += data(i) * data(i)
      i += 1
    math.sqrt(sum)

  /** The singular value decomposition `this = U diag(S) V^T` in its compact form (see
    * [[DenseMatrix.SingularValueDecomposition]]).
    */
  def svd: DenseMatrix.SingularValueDecomposition = DenseMatrix.SingularValueDecomposition.of(this)

  /** The LU decomposition with partial pivoting of a square matrix. The matrix counts as singular when a pivot has
    * absolute value below `singularityThreshold` (or is exactly zero).
    */
  def lu(singularityThreshold: Double = DenseMatrix.DefaultSingularityThreshold): DenseMatrix.LuDecomposition =
    DenseMatrix.LuDecomposition.of(this, singularityThreshold)

  override def equals(other: Any): Boolean = other match
    case that: DenseMatrix =>
      (this eq that) || (rows == that.rows && cols == that.cols && {
        var same = true
        var i = 0
        while same && i < data.length do
          same = data(i) == that.data(i) || (data(i).isNaN && that.data(i).isNaN)
          i += 1
        same
      })
    case _ => false

  // Consistent with `equals` (see `DenseVector.hashCode`).
  override def hashCode: Int =
    var h = 31 * rows + cols
    var i = 0
    while i < data.length do
      h = 31 * h + java.lang.Double.hashCode(if data(i).isNaN then Double.NaN else data(i) + 0.0)
      i += 1
    h

  override def toString: String =
    (0 until rows).map(i => row(i).toArray.mkString("[", ", ", "]")).mkString("DenseMatrix(", ", ", ")")

  // The row-major entries, for the library's own algorithms; a copy would cost the allocation they exist to avoid.
  private[tda4j] def unsafeData: Array[Double] = data

object DenseMatrix:

  /** The pivot size below which [[DenseMatrix.lu]] calls a matrix singular, unless another is given. */
  val DefaultSingularityThreshold: Double = 1e-11

  /** The matrix with these rows, which must all have the same length (the arrays are copied). */
  def apply(rows: Array[Array[Double]]): DenseMatrix =
    val cols = if rows.isEmpty then 0 else rows.head.length
    require(rows.forall(_.length == cols), "the rows of a matrix must all have the same length")
    val data = new Array[Double](rows.length * cols)
    var i = 0
    while i < rows.length do
      System.arraycopy(rows(i), 0, data, i * cols, cols)
      i += 1
    new DenseMatrix(rows.length, cols, data)

  /** The matrix of zeros with this many rows and columns. */
  def zeros(rows: Int, cols: Int): DenseMatrix =
    require(rows >= 0 && cols >= 0, s"a matrix cannot have $rows rows and $cols columns")
    new DenseMatrix(rows, cols, new Array[Double](rows * cols))

  /** The identity matrix of order `n`. */
  def identity(n: Int): DenseMatrix = tabulate(n, n)((i, j) => if i == j then 1.0 else 0.0)

  /** The matrix whose entry `(i, j)` is `f(i, j)`. */
  def tabulate(rows: Int, cols: Int)(f: (Int, Int) => Double): DenseMatrix =
    require(rows >= 0 && cols >= 0, s"a matrix cannot have $rows rows and $cols columns")
    val data = new Array[Double](rows * cols)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do
        data(i * cols + j) = f(i, j)
        j += 1
      i += 1
    new DenseMatrix(rows, cols, data)

  // private[tda4j]: takes the array over without copying (invariant-bearing state, openness rule 1).
  private[tda4j] def wrap(rows: Int, cols: Int, data: Array[Double]): DenseMatrix = new DenseMatrix(rows, cols, data)

  given showDenseMatrix: Show[DenseMatrix] = Show.fromToString

  /** The factorization `P A = L U` of a square matrix by Gaussian elimination with partial pivoting (the pivot of each
    * column is its entry of largest absolute value on or below the diagonal).
    *
    * @param isNonSingular
    *   whether every pivot had absolute value at least the threshold given to [[DenseMatrix.lu]] (and was not zero).
    */
  // The constructor is private[tda4j]: the factors are filled in by `LuDecomposition.of` (openness rule 1).
  class LuDecomposition private[tda4j] (
    private val n: Int,
    private val factors: Array[Double],
    private val permutation: Array[Int],
    val isNonSingular: Boolean
  ):
    /** The solution `x` of `A x = b`. Throws if the matrix is singular: check [[isNonSingular]] first, or use
      * [[DenseMatrix.svd]]`.solve` for the least squares solution of a singular system.
      */
    def solve(b: DenseVector): DenseVector =
      require(
        isNonSingular,
        "the matrix is singular (a pivot is below the singularity threshold): check isNonSingular first, or use " +
          "svd.solve for a least squares solution"
      )
      require(b.size == n, s"the right-hand side has ${b.size} entries, the matrix has $n rows")
      val x = Array.tabulate(n)(i => b(permutation(i)))
      // L y = P b, L unit lower triangular, a column at a time.
      var col = 0
      while col < n do
        val xCol = x(col)
        var i = col + 1
        while i < n do
          x(i) -= xCol * factors(i * n + col)
          i += 1
        col += 1
      // U x = y, back to front, a column at a time.
      col = n - 1
      while col >= 0 do
        x(col) /= factors(col * n + col)
        val xCol = x(col)
        var i = 0
        while i < col do
          x(i) -= xCol * factors(i * n + col)
          i += 1
        col -= 1
      DenseVector.wrap(x)

  object LuDecomposition:
    private[tda4j] def of(a: DenseMatrix, singularityThreshold: Double): LuDecomposition =
      require(a.rows == a.cols, s"the LU decomposition needs a square matrix, not ${a.rows}x${a.cols}")
      val n = a.rows
      val lu = a.unsafeData.clone()
      val permutation = Array.tabulate(n)(i => i)
      var singular = false
      var col = 0
      // Column by column (Crout's arrangement): first the entries of U above the diagonal, then the candidates for the
      // pivot, then the multipliers of L.
      while col < n && !singular do
        var row = 0
        while row < col do
          var sum = lu(row * n + col)
          var i = 0
          while i < row do
            sum -= lu(row * n + i) * lu(i * n + col)
            i += 1
          lu(row * n + col) = sum
          row += 1
        var best = col
        var largest = Double.NegativeInfinity
        row = col
        while row < n do
          var sum = lu(row * n + col)
          var i = 0
          while i < col do
            sum -= lu(row * n + i) * lu(i * n + col)
            i += 1
          lu(row * n + col) = sum
          if math.abs(sum) > largest then
            largest = math.abs(sum)
            best = row
          row += 1
        val pivot = lu(best * n + col)
        if math.abs(pivot) < singularityThreshold || pivot == 0.0 then singular = true
        else
          if best != col then
            var j = 0
            while j < n do
              val t = lu(best * n + j)
              lu(best * n + j) = lu(col * n + j)
              lu(col * n + j) = t
              j += 1
            val t = permutation(best)
            permutation(best) = permutation(col)
            permutation(col) = t
          val diagonal = lu(col * n + col)
          row = col + 1
          while row < n do
            lu(row * n + col) /= diagonal
            row += 1
        col += 1
      new LuDecomposition(n, lu, permutation, !singular)

  /** The compact singular value decomposition `A = U diag(S) V^T` of an `m x n` matrix, with `p = min(m, n)`:
    * `singularValues` are the `p` singular values in descending order, `u` is `m x p` and `v` is `n x p`, and both have
    * orthonormal columns. For a singular value of 0 (or one lost in the rounding) the column of `u` or `v` is a unit
    * vector orthogonal to the others, chosen among the standard basis directions.
    *
    * Computed by one-sided Jacobi rotations (Hestenes), which keep small singular values accurate relative to their own
    * size when the matrix is well scaled by columns, and need no tuning: they stop when every pair of columns is
    * orthogonal to rounding. A wide matrix is decomposed through its transpose, which exchanges `u` and `v`.
    */
  // The constructor is private[tda4j]: the decomposition is built by `SingularValueDecomposition.of` (openness rule 1).
  class SingularValueDecomposition private[tda4j] (
    val u: DenseMatrix,
    val singularValues: IndexedSeq[Double],
    val v: DenseMatrix
  ):
    private val rowCount = u.rows
    private val columnCount = v.rows

    /** The cutoff below which a singular value counts as zero: `max(m, n) * s_1 * 2^-52`. */
    def tolerance: Double =
      if singularValues.isEmpty then 0.0
      else math.max(rowCount, columnCount) * singularValues.head * SingularValueDecomposition.MachineEpsilon

    /** The number of singular values above [[tolerance]]. */
    def rank: Int = rank(tolerance)

    /** The number of singular values above `cutoff`. */
    def rank(cutoff: Double): Int = singularValues.count(_ > cutoff)

    /** The pseudo-inverse solution `x = V S^+ U^T b`: for a consistent system the solution of least norm, for an
      * inconsistent one the least squares solution of least norm, where `S^+` inverts the singular values above
      * [[tolerance]] and sets the rest to 0.
      */
    def solve(b: DenseVector): DenseVector =
      require(b.size == rowCount, s"the right-hand side has ${b.size} entries, the matrix has $rowCount rows")
      val cutoff = tolerance
      val x = new Array[Double](columnCount)
      var k = 0
      while k < singularValues.length do
        val s = singularValues(k)
        if s > cutoff then
          val coefficient = u.column(k).dot(b) / s
          var i = 0
          while i < columnCount do
            x(i) += coefficient * v(i, k)
            i += 1
        k += 1
      DenseVector.wrap(x)

  object SingularValueDecomposition:
    private[tda4j] val MachineEpsilon: Double = math.pow(2.0, -52)

    private[tda4j] def of(a: DenseMatrix): SingularValueDecomposition =
      val m = a.rows
      val n = a.cols
      if m == 0 || n == 0 then new SingularValueDecomposition(zeros(m, 0), IndexedSeq.empty, zeros(n, 0))
      else if m >= n then
        // The columns of a, as arrays.
        val columns = Array.tabulate(n)(j => Array.tabulate(m)(i => a(i, j)))
        val (uColumns, s, vColumns) = jacobi(columns, m, n)
        assemble(uColumns, s, vColumns, m, n)
      else
        // Wide: A^T = U' S V'^T, so A = V' S U'^T: the roles of the two exchange.
        val columns = Array.tabulate(m)(i => Array.tabulate(n)(j => a(i, j)))
        val (uColumns, s, vColumns) = jacobi(columns, n, m)
        assemble(vColumns, s, uColumns, m, n)

    /** Two-sided assembly of the matrices from columns: `leftColumns` has `p` columns of length `m`, `rightColumns` `p`
      * columns of length `n`.
      */
    private def assemble(
      leftColumns: Array[Array[Double]],
      s: Array[Double],
      rightColumns: Array[Array[Double]],
      m: Int,
      n: Int
    ): SingularValueDecomposition =
      val p = s.length
      val u = DenseMatrix.tabulate(m, p)((i, k) => leftColumns(k)(i))
      val v = DenseMatrix.tabulate(n, p)((j, k) => rightColumns(k)(j))
      new SingularValueDecomposition(u, s.toIndexedSeq, v)

    /** One-sided Jacobi on the `n` columns (each of length `m >= n`) of a matrix `W`: rotates pairs of columns until
      * all are orthogonal, accumulating the rotations in `V`. Then `W = A V` has columns `s_k u_k`. Returns the `n`
      * columns of `U` (completed to an orthonormal set where `s_k` is 0), the singular values, descending, and the `n`
      * columns of `V`, in the same order.
      */
    private def jacobi(
      w: Array[Array[Double]],
      m: Int,
      n: Int
    ): (Array[Array[Double]], Array[Double], Array[Array[Double]]) =
      val v = Array.tabulate(n)(j => Array.tabulate(n)(i => if i == j then 1.0 else 0.0))
      // Pairs count as orthogonal when |w_p . w_q| <= tol |w_p| |w_q|; below a few units of rounding error the
      // rotations would only chase the noise of the inner products themselves.
      val tol = math.max(1.0, math.sqrt(m.toDouble)) * MachineEpsilon
      val maxSweeps = 60
      var sweep = 0
      var rotated = true
      while rotated && sweep < maxSweeps do
        rotated = false
        var p = 0
        while p < n - 1 do
          var q = p + 1
          while q < n do
            val wp = w(p)
            val wq = w(q)
            var alpha = 0.0
            var beta = 0.0
            var gamma = 0.0
            var i = 0
            while i < m do
              alpha += wp(i) * wp(i)
              beta += wq(i) * wq(i)
              gamma += wp(i) * wq(i)
              i += 1
            if gamma != 0.0 && math.abs(gamma) > tol * math.sqrt(alpha) * math.sqrt(beta) then
              rotated = true
              // The rotation that makes the two columns orthogonal: tan 2θ = 2γ / (α - β), with the smaller angle.
              val zeta = (beta - alpha) / (2.0 * gamma)
              val t =
                if math.abs(zeta) > 1e150 then 0.5 / zeta
                else (if zeta >= 0.0 then 1.0 else -1.0) / (math.abs(zeta) + math.sqrt(1.0 + zeta * zeta))
              val c = 1.0 / math.sqrt(1.0 + t * t)
              val s = c * t
              rotate(wp, wq, c, s, m)
              rotate(v(p), v(q), c, s, n)
            q += 1
          p += 1
        sweep += 1
      val norms = Array.tabulate(n) { k =>
        var sum = 0.0
        var i = 0
        while i < m do
          sum += w(k)(i) * w(k)(i)
          i += 1
        math.sqrt(sum)
      }
      val order = norms.indices.sortBy(k => -norms(k)).toArray
      val s = order.map(norms)
      val vSorted = order.map(v)
      val cutoff = if s.isEmpty then 0.0 else math.max(m, n) * s.head * MachineEpsilon
      // A column that is not filled in yet is empty (m >= 1, so a real column never is).
      val u = Array.fill(n)(Array.emptyDoubleArray)
      var k = 0
      while k < n do
        if s(k) > cutoff then
          val wk = w(order(k))
          val inv = 1.0 / s(k)
          u(k) = Array.tabulate(m)(i => wk(i) * inv)
        k += 1
      k = 0
      while k < n do
        if u(k).isEmpty then u(k) = complement(u, m)
        k += 1
      (u, s, vSorted)

    /** The Givens rotation `(x, y) <- (c x - s y, s x + c y)` applied to two columns of length `len`. */
    private def rotate(x: Array[Double], y: Array[Double], c: Double, s: Double, len: Int): Unit =
      var i = 0
      while i < len do
        val xi = x(i)
        val yi = y(i)
        x(i) = c * xi - s * yi
        y(i) = s * xi + c * yi
        i += 1

    /** A unit vector of length `m` orthogonal to every column already filled in `columns` (the empty ones are not): the
      * standard basis vector with the largest part orthogonal to them, orthogonalized twice against them.
      */
    private def complement(columns: Array[Array[Double]], m: Int): Array[Double] =
      val filled = columns.filter(_.nonEmpty)
      var best = Array.emptyDoubleArray
      var bestNorm = -1.0
      var e = 0
      while e < m do
        val candidate = new Array[Double](m)
        candidate(e) = 1.0
        var pass = 0
        while pass < 2 do
          filled.foreach { c =>
            var dot = 0.0
            var i = 0
            while i < m do
              dot += candidate(i) * c(i)
              i += 1
            i = 0
            while i < m do
              candidate(i) -= dot * c(i)
              i += 1
          }
          pass += 1
        var sum = 0.0
        var i = 0
        while i < m do
          sum += candidate(i) * candidate(i)
          i += 1
        val norm = math.sqrt(sum)
        if norm > bestNorm then
          bestNorm = norm
          best = candidate
        e += 1
      var i = 0
      while i < m do
        best(i) /= bestNorm
        i += 1
      best
