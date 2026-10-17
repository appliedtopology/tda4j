package org.appliedtopology.tda4j

import org.specs2.mutable

/** The error function. The reference values are the correctly rounded (to within one unit in the last place) values of
  * the C library's `erf` and `erfc`, one or more in every branch of the approximation.
  */
class ErfSpec extends mutable.Specification:

  // x and erf(x), spanning |x| <= 0.46875, 0.46875 < |x| <= 4, and |x| > 4.
  private val erfTable: Seq[(Double, Double)] = Seq(
    (1e-300, 1.1283791670955126e-300),
    (1e-10, 1.1283791670955126e-10),
    (0.01, 0.011283415555849616),
    (0.1, 0.1124629160182849),
    (0.25, 0.2763263901682369),
    (0.46875, 0.49261347321793797),
    (0.4687500001, 0.4926134733085173),
    (0.5, 0.5204998778130465),
    (0.6, 0.6038560908479259),
    (0.75, 0.7111556336535151),
    (1.0, 0.8427007929497149),
    (1.25, 0.9229001282564582),
    (1.5, 0.9661051464753108),
    (2.0, 0.9953222650189527),
    (2.5, 0.999593047982555),
    (3.0, 0.9999779095030014),
    (3.5, 0.9999992569016276),
    (3.9999999, 0.9999999845827294),
    (4.0, 0.9999999845827421),
    (4.0000001, 0.9999999845827549),
    (4.5, 0.9999999998033839),
    (5.0, 0.9999999999984626),
    (5.5, 0.9999999999999927),
    (5.9, 0.9999999999999999)
  )

  // y and erfc(y): the tail, where erf(y) = 1 - erfc(y) has lost everything and erfc has to be right in relative terms.
  private val erfcTable: Seq[(Double, Double)] = Seq(
    (0.5, 0.4795001221869535),
    (1.0, 0.15729920705028513),
    (2.0, 0.004677734981047265),
    (3.0, 2.2090496998585438e-05),
    (4.0, 1.541725790028002e-08),
    (5.0, 1.5374597944280351e-12),
    (6.0, 2.1519736712498916e-17),
    (8.0, 1.1224297172982928e-29),
    (10.0, 2.088487583762545e-45),
    (15.0, 7.212994172451206e-100),
    (20.0, 5.3958656116079005e-176),
    (26.0, 5.663192408856143e-296)
  )

  "erf" should {
    "be 0 at 0, and about 2x/sqrt(pi) near it, to relative accuracy" in {
      Erf.erf(0.0) must beEqualTo(0.0)
      for x <- Seq(1e-300, 1e-100, 1e-20, 1e-10) do
        math.abs(Erf.erf(x) / (2.0 * x / math.sqrt(math.Pi)) - 1.0) must beLessThan(1e-15)
      ok
    }
    "equal 0.8427007929497149 at 1" in {
      math.abs(Erf.erf(1.0) - 0.8427007929497149) must beLessThanOrEqualTo(2.3e-16)
    }
    "match the reference values to 4e-16 absolute (3.3e-16 was measured), in every branch" in {
      val worst = erfTable.map((x, expected) => math.abs(Erf.erf(x) - expected)).max
      worst must beLessThan(4e-16)
      // and relative to the value for the small arguments, where it is the relative error that matters
      erfTable.filter(_._1 < 0.1).map((x, expected) => math.abs(Erf.erf(x) / expected - 1.0)).max must beLessThan(4e-16)
    }
    "be odd: erf(-x) = -erf(x) exactly" in {
      val xs = erfTable.map(_._1) ++ Seq(0.3, 0.7, 1.7, 2.9, 4.2, 5.1, 7.0, 100.0)
      xs.filter(x => Erf.erf(-x) != -Erf.erf(x)) must beEmpty
    }
    "tend to +-1, and be exactly 1 from the point where 1 - erf(x) drops below half an ulp" in {
      Erf.erf(Double.PositiveInfinity) must beEqualTo(1.0)
      Erf.erf(Double.NegativeInfinity) must beEqualTo(-1.0)
      Erf.erf(6.0) must beEqualTo(1.0)
      Erf.erf(-6.0) must beEqualTo(-1.0)
      Erf.erf(30.0) must beEqualTo(1.0)
      Erf.erf(-1e10) must beEqualTo(-1.0)
      Erf.erf(Double.MaxValue) must beEqualTo(1.0)
    }
    "never leave [-1, 1], and be nondecreasing where its slope exceeds the rounding, on a fine grid" in {
      val inRange = (-8000 to 8000).map(_ / 1000.0).map(Erf.erf)
      inRange.forall(v => v >= -1.0 && v <= 1.0) must beTrue
      // Beyond |x| = 4.5 the slope is below 1e-9 and the step below the spacing of the doubles near 1.
      val values = (-4500 to 4500).map(_ / 1000.0).map(Erf.erf)
      values.zip(values.drop(1)).forall((a, b) => a <= b) must beTrue
    }
    "be continuous across the branch points 0.46875, 4 and 6" in {
      for edge <- Seq(0.46875, 4.0, 6.0) do
        val gap = math.abs(Erf.erf(edge + 1e-9) - Erf.erf(edge - 1e-9))
        gap must beLessThan(1e-8)
      ok
    }
    "give NaN for NaN" in {
      Erf.erf(Double.NaN).isNaN must beTrue
      Erf.erfc(Double.NaN).isNaN must beTrue
    }
    "agree with the persistence image's normal CDF: Phi(0) = 1/2, Phi(1) = 0.8413447460685429" in {
      0.5 * (1.0 + Erf.erf(0.0 / math.sqrt(2.0))) must beEqualTo(0.5)
      math.abs(0.5 * (1.0 + Erf.erf(1.0 / math.sqrt(2.0))) - 0.8413447460685429) must beLessThan(2e-16)
    }
  }

  "erfc" should {
    "match the reference values to a relative 2e-15 (9e-16 was measured), far into the tail" in {
      val worst = erfcTable.map((y, expected) => math.abs(Erf.erfc(y) / expected - 1.0)).max
      worst must beLessThan(2e-15)
    }
    "reflect: erfc(-x) = 2 - erfc(x)" in {
      math.abs(Erf.erfc(-1.0) - 1.842700792949715) must beLessThan(3e-16)
      math.abs(Erf.erfc(-3.0) - 1.9999779095030015) must beLessThan(3e-16)
      Erf.erfc(-6.0) must beEqualTo(2.0)
      Erf.erfc(0.0) must beEqualTo(1.0)
    }
    "underflow to 0 for large arguments, and tend to 0 and 2 at the infinities" in {
      Erf.erfc(30.0) must beEqualTo(0.0)
      Erf.erfc(Double.PositiveInfinity) must beEqualTo(0.0)
      Erf.erfc(Double.NegativeInfinity) must beEqualTo(2.0)
    }
    "complete erf: erf(x) + erfc(x) = 1 up to rounding, for |x| <= 4" in {
      val xs = (-4000 to 4000 by 7).map(_ / 1000.0)
      xs.map(x => math.abs(Erf.erf(x) + Erf.erfc(x) - 1.0)).max must beLessThan(5e-16)
    }
  }
