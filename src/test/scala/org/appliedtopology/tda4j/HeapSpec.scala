package org.appliedtopology.tda4j

import org.specs2.{mutable, ScalaCheck}
import heap.*
import org.scalacheck.Prop.forAll

class HeapSpec extends mutable.Specification with ScalaCheck {
  "Brodal-Okasaki Heap Properties" >> {
    "Skew Binomial Queues should" >> {
      prop { (lst: List[Int]) =>
        val sbq = SBQueue.from(lst)
        if(lst.nonEmpty) {
          "have no repeated ranks after the first entry" ==>
            (sbq.forest.tail.map(_.rank).toSet must containTheSameElementsAs(sbq.forest.tail.map(_.rank)))
          "be non-decreasing" ==>
            (sbq.forest.map(_.rank).zip(sbq.forest.map(_.rank).drop(1)).map(math.Ordering.Int.compare) must contain(be_<=(0)).forall)
        }
      }
    }

    "Bootstrapped heaps should" >> {
      prop { (lst: List[Int]) =>
        val heap = Heap.from(lst)
        "return the minimum" ==> (heap.head === lst.minOption)
        "traverse in sorted order" ==> (heap.iterator.toSeq must beSorted)
        "return all the elements" ==> (heap.iterator.toSeq === lst.sorted)
      }
    }
  }
}
