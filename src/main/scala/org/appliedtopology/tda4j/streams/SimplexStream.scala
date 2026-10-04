package org.appliedtopology.tda4j

import org.apache.commons.numbers.combinatorics.BinomialCoefficient

import scala.collection.{immutable, mutable}
import scala.collection.immutable.{Map, Seq, SortedSet}
import scala.collection.concurrent.TrieMap
import scala.collection.parallel.CollectionConverters.*
import math.Ordering.Implicits.*

trait Filterable[FiltrationT: Ordering]:
  def smallest: FiltrationT
  def largest: FiltrationT

/** The stdlib-numeric-type instances live here, on `Filterable`'s own companion, rather than as bare top-level package
  * `given`s: found via ordinary implicit-scope search (the companion of either side of `Filterable[Double]`) regardless
  * of import, rather than being ambient to every file that happens to do `import streams.{given, *}`.
  */
object Filterable:
  /** Lets [[ExplicitStreamBuilder]]'s optional `Filterable` parameter pick up the instance below whenever one exists
    * for its filtration type. Without it the builder silently fell back to the stream's own smallest/largest filtration
    * VALUES as the "-infinity"/"+infinity" sentinels, so `barcodeAt` reported a class dying at the last filtration
    * value as essential (and one born at the first as born at -infinity).
    */
  given optionalFilterable: [T] => (f: Filterable[T]) => Option[Filterable[T]] = Some(f)

  given DoubleIsFilterable: Filterable[Double] = new Filterable[Double]:
    val smallest = Double.NegativeInfinity
    val largest = Double.PositiveInfinity

  given FloatIsFilterable: Filterable[Float] = new Filterable[Float]:
    val smallest = Float.NegativeInfinity
    val largest = Float.PositiveInfinity

  given IntIsFilterable: Filterable[Int] = new Filterable[Int]:
    val smallest = Int.MinValue
    val largest = Int.MaxValue

  given ShortIsFilterable: Filterable[Short] = new Filterable[Short]:
    val smallest = Short.MinValue
    val largest = Short.MaxValue

  given LongIsFilterable: Filterable[Long] = new Filterable[Long]:
    val smallest = Long.MinValue
    val largest = Long.MaxValue

trait Filtration[CellT: Cell, FiltrationT: {Ordering, Filterable}] extends Filterable[FiltrationT]:
  def filtrationValue: PartialFunction[CellT, FiltrationT]

trait DoubleFiltration[CellT: Cell] extends Filtration[CellT, Double]:
  val smallest = Double.NegativeInfinity
  val largest = Double.PositiveInfinity

trait CellStream[CellT: Cell, FiltrationT: Ordering] extends Filtration[CellT, FiltrationT] with IterableOnce[CellT]:
  def filtrationOrdering: Ordering[CellT]

object FiltrationOrdering:
  /** The `filtrationOrdering` every stream uses: filtration value reversed (smaller under the ordering means younger,
    * which is what `Chain` pivots on), then dimension, then `tieBreak`. A cell outside `fv`'s domain is ordered by the
    * last two keys alone. `fv` stays a `PartialFunction`: this is on the hottest path of every engine.
    */
  def canonical[C](fv: PartialFunction[C, Double], dim: C => Int, tieBreak: Ordering[C]): Ordering[C] =
    new Ordering[C]:
      def compare(x: C, y: C): Int =
        lazy val tb: Int =
          Ordering.Int.compare(dim(x), dim(y)) match
            case 0  => tieBreak.compare(x, y)
            case dc => dc
        if fv.isDefinedAt(x) && fv.isDefinedAt(y) then
          java.lang.Double.compare(fv(y), fv(x)) match
            case 0  => tb
            case fc => fc
        else tb

/** Abstract trait for representing a sequence of simplices.
  *
  * @tparam VertexT
  *   Type of vertices of the contained simplices.
  * @tparam FiltrationT
  *   Type of the filtration values.
  *
  * @todo
  *   We may want to change this to inherit instead from `IterableOnce[Simplex[VertexT]]`, so that a lazy computed
  *   simplex stream can be created and fit in the type hierarchy.
  */
trait SimplexStream[VertexT: Ordering, FiltrationT: {Ordering, Filterable}]
    extends CellStream[Simplex[VertexT], FiltrationT]:
  val filterable: Filterable[FiltrationT] = summon[Filterable[FiltrationT]]
  export filterable.{largest, smallest}

  val filtrationOrdering =
    FilteredSimplexOrdering[VertexT, FiltrationT](this)(using vertexOrdering = summon[Ordering[VertexT]])(using
      filtrationOrdering = summon[Ordering[FiltrationT]].reverse
    )

