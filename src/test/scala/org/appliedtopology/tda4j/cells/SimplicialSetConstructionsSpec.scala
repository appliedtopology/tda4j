package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}
import FiniteSimplicialSet.*

import org.specs2.mutable

/** Structural tests for `product`/`coproduct` (`SimplicialSetConstructions.scala`) -- the word-based non-degeneracy
  * shortcut checked against its definition, the hand-derived generator counts for
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

  "identify(coproduct(edge, edge), ...)'s induced face data satisfies the simplicial identities, and has the hand-derived (2, 2) generator counts of a bigon" >> {
    import SimplicialSetFixtures.EdgeGenerator
    import SimplicialSetFixtures.EdgeGenerator.*

    type G = Either[EdgeGenerator, EdgeGenerator]
    given Ordering[G] = eitherOrdering[EdgeGenerator, EdgeGenerator]

    val bigon = identify(
      coproduct(SimplicialSetFixtures.edge, SimplicialSetFixtures.edge),
      Seq[(G, G)]((Left(V0), Right(V0)), (Left(V1), Right(V1)))
    )
    (bigon.validate() must beEmpty).and(bigon.generatorsByDim.map(_.size) === IndexedSeq(2, 2))
  }

  "quotient(triangle, ...)'s induced face data satisfies the simplicial identities, and has the hand-derived (1, 1, 1) generator counts of Hatcher's single-2-simplex RP^2 model" >> {
    val rp2ViaQuotient = SimplicialSetFixtures.realProjectiveSpaceViaQuotient
    (rp2ViaQuotient.validate() must beEmpty).and(rp2ViaQuotient.generatorsByDim.map(_.size) === IndexedSeq(1, 1, 1))
  }

  "quotient with a dimension-inconsistent quotientMap is caught by validate() as a structural error, not silently accepted -- validate() is a necessary precondition, even though it can't by itself prove a quotient is the INTENDED one (see quotient's own doc comment)" >> {
    import SimplicialSetFixtures.TriangleGenerator
    import SimplicialSetFixtures.TriangleGenerator.*

    // Wrong on purpose: V0 (dimension 0) is mapped to a degenerate dimension-1 element instead of a fixed point
    // or a dimension-0 target -- every OTHER generator is left alone (mapped to itself), so this isolates the
    // one broken case rather than conflating it with an otherwise-nontrivial gluing.
    def brokenQuotientMap(g: TriangleGenerator): SSetElement[TriangleGenerator] = g match
      case V0    => SSetElement(List(0), V1)
      case other => SSetElement(Nil, other)

    quotient(SimplicialSetFixtures.triangle, brokenQuotientMap).validate() must not(beEmpty)
  }

  "identify refuses to glue generators of different dimensions" >> {
    import SimplicialSetFixtures.TriangleGenerator
    import SimplicialSetFixtures.TriangleGenerator.*

    identify(SimplicialSetFixtures.triangle, Seq((V0, E01))) must throwAn[IllegalArgumentException]
  }
