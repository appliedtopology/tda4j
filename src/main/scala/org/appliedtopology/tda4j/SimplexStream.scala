package org.appliedtopology.tda4j

import scala.collection.mutable
import scala.collection.immutable.{Map, Seq}
import math.Ordering.Implicits._

trait Filterable[FiltrationT : Ordering] {
  def smallest: FiltrationT
  def largest: FiltrationT
}

given DoubleIsFilterable : Filterable[Double] = new Filterable[Double] {
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


trait Filtration[CellT <: Cell[CellT], FiltrationT : Ordering : Filterable] extends Filterable[FiltrationT] {
  def filtrationValue: PartialFunction[CellT, FiltrationT]
}

trait DoubleFiltration[CellT <: Cell[CellT]] extends Filtration[CellT, Double] {
  val smallest = Double.NegativeInfinity
  val largest = Double.PositiveInfinity
}

trait CellStream[CellT <: Cell[CellT], FiltrationT : Ordering] extends Filtration[CellT,FiltrationT] 
  with IterableOnce[CellT] {
  def filtrationOrdering : Ordering[CellT]
}

/** Abstract trait for representing a sequence of simplices.
  *
  * @tparam VertexT
  *   Type of vertices of the contained simplices.
  * @tparam FiltrationT
  *   Type of the filtration values.
  *
  * @todo
  *   We may want to change this to inherit instead from `IterableOnce[AbstractSimplex[VertexT]]`, so that a lazy
  *   computed simplex stream can be created and fit in the type hierarchy.
  */
trait SimplexStream[VertexT: Ordering, FiltrationT : Ordering : Filterable]
    extends CellStream[AbstractSimplex[VertexT], FiltrationT] {
  val filterable: Filterable[FiltrationT] = summon[Filterable[FiltrationT]]
  export filterable.{smallest, largest}

  val filtrationOrdering =
    FilteredSimplexOrdering[VertexT, FiltrationT](this)(using vertexOrdering = summon[Ordering[VertexT]])(using
      filtrationOrdering = summon[Ordering[FiltrationT]].reverse
    )
}

object SimplexStream {
  def from[VertexT: Ordering](
    stream: Seq[AbstractSimplex[VertexT]],
    metricSpace: FiniteMetricSpace[VertexT]
  ): SimplexStream[VertexT, Double] = new SimplexStream[VertexT, Double] {

    override def filtrationValue: PartialFunction[AbstractSimplex[VertexT], Double] =
      FiniteMetricSpace.MaximumDistanceFiltrationValue[VertexT](metricSpace)(using summon[Ordering[VertexT]])

    override def iterator: Iterator[AbstractSimplex[VertexT]] =
      stream.iterator
  }
}

class ExplicitStream[VertexT: Ordering, FiltrationT](
  protected val filtrationValues: Map[AbstractSimplex[VertexT], FiltrationT],
  protected val simplices: Seq[AbstractSimplex[VertexT]]
)(using filterable: Filterable[FiltrationT])(using ordering: Ordering[FiltrationT])
    extends SimplexStream[VertexT, FiltrationT] {
  self =>

  // Members declared in org.appliedtopology.tda4j.SimplexFiltration
  def filtrationValue: PartialFunction[
    org.appliedtopology.tda4j.AbstractSimplex[VertexT],
    FiltrationT
  ] =
    filtrationValues

  // Members declared in scala.collection.IterableOnce
  def iterator: Iterator[org.appliedtopology.tda4j.AbstractSimplex[VertexT]] =
    simplices.iterator

  // Members declared in scala.collection.SeqOps
  def apply(i: Int): org.appliedtopology.tda4j.AbstractSimplex[VertexT] =
    simplices(i)

  def length: Int = simplices.length
}

given [FiltrationT : Filterable] : Option[Filterable[FiltrationT]] =
  Some(summon[Filterable[FiltrationT]])
  
class ExplicitStreamBuilder[VertexT: Ordering, FiltrationT](using
  ordering: Ordering[FiltrationT]
)(using filterableO : Option[Filterable[FiltrationT]] = None) extends mutable.ReusableBuilder[
      (FiltrationT, AbstractSimplex[VertexT]),
      ExplicitStream[VertexT, FiltrationT]
    ] {
  self =>

  val filterable : Filterable[FiltrationT] = filterableO match {
      case Some(f) => f
      case None => new Filterable[FiltrationT] {
        val largest: FiltrationT = filtrationValues.maxBy(_._2)._2
        val smallest: FiltrationT = filtrationValues.minBy(_._2)._2
      }
    }
  
  protected val filtrationValues: mutable.Map[AbstractSimplex[VertexT], FiltrationT] =
    new mutable.HashMap[AbstractSimplex[VertexT], FiltrationT]()
  protected val simplices: mutable.Queue[(FiltrationT, AbstractSimplex[VertexT])] =
    mutable.Queue[(FiltrationT, AbstractSimplex[VertexT])]()

  override def clear(): Unit = {
    filtrationValues.clear()
    simplices.clear()
  }

  override def result(): ExplicitStream[VertexT, FiltrationT] = {
    def lt(
      fs1: (FiltrationT, AbstractSimplex[VertexT]),
      fs2: (FiltrationT, AbstractSimplex[VertexT])
    ): Boolean =
      (fs1._1, fs1._2.to(Seq)) < (fs2._1, fs2._2.to(Seq))
    simplices.sortInPlaceWith(lt)
    
    new ExplicitStream(filtrationValues.toMap, simplices.map((_, s) => s).toSeq)(using filterable)
  }
  
  override def addOne(
    elem: (FiltrationT, AbstractSimplex[VertexT])
  ): ExplicitStreamBuilder.this.type = {
    filtrationValues(elem._2) = elem._1
    simplices += elem
    self
  }
}

class FilteredSimplexOrdering[VertexT, FiltrationT](
  val filtration: Filtration[AbstractSimplex[VertexT], FiltrationT]
)(using vertexOrdering: Ordering[VertexT])(using
  filtrationOrdering: Ordering[FiltrationT]
) extends Ordering[AbstractSimplex[VertexT]] {
  def compare(x: AbstractSimplex[VertexT], y: AbstractSimplex[VertexT]): Int = (x, y) match {
    case (x, y) if filtration.filtrationValue.isDefinedAt(x) && filtration.filtrationValue.isDefinedAt(y) =>
      filtrationOrdering.compare(filtration.filtrationValue(x), filtration.filtrationValue(y)) match {
        case 0 =>
          if (Ordering.Int.compare(x.size, y.size) == 0)
            Ordering.Implicits
              .seqOrdering[Seq, VertexT](vertexOrdering)
              .compare(x.to(Seq), y.to(Seq))
          else
            Ordering.Int.compare(x.size, y.size)
        case cmp if cmp != 0 => cmp
      }
    case (x, y) => // at least one does not have a filtration value defined; just go by dimension and lexicographic
      if (Ordering.Int.compare(x.size, y.size) == 0)
        Ordering.Implicits
          .seqOrdering[Seq, VertexT](vertexOrdering)
          .compare(x.to(Seq), y.to(Seq))
      else
        Ordering.Int.compare(x.size, y.size)
  }
}
