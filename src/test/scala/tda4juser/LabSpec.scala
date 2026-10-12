package tda4juser

import org.specs2.mutable.Specification
import scala.compiletime.testing.typeCheckErrors

/** Prebuilt labs, from outside the package: one import line, and the arithmetic they set up is the field they name. */
class LabSpec extends Specification:
  "import TDAlab.F17.{*, given}" should {
    "be the only import a simplicial lab user needs" in {
      typeCheckErrors(
        """
        import org.appliedtopology.tda4j.TDAlab.F17.{*, given}
        val c: Chain[Simplex[Int], CoefficientT] = Fp(2) ⊠ ∆(1, 2) - ∆(2, 3)
        val d = Persistence(Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.0, 1.0)))
        val s = Simplex.fromSortedSet(scala.collection.immutable.SortedSet(1, 2))
        c.show
        """
      ) must beEmpty
    }
    "compute mod 17" in {
      import org.appliedtopology.tda4j.TDAlab.F17.{*, given}
      val f = summon[CoefficientT is Field]
      (f.isZero(f.plus(Fp(16), Fp(1))) must beTrue).and(f.isEqual(f.times(Fp(4), Fp(13)), Fp(1)) must beTrue)
    }
  }

  "the F_2 lab" should {
    "treat 1 and -1 as the same coefficient" in {
      import org.appliedtopology.tda4j.TDAlab.F2.{*, given}
      summon[CoefficientT is Field].isEqual(Fp(1), Fp(-1)) must beTrue
    }
  }

  "import CubicalLab.F3.{*, given}" should {
    "give chain arithmetic on cubes with boundary of boundary zero (F_3: signs matter)" in {
      import org.appliedtopology.tda4j.CubicalLab.F3.{*, given}
      val square: Chain[Cube, CoefficientT] = Cube.unitCube(0, 0)
      val twoSquares = square + Fp(2) ⊠ Cube.unitCube(1, 0)
      val boundary = Chain.from(twoSquares.boundary)
      (boundary.isZero() must beFalse).and(Chain.from(boundary.boundary).isZero() must beTrue)
    }
    "have the companion spellings of the removed lab extensions" in {
      import org.appliedtopology.tda4j.CubicalLab.F3.{*, given}
      Cube.fromVector(Vector(1, 1)) must beEqualTo(Cube.unitCube(0, 0))
    }
  }
