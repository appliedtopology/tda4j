package org.appliedtopology.tda4j

import scala.collection.immutable.Map
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.*

/** The cubical complex of a full rectangular grid, filtered by the values of its top cells (pixels, voxels): every
  * other cube gets the minimum value of the top cells containing it, the sublevel-set convention of GUDHI, DIPHA and
  * Perseus. A face is contained in every top cell its cofaces are, so the filtration is monotone. For superlevel sets,
  * negate the values (what [[CubicalImage]]'s `sublevel = false` does).
  *
  * `shape(i)` is the number of pixels along axis `i`. The complex has `prod_i (2 shape(i) + 1)` cells
  * (`totalCellCount`): a 256x256 image has 513² = 263169.
  */
class CubicalGridStream(
  val shape: IndexedSeq[Int],
  val topCellValue: IndexedSeq[Int] => Double,
  // Read every top cell's value in parallel when the values are first needed. `topCellValue` must then be safe to call
  // from several threads; every constructor in CubicalImage.scala reads only immutable captured data.
  val parallelFiltrationValue: Boolean = false
) extends StratifiedCellStream[Cube, Double]
    with DoubleFiltration[Cube]():

  require(shape.nonEmpty, "grid shape must have at least one axis")
  require(shape.forall(_ > 0), "grid shape must be strictly positive along every axis")

  val ambientDim: Int = shape.length

  /** Total cell count of the full grid complex, by the "sum over subsets of a product = product of (in-choice +
    * out-choice)" identity: for a fixed set of non-degenerate axes there are `shape(i)` choices along a non-degenerate
    * axis and `shape(i)+1` along a degenerate one, and summing that product over every subset of non-degenerate axes
    * collapses to `prod_i (shape(i) + (shape(i)+1))`. O(ambientDim), no enumeration needed.
    */
  def totalCellCount: Long = shape.map(n => 2L * n + 1L).product

  private def inGrid(c: Cube): Boolean =
    c.ambientDim == ambientDim && (0 until ambientDim).forall { i =>
      val coord = c.encoded(i)
      coord >= 0 && coord <= 2 * shape(i)
    }

  // Every cell's value, precomputed once into a flat array over the doubled-coordinate grid (cell with encoding `e` at
  // index sum_i e(i) * weight(i)): engines consult `filtrationValue` on every comparison, so it must be an array lookup,
  // not a hash of a `Cube` (`.claude/WORKLOG-fast-cubical-representatives.md`). The minimum over the top cells
  // containing a cube is separable by axis: start from the top cells' values at their (all-odd) positions and +∞
  // elsewhere, then along each axis give every even position the minimum of its odd neighbours.
  private val doubledShape: Array[Int] = shape.map(n => 2 * n + 1).toArray
  private val cellWeight: Array[Long] =
    val w = new Array[Long](ambientDim)
    w(ambientDim - 1) = 1L
    for i <- ambientDim - 2 to 0 by -1 do w(i) = w(i + 1) * doubledShape(i + 1)
    w

  /** Every top cell's value in row-major order (the last axis fastest), read from `topCellValue` once: the flat array
    * the grid engines and `cellValues` work from. In parallel when `parallelFiltrationValue`.
    */
  private[tda4j] lazy val topCellValues: Array[Double] =
    val pixelCount = shape.foldLeft(1L)(_ * _)
    require(pixelCount <= Int.MaxValue, s"CubicalGridStream: $pixelCount top cells do not fit in one array")
    val values = new Array[Double](pixelCount.toInt)
    val pstride = shape.scanRight(1)(_ * _).tail
    def read(p: Int): Unit = values(p) = topCellValue(IndexedSeq.tabulate(ambientDim)(i => (p / pstride(i)) % shape(i)))
    if parallelFiltrationValue then (0 until pixelCount.toInt).par.foreach(read)
    else
      var p = 0
      while p < values.length do
        read(p)
        p += 1
    values

  private lazy val cellValues: Array[Double] =
    val total = totalCellCount
    require(total <= Int.MaxValue, s"CubicalGridStream: $total cells do not fit in one array")
    val values = Array.fill(total.toInt)(Double.PositiveInfinity)
    val pixels = topCellValues
    val pstride = shape.scanRight(1)(_ * _).tail
    for p <- pixels.indices do
      var index = 0L
      for i <- 0 until ambientDim do index += (2L * ((p / pstride(i)) % shape(i)) + 1L) * cellWeight(i)
      values(index.toInt) = pixels(p)
    for axis <- 0 until ambientDim do
      val w = cellWeight(axis).toInt
      val len = doubledShape(axis)
      for i <- values.indices do
        val c = (i / w) % len
        if c            % 2 == 0 then
          val below = if c > 0 then values(i - w) else Double.PositiveInfinity
          val above = if c < len - 1 then values(i + w) else Double.PositiveInfinity
          values(i) = math.min(below, above)
    values

  private def cellIndex(c: Cube): Int =
    val e = c.encoded
    var index = 0L
    var i = 0
    while i < ambientDim do
      index += e(i) * cellWeight(i)
      i += 1
    index.toInt

  override val filtrationValue: PartialFunction[Cube, Double] = new PartialFunction[Cube, Double]:
    def isDefinedAt(c: Cube): Boolean = inGrid(c)
    def apply(c: Cube): Double = cellValues(cellIndex(c))

  /** `FiltrationOrdering.canonical` with `cubeOrdering` as tie-break. Ties are common here: a face shares its value
    * with at least one coface.
    */
  override val filtrationOrdering: Ordering[Cube] =
    FiltrationOrdering.canonical(filtrationValue, _.dim, cubeOrdering)

  private def cubesOfDimension(d: Int): Iterator[Cube] =
    if d < 0 || d > ambientDim then Iterator.empty
    else
      (0 until ambientDim).toVector.combinations(d).flatMap { nondegAxesSeq =>
        val nondegSet = nondegAxesSeq.toSet
        val perAxisRanges: IndexedSeq[Range] =
          (0 until ambientDim).map(i => if nondegSet(i) then 0 until shape(i) else 0 to shape(i))
        cartesianProductRange(perAxisRanges).map(coords => Cube(coords, nondegSet))
      }

  private def cartesianProductRange(ranges: IndexedSeq[Range]): Iterator[IndexedSeq[Int]] =
    ranges.foldLeft(Iterator(IndexedSeq.empty[Int])) { (acc, r) =>
      for prefix <- acc; v <- r.iterator yield prefix :+ v
    }

  /** The cubes of dimension `d` (`0 <= d <= ambientDim`), oldest first. Each call builds and sorts the whole dimension.
    */
  override def iterateDimension: PartialFunction[Int, Iterator[Cube]] = {
    case d if d >= 0 && d <= ambientDim =>
      val cubes = cubesOfDimension(d).toVector
      cubes.sorted(using filtrationOrdering.reverse).iterator
  }