object SimplexStream:
  def from[VertexT: Ordering](
    stream: Seq[Simplex[VertexT]],
    metricSpace: FiniteMetricSpace[VertexT]
  ): SimplexStream[VertexT, Double] = new SimplexStream[VertexT, Double]:

    override def filtrationValue: PartialFunction[Simplex[VertexT], Double] =
      FiniteMetricSpace.MaximumDistanceFiltrationValue[VertexT](metricSpace)(using summon[Ordering[VertexT]])

    override def iterator: Iterator[Simplex[VertexT]] =
      stream.iterator

/** A complex given cell by cell (built by [[ExplicitStreamBuilder]], e.g. `ExplicitStreamBuilder.fromFacets(...)`). */
class ExplicitStream[VertexT: Ordering, FiltrationT](
  protected val filtrationValues: Map[Simplex[VertexT], FiltrationT],
  protected val simplices: Seq[Simplex[VertexT]]
)(using filterable: Filterable[FiltrationT])(using ordering: Ordering[FiltrationT])
    extends SimplexStream[VertexT, FiltrationT]
    with StratifiedCellStream[Simplex[VertexT], FiltrationT]:
  self =>

  def filtrationValue: PartialFunction[Simplex[VertexT], FiltrationT] = filtrationValues

  override def iterator: Iterator[Simplex[VertexT]] = simplices.iterator

  private lazy val byDimension: Map[Int, Seq[Simplex[VertexT]]] =
    simplices.groupBy(_.dim).view.mapValues(_.sorted(using filtrationOrdering.reverse)).toMap

  def iterateDimension: PartialFunction[Int, Iterator[Simplex[VertexT]]] = {
    case d if byDimension.contains(d) => byDimension(d).iterator
  }

  def apply(i: Int): Simplex[VertexT] = simplices(i)

  def length: Int = simplices.length

class ExplicitStreamBuilder[VertexT: Ordering, FiltrationT](using
  ordering: Ordering[FiltrationT]
)(using filterableO: Option[Filterable[FiltrationT]] = None)
    extends mutable.ReusableBuilder[
      (FiltrationT, Simplex[VertexT]),
      ExplicitStream[VertexT, FiltrationT]
    ]:
  self =>

  protected val filtrationValues: mutable.Map[Simplex[VertexT], FiltrationT] =
    new mutable.HashMap[Simplex[VertexT], FiltrationT]()
  protected val simplices: mutable.Queue[(FiltrationT, Simplex[VertexT])] =
    mutable.Queue[(FiltrationT, Simplex[VertexT])]()

  // `def`s, not `val`s: this fallback is only ever queried after entries have been added (from `result()`'s
  // `filterable`, used downstream), never at construction time, when `filtrationValues` is still empty.
  val filterable: Filterable[FiltrationT] = filterableO match
    case Some(f) => f
    case None    =>
      new Filterable[FiltrationT]:
        def largest: FiltrationT = filtrationValues.maxBy(_._2)._2
        def smallest: FiltrationT = filtrationValues.minBy(_._2)._2

  override def clear(): Unit =
    filtrationValues.clear()
    simplices.clear()

  override def result(): ExplicitStream[VertexT, FiltrationT] =
    given filtrationOrdering: Ordering[(FiltrationT, Simplex[VertexT])] =
      Ordering
        .by[(FiltrationT, Simplex[VertexT]), FiltrationT]((f: FiltrationT, s: Simplex[VertexT]) => f)(using ordering)
        .orElseBy((f: FiltrationT, s: Simplex[VertexT]) => s)(using simplexOrdering)
    simplices.sortInPlace()

    new ExplicitStream(filtrationValues.toMap, simplices.map((_, s) => s).toSeq)(using filterable)

  override def addOne(
    elem: (FiltrationT, Simplex[VertexT])
  ): ExplicitStreamBuilder.this.type =
    filtrationValues(elem._2) = elem._1
    simplices += elem
    self

