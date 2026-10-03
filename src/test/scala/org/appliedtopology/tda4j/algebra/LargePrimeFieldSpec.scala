package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** `FiniteField(p)` must be exact for primes well past 46341 (= ceil(sqrt(Int.MaxValue))), where an Int product of two
  * residues overflows. Inputs are deliberately NOT normalized (`Fp(a)` keeps `a % p`, up to p - 1 in absolute value),
  * which is what `divide` fed `times` through the inverse table.
  */
class LargePrimeFieldSpec extends Specification:
  private def check(p: Int): Boolean =
    val ff = FiniteField(p)
    import ff.given
    val fld = summon[ff.Fp is Field]
    val rnd = new scala.util.Random(p)
    val samples = Seq(p - 1, p - 2, -(p - 1), (p - 1) / 2, -(p - 1) / 2, 1, -1) ++ Seq.fill(200)(rnd.nextInt(2 * p - 1) - (p - 1))
    def residue(x: ff.Fp): BigInt = BigInt(ff.toUInt(x))
    samples.forall { a =>
      samples.take(40).forall { b =>
        val (x, y) = (ff.Fp(a), ff.Fp(b))
        val productOk = residue(fld.times(x, y)) == (BigInt(a) * BigInt(b)).mod(p)
        val quotientOk = b % p == 0 || fld.isEqual(fld.times(fld.divide(x, y), y), x)
        productOk && quotientOk
      }
    }

  "FiniteField arithmetic" should {
    "be exact at p = 65537 (products of residues exceed Int.MaxValue)" in { check(65537) must beTrue }
    "be exact at p = 46349, just past the overflow threshold" in { check(46349) must beTrue }
    "still be exact at the default prime 17 and at 2" in { (check(17) must beTrue).and(check(2) must beTrue) }
  }

  "a homology computation over a large prime" should {
    "agree with F_17 on a torsion-free space (the circle's Betti numbers)" in {
      def betti(p: Int) =
        val ff = FiniteField(p)
        import ff.given
        val pts = Array.tabulate(12)(i => Array(math.cos(i * math.Pi / 6), math.sin(i * math.Pi / 6)))
        SimplicialHomologyEngine[Int, ff.Fp, Double]()
          .persistentHomology(VietorisRips(EuclideanMetricSpace(pts), 1, Some(2.0)))
          .diagramAt(Double.PositiveInfinity)
          .filter((d, b, e) => d <= 1 && e - b > 0.1)
          .groupBy(_._1).view.mapValues(_.size).toMap
      betti(65537) must beEqualTo(betti(17))
    }
  }

  "F_2" should {
    "have a stable normal form, so 1 and -1 are equal (they were not: norm oscillated 1 -> -1 -> 1)" in {
      val f2 = FiniteField(2)
      import f2.given
      val fld = summon[f2.Fp is Field]
      (fld.isEqual(f2.Fp(1), f2.Fp(-1)) must beTrue)
        .and(f2.norm(f2.norm(f2.Fp(1))) must beEqualTo(f2.norm(f2.Fp(1))))
    }
  }

