package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/comparing-barcodes.md` quotes, on the values its "whole script" fence defines
  * (see `build.sbt`).
  */
class ComparingBarcodesSpec extends Specification:
  sequential

  private val page = ComparingBarcodesScript

  private def pairwise(measure: (String, String) => Double) =
    Map("A-B" -> measure("A", "B"), "A-8" -> measure("A", "8"), "B-8" -> measure("B", "8"))

  "comparing-barcodes.md" should {
    "count the loops: one in each circle, two in the figure eight" in
      (page.circleA.size must beEqualTo(1))
        .and(page.circleB.size must beEqualTo(1))
        .and(page.eight.size must beEqualTo(2))
    "put the two circles close together and the figure eight far from both, in every measure" in {
      val landscape = pairwise((a, b) => page.l2(page.landscapes(a), page.landscapes(b)))
      val image = pairwise((a, b) => page.l2(page.images(a), page.images(b)))
      List(page.bottleneck, page.wasserstein, landscape, image).forall(m =>
        m("A-B") < m("A-8") && m("A-B") < m("B-8")
      ) must beTrue
    }
    "quote the distances" in
      (page.bottleneck("A-B") must beCloseTo(0.040, 0.001))
        .and(page.bottleneck("A-8") must beCloseTo(0.556, 0.001))
        .and(page.bottleneck("B-8") must beCloseTo(0.518, 0.001))
        .and(page.wasserstein("A-8") must beCloseTo(0.864, 0.001))
        .and(page.bottleneck("A-8") / page.bottleneck("A-B") must beGreaterThan(10.0))
        .and(page.landscapeDistance must beCloseTo(2.43, 0.01))
        .and(page.imageDistance must beCloseTo(0.163, 0.001))
  }
