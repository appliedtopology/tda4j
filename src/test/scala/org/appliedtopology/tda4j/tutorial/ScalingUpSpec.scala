package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/scaling-up.md` quotes, on the values its "whole script" fence defines (see
  * `build.sbt`).
  */
class ScalingUpSpec extends Specification:
  sequential

  private val page = ScalingUpScript

  "scaling-up.md" should {
    "run four engines that return different amounts of raw output" in {
      List(page.naive, page.chunks, page.cohomology, page.ripser).map(_.size) must beEqualTo(
        List(23168, 1544, 23168, 1544)
      )
    }
    "agree exactly on the answer: 61 bars" in {
      val reference = page.answer(page.naive)
      (reference.size must beEqualTo(61))
        .and(page.answer(page.chunks) must beEqualTo(reference))
        .and(page.answer(page.cohomology) must beEqualTo(reference))
        .and(page.answer(page.ripser) must beEqualTo(reference))
    }
    "collapse 1543 edges to 321 and 24711 simplices to 1547, keeping the barcode" in
      (page.collapsed.stats.edgesBefore must beEqualTo(1543))
        .and(page.collapsed.stats.edgesAfter must beEqualTo(321))
        .and(page.stream.iterator.size must beEqualTo(24711))
        .and(page.collapsedStream.iterator.size must beEqualTo(1547))
        .and(page.answer(page.collapsedBars) must beEqualTo(page.answer(page.naive)))
  }