object ExplicitStreamBuilder:

  /** How [[fromFilteredFacets]] chooses a filtration value for a cell the caller did not list (a proper face of a
    * listed cell).
    */
  enum UnlistedValues[+FiltrationT]:
    /** The smallest value among the listed cells containing it: the earliest moment it is forced to exist, and the
      * assumption that adds the least. The default; it is always monotone.
      */
    case EarliestCoface

    /** A fixed value for every unlisted cell (e.g. the filtration's smallest value, `0.0`, to have all the implied
      * faces present from the start). Rejected if that breaks monotonicity.
      */
    case Constant(value: FiltrationT)

  /** The stream of everything generated by `facets`: each facet and every one of its nonempty faces, so a complex can
    * be given by its maximal cells alone (`fromFacets(Seq(∆(1,2,3), ∆(3,4)))` is a triangle with a tail). Every cell
    * enters at filtration value `0.0`; use [[fromFilteredFacets]] to give the facets values.
    */
  def fromFacets[VertexT: Ordering](facets: Iterable[Simplex[VertexT]]): ExplicitStream[VertexT, Double] =
    fromFilteredFacets(facets.map(f => (0.0, f)))

  /** As [[fromFacets]], with a filtration value per listed cell. Listed values are kept as given, and may differ freely
    * between cells that overlap; each cell NOT listed gets a value by `unlisted` (default
    * [[UnlistedValues.EarliestCoface]]: the minimum over the listed cells containing it). A cell listed twice with
    * different values, or a filtration that is not monotone in the end (a face later than a cell containing it -- only
    * possible through a contradictory listing or a bad [[UnlistedValues.Constant]]) throws `IllegalArgumentException`.
    */
  def fromFilteredFacets[VertexT: Ordering, FiltrationT: {Ordering, Filterable}](
    facets: Iterable[(FiltrationT, Simplex[VertexT])],
    unlisted: UnlistedValues[FiltrationT] = UnlistedValues.EarliestCoface
  ): ExplicitStream[VertexT, FiltrationT] =
    val ordering = summon[Ordering[FiltrationT]]
    val listed = mutable.HashMap[Simplex[VertexT], FiltrationT]()
    for (value, cell) <- facets do
      val key = Simplex.from(cell.toList)
      require(
        listed.get(key).forall(ordering.equiv(_, value)),
        s"cell $key is listed twice with different filtration values"
      )
      listed(key) = value
    val earliest = mutable.HashMap[Simplex[VertexT], FiltrationT]()
    for (cell, value) <- listed do
      val vertices = cell.toList
      for size <- 1 to vertices.size; face <- vertices.combinations(size) do
        earliest.updateWith(Simplex.from(face)) {
          case Some(old) if ordering.lteq(old, value) => Some(old)
          case _                                      => Some(value)
        }
    val values: Map[Simplex[VertexT], FiltrationT] = earliest.keys.map { cell =>
      cell -> listed.getOrElse(
        cell,
        unlisted match
          case UnlistedValues.EarliestCoface => earliest(cell)
          case UnlistedValues.Constant(v)    => v
      )
    }.toMap
    for (cell, value) <- values; if cell.size > 1; v <- cell.toList do
      val face = Simplex.from(cell.toList.filter(_ != v))
      require(
        ordering.lteq(values(face), value),
        s"filtration is not monotone: $face has value ${values(face)}, later than $cell containing it, at $value"
      )
    given Option[Filterable[FiltrationT]] = Some(summon[Filterable[FiltrationT]])
    val builder = new ExplicitStreamBuilder[VertexT, FiltrationT]()
    values.foreach((cell, value) => builder.addOne((value, cell)))
    builder.result()

class FilteredSimplexOrdering[VertexT, FiltrationT](
  val filtration: Filtration[Simplex[VertexT], FiltrationT]
)(using vertexOrdering: Ordering[VertexT])(using
  filtrationOrdering: Ordering[FiltrationT]
) extends Ordering[Simplex[VertexT]]:
  def compare(x: Simplex[VertexT], y: Simplex[VertexT]): Int = (x, y) match
    case (x, y) if filtration.filtrationValue.isDefinedAt(x) && filtration.filtrationValue.isDefinedAt(y) =>
      filtrationOrdering.compare(filtration.filtrationValue(x), filtration.filtrationValue(y)) match
        case 0 =>
          if Ordering.Int.compare(x.size, y.size) == 0 then
            Ordering.Implicits
              .sortedSetOrdering[SortedSet, VertexT](using vertexOrdering)
              .compare(x.toSortedSet, y.toSortedSet)
          else Ordering.Int.compare(x.size, y.size)
        case cmp if cmp != 0 => cmp
    case (x, y) => // at least one does not have a filtration value defined; just go by dimension and lexicographic
      if Ordering.Int.compare(x.size, y.size) == 0 then
        Ordering.Implicits
          .sortedSetOrdering[SortedSet, VertexT](using vertexOrdering)
          .compare(x.toSortedSet, y.toSortedSet)
      else Ordering.Int.compare(x.size, y.size)

