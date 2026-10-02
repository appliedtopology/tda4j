package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/choosing-a-complex.md` quotes, on the values its "whole script" fence defines
  * (see `build.sbt`).
  */
class ChoosingAComplexSpec extends Specification:
  sequential

  private val r = ChoosingAComplexScript.results

  "choosing-a-complex.md" should {
    "report the simplex counts it quotes" in {
      r.view.mapValues(_._1).toMap must beEqualTo(
        Map("vietoris-rips" -> 24711, "cech" -> 36050, "alpha" -> 325, "sparse-rips" -> 1730, "witness" -> 441)
      )
    }
    "find exactly one connected component in all five" in { r.values.map(_._3).toList must beEqualTo(List.fill(5)(1)) }
    "find the loop in every complex" in {
      r.values.forall((_, loop, _) => loop._2 - loop._1 > 0.4) must beTrue
    }
    "give Cech and alpha the same loop, to six digits" in
      (r("cech")._2._1 must beCloseTo(r("alpha")._2._1, 1e-6))
        .and(r("cech")._2._2 must beCloseTo(r("alpha")._2._2, 1e-6))
    "show VR births at about twice the Cech radius, but dying at sqrt(3) against 1" in
      (r("vietoris-rips")._2._1 / r("cech")._2._1 must beCloseTo(2.0, 0.02))
        .and(r("vietoris-rips")._2._2 must beCloseTo(1.707, 0.001))
        .and(r("cech")._2._2 must beCloseTo(0.935, 0.001))
    "quote the loops of the sparse and witness complexes" in
      (r("sparse-rips")._2._1 must beCloseTo(0.708, 0.001))
        .and(r("sparse-rips")._2._2 must beCloseTo(1.789, 0.001))
        .and(r("witness")._2._1 must beCloseTo(0.278, 0.001))
        .and(r("witness")._2._2 must beCloseTo(0.742, 0.001))
  }
