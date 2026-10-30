package org.appliedtopology.tda4j

import Coefficients.Rational

import org.scalacheck.{Arbitrary, Gen}
import org.specs2.execute.AsResult
import SpecThrows.throwWith

import org.specs2.mutable.Specification

/** The exact rational numbers as a `Field`: normal form, the field laws on random fractions with large parts, and what
  * a tolerance or a residue cannot do (tell `1/3 + 1/6 = 1/2` exactly, and `10^30 + 1` from `10^30`).
  */
class RationalSpec extends Specification with PlatformScalaCheck:

  private val field = summon[Rational is Field]

  private val fractions: Gen[Rational] =
    for
      n <- Gen.oneOf(Gen.choose(-50L, 50L), Gen.choose(-(1L << 40), 1L << 40))
      d <- Gen.oneOf(Gen.choose(1L, 50L), Gen.choose(1L, 1L << 40))
    yield Rational(n, d)

  private given Arbitrary[Rational] = Arbitrary(fractions)

  "Rational" should {
    "be in lowest terms with a positive denominator, so equal numbers are equal values" in {
      (Rational(2, 4) must beEqualTo(Rational(1, 2))) and (Rational(-1, -2) must beEqualTo(Rational(1, 2))) and
        (Rational(3, -6) must beEqualTo(Rational(-1, 2))) and (Rational(0, -7) must beEqualTo(Rational.zero)) and
        (Rational(2, 4).hashCode must beEqualTo(Rational(1, 2).hashCode)) and
        (Rational(6, 3).toString must beEqualTo("2")) and (Rational(-1, 2).toString must beEqualTo("-1/2"))
    }

    "reduce a fraction of any signs to a positive denominator, lowest terms and the same value" in {
      val parts = Gen.choose(-(1L << 40), 1L << 40)
      AsResult(
        org.scalacheck.Prop.forAll(parts, parts.suchThat(_ != 0)) { (n, d) =>
          val reduced = Rational(n, d)
          (reduced.denominator.signum > 0) && (reduced.numerator.gcd(reduced.denominator) == 1) &&
          (BigInt(n) * reduced.denominator == reduced.numerator * BigInt(d))
        }
      )
    }

    "add exactly what a Double cannot: 1/3 + 1/6 is 1/2, and 10^30 + 1 is not 10^30" in {
      import field.*
      val big = Rational(BigInt(10).pow(30))
      (Rational(1, 3) + Rational(1, 6) must beEqualTo(Rational(1, 2))) and
        (big + Rational.one must not(beEqualTo(big))) and (big + Rational.one - big must beEqualTo(Rational.one))
    }

    "satisfy the field laws on random fractions" in {
      import field.*
      AsResult(
        prop((x: Rational, y: Rational, z: Rational) =>
          (x + y == y + x) && ((x + y) + z == x + (y + z)) && (x * (y + z) == x * y + x * z) && (x * y == y * x) &&
            (x - x == zero) && (x + zero == x) && (x * one == x)
        )
      )
    }

    "invert every nonzero fraction" in {
      import field.*
      AsResult(prop((x: Rational) => x.isZero || (x * field.invert(x) == one)))
    }

    "hash like it compares: equal numbers, equal hashes" in
      AsResult(prop((x: Rational, y: Rational) =>
        // x and its normal form built another way
        val again = Rational(x.numerator * 3, x.denominator * 3)
        (field.hash(again) == field.hash(x)) && (again == x) && ((x == y) == field.isEqual(x, y))
      ))

    "refuse a zero denominator and division by zero" in {
      (Rational(1, 0) must throwWith[IllegalArgumentException]("denominator 0")) and
        (field.divide(Rational.one, Rational.zero) must throwWith[IllegalArgumentException]("division by zero"))
    }

    "give the nearest Double, also for parts too big for one" in {
      val huge = Rational(BigInt(10).pow(400), BigInt(10).pow(399) * 4)
      (field.toDouble(Rational(1, 4)) must beEqualTo(0.25)) and (field.toDouble(huge) must beCloseTo(2.5, 1e-12))
    }

    "be the field of Coefficients.Rationals, of characteristic 0, with fromInt" in {
      val q = Coefficients.Rationals
      import q.given
      (q.characteristic must beEqualTo(0)) and (q.fromInt(-3) must beEqualTo(Rational(-3, 1))) and
        (summon[q.C is Field].plus(q.fromInt(1), q.fromInt(2)) must beEqualTo(q.fromInt(3)))
    }
  }
