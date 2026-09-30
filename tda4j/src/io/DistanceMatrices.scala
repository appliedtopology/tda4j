package org.appliedtopology.tda4j
package io

/** Shared triangular-distance-matrix arithmetic, used by both `CSV` and `Ripser` -- the two families of format that
  * store a distance matrix as a flat list of triangular entries rather than a dense grid. Kept as one implementation
  * rather than two so the "which row has how many entries, in what order" convention can't silently drift apart between
  * the two call sites.
  */
private[io] object DistanceMatrices:

  /** Solve `n*(n-1)/2 = count` for `n`, the number of points implied by a flat triangular list of pairwise distances
    * with no diagonal. Fails loudly (rather than silently flooring or truncating) if `count` is not exactly of this
    * form -- a truncated or malformed input file would otherwise silently produce a smaller-than-intended, wrong matrix
    * instead of an error.
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

  /** `flat` holds, for `i = 0 until n-1`, the `n-1-i` entries `d(i,i+1),...,d(i,n-1)`, rows concatenated -- Ripser's
    * own `UPPER_DISTANCE_MATRIX` convention (confirmed against `compressed_upper_distance_matrix`'s `init_rows` pointer
    * arithmetic in `ripser.cpp` directly, not guessed by symmetry with the lower case -- see
    * `.claude/WORKLOG-io-module.md`). Expanded the same way as `expandLowerTriangular`.
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
