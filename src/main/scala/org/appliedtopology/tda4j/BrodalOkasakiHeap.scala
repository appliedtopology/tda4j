package org.appliedtopology.tda4j


package heap {

  /** A custom implementation of a Brodal-Okasaki functional Heap, with built-in support for collapsing on the top
   * elements.
   *
   * <https://www.brics.dk/RS/96/37/BRICS-RS-96-37.pdf>
   */

  import scala.annotation.{tailrec, targetName}
  import scala.collection.immutable.{List, Seq}
  import math.Ordered.orderingToOrdered

  // Skew Binomial Trees

  sealed trait SBTree[T: Ordering] {
    def rank: Int

    def headOption: Option[T]

    def children: Vector[SBTree[T]]
  }

  case class EmptySBT[T: Ordering]() extends SBTree[T] {
    override val rank: Int = -1
    override val headOption: Option[T] = None
    override val children: Vector[SBTree[T]] = Vector.empty
  }

  case class LeafSBT[T: Ordering](t: T) extends SBTree[T] {
    override val rank: Int = 0

    override def headOption: Option[T] = Some(t)

    override val children: Vector[SBTree[T]] = Vector.empty
  }

  case class NodeSBT[T: Ordering](t: T, children: Vector[SBTree[T]], rank: Int) extends SBTree[T] {
    override def headOption: Option[T] = Some(t)
  }

  object SBTree {
    def link[T: Ordering](left: SBTree[T], right: SBTree[T]): SBTree[T] = (left, right) match {
      case (EmptySBT(), EmptySBT()) => EmptySBT()
      case (LeafSBT(t1), LeafSBT(t2)) => NodeSBT(List(t1, t2).min, Vector(LeafSBT(List(t1, t2).max)), 1)
      case (NodeSBT(t1, c1, r1), NodeSBT(t2, c2, r2)) if r1 == r2 =>
        if (t1 <= t2) NodeSBT(t1, c1.prepended(right), r1 + 1)
        else NodeSBT(t2, c2.prepended(left), r2 + 1)
      case _ => throw IllegalArgumentException("Must call link with equal rank trees")
    }

    def skewLink[T: Ordering](leaf: LeafSBT[T], first: SBTree[T], second: SBTree[T]): SBTree[T] = (first, second) match {
      case (EmptySBT(), _) | (_, EmptySBT()) => throw IllegalArgumentException("Must call skewLink with nonempty trees")
      case (LeafSBT(t1), LeafSBT(t2)) =>
        val List(a, b, c) = List(leaf.t, t1, t2).sorted
        NodeSBT(a, Vector(LeafSBT(b), LeafSBT(c)), 1)
      case (NodeSBT(t1, c1, r1), NodeSBT(t2, c2, r2)) if r1 == r2 =>
        if (leaf.t <= t1 & leaf.t <= t2) NodeSBT(leaf.t, Vector(first, second), first.rank + 1)
        else if (t1 <= t2) NodeSBT(t1, c1.prepended(second).prepended(leaf), second.rank + 1)
        else NodeSBT(t2, c2.prepended(first).prepended(leaf), first.rank + 1)
      case _ => throw IllegalArgumentException("Must call skewLink with equal rank trees")
    }
  }

  // Skew Binomial Queues - only first pair may share rank

  case class SBQueue[T: Ordering](forest: Vector[SBTree[T]]) {
    def insert(t: T): SBQueue[T] =
      if (forest.size >= 2 && (forest(0).rank == forest(1).rank)) {
        SBQueue(forest.drop(2).prepended(SBTree.skewLink(LeafSBT(t), forest(0), forest(1))))
      } else {
        SBQueue(forest.prepended(LeafSBT(t)))
      }

    def union(that: SBQueue[T]) =
      SBQueue(unionRecursion(forest.iterator.buffered, that.forest.iterator.buffered, Vector.empty))

    @tailrec
    final def unionRecursion(
                              it1: collection.BufferedIterator[SBTree[T]],
                              it2: collection.BufferedIterator[SBTree[T]],
                              acc: Vector[SBTree[T]]
                            ): Vector[SBTree[T]] =
      if (it1.isEmpty) acc.appendedAll(it2)
      else if (it2.isEmpty) acc.appendedAll(it1)
      else if (it1.head.headOption.isEmpty) {
        it1.next()
        unionRecursion(it1, it2, acc)
      } else if (it2.head.headOption.isEmpty) {
        it2.next()
        unionRecursion(it1, it2, acc)
      } else if (it1.head.headOption <= it2.head.headOption) {
        val v1 = it1.next()
        unionRecursion(it1, it2, acc.appended(v1))
      } else if (it2.head.headOption <= it1.head.headOption) {
        val v2 = it2.next()
        unionRecursion(it1, it2, acc.appended(v2))
      } else if (it1.head.headOption == it2.head.headOption) {
        val v1 = it1.next()
        val v2 = it2.next()
        unionRecursion(it1, it2, acc.appended(SBTree.link(v1, v2)))
      } else throw IllegalStateException("This should have been an exhaustive list of options in the unionRecursion")

    def head: Option[T] = forest.flatMap(_.headOption).minOption

    def deleteHead(): SBQueue[T] =
      forest.zipWithIndex.flatMap((h, i) => h.headOption.map(t => (t, i))).minOption.map(_._2) match {
        case None => throw IllegalStateException("Cannot delete from empty queue")
        case Some(index) =>
          val siblings: Vector[SBTree[T]] = forest.patch(index, None, 1)
          val newHead: SBTree[T] = forest(index)
          val (leafs, orphans) = newHead.children.partition(_.rank == 0)
          val newChildren = unionRecursion(orphans.iterator.buffered, siblings.iterator.buffered, Vector.empty)
          val root = SBQueue(newChildren)
          leafs.foldLeft(root: SBQueue[T])((q, t) => q.insert(t.headOption.get))
      }
  }

  object SBQueue {
    def apply[T: Ordering](): SBQueue[T] = SBQueue(Vector.empty)

    def apply[T: Ordering](t: T): SBQueue[T] = SBQueue(Vector(LeafSBT(t)))

    def apply[T: Ordering](ts: T*): SBQueue[T] = from(ts)

    def from[T: Ordering](ts: Seq[T]): SBQueue[T] = ts.foldLeft(SBQueue(): SBQueue[T])((q, t) => q.insert(t))
  }

  // Bootstrapped globally rooted heaps

  case class Cofree[F[_, _], A](coUnit: F[Cofree[F, A], A])

  def cofree[F[_, _], A](counit: F[Cofree[F, A], A]) = Cofree(counit)

  sealed trait HeapA[F, A: Ordering] {
    def headOption: Option[A]

    def forest: SBQueue[F]
  }

  case class HeapEmpty[F: Ordering, A: Ordering]() extends HeapA[F, A]:
    override def headOption: Option[A] = None

    override def forest: SBQueue[F] = SBQueue()

  case class HeapNode[F, A: Ordering](value: A, forest: SBQueue[F]) extends HeapA[F, A]:
    override def headOption: Option[A] = Some(value)

  type Heap[A] = Cofree[HeapA, A]

  extension [A: Ordering](heap: Heap[A]) {
    // pass-through access
    def headOption: Option[A] = heap.coUnit.headOption
    def forest: SBQueue[Heap[A]] = heap.coUnit.forest

    // heap operations
    def insert(a: A): Heap[A] = union(Heap.leaf(a))
    def head: Option[A] = heap.headOption
    def union(that: Heap[A]): Heap[A] = heap.head match {
      case None => that
      case Some(v1) =>
        that.head match {
          case None => heap
          case Some(v2) =>
            if (v1 <= v2) Heap.node(v1, heap.forest.insert(that))
            else Heap.node(v2, that.forest.insert(heap))
        }
    }
    def deleteHead(): Heap[A] = heap.coUnit.forest.head match {
      case None => Heap.empty
      case Some(minHeap) =>
        Heap.node(minHeap.head.get, minHeap.coUnit.forest.union(heap.coUnit.forest.deleteHead()))
    }

    // convenience functionality
    def iterator: Iterator[A] =
      Iterator.unfold(heap)((h: Heap[A]) => h.head.map(v => (v, h.deleteHead())))
  }

  given [A: Ordering]: Ordering[Heap[A]] with {
    def compare(heap1: Heap[A], heap2: Heap[A]) = (heap1.head, heap2.head) match {
      case (None, None) => 0
      case (None, _) => +1
      case (_, None) => -1
      case (Some(a), Some(b)) => summon[Ordering[A]].compare(a, b)
    }
  }

  object Heap {
    def empty[A: Ordering]: Heap[A] = cofree(HeapEmpty())

    def leaf[A: Ordering](a: A): Heap[A] = cofree(HeapNode(a, SBQueue()))

    def node[A: Ordering](a: A, q: SBQueue[Heap[A]]) = cofree(HeapNode(a, q))

    def from[A: Ordering](as: Seq[A]) =
      as.foldLeft(empty: Heap[A])((h, a) => h.insert(a))
  }

}

