package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.SpecThrows.throwWith
import org.specs2.mutable.Specification

/** The facade's `edgeTies` option (the order of equal-length edges, read by the two Ripser engines): every order gives
  * the bars of the unset option, through `engine=ripser`, `engine=parallel-ripser` and an unset engine, representatives
  * that are what they say, and a refusal anywhere the option has no meaning. The orders themselves, their pair counts
  * and their agreement with ripser.cpp are `ParallelEdgeTiesSpec`'s.
  */
class EdgeTiesFacadeSpec extends Specification:
  private val prime = 17
  private val orders =
    Seq("lexicographic", "reverse-lexicographic", "colexicographic", "reverse-colexicographic", "shuffled")

  // A 4x4 grid with a point's neighbours at distance 1, 1.41, 2 ...: a great many equal lengths.
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

  "edgeTies" should {
    "give the bars of the unset option for every order, through engine=ripser" >> {
      orders.foreach(o => bars(compute("engine", "ripser", "edgeTies", o)) must beEqualTo(reference))
      ok
    }
    "give them through engine=parallel-ripser and through an unset engine" >> {
      orders.foreach { o =>
        bars(compute("engine", "parallel-ripser", "threads", "2", "edgeTies", o)) must beEqualTo(reference)
        bars(compute("edgeTies", o)) must beEqualTo(reference)
      }
      ok
    }
    "give cycles that close, and cocycles, for every order" >> {
      orders.foreach { o =>
        edgeCyclesClose(compute("engine", "ripser", "edgeTies", o)) must beTrue
        edgeCyclesClose(compute("engine", "parallel-ripser", "threads", "2", "edgeTies", o)) must beTrue
        val cocycles = compute("engine", "ripser", "edgeTies", o, "representativeType", "cocycles")
        bars(cocycles) must beEqualTo(reference)
        (0 until cocycles.size()).forall(i => cocycles.cycleCoefficients(i).nonEmpty) must beTrue
      }
      ok
    }
    "be read: the representatives follow the order" >> {
      // The option must not be a no-op: on a lattice the orders pick different (valid) cycles.
      val perOrder = orders.map(o => representatives(compute("engine", "ripser", "edgeTies", o)))
      perOrder.distinct.size must beGreaterThan(1)
    }
    "take any case, and the boundary matrix of the complex, not of the ranks" >> {
      val shouted = compute("engine", "ripser", "edgeTies", "Reverse-Colexicographic")
      bars(shouted) must beEqualTo(reference)
      shouted.numCells() === compute("engine", "ripser").numCells()
    }
    "apply together with edgeCollapse and with includeZeroLength" >> {
      bars(compute("engine", "ripser", "edgeCollapse", "true", "edgeTies", "colexicographic")) must
        beEqualTo(reference)
      val withZero = compute("engine", "ripser", "includeZeroLength", "true", "edgeTies", "shuffled")
      bars(withZero).filter { case (_, b, d) => b != d } must beEqualTo(reference)
    }
    "be refused where it has no meaning, naming what to change" >> {
      compute("engine", "cohomology", "edgeTies", "colexicographic") must throwWith[IllegalArgumentException](
        "needs complex=vr and engine=ripser"
      )
      compute("complex", "alpha", "edgeTies", "colexicographic") must throwWith[IllegalArgumentException](
        "needs complex=vr and engine=ripser"
      )
      compute("complex", "cech", "edgeTies", "colexicographic") must throwWith[IllegalArgumentException](
        "needs complex=vr and engine=ripser"
      )
    }
    "refuse an unknown order, listing the ones there are" >> {
      compute("engine", "ripser", "edgeTies", "sideways") must throwWith[IllegalArgumentException](
        "edgeTies.*sideways.*reverse-colexicographic"
      )
    }
  }
