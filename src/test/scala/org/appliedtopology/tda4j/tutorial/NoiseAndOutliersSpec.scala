package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Runs the code of `_docs/tutorials/noise-and-outliers.md` and asserts every number the page quotes. */
class NoiseAndOutliersSpec extends Specification:
  sequential

  private case class Result(
    points: Int,
    vrSize: Int,
    dtmSize: Int,
    vrTwo: List[Double],
    dtmTwo: List[Double],
    ringWeight: Double,
    outlierWeight: Double,
    cycles: List[(Int, Int)]
  )

  private def page(): Result =
    val lab = TDAlab(2)
    import lab.{*, given}

    val metricSpace = io.CSV.readEuclideanMetricSpace(
      "_docs/tutorials/data/circle-with-outliers.csv"
    ) // 70 ring points, then 25 outliers
    val engine = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]()

    def twoLongestLoops(stream: streams.LevelwiseSimplexStream[Int, Double]): List[Double] =
      val bars = engine.persistentHomology(stream).diagramAt(Double.PositiveInfinity)
      bars.filter(_._1 == 1).map((_, birth, death) => death - birth).sorted.reverse.take(2)

    val vr = streams.VietorisRips(metricSpace, maxDimension = 1)

    // Which points make up each loop? (How many points in the loop's representative cycle, and how many of them are outliers)
    val loops = engine
      .persistentHomology(vr)
      .diagramWithGeneratorsAt(Double.PositiveInfinity)
      .filter(_._1 == 1)
      .sortBy((_, birth, death, _) => -(death - birth))
      .take(2)
    val cycles = loops.map { (_, _, _, cycle) =>
      val pointsOnCycle = cycle.rawEntries.flatMap((edge, _) => edge.toList).distinct
      (pointsOnCycle.size, pointsOnCycle.count(_ >= 70))
    }
    val dtm = streams.DtmRips.fromNeighbours(metricSpace, k = 8, maxDimension = 1, p = 1.0)

    val weights = streams.DistanceToMeasure(metricSpace, 8, 2.0)
    val (ring, outliers) = weights.splitAt(70)

    Result(
      metricSpace.size,
      vr.iterator.size,
      dtm.iterator.size,
      twoLongestLoops(vr),
      twoLongestLoops(dtm),
      ring.sum / ring.size,
      outliers.sum / outliers.size,
      cycles
    )

  "noise-and-outliers.md" should {
    lazy val r = page()
    "report the data and the sizes" in {
      println(r)
      r.points must beEqualTo(95)
    }
    "have Vietoris-Rips find the loop with a visible runner-up (about 7 to 1)" in
      (r.vrTwo(0) must beCloseTo(0.901, 0.001)).and(r.vrTwo(1) must beCloseTo(0.127, 0.001))
    "have DTM-Rips push the runner-up down to almost nothing (over 100 to 1)" in
      (r.dtmTwo(0) / r.dtmTwo(1) must beGreaterThan(100.0)).and(r.dtmTwo(0) must beCloseTo(0.815, 0.001))
    "show the runner-up loop is made mostly of outliers, the real one of ring points" in {
      r.cycles must beEqualTo(List((66, 2), (8, 5)))
    }
    "give the outliers much larger weights than the ring points" in {
      r.outlierWeight / r.ringWeight must beGreaterThan(3.0)
    }
  }
