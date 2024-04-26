package org.appliedtopology.tda4j

import scala.collection.mutable
import scala.collection.immutable.{Map, Seq}
import math.Ordering.Implicits._

trait Filtration[VertexT, FiltrationT: Ordering] {
  def filtrationValue: PartialFunction[AbstractSimplex[VertexT], FiltrationT]
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
trait SimplexStream[VertexT: Ordering, FiltrationT: Ordering]
    extends Filtration[VertexT, FiltrationT]
    with IterableOnce[AbstractSimplex[VertexT]] {
  val filtrationOrdering =
    FilteredSimplexOrdering[VertexT, FiltrationT](this)(using vertexOrdering = summon[Ordering[VertexT]])(using
      filtrationOrdering = summon[Ordering[FiltrationT]].reverse
    )
}

class ExplicitStream[VertexT: Ordering, FiltrationT](
  protected val filtrationValues: Map[AbstractSimplex[VertexT], FiltrationT],
  protected val simplices: Seq[AbstractSimplex[VertexT]]
)(using ordering: Ordering[FiltrationT])
    extends SimplexStream[VertexT, FiltrationT] {
  self =>

  // Members declared in org.appliedtopology.tda4j.Filtration
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

class ExplicitStreamBuilder[VertexT: Ordering, FiltrationT](using
  ordering: Ordering[FiltrationT]
) extends mutable.ReusableBuilder[
      (FiltrationT, AbstractSimplex[VertexT]),
      ExplicitStream[VertexT, FiltrationT]
    ] {
  self =>

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
    new ExplicitStream(filtrationValues.toMap, simplices.map((_, s) => s).toSeq)
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
  val filtration: Filtration[VertexT, FiltrationT]
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
