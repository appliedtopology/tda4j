package org.appliedtopology.tda4j

import org.apache.commons.numbers.combinatorics.BinomialCoefficient

import scala.collection.{mutable,immutable}
import scala.collection.immutable.{Map, Seq, SortedSet}
import math.Ordering.Implicits.*

trait Filterable[FiltrationT: Ordering] {
  def smallest: FiltrationT
  def largest: FiltrationT
}

given DoubleIsFilterable: Filterable[Double] = new Filterable[Double] {
  val smallest = Double.NegativeInfinity
  val largest = Double.PositiveInfinity
}

given FloatIsFilterable: Filterable[Float] = new Filterable[Float] {
  val smallest = Float.NegativeInfinity
  val largest = Float.PositiveInfinity
}

given IntIsFilterable: Filterable[Int] = new Filterable[Int] {
  val smallest = Int.MinValue
  val largest = Int.MaxValue
}

given ShortIsFilterable: Filterable[Short] = new Filterable[Short] {
  val smallest = Short.MinValue
  val largest = Short.MaxValue
}

given LongIsFilterable: Filterable[Long] = new Filterable[Long] {
  val smallest = Long.MinValue
  val largest = Long.MaxValue
}

trait Filtration[CellT: Cell, FiltrationT: Ordering: Filterable] extends Filterable[FiltrationT] {
  def filtrationValue: PartialFunction[CellT, FiltrationT]
}

trait DoubleFiltration[CellT: Cell] extends Filtration[CellT, Double] {
  val smallest = Double.NegativeInfinity
  val largest = Double.PositiveInfinity
}

trait CellStream[CellT: Cell, FiltrationT: Ordering] extends Filtration[CellT, FiltrationT] with IterableOnce[CellT] {
  def filtrationOrdering: Ordering[CellT]
}

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
trait SimplexStream[VertexT: Ordering, FiltrationT: Ordering: Filterable]
    extends CellStream[Simplex[VertexT], FiltrationT] {
  val filterable: Filterable[FiltrationT] = summon[Filterable[FiltrationT]]
  export filterable.{largest, smallest}

  val filtrationOrdering =
    FilteredSimplexOrdering[VertexT, FiltrationT](this)(using vertexOrdering = summon[Ordering[VertexT]])(using
      filtrationOrdering = summon[Ordering[FiltrationT]].reverse
    )
}

object SimplexStream {
  def from[VertexT: Ordering](
    stream: Seq[Simplex[VertexT]],
    metricSpace: FiniteMetricSpace[VertexT]
  ): SimplexStream[VertexT, Double] = new SimplexStream[VertexT, Double] {

    override def filtrationValue: PartialFunction[Simplex[VertexT], Double] =
      FiniteMetricSpace.MaximumDistanceFiltrationValue[VertexT](metricSpace)(using summon[Ordering[VertexT]])

    override def iterator: Iterator[Simplex[VertexT]] =
      stream.iterator
  }
}

class ExplicitStream[VertexT: Ordering, FiltrationT](
  protected val filtrationValues: Map[Simplex[VertexT], FiltrationT],
  protected val simplices: Seq[Simplex[VertexT]]
)(using filterable: Filterable[FiltrationT])(using ordering: Ordering[FiltrationT])
    extends SimplexStream[VertexT, FiltrationT] {
  self =>

  // Members declared in org.appliedtopology.tda4j.SimplexFiltration
  def filtrationValue: PartialFunction[
    org.appliedtopology.tda4j.Simplex[VertexT],
    FiltrationT
  ] =
    filtrationValues

  // Members declared in scala.collection.IterableOnce
  def iterator: Iterator[org.appliedtopology.tda4j.Simplex[VertexT]] =
    simplices.iterator

  // Members declared in scala.collection.SeqOps
  def apply(i: Int): org.appliedtopology.tda4j.Simplex[VertexT] =
    simplices(i)

  def length: Int = simplices.length
}

given [FiltrationT: Filterable]: Option[Filterable[FiltrationT]] =
  Some(summon[Filterable[FiltrationT]])

class ExplicitStreamBuilder[VertexT: Ordering, FiltrationT](using
  ordering: Ordering[FiltrationT]
)(using filterableO: Option[Filterable[FiltrationT]] = None)
    extends mutable.ReusableBuilder[
      (FiltrationT, Simplex[VertexT]),
      ExplicitStream[VertexT, FiltrationT]
    ] {
  self =>

  val filterable: Filterable[FiltrationT] = filterableO match {
    case Some(f) => f
    case None =>
      new Filterable[FiltrationT] {
        val largest: FiltrationT = filtrationValues.maxBy(_._2)._2
        val smallest: FiltrationT = filtrationValues.minBy(_._2)._2
      }
  }

  protected val filtrationValues: mutable.Map[Simplex[VertexT], FiltrationT] =
    new mutable.HashMap[Simplex[VertexT], FiltrationT]()
  protected val simplices: mutable.Queue[(FiltrationT, Simplex[VertexT])] =
    mutable.Queue[(FiltrationT, Simplex[VertexT])]()

  override def clear(): Unit = {
    filtrationValues.clear()
    simplices.clear()
  }

  override def result(): ExplicitStream[VertexT, FiltrationT] = {
    given filtrationOrdering: Ordering[(FiltrationT, Simplex[VertexT])] =
      Ordering
        .by[(FiltrationT, Simplex[VertexT]), FiltrationT]((f: FiltrationT, s: Simplex[VertexT]) => f)(ordering)
        .orElseBy((f: FiltrationT, s: Simplex[VertexT]) => s)(simplexOrdering)
    simplices.sortInPlace

    new ExplicitStream(filtrationValues.toMap, simplices.map((_, s) => s).toSeq)(using filterable)
  }

  override def addOne(
    elem: (FiltrationT, Simplex[VertexT])
  ): ExplicitStreamBuilder.this.type = {
    filtrationValues(elem._2) = elem._1
    simplices += elem
    self
  }
}

