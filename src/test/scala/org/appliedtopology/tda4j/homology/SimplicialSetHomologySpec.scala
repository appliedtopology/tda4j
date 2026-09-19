package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.cells.SimplicialSetFixtures
import org.appliedtopology.tda4j.streams.{given, *}

import org.specs2.mutable

/** Homology of the hand-built `SimplicialSetFixtures`, computed through the real `CellularHomologyContext` engine
  * via the trivial `SimplicialSetStream` adapter -- the actual evidence that `faceOf`/`insertOuter`
  * (`SSetElement.scala`) are correct, independent of `SSetElementSpec`'s direct algebra traces. Every generator
  * sits at filtration value 0, so a finite bar is always `(dim, 0, 0)` and an essential one `(dim, 0,
  * Int.MaxValue)` -- see `SimplicialSetStream`'s own doc. Expected answers hand-derived in
  * `.claude/WORKLOG-simplicial-sets.md`.
  */
class SimplicialSetHomologySpec extends mutable.Specification:

  private def homologyOf[G, CoefficientT: Field](sset: FiniteSimplicialSet[G]): List[(Int, Int, Int)] =
    given (G is OrderedCell) = sset.cellInstance
    val chc = CellularHomologyContext[G, CoefficientT, Int]()
    chc.persistentHomology(SimplicialSetStream(sset)).diagramAt(0)

  private def essentialCountsByDim(diagram: List[(Int, Int, Int)]): Map[Int, Int] =
    diagram.collect { case (dim, _, Int.MaxValue) => dim }.groupBy(identity).view.mapValues(_.size).toMap

  private def finiteCount(diagram: List[(Int, Int, Int)]): Int =
    diagram.count { case (_, _, death) => death != Int.MaxValue }

  private val f11 = new FiniteField(11)
  import f11.given

  "Minimal S^n has H_0 = H_n = F, nothing else, for n = 1, 2, 3" >> {
    forall(1 to 3) { n =>
      homologyOf[SimplicialSetFixtures.SphereGenerator, f11.Fp](SimplicialSetFixtures.minimalSphere(n)) must
        containTheSameElementsAs(List((0, 0, Int.MaxValue), (n, 0, Int.MaxValue)))
    }
  }

  "RP2 is the sign-discriminating fixture: H_1=H_2=F2 over F2, both 0 over F3" >> {
    val f2 = new FiniteField(2)
    val f3 = new FiniteField(3)
    import f2.given
    import f3.given

    val overF2 = homologyOf[SimplicialSetFixtures.ProjectiveGenerator, f2.Fp](SimplicialSetFixtures.realProjectiveSpace(2))
    val overF3 = homologyOf[SimplicialSetFixtures.ProjectiveGenerator, f3.Fp](SimplicialSetFixtures.realProjectiveSpace(2))

    (overF2 must containTheSameElementsAs(List((0, 0, Int.MaxValue), (1, 0, Int.MaxValue), (2, 0, Int.MaxValue))))
      .and(overF3 must containTheSameElementsAs(List((0, 0, Int.MaxValue), (1, 0, 0))))
  }

  "RP3 has an essential H_3 over every field, but H_1=H_2=0 over F3 (vs. F2 = F2 everywhere)" >> {
    val f2 = new FiniteField(2)
    val f3 = new FiniteField(3)
    import f2.given
    import f3.given

    val overF2 = homologyOf[SimplicialSetFixtures.ProjectiveGenerator, f2.Fp](SimplicialSetFixtures.realProjectiveSpace(3))
    val overF3 = homologyOf[SimplicialSetFixtures.ProjectiveGenerator, f3.Fp](SimplicialSetFixtures.realProjectiveSpace(3))

    (overF2 must containTheSameElementsAs(
      List((0, 0, Int.MaxValue), (1, 0, Int.MaxValue), (2, 0, Int.MaxValue), (3, 0, Int.MaxValue))
    )).and(overF3 must containTheSameElementsAs(List((0, 0, Int.MaxValue), (1, 0, 0), (3, 0, Int.MaxValue))))
  }

  "Torus has Betti numbers (1, 2, 1) for every coefficient field" >> {
    val diagram = homologyOf[SimplicialSetFixtures.TorusGenerator, f11.Fp](SimplicialSetFixtures.torus)
    (essentialCountsByDim(diagram) === Map(0 -> 1, 1 -> 2, 2 -> 1)).and(finiteCount(diagram) === 1)
  }

  "product(minimalSphere(1), minimalSphere(1)) has the torus's Betti numbers (1, 2, 1), via a completely different construction than the hand-built torus fixture" >> {
    val prod = product(SimplicialSetFixtures.minimalSphere(1), SimplicialSetFixtures.minimalSphere(1))
    val diagram = homologyOf[ProductGenerator[SimplicialSetFixtures.SphereGenerator, SimplicialSetFixtures.SphereGenerator], f11.Fp](prod)
    essentialCountsByDim(diagram) === Map(0 -> 1, 1 -> 2, 2 -> 1)
  }

  "product(minimalSphere(1), minimalSphere(2)) matches Kunneth's S^1 x S^2 Betti numbers (1, 1, 1, 1) -- this is the case that exercises the common-degeneracy stripping path in product's face maps" >> {
    val prod = product(SimplicialSetFixtures.minimalSphere(1), SimplicialSetFixtures.minimalSphere(2))
    val diagram = homologyOf[ProductGenerator[SimplicialSetFixtures.SphereGenerator, SimplicialSetFixtures.SphereGenerator], f11.Fp](prod)
    essentialCountsByDim(diagram) === Map(0 -> 1, 1 -> 1, 2 -> 1, 3 -> 1)
  }

  "coproduct(minimalSphere(1), minimalSphere(2)) has Betti numbers (2, 1, 1) -- unreduced H_0 adds directly across a disjoint union" >> {
    val coprod = coproduct(SimplicialSetFixtures.minimalSphere(1), SimplicialSetFixtures.minimalSphere(2))
    val diagram = homologyOf[Either[SimplicialSetFixtures.SphereGenerator, SimplicialSetFixtures.SphereGenerator], f11.Fp](coprod)
    essentialCountsByDim(diagram) === Map(0 -> 2, 1 -> 1, 2 -> 1)
  }

  "Every fixture's diagram accounts for exactly one bar-cell-slot per generator" >> {
    def totalCells[G](sset: FiniteSimplicialSet[G]): Int = sset.generatorsByDim.map(_.size).sum
    def accountsForAllCells(diagram: List[(Int, Int, Int)], cells: Int): Boolean =
      val (finite, essential) = diagram.partition { case (_, _, death) => death != Int.MaxValue }
      finite.size * 2 + essential.size == cells

    val s3 = SimplicialSetFixtures.minimalSphere(3)
    val rp3 = SimplicialSetFixtures.realProjectiveSpace(3)
    val torus = SimplicialSetFixtures.torus

    accountsForAllCells(homologyOf[SimplicialSetFixtures.SphereGenerator, f11.Fp](s3), totalCells(s3)) must beTrue
    accountsForAllCells(homologyOf[SimplicialSetFixtures.ProjectiveGenerator, f11.Fp](rp3), totalCells(rp3)) must beTrue
    accountsForAllCells(homologyOf[SimplicialSetFixtures.TorusGenerator, f11.Fp](torus), totalCells(torus)) must beTrue
  }
