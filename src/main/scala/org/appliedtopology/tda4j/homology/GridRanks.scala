package org.appliedtopology.tda4j

/** The pixel (top cell) values of a cubical grid with their dense ranks: `distinct` holds the distinct values in
  * ascending `java.lang.Double.compare` order, and `rank(p)` is the position of pixel `p`'s value in it. In the
  * T-construction every cell takes the smallest value of the pixels containing it, so every cell's value is some
  * pixel's and its rank is the smallest rank among those pixels: the grid engines order cells by integer ranks and look
  * the values up only to report them or compare them.
  *
  * Pixels are row-major, the last axis fastest. NaN values are refused: a NaN pixel has no place in a filtration
  * (`Math.min` would carry it to every face of the pixel, so a face would enter after its coface).
  */
private[tda4j] final class GridRanks private (
  val shape: Array[Int],
  val pixels: Array[Double],
  val distinct: Array[Double],
  val rank: Array[Int]
):
  def d: Int = shape.length
  def size: Int = pixels.length

private[tda4j] object GridRanks:
  def apply(shape: IndexedSeq[Int], pixels: Array[Double]): GridRanks =
    val dims = shape.toArray
    require(
      dims.foldLeft(1L)(_ * _) == pixels.length.toLong,
      s"GridRanks: ${pixels.length} values for shape ${dims.mkString("x")}"
    )
    var i = 0
    while i < pixels.length do
      if pixels(i).isNaN then throw nanPixel(dims, i)
      i += 1
    val (sortedKeys, order) = radixOrder(pixels)
    val n = pixels.length
    val rank = new Array[Int](n)
    val distinctBuffer = new Array[Double](n)
    var r = -1
    var j = 0
    while j < n do
      if r < 0 || sortedKeys(j) != sortedKeys(j - 1) then
        r += 1
        distinctBuffer(r) = pixels(order(j))
      rank(order(j)) = r
      j += 1
    new GridRanks(dims, pixels, java.util.Arrays.copyOf(distinctBuffer, r + 1), rank)

  /** The error for a NaN value at pixel `p` (row-major): where it is, and what to use instead. In the user's units: an
    * image filtered by superlevel sets (`sublevel = false`, values negated) is masked by `-Infinity`, not `+Infinity`.
    */
  def nanPixel(shape: Array[Int], p: Int, sublevel: Boolean = true): IllegalArgumentException =
    val mask = if sublevel then "+Infinity" else "-Infinity (the filtration is superlevel)"
    new IllegalArgumentException(
      s"the image has a NaN value at pixel ${coordinates(shape, p).mkString("(", ", ", ")")}: NaN has no place in a " +
        s"filtration. Replace it by a number, or by $mask for a missing pixel: it then never enters, like a mask."
    )

  /** The lattice coordinates of pixel `p` (row-major, last axis fastest). */
  def coordinates(shape: Array[Int], p: Int): Array[Int] =
    val c = new Array[Int](shape.length)
    var rem = p
    var i = shape.length - 1
    while i >= 0 do
      c(i) = rem % shape(i)
      rem /= shape(i)
      i -= 1
    c

  /** Row-major strides of `dims`. */
  def strides(dims: Array[Int]): Array[Int] =
    val s = new Array[Int](dims.length)
    s(dims.length - 1) = 1
    var i = dims.length - 2
    while i >= 0 do
      s(i) = s(i + 1) * dims(i + 1)
      i -= 1
    s

  /** Weights turning a cube's doubled-coordinate encoding (coordinate `i` in `0 .. 2 shape(i)`) into a `Long` ordered
    * like the encoding itself, lexicographically: the cube's index in the doubled grid.
    */
  def encodingWeights(shape: Array[Int]): Array[Long] =
    val w = new Array[Long](shape.length)
    w(shape.length - 1) = 1L
    var i = shape.length - 2
    while i >= 0 do
      w(i) = w(i + 1) * (2L * shape(i + 1) + 1L)
      i -= 1
    w

  /** The product of `dims`, refused past `Int.MaxValue` with a message naming `what`. */
  def checkedProduct(dims: Array[Int], what: String): Int =
    val p = dims.foldLeft(1L)(_ * _)
    require(p <= Int.MaxValue, s"$what: $p entries do not fit in one array")
    p.toInt

  /** `v`'s bits, transformed so that comparing them as UNSIGNED longs is `java.lang.Double.compare` (-0.0 before 0.0).
    */
  private def sortableKey(v: Double): Long =
    val bits = java.lang.Double.doubleToLongBits(v)
    bits ^ ((bits >> 63) | Long.MinValue)

  // A stable LSD radix sort on the sortable keys, eight bits at a time, skipping a digit every key shares; returns the
  // sorted keys and the pixel order. A comparison sort of the indices cost a quarter of the fast engine's time.
  private def radixOrder(pixels: Array[Double]): (Array[Long], Array[Int]) =
    val n = pixels.length
    var srcKeys = new Array[Long](n)
    var i = 0
    while i < n do
      srcKeys(i) = sortableKey(pixels(i))
      i += 1
    var srcOrder = Array.range(0, n)
    var dstKeys = new Array[Long](n)
    var dstOrder = new Array[Int](n)
    val count = new Array[Int](256)
    var shift = 0
    while shift < 64 do
      java.util.Arrays.fill(count, 0)
      i = 0
      while i < n do
        count(((srcKeys(i) >>> shift) & 0xffL).toInt) += 1
        i += 1
      if count(((srcKeys(0) >>> shift) & 0xffL).toInt) != n then
        var sum = 0
        var b = 0
        while b < 256 do
          val c = count(b)
          count(b) = sum
          sum += c
          b += 1
        i = 0
        while i < n do
          val digit = ((srcKeys(i) >>> shift) & 0xffL).toInt
          val position = count(digit)
          dstKeys(position) = srcKeys(i)
          dstOrder(position) = srcOrder(i)
          count(digit) = position + 1
          i += 1
        val k = srcKeys
        srcKeys = dstKeys
        dstKeys = k
        val o = srcOrder
        srcOrder = dstOrder
        dstOrder = o
      shift += 8
    (srcKeys, srcOrder)