/** A grid whose top-cell values are already one flat row-major array, as [[CubicalImage.fromFlatArray]] builds it: the
  * engines read the array itself instead of calling `topCellValue` once per pixel.
  */
private[tda4j] final class FlatCubicalGridStream(
  shape: IndexedSeq[Int],
  pixels: Array[Double],
  parallelFiltrationValue: Boolean = false
) extends CubicalGridStream(shape, FlatCubicalGridStream.reader(shape, pixels), parallelFiltrationValue):
  override private[tda4j] lazy val topCellValues: Array[Double] = pixels

private[tda4j] object FlatCubicalGridStream:
  /** `topCellValue` for a row-major array. */
  def reader(shape: IndexedSeq[Int], pixels: Array[Double]): IndexedSeq[Int] => Double =
    val strides = shape.scanRight(1)(_ * _).tail.toArray
    idx =>
      var flat = 0
      var i = 0
      while i < strides.length do
        flat += idx(i) * strides(i)
        i += 1
      pixels(flat)

/** `stream` without its cells of dimension above `maxDim`, with the same values and order. */
class LimitedCubicalGridStream(stream: CubicalGridStream, maxDim: Int)
    extends StratifiedCellStream[Cube, Double]
    with DoubleFiltration[Cube]():
  override def iterateDimension: PartialFunction[Int, Iterator[Cube]] = {
    case d: Int if d >= 0 && d <= maxDim && stream.iterateDimension.isDefinedAt(d) => stream.iterateDimension(d)
  }
  override def filtrationOrdering: Ordering[Cube] = stream.filtrationOrdering
  override def filtrationValue: PartialFunction[Cube, Double] = stream.filtrationValue
  // Cells up to dimension maxDim: degrees above maxDim - 1 are truncation artifacts.
  override def homologyDegreeLimit: Option[Int] = Some(maxDim - 1)

/** A finite set of cubes with explicit filtration values, the cubical counterpart of `ExplicitStream`: for small
  * hand-built complexes and cubical complexes that are not a full grid.
  */
class ExplicitCubicalStream(
  protected val filtrationValues: Map[Cube, Double],
  protected val cubes: Seq[Cube]
) extends StratifiedCellStream[Cube, Double]
    with DoubleFiltration[Cube]():

  override val filtrationValue: PartialFunction[Cube, Double] = filtrationValues

  override val filtrationOrdering: Ordering[Cube] =
    FiltrationOrdering.canonical(filtrationValue, _.dim, cubeOrdering)

  private lazy val byDimension: Map[Int, Vector[Cube]] =
    cubes.groupBy(_.dim).view.mapValues(_.sorted(using filtrationOrdering.reverse).toVector).toMap
  private lazy val maxDim: Int = if cubes.isEmpty then -1 else cubes.map(_.dim).max

  override def iterateDimension: PartialFunction[Int, Iterator[Cube]] = {
    case d if d >= 0 && d <= maxDim => byDimension.getOrElse(d, Vector.empty).iterator
  }

object ExplicitCubicalStream:
  def apply(cellsWithValues: (Double, Cube)*): ExplicitCubicalStream =
    new ExplicitCubicalStream(
      cellsWithValues.map { case (v, c) => c -> v }.toMap,
      cellsWithValues.map(_._2)
    )
