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
    "give 61 bars from each of the four engines, and the same bars" in {
      val diagrams = List(page.chunks, page.naive, page.cohomology, page.ripser)
      (diagrams.map(_.size) must beEqualTo(List(61, 61, 61, 61)))
        .and(diagrams.map(page.rounded).distinct.size must beEqualTo(1))
    }
    "collapse 1543 edges to 321 and 24711 simplices to 1547, keeping the diagram" in
      (page.collapsed.stats.edgesBefore must beEqualTo(1543))
        .and(page.collapsed.stats.edgesAfter must beEqualTo(321))
        .and(page.complexSize must beEqualTo(24711))
        .and(page.collapsedSize must beEqualTo(1547))
        .and(page.rounded(page.collapsedDiagram) must beEqualTo(page.rounded(page.chunks)))
  }