/** Iteration over the doubled grid of a cubical grid (KMM encoding: coordinate `2a` is the point `a`, `2a + 1` the
  * interval `[a, a + 1]`), one prefix (the coordinates of every axis but the last) at a time, so a caller can walk the
  * last axis in a tight loop and skip the prefixes that hold none of the cells it wants.
  */
private[tda4j] object DoubledGrid:
  /** Calls `body` with the doubled coordinates of axes `0 .. d - 2` (a live array: read it, never keep it), for every
    * such prefix in ascending lexicographic order if `ascending`, else descending. For `d = 1` there is one, empty,
    * prefix.
    */
  inline def foreachPrefix(shape: Array[Int], ascending: Boolean)(inline body: Array[Int] => Unit): Unit =
    val p = shape.length - 1
    val c = new Array[Int](p)
    if !ascending then
      var i = 0
      while i < p do
        c(i) = 2 * shape(i)
        i += 1
    var done = false
    while !done do
      body(c)
      var i = p - 1
      var carry = true
      while carry && i >= 0 do
        if ascending then
          c(i) += 1
          if c(i) > 2 * shape(i) then
            c(i) = 0
            i -= 1
          else carry = false
        else
          c(i) -= 1
          if c(i) < 0 then
            c(i) = 2 * shape(i)
            i -= 1
          else carry = false
      if carry then done = true

/** The cubes of one grid by their doubled-grid index, row-major with the last axis fastest: the decoder of the grid
  * engines' packed representatives. Index order is `cubeOrdering`'s on the grid (lexicographic on the encoding, axis 0
  * first). Holds only the strides and one box per coordinate value, since every representative it decodes keeps it.
  */
private[tda4j] final class GridCubes(shape: Array[Int]) extends CellDecoder[Cube]:
  private val extent: Array[Int] = shape.map(n => 2 * n + 1)
  private val weight: Array[Int] = GridRanks.strides(extent)
  private val boxes = CubeBoxes(shape)

  /** The number of cells, which must fit in an `Int`. */
  val size: Int = GridRanks.checkedProduct(extent, "GridCubes")

  def apply(key: Int): Cube = boxes.cube(i => (key / weight(i)) % extent(i))

  def keyOf(c: Cube): Int =
    val e = c.encoded
    var key = 0
    var i = 0
    while i < weight.length do
      key += e(i) * weight(i)
      i += 1
    key

private[tda4j] object GridCubes:
  /** Whether every cell of a grid of this shape has an `Int` index. */
  def fits(shape: Array[Int]): Boolean = shape.foldLeft(1.0)((p, n) => p * (2.0 * n + 1.0)) <= Int.MaxValue
