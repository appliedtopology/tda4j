package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

class UnionFindSpec extends Specification {
  "UnionFind will" >> {
    val ms: FiniteMetricSpace[Int] = ExplicitMetricSpace(
      Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0))
    )
    val mst = Kruskal(ms)
    val kruskalExpected: Iterator[(Int,Int)] = Iterator((0,1),(0,2))
    mst.toSeq must containAllOf(kruskalExpected.toSeq)
    kruskalExpected.toSeq must containAllOf(mst.toSeq)
  }
}
