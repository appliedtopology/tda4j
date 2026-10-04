package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import org.specs2.mutable

/** Homology of the hand-built `SimplicialSetFixtures`, computed through the real `CellularHomologyEngine` engine via
  * the trivial `SimplicialSetStream` adapter -- the actual evidence that `faceOf`/`insertOuter` (`SSetElement.scala`)
  * are correct, independent of `SSetElementSpec`'s direct algebra traces. Every generator sits at filtration value 0,
  * so every finite bar is zero-length (and left out) and the diagram is the essential classes `(dim, 0, Int.MaxValue)`,
  * one per homology generator. Expected answers hand-derived in `.claude/WORKLOG-simplicial-sets.md`.
  */
class SimplicialSetHomologySpec extends mutable.Specification:

  private def homologyOf[G, CoefficientT: Field](sset: FiniteSimplicialSet[G]): List[(Int, Int, Int)] =
    given (G is OrderedCell) = sset.cellInstance
    val chc = CellularHomologyEngine[G, CoefficientT, Int]()
    chc.persistentHomology(SimplicialSetStream(sset)).diagramAt(0)

  private def essentialCountsByDim(diagram: List[(Int, Int, Int)]): Map[Int, Int] =
    diagram.collect { case (dim, _, Int.MaxValue) => dim }.groupBy(identity).view.mapValues(_.size).toMap

  private val f11 = new FiniteField(11)
  import f11.given

  "Minimal S^n has H_0 = H_n = F, nothing else, for n = 1, 2, 3" >>
    forall(1 to 3) { n =>
      homologyOf[SimplicialSetFixtures.SphereGenerator, f11.Fp](SimplicialSetFixtures.minimalSphere(n)) must
        containTheSameElementsAs(List((0, 0, Int.MaxValue), (n, 0, Int.MaxValue)))
    }

  "RP2 is the sign-discriminating fixture: H_1=H_2=F2 over F2, both 0 over F3" >> {
    val f2 = new FiniteField(2)
    val f3 = new FiniteField(3)
    import f2.given
    import f3.given

    val overF2 =
      homologyOf[RealProjectiveGenerator, f2.Fp](SimplicialSet.realProjectiveSpace(2))
    val overF3 =
      homologyOf[RealProjectiveGenerator, f3.Fp](SimplicialSet.realProjectiveSpace(2))

    (overF2 must containTheSameElementsAs(List((0, 0, Int.MaxValue), (1, 0, Int.MaxValue), (2, 0, Int.MaxValue))))
      .and(overF3 must containTheSameElementsAs(List((0, 0, Int.MaxValue))))
  }

  "RP3 has an essential H_3 over every field, but H_1=H_2=0 over F3 (vs. F2 = F2 everywhere)" >> {
    val f2 = new FiniteField(2)
    val f3 = new FiniteField(3)
    import f2.given
    import f3.given

    val overF2 =
      homologyOf[RealProjectiveGenerator, f2.Fp](SimplicialSet.realProjectiveSpace(3))
    val overF3 =
      homologyOf[RealProjectiveGenerator, f3.Fp](SimplicialSet.realProjectiveSpace(3))

    (overF2 must containTheSameElementsAs(
      List((0, 0, Int.MaxValue), (1, 0, Int.MaxValue), (2, 0, Int.MaxValue), (3, 0, Int.MaxValue))
    )).and(overF3 must containTheSameElementsAs(List((0, 0, Int.MaxValue), (3, 0, Int.MaxValue))))
  }

  "Torus has Betti numbers (1, 2, 1) for every coefficient field" >> {
    val diagram = homologyOf[TorusGenerator, f11.Fp](SimplicialSet.torus)
    essentialCountsByDim(diagram) === Map(0 -> 1, 1 -> 2, 2 -> 1)
  }

  "product(minimalSphere(1), minimalSphere(1)) has the torus's Betti numbers (1, 2, 1), via a completely different construction than the hand-built torus fixture" >> {
    val prod = SimplicialSetFixtures.minimalSphere(1).product(SimplicialSetFixtures.minimalSphere(1))
    val diagram = homologyOf[
      ProductGenerator[SimplicialSetFixtures.SphereGenerator, SimplicialSetFixtures.SphereGenerator],
      f11.Fp
    ](prod)
    essentialCountsByDim(diagram) === Map(0 -> 1, 1 -> 2, 2 -> 1)
  }

  "product(minimalSphere(1), minimalSphere(2)) matches Kunneth's S^1 x S^2 Betti numbers (1, 1, 1, 1) -- this is the case that exercises the common-degeneracy stripping path in product's face maps" >> {
    val prod = SimplicialSetFixtures.minimalSphere(1).product(SimplicialSetFixtures.minimalSphere(2))
    val diagram = homologyOf[
      ProductGenerator[SimplicialSetFixtures.SphereGenerator, SimplicialSetFixtures.SphereGenerator],
      f11.Fp
    ](prod)
    essentialCountsByDim(diagram) === Map(0 -> 1, 1 -> 1, 2 -> 1, 3 -> 1)
  }

  "coproduct(minimalSphere(1), minimalSphere(2)) has Betti numbers (2, 1, 1) -- unreduced H_0 adds directly across a disjoint union" >> {
    val coprod = SimplicialSetFixtures.minimalSphere(1).coproduct(SimplicialSetFixtures.minimalSphere(2))
    val diagram =
      homologyOf[Either[SimplicialSetFixtures.SphereGenerator, SimplicialSetFixtures.SphereGenerator], f11.Fp](coprod)
    essentialCountsByDim(diagram) === Map(0 -> 2, 1 -> 1, 2 -> 1)
  }

  "identify(coproduct(edge, edge), ...) glues two disjoint edges endpoint-to-endpoint into a bigon (S^1): H_0 = H_1 = F, hand-verifiable directly (two edges sharing the same pair of boundary vertices, so the boundary map has rank 1, not 2)" >> {
    import SimplicialSetFixtures.EdgeGenerator
    import SimplicialSetFixtures.EdgeGenerator.*

    type G = Either[EdgeGenerator, EdgeGenerator]
    given Ordering[G] = eitherOrdering[EdgeGenerator, EdgeGenerator]

    val bigon: FiniteSimplicialSet[G] =
      SimplicialSetFixtures.edge
        .coproduct(SimplicialSetFixtures.edge)
        .identify(Seq[(G, G)]((Left(V0), Right(V0)), (Left(V1), Right(V1))))

    (bigon.validate() must beEmpty)
      .and(bigon.generatorsByDim.map(_.size).toList === List(2, 2))
      .and(essentialCountsByDim(homologyOf[G, f11.Fp](bigon)) === Map(0 -> 1, 1 -> 1))
  }

  "quotient(triangle, ...) reproduces Hatcher's single-2-simplex Delta-complex model of RP^2 by gluing two of a filled triangle's edges into a loop and collapsing the third to a degenerate point -- cross-validated against the independently-hand-built realProjectiveSpace(2) fixture, over both F2 and F3 (the sign-discriminating pair)" >> {
    import SimplicialSetFixtures.TriangleGenerator

    val rp2ViaQuotient = SimplicialSetFixtures.realProjectiveSpaceViaQuotient

    def diagramOver[CoefficientT: Field](sset: FiniteSimplicialSet[TriangleGenerator]) =
      homologyOf[TriangleGenerator, CoefficientT](sset)

    val f2 = new FiniteField(2)
    val f3 = new FiniteField(3)

    val overF2 =
      import f2.given; diagramOver[f2.Fp](rp2ViaQuotient)
    val overF3 =
      import f3.given; diagramOver[f3.Fp](rp2ViaQuotient)

    (rp2ViaQuotient.validate() must beEmpty)
      .and(rp2ViaQuotient.generatorsByDim.map(_.size).toList === List(1, 1, 1))
      .and(overF2 must containTheSameElementsAs(List((0, 0, Int.MaxValue), (1, 0, Int.MaxValue), (2, 0, Int.MaxValue))))
      .and(overF3 must containTheSameElementsAs(List((0, 0, Int.MaxValue))))
  }
