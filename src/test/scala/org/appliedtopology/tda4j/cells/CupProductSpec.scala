package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.groups.{ClassifyingSpace, FiniteGroup}
import org.specs2.mutable.Specification

/** Cup products, against facts that do not depend on the triangulation (and differ between spaces with the same Betti
  * numbers). Each check runs over F_2 or F_3 as indicated; F_3 catches sign errors F_2 hides.
  */
class CupProductSpec extends Specification:
  import SimplicialSetFixtures.*
  import SimplicialSets.*

  /** (number of basis classes of H^1, whether SOME product of two H^1 basis classes is nonzero in H^2). */
  private def h1Cups[G](x: FiniteSimplicialSet[G], p: Int): (Int, Boolean) =
    val field = new FiniteField(p)
    import field.given
    val basis = CupProduct.cohomologyBasis[G, field.Fp](x, 1)
    val nonzero = for a <- basis; b <- basis yield !CupProduct.isCoboundary(x, 2, CupProduct.cup(x, 1, 1, a, b))
    (basis.length, nonzero.exists(identity))

  /** Whether x ∪ x is nonzero for the (unique) degree-1 class x. */
  private def squareNonzero[G](x: FiniteSimplicialSet[G], p: Int): Boolean =
    val field = new FiniteField(p)
    import field.given
    val basis = CupProduct.cohomologyBasis[G, field.Fp](x, 1)
    basis.length == 1 && !CupProduct.isCoboundary(x, 2, CupProduct.cup(x, 1, 1, basis.head, basis.head))

  "Alexander-Whitney" should {
    "split a 2-simplex of the triangle: front edge d_2, back edge d_0" in {
      val t = simplex(2)
      val sigma = Simplex(0, 1, 2)
      val (front, back) = CupProduct.alexanderWhitney(t, sigma, 1)
      (front must beEqualTo(SSetElement(Nil, Simplex(0, 1)))).and(back must beEqualTo(SSetElement(Nil, Simplex(1, 2))))
    }
  }

  "the torus and S^1 v S^1 v S^2 have the same Betti numbers but different cup products" should {
    val circle = minimalSphere(1)
    val twoCircles = wedge(circle, SphereGenerator.Vertex, circle, SphereGenerator.Vertex)
    val wedged = wedge(twoCircles, Left(SphereGenerator.Vertex), minimalSphere(2), SphereGenerator.Vertex)
    "agree on Betti numbers" in {
      (SSetBetti(torus, 2) must beEqualTo(Vector(1, 2, 1))).and(SSetBetti(wedged, 2) must beEqualTo(Vector(1, 2, 1)))
    }
    "disagree on cup products, over F_2 and F_3" in {
      (h1Cups(torus, 2) must beEqualTo((2, true)))
        .and(h1Cups(torus, 3) must beEqualTo((2, true)))
        .and(h1Cups(wedged, 2) must beEqualTo((2, false)))
        .and(h1Cups(wedged, 3) must beEqualTo((2, false)))
    }
  }

  "squares" should {
    "x^2 != 0 for RP^2 over F_2" in { squareNonzero(realProjectiveSpace(2), 2) must beTrue }
    "x^2 != 0 for B(Z/2) over F_2" in { squareNonzero(ClassifyingSpace(FiniteGroup.cyclic(2), 3), 2) must beTrue }
    "x^2 = 0 for the degree-1 class of B(Z/3) over F_3 (odd characteristic: graded commutativity)" in {
      squareNonzero(ClassifyingSpace(FiniteGroup.cyclic(3), 3), 3) must beFalse
    }
  }

  "graded commutativity over F_3" should {
    "give x ∪ y = - y ∪ x in H^2 of the torus" in {
      val field = new FiniteField(3)
      import field.given
      val basis = CupProduct.cohomologyBasis[SimplicialSetFixtures.TorusGenerator, field.Fp](torus, 1)
      val xy = CupProduct.cup(torus, 1, 1, basis(0), basis(1))
      val yx = CupProduct.cup(torus, 1, 1, basis(1), basis(0))
      val sum = (xy.keySet ++ yx.keySet).map(g => g -> (xy.getOrElse(g, field.Fp(0)) + yx.getOrElse(g, field.Fp(0)))).toMap
      CupProduct.isCoboundary(torus, 2, sum) must beTrue
    }
  }
