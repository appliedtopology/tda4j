package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import org.specs2.mutable.Specification

/** Steenrod squares against Wu's formula on B(Z/2) = RP^infinity, `Sq^i(x^k) = C(k, i) x^(k+i)`, which uses cup-i for
  * every `i`, and on CP^2 and RP^2.
  */
class SteenrodSpec extends Specification:
  private val f2 = new FiniteField(2)
  import f2.given

  private def binomialMod2(k: Int, i: Int): Int =
    if i < 0 || i > k then 0
    else
      (BigInt(k).toLong.toInt, i) match
        case (kk, ii) =>
          (BigInt(1) to BigInt(ii)).foldLeft(BigInt(1))((acc, j) => acc * (kk - ii + j.toInt) / j).mod(2).toInt

  "Steenrod squares on B(Z/2)" should {
    // B(Z/2) has one non-degenerate simplex per dimension, so dimension 9 is trivially cheap; the skeleton is exact below its top.
    val bz2 = ClassifyingSpace(FiniteGroup.cyclic(2), 8)

    "satisfy Wu's formula: Sq^i(x^k) is cohomologous to C(k, i) x^(k+i)" in {
      val x = CupProduct.cohomologyBasis[NerveSimplex, f2.Fp](bz2, 1).head
      val powers: Vector[Map[NerveSimplex, f2.Fp]] =
        (1 to 8).scanLeft(x)((acc, k) => CupProduct.cup(bz2, k, 1, acc, x)).toVector.take(8)
      val failures = for
        k <- 1 to 4
        i <- 0 to k
        lhs = Steenrod.sq(bz2, i, k, powers(k - 1))
        rhs = if binomialMod2(k, i) == 1 then powers(k + i - 1) else Map.empty[NerveSimplex, f2.Fp]
        diff = (lhs.keySet ++ rhs.keySet).map(g => g -> (lhs.getOrElse(g, f2.Fp(0)) + rhs.getOrElse(g, f2.Fp(0)))).toMap
        if k + i <= 7 && !CupProduct.isCoboundary(bz2, k + i, diff)
      yield (k, i)
      failures must beEmpty
    }
    "make the Wu check non-vacuous: x^2 is a nonzero class, and Sq^1 of x^3 is x^4 (nonzero) while Sq^1 of x^2 is zero" in {
      val x = CupProduct.cohomologyBasis[NerveSimplex, f2.Fp](bz2, 1).head
      val x2 = CupProduct.cup(bz2, 1, 1, x, x)
      val x3 = CupProduct.cup(bz2, 2, 1, x2, x)
      (CupProduct.isCoboundary(bz2, 2, x2) must beFalse)
        .and(CupProduct.isCoboundary(bz2, 3, Steenrod.sq(bz2, 1, 2, x2)) must beTrue)
        .and(CupProduct.isCoboundary(bz2, 4, Steenrod.sq(bz2, 1, 3, x3)) must beFalse)
    }
  }

  "Steenrod squares elsewhere" should {
    "Sq^2 on H^2(CP^2) is the cup square, nonzero; Sq^1 on it vanishes" in {
      val cp2 = SimplicialSet.complexProjectivePlaneKuhnel
      val x = CupProduct.cohomologyBasis[Simplex[Int], f2.Fp](cp2, 2).head
      (CupProduct.isCoboundary(cp2, 4, Steenrod.sq(cp2, 2, 2, x)) must beFalse)
        .and(CupProduct.isCoboundary(cp2, 3, Steenrod.sq(cp2, 1, 2, x)) must beTrue)
    }
    "Sq^1 on H^1(RP^2) is nonzero (x^2 != 0)" in {
      val rp2 = SimplicialSet.realProjectiveSpace(2)
      val x = CupProduct.cohomologyBasis[RealProjectiveGenerator, f2.Fp](rp2, 1).head
      CupProduct.isCoboundary(rp2, 2, Steenrod.sq(rp2, 1, 1, x)) must beFalse
    }
    "reject odd characteristic" in {
      val f3 = new FiniteField(3)
      import f3.given
      val rp2 = SimplicialSet.realProjectiveSpace(2)
      Steenrod.cupI(rp2, 0, 1, 1, Map.empty[RealProjectiveGenerator, f3.Fp], Map.empty) must throwAn[
        IllegalArgumentException
      ]
    }
  }