trait StratifiedCellStream[CellT: OrderedCell, FiltrationT: Filterable] extends CellStream[CellT, FiltrationT]:
  /** The cells of dimension `d`, oldest first. The domain must be contiguous from 0 (`0, 1, ..., k`, or empty, or all
    * of the naturals): [[iterator]] stops at the first dimension where this is undefined.
    */
  def iterateDimension: PartialFunction[Int, Iterator[CellT]]

  /** `Some(k)` for a complex cut off to compute homology in degrees `0..k` (what `VietorisRips(points, maxDimension =
    * k)` returns, holding cells up to dimension `k + 1`): its higher degrees are not the homology of the full complex.
    * `None` (the default) for a complete complex.
    */
  def homologyDegreeLimit: Option[Int] = None

  /** All the cells, dimension by dimension. Stops at the first undefined dimension (`takeWhile`, never `filter`, which
    * would search forever past the last one).
    */
  override def iterator: Iterator[CellT] =
    Iterator
      .from(0)
      .takeWhile(iterateDimension.isDefinedAt)
      .flatMap(iterateDimension)

trait LevelwiseSimplexStream[VertexT: Ordering, FiltrationT: Filterable]
    extends StratifiedCellStream[Simplex[VertexT], FiltrationT] {}

private[tda4j] trait CofaceSimplexStream[VertexT: Ordering, FiltrationT: Filterable]
    extends LevelwiseSimplexStream[VertexT, FiltrationT]:

  def currentDimension: Int

  def lastDimensionCache: Seq[Simplex[VertexT]]

  def currentDimensionCache: Seq[Simplex[VertexT]]

  def keepCriterion: PartialFunction[Simplex[VertexT], Boolean]

private[tda4j] class LimitedCofaceSimplexStream(stream: CofaceSimplexStream[Int, Double], maxDim: Int)
    extends CofaceSimplexStream[Int, Double]
    with DoubleFiltration[Simplex[Int]]():
  override def homologyDegreeLimit: Option[Int] =
    Some(stream.homologyDegreeLimit.fold(maxDim - 1)(math.min(_, maxDim - 1)))
  // `d <= maxDim` alone is not sufficient: the WRAPPED stream has its own natural bound (e.g.
  // EnumeratingCofaceSimplexStream's `d < metricSpace.size`, needed because a d-simplex needs d+1 distinct
  // vertices), which can be tighter than `maxDim` for a small point cloud. Calling `stream.iterateDimension(d)`
  // directly (not through `.applyOrElse`) when the wrapped stream isn't itself defined at `d` throws a raw
  // MatchError rather than correctly reporting "undefined here" (see .claude/WORKLOG-maxdim-semantics-fix.md).
  // Checking `stream.iterateDimension.isDefinedAt(d)` here keeps this class's own contract (a d it declares
  // undefined for is a d that was never going to have any cells anyway) rather than crashing on a d that merely
  // exceeds what the wrapped stream can combinatorially produce.
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d: Int if d >= 0 && d <= maxDim && stream.iterateDimension.isDefinedAt(d) => stream.iterateDimension(d)
  }

  override def currentDimension: Int = stream.currentDimension
  override def lastDimensionCache: Seq[Simplex[Int]] = stream.lastDimensionCache
  override def currentDimensionCache: Seq[Simplex[Int]] = stream.currentDimensionCache
  override def keepCriterion: PartialFunction[Simplex[Int], Boolean] = stream.keepCriterion
  override def filtrationOrdering: Ordering[Simplex[Int]] = stream.filtrationOrdering
  override def filtrationValue: PartialFunction[Simplex[Int], Double] = stream.filtrationValue

