package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

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
  /** The canonical `filtrationOrdering` shape every stream in this codebase's ordering contract must produce
    * (CLAUDE.md's stream rules 1/2): primary key filtration value REVERSED (smaller-under-this-ordering means younger
    * -- `Chain`'s pivot-selection machinery needs "smaller" to mean "younger"), then dimension ascending, then a
    * caller-supplied tie-break. A cell either side's `fv` is undefined for falls through to the dimension/tie-break
    * keys alone, matching every existing call site's behavior.
    *
    * `fv` stays a `PartialFunction`, not `C => Option[Double]`: every call site already had one on hand (no `.lift`
    * allocation needed), and this sits directly on `Chain.reduceBy`'s `SortedMap`/`PriorityQueue` comparison path --
    * the hottest loop in every engine.
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

class ExplicitStream[VertexT: Ordering, FiltrationT](
  protected val filtrationValues: Map[Simplex[VertexT], FiltrationT],
  protected val simplices: Seq[Simplex[VertexT]]
)(using filterable: Filterable[FiltrationT])(using ordering: Ordering[FiltrationT])
    extends SimplexStream[VertexT, FiltrationT]:
  self =>

  def filtrationValue: PartialFunction[Simplex[VertexT], FiltrationT] = filtrationValues

  def iterator: Iterator[Simplex[VertexT]] = simplices.iterator

  def apply(i: Int): Simplex[VertexT] = simplices(i)

  def length: Int = simplices.length

given [FiltrationT: Filterable] => Option[Filterable[FiltrationT]] =
  Some(summon[Filterable[FiltrationT]])

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
  /** Contract `.iterator` below relies on: the domain must be contiguous starting at 0 -- defined for `0, 1, ..., k`
    * for some `k` (or empty, or all of the non-negative integers), never with a gap. `.iterator` stops at the first
    * dimension this is undefined for, so a non-contiguous domain (defined at `d` but not at `d - 1`) would silently
    * truncate iteration instead of skipping the gap. Every implementation in this codebase already satisfies this (a
    * simplicial complex can't have a `d`-simplex without its `(d-1)`-dimensional faces, so "no cells at `d`" implies
    * "no cells at any dimension beyond `d`" too); a new implementation must preserve it.
    */
  def iterateDimension: PartialFunction[Int, Iterator[CellT]]

  /** Dimension-major: all of dimension `d` before any of dimension `d + 1`.
    *
    * MUST NOT be implemented as `Iterator.from(0).filter(iterateDimension.isDefinedAt)....fold(...)` (a real, confirmed
    * bug this replaced -- see `.claude/WORKLOG-cohomology.md`): `Iterator.filter` on an infinite source can never prove
    * "no more matches ahead", so once past the last dimension `iterateDimension` is defined for, it spins forever
    * searching for a `d` that will never come -- and `Int` silently wrapping from `Int.MaxValue` to `Int.MinValue`
    * after ~2^31 iterations can eventually feed a huge negative `d` straight to `iterateDimension` instead, surfacing
    * as a `BinomialCoefficient` range exception rather than a hang. `.takeWhile` instead stops at the first `d` this is
    * undefined for and never asks about any `d` beyond it, relying on exactly the contiguous-domain contract documented
    * on `iterateDimension` above.
    */
  override def iterator: Iterator[CellT] =
    Iterator
      .from(0)
      .takeWhile(iterateDimension.isDefinedAt)
      .flatMap(iterateDimension)

trait StratifiedSimplexStream[VertexT: Ordering, FiltrationT: Filterable]
    extends StratifiedCellStream[Simplex[VertexT], FiltrationT] {}

trait CofaceSimplexStream[VertexT: Ordering, FiltrationT: Filterable]
    extends StratifiedSimplexStream[VertexT, FiltrationT]:

  def currentDimension: Int

  def lastDimensionCache: Seq[Simplex[VertexT]]

  def currentDimensionCache: Seq[Simplex[VertexT]]

  def keepCriterion: PartialFunction[Simplex[VertexT], Boolean]

class LimitedCofaceSimplexStream(stream: CofaceSimplexStream[Int, Double], maxDim: Int)
    extends CofaceSimplexStream[Int, Double]
    with DoubleFiltration[Simplex[Int]]():
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

