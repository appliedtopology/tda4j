package org.appliedtopology.tda4j
package cells

import org.specs2.mutable.Specification

/** `SimplicialSets`: every construction is checked by `validate()` AND against homology derived by hand, over F_2 and
  * F_3 (F_2 alone would hide sign errors).
  */
class SimplicialSetsSpec extends Specification:
  import SimplicialSets.*

  private def betti[G](x: FiniteSimplicialSet[G], p: Int) = SSetBetti(x, p)

  "fixtures" should {
    "Delta^n: valid and acyclic" in {
      (0 to 3).map(n => (simplex(n).validate(), betti(simplex(n), 2))) must beEqualTo(
        (0 to 3).map(n => (Seq.empty[String], Vector(1) ++ Vector.fill(n)(0)))
      )
    }
    "the horn Lambda^3_1 is valid and contractible" in
      (horn(3, 1).validate() must beEmpty).and(betti(horn(3, 1), 2) must beEqualTo(Vector(1, 0, 0)))
    "Klein bottle: valid; (1,2,1) over F_2 but (1,1,0) over F_3" in
      (kleinBottle.validate() must beEmpty)
        .and(betti(kleinBottle, 2) must beEqualTo(Vector(1, 2, 1)))
        .and(betti(kleinBottle, 3) must beEqualTo(Vector(1, 1, 0)))
    "an octahedron from its facets is a 2-sphere" in {
      val triangles = for a <- List(1, 2); b <- List(3, 4); c <- List(5, 6) yield Simplex(a, b, c)
      val oct = fromSimplicialComplex(triangles)
      (fVector(oct) must beEqualTo(Vector(6, 12, 8)))
        .and(oct.validate() must beEmpty)
        .and(betti(oct, 3) must beEqualTo(Vector(1, 0, 1)))
    }
    "the empty set and the point" in
      (empty.generatorsByDim must beEmpty).and(betti(point, 2) must beEqualTo(Vector(1)))
  }

  "subcomplex, connectivity" should {
    "keep a face-closed subset and reject one that is not" in {
      val t = simplex(2)
      val edges = t.generatorsByDim(0) ++ t.generatorsByDim(1)
      (subcomplex(t, edges).generatorsByDim.map(_.size) must beEqualTo(Vector(3, 3)))
        .and(subcomplex(t, Set(Simplex(0, 1))) must throwAn[IllegalArgumentException])
    }
    "isConnected" in
      (isConnected(simplex(2)) must beTrue)
        .and(isConnected(empty) must beFalse)
        .and(isConnected(fromSimplicialComplex(Seq(Simplex(0, 1), Simplex(2, 3)))) must beFalse)
  }

  "cone" should {
    "have the hand-counted generators on the torus, be valid, and be acyclic" in {
      val c = cone(SimplicialSetFixtures.torus)
      // apex + base(1,3,2) + cones(over 1,3,2 gens) per dimension
      (fVector(c) must beEqualTo(Vector(2, 4, 5, 2)))
        .and(c.validate() must beEmpty)
        .and(betti(c, 2) must beEqualTo(Vector(1, 0, 0, 0)))
        .and(betti(c, 3) must beEqualTo(Vector(1, 0, 0, 0)))
    }
  }

  "suspension" should {
    "send S^n to S^(n+1)" in {
      (1 to 2).map { n =>
        val s = suspension(SimplicialSetFixtures.minimalSphere(n))
        (s.validate(), betti(s, 2))
      } must beEqualTo(Seq((Seq.empty[String], Vector(1, 0, 1)), (Seq.empty[String], Vector(1, 0, 0, 1))))
    }
    "shift reduced homology of RP^2: H_2 = H_3 = 1 over F_2, only H_0 over F_3 (a sign test)" in {
      val s = suspension(SimplicialSetFixtures.realProjectiveSpace(2))
      (s.validate() must beEmpty)
        .and(betti(s, 2) must beEqualTo(Vector(1, 0, 1, 1)))
        .and(betti(s, 3) must beEqualTo(Vector(1, 0, 0, 0)))
    }
  }

  "wedge" should {
    "of two circles have H_1 of rank 2" in {
      import SimplicialSetFixtures.*
      val circle = minimalSphere(1)
      val w = wedge(circle, SphereGenerator.Vertex, circle, SphereGenerator.Vertex)
      (w.validate() must beEmpty).and(betti(w, 2) must beEqualTo(Vector(1, 2)))
    }
  }
