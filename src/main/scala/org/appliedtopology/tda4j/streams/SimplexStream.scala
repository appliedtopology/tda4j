package org.appliedtopology.tda4j

import scala.collection.{immutable, mutable}
import scala.collection.immutable.{Map, Seq, SortedSet}

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
    // One `applyOrElse` per cell, not `isDefinedAt` and then `apply`: a memoized `fv` answers from its cache without
    // checking the cell's domain, and each check or lookup of a simplex walks its vertices.
    val undefined: C => Any = _ => Undefined
    new Ordering[C]:
      private def byDimensionThenTieBreak(x: C, y: C): Int =
        Ordering.Int.compare(dim(x), dim(y)) match
          case 0  => tieBreak.compare(x, y)
          case dc => dc
      def compare(x: C, y: C): Int =
        val fx = fv.applyOrElse[C, Any](x, undefined)
        if fx.asInstanceOf[AnyRef] eq Undefined then byDimensionThenTieBreak(x, y)
        else
          val fy = fv.applyOrElse[C, Any](y, undefined)
          if fy.asInstanceOf[AnyRef] eq Undefined then byDimensionThenTieBreak(x, y)
          else
            java.lang.Double.compare(fy.asInstanceOf[Double], fx.asInstanceOf[Double]) match
              case 0  => byDimensionThenTieBreak(x, y)
              case fc => fc

  /** Marks a cell outside a filtration function's domain. */
  private object Undefined

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
open class ExplicitStream[VertexT: Ordering, FiltrationT](
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

open class ExplicitStreamBuilder[VertexT: Ordering, FiltrationT](using
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

open class FilteredSimplexOrdering[VertexT, FiltrationT](
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

/** A levelwise stream that builds dimension `d + 1` from the simplices it kept in dimension `d` (their cofaces),
  * caching the two dimensions it is working between. The machinery behind [[VietorisRips]], [[Cech]], [[Witness]],
  * [[Dowker]], [[DtmRips]] and [[SparseRips]]; extend it (usually through [[RipserCofaceSimplexStream]]'s
  * `filtrationValueOverride`) to build a new filtered complex on the same coface loop. Any stream must keep the
  * ordering contract of [[CellStream]].
  */
trait CofaceSimplexStream[VertexT: Ordering, FiltrationT: Filterable]
    extends LevelwiseSimplexStream[VertexT, FiltrationT]:

  def currentDimension: Int

  def lastDimensionCache: Seq[Simplex[VertexT]]

  def currentDimensionCache: Seq[Simplex[VertexT]]

  /** Which simplices the stream keeps, besides the threshold: a vertex, an edge or a higher simplex is kept when this
    * returns `true` (a simplex it is not defined at is kept).
    *
    * '''The criterion must be closed under faces''': every face of a simplex it keeps, a vertex or an edge among them,
    * is kept too. That is what makes the kept simplices a simplicial complex, which every engine needs for `∂∂ = 0`. It
    * is not checked, and a criterion that breaks it gives a stream whose cells, order and bars are not defined.
    */
  def keepCriterion: PartialFunction[Simplex[VertexT], Boolean]

object CofaceSimplexStream:

  /** The `keepCriterion` that keeps every simplex, and the default of the coface streams. A stream whose criterion is
    * this very function builds its dimensions as arrays ([[FlatLayerStream]]) without asking about any simplex; any
    * other criterion is asked about each candidate as a [[Simplex]].
    */
  val keepAll: PartialFunction[Simplex[Int], Boolean] = { case _ => true }

/** `stream` with simplices up to dimension `maxDim` (a simplex dimension; [[Truncated.ofCofaces]] takes the homological
  * degree instead).
  */
open class LimitedCofaceSimplexStream(stream: CofaceSimplexStream[Int, Double], maxDim: Int)
    extends CofaceSimplexStream[Int, Double]
    with FlatLayerStream
    with DoubleFiltration[Simplex[Int]]():
  override def flatLayers: Option[Iterator[FlatLayerStream.Layer]] = stream match
    case flat: FlatLayerStream => flat.flatLayers.map(_.take(maxDim + 1))
    case _                     => None

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

/** Vietoris-Rips (or, with `filtrationValueOverride`, any filtration on the same vertex set that is monotone along
  * faces), each dimension enumerated in full by [[SimplexIndexing]]'s combinatorial number system and filtered by
  * `keepCriterion` and `maxFiltrationValue`. What [[VietorisRips]] chooses between, with [[RipserCofaceSimplexStream]].
  *
  * Enumerating every vertex set of a dimension holds only while there are at most `Int.MaxValue` of them (a `Range` of
  * indices); [[RipserCofaceSimplexStream]], the default, builds each dimension from the cofaces of the cells it kept
  * and has no such limit. The tie-break of [[filtrationOrdering]] is [[SimplexIndexing.colexOrdering]], which has none
  * either.
  */
open class EnumeratingCofaceSimplexStream(
  val metricSpace: FiniteMetricSpace[Int],
  var keepCriterion: PartialFunction[Simplex[Int], Boolean] = CofaceSimplexStream.keepAll,
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
    edge = Simplex(i, j)
    if criterionKeeps(edge)
  yield edge

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
  // FiltrationCache (a TrieMap on the JVM), not mutable.HashMap: a plain HashMap's getOrElseUpdate is not safe to call
  // concurrently, and parallelFiltrationValue's pre-warm step (below) does exactly that -- TrieMap's own getOrElseUpdate is
  // a genuine drop-in backed by a lock-free Ctrie. See .claude/WORKLOG-parallelization-survey.md item 2 (Cech).
  private val filtrationValueCache = FiltrationCache.empty[Simplex[Int], Double]

  private val builtFiltrationValue: PartialFunction[Simplex[Int], Double] =
    filtrationValueOverride.getOrElse {
      val base = FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)
      MemoizedFiltration(filtrationValueCache, base.isDefinedAt)(base(_))
    }

  override val filtrationValue: PartialFunction[Simplex[Int], Double] = builtFiltrationValue

  /** Whether the value of a simplex is the default one, the largest distance between two of its vertices: no
    * `filtrationValueOverride`, and no subclass that replaced `filtrationValue`.
    */
  protected final def filtrationIsDefault: Boolean =
    filtrationValueOverride.isEmpty && (filtrationValue eq builtFiltrationValue)

  /** Filtration value reversed, then dimension, then colexicographic order of the vertex sets (their combinatorial
    * index), the refinement Ripser's apparent pairs are defined with. A total order: without a tie-break, a triangle
    * and its longest edge would compare equal and collide in the reduction. `iterateDimension` sorts by this same
    * ordering reversed, so iteration order and pivot order agree.
    */
  override val filtrationOrdering: Ordering[Simplex[Int]] =
    FiltrationOrdering.canonical(filtrationValue, _.size, SimplexIndexing.colexOrdering)

  lazy val simplexIndexing: SimplexIndexing = SimplexIndexing(metricSpace.size)

  /** `cells` sorted by `filtrationOrdering.reverse`, computing each cell's value and tie-break key once for the sort
    * rather than on every comparison. The keys sit in primitive arrays and the sort moves positions, so no `Simplex` is
    * hashed (a `SortedSet` hashes by walking its tree). The tie-break key is the combinatorial index while the indices
    * of this many vertices fit a `Long`, and the vertex array beyond that.
    */
  protected def sortedByFiltration(cells: IterableOnce[Simplex[Int]]): Vector[Simplex[Int]] =
    val all = mutable.ArrayBuffer.from(cells.iterator)
    val n = all.length
    val values = new Array[Double](n)
    val sizes = new Array[Int](n)
    var allDefined = true
    var maxSize = 0
    var k = 0
    while k < n do
      val cell = all(k)
      var defined = true
      values(k) = filtrationValue.applyOrElse(
        cell,
        (_: Simplex[Int]) =>
          defined = false
          0.0
      )
      allDefined &&= defined
      sizes(k) = cell.size
      maxSize = math.max(maxSize, sizes(k))
      k += 1
    if allDefined then
      // The reverse of the tie-break of `filtrationOrdering`: colexicographic descending, by index or by vertices.
      val laterFirst: (Int, Int) => Int =
        if SimplexIndexing.fitsLong(metricSpace.size, maxSize) then
          val indices = Array.tabulate(n)(j => simplexIndexing(all(j)))
          (a, b) => java.lang.Long.compare(indices(b), indices(a))
        else
          val vertices = Array.tabulate(n)(j => all(j).underlying.toArray)
          (a, b) => SimplexIndexing.colexCompare(vertices(b), vertices(a))
      val order: Array[Integer] = Array.tabulate(n)(Integer.valueOf(_))
      // The reverse of `filtrationOrdering` (value reversed, then size, then index): value ascending, then size and
      // index descending.
      java.util.Arrays.sort(
        order,
        (a: Integer, b: Integer) =>
          val byValue = java.lang.Double.compare(values(a), values(b))
          if byValue != 0 then byValue
          else
            val bySize = Integer.compare(sizes(b), sizes(a))
            if bySize != 0 then bySize else laterFirst(a, b)
      )
      Vector.tabulate(n)(j => all(order(j)))
    else
      // A cell outside the filtration function's domain is ordered by size and index alone (`FiltrationOrdering`).
      val fvCache = mutable.HashMap.empty[Simplex[Int], Option[Double]]
      val memoFv: PartialFunction[Simplex[Int], Double] = new PartialFunction[Simplex[Int], Double]:
        def isDefinedAt(s: Simplex[Int]): Boolean = fvCache.getOrElseUpdate(s, filtrationValue.lift(s)).isDefined
        def apply(s: Simplex[Int]): Double = fvCache.getOrElseUpdate(s, filtrationValue.lift(s)).get
      all.toVector.sorted(using FiltrationOrdering.canonical(memoFv, _.size, SimplexIndexing.colexOrdering).reverse)

  /** A candidate is kept iff both the caller's own `keepCriterion` AND the threshold accept it -- one place deciding
    * "is this cell kept," per `sortedByFiltration`'s own note about not duplicating filtration-value lookups across
    * independent filters.
    */
  protected def keptByThresholdAndCriterion(spx: Simplex[Int]): Boolean =
    criterionKeeps(spx) &&
      keptValue(filtrationValue.applyOrElse(spx, (_: Simplex[Int]) => Double.PositiveInfinity))

  /** Whether `keepCriterion` accepts `spx` (a simplex it is not defined at is accepted). */
  protected final def criterionKeeps(spx: Simplex[Int]): Boolean =
    keepCriterion.applyOrElse(spx, (_: Simplex[Int]) => true)

  /** Whether `keepCriterion` is [[CofaceSimplexStream.keepAll]], so that no simplex has to be built to ask it. */
  protected final def keepsEverything: Boolean = keepCriterion eq CofaceSimplexStream.keepAll

  /** Whether a simplex of filtration value `value` is within the threshold: the half of [[keptByThresholdAndCriterion]]
    * that depends on the value alone, for the streams that build their dimensions on arrays ([[FlatLayerStream]]).
    */
  protected def keptValue(value: Double): Boolean = value <= resolvedMaxFiltrationValue

  // Bounded at metricSpace.size (a d-simplex needs d+1 distinct vertices, and there are none beyond that): a
  // real bound, not just a defensive one -- StratifiedCellStream's default .iterator relies on isDefinedAt
  // eventually staying false (an unbounded catch-all here was the actual root cause of the "some engines' .iterator
  // hangs / eventually crashes" bug, see StratifiedCellStream's doc).
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case 0 => metricSpace.elements.map(v => Simplex(v)).filter(criterionKeeps).iterator
    case 1 => sortedByFiltration(edges).iterator
    case d if d >= 0 && d < metricSpace.size =>
      // first, generate all simplices of this dimension
      sortedByFiltration(
        (0 until SimplexIndexing.binomialInt(metricSpace.size, d + 1)).toSeq
          .flatMap { ix =>
            Some(simplexIndexing(ix, d + 1)).filter(keptByThresholdAndCriterion)
          }
      ).iterator
  }

/** Vietoris-Rips, or any filtration monotone along faces given as `filtrationValueOverride`, built dimension by
  * dimension from the cofaces of the simplices kept in the dimension below (the loop [[Cech]], [[Witness]], [[Dowker]],
  * [[DtmRips]] and [[SparseRips]] reuse). `keepCriterion` and `maxFiltrationValue` decide what is kept; the criterion
  * must be closed under faces (see [[CofaceSimplexStream.keepCriterion]]). It is asked about each candidate that has a
  * kept facet, whether the stream builds its dimensions with the coface loop or with [[flatGenerator]], which cuts each
  * dimension to what the criterion keeps before generating the next from it.
  */
open class RipserCofaceSimplexStream(
  metricSpace: FiniteMetricSpace[Int],
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = CofaceSimplexStream.keepAll,
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
) extends EnumeratingCofaceSimplexStream(metricSpace, keepCriterion, maxFiltrationValue, filtrationValueOverride)
    with FlatLayerStream:
  import RipserCofaceSimplexStream.FlatFiltration

  /** How this stream computes a value from the vertices in an array, so that it can build its dimensions as arrays
    * ([[FlatLayerStream]]) without a [[Simplex]] per candidate. `None` builds cells. A stream whose `filtrationValue`
    * is not the default one overrides this to match it ''exactly'' (the same value for every simplex, bit for bit), and
    * overrides [[keptValue]] if it keeps a different set of values than `<= maxFiltrationValue`.
    */
  protected def flatFiltration: Option[FlatFiltration] =
    if filtrationIsDefault then Some(FlatFiltration.flag(metricSpace)) else None

  /** The generator that builds this stream's dimensions from the edges up, in filtration order and without sorting
    * ([[LayeredInorderSimplexStream.FlagGenerator]]), for a stream that is the flag complex of a weighted graph: a
    * simplex is a set of vertices whose pairs are all edges, and its value is the largest weight. It is used instead of
    * the coface loop when the vertices are `0 until n`, with `keepCriterion` applied to each generated dimension;
    * `None` (the default) is the coface loop. A stream that gives one must give, for every simplex, the cell and the
    * value of its [[flatFiltration]] (the stream's tests compare the two). A fresh generator is asked for once per
    * stream. The default gives one for the edge-collapsed graph of an [[EdgeCollapsedMetricSpace]] with the default
    * filtration, from its surviving edges, so that the pairs the collapse removed are never scanned, and for any other
    * metric space with the default filtration of up to `MaxGeneratorVertices` points (the Vietoris-Rips complex), where
    * it replaces the sort of each dimension.
    */
  protected def flatGenerator: Option[LayeredInorderSimplexStream.FlagGenerator] = metricSpace match
    case collapsed: EdgeCollapsedMetricSpace if filtrationIsDefault =>
      val as = LayeredInorderSimplexStream.IntBuffer()
      val bs = LayeredInorderSimplexStream.IntBuffer()
      val weights = LayeredInorderSimplexStream.DoubleBuffer()
      collapsed.edges.foreach { (a, b, weight) =>
        as += a
        bs += b
        weights += weight
      }
      Some(
        LayeredInorderSimplexStream.FlagGenerator
          .ofEdges(metricSpace.size, as.result, bs.result, weights.result, resolvedMaxFiltrationValue)
      )
    case _ if filtrationIsDefault && metricSpace.size <= RipserCofaceSimplexStream.MaxGeneratorVertices =>
      Some(
        LayeredInorderSimplexStream.Generator(
          metricSpace.size,
          LayeredInorderSimplexStream.distanceOf(metricSpace),
          resolvedMaxFiltrationValue
        )
      )
    case _ => None

  private lazy val generator: Option[LayeredInorderSimplexStream.FlagGenerator] =
    if java.util.Arrays.equals(vertexIds, Array.range(0, vertexIds.length)) then flatGenerator
    else None

  // The dimension built last by the generator and its `root` (the first longest edge of each simplex, in the generator's
  // order). The next dimension is generated from these and that dimension's flat layer, which is the generator's with
  // each tied run reversed: the stream keeps 4 bytes a simplex of the top dimension, not a second copy of it.
  private var generatedRoots: Option[(Int, Array[Int])] = None

  override def flatLayers: Option[Iterator[FlatLayerStream.Layer]] =
    flatFiltration.map(f => Iterator.range(0, metricSpace.size).map(flatLayer(f, _)).takeWhile(_.size > 0))

  // The two dimensions last built: `flatPrevious` is the one below `flatCurrent`.
  private var flatPrevious: Option[FlatLayerStream.Layer] = None
  private var flatCurrent: Option[FlatLayerStream.Layer] = None

  private lazy val vertexIds: Array[Int] = metricSpace.elements.toArray.sorted

  private def flatLayer(f: FlatFiltration, d: Int): FlatLayerStream.Layer =
    flatCurrent.filter(_.dim == d).orElse(flatPrevious.filter(_.dim == d)).getOrElse {
      val parent = if d == 0 then None else Some(flatLayer(f, d - 1))
      val layer = parent.fold(flatVertices(f))(p => generator.fold(flatCofaces(f, p))(generatedLayer(_, d)))
      flatPrevious = parent
      flatCurrent = Some(layer)
      if d == 1 && generator.isEmpty then buildNeighbours(layer)
      layer
    }

  // Dimension `d >= 1` from the generator, as a flat layer: its tied runs, which it lists colexicographically descending,
  // are reversed to the order a layer is in (ascending).
  private def generatedLayer(g: LayeredInorderSimplexStream.FlagGenerator, d: Int): FlatLayerStream.Layer =
    val raw = pruned(if d == 1 then g.edges else g.cofacets(generatorLayer(g, d - 1)))
    generatedRoots = Some((d, raw.root))
    val values = Array.tabulate(raw.size)(g.diameter(raw, _))
    FlatLayerStream.Layer(d, reversedTies(raw.vertices, values, d + 1), values)

  // `layer` without the simplices `keepCriterion` rejects, in the same order. The next dimension is generated from what
  // is left, which is the whole next dimension when the criterion is closed under faces (every face of a simplex it
  // keeps is kept): a simplex is generated by its first full-diameter facet, which is then kept.
  private def pruned(layer: LayeredInorderSimplexStream.Layer): LayeredInorderSimplexStream.Layer =
    if keepsEverything then layer else criterionKept(layer)

  private def criterionKept(layer: LayeredInorderSimplexStream.Layer): LayeredInorderSimplexStream.Layer =
    val width = layer.dim + 1
    val vertices = new mutable.ArrayBuilder.ofInt
    val roots = new mutable.ArrayBuilder.ofInt
    var kept = 0
    var i = 0
    while i < layer.size do
      if criterionKeeps(FlatLayerStream.simplexOf(layer.vertices, i * width, width)) then
        vertices.addAll(layer.vertices, i * width, width)
        roots += layer.root(i)
        kept += 1
      i += 1
    LayeredInorderSimplexStream.Layer(layer.dim, kept, vertices.result(), roots.result())

  // The generator's layer of dimension `d`: from the flat layer and roots kept, or built again from the edges (when
  // dimensions are asked for out of order).
  private def generatorLayer(g: LayeredInorderSimplexStream.FlagGenerator, d: Int): LayeredInorderSimplexStream.Layer =
    val kept = for
      (dim, root) <- generatedRoots if dim == d
      flat <- flatCurrent if flat.dim == d
    yield LayeredInorderSimplexStream.Layer(d, flat.size, reversedTies(flat.vertices, flat.values, d + 1), root)
    kept.getOrElse((2 to d).foldLeft(pruned(g.edges))((layer, _) => pruned(g.cofacets(layer))))

  // `vertices`, `width` to a simplex, with every run of equal `values` in the reverse order: its own inverse.
  private def reversedTies(vertices: Array[Int], values: Array[Double], width: Int): Array[Int] =
    val reversed = new Array[Int](vertices.length)
    var start = 0
    while start < values.length do
      var end = start + 1
      while end < values.length && java.lang.Double.compare(values(start), values(end)) == 0 do end += 1
      var i = start
      while i < end do
        System.arraycopy(vertices, i * width, reversed, (end - 1 - (i - start)) * width, width)
        i += 1
      start = end
    reversed

  private def flatKept(value: Double, vertices: Array[Int], from: Int, width: Int): Boolean =
    keptValue(value) && (keepsEverything || criterionKeeps(FlatLayerStream.simplexOf(vertices, from, width)))

  private def flatVertices(f: FlatFiltration): FlatLayerStream.Layer =
    val kept = new mutable.ArrayBuilder.ofInt
    val values = new mutable.ArrayBuilder.ofDouble
    val tuple = new Array[Int](1)
    vertexIds.foreach { v =>
      tuple(0) = v
      val value = f.value(tuple, 0, 1, Double.NaN)
      if flatKept(value, tuple, 0, 1) then
        kept += v
        values += value
    }
    RipserCofaceSimplexStream.ordered(0, kept.result(), values.result())

  /** Dimension `parent.dim + 1`: every vertex below the smallest of a simplex of `parent` (and, from dimension 2 on,
    * adjacent to all of it) makes a candidate, whose value is asked in chunks, in parallel if
    * `parallelFiltrationValue`.
    */
  private def flatCofaces(f: FlatFiltration, parent: FlatLayerStream.Layer): FlatLayerStream.Layer =
    val width = parent.dim + 2
    val parentWidth = width - 1
    val table = if parent.dim >= 1 then keptEdgeNeighbours else None
    val words = (vertexBound + 63) >>> 6
    val common = new Array[Long](words)
    val chunk = RipserCofaceSimplexStream.ChunkSize
    val candidates = new Array[Int](chunk * width)
    val parentValues = new Array[Double](chunk)
    val values = new Array[Double](chunk)
    var count = 0
    val keptVertices = new mutable.ArrayBuilder.ofInt
    val keptValues = new mutable.ArrayBuilder.ofDouble

    def flush(): Unit =
      RipserCofaceSimplexStream.evaluate(f, width, candidates, parentValues, values, count, parallelFiltrationValue)
      var c = 0
      while c < count do
        if flatKept(values(c), candidates, c * width, width) then
          keptVertices.addAll(candidates, c * width, width)
          keptValues += values(c)
        c += 1
      count = 0

    def add(vertex: Int, parentIndex: Int): Unit =
      if count == chunk then flush()
      val at = count * width
      candidates(at) = vertex
      System.arraycopy(parent.vertices, parentIndex * parentWidth, candidates, at + 1, parentWidth)
      parentValues(count) = parent.values(parentIndex)
      count += 1

    var k = 0
    while k < parent.size do
      val smallest = parent.vertices(k * parentWidth)
      table match
        case None =>
          var i = 0
          while i < vertexIds.length && vertexIds(i) < smallest do
            add(vertexIds(i), k)
            i += 1
        case Some(rows) =>
          // Only the words up to the one holding `smallest` can have a bit below it.
          val lastWord = smallest >>> 6
          System.arraycopy(rows(smallest), 0, common, 0, lastWord + 1)
          var j = 1
          while j < parentWidth do
            val row = rows(parent.vertices(k * parentWidth + j))
            var w = 0
            while w <= lastWord do
              common(w) &= row(w)
              w += 1
            j += 1
          var w = 0
          while w <= lastWord do
            var bits = if w == lastWord then common(w) & ((1L << (smallest & 63)) - 1L) else common(w)
            while bits != 0L do
              add((w << 6) + java.lang.Long.numberOfTrailingZeros(bits), k)
              bits &= bits - 1L
            w += 1
      k += 1
    flush()
    RipserCofaceSimplexStream.ordered(parent.dim + 1, keptVertices.result(), keptValues.result())

  // Bit `i` of `keptEdgeNeighbours(j)` is set when the edge {i, j} is kept. A kept simplex has all its edges kept (the
  // filtration is monotone along faces), so a coface of a simplex can only add a vertex adjacent to every vertex of
  // it: the candidates of dimension d >= 2 are read off these rows instead of being every smaller vertex, and only
  // those get a filtration value. Built from dimension 1 as it is computed, or on first use if dimensions are asked
  // for out of order. Absent for a vertex set too large for the table (then every smaller vertex is a candidate).
  private var keptEdgeNeighbours: Option[Array[Array[Long]]] = None
  private var neighboursBuilt = false

  /** The table needs `n * ceil(n / 64)` words: at most 2^25 (256 MB), about 40000 vertices. */
  private val maxNeighbourWords = 1L << 25

  private val vertexBound: Int = metricSpace.elements.foldLeft(0)((bound, v) => math.max(bound, v + 1))

  private def buildNeighbours(kept: Iterable[Simplex[Int]]): Unit =
    neighboursBuilt = true
    val words = (vertexBound + 63) >>> 6
    keptEdgeNeighbours =
      if vertexBound.toLong * words > maxNeighbourWords then None
      else
        val table = Array.ofDim[Long](vertexBound, words)
        kept.foreach { edge =>
          val vertices = edge.iterator
          val a = vertices.next()
          val b = vertices.next()
          table(a)(b >>> 6) |= 1L << (b & 63)
          table(b)(a >>> 6) |= 1L << (a & 63)
        }
        Some(table)

  private def buildNeighbours(edges: FlatLayerStream.Layer): Unit =
    neighboursBuilt = true
    val words = (vertexBound + 63) >>> 6
    keptEdgeNeighbours =
      if vertexBound.toLong * words > maxNeighbourWords then None
      else
        val table = Array.ofDim[Long](vertexBound, words)
        var k = 0
        while k < edges.size do
          val a = edges.vertices(2 * k)
          val b = edges.vertices(2 * k + 1)
          table(a)(b >>> 6) |= 1L << (b & 63)
          table(b)(a >>> 6) |= 1L << (a & 63)
          k += 1
        Some(table)

  /** Every simplex `spx + i` with `i` below the smallest vertex of `spx`, for `spx` in `layer`, and `i` adjacent to all
    * of `spx` when the neighbour table is there.
    */
  private def cofaceCandidates(layer: IndexedSeq[Simplex[Int]]): IndexedSeq[Simplex[Int]] =
    keptEdgeNeighbours match
      case None =>
        for
          spx <- layer
          i <- metricSpace.elements.filter(j => j < spx.min)
        yield spx + i
      case Some(table) =>
        val words = (vertexBound + 63) >>> 6
        val common = new Array[Long](words)
        val out = Vector.newBuilder[Simplex[Int]]
        layer.foreach { spx =>
          val vertices = spx.iterator
          val smallest = vertices.next()
          System.arraycopy(table(smallest), 0, common, 0, words)
          while vertices.hasNext do
            val row = table(vertices.next())
            var w = 0
            while w < words do
              common(w) &= row(w)
              w += 1
          // Only the bits below `smallest`.
          val lastWord = smallest >>> 6
          var w = 0
          while w <= lastWord do
            var bits = if w == lastWord then common(w) & ((1L << (smallest & 63)) - 1L) else common(w)
            while bits != 0L do
              out += spx + ((w << 6) + java.lang.Long.numberOfTrailingZeros(bits))
              bits &= bits - 1L
            w += 1
        }
        out.result()

  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] =
    val vertices: PartialFunction[Int, Iterator[Simplex[Int]]] = { case 0 =>
      currentDimensionCache = metricSpace.elements.map(v => Simplex(v)).filter(criterionKeeps).to(immutable.Queue)
      currentDimension = 0
      lastDimensionCache = IndexedSeq.empty[Simplex[Int]]
      currentDimensionCache.iterator
    }
    vertices.orElse(flatFiltration.fold(cofacesOfSimplices)(cofacesOfLayers))

  // Dimension d >= 1 from the layers (`flatLayers`), as simplices in the stream's listing order.
  private def cofacesOfLayers(f: FlatFiltration): PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if d >= 1 && d < metricSpace.size =>
      val previousIsCached = currentDimension == d - 1
      val cells = flatLayer(f, d).simplicesInStreamOrder
      lastDimensionCache =
        if previousIsCached then currentDimensionCache.toIndexedSeq else flatLayer(f, d - 1).simplicesInStreamOrder
      currentDimensionCache = cells.to(immutable.Queue)
      currentDimension = d
      currentDimensionCache.iterator
  }

  // Dimension d >= 1 from cells: the candidates are Simplex values, looked up in the filtration function.
  private def cofacesOfSimplices: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    // Bounded at metricSpace.size, same reasoning as EnumeratingCofaceSimplexStream's own iterateDimension --
    // see that class's comment and StratifiedCellStream's doc.
    case d if d >= 1 && d < metricSpace.size =>
      if currentDimension != d - 1 then
        // we don't have a good cache, just generate entire previous dimension and deal with it
        lastDimensionCache = sortedByFiltration(
          (0 until SimplexIndexing.binomialInt(metricSpace.size, d)).toSeq
            .flatMap { ix =>
              Some(simplexIndexing(ix, d)).filter(keptByThresholdAndCriterion)
            }
        )
        if d == 2 then buildNeighbours(lastDimensionCache)
      else lastDimensionCache = currentDimensionCache.toIndexedSeq
      // now we have a known good lastDimensionCache
      if d >= 2 && !neighboursBuilt then
        buildNeighbours(for
          i <- metricSpace.elements
          j <- metricSpace.elements
          if i < j
          edge = Simplex(i, j)
          if keptByThresholdAndCriterion(edge)
        yield edge)
      val rawCandidates = cofaceCandidates(lastDimensionCache)
      if parallelFiltrationValue then
        // Side-effect-only: populates filtrationValueCache/CechFiltration's own cache as a side effect of
        // each applyOrElse call, in parallel; the returned values themselves are discarded, exactly the
        // same "parallel compute, sequential-cache-already-safe" pattern used elsewhere in this arc, except
        // here the safety comes from the TrieMap swap above rather than a separate sequential merge step.
        Parallel.foreach(rawCandidates)(c => filtrationValue.applyOrElse(c, (_: Simplex[Int]) => Double.NaN))
      currentDimensionCache = sortedByFiltration(
        rawCandidates.filter(keptByThresholdAndCriterion)
      ).to(immutable.Queue) // we _would_ want to avoid creating the entire thing and sort it
      currentDimension = d // so dimension d + 1 builds on this cache instead of enumerating every d-subset again
      if d == 1 then buildNeighbours(currentDimensionCache)
      currentDimensionCache.iterator
  }