private[tda4j] class EnumeratingCofaceSimplexStream(
  val metricSpace: FiniteMetricSpace[Int],
  var keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true },
  // None means "not explicitly set," resolved to metricSpace.minimumEnclosingRadius just below (a constant
  // default, since a literal `= metricSpace.minimumEnclosingRadius` can't reference the earlier `metricSpace`
  // parameter). Beyond that radius the complex is a cone from that point on and contributes no further
  // homology (Ripser paper, p. 412; real Ripser uses this exact quantity, `enclosing_radius`, as its own
  // default threshold). Pass `Some(Double.PositiveInfinity)` explicitly for the old always-unbounded behavior.
  // See CLAUDE.md/WORKLOG-mst-and-perf.md.
  maxFiltrationValue: Option[Double] = None,
  // None means "use the default Vietoris-Rips diameter (max pairwise distance) functional," resolved just
  // below. This class's own coface-generation logic touches filtrationValue only as an opaque
  // PartialFunction, so a caller building a genuinely different filtered complex over the same vertex set
  // (e.g. Cech's circumradius functional, see CechCofaceSimplexStream) can supply its own here. Named
  // `filtrationValueOverride`, not `filtrationValue`: the latter is the class's own overridden member below.
  filtrationValueOverride: Option[PartialFunction[Simplex[Int], Double]] = None
) extends CofaceSimplexStream[Int, Double]
    with DoubleFiltration[Simplex[Int]]():

  protected val resolvedMaxFiltrationValue: Double =
    maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

  lazy val edges = for
    i <- metricSpace.elements
    j <- metricSpace.elements
    if i < j && metricSpace.distance(i, j) <= resolvedMaxFiltrationValue
  yield Simplex(i, j)

  // -1, not 0: subclasses' iterateDimension overrides (RipserCofaceSimplexStream and its own subclasses) use
  // `currentDimension != d - 1` to decide whether currentDimensionCache is fresh enough to reuse as dimension
  // d's own lastDimensionCache, or must be rebuilt from scratch. `0` was indistinguishable from "dimension 0
  // was genuinely computed and cached" -- a fresh, never-iterated instance already satisfies
  // `currentDimension(0) != 1 - 1(0)` as `false`, so calling `iterateDimension(1)` directly (skipping dimension
  // 0) silently reused the still-empty default `currentDimensionCache` instead of rebuilding, returning zero
  // edges with no error (CLAUDE.md's ordering-contract rule 5; `.claude/WORKLOG-sheehy-rips.md`). `-1` can never
  // equal `d - 1` for any `d >= 0` a caller could legitimately ask for, so it can only ever mean "nothing has
  // been computed yet" -- every dimension `d >= 1` then correctly rebuilds on first access regardless of
  // whether it's reached via `.iterator` (which always visits dimension 0 first) or a direct out-of-order call.
  var currentDimension: Int = -1

  var lastDimensionCache: IndexedSeq[Simplex[Int]] = IndexedSeq()

  var currentDimensionCache: immutable.Queue[Simplex[Int]] = immutable.Queue.empty

  // Memoized -- but ONLY the default MaximumDistanceFiltrationValue fallback, not a caller-supplied
  // filtrationValueOverride (e.g. CechFiltration already caches internally; double-wrapping it is pure
  // waste). CellularHomologyEngine and CellularPersistenceInChunksEngine both re-derive
  // Ordering[CellT] = stream.filtrationOrdering and consult it on every chain-arithmetic comparison during
  // reduction, not just once per cell during this stream's own up-front sorts -- an uncached filtrationValue
  // means MaximumDistanceFiltrationValue's O(d^2) pairwise-distance recompute reruns on every one of those
  // comparisons. Measured (`.claude/WORKLOG-autonomous-session-2026-09-19.md`): memoizing this one fallback
  // cut reduction-phase wall-clock time by 32-46% across n=5000-20000 on a large, sparse, maxDim=1 complex.
  //
  // This is NOT a reversal of RipserCohomologyEngine's own `memoizeFiltrationValue = false` default: that
  // decision is about a stream that never fully materializes (a genuinely unbounded-in-practice VR complex,
  // by design), where `insertionDiameter` gives an O(d) incremental alternative that makes not caching
  // viable in the first place. Neither condition holds here -- both engines already eagerly materialize
  // every cell into one in-memory Vector before reduction starts, so a cache bounded by that same
  // already-resident cell count adds no new memory concern, and there is no incremental formula for the
  // general max-pairwise-distance functional this stream computes by default.
  //
  // TrieMap, not mutable.HashMap: a plain HashMap's getOrElseUpdate is not safe to call concurrently, and
  // parallelFiltrationValue's pre-warm step (below) does exactly that -- TrieMap's own getOrElseUpdate is a
  // genuine drop-in backed by a lock-free Ctrie. See .claude/WORKLOG-parallelization-survey.md item 2 (Cech).
  private val filtrationValueCache = TrieMap.empty[Simplex[Int], Double]

  override val filtrationValue: PartialFunction[Simplex[Int], Double] =
    filtrationValueOverride.getOrElse {
      val base = FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)
      new PartialFunction[Simplex[Int], Double]:
        def isDefinedAt(spx: Simplex[Int]): Boolean = base.isDefinedAt(spx)
        def apply(spx: Simplex[Int]): Double = filtrationValueCache.getOrElseUpdate(spx, base(spx))
    }

  /** Filtration value reversed, then dimension, then colexicographic order of the vertex sets (their combinatorial
    * index), the refinement Ripser's apparent pairs are defined with. A total order: without a tie-break, a triangle
    * and its longest edge would compare equal and collide in the reduction. `iterateDimension` sorts by this same
    * ordering reversed, so iteration order and pivot order agree.
    */
  override val filtrationOrdering: Ordering[Simplex[Int]] =
    FiltrationOrdering.canonical(filtrationValue, _.size, Ordering.by(simplexIndexing(_)))

  lazy val simplexIndexing: SimplexIndexing = SimplexIndexing(metricSpace.size)

  /** `cells` sorted by `filtrationOrdering.reverse`, computing each cell's value and index once for the sort rather
    * than on every comparison.
    */
  protected def sortedByFiltration(cells: IterableOnce[Simplex[Int]]): Vector[Simplex[Int]] =
    val fvCache = mutable.HashMap.empty[Simplex[Int], Option[Double]]
    val ixCache = mutable.HashMap.empty[Simplex[Int], Long]
    val memoFv: PartialFunction[Simplex[Int], Double] = new PartialFunction[Simplex[Int], Double]:
      def isDefinedAt(s: Simplex[Int]): Boolean = fvCache.getOrElseUpdate(s, filtrationValue.lift(s)).isDefined
      def apply(s: Simplex[Int]): Double = fvCache.getOrElseUpdate(s, filtrationValue.lift(s)).get
    val memoTieBreak: Ordering[Simplex[Int]] = Ordering.by(s => ixCache.getOrElseUpdate(s, simplexIndexing(s)))
    cells.iterator.toVector.sorted(using FiltrationOrdering.canonical(memoFv, _.size, memoTieBreak).reverse)

  /** A candidate is kept iff both the caller's own `keepCriterion` AND the threshold accept it -- one place deciding
    * "is this cell kept," per `sortedByFiltration`'s own note about not duplicating filtration-value lookups across
    * independent filters.
    */
  protected def keptByThresholdAndCriterion(spx: Simplex[Int]): Boolean =
    keepCriterion.applyOrElse(spx, (_: Simplex[Int]) => true) &&
      filtrationValue.applyOrElse(spx, (_: Simplex[Int]) => Double.PositiveInfinity) <= resolvedMaxFiltrationValue

  // Bounded at metricSpace.size (a d-simplex needs d+1 distinct vertices, and there are none beyond that): a
  // real bound, not just a defensive one -- StratifiedCellStream's default .iterator relies on isDefinedAt
  // eventually staying false, and BinomialCoefficient.value(metricSpace.size, d + 1) throws outright for
  // d + 1 > metricSpace.size, so an unbounded catch-all here was the actual root cause of the "some engines'
  // .iterator hangs / eventually crashes" bug (see StratifiedCellStream's doc).
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case 0                                   => metricSpace.elements.map(v => Simplex(v)).iterator
    case 1                                   => sortedByFiltration(edges).iterator
    case d if d >= 0 && d < metricSpace.size =>
      // first, generate all simplices of this dimension
      sortedByFiltration(
        (0 until BinomialCoefficient.value(metricSpace.size, d + 1).toInt).toSeq
          .flatMap { ix =>
            Some(simplexIndexing(ix, d + 1)).filter(keptByThresholdAndCriterion)
          }
      ).iterator
  }

