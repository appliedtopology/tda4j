package org.appliedtopology.tda4j

/** Shared triangular-distance-matrix arithmetic, used by both `CSV` and `Ripser` -- the two families of format that
  * store a distance matrix as a flat list of triangular entries rather than a dense grid. Kept as one implementation
  * rather than two so the "which row has how many entries, in what order" convention can't silently drift apart between
  * the two call sites.
  */
object DistanceMatrices:

  /** The `n` with `n(n-1)/2 = count`: the number of points of a triangular distance list without diagonal. Throws if
    * there is none (a truncated file).
    */
  def sizeFromTriangularCount(count: Int): Int =
    val approx = (1 + math.sqrt(1 + 8.0 * count)) / 2
    val n = math.round(approx).toInt
    require(
      n.toLong * (n - 1) / 2 == count.toLong,
      s"$count values is not a triangular number n*(n-1)/2 for any integer n -- malformed or truncated input"
    )
    n

  /** `flat` holds, for `i = 1 until n`, the `i` entries `d(i,0),...,d(i,i-1)`, rows concatenated in order -- both
    * Ripser's own `LOWER_DISTANCE_MATRIX`/`DISTANCE_MATRIX` text formats and `CSV.readLowerTriangularDistanceMatrix`
    * use this exact convention. Expanded here into a full symmetric `n x n` matrix with zero diagonal.
    */
  def expandLowerTriangular(flat: IndexedSeq[Double], n: Int): Array[Array[Double]] =
    val m = Array.ofDim[Double](n, n)
    var k = 0
    for
      i <- 1 until n
      j <- 0 until i
    do
      m(i)(j) = flat(k)
      m(j)(i) = flat(k)
      k += 1
    m

  /** Inverse of `expandLowerTriangular`: row `i` (`1 until n`) contributes `d(i,0),...,d(i,i-1)`, in that order. */
  def flattenLowerTriangular(m: Array[Array[Double]]): Array[Double] =
    val n = m.size
    val out = Array.newBuilder[Double]
    for
      i <- 1 until n
      j <- 0 until i
    do out += m(i)(j)
    out.result()

  /** The full matrix from its entries above the diagonal, row by row (`d(0,1), ..., d(0,n-1), d(1,2), ...`), Ripser's
    * upper-distance convention.
    */
  def expandUpperTriangular(flat: IndexedSeq[Double], n: Int): Array[Array[Double]] =
    val m = Array.ofDim[Double](n, n)
    var k = 0
    for
      i <- 0 until (n - 1)
      j <- (i + 1) until n
    do
      m(i)(j) = flat(k)
      m(j)(i) = flat(k)
      k += 1
    m

  /** Inverse of `expandUpperTriangular`. */
  def flattenUpperTriangular(m: Array[Array[Double]]): Array[Double] =
    val n = m.size
    val out = Array.newBuilder[Double]
    for
      i <- 0 until (n - 1)
      j <- (i + 1) until n
    do out += m(i)(j)
    out.result()
