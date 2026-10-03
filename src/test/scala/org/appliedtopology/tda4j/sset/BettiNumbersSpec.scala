package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import org.specs2.mutable.Specification

class BettiNumbersSpec extends Specification:
  "BettiNumbers" should {
    "give (1,2,1) for the torus over every field, and tell the Klein bottle apart by characteristic" in
      (BettiNumbers(SimplicialSet.torus, 2) must beEqualTo(Vector(1, 2, 1)))
        .and(BettiNumbers(SimplicialSet.torus, 3) must beEqualTo(Vector(1, 2, 1)))
        .and(BettiNumbers(SimplicialSet.kleinBottle, 2) must beEqualTo(Vector(1, 2, 1)))
        .and(BettiNumbers(SimplicialSet.kleinBottle, 3) must beEqualTo(Vector(1, 1, 0)))
    "be empty for the empty set and (1) for a point" in
      (BettiNumbers(SimplicialSet.empty, 2) must beEmpty)
        .and(BettiNumbers(SimplicialSet.point, 2) must beEqualTo(Vector(1)))
    "reach infinite simplicial sets through a skeleton: B(Z/2) has one class in every degree over F_2, none above 0 over F_3" in {
      val bz2 = ClassifyingSpace.nerve(FiniteGroup.cyclic(2))
      (BettiNumbers(bz2, 4, 2) must beEqualTo(Vector(1, 1, 1, 1, 1)))
        .and(BettiNumbers(bz2, 4, 3) must beEqualTo(Vector(1, 0, 0, 0, 0)))
    }
    "reject a non-prime characteristic and a negative degree" in
      (BettiNumbers(SimplicialSet.point, 4) must throwAn[IllegalArgumentException])
        .and(BettiNumbers(ClassifyingSpace.nerve(FiniteGroup.cyclic(2)), -1, 2) must throwAn[IllegalArgumentException])
  }
