package org.appliedtopology.tda4j
package io

import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.{given, *}

/** DIPHA's own binary container format -- little-endian throughout, every file starting with the magic number
  * `8067171840` followed by an `Int64` file-type tag -- verified directly against `DIPHA/dipha`'s own
  * `include/dipha/file_types.h` and `README.md` (see `.claude/WORKLOG-io-module.md`), not reconstructed from a
  * secondhand description.
  *
  * '''Axis order''': DIPHA's `IMAGE_DATA` format is explicitly "x-fastest" (`g(1)` varies fastest) -- the OPPOSITE of
  * `CubicalImage.fromFlatArray`'s own convention (its LAST shape axis is fastest, matching ordinary row-major
  * `Array[Array[...]].flatten`). `readImageData`/`writeImageData` reverse the shape to line the two conventions up (a
  * flat array with axis-0 fastest is, by definition, already in row-major order for the REVERSED shape) -- pinned with
  * a hand-built asymmetric-shape fixture, not just reasoned through, since transposing an image preserves its homology
  * and a barcode-only test cannot catch getting this backwards.
  */
object Dipha:
  private val Magic: Long = 8067171840L
  private val TypeImageData = 1L
  private val TypePersistenceDiagram = 2L
  private val TypeDistanceMatrix = 7L

  private def requireMagicAndType(buf: java.nio.ByteBuffer, expected: Long): Unit =
    val magic = buf.getLong()
    require(magic == Magic, s"not a DIPHA file (magic was $magic, expected $Magic)")
    val fileType = buf.getLong()
    require(fileType == expected, s"expected DIPHA file type $expected, got $fileType")

  /** `DISTANCE_MATRIX` (file type 7): magic, type, `n` (Int64), then `n*n` `Float64` values in plain row-major order
    * (`d(1,1)...d(1,n) d(2,1)...d(n,n)` per the README) -- the FULL matrix, diagonal included, not a triangular
    * packing.
    */
  def readDistanceMatrix(path: String): Array[Array[Double]] =
    val buf = BinaryIO.readAllLE(path)
    requireMagicAndType(buf, TypeDistanceMatrix)
    val n = buf.getLong().toInt
    val m = Array.ofDim[Double](n, n)
    for
      i <- 0 until n
      j <- 0 until n
    do m(i)(j) = buf.getDouble()
    m

  def readExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def writeDistanceMatrix(path: String, matrix: IndexedSeq[IndexedSeq[Double]]): Unit =
    val n = matrix.size
    val buf = BinaryIO.newBufferLE(8 + 8 + 8 + n * n * 8)
    buf.putLong(Magic)
    buf.putLong(TypeDistanceMatrix)
    buf.putLong(n.toLong)
    for
      i <- 0 until n
      j <- 0 until n
    do buf.putDouble(matrix(i)(j))
    BinaryIO.writeLE(path, buf)

  /** `IMAGE_DATA` (file type 1): magic, type, `n` (Int64, total value count), `d` (Int64, dimension), `d` Int64 grid
    * sizes `g(1)...g(d)` (`g(1)` fastest-varying), then `n` `Float64` values in "x-fastest" order. Returned as
    * `(shape, flatValues)` in `CubicalImage.fromFlatArray`'s own last-axis-fastest convention -- i.e. `shape` here is
    * `g.reverse`, not `g` -- ready to pass straight to `fromFlatArray`/`readCubicalGridStream`.
    */
  def readImageData(path: String): (IndexedSeq[Int], IndexedSeq[Double]) =
    val buf = BinaryIO.readAllLE(path)
    requireMagicAndType(buf, TypeImageData)
    val n = buf.getLong().toInt
    val d = buf.getLong().toInt
    val g = new Array[Int](d)
    var i = 0
    while i < d do
      g(i) = buf.getLong().toInt
      i += 1
    require(g.product == n, s"declared value count $n does not match grid size product ${g.product}")
    val values = new Array[Double](n)
    i = 0
    while i < n do
      values(i) = buf.getDouble()
      i += 1
    (g.reverse.toIndexedSeq, values.toIndexedSeq)

  def readCubicalGridStream(path: String, sublevel: Boolean = true): CubicalGridStream =
    val (shape, flatValues) = readImageData(path)
    CubicalImage.fromFlatArray(shape, flatValues, sublevel)

  /** `shape`/`flatValues` in `CubicalImage.fromFlatArray`'s own last-axis-fastest convention -- reversed internally to
    * DIPHA's own `g(1)`-fastest convention before writing (the inverse of `readImageData`'s own reversal).
    */
  def writeImageData(path: String, shape: IndexedSeq[Int], flatValues: IndexedSeq[Double]): Unit =
    require(flatValues.size == shape.product, "flatValues size must equal the product of shape")
    val g = shape.reverse
    val d = g.size
    val n = flatValues.size
    val buf = BinaryIO.newBufferLE(8 + 8 + 8 + 8 + d * 8 + n * 8)
    buf.putLong(Magic)
    buf.putLong(TypeImageData)
    buf.putLong(n.toLong)
    buf.putLong(d.toLong)
    g.foreach(gi => buf.putLong(gi.toLong))
    flatValues.foreach(v => buf.putDouble(v))
    BinaryIO.writeLE(path, buf)

  /** `PERSISTENCE_DIAGRAM` (file type 2): magic, type, `p` (Int64), then `p` `(dim, birth, death)` triples as
    * `Int64`/`Float64`/`Float64`. A negative `dim` value `-k` encodes an ESSENTIAL class of real dimension `k - 1` (the
    * README's own convention -- the `-1` offset exists specifically so a dimension-0 essential class, the single most
    * common case, doesn't collide with an ordinary finite `dim == 0`); confirmed with a dedicated dimension-0-essential
    * fixture, not just read from the README (see `.claude/WORKLOG-io-module.md`). The `death` field of an essential
    * triple is unspecified by the format and is written here as `0.0`, ignored on read.
    */
  def readPersistenceDiagram(path: String): Seq[PersistenceBar[Double, Nothing]] =
    val buf = BinaryIO.readAllLE(path)
    requireMagicAndType(buf, TypePersistenceDiagram)
    val p = buf.getLong().toInt
    (0 until p).map { _ =>
      val rawDim = buf.getLong().toInt
      val birth = buf.getDouble()
      val death = buf.getDouble()
      // Closed/Open (not Closed/Closed) to match `PersistenceBar.apply(dim, lower, upper)`'s own half-open
      // convention -- so a bar built the ordinary way round-trips through this format unchanged, exactly the
      // same reasoning as `Endpoints.toBar` (this format's essential-class encoding is numeric, not textual, so
      // it doesn't share that helper, but the convention it must match is identical).
      if rawDim < 0 then PersistenceBar[Double](-rawDim - 1, birth)
      else PersistenceBar[Double](rawDim, birth, death)
    }

  def writePersistenceDiagram(path: String, bars: Seq[PersistenceBar[Double, ?]]): Unit =
    val p = bars.size
    val buf = BinaryIO.newBufferLE(8 + 8 + 8 + p * (8 + 8 + 8))
    buf.putLong(Magic)
    buf.putLong(TypePersistenceDiagram)
    buf.putLong(p.toLong)
    bars.foreach { bar =>
      val birth = bar.lower match
        case ClosedEndpoint(v)  => v
        case OpenEndpoint(v)    => v
        case NegativeInfinity() => Double.NegativeInfinity
        case PositiveInfinity() => throw new IllegalArgumentException("a bar's lower endpoint cannot be +infinity")
      val (rawDim, death) = bar.upper match
        case PositiveInfinity() => (-bar.dim - 1, 0.0)
        case ClosedEndpoint(v)  => (bar.dim, v)
        case OpenEndpoint(v)    => (bar.dim, v)
        case NegativeInfinity() => throw new IllegalArgumentException("a bar's upper endpoint cannot be -infinity")
      buf.putLong(rawDim.toLong)
      buf.putDouble(birth)
      buf.putDouble(death)
    }
    BinaryIO.writeLE(path, buf)
