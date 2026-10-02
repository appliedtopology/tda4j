package org.appliedtopology.tda4j
package cells

import org.specs2.mutable.Specification

/** Presentation complexes against the spaces the presentations are known to describe. */
class PresentationComplexSpec extends Specification:
  import SimplicialSets.*

  private def a = (0, 1)
  private def b = (1, 1)
  private def aInv = (0, -1)
  private def bInv = (1, -1)

  private def betti(p: Seq[List[(Int, Int)]], n: Int, prime: Int) =
    val x = presentationComplex(n, p)
    (x.validate(), SSetBetti(x, prime))

  "presentation complexes" should {
    "of <a | a> be a disk (contractible)" in {
      betti(Seq(List(a)), 1, 2) must beEqualTo((Seq.empty[String], Vector(1, 0, 0)))
    }
    "of <a, b | > be a wedge of two circles" in {
      betti(Seq.empty, 2, 3)._2 must beEqualTo(Vector(1, 2, 0))
    }
    "of <a | a^2> (relator a a) be RP^2: (1,1,1) over F_2, (1,0,0) over F_3" in
      (betti(Seq(List(a, a)), 1, 2) must beEqualTo((Seq.empty[String], Vector(1, 1, 1))))
        .and(betti(Seq(List(a, a)), 1, 3)._2 must beEqualTo(Vector(1, 0, 0)))
    "of <a, b | a b a^-1 b^-1> be the torus: (1,2,1) over every field, with a nonzero cup product" in {
      val commutator = List(a, b, aInv, bInv)
      val x = presentationComplex(2, Seq(commutator))
      val field = new org.appliedtopology.tda4j.algebra.FiniteField(3)
      import field.given
      val basis = CupProduct.cohomologyBasis[PresentationCell, field.Fp](x, 1)
      (x.validate() must beEmpty)
        .and(SSetBetti(x, 2) must beEqualTo(Vector(1, 2, 1)))
        .and(SSetBetti(x, 3) must beEqualTo(Vector(1, 2, 1)))
        .and(basis.length must beEqualTo(2))
        .and(CupProduct.isCoboundary(x, 2, CupProduct.cup(x, 1, 1, basis(0), basis(1))) must beFalse)
    }
    "of <a, b | a b a b^-1> be the Klein bottle: (1,2,1) over F_2, (1,1,0) over F_3" in {
      val klein = List(a, b, a, bInv)
      (betti(Seq(klein), 2, 2) must beEqualTo((Seq.empty[String], Vector(1, 2, 1))))
        .and(betti(Seq(klein), 2, 3)._2 must beEqualTo(Vector(1, 1, 0)))
    }
    "round-trip with FundamentalGroup.presentation (Hurewicz) on <a, b | a^3, b^2, a b a^-1 b^-1>" in {
      val relations = Seq(List(a, a, a), List(b, b), List(a, b, aInv, bInv))
      val x = presentationComplex(2, relations)
      val back = FundamentalGroup.presentation(x)
      Seq(2, 3).map(p => (back.abelianRank(p), SSetBetti(x, p)(1))).forall((r, h) => r == h) must beTrue
    }
    "reject a letter outside the generators" in {
      presentationComplex(1, Seq(List((3, 1)))) must throwAn[IllegalArgumentException]
    }
  }