private[tda4j] class RipserCofaceSimplexStream(
  metricSpace: FiniteMetricSpace[Int],
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ =>
    true
  },
  maxFiltrationValue: Option[Double] = None,
  // See EnumeratingCofaceSimplexStream's identical parameter -- this class's own iterateDimension override
  // touches filtrationValue only through the inherited keptByThresholdAndCriterion/sortedByFiltration, so it
  // carries over unchanged to any filtration functional supplied here, VR-specific or not.
  filtrationValueOverride: Option[PartialFunction[Simplex[Int], Double]] = None,
  // Pre-warm filtrationValue for one dimension's whole candidate list in parallel before the sequential
  // filter+sort below touches it (`.claude/WORKLOG-parallelization-survey.md` item 2, Cech). Safe because
  // every candidate at dimension d is generated from lastDimensionCache (dimension d-1, already fully
  // resolved and frozen), so each candidate's own filtration-value computation reads only already-complete
  // lower-dimension state, and the caches this writes to concurrently are TrieMap-backed. Defaults to false.
  parallelFiltrationValue: Boolean = false
) extends EnumeratingCofaceSimplexStream(metricSpace, keepCriterion, maxFiltrationValue, filtrationValueOverride):
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case 0 =>
      currentDimensionCache = metricSpace.elements.map(v => Simplex(v)).to(immutable.Queue)
      currentDimension = 0
      lastDimensionCache = IndexedSeq.empty[Simplex[Int]]
      currentDimensionCache.iterator
    // Bounded at metricSpace.size, same reasoning as EnumeratingCofaceSimplexStream's own iterateDimension --
    // see that class's comment and StratifiedCellStream's doc.
    case d if d >= 1 && d < metricSpace.size =>
      if currentDimension != d - 1 then
        // we don't have a good cache, just generate entire previous dimension and deal with it
        lastDimensionCache = sortedByFiltration(
          (0 until BinomialCoefficient.value(metricSpace.size, d).toInt).toSeq
            .flatMap { ix =>
              Some(simplexIndexing(ix, d)).filter(keptByThresholdAndCriterion)
            }
        )
      else lastDimensionCache = currentDimensionCache.toIndexedSeq
      // now we have a known good lastDimensionCache
      val rawCandidates: IndexedSeq[Simplex[Int]] =
        for
          spx <- lastDimensionCache
          i <- metricSpace.elements.filter(j => j < spx.min)
        yield spx + i
      if parallelFiltrationValue then
        // Side-effect-only: populates filtrationValueCache/CechFiltration's own cache as a side effect of
        // each applyOrElse call, in parallel; the returned values themselves are discarded, exactly the
        // same "parallel compute, sequential-cache-already-safe" pattern used elsewhere in this arc, except
        // here the safety comes from the TrieMap swap above rather than a separate sequential merge step.
        rawCandidates.par.foreach(c => filtrationValue.applyOrElse(c, (_: Simplex[Int]) => Double.NaN))
      currentDimensionCache = sortedByFiltration(
        rawCandidates.filter(keptByThresholdAndCriterion)
      ).to(immutable.Queue) // we _would_ want to avoid creating the entire thing and sort it
      currentDimensionCache.iterator
  }

