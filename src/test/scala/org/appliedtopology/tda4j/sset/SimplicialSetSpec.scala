package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import org.appliedtopology.tda4j.sset.RealProjectiveGenerator

import org.specs2.mutable

class SimplicialSetSpec extends mutable.Specification:

  "validate() finds no errors on any hand-built fixture" >> {
    SimplicialSetFixtures.minimalSphere(1).validate() must beEmpty
    SimplicialSetFixtures.minimalSphere(2).validate() must beEmpty
    SimplicialSetFixtures.minimalSphere(3).validate() must beEmpty
    SimplicialSet.realProjectiveSpace(2).validate() must beEmpty
    SimplicialSet.realProjectiveSpace(3).validate() must beEmpty
    SimplicialSet.torus.validate() must beEmpty
  }

  "validate() catches a real off-by-one in hand-supplied face data" >> {
    import RealProjectiveGenerator.*
    val rp3 = SimplicialSet.realProjectiveSpace(3)
    // Swap two of e_3's faces -- a data-entry mistake a real user could make -- and confirm it's caught by
    // the simplicial-identity check, not silently accepted as a different-but-valid simplicial set.
    val brokenFaces: RealProjectiveGenerator => IndexedSeq[SSetElement[RealProjectiveGenerator]] = g =>
      val fs = rp3.faces(g)
      if g == E(3) then IndexedSeq(fs(1), fs(0), fs(2), fs(3)) else fs
    val broken = new FiniteSimplicialSet(rp3.generatorsByDim, brokenFaces)
    broken.validate() must not(beEmpty)
  }

  "validate() catches a structurally malformed degeneracy word" >> {
    import RealProjectiveGenerator.*
    val rp2 = SimplicialSet.realProjectiveSpace(2)
    val brokenFaces: RealProjectiveGenerator => IndexedSeq[SSetElement[RealProjectiveGenerator]] = g =>
      if g == E(2) then IndexedSeq(SSetElement(Nil, E(1)), SSetElement(List(0, 1), E(0)), SSetElement(Nil, E(1)))
      else rp2.faces(g)
    val broken = new FiniteSimplicialSet(rp2.generatorsByDim, brokenFaces)
    broken.validate().exists(_.contains("malformed degeneracy word")) must beTrue
  }