class FilteredSimplexOrdering[VertexT, FiltrationT](
  val filtration: Filtration[Simplex[VertexT], FiltrationT]
)(using vertexOrdering: Ordering[VertexT])(using
  filtrationOrdering: Ordering[FiltrationT]
) extends Ordering[Simplex[VertexT]] {
  def compare(x: Simplex[VertexT], y: Simplex[VertexT]): Int = (x, y) match {
    case (x, y) if filtration.filtrationValue.isDefinedAt(x) && filtration.filtrationValue.isDefinedAt(y) =>
      filtrationOrdering.compare(filtration.filtrationValue(x), filtration.filtrationValue(y)) match {
        case 0 =>
          if (Ordering.Int.compare(x.size, y.size) == 0)
            Ordering.Implicits
              .sortedSetOrdering[SortedSet, VertexT](vertexOrdering)
              .compare(x, y)
          else
            Ordering.Int.compare(x.size, y.size)
        case cmp if cmp != 0 => cmp
      }
    case (x, y) => // at least one does not have a filtration value defined; just go by dimension and lexicographic
      if (Ordering.Int.compare(x.size, y.size) == 0)
        Ordering.Implicits
          .sortedSetOrdering[SortedSet, VertexT](vertexOrdering)
          .compare(x, y)
      else
        Ordering.Int.compare(x.size, y.size)
  }
}

trait StratifiedCellStream[CellT: OrderedCell, FiltrationT: Filterable] extends CellStream[CellT, FiltrationT] {
  def iterateDimension: PartialFunction[Int, Iterator[CellT]]

  override def iterator: Iterator[CellT] =
    Iterator
      .from(0)
      .filter(iterateDimension.isDefinedAt)
      .map((dim: Int) => iterateDimension.applyOrElse(dim, (d: Int) => Iterator.empty))
      .fold(Iterator.empty: Iterator[CellT])((x, y) => x ++ y)
}

trait StratifiedSimplexStream[VertexT: Ordering, FiltrationT: Filterable]
    extends StratifiedCellStream[Simplex[VertexT], FiltrationT] {}

trait CofaceSimplexStream[VertexT: Ordering, FiltrationT: Filterable]
    extends StratifiedSimplexStream[VertexT, FiltrationT] {

  def currentDimension: Int

  def lastDimensionCache: Seq[Simplex[VertexT]]

  def currentDimensionCache: Seq[Simplex[VertexT]]

  def pruneAllCofaces: Boolean

  def keepCriterion: PartialFunction[Simplex[VertexT], Boolean]
}

class EnumeratingCofaceSimplexStream(
  val metricSpace: FiniteMetricSpace[Int],
  var keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true }
) extends CofaceSimplexStream[Int, Double]
    with DoubleFiltration[Simplex[Int]]() {

  lazy val edges = for
    i <- metricSpace.elements
    j <- metricSpace.elements
    if i < j
  yield Simplex(i, j)

  var currentDimension: Int = 0

  var lastDimensionCache: IndexedSeq[Simplex[Int]] = IndexedSeq()

  var currentDimensionCache: immutable.Queue[Simplex[Int]] = immutable.Queue.empty

  override def pruneAllCofaces: Boolean = false

  override val filtrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  override val filtrationOrdering: Ordering[Simplex[Int]] =
    Ordering.by(filtrationValue)

  var finishedCurrent: Boolean = false

  lazy val simplexIndexing: SimplexIndexing = SimplexIndexing(metricSpace.size)

  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case 0 => metricSpace.elements.map(v => Simplex(v)).iterator
    case 1 => edges.toSeq.sortBy(filtrationValue).iterator
    case d =>
      // first, generate all simplices of this dimension
      (0 until BinomialCoefficient.value(metricSpace.size, d + 1).toInt).toSeq
        .flatMap { ix =>
          Some(simplexIndexing(ix, d+1)).filter(keepCriterion.applyOrElse(_, _ => true))
        }
        .sortBy(filtrationValue)
        .iterator
  }
}

