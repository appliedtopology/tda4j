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
    "print the diagram: 64 bars, one loop, three small voids" in
      (page.points.length must beEqualTo(60))
        .and(
          page.diagram.toString.linesIterator.toList must beEqualTo(
            List(
              "PersistenceDiagram(64 bars, degrees 0..2)",
              "  H0: 60 bars, longest [0.000, Infinity) [0.000, 0.4836) [0.000, 0.3752) [0.000, 0.2874) [0.000, 0.2747) ...",
              "  H1: 1 bar, longest [0.5945, 1.707)",
              "  H2: 3 bars, longest [1.710, 1.813) [1.750, 1.800) [1.739, 1.761)"
            )
          )
        )
    "find one loop, far more persistent than the biggest sampling gap" in
      (page.loop.birth must beCloseTo(0.595, 0.001))
        .and(page.loop.death must beCloseTo(1.707, 0.001))
        .and(page.longestGap must beCloseTo(0.484, 0.001))
        .and(page.loop.persistence must beCloseTo(1.11, 0.005))
        .and(page.loop.persistence must beGreaterThan(2 * page.longestGap))
    "keep 24 bars longer than 0.1 and 61 significant ones (1% of the enclosing radius 1.95)" in
      (page.longerThanATenth.size must beEqualTo(24))
        .and(page.significant.size must beEqualTo(61))
        .and(EuclideanMetricSpace(page.points).minimumEnclosingRadius must beCloseTo(1.95, 0.005))
    "come with a cycle of 18 edges by default and a cocycle of 121 edges on request, for the same loop" in
      (page.cycle.cells.size must beEqualTo(18))
        .and(page.cocycle.cells.size must beEqualTo(121))
        .and(page.withCocycles.dim(1).longest.get.toTriple must beEqualTo(page.diagram.dim(1).longest.get.toTriple))
    "build 24711 simplices, and keep the loop alive to the horizon when cut at 1.0" in
      (page.complexSize must beEqualTo(24711))
        .and(page.shortLoop.birth must beCloseTo(0.595, 0.001))
        .and(page.shortLoop.death.isInfinite must beTrue)
  }
