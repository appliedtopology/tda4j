package org.appliedtopology.tda4j

import collection.immutable.SortedMap
import math.Ordering.Implicits.sortedSetOrdering
import scala.annotation.{tailrec, targetName}
import scala.collection.mutable
import math.Fractional.Implicits.infixFractionalOps

/**
 * Typeclass for having a boundary map
 */
trait HasBoundary[T] {
  extension (t: T) {
    def boundary[CoefficientT: Fractional]: Chain[T, CoefficientT]
  }
}

trait HasDimension[T] {
  extension (t:T) {
    def dim: Int
  }
}

trait Cell[T] extends HasBoundary[T] with HasDimension[T]

trait OrderedCell[T] extends Cell[T] with Ordering[T]



/** Trait that defines what it means to have an ordered basis
  */
trait OrderedBasis[CellT : Ordering, CoefficientT: Fractional] {
  def leadingCell: Option[CellT] = leadingTerm._1

  def leadingCoefficient: CoefficientT = leadingTerm._2

  def leadingTerm: (Option[CellT], CoefficientT)
}



/*
Implementation of the Chain trait using heaps for internal storage and deferred arithmetic.
 */

class Chain[CellT : OrderedCell, CoefficientT : Fractional] private[tda4j] (
  var entries: mutable.PriorityQueue[(CellT, CoefficientT)]
) extends OrderedBasis[CellT, CoefficientT] {
  override def leadingTerm: (Option[CellT], CoefficientT) = {
    collapseHead()
    { (x: (Option[CellT], Option[CoefficientT])) =>
      x.copy(_2 = x._2.getOrElse(summon[Fractional[CoefficientT]].zero))
    }
      .apply(entries.headOption.unzip)
  }

  @tailrec
  final def collapseHead(): Unit = {
    val fr = summon[Fractional[CoefficientT]]
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

  def collapseAll()(using fr: Fractional[CoefficientT]): Unit =
    entries = mutable.PriorityQueue.from(
      entries
        .groupMapReduce(_._1) // group by cell
        (x => x._2) // extract coefficient
        (fr.plus) // sum the coefficient parts
        .filter((c, x) => x != fr.zero)
        .iterator
        .toSeq
    )

  def isZero(): Boolean = {
    collapseHead()
    entries.isEmpty || (entries.head._2 == summon[Fractional[CoefficientT]].zero)
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
  def apply[CellT : OrderedCell, CoefficientT: Fractional](
    cs: (CellT, CoefficientT)*
  ): Chain[CellT, CoefficientT] =
    from(cs)
  def apply[CellT : OrderedCell, CoefficientT: Fractional](c: CellT): Chain[CellT, CoefficientT] =
    apply(c -> summon[Fractional[CoefficientT]].one)
  def from[CellT : OrderedCell, CoefficientT: Fractional](
    cs: Seq[(CellT, CoefficientT)]
  ): Chain[CellT, CoefficientT] =
    new Chain(mutable.PriorityQueue.from(cs)(using Ordering.by[(CellT, CoefficientT), CellT](_._1)))
}

class ChainOps[CellT : OrderedCell, CoefficientT](using fr: Fractional[CoefficientT])
    extends RingModule[Chain[CellT, CoefficientT], CoefficientT] {

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
