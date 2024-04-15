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

case class BOHeap[E: Ordering](root: Option[Node[E]]) {
  def findMin = root.flatMap(_.findMin)
  def insert(x: E) = root match {
    case None       => BOHeap(Some(Node(x)))
    case Some(node) => BOHeap(root.map(_.insert(x)))
  }
  def deleteMin(): BOHeap[E] =
    if (root.map(_.rank) == Some(0)) BOHeap(None)
    else BOHeap(root.map(_.deleteMin()))

  /** Apply function `f` to all entries in the heap.
    *
    * This assumes that `f` is monotonic - if not, any heap property guarantees are invalidated.
    *
    * To use a non-monotonic function, use `map` to get a `List`, and then re-add everything.
    */
  def mapHeap[F: Ordering](f: (E => F)): BOHeap[F] =
    BOHeap(root.map(_.map(f)))

  def toList(): List[E] = root match {
    case None    => List()
    case Some(n) => n.toList()
  }

  def dequeue(): (Option[E], BOHeap[E]) =
    if (root.map(_.rank).forall(_ <= 0)) (findMin, BOHeap.empty)
    else {
      (findMin, deleteMin())
    }

  def dequeueAll(): Seq[E] =
    Seq.unfold[E, BOHeap[E]](this) { boh =>
      val (oe, boh2) = boh.dequeue()
      oe.map((_, boh2))
    }
}

object BOHeap {
  def empty[E: Ordering]: BOHeap[E] = BOHeap(None)

  def from[E: Ordering](coll: IterableOnce[E]) =
    BOHeap(Node.from(coll))
}
case class Node[E: Ordering](elem: E, rank: Int, forest: List[Node[E]]) {
  def link(that: Node[E]): Node[E] =
    if (rank != that.rank)
      throw IllegalArgumentException("Simple links require equal ranks")
    else if (elem < that.elem)
      Node(elem, rank + 1, that :: forest)
    else
      Node(that.elem, that.rank + 1, this :: that.forest)

  def findMin: Option[E] =
    (elem :: (forest.map(_.elem))).minOption

  def insert(x: E): Node[E] =
    if (x < elem)
      copy(elem = x).insert(elem)
    else
      forest.size match {
        case 0 => copy(forest = List(Node(x)), rank = rank + 1)
        case 1 =>
          copy(forest = Node(x) :: forest, rank = forest.map(_.rank).max + 1)
        case s if s > 1 =>
          val t1 :: t2 :: ttail = forest: @unchecked
          if (t1.rank == t2.rank) {
            val newforest = Node.skewLink(Node(x), t1, t2) :: ttail
            copy(
              forest = newforest.sortBy(_.rank),
              rank = newforest.map(_.rank).max + 1
            )
          } else copy(forest = Node(x) :: forest, rank = forest.map(_.rank).max + 1)
      }

  def deleteMin(): Node[E] = {
    if (forest.isEmpty)
      throw IllegalStateException("Removing from 1-element rooted heap")
    val minTreeIdx = forest.map(_.elem).zipWithIndex.minBy(_._1)._2
    val minTree = forest(minTreeIdx)
    val remainingForest = forest.patch(minTreeIdx, Nil, 1)
    val (singletons, queue) = remainingForest.partition(_.rank == 0)
    val newForest = Node.meld(minTree.forest, queue)
    val seedTree =
      minTree.copy(
        forest = newForest.sortBy(_.rank),
        rank = newForest.map(_.rank).maxOption.getOrElse(-1) + 1
      )
    singletons.foldLeft(seedTree) { (tree, leaf) =>
      tree.insert(leaf.elem)
    }
  }

  def map[F: Ordering](f: (E => F)): Node[F] =
    copy(elem = f(elem), forest = forest.map(_.map(f)))

  /** Return a list with all values. No guarantees that the list is in any useful order, except that the global minimum
    * will be first.
    */
  def toList(): List[E] =
    elem :: forest.flatMap(_.toList())
}
object Node {
  def apply[E: Ordering](elem: E): Node[E] =
    Node(elem, 0, List.empty)

