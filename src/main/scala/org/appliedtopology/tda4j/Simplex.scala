package org.appliedtopology.tda4j

import scala.collection.mutable
import scala.collection.immutable.SortedSet
import math.Ordering.Implicits.sortedSetOrdering
import scala.reflect.ClassTag

/** Simplices really are just sets, outright. We provide an implementation of the [OrderedCell] typeclass
 * for simplicial complex structures, to enable their use.
 *
 */

opaque type Simplex[VertexT] = SortedSet[VertexT]

/** Inherit a selection of the SortedSet methods
 * and add other utility methods
 */
extension [VertexT](spx : Simplex[VertexT]) {
  def show: String = spx.mkString(s"∆(", ",", ")")
  //override def toString(): String = spx.mkString(s"∆(", ",", ")")
  def contains(elem: VertexT): Boolean = spx.contains(elem)
  def forall(p: VertexT => Boolean): Boolean = spx.forall(p)
  def flatMap[B: Ordering](f: VertexT => IterableOnce[B]): Simplex[B] = spx.flatMap(f)
  def first: VertexT = spx.first
  def last: VertexT = spx.last
  def firstKey: VertexT = spx.firstKey
  def lastKey: VertexT = spx.lastKey
  def firstOption: Option[VertexT] = spx.firstOption
  def lastOption: Option[VertexT] = spx.lastOption
  def map[B: Ordering](f: VertexT => B): Simplex[B] = spx.map(f)
  //def map[B](f: (VertexT => B)): Set[B] = spx.toSet.map(f)
  def nonEmpty: Boolean = spx.nonEmpty
  def isEmpty: Boolean = spx.isEmpty
  def size: Int = spx.size
  def dim: Int = spx.size - 1
  //def to[C1](factory: Factory[VertexT, C1]): C1 = spx.to(factory)
  def toArray[B >: VertexT : ClassTag]: Array[B] = spx.toArray
  //final def toBuffer[B >: VertexT]: Buffer[B] = spx.toBuffer
  def toIndexedSeq: IndexedSeq[VertexT] = spx.toIndexedSeq
  def toList: List[VertexT] = spx.toList
  def toSeq: Seq[VertexT] = spx.toSeq
  def toSet: Set[VertexT] = spx.toSet
  def toSortedSet: SortedSet[VertexT] = spx
  def union(that: Simplex[VertexT]): Simplex[VertexT] = spx.union(that)
  def |(that: Simplex[VertexT]): Simplex[VertexT] = spx.union(that)
  def incl(v : VertexT): Simplex[VertexT] = spx.incl(v)
  def +(v: VertexT): Simplex[VertexT] = spx.incl(v)
  def concat(that: IterableOnce[VertexT]): Simplex[VertexT] = spx.concat(that)
  def ++(that: IterableOnce[VertexT]): Simplex[VertexT] = spx.concat(that)
  def min[B >: VertexT : Ordering]: VertexT = spx.min
  def minAfter(key: VertexT): Option[VertexT] = spx.minAfter(key)
  def minBy[B: Ordering](f: VertexT => B): VertexT = spx.minBy(f)
  def minByOption[B: Ordering](f: VertexT => B): Option[VertexT] = spx.minByOption(f)
  def minOption[B >: VertexT](using ordering : Ordering[B]): Option[B] = spx.minOption
  def max[B >: VertexT : Ordering]: VertexT = spx.max
  def maxAfter(key: VertexT): Option[VertexT] = spx.maxAfter(key)
  def maxBy[B: Ordering](f: VertexT => B): VertexT = spx.maxBy(f)
  def maxByOption[B: Ordering](f: VertexT => B): Option[VertexT] = spx.maxByOption(f)
  def maxOption[B >: VertexT](using ordering : Ordering[B]): Option[B] = spx.maxOption
  def filter(pred: VertexT => Boolean): Simplex[VertexT] = spx.filter(pred)
  def filterNot(pred: VertexT => Boolean): Simplex[VertexT] = spx.filterNot(pred)
  def zip[B](that: IterableOnce[B]): Set[(VertexT,B)] = spx.toSet.zip(that)
  def zipAll[V >: VertexT, B](that: Iterable[B], thisElem: V, thatElem: B): Set[(V,B)] = spx.zipAll(that, thisElem, thatElem)
  def zipWithIndex: Set[(VertexT, Int)] = spx.zipWithIndex
  def drop(n: Int): Simplex[VertexT] = spx.drop(n)
  def dropRight(n: Int): Simplex[VertexT] = spx.dropRight(n)
  def dropWhile(p: VertexT => Boolean): Simplex[VertexT] = spx.dropWhile(p)
  def dropIndex(n: Int): Simplex[VertexT] = spx -- spx.slice(n,n+1)
  def take(n: Int): Simplex[VertexT] = spx.take(n)
  def takeRight(n: Int): Simplex[VertexT] = spx.takeRight(n)
  def count(p: VertexT => Boolean): Int = spx.count(p)
  def exists(p: VertexT => Boolean): Boolean = spx.exists(p)
  def tail: Simplex[VertexT] = spx.tail
  def tails: Iterator[Simplex[VertexT]] = spx.tails
  def head: VertexT = spx.head
  def headOption: Option[VertexT] = spx.headOption
}

object Simplex:
  def from[VertexT : Ordering, T <: Seq[VertexT]](vertices : T) : Simplex[VertexT] = SortedSet.from(vertices)
  def apply[VertexT : Ordering](vertices : VertexT*) : Simplex[VertexT] = from(vertices)
  def unapplySeq[VertexT : Ordering](simplex : Simplex[VertexT]): Option[Seq[VertexT]] = Some(simplex.toSeq)

/** Convenience method for defining simplices
 *
 * The character ∆ is typed as Alt+J on Mac GB layout, and has unicode code 0x0394.
 */
def ∆[VertexT : Ordering](vertices : VertexT*) : Simplex[VertexT] = Simplex.from(vertices)

def simplexOrdering[VertexT](using vtxOrd : Ordering[VertexT]) : Ordering[Simplex[VertexT]] = sortedSetOrdering(vtxOrd)
def Simplex_is_OrderedCell[VertexT](using vtxOrd : Ordering[VertexT])(setOrdering : Ordering[Simplex[VertexT]] = simplexOrdering(using vtxOrd)): (Simplex[VertexT] is OrderedCell) =
  new(Simplex[VertexT] is OrderedCell) {
    override lazy val ordering = setOrdering
    extension (spx: Simplex[VertexT]) {
      override def dim = spx.size - 1
      override def boundary[CoefficientT: Field as fr]: Chain[Simplex[VertexT],CoefficientT] =
        if (spx.dim <= 0) Chain()
        else Chain.from(
          spx.zipWithIndex
            .map((vtx, i) => spx.dropIndex(i))
            .toSeq
            .zip(Iterator.unfold(fr.one)(s => Some((s, fr.negate(s)))))
        )
    }
  }
given default_Simplex_is_OrderedCell: [VertexT : Ordering] => (Simplex[VertexT] is OrderedCell) =
  Simplex_is_OrderedCell[VertexT]()
