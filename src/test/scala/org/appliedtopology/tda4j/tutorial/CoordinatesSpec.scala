package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/circular-and-toroidal-coordinates.md` quotes, on the values its "whole script"
  * fence defines (see `build.sbt`).
  */
class CoordinatesSpec extends Specification:
  sequential

  private val page = CircularAndToroidalCoordinatesScript

  "circular-and-toroidal-coordinates.md" should {
    "find one long loop on the circle and a coordinate for every point" in {
      val bars = page.circleBars
      (bars.size must beEqualTo(1))
        .and(bars.head._1 must beCloseTo(0.595, 0.001))
        .and(bars.head._2 must beCloseTo(1.707, 0.001))
        .and(page.circleCoordinate.theta.size must beEqualTo(60))
    }
    "recover the angle to within 0.07 of a turn on average" in
      (page.circleError must beLessThan(0.07)).and(page.circleError must beGreaterThan(0.05))
    "pick the scale 1.225 on the torus" in { page.r must beCloseTo(1.225, 0.001) }
    "find two long-lived loops on the torus, and then a clear drop" in {
      val persistences = page.torusBars.take(4).map((b, d) => d - b)
      (persistences(0) must beGreaterThan(1.0))
        .and(persistences(1) must beGreaterThan(1.0))
        .and(persistences(2) must beLessThan(0.7))
    }
    "give every point both torus coordinates, each following one true angle, much better than chance (0.25)" in
      (page.coordinates.theta.map(_.size).toList must beEqualTo(List(120, 120)))
        .and(page.torusErrors.forall(e => e > 0.1 && e < 0.17) must beTrue)
  }
