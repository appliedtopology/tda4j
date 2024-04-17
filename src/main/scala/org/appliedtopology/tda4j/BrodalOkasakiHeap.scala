package org.appliedtopology.tda4j
package heap

/** A custom implementation of a Brodal-Okasaki functional Heap, with built-in support for collapsing on the top
  * elements.
  *
  * <https://www.brics.dk/RS/96/37/BRICS-RS-96-37.pdf>
  */

import collection.mutable
import math.Ordered.orderingToOrdered
import scala.annotation.tailrec
import scala.collection.immutable.{List, Seq}

// setup and type declarations

import math.Ordered.orderingToOrdered

final case class Cofree[F[_, _], A](coUnit: F[Cofree[F, A], A])
def cofree[F[_, _], A](counit: F[Cofree[F, A], A]) = Cofree(counit)

// First, common trait for both priority queues

trait Enqueueable

trait SBQ[T: Ordering] extends Enqueueable {
  val rank: Int
  def insert(t: T): SBQ[T]
  def union(other: SBQ[T]): SBQ[T]
  def head: Option[T]
  def last: Option[SBQ[T]]
  def children: Vector[SBQ[T]]
  def deleteHead(): SBQ[T]
  def toString: String
}
object SBQ {
  def apply[T: Ordering](): SBQ[T] = EmptySBQ()
  def apply[T: Ordering](t: T): SBQ[T] = NodeSBQ(t, 0, Vector.empty)
  def apply[T: Ordering](ts: T*): SBQ[T] = SBQ.from(ts)
  def from[T: Ordering](ts: Seq[T]): SBQ[T] = ts.foldLeft(SBQ())((sbq, t) => sbq.insert(t))
  def link[T: Ordering](left: SBQ[T], right: SBQ[T]): SBQ[T] = left match {
    case EmptySBQ() => right
    case NodeSBQ(value1, rank1, children1) =>
      right match {
        case EmptySBQ() => left
        case NodeSBQ(value2, rank2, children2) =>
          if (value1 <= value2) NodeSBQ(value1, rank1 + 1, children1.appended(right))
          else NodeSBQ(value2, rank2 + 1, children2.appended(left))
      }
  }
  def skewLink[T: Ordering](leafOf: T, first: SBQ[T], second: SBQ[T]): SBQ[T] = (first, second) match {
    case (EmptySBQ(), _) => throw IllegalArgumentException("Must skewLink with nonempty heaps")
    case (_, EmptySBQ()) => throw IllegalArgumentException("Must skewLink with nonempty heaps")
    case (NodeSBQ(v1, r1, c1), NodeSBQ(v2, r2, c2)) =>
      if (leafOf <= v1 & leafOf <= v2)
        NodeSBQ(leafOf, List(first.rank, second.rank).max + 1, Vector(first, second).sortBy(_.rank))
      else if (v1 <= v2) NodeSBQ(v1, List(r1, r2 + 1).max, c1.prepended(SBQ(leafOf)).appended(second))
      else NodeSBQ(v2, List(r2, r1 + 1).max, c2.prepended(SBQ(leafOf)).appended(first))
  }
}
case class EmptySBQ[T: Ordering]() extends SBQ[T] {
  override val rank: Int = -1
  override def insert(t: T): SBQ[T] = SBQ(t)
  override def union(other: SBQ[T]): SBQ[T] = other
  override def head: Option[T] = None
  override def last: Option[SBQ[T]] = None
  override def children: Vector[SBQ[T]] = Vector.empty
  override def deleteHead(): SBQ[T] = throw IllegalStateException("Cannot remove from empty queue")

  override def toString: String = "()"
}

