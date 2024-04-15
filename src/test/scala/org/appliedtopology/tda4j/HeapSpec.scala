package org.appliedtopology.tda4j

import org.specs2.{mutable, ScalaCheck}
import heap.*
import org.scalacheck.Prop.forAll

class HeapSpec extends mutable.Specification with ScalaCheck {
  "Skew Binomial Heap Properties" >> {
    val heaps = RecursiveHeap[Int]()

    prop { (lst: List[Int]) =>
      val heap = heaps.from(lst)
      heaps.findMin(heap) === lst.minOption
    }

    val h1 = heaps.from(Seq(1,2,3))
    val h2 = heaps.deleteMin(h1)
    val h3 = heaps.deleteMin(h2)
    val h4 = heaps.deleteMin(h3)

    heaps.findMin(heaps.from(Seq(1,2,3))) === Some(1)
    heaps.findMin(
      heaps.deleteMin(
        heaps.from(Seq(1,2,3))
      )
    ) === Some(2)

    heaps.findMin(
      heaps.deleteMin(
        heaps.deleteMin(
          heaps.from(Seq(1, 2, 3))
        )
      )
    ) === Some(3)

    heaps.findMin(
      heaps.deleteMin(
        heaps.deleteMin(
          heaps.deleteMin(
            heaps.from(Seq(1, 2, 3))
          )
        )
      )
    ) === None


    prop { (lst: List[Int]) =>
      val boh = BOHeap.from(lst)
      if (lst.size == 0) boh.root must beNone
      else {
        "checking type" ==>
          (boh.root must beSome[Node[Int]])
        "checking that non-first ranks are wellformed" ==> (
          if (boh.root.get.forest.size > 1)
            boh.root.get.forest.tail
              .map(_.rank)
              .groupBy(identity)
              .map((k, v) => v.size) must contain(beOneOf(0, 1)).foreach
        )
        "checking that all ranks are wellformed" ==> (
          boh.root.get.forest
            .map(_.rank)
            .groupBy(identity)
            .map((k, v) => v.size) must contain(beOneOf(0, 1, 2)).foreach
        )

        def testnode(n: Node[Int]) =
          n.forest.flatMap(_.toList()).forall(j => n.elem <= j)
        def testtree(n: Node[Int]): Boolean =
          testnode(n) & n.forest.forall(testtree)

        "checking the heap property" ==>
          (testtree(boh.root.get) must beTrue)

        "checking that minimum is returned" ==>
          (boh.findMin must beSome(lst.min))

        val boh2 = boh.deleteMin()
        if (lst.size == 1) boh2.root must beNone
        else {
          boh.root must beSome[Node[Int]]
          if (boh.root.get.forest.size > 1)
            boh.root.get.forest.tail
              .map(_.rank)
              .groupBy(identity)
              .map((k, v) => v.size) must contain(beOneOf(0, 1)).foreach
          boh.root.get.forest
            .map(_.rank)
            .groupBy(identity)
            .map((k, v) => v.size) must contain(beOneOf(0, 1, 2)).foreach
        }
      }
    }

    prop { (lst: List[Int]) =>
      val boh = BOHeap.from(lst)
      val sortedlst = boh.dequeueAll()
      sortedlst must beSorted
      sortedlst must containTheSameElementsAs(lst)
    }
  }
}
