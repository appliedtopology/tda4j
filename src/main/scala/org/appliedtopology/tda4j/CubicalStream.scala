package org.appliedtopology.tda4j

import scala.collection.immutable.Map

/** Dense cubical complex over a full rectangular grid, filtration values assigned via the T-construction: a
  * caller-supplied `topCellValue` gives every top-dimensional cube (pixel/voxel) its own value directly, and every
  * lower-dimensional cube's value is the min over all top cells that contain it as a face -- exactly the "sublevel
  * set of a piecewise-constant function on pixels" convention GUDHI/DIPHA/Perseus all use for image persistence
  * (see `.claude/WORKLOG-cubical.md` for the monotonicity proof: since every top cell containing an immediate
  * coface of `c` also contains `c`, `fv(coface) >= fv(c)` always holds by construction, which is exactly what
  * `CellularHomologyContext.processingOrder`'s ascending sort requires).
  *
  * For a SUPERLEVEL-set convention instead, negate `topCellValue` before constructing (the standard trick -- see
  * `CubicalImage.scala`'s `sublevel` parameter, which does exactly this): sublevel persistence of `-f` is
  * superlevel persistence of `f`, reparametrized, so this type deliberately does not carry its own sign-direction
  * flag -- one code path, always "min over cofaces," is easier to get right and to verify than baking a direction
  * switch into the core stream.
  *
  * `shape(i)` is the number of PIXELS along axis `i` (not lattice points -- there are `shape(i) + 1` of those). The
  * full grid complex has `prod_i (2*shape(i)+1)` cells total (`totalCellCount`) -- e.g. a 256x256 image has
  * 513*513 = 263169 cells, not 65536.
  */
class CubicalGridStream(
  val shape: IndexedSeq[Int],
  val topCellValue: IndexedSeq[Int] => Double
) extends StratifiedCellStream[Cube, Double]
    with DoubleFiltration[Cube]():

  require(shape.nonEmpty, "grid shape must have at least one axis")
  require(shape.forall(_ > 0), "grid shape must be strictly positive along every axis")

  val ambientDim: Int = shape.length

  /** Total cell count of the full grid complex, by the "sum over subsets of a product = product of (in-choice +
    * out-choice)" identity: for a fixed set of non-degenerate axes there are `shape(i)` choices along a
    * non-degenerate axis and `shape(i)+1` along a degenerate one, and summing that product over every subset of
    * non-degenerate axes collapses to `prod_i (shape(i) + (shape(i)+1))`. O(ambientDim), no enumeration needed.
    */
  def totalCellCount: Long = shape.map(n => 2L * n + 1L).product

  private def inGrid(c: Cube): Boolean =
    c.ambientDim == ambientDim && (0 until ambientDim).forall { i =>
      val coord = c.encoded(i)
      coord >= 0 && coord <= 2 * shape(i)
    }

  /** Every top-dimensional cell (grid index) containing `c` as a face, computed directly rather than via a
    * recursive "immediate cofaces" walk -- mathematically equivalent (see class doc) and cheaper: for each
    * degenerate axis of `c` at lattice point `k`, a containing top cell's own index along that axis is `k-1` or
    * `k` (whichever lies in range); for each non-degenerate axis, it's forced to `c`'s own index there. Cost is
    * O(2^(ambient dim - dim(c))) per call -- fine at the ambient dimensions cubical complexes are actually used at
    * (2D/3D), not memoized here on purpose (see class doc's note on deferring optimization until measured).
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

  override val filtrationValue: PartialFunction[Cube, Double] = new PartialFunction[Cube, Double]:
    def isDefinedAt(c: Cube): Boolean = inGrid(c)
    def apply(c: Cube): Double = containingTopCells(c).map(topCellValue).min

  /** Explicit negated-fv comparison, then dimension, then the canonical `cubeOrdering` tie-break -- copied in shape
    * from `EnumeratingCofaceSimplexStream.filtrationOrdering`, deliberately NOT `.reverse` of an ascending-built
    * ordering (that flips the dimension tie-break too -- see `Homology.scala`'s `processingOrder` note and
    * `.claude/WORKLOG-cubical.md`'s advisor-consult section). Ties on filtration value are the COMMON case here,
    * not an edge case: every non-top face shares its value with at least one of its cofaces by construction
    * (min-over-cofaces), so a broken tie-break would corrupt essentially every reduction, not just rare
    * coincidences.
    */
  override val filtrationOrdering: Ordering[Cube] = new Ordering[Cube]:
    def compare(x: Cube, y: Cube): Int =
      lazy val tieBreak: Int =
        Integer.compare(x.dim, y.dim) match
          case 0  => cubeOrdering.compare(x, y)
          case dc => dc
      if filtrationValue.isDefinedAt(x) && filtrationValue.isDefinedAt(y) then
        java.lang.Double.compare(filtrationValue(y), filtrationValue(x)) match
          case 0  => tieBreak
          case fc => fc
      else tieBreak

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

  /** Bounded at `0 to ambientDim`, contiguous from 0 -- the contract `StratifiedCellStream.iterator`'s default
    * implementation (and this class's own callers) rely on. Each dimension's bucket is fully materialized and
    * sorted by `filtrationOrdering.reverse` (oldest-first, what `CellularHomologyContext` -- via its own
    * `processingOrder` re-sort -- and `iterateDimension`'s own established convention both expect); on a large
    * grid this is the memory-heavy step, not `containingTopCells`. Recomputed and re-sorted from scratch on
    * EVERY call, unlike `ExplicitCubicalStream.byDimension` below (a `lazy val`) -- fine for
    * `CellularHomologyContext`, which calls `.iterator` (hence this) exactly once per `persistentHomology`
    * run, but a caller that repeatedly calls `iterateDimension(d)` directly (as some alpha-complex specs do
    * for their own streams) would pay the full re-sort every time; not measured as an actual problem, just
    * flagged rather than silently left unmentioned.
    */
  override def iterateDimension: PartialFunction[Int, Iterator[Cube]] = {
    case d if d >= 0 && d <= ambientDim =>
      cubesOfDimension(d).toVector.sorted(using filtrationOrdering.reverse).iterator
  }

