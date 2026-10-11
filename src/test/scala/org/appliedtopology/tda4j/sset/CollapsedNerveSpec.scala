package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import org.specs2.mutable.Specification

/** `RewritingSystem` and `CollapsedNerve` (Brown's collapse of the bar complex), checked three ways: term for term
  * against the slow reference implementation (`BrownCollapseReference`: critical cells by classifying every bar cell,
  * Morse boundary by the unmemoized flow), barcode for barcode against the bar complex itself, and against group
  * homology derived by hand where the bar complex is out of reach.
  */
class CollapsedNerveSpec extends Specification:
  sequential

  private def subgroups(g: FiniteGroup, generatorSets: Seq[Int]*): Seq[Set[Int]] =
    generatorSets.map(g.subgroupGeneratedBy)
  private def all(g: FiniteGroup): Set[Int] = (0 until g.order).toSet

  private val z4 = FiniteGroup.cyclic(4)
  private val z4chain = Seq(Set(0, 2), all(z4))
  private val z8 = FiniteGroup.cyclic(8)
  private val z8chain = Seq(Set(0, 4), Set(0, 2, 4, 6), all(z8))
  private val s3 = FiniteGroup.symmetric(3)
  private val s3chain = Seq(s3.subgroupGeneratedBy(Seq(FiniteGroup.elementOfPermutation(s3, Seq(1, 2, 0)))), all(s3))
  // D_8 as the symmetries of a square: rotation r = (0 1 2 3), reflection f = (1 3). A 2-group: every chain refines.
  private val (d8, d8gens) = FiniteGroup.permutationGroup(4, Seq(Seq(1, 2, 3, 0), Seq(0, 3, 2, 1)))
  private val d8chain = subgroups(d8, Seq(d8gens(1)), Seq(d8gens(1), d8.multiply(d8gens(0), d8gens(0)))) :+ all(d8)
  // S_4 through A_4: <(01)(23)> < V_4 < A_4 < S_4, each normal in the next.
  private val (s4, s4gens) =
    FiniteGroup.permutationGroup(4, Seq(Seq(1, 0, 3, 2), Seq(2, 3, 0, 1), Seq(1, 2, 0, 3), Seq(1, 0, 2, 3)))
  private val s4chain = subgroups(s4, s4gens.take(1), s4gens.take(2), s4gens.take(3)) :+ all(s4)

  private def refined(g: FiniteGroup, chain: Seq[Set[Int]]): RewritingSystem = RewritingSystem.refining(g, chain) match
    case Right(rw) => rw
    case Left(why) => throw AssertionError(why)

  private lazy val systems: Seq[(String, RewritingSystem, Seq[Set[Int]])] = Seq(
    ("Z/4", refined(z4, z4chain), z4chain),
    ("Z/8", refined(z8, z8chain), z8chain),
    ("S_3", refined(s3, s3chain), s3chain),
    ("D_8", refined(d8, d8chain), d8chain),
    ("S_4", refined(s4, s4chain), s4chain)
  )
  // One generator a = 1 for Z/4: complete, but element 2 = "aa" uses a letter outside Z/2.
  private val z4oneGenerator =
    RewritingSystem(z4, IndexedSeq(Vector(), Vector(1), Vector(1, 1), Vector(1, 1, 1)), Map(1 -> 1))

  private def reference(rw: RewritingSystem): BrownCollapseReference.Collapse =
    BrownCollapseReference.Collapse(
      BrownCollapseReference.Rewriting(rw.monoid, rw.letters, rw.normalForms.zipWithIndex.map(_.swap).toMap)
    )

  "RewritingSystem.refining" should {
    "give a complete system respecting the chain for p-groups and solvable groups whose chains are subnormal" in {
      val more = Seq(refined(FiniteGroup.generalLinear(2, 3), Seq(all(FiniteGroup.generalLinear(2, 3)))))
      (systems.map(_._2) ++ more).flatMap(_.validate()) must beEmpty
      systems.flatMap((_, rw, chain) => rw.respects(chain)) must beEmpty
    }
    "say why it cannot: a subgroup that is not subnormal, a group that is not solvable" in {
      val tutorialChain = subgroups(s4, Seq(s4gens(3)), Seq(s4gens(3), s4.multiply(s4gens(0), s4gens(3)))) :+ all(s4)
      (RewritingSystem.refining(s4, Seq(s4.subgroupGeneratedBy(Seq(s4gens(3))))) must beLeft(contain("not subnormal")))
        .and(
          RewritingSystem.refining(FiniteGroup.symmetric(5), Seq(all(FiniteGroup.symmetric(5)))) must beLeft(
            contain("not solvable")
          )
        )
        .and(
          RewritingSystem.refining(FiniteGroup.alternating(5), Seq(all(FiniteGroup.alternating(5)))) must beLeft(
            contain("not solvable")
          )
        )
        .and(tutorialChain.map(_.size) must beEqualTo(Seq(2, 4, 24)))
        .and(RewritingSystem.refining(s4, tutorialChain) must beLeft)
    }
    "need the collection order: S_4's rules lengthen words (shortlex would reject them) yet all decrease" in {
      val rw = refined(s4, s4chain)
      (rw.rules.exists((lhs, rhs) => rhs.length > lhs.length) must beTrue)
        .and(rw.rules.forall((lhs, rhs) => rw.compareWords(rhs, lhs) < 0) must beTrue)
    }
    "reject a rule that increases: Z/4's polycyclic normal forms with the levels swapped" in {
      val swapped = RewritingSystem(z4, IndexedSeq(Vector(), Vector(1), Vector(2), Vector(2, 1)), Map(1 -> 1, 2 -> 2))
      (refined(z4, z4chain).normalForms must beEqualTo(IndexedSeq(Vector(), Vector(1), Vector(2), Vector(1, 2))))
        .and(swapped.validate() must contain(contain("does not decrease")))
    }
  }

  "the collapsed nerve against the reference implementation" should {
    "have exactly the critical cells that classifying every bar cell finds" in {
      val cases =
        systems.map((name, rw, _) => (name, rw, if name == "S_4" then 3 else 4)) :+ ("Z/4 one", z4oneGenerator, 5)
      cases
        .flatMap { (name, rw, top) =>
          val collapsed = CollapsedNerve(rw)
          val ref = reference(rw)
          (0 to top).map { n =>
            val direct = collapsed.criticalCells(n).toSet
            val classified =
              collapsed.nerve.generators(n).filter(ref.role(_) == BrownCollapseReference.Role.Critical).toSet
            (name, n, direct == classified, direct.size == collapsed.criticalCells(n).size)
          }
        }
        .filterNot((_, _, same, distinct) => same && distinct) must beEmpty
    }
    "have the Morse boundary of the unmemoized flow, term for term, on every critical cell" in {
      val cases =
        systems.map((name, rw, _) => (name, rw, if name == "S_4" then 3 else 4)) :+ ("Z/4 one", z4oneGenerator, 5)
      cases.flatMap { (name, rw, top) =>
        val collapsed = CollapsedNerve(rw)
        val ref = reference(rw)
        (1 to top)
          .flatMap(collapsed.criticalCells)
          .filter(c => collapsed.morseBoundary(c) != ref.flow(c)._1)
          .map(name -> _)
      } must beEmpty
    }
    "leave S_4 with C(n+3, 3) = 1, 4, 10, 20, 35, 56 cells in dimensions 0..5 (the bar complex: 23^n), Z/3 one per dimension" in {
      val collapsed = CollapsedNerve(refined(s4, s4chain))
      val z3 = CollapsedNerve(refined(FiniteGroup.cyclic(3), Seq(all(FiniteGroup.cyclic(3)))))
      ((0 to 5).map(collapsed.criticalCells(_).size) must beEqualTo(Seq(1, 4, 10, 20, 35, 56)))
        .and((0 to 5).map(z3.criticalCells(_).size) must beEqualTo(Seq.fill(6)(1)))
    }
  }

  "the collapse on the systems refining builds" should {
    "be an involution with incidence ±1 (D_8 up to dimension 4, S_4 up to 3)" in {
      Seq(("D_8", 4), ("S_4", 3)).forall { (name, top) =>
        val collapsed = CollapsedNerve(systems.find(_._1 == name).get._2)
        (0 to top).forall(n =>
          collapsed.nerve.generators(n).forall { s =>
            collapsed.role(s) match
              case CollapseRole.Critical     => true
              case CollapseRole.Redundant(t) =>
                collapsed.role(t) == CollapseRole.Collapsible(s) && math
                  .abs(collapsed.barBoundary(t).getOrElse(s, 0L)) == 1
              case CollapseRole.Collapsible(f) => collapsed.role(f) == CollapseRole.Redundant(s)
          }
        )
      } must beTrue
    }
    "square to zero, ∂∂ = 0, on S_4's collapsed complex up to dimension 6 (where the bar complex cannot check it)" in {
      val collapsed = CollapsedNerve(refined(s4, s4chain))
      (2 to 6).flatMap(collapsed.criticalCells).filterNot { c =>
        collapsed
          .morseBoundary(c)
          .toSeq
          .flatMap((s, a) => collapsed.morseBoundary(s).toSeq.map((t, b) => (t, a * b)))
          .groupMapReduce(_._1)(_._2)(_ + _)
          .values
          .forall(_ == 0L)
      } must beEmpty
    }
  }

  "persistent homology on the collapsed nerve" should {
    "equal the bar complex's barcode" in {
      val cases = Seq(("Z/8", 2, 3), ("S_3", 2, 4), ("S_3", 3, 4), ("D_8", 2, 3), ("S_4", 2, 2), ("S_4", 3, 2))
      cases.map { (name, p, degree) =>
        val (_, rw, chain) = systems.find(_._1 == name).get
        val collapsed = CollapsedNerve(rw).persistentHomology(chain, degree, p).triples.sorted
        val bar = Nerve(rw.monoid).persistentHomology(ClassifyingSpace.filtrationBy(chain), degree, p).triples.sorted
        (name, p, collapsed == bar, collapsed.nonEmpty)
      } must beEqualTo(cases.map((name, p, _) => (name, p, true, true)))
    }
    "give H_*(S_4) beyond the bar complex's reach: F_2 1, 1, 2, 3, 3, 4 and F_3 1, 0, 0, 1, 1, 0" in {
      // H*(S_4; F_2) = F_2[σ1, σ2, c3]/(σ1 c3) (degrees 1, 2, 3): Poincaré series (1 + t^2)/((1 - t)(1 - t^3)).
      // F_3: the Sylow 3-subgroup is abelian with normalizer S_3, so H*(S_4; F_3) = H*(S_3; F_3).
      val collapsed = CollapsedNerve(refined(s4, s4chain))
      val f2 = collapsed.persistentHomology(Seq(all(s4)), 5, 2).bettiNumbers
      val barCellsToDimension6 = (0 to 6).map(n => BigInt(23).pow(n)).sum
      (f2 must beEqualTo(Vector(1, 1, 2, 3, 3, 4)))
        .and(collapsed.persistentHomology(Seq(all(s4)), 5, 3).bettiNumbers must beEqualTo(Vector(1, 0, 0, 1, 1, 0)))
        .and(BigInt(collapsed.redundantCellsVisited) * 100 must beLessThan(barCellsToDimension6))
    }
    "lift every representative to a cycle of the bar complex present at the bar's birth" in {
      Seq(("S_4", 2), ("S_4", 3), ("Z/8", 2), ("D_8", 2)).forall { (name, p) =>
        val (_, rw, chain) = systems.find(_._1 == name).get
        val diagram: PersistenceDiagram[NerveSimplex] = CollapsedNerve(rw).persistentHomology(chain, 3, p)
        val bar = Nerve(rw.monoid).skeleton(4)
        val level = ClassifyingSpace.filtrationBy(chain)
        import diagram.given
        given (NerveSimplex is OrderedCell) = bar.cellInstance
        diagram.bars.forall(b =>
          b.annotation.exists(rep =>
            !rep.isZero() && Chain.from(rep.boundary).terms.isEmpty && rep.cells.forall(level(_) <= b.birth)
          )
        )
      } must beTrue
    }
    "refuse a chain the rewriting system does not respect, and say what to do" in
      (z4oneGenerator.validate() must beEmpty)
        .and(z4oneGenerator.respects(z4chain) must not(beEmpty))
        .and(
          CollapsedNerve(z4oneGenerator).persistentHomology(z4chain, 3, 2) must throwAn[IllegalArgumentException](
            message = "RewritingSystem.refining"
          )
        )
        .and(
          CollapsedNerve(z4oneGenerator).persistentHomology(Seq(all(z4)), 3, 2).bettiNumbers must beEqualTo(
            Vector(1, 1, 1, 1)
          )
        )
    "handle a chain that stops below the whole group, like the bar complex of its last subgroup" in {
      val cases =
        Seq(
          (z8, Seq(Set(0, 4), Set(0, 2, 4, 6)), 2, 3),
          (s4, s4chain.slice(1, 3), 2, 2),
          (s4, s4chain.slice(1, 3), 3, 2)
        )
      cases.map { (g, chain, p, degree) =>
        val dispatched = ClassifyingSpace.persistentGroupHomology(g, chain, degree, p).triples.sorted
        val bar =
          Nerve(g, Some(chain.last)).persistentHomology(ClassifyingSpace.filtrationBy(chain), degree, p).triples.sorted
        (RewritingSystem.refining(g, chain).isRight, dispatched == bar, dispatched.nonEmpty)
      } must beEqualTo(cases.map(_ => (true, true, true)))
    }
    "be what persistentGroupHomology uses for a chain that refines, and the bar complex otherwise" in
      // Both give the same barcode, so check the dispatch through what only the collapse can reach in this time:
      // degree 4 of S_4 (the bar complex needs 6.7 million cells for it).
      (ClassifyingSpace.bettiNumbers(s4, 4, 2) must beEqualTo(Vector(1, 1, 2, 3, 3)))
        .and(
          ClassifyingSpace.persistentGroupHomology(s4, s4chain, 2, 2).triples.sorted must beEqualTo(
            Nerve(s4).persistentHomology(ClassifyingSpace.filtrationBy(s4chain), 2, 2).triples.sorted
          )
        )
  }
