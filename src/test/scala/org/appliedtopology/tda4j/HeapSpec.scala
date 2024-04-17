package org.appliedtopology.tda4j

import org.specs2.{mutable, ScalaCheck}
import heap.*
import org.scalacheck.Prop.forAll

class HeapSpec extends mutable.Specification with ScalaCheck {
  "Skew Binomial Heap Properties" >> {
    /*
    prop { (lst: List[Int]) =>
      val heap = Heap(lst.toSeq :_*)
      heap.findMin === lst.minOption

      val sortedlst = heap.iterator.toSeq
      sortedlst === lst.sorted
    }

    prop { (lst: List[Int]) =>
      val heap = Heap(lst.toSeq :_*)

      // Heap property: value is greater than every other value?
      // Enough to test greater than every other root
      val check1 : List[Boolean] = heap
        .mapStructure((h) => (for a <- h.findMin yield (
          for v <- h.forest.toVector.flatMap(_.findMin) yield (a <= v))))
        .toList
        .flatten
        .flatten
      check1 must contain(beTrue).forall

      // Skew binomial property
      // first rank is lowest
      val check2 : List[Boolean] = heap
        .mapStructure((h) => h.forest match {
          case EmptyForest() => true
          case NonemptyForest(first, rest) => rest.forall(first.rank <= _.rank)
        }).toList
      check2 must contain(beTrue).forall
      // rest ranks are monotonic
      val check3 : List[Boolean] = heap
        .mapStructure((h) => h.forest match {
          case EmptyForest() => true
          case NonemptyForest(first, rest) => rest.map(_.rank).sorted == rest.map(_.rank)
        }).toList
      check3 must contain(beTrue).forall
      // rest ranks have no repeats
      val check4 : List[Boolean] = heap
        .mapStructure((h) => h.forest match {
          case EmptyForest() => true
          case NonemptyForest(first, rest) => rest.map(_.rank).toSet.toList.sorted == rest.map(_.rank)
        }).toList
      check4 must contain(beTrue).forall
    }

    val hA = Heap(1,2,3,4,5,6)
    val hB = Heap(4,5,6,7,8,9,2)
    hA.meld(hB)


    prop { (lst1: List[Int], lst2: List[Int]) =>
      val heap1 = Heap.from(lst1)
      val heap2 = Heap.from(lst2)

      heap1.meld(heap2).iterator.toSeq === (lst1 ++ lst2).sorted
    }

     */
  }
}
