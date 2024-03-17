package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

class UnionFindSpec extends Specification {
  "UnionFind will find exactly the correct edges" >> {
    val ms: FiniteMetricSpace[Int] = ExplicitMetricSpace(
      Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0))
    )
    val kruskal = Kruskal(ms)
    val mst = kruskal.mstIterator
    val mstExpected: Iterator[(Int,Int)] = Iterator((0,1),(0,2))
    "MST contains expected edges" ==> (mst.toSeq must containAllOf(mstExpected.toSeq))
    "MST does not contain unexpected edges" ==> (mstExpected.toSeq must containAllOf(mst.toSeq))
    val cycles = kruskal.cyclesIterator
    val cyclesExpected: Iterator[(Int,Int)] = Iterator((1,2))
    "Cycle basis contains expected edges" ==> (cycles.toSeq must containAllOf(cyclesExpected.toSeq))
    "Cycle basis does not contain unexpected edges" ==> (cyclesExpected.toSeq must containAllOf(cycles.toSeq))
  }
}
