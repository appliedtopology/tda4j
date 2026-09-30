package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.SimplicialSetFixtures.ProjectiveGenerator

import org.specs2.mutable

class SimplicialSetSpec extends mutable.Specification:

  "validate() finds no errors on any hand-built fixture" >> {
    SimplicialSetFixtures.minimalSphere(1).validate() must beEmpty
    SimplicialSetFixtures.minimalSphere(2).validate() must beEmpty
    SimplicialSetFixtures.minimalSphere(3).validate() must beEmpty
    SimplicialSetFixtures.realProjectiveSpace(2).validate() must beEmpty
    SimplicialSetFixtures.realProjectiveSpace(3).validate() must beEmpty
    SimplicialSetFixtures.torus.validate() must beEmpty
  }

  "validate() catches a real off-by-one in hand-supplied face data" >> {
    import ProjectiveGenerator.*
    val rp3 = SimplicialSetFixtures.realProjectiveSpace(3)
    // Swap two of e_3's faces -- a data-entry mistake a real user could make -- and confirm it's caught by
    // the simplicial-identity check, not silently accepted as a different-but-valid simplicial set.
    val brokenFaces: ProjectiveGenerator => IndexedSeq[SSetElement[ProjectiveGenerator]] = g =>
      val fs = rp3.faces(g)
      if g == E(3) then IndexedSeq(fs(1), fs(0), fs(2), fs(3)) else fs
    val broken = new FiniteSimplicialSet(rp3.generatorsByDim, brokenFaces)
    broken.validate() must not(beEmpty)
  }

  "validate() catches a structurally malformed degeneracy word" >> {
    import ProjectiveGenerator.*
    val rp2 = SimplicialSetFixtures.realProjectiveSpace(2)
    val brokenFaces: ProjectiveGenerator => IndexedSeq[SSetElement[ProjectiveGenerator]] = g =>
      if g == E(2) then IndexedSeq(SSetElement(Nil, E(1)), SSetElement(List(0, 1), E(0)), SSetElement(Nil, E(1)))
      else rp2.faces(g)
    val broken = new FiniteSimplicialSet(rp2.generatorsByDim, brokenFaces)
    broken.validate().exists(_.contains("malformed degeneracy word")) must beTrue
  }
