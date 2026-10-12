package org.appliedtopology.tda4j
package tutorial

import org.appliedtopology.tda4j.sset.*
import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/telling-spaces-apart.md` quotes, on the values its "whole script" fence
  * defines (see `build.sbt`).
  */
class TellingSpacesApartSpec extends Specification:
  sequential

  private val page = TellingSpacesApartScript

  "telling-spaces-apart.md" should {
    "report the Betti numbers it quotes" in
      (page.betti(page.torus) must beEqualTo((Vector(1, 2, 1), Vector(1, 2, 1))))
        .and(page.betti(page.notATorus) must beEqualTo((Vector(1, 2, 1), Vector(1, 2, 1))))
        .and(page.betti(page.projectivePlane) must beEqualTo((Vector(1, 1, 1), Vector(1, 0, 0))))
        .and(page.betti(page.kleinBottle) must beEqualTo((Vector(1, 2, 1), Vector(1, 1, 0))))
        .and(page.betti(page.cp2) must beEqualTo((Vector(1, 0, 1, 0, 1), Vector(1, 0, 1, 0, 1))))
        .and(page.betti(page.s2s4) must beEqualTo(page.betti(page.cp2)))
    "separate the torus from the wedge by cup products, not Betti numbers" in
      (page.basis(page.torus, 1).size must beEqualTo(2))
        .and(page.basis(page.notATorus, 1).size must beEqualTo(2))
        .and(page.someProductOfOneClassesIsNonzero(page.torus) must beTrue)
        .and(page.someProductOfOneClassesIsNonzero(page.notATorus) must beFalse)
    "separate CP^2 from S^2 v S^4 by Steenrod's Sq^2, and see Sq^1 nonzero on the projective plane" in
      (page.squareOfTheTwoClassIsSq2(page.cp2) must beTrue)
        .and(page.squareOfTheTwoClassIsSq2(page.s2s4) must beFalse)
        .and(page.sq1IsNonzero must beTrue)
    "build CP^2's cohomology ring from the Hopf map" in { page.hopfSquareIsNonzero must beTrue }
  }