  def from[E: Ordering](coll: IterableOnce[E]): Option[Node[E]] = {
    val collit = coll.iterator
    if (collit.isEmpty) None
    else {
      val x = collit.next()
      collit.foldLeft(Some(Node(x)): Option[Node[E]]) { (tree, value) =>
        tree.map(_.insert(value))
      }
    }
  }

  def skewLink[E: Ordering](l: Node[E], t1: Node[E], t2: Node[E]): Node[E] =
    if (l.rank > 0) throw IllegalArgumentException("Leaf in skew link too deep")
    else {
      if (l.elem <= t1.elem & l.elem <= t2.elem)
        Node(l.elem, l.rank + 1, List(t1, t2).sortBy(_.rank))
      else if (t1.elem <= l.elem & t1.elem <= t2.elem)
        Node(t1.elem, t1.rank + 1, (l :: t2 :: t1.forest).sortBy(_.rank))
      else
        Node(t2.elem, t2.rank + 1, (l :: t1 :: t2.forest).sortBy(_.rank))
    }

  def meld[E: Ordering](l1: List[Node[E]], l2: List[Node[E]]): List[Node[E]] = {
    val it1 = l1.iterator.buffered
    val it2 = l2.iterator.buffered
    val buf: mutable.ListBuffer[Node[E]] = mutable.ListBuffer()
    while (it1.hasNext & it2.hasNext)(it1.head.rank
      .compare(it2.head.rank)) match {
      case 0 => // equal rank; simple link
        buf.append(it1.next.link(it2.next))
      case x if x > 0 => // it1 has the larger rank
        buf.append(it2.next)
      case x if x < 0 => // it2 has the larger rank
        buf.append(it1.next)
    }
    buf.appendAll(it1)
    buf.appendAll(it2)
    buf.toList
  }
}

class RecursiveHeap[A: Ordering]() {
  def from(items: Seq[A]): Heap =
    items.foldLeft(nil)((heap, x) => insert(x, heap))

  trait Forest[F] {
    def lastOption: Option[(Int, F)]

    def appended(value: (Int, F)): Forest[F]
  }

  case class EmptyForest[F]() extends Forest[F] {
    val lastOption = None

    override def appended(value: (Int, F)): Forest[F] = NonEmptyForest(value, Vector.empty)
  }

  case class NonEmptyForest[F](val first: (Int, F), val queue: Vector[(Int, F)]) extends Forest[F] {
    def head: (Int, F) = first

    def headPair: ((Int, F), Option[(Int, F)]) = (first, queue.headOption)

    def lastOption: Option[(Int, F)] = queue.lastOption.orElse(Some(first))

    override def appended(value: (Int, F)): Forest[F] = copy(queue = queue.appended(value))
  }

  import math.Ordered.orderingToOrdered

  final case class Fix[F[_]](unfix: F[Fix[F]])

  sealed trait HeapA[F] {
    def rank: Int
    def isNil: Boolean
  }

  final case class Node[F](
    a: A,
    forest: Forest[F], // pairs: rank, heap, in increasing order of rank
    rank: Int
  ) extends HeapA[F] {
    val isNil = false
  }

  final case class Nil[F]() extends HeapA[F] {
    val rank = -1
    val isNil = true
  }

  type Heap = Fix[HeapA]

  def nil: Heap = Fix(Nil())

  def node(a: A, forest: Forest[Heap]): Heap =
    Fix(Node(a, forest, forest.lastOption.map(_._1).getOrElse(-1) + 1))

  def leaf(a: A): Heap = node(a, EmptyForest())

  object extractor {
    def unapply(x: Heap): Option[(A, Forest[Heap], Int)] = x.unfix match {
      case Nil()         => None
      case Node(a, f, r) => Some((a, f, r))
    }
  }

  def findMin(h: Heap): Option[A] = h.unfix match {
    case Nil()            => None
    case Node(a: A, _, _) => Some(a)
  }

