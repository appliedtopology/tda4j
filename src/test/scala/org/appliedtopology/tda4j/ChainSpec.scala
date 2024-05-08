package org.appliedtopology.tda4j

import org.apache.commons.numbers.fraction.GeneralizedContinuedFraction.Coefficient
import org.specs2.mutable
import org.specs2.execute.Result

import math.Ordering.Implicits.sortedSetOrdering
import scala.math.Numeric.FloatIsFractional

class ChainSpec extends mutable.Specification {
  """This is the specification for testing the Chain implementation.
    |""".stripMargin.txt

  given sc: SimplexContext[Int]()
  import sc.*

  given Conversion[Simplex, Chain[Simplex, Double]] =
    Chain.apply

  given Fractional[Double] = math.Numeric.DoubleIsFractional

  given Ordering[Int] = math.Ordering.Int

  given rm: RingModule[Chain[Simplex, Double], Double] =
    ChainOps[Simplex, Double]()
  import rm.*

  "The `Chain` type should" >> {
    val z1 = Chain(Simplex(1, 2, 3))
    val z2 =
      Chain(
        Simplex(1, 2) -> 1.0,
        Simplex(1, 3) -> -1.0,
        Simplex(2, 3) -> 1.0
      )
    val z3 = Chain(Simplex(1, 2, 5))
    val z4 = Chain(Simplex(1, 4, 8))
    val z5 =
      Chain(
        Simplex(1, 2) -> -1.0,
        Simplex(1, 4) -> 1.0,
        Simplex(2, 3) -> -1.0
      )
    val z6 =
      Chain(
        Simplex(1, 2) -> 1.0,
        Simplex(2, 3) -> 1.0,
        Simplex(1, 3) -> -1.0
      )
    val z7 = Chain(
      Simplex(1, 2) -> 1.0,
      Simplex(1, 3) -> -1.0,
      Simplex(2, 3) -> 1.0,
      Simplex(3, 4) -> 0.0
    )
    // val z6 = Chain(Simplex(1, 2, 6, 7))

    "be the return type of Chain applied to a single simplex" >> {
      z1 must haveClass[Chain[Simplex, Double]]
    }
    "be the return type of Chain applied to several simplex/coefficient pairs" >> {
      z2 must haveClass[Chain[Simplex, Double]]
    }
    "maintain it's equality when its component simplex-coefficient pairs are permuted" >> {
      z2 must beEqualTo(z6)
    }
    "maintain equality to itself after the addition of simplices with 0-valued coefficient " >> {
      z2 must beEqualTo(z7)
    }
    "should be able to 'add', 'subtract', and 'multiply'" >> {

      def is =
        s2"""
       Chain should
         correctly perform scalar multiplication $e1
         correctly perform addition $e2
         correctly perform unary minus $e3
         correctly perform subtraction $e4
       """

      def e1 = {
        val chain = z1
        val expectedResult = Chain(Simplex(1, 2, 3))
        val result = 2 |*| chain

        result must beEqualTo(expectedResult)
      }

      def e2 = {
        val chain1 = z1
        val chain2 = z2
        val chain3 = z5

        val result1 = chain1 + chain2
        val result2 = chain2 + chain3
        val expectedResult1 = Chain(
          Simplex(1, 2, 3) -> 1.0,
          Simplex(1, 2) -> 1.0,
          Simplex(1, 3) -> -1.0,
          Simplex(2, 3) -> 1.0
        )
        val expectedResult2 = Chain(
          Simplex(1, 2) -> 0.0,
          Simplex(1, 3) -> 0.0,
          Simplex(1, 4) -> 0.0,
          Simplex(2, 3) -> 0.0
        )
        // note - check up on expectedResult2 after conferring with prof in github

        result1 must beEqualTo(expectedResult1)
        result2 must beEqualTo(expectedResult2)
      }

      def e3 = {
        val chain = z1
        val expectedResult = Chain(Simplex(1, 2, 3) -> -1.0)
        val result = -chain

        result must beEqualTo(expectedResult)
      }

      def e4 = {
        val chain1 = z1
        val chain2 = z2
        val expectedResult = Chain(
          Simplex(1, 2, 3) -> 1.0,
          Simplex(1, 2) -> -1.0,
          Simplex(1, 3) -> 1.0,
          Simplex(2, 3) -> -1.0
        )
        val result = chain1 - chain2

        result must beEqualTo(expectedResult)
      }
    }

  }

