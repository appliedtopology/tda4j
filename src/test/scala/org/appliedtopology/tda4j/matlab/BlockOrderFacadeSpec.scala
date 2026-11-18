package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.SpecThrows.throwWith
import org.specs2.mutable.Specification

/** The facade's `blockOrder` option (which of two simplices under one edge is older, read by the block-parallel Ripser
  * engine): both values give the bars of the unset option with every edge order, on `engine=parallel-ripser` and on an
  * unset engine (which `smaller-index-first` sends to the block-parallel engine on any number of threads),
  * representatives that are what they say, and a refusal anywhere the option has no meaning. The orders themselves,
  * their pair counts and their agreement with ripser-forest are `ParallelBlockOrderSpec`'s.
  */
class BlockOrderFacadeSpec extends Specification:
  private val prime = 17
  private val blockOrders = Seq("larger-index-first", "smaller-index-first")
  private val edgeOrders = Seq("lexicographic", "colexicographic", "reverse-colexicographic", "shuffled")

  // A 4x4 grid: a great many equal lengths.
  private val grid: Array[Array[Double]] =
    Array.tabulate(16)(i => Array((i % 4).toDouble, (i / 4).toDouble))

  private def compute(extra: String*): PersistenceResult =
    TDA4j.computeFromPoints(grid, Array("minPersistence", "0", "maxDimension", "2") ++ extra)

  private def bars(r: PersistenceResult): List[(Int, Double, Double)] =
    r.toArray().toList.map(row => (row(0).toInt, row(1), row(2))).sorted

  private def representatives(r: PersistenceResult) =
    (0 until r.size()).toList.map(i => (r.cycleVertices(i).map(_.toList).toList, r.cycleCoefficients(i).toList))

  // The boundary of a cycle of edges, mod the prime: zero at every vertex.
  private def edgeCyclesClose(r: PersistenceResult): Boolean =
    (0 until r.size()).filter(r.dimension(_) == 1).forall { i =>
      val net = scala.collection.mutable.Map.empty[Int, Long].withDefaultValue(0L)
      for (edge, c) <- r.cycleVertices(i).zip(r.cycleCoefficients(i)) do
        net(edge(0)) -= c.round
        net(edge(1)) += c.round
      net.values.forall(v => ((v % prime) + prime) % prime == 0)
    }

  private val reference = bars(compute("engine", "ripser"))

  "blockOrder" should {
    "give the bars of the unset option in both orders, with every edge order, on the block-parallel engine" >> {
      for b <- blockOrders; e <- edgeOrders do
        bars(compute("engine", "parallel-ripser", "threads", "2", "edgeTies", e, "blockOrder", b)) must
          beEqualTo(reference)
      ok
    }
    "send smaller-index-first to the block-parallel engine when the engine is unset, on one thread too" >> {
      for e <- edgeOrders do
        bars(compute("threads", "1", "edgeTies", e, "blockOrder", "smaller-index-first")) must beEqualTo(reference)
      // The same cycles as the block-parallel engine's: the unset engine took it.
      val unset = compute("threads", "1", "blockOrder", "smaller-index-first")
      val named = compute("engine", "parallel-ripser", "threads", "1", "blockOrder", "smaller-index-first")
      representatives(unset) must beEqualTo(representatives(named))
    }
    "give cycles that close, and cocycles, in both orders" >> {
      for b <- blockOrders do
        edgeCyclesClose(
          compute("engine", "parallel-ripser", "edgeTies", "colexicographic", "blockOrder", b)
        ) must beTrue
        val cocycles =
          compute("engine", "parallel-ripser", "blockOrder", b, "representativeType", "cocycles")
        bars(cocycles) must beEqualTo(reference)
        (0 until cocycles.size()).forall(i => cocycles.cycleCoefficients(i).nonEmpty) must beTrue
      ok
    }
    "be read: the representatives follow the order" >> {
      val perOrder = blockOrders.map { b =>
        representatives(compute("engine", "parallel-ripser", "edgeTies", "colexicographic", "blockOrder", b))
      }
      perOrder.distinct.size must beEqualTo(2)
    }
    "take larger-index-first, the default, on engine=ripser too, and any case" >> {
      bars(compute("engine", "ripser", "blockOrder", "larger-index-first")) must beEqualTo(reference)
      bars(compute("engine", "parallel-ripser", "blockOrder", "Smaller-Index-First")) must beEqualTo(reference)
    }
    "be refused where it has no meaning, naming what to change" >> {
      compute("engine", "ripser", "blockOrder", "smaller-index-first") must throwWith[IllegalArgumentException](
        "engine=parallel-ripser"
      )
      compute("engine", "cohomology", "blockOrder", "smaller-index-first") must throwWith[IllegalArgumentException](
        "needs complex=vr and engine=parallel-ripser"
      )
      compute("complex", "alpha", "blockOrder", "smaller-index-first") must throwWith[IllegalArgumentException](
        "needs complex=vr and engine=parallel-ripser"
      )
      compute("includeZeroLength", "true", "blockOrder", "smaller-index-first") must
        throwWith[IllegalArgumentException]("includeZeroLength=false")
    }
    "refuse an unknown order, listing the ones there are" >> {
      compute("engine", "parallel-ripser", "blockOrder", "sideways") must throwWith[IllegalArgumentException](
        "blockOrder.*sideways.*smaller-index-first"
      )
    }
  }