class EnumeratingCofaceSimplexStream(
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

  var currentDimension: Int = 0

  var lastDimensionCache: IndexedSeq[Simplex[Int]] = IndexedSeq()

  var currentDimensionCache: immutable.Queue[Simplex[Int]] = immutable.Queue.empty

  // Memoized -- but ONLY the default MaximumDistanceFiltrationValue fallback, not a caller-supplied
  // filtrationValueOverride (e.g. CechFiltration already caches internally; double-wrapping it is pure
  // waste). CellularHomologyContext and CellularPersistenceInChunksContext both re-derive
  // Ordering[CellT] = stream.filtrationOrdering and consult it on every chain-arithmetic comparison during
  // reduction, not just once per cell during this stream's own up-front sorts -- an uncached filtrationValue
  // means MaximumDistanceFiltrationValue's O(d^2) pairwise-distance recompute reruns on every one of those
  // comparisons. Measured (`.claude/WORKLOG-autonomous-session-2026-09-19.md`): memoizing this one fallback
  // cut reduction-phase wall-clock time by 32-46% across n=5000-20000 on a large, sparse, maxDim=1 complex.
  //
  // This is NOT a reversal of RipserCohomologyContext's own `memoizeFiltrationValue = false` default: that
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

  /** Filtration value, reversed (so smaller-under-this-ordering means YOUNGER, matching `SimplexStream`'s own
    * established convention), then dimension, then COLEXICOGRAPHIC order on the vertex set (via `simplexIndexing`'s own
    * combinatorial-number-system index) -- the "lexicographically refined" tie-break Ripser's own apparent-pairs
    * machinery (Definition 3.2/Proposition 3.9, see `RipserCohomologyContext`) is defined in terms of, so using it here
    * keeps this stream's ordering consistent with every other Ripser-flavored piece of this codebase, not just
    * internally self-consistent -- deliberately not the plain lexicographic tie-break `FilteredSimplexOrdering` uses.
    *
    * Fixes a real, previously-confirmed bug (`.claude/WORKLOG-cohomology.md`): a bare `Ordering.by(filtrationValue)`
    * has no tie-break at all, so two DIFFERENT simplices tied at the same filtration value compare as *equal* -- not a
    * total order. This happens by construction on any Vietoris-Rips complex with a triangle, since a triangle's
    * filtration value always equals that of its own longest edge; `CellularHomologyContext` bakes a stream's
    * `filtrationOrdering` into `Chain.reduceBy`'s `SortedMap`, so two cells that compare equal collide as a single map
    * key and the reduction silently garbles pairings for that complex.
    *
    * `iterateDimension` sorts each dimension's bucket by `filtrationOrdering.reverse` -- deliberately `.reverse` on
    * this SAME `Ordering` object, not an independently-built "oldest first" comparator: two individually-valid
    * orderings that disagree on tie-break direction let a coface sort before its own tied facet, corrupting
    * `Chain.reduceBy`'s pivot table the same way the no-tie-break bug did. A stream's iteration order and its
    * `filtrationOrdering` (pivot order) must be THE SAME total order, one the consistent reverse of the other.
    */
  override val filtrationOrdering: Ordering[Simplex[Int]] =
    FiltrationOrdering.canonical(filtrationValue, _.size, Ordering.by(simplexIndexing(_)))

  lazy val simplexIndexing: SimplexIndexing = SimplexIndexing(metricSpace.size)

  /** Sorts `cells` by `filtrationOrdering.reverse` -- semantically identical to `.sorted(using
    * filtrationOrdering.reverse)`, but memoizes each cell's filtrationValue/simplexIndexing for the duration of this
    * one call instead of letting TimSort's O(m log m) comparisons each recompute both from scratch
    * (`MaximumDistanceFiltrationValue.apply` is O(d^2); `simplexIndexing`'s tie-break sorts a list). Both are pure,
    * side-effect-free functions of the cell alone, so caching them for this one sort changes nothing about the
    * resulting order, only how many times each is computed. The cache is local to this call, not stored on the stream
    * instance, so it stays bounded to one dimension's bucket -- deliberately NOT a stream-lifetime cache like
    * `RipserCohomologyContext.memoizeFiltrationValue`, off there for the same memory-frugality reasons. Delegates to
    * the exact same `FiltrationOrdering.canonical` shape above, just memoized, so it cannot silently diverge from it.
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

class RipserCofaceSimplexStream(
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
class InorderCofaceSimplexStream(
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

/** A straightforward, non-optimized reference implementation of a Vietoris-Rips coface stream, following Antonio
  * Rieser's New-VR algorithm ("A New Construction of the Vietoris-Rips Complex", arXiv:2301.07191v3) -- an explicit
  * refinement of Zomorodian's own Incremental-VR algorithm (Algorithms 6/7 in that paper's Section 4; the algorithm
  * `EnumeratingCofaceSimplexStream` and its siblings above are alternate, independently-optimized engines for the same
  * construction). Kept intentionally close to the paper's own Algorithms 1-4, as a solid baseline the other, more
  * experimental streams in this file can be cross-validated against, rather than as a speed-competitive engine in its
  * own right.
  *
  * The paper phrases the construction as a depth-first recursion over a simplex tree (`New-Add-Cofaces`, Algorithm 3),
  * but its own prose description of the "inductive step" (Section 3, immediately above Algorithm 1) is equivalently a
  * breadth-first, layer-by-layer construction: layer `D(k+1)` is built entirely from layer `D(k)` and each of that
  * layer's own candidate/sibling lists. This class uses that framing so it can slot into `iterateDimension`'s
  * per-dimension contract like every other `CofaceSimplexStream` here. Since `iterateDimension` is a `PartialFunction`
  * that may be called for any dimension in any order (unlike a genuinely incremental engine such as
  * `RipserCofaceSimplexStream`, which depends on being driven dimension-by-dimension), the whole complex is built
  * eagerly, once, into `byDimension`, and every call just serves a bucket from it -- simpler and safer than making the
  * recursive construction itself resumable/order-independent.
  *
  * `maxFiltrationValue` (default `+Infinity`) is the threshold defining the graph `G` whose clique complex the paper's
  * algorithm builds: `{i,j} ∈ E` iff `metricSpace.distance(i,j) <= maxFiltrationValue` (the same `<=` convention
  * `RipserCohomologyContext`'s own sparse-Rips support uses, see `Homology.scala`). At the default `+Infinity`, `G` is
  * the complete graph, every vertex subset is a clique, and this degenerates to plain bounded subset enumeration -- the
  * New-VR algorithm's whole advantage over Incremental-VR is exploiting genuine non-edges in `G`, so a finite threshold
  * is where this class's construction actually differs in kind, not just in output, from
  * `EnumeratingCofaceSimplexStream`'s. The inherited `keepCriterion` is a separate, per-simplex filter for callers who
  * want to prune the output further -- it does not define the graph.
  *
  * `largestNeighbor`, the paper's own precomputed "largest neighbor of `v`" table (`L` in Algorithm 2) used by
  * `Table-Lookup` to early-exit the scan over a candidate list once it is provably exhausted, is a pure optimization on
  * top of `Table-Lookup`'s definition (`M = {w ∈ N : w > v, {v,w} ∈ E}`) -- see `IncrementalVietorisRipsSpec` for a
  * pinned test that including it changes nothing about the output.
  *
  * No deduplication is needed anywhere in this construction: every simplex is reached via exactly one recursive path,
  * built by always appending vertices in increasing order from a candidate list that only ever contains vertices
  * greater than every vertex already in `tau` (Theorem 2.5's minimal-pair bijection, in the paper's own terms). If a
  * bug ever makes a simplex appear twice, that is a sign the recursion itself is wrong, not a reason to add a dedup
  * step.
  *
  * One deliberate departure from Algorithm 4 as written: the paper's own pseudocode does `Σ ← V ∪ E` unconditionally,
  * before the main loop, so the full 1-skeleton is always present regardless of `d`. Here `maxDimension` instead
  * behaves exactly like `LimitedCofaceSimplexStream`'s `maxDim` -- the highest dimension `iterateDimension` will ever
  * serve -- so `maxDimension = 0` yields vertices only, with no edges, unlike the paper's own Σ. This matches every
  * other bounded stream in this codebase and is what a caller building up dimension-by-dimension would expect.
  */
class IncrementalVietorisRipsSimplexStream(
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

  /** Algorithm 3 (New-Add-Cofaces), restructured to stop one layer early each call instead of recursing all the way to
    * `maxDimension` in a single pass -- see the class doc for why.
    */
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
