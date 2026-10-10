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
      Seq(0, 1, 2, 3, 6).map(n => nerve.generators(n).size) must beEqualTo(Seq(1, 5, 25, 125, 15625))
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

  // Finite monoids whose nerves look like no finite group's: free homology, identical over F_2 and F_3, vanishing above
  // degree 2 (a nontrivial finite group has torsion in infinitely many degrees). Values: Steinberg, "The homology of
  // completely simple semigroups" (arXiv:2405.06594), Thm 3.1 (bands) and Thm A (Rees matrix semigroups).
  "nerves of finite monoids that are not groups" should {
    "rectangular band A x B with an identity: a wedge of (|A|-1)(|B|-1) two-spheres" in {
      val cases = Seq((2, 2, 4, Vector(1, 0, 1, 0, 0)), (2, 3, 4, Vector(1, 0, 2, 0, 0)), (3, 3, 3, Vector(1, 0, 4, 0)))
      cases.flatMap { (rows, columns, degree, expected) =>
        val band = FiniteMonoid.rectangularBand(rows, columns)
        val wrong = band.validateMonoid() ++ Nerve(band).skeleton(3).validate()
        Seq(2, 3).map(p => (rows, columns, p, wrong, ClassifyingSpace.bettiNumbers(Nerve(band), degree, p)))
      } must beEqualTo(cases.flatMap { (rows, columns, _, expected) =>
        Seq(2, 3).map(p => (rows, columns, p, Seq.empty[String], expected))
      })
    }
    "Rees matrix monoids over Z/2 told apart by one sandwich entry, over F_2 only" in {
      // H_1 = coker ψ, H_2 = H_2(Z/2) ⊕ ker ψ, H_n = H_n(Z/2) for n >= 3, with ψ: Z -> Z/2 sending the generator to the
      // normalized sandwich entry P(1)(1). Entry 0: H = Z, Z/2, Z, Z/2 (n = 0..3); entry g: H = Z, 0, Z, Z/2.
      val z2 = FiniteGroup.cyclic(2)
      def rees(entry: Int) = FiniteMonoid.reesMatrix(z2, 2, 2, IndexedSeq(IndexedSeq(0, 0), IndexedSeq(0, entry)))
      val betti = for entry <- Seq(0, 1); p <- Seq(2, 3)
      yield (rees(entry).validateMonoid(), ClassifyingSpace.bettiNumbers(Nerve(rees(entry)), 3, p))
      betti must beEqualTo(
        Seq(Vector(1, 1, 2, 1), Vector(1, 0, 1, 0), Vector(1, 0, 1, 1), Vector(1, 0, 1, 0)).map(b =>
          (Seq.empty[String], b)
        )
      )
    }
    "rectangular band with trivial group: reesMatrix agrees with rectangularBand" in {
      val trivial = FiniteGroup.cyclic(1)
      val rees = FiniteMonoid.reesMatrix(trivial, 2, 3, IndexedSeq.fill(3)(IndexedSeq.fill(2)(0)))
      rees.table.map(_.toSeq).toSeq must beEqualTo(FiniteMonoid.rectangularBand(2, 3).table.map(_.toSeq).toSeq)
    }
  }

  // The posets of nontrivial p-subgroups S_p(G) and of elementary abelian ones A_p(G), as categories: their nerves are
  // the order complexes, and Quillen proved S_p(G) ≃ A_p(G). The counts were first found by a different enumeration
  // (subgroups generated by at most two p-elements), so agreement here cross-checks pSubgroups.
  "p-subgroup posets" should {
    def orderComplexOf(poset: IndexedSeq[Set[Int]]) =
      CategoryNerve(FiniteCategory.fromPoset(poset, (a: Set[Int], b: Set[Int]) => a.subsetOf(b))).skeleton(4)
    val gl32 = FiniteGroup.generalLinear(3, 2)
    val a5 = FiniteGroup.alternating(5)
    val s4 = FiniteGroup.symmetric(4)

    "come from groups of the right orders" in {
      Seq(gl32, FiniteGroup.generalLinear(2, 3), a5, FiniteGroup.alternating(4)).map(g => (g.order, g.validate())) must
        beEqualTo(Seq(168, 48, 60, 12).map(n => (n, Seq.empty[String])))
    }
    "have the expected subgroups, f-vectors and homology (F_2 = F_3)" in {
      // S_4: contractible, since O_2(S_4) = V_4 is a nontrivial normal 2-subgroup. A_5 = PSL(2,4): its building is 5
      // points. GL(3,2): its building is the Fano incidence graph, a wedge of 8 = 2^3 circles (the Steinberg rank).
      val rows = for
        (name, g) <- Seq("S_4" -> s4, "A_5" -> a5, "GL(3,2)" -> gl32)
        s2 = g.pSubgroups(2)
        a2 = s2.filter(g.isElementaryAbelian(_, 2))
        poset <- Seq(s2, a2)
      yield
        val x = orderComplexOf(poset)
        (
          name,
          poset.groupBy(_.size).view.mapValues(_.size).toMap,
          x.fVector.takeWhile(_ > 0),
          x.bettiNumbers(2),
          x.bettiNumbers(3)
        )
      rows.map(r => (r._1, r._2, r._3, r._4 == r._5, r._4.take(r._3.size))) must beEqualTo(
        Seq(
          ("S_4", Map(2 -> 9, 4 -> 7, 8 -> 3), Vector(19, 39, 21), true, Vector(1, 0, 0)),
          ("S_4", Map(2 -> 9, 4 -> 4), Vector(13, 12), true, Vector(1, 0)),
          ("A_5", Map(2 -> 15, 4 -> 5), Vector(20, 15), true, Vector(5, 0)),
          ("A_5", Map(2 -> 15, 4 -> 5), Vector(20, 15), true, Vector(5, 0)),
          ("GL(3,2)", Map(2 -> 21, 4 -> 35, 8 -> 21), Vector(77, 231, 147), true, Vector(1, 8, 0)),
          ("GL(3,2)", Map(2 -> 21, 4 -> 14), Vector(35, 42), true, Vector(1, 8))
        )
      )
    }
    "satisfy Brown's congruence: χ(S_p(G)) ≡ 1 mod |G|_p" in {
      def pPart(n: Int, p: Int): Int = if n % p == 0 then p * pPart(n / p, p) else 1
      val cases = Seq(
        s4 -> 2,
        s4 -> 3,
        a5 -> 2,
        a5 -> 3,
        a5 -> 5,
        gl32 -> 2,
        gl32 -> 3,
        gl32 -> 7,
        FiniteGroup.generalLinear(2, 3) -> 2,
        FiniteGroup.generalLinear(2, 3) -> 3
      )
      cases.map { (g, p) =>
        val chi = orderComplexOf(g.pSubgroups(p)).eulerCharacteristic
        Math.floorMod(chi - 1, pPart(g.order, p))
      } must beEqualTo(cases.map(_ => 0))
    }
    "agree with the order complex fromSimplicialComplex builds, simplex count for simplex count" in {
      val s2 = gl32.pSubgroups(2)
      val chains = s2.indices.toList.flatMap { i =>
        def up(c: List[Int]): List[List[Int]] =
          c :: s2.indices.toList
            .filter(j => s2(j).size > s2(c.head).size && s2(c.head).subsetOf(s2(j)))
            .flatMap(j => up(j :: c))
        up(List(i))
      }
      SimplicialSet.fromSimplicialComplex(chains.map(c => Simplex.from(c.sorted))).fVector must
        beEqualTo(Vector(77, 231, 147))
    }
  }

  "persistent homology of a monoid nerve along a chain of submonoids" should {
    // Sub-bands 2x2 < 2x3 < 3x3 of the 3x3 band. Any retractions r of the rows and r' of the columns give a monoid
    // retraction (a, b) |-> (r a, r' b) onto a sub-band, so every map in the chain is split injective on homology:
    // no finite bars at all, and H_2's essential classes are born 1, 1, 2 at levels 0, 1, 2.
    val band = FiniteMonoid.rectangularBand(3, 3)
    def subBand(rows: Int, columns: Int): Set[Int] =
      Set(0) ++ (for a <- 0 until rows; b <- 0 until columns yield 1 + a * 3 + b)
    val chain = Seq(subBand(2, 2), subBand(2, 3), subBand(3, 3))
    val nerve = Nerve(band)

    "have no finite bars and H_2 born at levels 0, 1, 2, 2, over F_2 and F_3" in {
      Seq(2, 3).map { p =>
        val diagram = nerve.persistentHomology(ClassifyingSpace.filtrationBy(chain), 3, p)
        (diagram.bars.count(!_.death.isPosInfinity), diagram.essential.map(b => (b.dim, b.birth)).sorted)
      } must beEqualTo(Seq.fill(2)((0, List((0, 0.0), (2, 0.0), (2, 1.0), (2, 2.0), (2, 2.0)))))
    }
    "give every bar a representative cycle that exists at its birth" in {
      val diagram = nerve.persistentHomology(ClassifyingSpace.filtrationBy(chain), 2, 3)
      val x = nerve.skeleton(3)
      import diagram.given
      given (NerveSimplex is OrderedCell) = x.cellInstance
      val level = ClassifyingSpace.filtrationBy(chain)
      diagram.bars.forall { bar =>
        bar.annotation.exists { rep =>
          !rep.isZero() && Chain.from(rep.boundary).terms.isEmpty && rep.cells.forall(level(_) <= bar.birth)
        }
      } must beTrue
    }
    "reject a filtration that lets a simplex enter before its face" in {
      nerve.persistentHomology(s => if s.dim == 0 then 1.0 else 0.0, 1, 2) must throwAn[IllegalArgumentException](
        message = "enter before its face"
      )
    }
  }
