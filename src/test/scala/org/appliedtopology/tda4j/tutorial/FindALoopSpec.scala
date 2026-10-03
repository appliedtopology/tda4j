package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/find-a-loop.md` quotes, on the values its "whole script" fence defines (see
  * `build.sbt`).
  */
class FindALoopSpec extends Specification:
  sequential

  private val page = FindALoopScript

  "find-a-loop.md" should {
    "print the diagram: 61 bars, one loop" in
      (page.points.length must beEqualTo(60))
        .and(
          page.diagram.toString.linesIterator.toList must beEqualTo(
            List(
              "PersistenceDiagram(61 bars, degrees 0..1)",
              "  H0: 60 bars, longest [0.000, Infinity) [0.000, 0.4836) [0.000, 0.3752) [0.000, 0.2874) [0.000, 0.2747) ...",
              "  H1: 1 bar, longest [0.5945, 1.707)"
            )
          )
        )
    "find one loop, far more persistent than the biggest sampling gap" in
      (page.loop.birth must beCloseTo(0.595, 0.001))
        .and(page.loop.death must beCloseTo(1.707, 0.001))
        .and(page.longestGap must beCloseTo(0.484, 0.001))
        .and(page.loop.persistence must beCloseTo(1.11, 0.005))
        .and(page.loop.persistence must beGreaterThan(2 * page.longestGap))
    "keep 23 bars longer than 0.1 and 58 significant ones (1% of the enclosing radius 1.95)" in
      (page.longerThanATenth.size must beEqualTo(23))
        .and(page.significant.size must beEqualTo(58))
        .and(EuclideanMetricSpace(page.points).minimumEnclosingRadius must beCloseTo(1.95, 0.005))
    "come with a representative cycle of 52 edges" in (page.cycle.cells.size must beEqualTo(52))
    "build 24711 simplices, and keep the loop alive to the horizon when cut at 1.0" in
      (page.complexSize must beEqualTo(24711))
        .and(page.shortLoop.birth must beCloseTo(0.595, 0.001))
        .and(page.shortLoop.death.isInfinite must beTrue)
  }
