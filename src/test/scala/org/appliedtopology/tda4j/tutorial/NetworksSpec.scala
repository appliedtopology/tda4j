package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/networks-and-relations.md` quotes, on the values its "whole script" fence
  * defines (see `build.sbt`).
  */
class NetworksSpec extends Specification:
  sequential

  private val page = NetworksAndRelationsScript

  "networks-and-relations.md" should {
    "find five merges, one component, one loop born at 2 and killed at 5, on both sides" in {
      val expected = List(
        (0, 1.0, 2.0),
        (0, 1.0, 2.0),
        (0, 1.0, 2.0),
        (0, 1.0, 2.0),
        (0, 1.0, 2.0),
        (0, 1.0, Double.PositiveInfinity),
        (1, 2.0, 5.0)
      )
      (page.people.triples.sorted must beEqualTo(expected))
        .and(page.clubs.triples.sorted must beEqualTo(expected))
        .and(page.agree must beTrue)
    }
  }