package preorderheap {
  // Bootstrapped globally rooted skew binomial queues
  // With fast-add fast-traverse collection of head-equivalent values
  // We call them `POHeap` for Pre-Order-Heaps

  import heap.{Heap, SBQueue}

  case class Cofree[F[_, _], A](coUnit: F[Cofree[F, A], A])

  def cofree[F[_, _], A](counit: F[Cofree[F, A], A]) = Cofree(counit)

  case class POHeapNode[F, A](heads: Vector[A], forest: SBQueue[F])

  type PreOrderHeap[A] = Cofree[POHeapNode, A]

  given [A: Ordering]: Ordering[PreOrderHeap[A]] with {
    def compare(heap1: PreOrderHeap[A], heap2: PreOrderHeap[A]) = (heap1.head, heap2.head) match {
      case (None, None) => 0
      case (None, _) => +1
      case (_, None) => -1
      case (Some(a), Some(b)) => summon[Ordering[A]].compare(a, b)
    }
  }

  object PreOrderHeap {
    def empty[A: Ordering]: PreOrderHeap[A] = cofree(POHeapNode(Vector.empty, SBQueue()))
    def apply[A:Ordering](as : A*): PreOrderHeap[A] = from(as)
    def from[A:Ordering](as : Seq[A]): PreOrderHeap[A] =
      as.foldLeft(empty){(h,a) => h.insert(a)}
  }

  extension [A](heap: PreOrderHeap[A])(using ord: Ordering[A]) {
    // pass-through methods
    def forest: SBQueue[PreOrderHeap[A]] = heap.coUnit.forest

    // heap operations
    def head: Option[A] = heap.coUnit.heads.headOption
    def insert(a: A): PreOrderHeap[A] =
      if(heap.heads.isEmpty) PreOrderHeap(a)
      else if(ord.compare(heap.head.get, a) == 0)
        cofree(POHeapNode(heap.heads.appended(a), heap.forest))
      else
        cofree(POHeapNode(heap.heads, heap.forest.insert(PreOrderHeap(a))))
    def union(that: PreOrderHeap[A]): PreOrderHeap[A] =
      if(heap.heads.isEmpty) that
      else if(that.heads.isEmpty) heap
      else ord.compare(heap.head.get, that.head.get) match {
        case 0 =>
          cofree (POHeapNode (heap.heads.appendedAll (that.heads), heap.forest.insert (that.deleteHead () ) ) )
        case cmp if cmp > 0 => // that is new root
          cofree (POHeapNode(that.heads, that.forest.insert(heap)))
        case cmp if cmp < 0 => // heap is new root
          cofree (POHeapNode(heap.heads, heap.forest.insert(that)))
      }

    def deleteHead(): PreOrderHeap[A] = heap.forest.head match {
      case None => PreOrderHeap()
      case Some(minHeap) => {
        val (minHeaps, siblings) : (Vector[SBTree[PreOrderHeap[A]]], Vector[SBTree[PreOrderHeap[A]]]) =
          heap.forest.forest.partition{(sbt) =>
            sbt.headOption.map{(poh) =>
              ord.compare(heap.forest.head,poh.head)}.contains(0)}
        val newHeads : Vector[A] = for
          minHeapTree <- minHeaps
          minHeapHead <- minHeapTree.headOption
          minHeapHeads <- minHeapHead.heads
          headValue <- minHeapHeads
        yield
          headValue
        
          // first, create a seed PreOrderHeap(newHeads, SBTQueue(siblings))
          // for each (tree: SBTree[PreOrderHeap[A]]) in minHeaps
          // 1. take (tree.t: PreOrderHeap[A])
          // 2. add tree.t.forest to the sequence of things to add
          // 3. add tree.children to the sequence of things to add
          // 4. insert each of all these children into the seed heap
          //
          // invariant maintained is that any a:A contained in anything in the forest must be
          // strictly larger in the ordering than any of the elements in the heads.
          //
          // we get access to the head in O(1)
          // access to all heads in O(#heads)
          // insertion in O(1)
          // union in O(1)
          // deleteHead must:
          // - traverse heap.forest to find all trees with the new heads O(log n)
          // - split the new heads into two parts O(log n)
          // - add O(2 log n) things into seed.forest
          // Conclusion O(log n) - concretely O(4 log n) - so we stay in the right complexity class
          // Worst case, it finds O(log n) new head trees, in which case the O(3 log n) steps to 
          // split each new head and add them to the seed.forest is done O(log n) times, for a final
          // worst case complexity for deleteHead of O((log n)^2)


      }
    }

    // pre-order specific operations
    def heads: Seq[A] = heap.coUnit.heads
    def collapseHeads(fun: (Seq[A]) => A): PreOrderHeap[A] = ???

  }
}