object RipserCofaceSimplexStream:

  /** The most points for which the default `flatGenerator` is the layered in-order generator: its two bit matrices take
    * `n^2 / 4` bytes, 256 MB at this bound, the cap of the coface loop's neighbour table.
    */
  private val MaxGeneratorVertices = 1 << 15

  /** Candidates are valued, and kept or dropped, this many at a time. */
  private val ChunkSize = 1 << 15

  /** Candidates per task when the values are computed in parallel. */
  private val ParallelPart = 1 << 11

  /** The filtration value of a simplex computed from its vertices, which lie in an array: what lets a coface stream
    * build its dimensions on arrays ([[FlatLayerStream]]) instead of asking a function of [[Simplex]] values.
    *
    * It must give the value of the simplex's own `filtrationValue`, bit for bit; the stream's tests compare the two.
    * `value` is called from several threads at once when `parallelFiltrationValue` is set.
    */
  trait FlatFiltration:

    /** The value of the simplex with the ascending vertices `vertices(from until from + width)`. `parentValue` is the
      * value of the simplex without its smallest vertex (not a number for a vertex): a value that is the largest over
      * the edges of a simplex needs only the edges of the smallest vertex on top of it.
      */
    def value(vertices: Array[Int], from: Int, width: Int, parentValue: Double): Double

    /** The filtration to call for one run of candidates, from one thread at a time: the candidates of one parent come
      * together, parent's vertices after the new smallest one, so a filtration may keep what it computed for the parent
      * between two calls. The default is the filtration itself, which must then keep nothing.
      */
    def perRun: FlatFiltration = this

  object FlatFiltration:

    /** `function` asked about a `Simplex` built for each candidate (`+Infinity` where it is not defined): no faster
      * than the function, but the dimensions are still built on arrays.
      */
    def ofSimplices(function: PartialFunction[Simplex[Int], Double]): FlatFiltration =
      new FlatFiltration:
        def value(vertices: Array[Int], from: Int, width: Int, parentValue: Double): Double =
          function.applyOrElse(
            FlatLayerStream.simplexOf(vertices, from, width),
            (_: Simplex[Int]) => Double.PositiveInfinity
          )

    /** The Vietoris-Rips value: `0` for a vertex, otherwise the largest `distance` between two vertices. */
    def flag(metricSpace: FiniteMetricSpace[Int]): FlatFiltration =
      new FlatFiltration:
        def value(vertices: Array[Int], from: Int, width: Int, parentValue: Double): Double =
          if width == 1 then 0.0
          else
            // An edge has the vertex below it as its parent, whose value is not its own largest distance.
            var largest = if width == 2 then 0.0 else parentValue
            val first = vertices(from)
            var j = from + 1
            while j < from + width do
              val d = metricSpace.distance(first, vertices(j))
              if d > largest then largest = d
              j += 1
            largest

  /** `values(c)` for the `count` candidates `c`, with `width` vertices from `candidates(c * width)`. */
  private def evaluate(
    f: FlatFiltration,
    width: Int,
    candidates: Array[Int],
    parentValues: Array[Double],
    values: Array[Double],
    count: Int,
    parallel: Boolean
  ): Unit =
    def run(from: Int, until: Int): Unit =
      val g = f.perRun
      var c = from
      while c < until do
        values(c) = g.value(candidates, c * width, width, parentValues(c))
        c += 1
    if parallel && count >= 4 * ParallelPart then
      Parallel.foreach((0 until count by ParallelPart).map(from => (from, math.min(count, from + ParallelPart)))) {
        (from, until) => run(from, until)
      }
    else run(0, count)

  /** The layer of the cells with these vertices (`dim + 1` ascending ones each) and values, in the order of
    * [[FlatLayerStream.Layer]]: ascending value, equal values in ascending colexicographic order (the largest vertex
    * decides first).
    */
  private def ordered(dim: Int, vertices: Array[Int], values: Array[Double]): FlatLayerStream.Layer =
    val n = values.length
    val width = dim + 1
    val order = new LayerSorter(width, values, vertices).sorted()
    val sortedVertices = new Array[Int](vertices.length)
    val sortedValues = new Array[Double](n)
    var i = 0
    while i < n do
      System.arraycopy(vertices, order(i) * width, sortedVertices, i * width, width)
      sortedValues(i) = values(order(i))
      i += 1
    FlatLayerStream.Layer(dim, sortedVertices, sortedValues)

  /** The numbers `0 until values.length` in the order of the cells they stand for (cell `i` has the `width` vertices
    * from `vertices(i * width)`): ascending value, equal values in ascending colexicographic order of the vertices (the
    * largest vertex decides first), or, with `colex = false`, in ascending lexicographic order.
    */
  private[tda4j] def layerOrder(width: Int, values: Array[Double], vertices: Array[Int], colex: Boolean): Array[Int] =
    new LayerSorter(width, values, vertices, colex).sorted()

  // Ranges of at least this many cells are sorted by two tasks, one for each half.
  private val ParallelSortRun = 1 << 15

  /** A merge sort of the numbers `0 until values.length` by the cell they stand for, on primitives: a boxed sort with a
    * comparator took a third of the time of a large Cech complex.
    */
  private final class LayerSorter(width: Int, values: Array[Double], vertices: Array[Int], colex: Boolean = true):
    private val n = values.length
    // The bits of a value in an order that is the signed order of the longs, which is `Double.compare`'s (-0.0 before
    // 0.0, NaN last).
    private val keys = new Array[Long](n)
    private val order = new Array[Int](n)
    private val scratch = new Array[Int](n)
    locally {
      var i = 0
      while i < n do
        val bits = java.lang.Double.doubleToLongBits(values(i))
        keys(i) = bits ^ ((bits >> 63) & Long.MaxValue)
        order(i) = i
        i += 1
    }

    private def less(a: Int, b: Int): Boolean =
      val (ka, kb) = (keys(a), keys(b))
      if ka != kb then ka < kb
      else
        var c = 0
        if colex then
          var k = width - 1
          while c == 0 && k >= 0 do
            c = Integer.compare(vertices(a * width + k), vertices(b * width + k))
            k -= 1
        else
          var k = 0
          while c == 0 && k < width do
            c = Integer.compare(vertices(a * width + k), vertices(b * width + k))
            k += 1
        c < 0

    private def sort(lo: Int, hi: Int): Unit =
      if hi - lo <= 16 then
        var i = lo + 1
        while i < hi do
          val x = order(i)
          var j = i - 1
          while j >= lo && less(x, order(j)) do
            order(j + 1) = order(j)
            j -= 1
          order(j + 1) = x
          i += 1
      else
        val mid = (lo + hi) >>> 1
        // The halves touch `order` and `scratch` only inside their own ranges.
        if hi - lo >= ParallelSortRun then Parallel.both(sort(lo, mid), sort(mid, hi))
        else
          sort(lo, mid)
          sort(mid, hi)
        // Already in order when the largest of the left half is not above the smallest of the right.
        if less(order(mid), order(mid - 1)) then
          System.arraycopy(order, lo, scratch, lo, mid - lo)
          var l = lo
          var r = mid
          var out = lo
          while l < mid do
            if r < hi && less(order(r), scratch(l)) then
              order(out) = order(r)
              r += 1
            else
              order(out) = scratch(l)
              l += 1
            out += 1

    def sorted(): Array[Int] =
      sort(0, n)
      order

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
open class IncrementalVietorisRipsSimplexStream(
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
