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
    "give 61 bars in degrees 0..1 from each of the four engines, and the same bars" in {
      val diagrams = List(page.ripser, page.chunks, page.naive, page.cohomology)
      (diagrams.map(_.size) must beEqualTo(List(61, 61, 61, 61)))
        .and(diagrams.map(page.rounded).distinct.size must beEqualTo(1))
    }
    "add 3 bars in degree 2 by default, from 231961 tetrahedra" in
      (page.diagram.size must beEqualTo(64))
        .and(page.diagram.dim(2).size must beEqualTo(3))
        .and(page.tetrahedra must beEqualTo(231961))
    "collapse 1543 edges to 321 and 24711 simplices to 1547, keeping the diagram" in
      (page.collapsed.stats.edgesBefore must beEqualTo(1543))
        .and(page.collapsed.stats.edgesAfter must beEqualTo(321))
        .and(page.complexSize must beEqualTo(24711))
        .and(page.collapsedSize must beEqualTo(1547))
        .and(page.rounded(page.collapsedDiagram) must beEqualTo(page.rounded(page.diagram)))
  }