  def insert(a: A, h: Heap): Heap = h.unfix match {
    case Nil() => leaf(a)
    case Node(a2, f, r) =>
      if (a < a2)
        node(a, meldLeaf(leaf(a2), f))
      else
        node(a2, meldLeaf(leaf(a), f))
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
    if (leaf.unfix.isNil | first.unfix.isNil | second.unfix.isNil)
      throw IllegalArgumentException("Must link nonempty heaps")
    else {
      val Some((aL, fL, rL)) = extractor.unapply(leaf): @unchecked
      val Some((a1, f1, r1)) = extractor.unapply(first): @unchecked
      val Some((a2, f2, r2)) = extractor.unapply(second): @unchecked

      if (rL != 0)
        throw IllegalStateException("skewlink must be for a leaf")
      else {
        if(aL <= a1 & aL <= a2)
          if(r1 <= r2)
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
    result: List[(Int, Heap)]
  ): List[(Int, Heap)] =
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

  def meldVector(left: Vector[(Int, Heap)], right: Vector[(Int, Heap)]): List[(Int, Heap)] =
    meldIterators(left.iterator.buffered, right.iterator.buffered, List.empty)

  def meldForest(left: Forest[Heap], right: Forest[Heap]): Forest[Heap] = (left, right) match {
    case (EmptyForest(), _) => right
    case (_, EmptyForest()) => left
    case (NonEmptyForest(e1, q1), NonEmptyForest(e2, q2)) =>
      val melded: List[(Int, Heap)] = meldVector(q1.prepended(e1), q2.prepended(e2))
      NonEmptyForest(melded.head, melded.tail.toVector)
  }

  def meldLeaf(leaf: Heap, queue: Forest[Heap]): Forest[Heap] = leaf.unfix match {
    case Nil() => throw IllegalStateException("Should not try to meld Nil")
    case Node(a1: A, q1: Forest[Heap], r1) =>
      queue match {
        case EmptyForest() => NonEmptyForest((leaf.unfix.rank, leaf), Vector.empty)
        case NonEmptyForest((r2: Int, t2: Heap), q2: Vector[(Int, Heap)]) => // this is the interesting case
          if (q2.nonEmpty) {
            val (r3, t3) = q2.head
            if (r2 == r3) {
              val newTree = skewLink(leaf, t2, t3)
              NonEmptyForest((newTree.unfix.rank, newTree), q2.tail)
            } else {
              NonEmptyForest((r1, leaf), q2.prepended((r2, t2)))
            }
          } else {
            NonEmptyForest((r1, leaf), Vector((r2, t2)))
          }
      }
  }

  def deleteMin(h: Heap): Heap = h.unfix match {
    case Nil() => h
    case Node(a,f,r) => {
      f match {
        case EmptyForest() => Fix(Nil())
        case NonEmptyForest(fst,q) =>
          val fullQ = q.prepended(fst)
          val (minQ,i) = fullQ.zipWithIndex
            .minBy{ (rt,i) => extractor.unapply(rt._2).map(_._1).get }
          val minQN = minQ._2.asInstanceOf[Fix[Node]]
          val minQF: Forest[Heap] = extractor.unapply(minQ._2).map(_._2).get
          val (r0s, qs): (Vector[(Int, Heap)], Vector[(Int, Heap)]) = (minQF match {
            case EmptyForest() => Vector()
            case NonEmptyForest(first, queue) => queue.prepended(first)
          }).partition(_._2.unfix.rank == 0)
          val rest = fullQ.patch(i,List(),1)
          val newForest : Forest[Heap] = if(rest.size > 0) {
              if (qs.size > 0)
                meldForest(NonEmptyForest(rest.head, rest.tail), NonEmptyForest(qs.head, qs.tail))
              else
                NonEmptyForest(rest.head, rest.tail)
          } else {
            if(qs.size > 0) NonEmptyForest(qs.head, qs.tail)
            else EmptyForest()
          }
          val minA = minQN.unfix.a
          r0s.foldLeft(node(minA,newForest)){(h,entry) => insert(entry._2.asInstanceOf[Fix[Node]].unfix.a,h)}
      }
    }
  }

  def iterator(heap: Heap): Iterator[A] =
    Iterator.unfold(heap){ (h) => findMin(h).map{ (a) => (a,deleteMin(h))}}
}