case class NodeSBQ[T: Ordering](value: T, rank: Int, children: Vector[SBQ[T]]) extends SBQ[T] {

  override def insert(t: T): SBQ[T] =
    if (children.size >= 2) {
      val first = children(0)
      val second = children(1)
      if (first.rank == second.rank) {
        if (t <= value)
          NodeSBQ(t, first.rank + 2, children.drop(2).appended(SBQ.skewLink(value, first, second)))
        else
          NodeSBQ(value, first.rank + 2, children.drop(2).appended(SBQ.skewLink(t, first, second)))
      } else {
        if (t <= value)
          NodeSBQ(t, List(rank, 1).max, children.prepended(SBQ(value)))
        else
          NodeSBQ(value, List(rank, 1).max, children.prepended(SBQ(t)))
      }
    } else {
      if (t <= value)
        NodeSBQ(t, List(rank, 1).max, children.prepended(SBQ(value)))
      else
        NodeSBQ(value, List(rank, 1).max, children.prepended(SBQ(t)))
    }

  @tailrec
  private def meldIterators[T: Ordering](
    it1: collection.BufferedIterator[SBQ[T]],
    it2: collection.BufferedIterator[SBQ[T]],
    result: Vector[SBQ[T]]
  ): Vector[SBQ[T]] =
    (it1.hasNext, it2.hasNext) match {
      case (false, _) => result.appendedAll(it2)
      case (_, false) => result.appendedAll(it1)
      case (true, true) =>
        it1.head.rank.compare(it2.head.rank) match {
          case cmp if cmp < 0 => // it1 has lower rank, consume
            val e1 = it1.next()
            meldIterators(it1, it2, result.appended(e1))
          case cmp if cmp > 0 => // it2 has lower rank, consume
            val e2 = it2.next()
            meldIterators(it1, it2, result.appended(e2))
          case cmp if cmp == 0 => // same rank, link!
            val t1 = it1.next()
            val t2 = it2.next()
            val linkTree = SBQ.link(t1, t2)
            meldIterators(it1, it2, result.appended(linkTree))
        }
    }

  override def union(other: SBQ[T]): SBQ[T] = other match {
    case EmptySBQ() => this
    case NodeSBQ(value2, rank2, children2) =>
      if (value <= value2)
        NodeSBQ(value, rank + 1, meldIterators(children.iterator.buffered, children2.iterator.buffered, Vector.empty))
          .insert(value2)
      else
        NodeSBQ(value2, rank2 + 1, meldIterators(children.iterator.buffered, children2.iterator.buffered, Vector.empty))
          .insert(value)
  }

  override def head: Option[T] = Some(value)
  override def last: Option[SBQ[T]] = children.lastOption
  override def deleteHead(): SBQ[T] =
    if (children.isEmpty) EmptySBQ()
    else {
      val (NodeSBQ(newValue, newRank, newChildren), index) = children.zipWithIndex.minBy(_._1.head.get)
      val (leaves, sbq) = newChildren.partition(_.rank == 0)
      val newRoot = NodeSBQ(
        newValue,
        sbq.map(_.rank + 1).maxOption.getOrElse(0),
        meldIterators(sbq.iterator.buffered, children.patch(index, None, 1).iterator.buffered, Vector.empty)
      )
      leaves.foldLeft(newRoot: SBQ[T])((root, leaf) => root.insert(leaf.head.get))
    }

  override def toString: String =
    s"""($value $rank [${children.map(_.toString).mkString(" ")}])"""
}

