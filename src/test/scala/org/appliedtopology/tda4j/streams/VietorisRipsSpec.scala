package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}
import SimplexIndexing.binomial

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.{mutable as s2mutable, ScalaCheck, Specification}
import org.specs2.specification.core.Fragment
import org.specs2.execute.Result
import org.specs2.specification.AllExpectations

import scala.collection.immutable.{Seq, Set}
import scala.collection.mutable
import scala.math.{cos, sin}
import scala.reflect.ClassTag

def matrixGen[T: ClassTag](g: Gen[T], dimension: Gen[Int], size: Gen[Int]): Gen[Array[Array[T]]] =
  for
    dim <- dimension
    sz <- size
    values <- Gen.listOfN(dim * sz, g)
  yield values.toArray.grouped(dim).toArray

class VietorisRipsSpec extends s2mutable.Specification with ScalaCheck with AllExpectations:
  "This is a specification of the Vietoris-Rips simplex stream implementation\n\n".txt

  val N = 50
  val maxF = 0.75
  val maxD = 3

  given Ordering[Int] = Ordering.Int

  "Vietoris Rips streams should" >> {
    "have sorted layers" >> forAll(matrixGen(Gen.double, Gen.chooseNum(5, 10), Gen.chooseNum(15, 50))) { pts =>
      val metricSpace = EuclideanMetricSpace(pts)
      val vrstream = RecursiveStackVietorisRipsSimplexStream(metricSpace)
      println(s"${pts.size} x ${pts(0).size}")
      var spxseq = vrstream.iterateDimension(0).toSeq
      spxseq.size === binomial(pts.length, 1)
      spxseq.map(vrstream.filtrationValue) must beSorted
      spxseq = vrstream.iterateDimension(1).toSeq
      spxseq.size === binomial(pts.length, 2)
      spxseq.map(vrstream.filtrationValue) must beSorted
      spxseq = vrstream.iterateDimension(2).toSeq
      spxseq.size === binomial(pts.length, 3)
      spxseq.map(vrstream.filtrationValue) must beSorted
    }
  }

  // Regression test for a confirmed bug (found by EngineComparisonBenchmarkSpec, full writeup in CLAUDE.md's
  // "Cross-engine benchmark, and a bug it found on first run" section): filtrationOrdering used to be plain
  // ascending here instead of reversed, which crashed SimplicialHomologyContext ("Naive" engine) at maxDim >= 2
  // with `IllegalStateException: reduction pivot ... was not a recorded open class`, while leaving
  // PersistenceInChunksContext ("Chunks") unaffected. Pins both halves: no exception, AND agreement between the
  // two engines -- the actual property that was broken, not just "doesn't crash".
  "RecursiveStackVietorisRipsSimplexStream's Naive-engine barcode agrees with Chunks at maxDim >= 2" >> {
    given Double is Field = Field.DoubleApproximated(1e-9)
    val maxDim = 2

    def bounded(stream: StratifiedSimplexStream[Int, Double]): StratifiedCellStream[Simplex[Int], Double] =
      val cells =
        (0 to maxDim).iterator.flatMap(d => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty)).toVector
      val byDim = cells.groupBy(_.dim)
      new StratifiedCellStream[Simplex[Int], Double]:
        def filtrationValue = stream.filtrationValue
        def filtrationOrdering = stream.filtrationOrdering
        val smallest = stream.smallest
        val largest = stream.largest
        def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
          case d if byDim.contains(d) => byDim(d).iterator
        }

    forAll(matrixGen(Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { pts =>
      val metricSpace = EuclideanMetricSpace(pts)
      val naive =
        SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(bounded(RecursiveStackVietorisRipsSimplexStream(metricSpace)))
          .diagramAt(Double.PositiveInfinity)
      val chunks =
        PersistenceInChunksContext[Int, Double](maxDim)
          .persistentHomology(bounded(RecursiveStackVietorisRipsSimplexStream(metricSpace)))
          .diagramAt(Double.PositiveInfinity)
      naive must containTheSameElementsAs(chunks)
    }
  }
