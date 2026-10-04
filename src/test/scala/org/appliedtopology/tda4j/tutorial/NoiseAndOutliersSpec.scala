package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/noise-and-outliers.md` quotes, on the values its "whole script" fence defines
  * (see `build.sbt`).
  */
class NoiseAndOutliersSpec extends Specification:
  sequential

  private val page = NoiseAndOutliersScript
  private val (vrTwo, dtmTwo, ringWeight, outlierWeight, cycles) = page.results

  "noise-and-outliers.md" should {
    "report the data" in { page.metricSpace.size must beEqualTo(95) }
    "have Vietoris-Rips find the loop with a visible runner-up (about 7 to 1)" in
      (vrTwo(0) must beCloseTo(0.901, 0.001)).and(vrTwo(1) must beCloseTo(0.127, 0.001))
    "have DTM-Rips push the runner-up down to almost nothing (over 100 to 1)" in
      (dtmTwo(0) / dtmTwo(1) must beGreaterThan(100.0)).and(dtmTwo(0) must beCloseTo(0.815, 0.001))
    "show the runner-up loop is made mostly of outliers, the real one of ring points" in {
      cycles must beEqualTo(List((66, 2), (8, 5)))
    }
    "give the outliers much larger weights than the ring points" in
      (ringWeight must beCloseTo(0.176, 0.001))
        .and(outlierWeight must beCloseTo(0.543, 0.001))
        .and(outlierWeight / ringWeight must beGreaterThan(3.0))
  }
