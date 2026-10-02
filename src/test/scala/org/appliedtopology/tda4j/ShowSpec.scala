package org.appliedtopology.tda4j

import org.specs2.mutable
import cats.syntax.all.{*, given}
import cats.Show.{*, given}
import org.appliedtopology.tda4j.algebra.{Chain, Field, FiniteField, given}
import org.appliedtopology.tda4j.cells.{*, given}

/** Introducing `Show` typeclasses from Cats. This class tests their implementations.
  */
class ShowSpec extends mutable.Specification:
  "Show typeclasses" >> {
    "Simplex" should {
      "show its vertices" in {
        val simplex = Simplex(1, 2, 3)
        (simplex.show) must beEqualTo("∆(1,2,3)")
      }
    }

    "Double" should {
      "show its value" in {
        val double = 1.0
        (double.show) must beEqualTo("1.0")
      }
    }

//    "Finite Fields" should {
//      "show their values" in {
//        val ff = FiniteField(2)
//        import ff.{*,given}
//        import org.appliedtopology.tda4j.algebra.Field.given
//        Seq(Fp(0).show, Fp(1).show, Fp(5).show) must beEqualTo(Seq("Fp(0)", "Fp(1)", "Fp(1)"))
//      }
//      "show signs when appropriate" in {
//        val ff = FiniteField(7)
//        import ff.{*,given}
//        import org.appliedtopology.tda4j.algebra.Field.given
//        Seq(Fp(0).show, Fp(1).show, Fp(2).show, Fp(-1).show, Fp(6).show) must beEqualTo(Seq("Fp(0)", "Fp(1)", "Fp(2)", "Fp(-1)", "Fp(-1)")
//      }
//    }

    "Chain" should {
      "show its entries" in {
        given Double is Field = Field.DoubleApproximated(1e-9)
        given Ordering[Simplex[Int]] = simplexOrdering[Int]
        val chain = Chain(Simplex(1, 2, 3) -> 1.0, Simplex(4, 5, 6) -> 2.0)
        (chain.show) must beEqualTo("1.0 ⊠ ∆(1,2,3) + 2.0 ⊠ ∆(4,5,6)")
      }
    }
  }
