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
  // Warm filtrationValueCache in parallel, per dimension, before iterateDimension's own sort -- see
  // .claude/WORKLOG-parallelization-survey.md item 3 (cubical). Every cube's filtration value is
  // independent of every other cube's -- unlike CechStream's Miniball radii, there is no facet
  // dependency at all here (containingTopCells(c) reads only c's own coordinates and shape, never
  // another Cube's cached value) -- so the only real precondition is that `topCellValue` itself is safe
  // to call concurrently from multiple threads. True for every built-in constructor in
  // CubicalImage.scala (each reads only immutable captured data -- a flat array, or a BufferedImage's
  // pixels -- never mutates anything); a caller-supplied `topCellValue` with its own mutable state would
  // need to be made safe first. Defaults to false, matching AlphaDQPSettings.parallel's own opt-in
  // convention.
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

  /** The grid indices of the top cells containing `c`: along an axis where `c` is a point `k`, index `k - 1` or `k`;
    * along an axis where it is an interval, its own index. `O(2^(ambient dimension - dim c))` per call.
    */
  private def containingTopCells(c: Cube): Seq[IndexedSeq[Int]] =
    val perAxisChoices: IndexedSeq[Seq[Int]] = (0 until ambientDim).map { i =>
      if c.isNondegenerate(i) then Seq(c.lowerCoordinate(i))
      else
        val k = c.lowerCoordinate(i)
        Seq(k - 1, k).filter(v => v >= 0 && v < shape(i))
    }
    cartesianProduct(perAxisChoices)

  private def cartesianProduct(choices: IndexedSeq[Seq[Int]]): Seq[IndexedSeq[Int]] =
    choices.foldLeft(Seq(IndexedSeq.empty[Int])) { (acc, opts) =>
      for prefix <- acc; v <- opts yield prefix :+ v
    }

  // Memoized: `CellularHomologyEngine` re-derives `Ordering[CellT] = stream.filtrationOrdering` and consults it
  // on every chain-arithmetic comparison during reduction (Chain's SortedMap/PriorityQueue accumulator), not
  // just once per cell during the stream's own up-front sorts -- an UNCACHED filtrationValue means
  // containingTopCells (already O(2^(ambientDim - dim(c))) per call) gets recomputed on every single one of
  // those comparisons. Unlike RipserCohomologyEngine's `memoizeFiltrationValue` (opt-in, defaulting to false
  // for memory frugality on potentially-huge VR complexes with a cheap incremental alternative,
  // `insertionDiameter`), there is no equivalent incremental formula here, AND `CellularHomologyEngine.
  // HomologyState.cellIterator` already materializes every cell of the stream into one in-memory Vector before
  // reduction even starts -- so a cache bounded by the same already-resident cell count adds no new
  // memory-frugality concern to weigh against. See `.claude/WORKLOG-autonomous-session-2026-09-19.md` for the
  // phase-separated profiling that found this: per-cell cost was flat in both of the stream's own sort phases,
  // and the entire 3D growth (231->678 us/cell, n=8->24) was isolated to the reduction phase alone.
  private val filtrationValueCache = mutable.HashMap.empty[Cube, Double]

  override val filtrationValue: PartialFunction[Cube, Double] = new PartialFunction[Cube, Double]:
    def isDefinedAt(c: Cube): Boolean = inGrid(c)
    def apply(c: Cube): Double =
      filtrationValueCache.getOrElseUpdate(c, containingTopCells(c).map(topCellValue).min)

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

  /** The cubes of dimension `d` (`0 <= d <= ambientDim`), oldest first. Each call builds and sorts the whole dimension. */
  override def iterateDimension: PartialFunction[Int, Iterator[Cube]] = {
    case d if d >= 0 && d <= ambientDim =>
      val cubes = cubesOfDimension(d).toVector
      if parallelFiltrationValue then
        // Compute into a plain parallel collection first, THEN write into the shared
        // filtrationValueCache sequentially -- mutable.HashMap.getOrElseUpdate is not safe to call
        // concurrently (same hazard class as every other memoization cache in this codebase; see
        // .claude/WORKLOG-parallelization-survey.md's "recurring hazard" note). `cubesOfDimension(d)`
        // yields each cube exactly once (a distinct non-degenerate-axis-set + coordinate combination
        // per cube), so the sequential merge below never redundantly recomputes or double-writes.
        val computed: IndexedSeq[(Cube, Double)] =
          cubes.par.map(c => c -> containingTopCells(c).map(topCellValue).min).toIndexedSeq
        computed.foreach { case (c, v) => filtrationValueCache.getOrElseUpdate(c, v) }
      cubes.sorted(using filtrationOrdering.reverse).iterator
  }

/** `stream` without its cells of dimension above `maxDim`, with the same values and order. */
class LimitedCubicalGridStream(stream: CubicalGridStream, maxDim: Int)
    extends StratifiedCellStream[Cube, Double]
    with DoubleFiltration[Cube]():
  override def iterateDimension: PartialFunction[Int, Iterator[Cube]] = {
    case d: Int if d >= 0 && d <= maxDim && stream.iterateDimension.isDefinedAt(d) => stream.iterateDimension(d)
  }
  override def filtrationOrdering: Ordering[Cube] = stream.filtrationOrdering
  override def filtrationValue: PartialFunction[Cube, Double] = stream.filtrationValue

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
