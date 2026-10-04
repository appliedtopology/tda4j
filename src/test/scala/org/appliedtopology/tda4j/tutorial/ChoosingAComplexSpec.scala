package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/choosing-a-complex.md` quotes, on the values its "whole script" fence defines
  * (see `build.sbt`).
  */
class ChoosingAComplexSpec extends Specification:
  sequential

  private val page = ChoosingAComplexScript
  // vr, cech, alpha, sparse, witness
  private val loops = page.loops.map(b => (b.birth, b.death))

  "choosing-a-complex.md" should {
    "report the simplex counts it quotes" in (page.sizes must beEqualTo(List(24711, 36050, 325, 1730, 441)))
    "find exactly one connected component in all five" in
      (page.diagrams.map(_.bettiNumbers(0)) must beEqualTo(List.fill(5)(1)))
    "find the loop in every complex" in (loops.forall((b, d) => d - b > 0.4) must beTrue)
    "give Cech and alpha the same loop, to six digits" in
      (loops(1)._1 must beCloseTo(loops(2)._1, 1e-6)).and(loops(1)._2 must beCloseTo(loops(2)._2, 1e-6))
    "show VR births at about twice the Cech radius, but dying at sqrt(3) against 1" in
      (loops(0)._1 / loops(1)._1 must beCloseTo(2.0, 0.02))
        .and(loops(0)._1 must beCloseTo(0.595, 0.001))
        .and(loops(0)._2 must beCloseTo(1.707, 0.001))
        .and(loops(1)._1 must beCloseTo(0.297, 0.001))
        .and(loops(1)._2 must beCloseTo(0.935, 0.001))
    "quote the loops of the sparse and witness complexes" in
      (loops(3)._1 must beCloseTo(0.708, 0.001))
        .and(loops(3)._2 must beCloseTo(1.789, 0.001))
        .and(loops(4)._1 must beCloseTo(0.278, 0.001))
        .and(loops(4)._2 must beCloseTo(0.742, 0.001))
  }
