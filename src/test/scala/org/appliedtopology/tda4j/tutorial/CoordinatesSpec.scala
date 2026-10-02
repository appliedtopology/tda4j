package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Runs the code of `_docs/tutorials/circular-and-toroidal-coordinates.md` and asserts every number the page quotes. */
class CoordinatesSpec extends Specification:
  sequential

  private case class Result(
    circleBars: List[(Double, Double)],
    circleError: Double,
    circleCovered: Int,
    torusBars: List[(Double, Double)],
    torusErrors: List[Double],
    torusCovered: List[Int]
  )

  private def page(): Result =
    val lab = TDAlab(2)
    import lab.{*, given}

    // How far, on average (as a fraction of a full turn), is `theta` from `truth`, allowing the best rotation and reflection?
    def circularDistance(a: Double, b: Double): Double =
      val d = math.abs(a - b) % 1.0
      math.min(d, 1.0 - d)

    def alignmentError(theta: Map[Int, Double], truth: Int => Double): Double =
      (for
        sign <- Seq(1.0, -1.0)
        offset <- (0 until 200).map(_ / 200.0)
      yield theta
        .map((i, t) => circularDistance(((sign * t + offset) % 1.0 + 1.0) % 1.0, truth(i)))
        .sum / theta.size).min

    def turn(y: Double, x: Double): Double = (math.atan2(y, x) / (2 * math.Pi) + 1.0) % 1.0

    // ---- a circle
    val circlePoints = io.CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
    val circle = streams.EuclideanMetricSpace(circlePoints)
    val circleBars = homology.CircularCoordinates.h1Bars(circle) // (birth, death), longest-lived first
    val (birth, death) = circleBars.head
    val circleCoordinate = homology.CircularCoordinates.compute(circle, r = (birth + death) / 2, cocycleIndex = 0)
    val circleError = alignmentError(circleCoordinate.theta, i => turn(circlePoints(i)(1), circlePoints(i)(0)))

    // ---- a torus
    val torusPoints = io.CSV.readPointCloud("_docs/tutorials/data/flat-torus.csv")
    val torus = streams.EuclideanMetricSpace(torusPoints)
    val torusBars = homology.CircularCoordinates.h1Bars(torus, maxFiltrationValue = Some(1.8))
    val r =
      (torusBars.take(2).map(_._1).max + torusBars.take(2).map(_._2).min) / 2 // a scale where both classes are alive
    val coordinates =
      homology.CircularCoordinates.computeToroidal(torus, r, cocycleIndices = Seq(0, 1), maxFiltrationValue = Some(1.8))
    val torusErrors = coordinates.theta.toList.map { theta =>
      val first = alignmentError(theta, i => turn(torusPoints(i)(1), torusPoints(i)(0)))
      val second = alignmentError(theta, i => turn(torusPoints(i)(3), torusPoints(i)(2)))
      math.min(first, second)
    }

    Result(
      circleBars.take(3).toList,
      circleError,
      circleCoordinate.theta.size,
      torusBars.take(4).toList,
      torusErrors,
      coordinates.theta.map(_.size).toList
    )

  "circular-and-toroidal-coordinates.md" should {
    lazy val r = page()
    "find one long loop on the circle and a coordinate for every point" in {
      println(r)
      (r.circleBars.head._1 must beCloseTo(0.595, 0.001))
        .and(r.circleBars.head._2 must beCloseTo(1.707, 0.001))
        .and(r.circleBars(1)._1 must beCloseTo(r.circleBars(1)._2, 1e-9)) // the runner-up has zero persistence
        .and(r.circleCovered must beEqualTo(60))
    }
    "recover the angle to within 0.07 of a turn on average" in
      (r.circleError must beLessThan(0.07)).and(r.circleError must beGreaterThan(0.05))
    "find two long-lived loops on the torus, and then a clear drop" in {
      val persistences = r.torusBars.map((b, d) => d - b)
      (persistences(0) must beGreaterThan(1.0))
        .and(persistences(1) must beGreaterThan(1.0))
        .and(persistences(2) must beLessThan(0.7))
    }
    "give every point both torus coordinates, each following one true angle, much better than chance (0.25)" in
      (r.torusCovered must beEqualTo(List(120, 120))).and(r.torusErrors.forall(e => e > 0.1 && e < 0.17) must beTrue)
  }
