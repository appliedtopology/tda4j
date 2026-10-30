package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.Result

import math.Ordering.Implicits.sortedSetOrdering
import scala.math.Numeric.FloatIsFractional

import scala.math.Fractional.Implicits.infixFractionalOps

class ChainSpec extends mutable.Specification:
  """This is the specification for testing the Chain implementation.
    |""".stripMargin.txt

  given Double is Field = Field.DoubleApproximated(1e-25)

  given Conversion[Simplex[Int], Chain[Simplex[Int], Double]] =
    Chain.apply

  given Ordering[Int] = math.Ordering.Int

  val rm = summon[RingModule { type Self = Chain[Simplex[Int], Double]; type R = Double }]
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
      z1 must beAnInstanceOf[Chain[Simplex[Int], Double]]
    }
    "be the return type of Chain applied to several simplex/coefficient pairs" >> {
      z2 must beAnInstanceOf[Chain[Simplex[Int], Double]]
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

      def e1 =
        val chain = z1
        val expectedResult = Chain(Simplex(1, 2, 3))
        val result = 2 |*| chain

        result must beEqualTo(expectedResult)

      def e2 =
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

      def e3 =
        val chain = z1
        val expectedResult: Chain[Simplex[Int], Double] = Chain(Simplex(1, 2, 3) -> -1.0)
        val result = -chain

        result must beEqualTo(expectedResult)

      def e4 =
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

  // Equality is that of formal sums: blind to the order the chains are kept in, and comparing coefficients by the
  // field's own equality (an F_p element has several Int forms; reals are compared within the field's tolerance).
  "Chain equality is equality of formal sums" >> {
    val f3 = FiniteField(3)
    import f3.given
    val fp = summon[f3.Fp is Field]
    val terms = Seq((1, f3.Fp(1)), (4, f3.Fp(2)), (7, f3.Fp(1)))
    val up = Chain.from[Int, f3.Fp](terms)(using Ordering.Int, fp)
    val down = Chain.from[Int, f3.Fp](terms)(using Ordering.Int.reverse, fp)
    // 1 + 1 is the element Fp(2), normalised to another Int form.
    val summed = Chain.from[Int, f3.Fp](Seq((1, f3.Fp(1)), (4, f3.Fp(1)), (4, f3.Fp(1)), (7, f3.Fp(1))))(using
      Ordering.Int,
      fp
    )
    val cancelled = Chain.from[Int, f3.Fp](terms ++ Seq((9, f3.Fp(1)), (9, f3.Fp(2))))(using Ordering.Int, fp)
    val shorter = Chain.from[Int, f3.Fp](terms.take(2))(using Ordering.Int, fp)
    val reals = Field.DoubleApproximated(1e-9)
    def real(terms: (Int, Double)*) = Chain.from[Int, Double](terms)(using Ordering.Int, reals)
    (up must beEqualTo(down)) and (down must beEqualTo(up)) and
      (summed must beEqualTo(up)) and (up must beEqualTo(summed)) and
      (cancelled must beEqualTo(up)) and
      (shorter must not(beEqualTo(up))) and (up must not(beEqualTo(shorter))) and
      (real((1, 1.0), (2, 0.5)) must beEqualTo(real((1, 1.0 + 1e-12), (2, 0.5)))) and
      (real((1, 1.0), (2, 0.5)) must not(beEqualTo(real((1, 1.1), (2, 0.5)))))
  }

  // Equal chains hash alike, so chains are keys of a Set or Map: whatever the storage, the order the chain is kept in, the
  // deferred entries it holds, or the Int form of a coefficient (Fp(-2) and Fp(1) are the same element of F_3).
  "Chain hashing agrees with equality" >> {
    object IntCells extends CellDecoder[Int]:
      def apply(key: Int): Int = key
    val f3 = FiniteField(3)
    import f3.given
    val fp = summon[f3.Fp is Field]
    type C3 = Chain[Int, f3.Fp]
    def heap(terms: Seq[(Int, f3.Fp)], order: Ordering[Int] = Ordering.Int): C3 =
      Chain.from[Int, f3.Fp](terms)(using order, fp)
    def packed(terms: Seq[(Int, f3.Fp)]): C3 =
      val sorted = terms.sortBy(_._1)
      Chain.packed[Int, f3.Fp](sorted.map(_._1).toArray, sorted.map(_._2.asInstanceOf[AnyRef]).toArray, IntCells)(using
        Ordering.Int,
        fp
      )
    val terms = Seq((1, f3.Fp(1)), (4, f3.Fp(2)), (7, f3.Fp(1)))
    // The same elements written another way: 1 = -2 and 2 = -1 in F_3.
    val respelled = Seq((1, f3.Fp(-2)), (4, f3.Fp(-1)), (7, f3.Fp(-2)))
    val equalChains: Seq[(String, C3)] = Seq(
      "heap" -> heap(terms),
      "heap in the reverse order" -> heap(terms, Ordering.Int.reverse),
      "heap with coefficients respelled" -> heap(respelled),
      "packed" -> packed(terms),
      "packed with coefficients respelled" -> packed(respelled),
      // Deferred arithmetic: 2 as 1 + 1, and a cell that cancels (1 + 2 = 0).
      "heap with deferred entries" -> heap(
        Seq((1, f3.Fp(1)), (4, f3.Fp(1)), (4, f3.Fp(1)), (7, f3.Fp(1)), (9, f3.Fp(1)), (9, f3.Fp(2)))
      )
    )
    val up = equalChains.head._2

    "the premise: respelled coefficients are the same element, written differently" >> {
      (fp.isEqual(f3.Fp(1), f3.Fp(-2)) must beTrue) and
        ((f3.Fp(1): Any) must not(beEqualTo(f3.Fp(-2): Any)))
    }
    "equal chains have equal hash codes, in every storage and spelling" >> {
      equalChains.collect { case (name, c) if c != up || c.## != up.## => name } must beEmpty
    }
    "equal chains collapse to one element of a Set, and find each other as keys of a Map" >> {
      (equalChains.map(_._2).toSet.size must beEqualTo(1)) and
        (equalChains.map(_._2).toSet.contains(heap(respelled, Ordering.Int.reverse)) must beTrue) and
        (Map(up -> "found").get(packed(respelled)) must beSome("found"))
    }
    "chains that differ in a coefficient, a cell or a term hash differently here" >> {
      // Not a requirement of the contract, which only constrains equal chains: pins that F_p coefficients are hashed.
      val differentCoefficient = heap(Seq((1, f3.Fp(2)), (4, f3.Fp(2)), (7, f3.Fp(1))))
      val differentCell = heap(Seq((1, f3.Fp(1)), (5, f3.Fp(2)), (7, f3.Fp(1))))
      val shorter = heap(terms.take(2))
      Seq(differentCoefficient, differentCell, shorter).map(c => (c == up, c.## == up.##)) must
        beEqualTo(Seq((false, false), (false, false), (false, false)))
    }
    "a chain that sums to zero hashes as the empty chain" >> {
      heap(Seq((3, f3.Fp(1)), (3, f3.Fp(2)))).## must beEqualTo(Chain.empty[Int, f3.Fp].##)
    }
    "over the reals, chains equal within the tolerance hash alike (by their cells)" >> {
      val reals = Field.DoubleApproximated(1e-9)
      def real(terms: (Int, Double)*) = Chain.from[Int, Double](terms)(using Ordering.Int, reals)
      val one = real((1, 1.0), (2, 0.5))
      val near = real((1, 1.0 + 1e-12), (2, 0.5))
      // A term below the tolerance is a zero term, which `terms` drops.
      val withNoise = real((1, 1.0), (2, 0.5), (3, 1e-12))
      (Seq(near, withNoise).map(c => (c == one, c.## == one.##)) must beEqualTo(Seq((true, true), (true, true)))) and
        (Set(one, near, withNoise).size must beEqualTo(1))
    }
  }

  "The `Chain` type should be comfortable to write expressions with" >> {
    val z1 = Chain(∆(1, 2, 3))
    val z2: Chain[Simplex[Int], Double] = ∆(1, 2) - ∆(1, 3) + ∆(2, 3)
    val z3 = 1.0 |*| ∆(1, 2, 5)
    val z4: Chain[Simplex[Int], Double] = Simplex(1, 4, 8) <* 1.0
    val z5: Chain[Simplex[Int], Double] = - ∆(1, 2) + ∆(1, 4) - ∆(2, 3)
    val z6: Chain[Simplex[Int], Double] = ∆(1, 2) + ∆(2, 3) - ∆(1, 3)
    val z7: Chain[Simplex[Int], Double] =
      ∆(1, 2) - ∆(1, 3) + ∆(2, 3) + (0.0 |*| ∆(3, 4))

    z1 must beAnInstanceOf[Chain[Simplex[Int], Double]]
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

    z1 + z2 must beEqualTo(∆(1, 2, 3) + ∆(1, 2) - ∆(1, 3) + ∆(2, 3))
    z2 - z6 must beEqualTo(
      summon[RingModule { type Self = Chain[Simplex[Int], Double]; type R = Double }].zero
    )
  }

class HeapChainSpec extends mutable.Specification:
  given Double is Field = Field.DoubleApproximated(1e-25)

  "Heap-based chains should" >> {
    "be created from a sequence" >> {
      val elts = List((∆(1, 2), 1.0), (∆(1, 3), -1.0))
      val hc = Chain.from[Simplex[Int], Double](elts)
      "contains the right things" ==>
        (hc.rawEntries.toList must containTheSameElementsAs(elts))
    }
    "be created from varargs" >> {
      val elts = Seq((∆(1, 2), 1.0), (∆(1, 3), -1.0))
      val hc = Chain[Simplex[Int], Double](elts*)
      "contains the right things" ==>
        (hc.rawEntries.toList must containTheSameElementsAs(elts))
    }
    "be created from a single simplex" >> {
      val hc = Chain[Simplex[Int], Double](∆(1, 2, 3))
      "contains the right things" ==>
        (hc.rawEntries.toList must containTheSameElementsAs(
          Seq((∆(1, 2, 3), 1.0))
        ))
    }
    "be possible to add together" >> {
      given Conversion[Simplex[Int], Chain[Simplex[Int], Double]] = Chain.apply
      val rm = summon[RingModule { type Self = Chain[Simplex[Int], Double]; type R = Double }]
      import rm.{given, *}
      val z1: Chain[Simplex[Int], Double] =
        1.0 ⊠ ∆(1, 2) + (∆(1, 3).mul(2.0)) - (1.0 |*| ∆(2, 3)) + (5.2.scalarMultiply(∆(1, 4)))
      val z2: Chain[Simplex[Int], Double] = 1.0 ⊠ ∆(1, 2) + 1.0 ⊠ ∆(2, 3)
      val z3: Chain[Simplex[Int], Double] = z2 + (-1.0 ⊠ z2)

      "subtracts to zero" ==>
        (z3.isZero() must beTrue)
    }
  }