/** An alternate Vietoris-Rips coface-generation strategy, independent of `EnumeratingCofaceSimplexStream`'s
  * combinatorial-number-system enumeration: cofaces are generated "in order" directly from the metric space's own
  * structure. A cross-validation baseline for the canonical VR streams, not a speed-competitive production engine in
  * its own right.
  */
private[tda4j] class InorderCofaceSimplexStream(
  metricSpace: FiniteMetricSpace[Int],
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true },
  maxFiltrationValue: Option[Double] = None
) extends EnumeratingCofaceSimplexStream(metricSpace, keepCriterion, maxFiltrationValue):
  def inOrderCofaceIterator(spx: Simplex[Int]): Iterator[Simplex[Int]] =
    if spx.isEmpty then metricSpace.elements.iterator.map(s => Simplex(s))
    else
      val alpha = filtrationValue(spx)
      val alpha0 = if spx.size > 0 then filtrationValue(spx.tail) else smallest
      val alpha1 = if spx.size > 1 then filtrationValue(spx.dropIndex(1)) else smallest
      def localMin(s: Simplex[Int]): Int =
        if s.nonEmpty then s.min
        else metricSpace.elements.max + 1
      Iterator.concat(
        // first, all the vertices that come before the first vertex - case 1
        for
          i <- metricSpace.elements.filter(j => j < localMin(spx))
          if spx.map(j => metricSpace.distance(i, j)).max <= alpha
        yield spx + i,
        // next, if filtrationValue drops when eliminating the first vertex
        if alpha > alpha0 then
          for
            i <- metricSpace.elements.filter(j => (localMin(spx) < j) && (j < localMin(spx.tail)))
            newSpx: Simplex[Int] = spx + i
            if filtrationValue(newSpx) == alpha
          yield newSpx
        else Iterable.empty[Simplex[Int]],
        // finally, if filtrationValue drops both when eliminating the first and the second vertex
        // in this case, the edge between first and second is the only full-length edge
        if (alpha > alpha0) && (alpha > alpha1) && (spx.size > 1) then
          for
            i <- metricSpace.elements.filter(j => (localMin(spx.tail) < j) && (j < localMin(spx.drop(2))))
            if spx.map(j => metricSpace.distance(i, j)).max < alpha
          yield spx + i
        else Iterable.empty
      )
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case 0 =>
      currentDimensionCache = metricSpace.elements.map(v => Simplex(v)).to(immutable.Queue)
      currentDimension = 0
      lastDimensionCache = IndexedSeq.empty
      currentDimensionCache.iterator
    case 1 =>
      currentDimensionCache = sortedByFiltration(edges).to(immutable.Queue)
      currentDimension = 1
      lastDimensionCache = metricSpace.elements.map(v => Simplex(v)).toIndexedSeq
      currentDimensionCache.iterator
    // Bounded at metricSpace.size, same reasoning as EnumeratingCofaceSimplexStream's own iterateDimension --
    // see that class's comment and StratifiedCellStream's doc.
    case d if d >= 2 && d < metricSpace.size =>
      if currentDimension != d - 1 then
        // we don't have a good cache, just generate entire previous dimension and deal with it
        lastDimensionCache = sortedByFiltration(
          (0 until BinomialCoefficient.value(metricSpace.size, d).toInt).toSeq
            .flatMap { ix =>
              Some(simplexIndexing(ix, d)).filter(keptByThresholdAndCriterion)
            }
        )
      else lastDimensionCache = currentDimensionCache.toIndexedSeq
      // now we have a known good lastDimensionCache
      currentDimensionCache = immutable.Queue.empty
      currentDimension = d
      for
        spx <- lastDimensionCache.iterator
        newSpx <- inOrderCofaceIterator(spx)
        // inOrderCofaceIterator's own alpha/alpha0/alpha1 logic is purely about the VR flag condition
        // relative to spx's own faces, with no awareness of a global threshold -- filtered here, same as
        // keepCriterion always was.
        if keptByThresholdAndCriterion(newSpx)
      yield
        currentDimensionCache = currentDimensionCache appended newSpx
        newSpx
  }

