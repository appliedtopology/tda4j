package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.scalacheck.Gen.listOfN
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}
import org.specs2.{mutable, ScalaCheck}

class UnionFindSpec extends mutable.Specification with ScalaCheck:
  "UnionFind will find exactly the correct edges" >> {
    val ms: FiniteMetricSpace[Int] = ExplicitMetricSpace(
      Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0))
    )
    val kruskal = Kruskal(ms)
    val mst = kruskal.mstIterator
    val mstExpected: Iterator[(Int, Int)] = Iterator((0, 1), (0, 2))
    "MST contains expected edges" ==> (mst.toSeq must containAllOf(
      mstExpected.toSeq
    ))
    "MST does not contain unexpected edges" ==> (mstExpected.toSeq must containAllOf(
      mst.toSeq
    ))
    val cycles = kruskal.cyclesIterator
    val cyclesExpected: Iterator[(Int, Int)] = Iterator((1, 2))
    "Cycle basis contains expected edges" ==> (cycles.toSeq must containAllOf(
      cyclesExpected.toSeq
    ))
    "Cycle basis does not contain unexpected edges" ==> (cyclesExpected.toSeq must containAllOf(
      cycles.toSeq
    ))
  }
  "UnionFind will find the right number of minimum spanning tree edges" >> {
    // matrixGen is defined in VietorisRipsSpec.scala
    "MST size checks" >> forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 25), Gen.chooseNum(25, 250))) {
      (points: Array[Array[Double]]) =>
        val metricSpace = EuclideanMetricSpace(points)
        val kruskal = Kruskal(metricSpace)
        val mst = kruskal.mstIterator.toSeq

        "MST has N-1 edges" ==> (mst.size === math.max(0, metricSpace.size - 1))
    }
  }
  "Kruskal.cycleToChain produces a genuine zero-boundary cycle for every non-tree edge" >> {
    val GF3 = new FiniteField(3)
    import GF3.{Fp, given}
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(4, 10), Gen.chooseNum(8, 20))) {
      (points: Array[Array[Double]]) =>
        val kruskal = Kruskal(EuclideanMetricSpace(points))
        kruskal.cyclesIterator.forall { edge =>
          val chain = kruskal.cycleToChain[Fp](edge)
          Chain.from[Simplex[Int], Fp](chain.boundary).isZero()
        } must beTrue
    }
  }
