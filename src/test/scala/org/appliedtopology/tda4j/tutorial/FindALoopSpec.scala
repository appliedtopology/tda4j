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
    "describe the data and the stream" in
      (page.metricSpace.size must beEqualTo(60))
        .and(page.stream.iterator.size must beEqualTo(24711))
        .and(page.enclosingRadius must beCloseTo(1.95, 0.005))
    "report 1544 bars, nearly all zero-length, and 58 shown at the default threshold" in
      (page.bars.size must beEqualTo(1544))
        .and(page.zeroLength must beEqualTo(1483))
        .and(page.shown.groupBy(_._1).view.mapValues(_.size).toMap must beEqualTo(Map(0 -> 57, 1 -> 1)))
    "find one loop, far more persistent than any sampling gap" in {
      val (_, birth, death) = page.loop
      (birth must beCloseTo(0.595, 0.001))
        .and(death must beCloseTo(1.707, 0.001))
        .and(page.longestFiniteH0 must beCloseTo(0.484, 0.001))
        .and((death - birth) must beGreaterThan(2 * page.longestFiniteH0))
    }
    "come with a representative cycle of 52 edges" in { page.cycle.rawEntries.size must beEqualTo(52) }
    "keep the loop alive to the horizon when the complex is cut at 1.0" in {
      val (_, birth, death) = page.shortLoop
      (page.shortStream.iterator.size must beEqualTo(3629))
        .and(birth must beCloseTo(0.595, 0.001))
        .and(death.isInfinite must beTrue)
    }
  }