package oldheaps {
  /*
  sealed trait HeapA[F: Linkable: Ordering, A: Ordering] {
    def rank: Int

    def forest: Forest[HeapA[F, A]]

    def insert(t: A): HeapA[F, A]

    def findMin: Option[A]

    def meld(other: HeapA[F, A]): HeapA[F, A]

    def deleteMin(): HeapA[F, A]

    def iterator: Iterator[A]

    def mapStructure[B](fun: (HeapA[F, A]) => B): Vector[B]
  }

  case class Node[F: Linkable: Ordering, A: Ordering](value: A, forest: Forest[F], rank: Int) extends HeapA[F, A] {
    override def insert(t: A): HeapA[F, A] = Node(t, EmptyForest(), 0).meld(this)

    override def findMin: Option[A] = Some(value)

    override def meld(other: Heap[A]): Heap[A] = other.coUnit match {
      case EmptyHeap() => cofree(this)
      case Node(a2, f2, r2): Node[F, A] =>
        if (value <= a2) Node(value, forest.insert(other), List(rank - 1, r2).max + 1).coUnit
        else Node(a2, f2.insert(this), List(r2 - 1, rank).max + 1).coUnit
    }

    override def deleteMin(): HeapA[F, A] =
      forest.findMin
        .map { minHeap =>
          val Node(a2, f2, r2): Node[F, A] = minHeap
          val newforest = f2.meld(forest.deleteMin())
          Node[F, A](a2, newforest, newforest.toVector.map(f => summon[Linkable[F]].rank(f)).max + 1)
        }
        .getOrElse(EmptyHeap[F, A]())

    override def iterator: Iterator[A] =
      Iterator.unfold(EmptyHeap[F, A]()) { (heap: HeapA[F, A]) =>
        heap.findMin.map((a: A) => (a, heap.deleteMin()))
      }

    override def mapStructure[B](fun: (F) => B): Vector[B] =
      forest.toVector.map(fun).prepended(fun(this))
  }

  case class EmptyHeap[F: Linkable: Ordering, A: Ordering]() extends HeapA[F, A] {
    override val rank: Int = -1
    override val forest: Forest[F] = EmptyForest()

    override def insert(t: A): HeapA[F, A] = Node[F, A](t, EmptyForest(), 0)

    override def findMin: Option[A] = throw IllegalStateException("Empty heap has no minimum")

    override def meld(other: F): F = other

    override def deleteMin(): F = throw IllegalStateException("Removing from empty heap")

    override def iterator: Iterator[A] = Iterator()

    override def mapStructure[B](fun: (Heap[A]) => B): Vector[B] = Vector(fun(cofree(this)))
  }

  type Heap[A] = Cofree[HeapA, A]

  // Pass-through methods for the Heap[A] type
  extension [A: Ordering](h: Heap[A]) {
    def rank: Int = h.coUnit.rank
    def forest: Forest[Heap[A]] = h.coUnit.forest.asInstanceOf[Forest[Heap[A]]] // ugly - can we do better?
    def insert(t: A): Heap[A] = cofree(h.coUnit.insert(t))
    def findMin: Option[A] = h.coUnit.findMin
    def meld(other: Heap[A]): Heap[A] = cofree(h.coUnit.meld(other.coUnit))
    def deleteMin(): Heap[A] = cofree(h.coUnit.deleteMin())

    def iterator: Iterator[A] = h.coUnit.iterator
    def mapStructure[B](fun: (Heap[A]) => B): Vector[B] =
      h.coUnit.mapStructure((h: HeapA[Heap[A], A]) => fun(cofree(h)))
  }

  object Heap {
    def apply[A: Ordering](as: A*): Heap[A] = from(as)

    def from[A: Ordering](as: Seq[A]) =
      as.foldLeft(empty())((heap, a) => heap.insert(a))
  }

  given [A: Ordering]: Ordering[Heap[A]] with {
    def compare(left: Heap[A], right: Heap[A]): Int = left.coUnit.match {
      case EmptyHeap() => -1
      case Node(a1, f1, r1) =>
        right.coUnit.match {
          case EmptyHeap()      => 1
          case Node(a2, f2, r2) => summon[Ordering[A]].compare(a1, a2)
        }
    }
  }

  given [A: Ordering]: Linkable[Heap[A]] with {
    override def rank(t: Heap[A]): Int = t.rank

    override def children(root: Heap[A]): Seq[Heap[A]] = root.forest.toVector

    override def link(left: Heap[A], right: Heap[A]): Heap[A] = (left.coUnit, right.coUnit) match {
      case (EmptyForest(), _) => right
      case (_, EmptyForest()) => left
      case (Node(a1, f1, r1), Node(a2, f2, r2)) =>
        if (a1 <= a2) node(a1, f1.appended(node(a2, f2)))
        else node(a2, f2.appended(node(a1, f1)))
    }

    override def skewLink(leaf: Heap[A], first: Heap[A], second: Heap[A]): Heap[A] =
      if (leaf.rank != 0 | first.rank < 0 | second.rank < 0)
        throw IllegalArgumentException("skewLink requires a leaf and two non-empty heaps")
      else {
        val Node(aL, _, _) = leaf.coUnit
        val Node(a1, f1, r1) = first.coUnit
        val Node(a2, f2, r2) = second.coUnit
        if (aL <= a1 & aL <= a2)
          node(aL, Forest(Vector(first, second)))
        else if (a1 <= a2)
          node(a1, f1.prepended(second).prepended(leaf))
        else
          node(a2, f2.prepended(first).prepended(leaf))
      }
  }

  def node[A: Ordering](a: A, f: Forest[Heap[A]]): Heap[A] =
    cofree(Node(a, f, f.toVector.map(_.rank).maxOption.getOrElse(-1) + 1))
  def leaf[A: Ordering](a: A): Heap[A] = node(a, EmptyForest())
  def empty[A: Ordering](): Heap[A] = cofree(EmptyHeap())

  sealed trait Forest[T: Linkable] {
    def toVector: Vector[T]

    def prepended(t: T): Forest[T]

    def appended(t: T): Forest[T]
  }

  object Forest {
    def apply[T: Linkable](vT: Vector[T]): Forest[T] = vT.headOption match {
      case None    => EmptyForest()
      case Some(t) => NonemptyForest(t, vT.tail)
    }
  }

  case class EmptyForest[T: Linkable]() extends Forest[T] {
    override def toVector: Vector[T] = Vector.empty

    override def prepended(t: T): Forest[T] = NonemptyForest(t, Vector.empty)

    override def appended(t: T): Forest[T] = NonemptyForest(t, Vector.empty)
  }

  case class NonemptyForest[T: Linkable](first: T, rest: Vector[T]) extends Forest[T] {
    override def toVector: Vector[T] = rest.prepended(first)

    override def prepended(t: T): Forest[T] =
      NonemptyForest(t, rest.prepended(first))

    override def appended(t: T): Forest[T] =
      NonemptyForest(first, rest.appended(t))
  }

  // first, the operations of a skew binomial queue
  // these operate on _Forests_

  trait Linkable[T] {
    def rank(t: T): Int

    def link(left: T, right: T): T

    def skewLink(leaf: T, first: T, second: T): T

    def children(root: T): Seq[T]
  }

  extension [T: Ordering](f: Forest[T])(using tLinkable: Linkable[T]) {
    def insert(t: T): Forest[T] = f match {
      case EmptyForest() => NonemptyForest(t, Vector.empty)
      case NonemptyForest(first, rest) =>
        if (rest.headOption.map(h => tLinkable.rank(h)).contains(tLinkable.rank(first)))
          // first two entries in forest have same rank; time to skew-link
          NonemptyForest(tLinkable.skewLink(t, first, rest.head), rest.tail)
        else
          f.prepended(t)
    }
    def findMin: Option[T] = f.toVector.minOption
    def meld(other: Forest[T]): Forest[T] =
      Forest(meldIterators(f.toVector.iterator.buffered, other.toVector.iterator.buffered, Vector.empty))
    def deleteMin(): Forest[T] = f.toVector.zipWithIndex.minByOption(_._1) match {
      case None         => throw IllegalStateException("Cannot delete from an empty forest")
      case Some((t, i)) => Forest(tLinkable.children(t).toVector).meld(Forest(f.toVector.patch(i, None, 1)))
    }
  }

  @tailrec
  def meldIterators[T](
    it1: collection.BufferedIterator[T],
    it2: collection.BufferedIterator[T],
    result: Vector[T]
  )(using tLinkable: Linkable[T]): Vector[T] =
    (it1.hasNext, it2.hasNext) match {
      case (false, _) => result.appendedAll(it2)
      case (_, false) => result.appendedAll(it1)
      case (true, true) =>
        tLinkable.rank(it1.head).compare(tLinkable.rank(it2.head)) match {
          case cmp if cmp < 0 => // it1 has lower rank, consume
            val e1 = it1.next()
            meldIterators(it1, it2, result.appended(e1))
          case cmp if cmp > 0 => // it2 has lower rank, consume
            val e2 = it2.next()
            meldIterators(it1, it2, result.appended(e2))
          case cmp if cmp == 0 => // same rank, link!
            val t1 = it1.next()
            val t2 = it2.next()
            val linkTree = tLinkable.link(t1, t2)
            meldIterators(it1, it2, result.appended(linkTree))
        }
    }

  package olderheaps {
    class RecursiveHeap[A: Ordering]() {
      def from(items: Seq[A]): Heap =
        items.foldLeft(nil)((heap, x) => heap.insert(x))

      sealed trait Forest[F] {
        def headOption: Option[(Int, F)]

        def secondOption: Option[(Int, F)]

        def lastOption: Option[(Int, F)]

        def appended(value: (Int, F)): Forest[F]

        def prepended(value: (Int, F)): Forest[F]

        def toVector: Vector[(Int, F)]
      }

      object Forest {
        def from[F](v: Vector[(Int, F)]) = v.size match {
          case 0          => EmptyForest[F]()
          case n if n > 0 => NonEmptyForest[F](v.head, v.tail)
        }
      }

      case class EmptyForest[F]() extends Forest[F] {
        val headOption = None
        val secondOption = None
        val lastOption = None
        val toVector = Vector.empty

        override def appended(value: (Int, F)): Forest[F] = NonEmptyForest(value, Vector.empty)

        override def prepended(value: (Int, F)): Forest[F] = NonEmptyForest(value, Vector.empty)
      }

      case class NonEmptyForest[F](val first: (Int, F), val queue: Vector[(Int, F)]) extends Forest[F] {
        def head: (Int, F) = first

        def headPair: ((Int, F), Option[(Int, F)]) = (first, queue.headOption)

        override def headOption: Option[(Int, F)] = Some(first)

        override def secondOption: Option[(Int, F)] = queue.headOption

        override def lastOption: Option[(Int, F)] = queue.lastOption.orElse(Some(first))

        override def appended(value: (Int, F)): Forest[F] = copy(queue = queue.appended(value))

        override def prepended(value: (Int, F)): Forest[F] =
          copy(first = value, queue = toVector)

        def toVector = queue.prepended(first)
      }

      import math.Ordered.orderingToOrdered

      final case class Fix[F[_]](unfix: F[Fix[F]])

      sealed trait HeapA[F] {
        def rank: Int

        def isNil: Boolean

        def valueOption: Option[A]

        def forestOption: Option[Forest[F]]
      }

      final case class Node[F](
        a: A,
        forest: Forest[F], // pairs: rank, heap, in increasing order of rank
        rank: Int
      ) extends HeapA[F] {
        val isNil = false
        val valueOption = Some(a)
        val forestOption = Some(forest)
      }

      final case class Nil[F]() extends HeapA[F] {
        val rank = -1
        val isNil = true
        val valueOption = None
        val forestOption = None
      }

      type Heap = Fix[HeapA]

      def nil: Heap = Fix(Nil())

      def node(a: A, forest: Forest[Heap], rank: Int) =
        Fix(Node(a, forest, rank))

      def node(a: A, forest: Forest[Heap]): Heap =
        node(a, forest, forest.lastOption.map(_._1).getOrElse(-1) + 1)

      def leaf(a: A): Heap = node(a, EmptyForest())

      object HeapNode {
        def unapply(x: Heap): Option[(A, Forest[Heap], Int)] = x.unfix match {
          case Nil()         => None
          case Node(a, f, r) => Some((a, f, r))
        }
      }

      extension (h: Heap) {
        def rank: Int = h.unfix.rank
        def isNil: Boolean = h.unfix.isNil
        def valueOption: Option[A] = h.unfix.valueOption
        def forestOption: Option[Forest[Heap]] = h.unfix.forestOption

        def findMin: Option[A] = valueOption
        def insert(a: A): Heap = h.unfix match {
          case Nil() => leaf(a)
          case Node(a2, f, r) =>
            if (a < a2)
              node(a, meldLeaf(leaf(a2), f))
            else
              node(a2, meldLeaf(leaf(a), f))
        }
        def deleteMin(): Heap = h.unfix match {
          case Nil() => throw IllegalStateException("Cannot delete from empty thisHeap")
          case Node(a, f, r) =>
            f match {
              case EmptyForest() => nil
              case NonEmptyForest(fst, q) =>
                val fullQ = q.prepended(fst)
                val ((_, HeapNode(minA, minF, minR)), i) = fullQ.zipWithIndex
                  .minBy((rt, i) => HeapNode.unapply(rt._2).map(_._1).get)

                val (minFleaves, minFrest): (Vector[(Int, Heap)], Vector[(Int, Heap)]) =
                  (minF.toVector).partition(_._2.rank == 0)
                val rest = fullQ.patch(i, List(), 1)
                val newForest: Forest[Heap] = Forest.from(meldVector(rest, minFrest))
                minFleaves.foldLeft(node(minA, newForest))((h, entry) => h.insert(entry._2.valueOption.get))
            }
        }
        def meld(that: Heap): Heap =
          if (h.isNil) that
          else if (that.isNil) h
          else {
            val HeapNode(x1, q1, r1) = h
            val HeapNode(x2, q2, r2) = that
            val xs = List(x1, x2)
            val innerNode = node(xs.max, meldForest(q1, q2))
            node(xs.min, Forest.from(Vector((innerNode.rank, innerNode))))
          }

        def iterator(): Iterator[A] =
          Iterator.unfold(h)(heap => heap.findMin.map(a => (a, h.deleteMin())))

      }

      def link(left: Heap, right: Heap): Heap = (left.unfix, right.unfix) match {
        case (Nil(), _) => right
        case (_, Nil()) => left
        case (Node(a1, q1, r1), Node(a2, q2, r2)) =>
          if (a1 <= a2)
            Fix(Node(a1, q1.appended((r2, right)), r1 + 1))
          else
            Fix(Node(a2, q2.appended((r1, left)), r2 + 1))
      }

      def skewLink(leaf: Heap, first: Heap, second: Heap): Heap =
        if (leaf.isNil | first.isNil | second.isNil) throw IllegalArgumentException("Must link nonempty heaps")
        else {
          val HeapNode(aL, fL, rL) = leaf: @unchecked
          val HeapNode(a1, f1, r1) = first: @unchecked
          val HeapNode(a2, f2, r2) = second: @unchecked

          if (rL != 0)
            throw IllegalStateException("skewlink must be for a leaf")
          else {
            if (aL <= a1 & aL <= a2)
              if (r1 <= r2)
                Fix(Node(aL, NonEmptyForest((r1, first), Vector((r2, second))), r2 + 1))
              else
                Fix(Node(aL, NonEmptyForest((r2, second), Vector((r1, first))), r1 + 1))
            else if (a1 <= aL & a1 <= a2)
              Fix(Node(a1, NonEmptyForest((rL, leaf), Vector((r2, second))), r2 + 1))
            else
              Fix(Node(a2, NonEmptyForest((rL, leaf), Vector((r1, first))), r1 + 1))
          }
        }

      @tailrec
      final def meldIterators(
        it1: collection.BufferedIterator[(Int, Heap)],
        it2: collection.BufferedIterator[(Int, Heap)],
        result: Vector[(Int, Heap)]
      ): Vector[(Int, Heap)] =
        (it1.hasNext, it2.hasNext) match {
          case (false, _) => result.appendedAll(it2)
          case (_, false) => result.appendedAll(it1)
          case (true, true) =>
            it1.head._1.compare(it2.head._1) match {
              case cmp if cmp < 0 => // it1 has lower rank, consume
                val e1 = it1.next()
                meldIterators(it1, it2, result.appended(e1))
              case cmp if cmp > 0 => // it2 has lower rank, consume
                val e2 = it2.next()
                meldIterators(it1, it2, result.appended(e2))
              case cmp if cmp == 0 => // same rank, link!
                val (r1, t1) = it1.next()
                val (r2, t2) = it2.next()
                val linkTree = link(t1, t2)
                meldIterators(it1, it2, result.appended((linkTree.unfix.rank, linkTree)))
            }
        }

      def meldVector(left: Vector[(Int, Heap)], right: Vector[(Int, Heap)]): Vector[(Int, Heap)] =
        meldIterators(left.iterator.buffered, right.iterator.buffered, Vector.empty)

      def meldForest(left: Forest[Heap], right: Forest[Heap]): Forest[Heap] =
        Forest.from(meldVector(left.toVector, right.toVector))

      def meldLeaf(leaf: Heap, queue: Forest[Heap]): Forest[Heap] = leaf.unfix match {
        case Nil() => throw IllegalStateException("Should not try to meld Nil")
        case Node(a1: A, q1: Forest[Heap], r1) =>
          queue match {
            case EmptyForest() => NonEmptyForest((leaf.rank, leaf), Vector.empty)
            case NonEmptyForest((r2: Int, t2: Heap), q2: Vector[(Int, Heap)]) => // this is the interesting case
              if (q2.nonEmpty) {
                val (r3, t3) = q2.head
                if (r2 == r3) {
                  val newTree = skewLink(leaf, t2, t3)
                  NonEmptyForest((newTree.rank, newTree), q2.tail)
                } else {
                  NonEmptyForest((r1, leaf), q2.prepended((r2, t2)))
                }
              } else {
                NonEmptyForest((r1, leaf), Vector((r2, t2)))
              }
          }
      }
    }

    package newheap {

      import math.Ordered.orderingToOrdered
      import scala.runtime.stdLibPatches.Predef.summon

      final case class Cofree[F[_, _], A](coUnit: F[Cofree[F, A], A])

      def cofree[F[_, _], A](counit: F[Cofree[F, A], A]) = Cofree(counit)

      trait Linkable[T] {
        def link(left: T, right: T): T

        def skewLink(leaf: T, first: T, second: T): T

        def rank(t: T): Int
      }

      sealed trait HeapA[F, A: Ordering] {
        def rank: Int

        def isNil: Boolean

        def valueOption: Option[A]

        def forestOption: Option[Forest[F]]
      }

      case class Node[F, A: Ordering](value: A, forest: Forest[F], rank: Int) extends HeapA[F, A] {
        val isNil = false
        val valueOption = Some(value)
        val forestOption = Some(forest)
      }

      case class Nil[F, A: Ordering]() extends HeapA[F, A] {
        val rank = -1
        val isNil = false
        val valueOption = None
        val forestOption = None
      }

      type Heap[A] = Cofree[HeapA, A]

      object Heap {
        def unapply[A](heap: Heap[A]) = heap.coUnit match {
          case Nil()         => Some((None, None, -1))
          case Node(a, f, r) => Some((Some(a), Some(f), r))
        }

        def apply[A: Ordering](): Heap[A] = nil

        def apply[A: Ordering](a: A): Heap[A] = node(a, EmptyForest())

        def apply[A: Ordering](as: A*): Heap[A] =
          if (as.size == 0) nil
          else as.tail.foldLeft(apply(as.head))((heap, a) => heap.insert(a))
      }

      def nil[A: Ordering]: Heap[A] = cofree(Nil())
      def node[A: Ordering](a: A, forest: Forest[Heap[A]]): Heap[A] =
        cofree(Node(a, forest, forest.toVector.map(_.rank).maxOption.getOrElse(-1) + 1))
      def leaf[A: Ordering](a: A): Heap[A] = cofree(Node(a, EmptyForest(), 0))

      extension [A: Ordering](thisHeap: Heap[A]) {
        def rank: Int = thisHeap.coUnit.rank
        def isNil: Boolean = thisHeap.coUnit.isNil
        def valueOption: Option[A] = thisHeap.coUnit.valueOption
        def forest: Forest[Heap[A]] = thisHeap.coUnit.forestOption.getOrElse(EmptyForest())
        def findMin: Option[A] = valueOption
        def headOption: Option[A] = valueOption
        def insert(a: A): Heap[A] = thisHeap.coUnit match {
          case Nil() => leaf(a)
          case Node(v, f, r) =>
            if (a < v)
              node(a, f.insert(leaf(v)))
            else
              node(v, f.insert(leaf(a)))
        }
        def deleteMin(): Heap[A] =
          forest.toVector.zipWithIndex.minByOption((h, i) => h.valueOption.get) match {
            case None => nil
            case Some((minHeap, minIdx)) =>
              val (minForestLeafs, minForest) = minHeap.forest.toVector.partition((h => h.rank == 0))
              val newForest = meldVector(minForest, forest.toVector.patch(minIdx, List(), 1))
              minForestLeafs
                .flatMap(_.headOption)
                .foldLeft(node(minHeap.valueOption.get, Forest(newForest))) { (h, a) =>
                  h.insert(a)
                }
          }
        def meld(that: Heap[A]): Heap[A] = that.coUnit match {
          case Nil() => thisHeap
          case Node(a2, f2, r2) =>
            thisHeap.coUnit match {
              case Nil() => that
              case Node(a1, f1, r1) =>
                node(List(a1, a2).min, Forest(meldVector(f1.toVector, f2.toVector))).insert(List(a1, a2).max)
            }
        }
        def union(that: Heap[A]): Heap[A] = meld(that)
        def iterator(): Iterator[A] =
          Iterator.unfold(thisHeap)(h => h.findMin.map(a => (a, h.deleteMin())))

        def pprint: String = thisHeap.coUnit match {
          case Nil()         => ""
          case Node(a, f, r) => s"($a ${f.pprint(_.pprint)} $r)"
        }

        def toList: List[A] = thisHeap.coUnit match {
          case Nil()         => List()
          case Node(a, f, r) => a :: f.toVector.toList.flatMap(_.toList)
        }

        def mapHeap[B: Ordering](fun: (A => B)): Heap[B] = thisHeap.coUnit match {
          case Nil() => nil
          case Node(a, f, r) =>
            node(fun(a), Forest(f.toVector.map((h: Heap[A]) => h.mapHeap(fun))))
        }

        def mapStructure[B](fun: (Heap[A] => B)): List[B] = thisHeap.coUnit match {
          case Nil()         => List()
          case Node(a, f, r) => fun(thisHeap) :: f.toVector.toList.flatMap(_.mapStructure(fun))
        }
      }

      @tailrec
      def meldIterators[A: Ordering](
        it1: collection.BufferedIterator[Heap[A]],
        it2: collection.BufferedIterator[Heap[A]],
        result: Vector[Heap[A]]
      ): Vector[Heap[A]] =
        (it1.hasNext, it2.hasNext) match {
          case (false, _) => result.appendedAll(it2)
          case (_, false) => result.appendedAll(it1)
          case (true, true) =>
            it1.head.rank.compare(it2.head.rank) match {
              case cmp if cmp < 0 => // it1 has lower rank, consume
                val e1 = it1.next()
                meldIterators(it1, it2, result.appended(e1))
              case cmp if cmp > 0 => // it2 has lower rank, consume
                val e2 = it2.next()
                meldIterators(it1, it2, result.appended(e2))
              case cmp if cmp == 0 => // same rank, link!
                val t1 = it1.next()
                val t2 = it2.next()
                val linkTree = summon[Linkable[Heap[A]]].link(t1, t2)
                meldIterators(it1, it2, result.appended(linkTree))
            }
        }

      def meldVector[A: Ordering](left: Vector[Heap[A]], right: Vector[Heap[A]]): Vector[Heap[A]] =
        meldIterators(left.iterator.buffered, right.iterator.buffered, Vector.empty)

      given heapLinkable[A: Ordering]: Linkable[Heap[A]] with {
        def link(left: Heap[A], right: Heap[A]) =
          (for
            leftValue <- left.valueOption
            rightValue <- right.valueOption
          yield
            if (leftValue <= rightValue)
              node(leftValue, left.forest.insert(right))
            else
              node(rightValue, right.forest.insert(left))).getOrElse(
            if (left.isNil) right
            else left
          )

        def skewLink(leaf: Heap[A], first: Heap[A], second: Heap[A]): Heap[A] =
          if (leaf.rank != 0) throw IllegalArgumentException("Skewlink requires a leaf")
          else {
            (for
              leafValue <- leaf.valueOption
              firstValue <- first.valueOption
              secondValue <- second.valueOption
            yield
              if (leafValue <= firstValue & leafValue <= secondValue)
                node(leafValue, NonemptyForest(first, Vector(second)))
              else if (firstValue <= secondValue)
                node(firstValue, NonemptyForest(leaf, Vector(second)))
              else
                node(secondValue, NonemptyForest(leaf, Vector(first)))).get
          }

        def rank(t: Heap[A]): Int = t.rank
      }

      sealed trait Forest[T: Linkable] {
        def toVector: Vector[T]

        def headOption: Option[T]

        def secondOption: Option[T]

        def lastOption: Option[T]

        def appended(value: T): Forest[T]

        def prepended(value: T): Forest[T]

        def insert(value: T): Forest[T]

        def pprint(print: T => String): String
      }

      object Forest {
        def apply[T: Linkable](ts: Vector[T]): Forest[T] =
          if (ts.isEmpty) EmptyForest[T]()
          else NonemptyForest[T](ts.head, ts.tail)
      }

      case class EmptyForest[T: Linkable]() extends Forest[T] {
        override val toVector: Vector[T] = Vector.empty
        override val headOption: Option[T] = None
        override val secondOption: Option[T] = None
        override val lastOption: Option[T] = None

        override def appended(value: T): Forest[T] = NonemptyForest[T](value, Vector.empty)

        override def prepended(value: T): Forest[T] = NonemptyForest[T](value, Vector.empty)

        override def insert(value: T): Forest[T] = appended(value)

        override def pprint(print: T => String): String = "[]"
      }

      case class NonemptyForest[T: Linkable](first: T, rest: Vector[T]) extends Forest[T] {
        override val toVector: Vector[T] = rest.prepended(first)
        override val headOption: Option[T] = Some(first)
        override val secondOption: Option[T] = rest.headOption
        override val lastOption: Option[T] = rest.lastOption.orElse(Some(first))

        override def appended(value: T): Forest[T] = NonemptyForest[T](first, rest.appended(value))

        override def prepended(value: T): Forest[T] = NonemptyForest[T](value, rest.prepended(first))

        override def insert(value: T): Forest[T] = {
          val tLink = summon[Linkable[T]]
          secondOption match {
            case None => prepended(value)
            case Some(t) =>
              if (tLink.rank(first) == tLink.rank(t))
                NonemptyForest(tLink.skewLink(value, first, t), rest.tail)
              else
                prepended(value)
          }
        }

        override def pprint(print: T => String): String = toVector.map(print).mkString("[", ",", "]")
      }
    }

  }
   */
}