  "The `Chain` type should be comfortable to write expressions with" >> {
    val z1 = Chain(s(1, 2, 3))
    val z2: Chain[Simplex, Double] = s(1, 2) - s(1, 3) + s(2, 3)
    val z3 = 1.0 |*| s(1, 2, 5)
    val z4: Chain[Simplex, Double] = Simplex(1, 4, 8) <* 1.0
    val z5: Chain[Simplex, Double] = -s(1, 2) + s(1, 4) - s(2, 3)
    val z6: Chain[Simplex, Double] = s(1, 2) + s(2, 3) - s(1, 3)
    val z7: Chain[Simplex, Double] =
      s(1, 2) - s(1, 3) + s(2, 3) + (0.0 |*| s(3, 4))

    z1 must haveClass[Chain[Simplex, Double]]
    z2 must beEqualTo(z6)
    z2 must beEqualTo(z7)

    val expectedResult1 = Chain(
      Simplex(1, 2, 3) -> 1.0,
      Simplex(1, 2) -> 1.0,
      Simplex(1, 3) -> -1.0,
      Simplex(2, 3) -> 1.0
    )
    val expectedResult2 = Chain(
      Simplex(1, 2) -> 0.0,
      Simplex(1, 3) -> 0.0,
      Simplex(1, 4) -> 0.0,
      Simplex(2, 3) -> 0.0
    )

    z1 + z2 must beEqualTo(s(1, 2, 3) + s(1, 2) - s(1, 3) + s(2, 3))
    z2 - z6 must beEqualTo(
      summon[RingModule[Chain[Simplex, Double], Double]].zero
    )
  }
}

class HeapChainSpec extends mutable.Specification {
  given sc: SimplexContext[Int]()
  import sc.{given, *}

  "Heap-based chains should" >> {
    "be created from a sequence" >> {
      val elts = List((s(1, 2), 1.0), (s(1, 3), -1.0))
      val hc = Chain.from[Simplex, Double](elts)
      "contains the right things" ==>
        (hc.items.toList must containTheSameElementsAs(elts))
    }
    "be created from varargs" >> {
      val elts = Seq((s(1, 2), 1.0), (s(1, 3), -1.0))
      val hc = Chain[Simplex, Double](elts: _*)
      "contains the right things" ==>
        (hc.items.toList must containTheSameElementsAs(elts))
    }
    "be created from a single simplex" >> {
      val hc = Chain[Simplex, Double](s(1, 2, 3))
      "contains the right things" ==>
        (hc.items.toList must containTheSameElementsAs(
          Seq((s(1, 2, 3), 1.0))
        ))
    }
    "be possible to add together" >> {
      given Conversion[Simplex, Chain[Simplex, Double]] = Chain.apply
      given rm: RingModule[Chain[Simplex, Double], Double] = ChainOps()
      import rm.{given, *}
      val z1: Chain[Simplex, Double] = 1.0 ⊠ s(1, 2) + (s(1, 3).mul(2.0)) - (1.0 |*| s(2, 3)) + (5.2.scale(s(1, 4)))
      val z2: Chain[Simplex, Double] = 1.0 ⊠ s(1, 2) + 1.0 ⊠ s(2, 3)
      val z3: Chain[Simplex, Double] = z2 + (-1.0 ⊠ z2)

      "subtracts to zero" ==>
        (z3.isZero() must beTrue)
    }
  }
}
