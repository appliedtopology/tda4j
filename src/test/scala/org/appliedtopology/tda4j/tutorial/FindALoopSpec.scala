package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Runs the code of `_docs/tutorials/find-a-loop.md` and asserts every number the page quotes. */
class FindALoopSpec extends Specification:
  sequential

  private case class Result(
    points: Int,
    enclosingRadius: Double,
    simplices: Int,
    totalBars: Int,
    zeroLength: Int,
    shownByDim: Map[Int, Int],
    loop: (Double, Double),
    longestFiniteH0: Double,
    cycleEdges: Int,
    truncatedSimplices: Int,
    truncatedLoopDeath: Double
  )

  private def page(): Result =
    val lab = TDAlab(2)
    import lab.{*, given}

    val metricSpace = io.CSV.readEuclideanMetricSpace("_docs/tutorials/data/noisy-circle.csv")
    val enclosingRadius = metricSpace.minimumEnclosingRadius

    val stream = streams.VietorisRips(metricSpace, maxDimension = 1)
    val engine = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]()
    val state = engine.persistentHomology(stream)

    val bars = state.diagramAt(Double.PositiveInfinity).filter((dim, _, _) => dim <= 1)
    val zeroLength = bars.count((_, birth, death) => death - birth <= 1e-12)
    val threshold = 0.01 * enclosingRadius
    val shown = bars.filter((_, birth, death) => death.isInfinite || death - birth > threshold)

    val loop = bars.filter(_._1 == 1).maxBy((_, birth, death) => death - birth)
    val longestFiniteH0 = bars.filter(b => b._1 == 0 && b._3.isFinite).map(b => b._3 - b._2).max

    val withCycles = state.diagramWithGeneratorsAt(Double.PositiveInfinity).filter((dim, _, _, _) => dim == 1)
    val (_, _, _, cycle) = withCycles.maxBy((_, birth, death, _) => death - birth)

    val shortStream = streams.VietorisRips(metricSpace, maxDimension = 1, maxFiltrationValue = Some(1.0))
    val shortBars = engine.persistentHomology(shortStream).diagramAt(Double.PositiveInfinity).filter(_._1 == 1)
    val shortLoop = shortBars.maxBy((_, birth, death) => death - birth)

    Result(
      metricSpace.size,
      enclosingRadius,
      stream.iterator.size,
      bars.size,
      zeroLength,
      shown.groupBy(_._1).view.mapValues(_.size).toMap,
      (loop._2, loop._3),
      longestFiniteH0,
      cycle.rawEntries.size,
      shortStream.iterator.size,
      shortLoop._3
    )

  "find-a-loop.md" should {
    lazy val r = page()
    "describe the data and the stream" in {
      println(r)
      (r.points must beEqualTo(60)).and(r.simplices must beEqualTo(24711))
    }
    "report 1544 bars, nearly all zero-length, and 58 shown at the default threshold" in {
      (r.totalBars must beEqualTo(1544))
        .and(r.zeroLength must beEqualTo(1483))
        .and(r.shownByDim must beEqualTo(Map(0 -> 57, 1 -> 1)))
    }
    "find one loop, far more persistent than any sampling gap" in {
      (r.loop._1 must beCloseTo(0.595, 0.001))
        .and(r.loop._2 must beCloseTo(1.707, 0.001))
        .and(r.longestFiniteH0 must beCloseTo(0.484, 0.001))
        .and((r.loop._2 - r.loop._1) must beGreaterThan(2 * r.longestFiniteH0))
    }
    "come with a representative cycle of 52 edges" in { r.cycleEdges must beEqualTo(52) }
    "keep the loop alive to the horizon when the complex is cut at 1.0" in {
      (r.truncatedLoopDeath.isInfinite must beTrue).and(r.truncatedSimplices must beLessThan(r.simplices))
    }
  }
