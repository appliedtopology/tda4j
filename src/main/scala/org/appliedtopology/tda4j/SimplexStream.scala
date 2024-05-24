package org.appliedtopology.tda4j

import scala.collection.mutable
import scala.collection.immutable.{Map, Seq}
import math.Ordering.Implicits._

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

trait Filtration[CellT <: Cell[CellT], FiltrationT: Ordering: Filterable] extends Filterable[FiltrationT] {
  def filtrationValue: PartialFunction[CellT, FiltrationT]
}

trait DoubleFiltration[CellT <: Cell[CellT]] extends Filtration[CellT, Double] {
  val smallest = Double.NegativeInfinity
  val largest = Double.PositiveInfinity
}

trait CellStream[CellT <: Cell[CellT], FiltrationT: Ordering]
    extends Filtration[CellT, FiltrationT]
    with IterableOnce[CellT] {
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
    given filtrationOrdering : Ordering[(FiltrationT, Simplex[VertexT])] =
      Ordering
        .by[(FiltrationT,Simplex[VertexT]),FiltrationT]{(f:FiltrationT,s:Simplex[VertexT]) => f}(ordering)
        .orElseBy{(f:FiltrationT,s:Simplex[VertexT]) => s}(simplexOrdering[VertexT])
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
          if (Ordering.Int.compare(x.dim, y.dim) == 0)
            Ordering.Implicits
              .seqOrdering[Seq, VertexT](vertexOrdering)
              .compare(x.vertices, y.vertices)
          else
            Ordering.Int.compare(x.dim, y.dim)
        case cmp if cmp != 0 => cmp
      }
    case (x, y) => // at least one does not have a filtration value defined; just go by dimension and lexicographic
      if (Ordering.Int.compare(x.dim, y.dim) == 0)
        Ordering.Implicits
          .seqOrdering[Seq, VertexT](vertexOrdering)
          .compare(x.vertices, y.vertices)
      else
        Ordering.Int.compare(x.dim, y.dim)
  }
}



trait StratifiedCellStream[CellT <: Cell[CellT] :Ordering,FiltrationT:Filterable] extends CellStream[CellT,FiltrationT] {
  def iterateDimension : PartialFunction[Int, Iterator[CellT]]

  override def iterator: Iterator[CellT] =
    Iterator.from(0)
      .filter(iterateDimension.isDefinedAt)
      .map{ (dim:Int) => iterateDimension.applyOrElse(dim, (d:Int) => Iterator.empty) }
      .fold(Iterator.empty:Iterator[CellT])((x,y) => x++y)
}

trait StratifiedSimplexStream[VertexT:Ordering, FiltrationT:Filterable] extends StratifiedCellStream[Simplex[VertexT],FiltrationT] { }