/** A sparse/arbitrary finite set of cubes with explicit filtration values -- mirrors `ExplicitStream` for
  * simplices. Useful for hand-built fixtures and for genuinely non-grid cubical complexes (arbitrary unions of
  * products of intervals, per the original ask -- `CubicalGridStream` is the important special case for images,
  * not the only shape a cubical complex can take).
  */
class ExplicitCubicalStream(
  protected val filtrationValues: Map[Cube, Double],
  protected val cubes: Seq[Cube]
) extends StratifiedCellStream[Cube, Double]
    with DoubleFiltration[Cube]():

  override val filtrationValue: PartialFunction[Cube, Double] = filtrationValues

  override val filtrationOrdering: Ordering[Cube] = new Ordering[Cube]:
    def compare(x: Cube, y: Cube): Int =
      lazy val tieBreak: Int =
        Integer.compare(x.dim, y.dim) match
          case 0  => cubeOrdering.compare(x, y)
          case dc => dc
      if filtrationValue.isDefinedAt(x) && filtrationValue.isDefinedAt(y) then
        java.lang.Double.compare(filtrationValue(y), filtrationValue(x)) match
          case 0  => tieBreak
          case fc => fc
      else tieBreak

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

/** Thin wrapper mirroring `SimplicialHomologyContext`'s own relationship to `CellularHomologyContext` -- `Cube`
  * needed nothing new from the naive engine (it is already generic over `CellT: OrderedCell`), so this exists
  * purely for the same ergonomic reason `SimplicialHomologyContext` does: a concrete, easily-discoverable name
  * instead of writing out `CellularHomologyContext[Cube, CoefficientT, FiltrationT]` at every call site.
  */
class CubicalHomologyContext[CoefficientT: Field, FiltrationT: Ordering]()
    extends CellularHomologyContext[Cube, CoefficientT, FiltrationT] {}
