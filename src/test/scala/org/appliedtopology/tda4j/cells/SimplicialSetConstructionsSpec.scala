package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

import org.specs2.mutable

/** Structural tests for `product`/`coproduct` (`SimplicialSetConstructions.scala`) -- the word-based
  * non-degeneracy shortcut checked against its definition, the hand-derived generator counts for
  * `minimalSphere(1) x minimalSphere(1)`, and `validate()` on the constructed simplicial sets. Homology
  * cross-validation (Betti numbers against independently-known answers) lives in
  * `homology/SimplicialSetHomologySpec.scala`, alongside the rest of this feature's homology tests.
  */
class SimplicialSetConstructionsSpec extends mutable.Specification:

  "isNonDegeneratePair's word-intersection shortcut agrees with the definitional sOp(j, dOp(j, elt)) == elt check" >> {
    def isDegenerateAtByDefinition[G](sset: FiniteSimplicialSet[G], elt: SSetElement[G], j: Int): Boolean =
      sset.sOp(j, sset.dOp(j, elt)) == elt

    def checkAllElements[G](sset: FiniteSimplicialSet[G], upToDim: Int): Boolean =
      (0 to upToDim).forall { n =>
        elementsAtDim(sset, n).forall { elt =>
          (0 until n).forall(j => elt.word.contains(j) == isDegenerateAtByDefinition(sset, elt, j))
        }
      }

    checkAllElements(SimplicialSetFixtures.minimalSphere(1), 4) must beTrue
    checkAllElements(SimplicialSetFixtures.minimalSphere(2), 4) must beTrue
    checkAllElements(SimplicialSetFixtures.realProjectiveSpace(3), 5) must beTrue
    checkAllElements(SimplicialSetFixtures.torus, 4) must beTrue
  }

  "product(minimalSphere(1), minimalSphere(1)) has the hand-derived generator counts (1, 3, 2) and terminates at dim 2" >> {
    val prod = product(SimplicialSetFixtures.minimalSphere(1), SimplicialSetFixtures.minimalSphere(1))
    prod.generatorsByDim.map(_.size) === IndexedSeq(1, 3, 2)
  }

  "product(minimalSphere(1), minimalSphere(1))'s induced face data satisfies the simplicial identities" >> {
    val prod = product(SimplicialSetFixtures.minimalSphere(1), SimplicialSetFixtures.minimalSphere(1))
    prod.validate() must beEmpty
  }

  "product(minimalSphere(1), minimalSphere(2))'s induced face data satisfies the simplicial identities (exercises the common-degeneracy stripping path)" >> {
    val prod = product(SimplicialSetFixtures.minimalSphere(1), SimplicialSetFixtures.minimalSphere(2))
    prod.validate() must beEmpty
  }

  "coproduct(minimalSphere(1), minimalSphere(2))'s induced face data satisfies the simplicial identities" >> {
    val coprod = coproduct(SimplicialSetFixtures.minimalSphere(1), SimplicialSetFixtures.minimalSphere(2))
    coprod.validate() must beEmpty
  }