class RipserCofaceSimplexStream(
                                  metricSpace: FiniteMetricSpace[Int],
                                  keepCriterion: PartialFunction[Simplex[Int], Boolean] = {
                                    case _ => true
                                  }
                                ) extends EnumeratingCofaceSimplexStream(metricSpace, keepCriterion) {
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
      case 0 => {
        currentDimensionCache = metricSpace.elements.map(v => Simplex(v)).to(immutable.Queue)
        currentDimension = 0
        lastDimensionCache = IndexedSeq.empty[Simplex[Int]]
        currentDimensionCache.iterator
      }
      case d => {
        if(currentDimension != d-1) {
          // we don't have a good cache, just generate entire previous dimension and deal with it
          lastDimensionCache = (0 until BinomialCoefficient.value(metricSpace.size, d).toInt).toSeq
            .flatMap { ix =>
              Some(simplexIndexing(ix, d)).filter(keepCriterion.applyOrElse(_, _ => true))
            }
            .sortBy(filtrationValue)
        } else {
          lastDimensionCache = currentDimensionCache.toIndexedSeq
        }
        // now we have a known good lastDimensionCache
        currentDimensionCache = (for
          spx <- lastDimensionCache
          i <- metricSpace.elements.filter((j) => j < spx.min)
          newSpx = spx + i
          if(keepCriterion.applyOrElse(newSpx, (_) => false))
        yield
          newSpx).sorted(using filtrationOrdering).to(immutable.Queue) // we _would_ want to avoid creating the entire thing and sort it
        currentDimensionCache.iterator
      }
    }
}

class InorderCofaceSimplexStream(
  metricSpace : FiniteMetricSpace[Int],
  keepCriterion : PartialFunction[Simplex[Int], Boolean] = { case _ => true }
                                ) extends EnumeratingCofaceSimplexStream(metricSpace, keepCriterion) {
  def inOrderCofaceIterator(spx : Simplex[Int]): Iterator[Simplex[Int]] = 
    if(spx.isEmpty) {
      metricSpace.elements.iterator.map((s) => Simplex(s))
    } else {
    val alpha = filtrationValue(spx)
    val alpha0 = if(spx.size > 0) then filtrationValue(spx.tail) else smallest
    val alpha1 = if(spx.size > 1) then filtrationValue(spx.tail.tail + spx.head) else smallest
    def localMin(s : Simplex[Int]): Int =
      if(s.nonEmpty) s.min
      else metricSpace.elements.max+1
    Iterator.concat(
      // first, all the vertices that come before the first vertex - case 1
      for
        i <- metricSpace.elements.filter((j) => j < localMin(spx))
        if(spx.map((j) => metricSpace.distance(i,j)).max <= alpha)
      yield
        spx + i
      ,
      // next, if filtrationValue drops when eliminating the first vertex
      if(alpha > alpha0) {
        for
          i <- metricSpace.elements.filter((j) => (localMin(spx) < j) && (j < localMin(spx.tail)))
          newSpx = spx + i
          if (filtrationValue(newSpx) == alpha)
        yield
          newSpx
      } else Iterable.empty[Simplex[Int]],
      // finally, if filtrationValue drops both when eliminating the first and the second vertex
      // in this case, the edge between first and second is the only full-length edge
      if((alpha > alpha0) && (alpha > alpha1) && (spx.size > 1)) {
        for 
          i <- metricSpace.elements.filter((j) => (localMin(spx.tail) < j) && (j < localMin(spx.tail.tail)))
          if (spx.map((j) => metricSpace.distance(i,j)).max < alpha)
        yield
          spx + i
      } else Iterable.empty
    )
  }
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case 0 => {
      currentDimensionCache = metricSpace.elements.map(v => Simplex(v)).to(immutable.Queue)
      currentDimension = 0
      lastDimensionCache = IndexedSeq.empty
      currentDimensionCache.iterator
    }
    case 1 => {
      currentDimensionCache = edges.toSeq.sortBy(filtrationValue).to(immutable.Queue)
      currentDimension = 1
      lastDimensionCache = metricSpace.elements.map(v => Simplex(v)).toIndexedSeq
      currentDimensionCache.iterator
    }
    case d => {
      if(currentDimension != d-1) {
        // we don't have a good cache, just generate entire previous dimension and deal with it
        lastDimensionCache = (0 until BinomialCoefficient.value(metricSpace.size, d).toInt).toSeq
          .flatMap { ix =>
            Some(simplexIndexing(ix, d)).filter(keepCriterion.applyOrElse(_, _ => true))
          }
          .sortBy(filtrationValue)
      } else {
        lastDimensionCache = currentDimensionCache.toIndexedSeq
      }
      // now we have a known good lastDimensionCache
      currentDimensionCache = immutable.Queue.empty
      currentDimension = d
      for
        spx <- lastDimensionCache.iterator
        newSpx <- inOrderCofaceIterator(spx)
        if(keepCriterion.applyOrElse(newSpx, (_) => false))
      yield {
        currentDimensionCache = currentDimensionCache appended newSpx
        newSpx
      }
    }
  }
}