/** A reference Vietoris-Rips construction following Rieser's New-VR algorithm ("A New Construction of the Vietoris-Rips
  * Complex", arXiv:2301.07191v3, Algorithms 1-4), kept close to the paper as a baseline for the faster constructions.
  * Built eagerly, layer by layer (layer `k + 1` from layer `k` and its candidate lists), the breadth-first reading of
  * the paper's recursion, so any dimension can be served in any order.
  *
  * `maxFiltrationValue` (default `Infinity`) defines the graph whose clique complex is built: `{i, j}` is an edge iff
  * `distance(i, j) <= maxFiltrationValue`. The `largestNeighbor` table (`L` in Algorithm 2) only shortens scans. Every
  * simplex is reached exactly once, so nothing needs deduplicating. Unlike Algorithm 4, which always includes the
  * edges, `maxDimension` is the top simplex dimension served, so `maxDimension = 0` gives the vertices only.
  */
private[tda4j] class IncrementalVietorisRipsSimplexStream(
  metricSpace: FiniteMetricSpace[Int],
  val maxDimension: Int,
  // Same default as EnumeratingCofaceSimplexStream's identical parameter (see there): resolved to
  // metricSpace.minimumEnclosingRadius, a free optimization here (this class's VR complex is provably
  // unaffected below that radius), not a truncation. Pass `Some(Double.PositiveInfinity)` to opt out.
  maxFiltrationValue: Option[Double] = None,
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true },
  /** Exposed purely so `IncrementalVietorisRipsSpec` can pin that `L` is a non-load-bearing optimization on top of
    * Table-Lookup's definition: disabling it must never change `byDimension`, only how it gets computed.
    */
  useLargestNeighborBound: Boolean = true
) extends EnumeratingCofaceSimplexStream(metricSpace, keepCriterion):

  override def homologyDegreeLimit: Option[Int] = Some(maxDimension - 1)

  private val resolvedMaxFiltrationValue: Double =
    maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

  private def isEdge(i: Int, j: Int): Boolean =
    metricSpace.distance(i, j) <= resolvedMaxFiltrationValue

  /** Algorithm 1, Upper-Neighbors(G, v). */
  private def upperNeighbors(v: Int): SortedSet[Int] =
    SortedSet.from(metricSpace.elements.filter(w => w > v && isEdge(v, w)))

  /** The table `L` used by Algorithm 2, precomputed once per vertex. */
  private lazy val largestNeighbor: Map[Int, Int] =
    metricSpace.elements.map(v => v -> upperNeighbors(v).maxOption.getOrElse(v)).toMap

  /** Algorithm 2 (Table-Lookup). `N` is always sorted ascending by construction (it is either `upperNeighbors(v)` or a
    * filtered subset thereof), so `N.maxOption` is `end(N)` in the paper's notation.
    */
  private def tableLookup(N: SortedSet[Int], v: Int): SortedSet[Int] =
    val bound =
      if useLargestNeighborBound then math.min(N.maxOption.getOrElse(v), largestNeighbor(v))
      else N.maxOption.getOrElse(v)
    N.filter(w => w > v && w <= bound && isEdge(v, w))

  /** Algorithm 3 (New-Add-Cofaces), one layer per call. */
  private def addCofaces(
    tau: Simplex[Int],
    N: SortedSet[Int],
    buckets: IndexedSeq[mutable.ArrayBuffer[Simplex[Int]]]
  ): Unit =
    buckets(tau.size - 1) += tau
    if tau.size - 1 < maxDimension then
      for v <- N do
        val sigma = tau + v
        if keepCriterion.applyOrElse(sigma, (_: Simplex[Int]) => true) then
          addCofaces(sigma, tableLookup(N, v), buckets)

  /** Algorithm 4 (New-VR), applied once and bucketed by dimension. */
  private lazy val byDimension: IndexedSeq[Seq[Simplex[Int]]] =
    val buckets = IndexedSeq.fill(maxDimension + 1)(mutable.ArrayBuffer.empty[Simplex[Int]])
    for u <- metricSpace.elements do addCofaces(Simplex(u), upperNeighbors(u), buckets)
    buckets.map(b => sortedByFiltration(b.toSeq))

  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if d >= 0 && d <= maxDimension => byDimension(d).iterator
  }
