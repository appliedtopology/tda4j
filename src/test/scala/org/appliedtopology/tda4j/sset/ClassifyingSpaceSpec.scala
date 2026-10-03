package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import org.specs2.mutable.Specification

/** Persistent group homology, against group-cohomology values derived by hand (see the comment on each). */
class ClassifyingSpaceSpec extends Specification:

  "the group tables" should {
    "be groups" in {
      val groups = Seq(
        FiniteGroup.cyclic(6),
        FiniteGroup.product(FiniteGroup.cyclic(2), FiniteGroup.cyclic(2)),
        FiniteGroup.symmetric(3),
        FiniteGroup.symmetric(4)
      )
      groups.flatMap(_.validate()) must beEmpty
    }
    "have the right orders" in {
      Seq(3, 4, 5).map(n => FiniteGroup.symmetric(n).order) must beEqualTo(Seq(6, 24, 120))
    }
  }

  "the classifying space" should {
    "be a valid simplicial set (simplicial identities hold)" in {
      val b = ClassifyingSpace(FiniteGroup.symmetric(3), 3)
      b.validate() must beEmpty
    }
    "have (|G|-1)^n non-degenerate n-simplices" in {
      ClassifyingSpace(FiniteGroup.symmetric(3), 3).generatorsByDim.map(_.size) must beEqualTo(Vector(1, 5, 25, 125))
    }
  }

  "H_n(G; F_p) dimensions" should {
    "Z/2 over F_2: one class in every degree" in {
      ClassifyingSpace.bettiNumbers(FiniteGroup.cyclic(2), 4, 2) must beEqualTo(Vector(1, 1, 1, 1, 1))
    }
    "Z/3 over F_2: only degree 0 (2 and 3 are coprime)" in {
      ClassifyingSpace.bettiNumbers(FiniteGroup.cyclic(3), 4, 2) must beEqualTo(Vector(1, 0, 0, 0, 0))
    }
    "Z/3 over F_3: one class in every degree" in {
      ClassifyingSpace.bettiNumbers(FiniteGroup.cyclic(3), 4, 3) must beEqualTo(Vector(1, 1, 1, 1, 1))
    }
    "Z/2 x Z/2 over F_2: n + 1 in degree n (Kunneth)" in {
      val v4 = FiniteGroup.product(FiniteGroup.cyclic(2), FiniteGroup.cyclic(2))
      ClassifyingSpace.bettiNumbers(v4, 3, 2) must beEqualTo(Vector(1, 2, 3, 4))
    }
    "S_3 over F_2 equals Z/2's (its Sylow 2-subgroup has odd index)" in {
      ClassifyingSpace.bettiNumbers(FiniteGroup.symmetric(3), 4, 2) must beEqualTo(Vector(1, 1, 1, 1, 1))
    }
    "S_3 over F_3: from H_n(S_3; Z) = Z/2, 0, Z/6, 0 (n = 1..4) by universal coefficients" in {
      ClassifyingSpace.bettiNumbers(FiniteGroup.symmetric(3), 4, 3) must beEqualTo(Vector(1, 0, 0, 1, 1))
    }
    "S_4 over F_2: H_1 = abelianization (Z/2), H_2 from Schur multiplier Z/2 plus Tor (so 2)" in {
      // H_3 (dimension 3 in H*(S_4;F_2), by hand: 3) needs 280k cells and takes > 9 minutes with the chunks engine: not run here.
      ClassifyingSpace.bettiNumbers(FiniteGroup.symmetric(4), 2, 2) must beEqualTo(Vector(1, 1, 2))
    }
  }

  "persistent group homology over a subgroup chain" should {
    "S_4 over F_2, along <(01)> < <(01),(23)> < D_8 < S_4: nothing is born at S_4 (the Sylow 2-subgroup D_8 surjects)" in {
      val (s4, gens) =
        FiniteGroup.permutationGroup(4, Seq(Seq(1, 0, 2, 3), Seq(0, 1, 3, 2), Seq(2, 3, 0, 1), Seq(1, 2, 3, 0)))
      val a = gens(0); val b = gens(1); val c = gens(2)
      val chain = Seq(
        s4.subgroupGeneratedBy(Seq(a)),
        s4.subgroupGeneratedBy(Seq(a, b)),
        s4.subgroupGeneratedBy(Seq(a, b, c)),
        (0 until s4.order).toSet
      )
      chain.map(_.size) must beEqualTo(Seq(2, 4, 8, 24))
      val diagram = ClassifyingSpace.persistentGroupHomology(s4, chain, 2, 2)
      val essential = diagram.filter(_._3.isPosInfinity)
      (Vector.tabulate(3)(n => essential.count(_._1 == n)) must beEqualTo(Vector(1, 1, 2)))
        .and(essential.forall(_._2 < 3.0) must beTrue)
    }
  }

  "the lazy nerve" should {
    "be infinite: generators in every dimension, (|G|-1)^n of them" in {
      val nerve = ClassifyingSpace.nerve(FiniteGroup.symmetric(3))
      Seq(0, 1, 2, 3, 6).map(n => nerve.generatorsAt(n).size) must beEqualTo(Seq(1, 5, 25, 125, 15625))
    }
    "have skeletons whose homology is right only below the top degree" in {
      val nerve = ClassifyingSpace.nerve(FiniteGroup.cyclic(3))
      // B(Z/3) over F_2 is acyclic. Its 1-skeleton (a bouquet of 2 circles) has H_1 of rank 2, so the top degree is wrong
      // exactly as the `skeleton` doc warns; asking for degree 1 via the 2-skeleton gets it right.
      (ClassifyingSpace.bettiNumbers(nerve, 1, 2) must beEqualTo(Vector(1, 0)))
        .and(nerve.skeleton(1).generatorsByDim.map(_.size) must beEqualTo(Vector(1, 2)))
    }
    "model a monoid: the multiplicative monoid mod 4 has an absorbing 0, so its nerve is contractible" in {
      val monoid = FiniteMonoid.multiplicativeResidues(4)
      (monoid.validateMonoid() must beEmpty)
        .and(Nerve(monoid).skeleton(4).validate() must beEmpty)
        .and(ClassifyingSpace.bettiNumbers(Nerve(monoid), 3, 2) must beEqualTo(Vector(1, 0, 0, 0)))
    }
    "reject a subset that is not closed under multiplication" in {
      Nerve(FiniteGroup.cyclic(4), Some(Set(0, 1))) must throwAn[IllegalArgumentException]
    }
  }
