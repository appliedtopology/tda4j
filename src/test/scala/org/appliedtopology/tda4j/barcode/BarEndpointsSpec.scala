package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

class BarEndpointsSpec extends Specification:
  "the numeric accessors on a bar over Double" should {
    "read birth, death, persistence and the triple of a finite bar" in {
      val bar = PersistenceBar[Double](1, 0.5, 2.0)
      (bar.birth must beEqualTo(0.5))
        .and(bar.death must beEqualTo(2.0))
        .and(bar.persistence must beEqualTo(1.5))
        .and(bar.toTriple must beEqualTo((1, 0.5, 2.0)))
    }
    "give an essential bar an infinite death and persistence" in {
      val bar = PersistenceBar[Double](0, 0.0)
      (bar.death.isPosInfinity must beTrue)
        .and(bar.persistence.isPosInfinity must beTrue)
        .and(bar.toTriple must beEqualTo((0, 0.0, Double.PositiveInfinity)))
    }
  }
