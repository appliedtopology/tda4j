package org.appliedtopology.tda4j

import collection.immutable.SortedMap
import math.Ordering.Implicits.sortedSetOrdering
import scala.annotation.{tailrec, targetName}
import scala.collection.mutable
import math.Fractional.Implicits.infixFractionalOps

trait HasDimension:
  type Self
  extension (self : Self)
    def dim : Int

trait Cell extends HasDimension:
  type Self
  extension (self : Self)
    def boundary[CoefficientT : Field] : Chain[Self, CoefficientT]

trait OrderedCell extends Cell { type Self : Ordering as ordering }

given [CellT : OrderedCell as oCell] => Ordering[CellT] = oCell.ordering


/** Trait that defines what it means to have an ordered basis
  */
trait OrderedBasis[CellT : OrderedCell, CoefficientT: Field]:
  type Self
  extension(t: Self)
    def leadingCell: Option[CellT] = leadingTerm._1
    def leadingCoefficient: CoefficientT = leadingTerm._2
    def leadingTerm: (Option[CellT], CoefficientT)




/*
Implementation of the Chain trait using heaps for internal storage and deferred arithmetic.
 */

class Chain[CellT : OrderedCell as oCell, CoefficientT : Field] private[tda4j] (
  var entries: mutable.PriorityQueue[(CellT, CoefficientT)]
) {
  @tailrec
  final def collapseHead(): Unit = {
    val fr = summon[CoefficientT is Field]
    val cmp = entries.ord
    entries.headOption match {
      case None => ()
      case Some(_) =>
        val head = entries.dequeue
        val cell = head._1
        var acc = head._2
        while (entries.headOption.map(cmp.compare(head, _)) == Some(0)) {
          val otherHead = entries.dequeue
          acc = fr.plus(acc, otherHead._2)
        }
        if (acc == fr.zero)
          collapseHead()
        else
          entries.enqueue((cell, acc))
    }
  }

  def collapseAll()(using fr: (CoefficientT is Field)): Unit =
    entries = mutable.PriorityQueue.from(
      entries
        .groupMapReduce(_._1) // group by cell
        (x => x._2) // extract coefficient
        (fr.plus) // sum the coefficient parts
        .filter((c, x) => x != fr.zero)
        .iterator
        .toSeq
    )(using entries.ord)

  def isZero(): Boolean = {
    collapseHead()
    entries.isEmpty || (entries.head._2 == summon[CoefficientT is Field].zero)
  }

  def items: Seq[(CellT, CoefficientT)] = entries.toSeq

  /** WARNING - this is potentially an expensive operation
   */
  override def equals(obj: Any): Boolean = obj match {
    case other: Chain[CellT, CoefficientT] =>
      collapseAll()
      other.collapseAll()
      entries.iterator.toList.sorted(using entries.ord) == other.entries.iterator.toList.sorted(using other.entries.ord)
    case _ => false
  }

  override def toString: String =
    if (entries.iterator.isEmpty) "Chain()"
    else entries.iterator.map((c, x) => s"${x.toString}⊠${c.toString}").mkString(" + ")

  def chainBoundary: Chain[CellT, CoefficientT] =
    Chain.from(entries
      .iterator
      .flatMap { (cellO, coeffO) =>
        cellO
          .boundary[CoefficientT]
          .entries
          .iterator
          .map { (cellI, coeffI) => (cellI, coeffO * coeffI) }
      }
      .toSeq
    )
}

object Chain {
  def empty[CellT: OrderedCell, CoefficientT: Field] = from(Seq())

  def apply[CellT: OrderedCell, CoefficientT: Field](
                                                           cs: (CellT, CoefficientT)*
                                                         ): Chain[CellT, CoefficientT] =
    from(cs)

  def apply[CellT: OrderedCell, CoefficientT: Field as fld](c: CellT): Chain[CellT, CoefficientT] =
    apply(c -> fld.one)

  def from[CellT: OrderedCell as oCell, CoefficientT: Field](
                                                          cs: Seq[(CellT, CoefficientT)]
                                                        ): Chain[CellT, CoefficientT] =
    new Chain(mutable.PriorityQueue.from(cs)(using Ordering.by[(CellT, CoefficientT), CellT](_._1)(using oCell.ordering.reverse)))

  given [CellT: OrderedCell, CoefficientT: Field as fld] => Chain[CellT, CoefficientT] is OrderedBasis[CellT, CoefficientT] {
    extension (self: Self)
      def leadingTerm: (Option[CellT], CoefficientT) = {
        self.collapseHead()
        { (x: (Option[CellT], Option[CoefficientT])) =>
          x.copy(_2 = x._2.getOrElse(fld.zero))
        }
          .apply(self.entries.headOption.unzip)
      }
  }

  given [CellT: OrderedCell, CoefficientT: Field as fr] => (Chain[CellT, CoefficientT] is RingModule {
    type R = CoefficientT
  }) = new {
    type R = CoefficientT

    import Numeric.Implicits.*

    override val zero: Chain[CellT, CoefficientT] = Chain()

    override def plus(
                       x: Chain[CellT, CoefficientT],
                       y: Chain[CellT, CoefficientT]
                     ): Chain[CellT, CoefficientT] = new Chain[CellT, CoefficientT](x.entries.clone().addAll(y.entries))

    override def scale(
                        x: CoefficientT,
                        y: Chain[CellT, CoefficientT]
                      ): Chain[CellT, CoefficientT] =
      Chain.from[CellT, CoefficientT](y.entries.map((cell, coeff) => (cell, x * coeff)).toSeq)

    override def negate(
                         x: Chain[CellT, CoefficientT]
                       ): Chain[CellT, CoefficientT] =
      scale(-fr.one, x)
  }
}