package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import org.specs2.mutable.Specification

/** The `SimplicialSet` catalog and the construction methods: every construction is checked by `validate()` AND against
  * homology derived by hand, over F_2 and F_3 (F_2 alone would hide sign errors).
  */
class SimplicialSetsSpec extends Specification:
  import SimplicialSet.*

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
      (oct.fVector must beEqualTo(Vector(6, 12, 8)))
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
      (t.subcomplex(edges).generatorsByDim.map(_.size) must beEqualTo(Vector(3, 3)))
        .and(t.subcomplex(Set(Simplex(0, 1))) must throwAn[IllegalArgumentException])
    }
    "isConnected" in
      (simplex(2).isConnected must beTrue)
        .and(empty.isConnected must beFalse)
        .and(fromSimplicialComplex(Seq(Simplex(0, 1), Simplex(2, 3))).isConnected must beFalse)
  }

  "cone" should {
    "have the hand-counted generators on the torus, be valid, and be acyclic" in {
      val c = SimplicialSet.torus.cone
      // apex + base(1,3,2) + cones(over 1,3,2 gens) per dimension
      (c.fVector must beEqualTo(Vector(2, 4, 5, 2)))
        .and(c.validate() must beEmpty)
        .and(betti(c, 2) must beEqualTo(Vector(1, 0, 0, 0)))
        .and(betti(c, 3) must beEqualTo(Vector(1, 0, 0, 0)))
    }
  }

  "suspension" should {
    "send S^n to S^(n+1)" in {
      (1 to 2).map { n =>
        val s = SimplicialSetFixtures.minimalSphere(n).suspension
        (s.validate(), betti(s, 2))
      } must beEqualTo(Seq((Seq.empty[String], Vector(1, 0, 1)), (Seq.empty[String], Vector(1, 0, 0, 1))))
    }
    "shift reduced homology of RP^2: H_2 = H_3 = 1 over F_2, only H_0 over F_3 (a sign test)" in {
      val s = SimplicialSet.realProjectiveSpace(2).suspension
      (s.validate() must beEmpty)
        .and(betti(s, 2) must beEqualTo(Vector(1, 0, 1, 1)))
        .and(betti(s, 3) must beEqualTo(Vector(1, 0, 0, 0)))
    }
  }

  "wedge" should {
    "of two circles have H_1 of rank 2" in {
      import SimplicialSetFixtures.*
      val circle = minimalSphere(1)
      val w = circle.wedge(SphereGenerator.Vertex, circle, SphereGenerator.Vertex)
      (w.validate() must beEmpty).and(betti(w, 2) must beEqualTo(Vector(1, 2)))
    }
  }

  "smash" should {
    import SimplicialSetFixtures.*
    val v = SphereGenerator.Vertex
    "send S^p ^ S^q to a space with the homology of S^(p+q), valid, over F_2 and F_3" in {
      val cases = Seq((1, 1), (1, 2), (2, 2))
      cases.map { (p, q) =>
        val s = minimalSphere(p).smash(v, minimalSphere(q), v)
        (s.validate(), betti(s, 2), betti(s, 3))
      } must beEqualTo(cases.map { (p, q) =>
        val expected = Vector(1) ++ Vector.fill(p + q - 1)(0) ++ Vector(1)
        (Seq.empty[String], expected, expected)
      })
    }
    "have X ^ S^0 with the homology of X (RP^2 is not trivial, so this discriminates)" in {
      val s0 = fromSimplicialComplex(Seq(Simplex(0), Simplex(1)))
      val rp2 = realProjectiveSpace(2)
      val s = rp2.smash(RealProjectiveGenerator.E(0), s0, Simplex(0))
      (s.validate() must beEmpty)
        .and(betti(s, 2) must beEqualTo(betti(rp2, 2)))
        .and(betti(s, 3) must beEqualTo(betti(rp2, 3)))
    }
    "RP^2 ^ S^1 matches the suspension of RP^2: (1,0,1,1) over F_2, (1,0,0,0) over F_3" in {
      val s = realProjectiveSpace(2).smash(RealProjectiveGenerator.E(0), minimalSphere(1), v)
      (s.validate() must beEmpty)
        .and(betti(s, 2) must beEqualTo(Vector(1, 0, 1, 1)))
        .and(betti(s, 3) must beEqualTo(Vector(1, 0, 0, 0)))
    }
  }

  "join" should {
    import SimplicialSetFixtures.*
    "of two simplices be the simplex on all vertices: Delta^1 * Delta^1 has the f-vector of Delta^3" in {
      val j = simplex(1).join(simplex(1))
      (j.fVector must beEqualTo(simplex(3).fVector))
        .and(j.validate() must beEmpty)
        .and(betti(j, 2) must beEqualTo(Vector(1, 0, 0, 0)))
    }
    "pt * X is a cone: contractible even for the torus" in {
      val j = point.join(torus)
      (j.validate() must beEmpty)
        .and(betti(j, 2) must beEqualTo(Vector(1, 0, 0, 0)))
        .and(betti(j, 3) must beEqualTo(Vector(1, 0, 0, 0)))
    }
    "S^0 * RP^2 is its suspension: (1,0,1,1) over F_2, (1,0,0,0) over F_3" in {
      val s0 = fromSimplicialComplex(Seq(Simplex(0), Simplex(1)))
      val j = s0.join(realProjectiveSpace(2))
      (j.validate() must beEmpty)
        .and(betti(j, 2) must beEqualTo(Vector(1, 0, 1, 1)))
        .and(betti(j, 3) must beEqualTo(Vector(1, 0, 0, 0)))
    }
    "S^1 * S^1 has the homology of S^3, and S^1 * S^2 that of S^4" in {
      val a = minimalSphere(1).join(minimalSphere(1))
      val b = minimalSphere(1).join(minimalSphere(2))
      (a.validate() must beEmpty)
        .and(betti(a, 2) must beEqualTo(Vector(1, 0, 0, 1)))
        .and(b.validate() must beEmpty)
        .and(betti(b, 3) must beEqualTo(Vector(1, 0, 0, 0, 1)))
    }
  }

  "the complex projective plane" should {
    def squareOfH2Nonzero[G](x: FiniteSimplicialSet[G], p: Int): Boolean =
      val field = new org.appliedtopology.tda4j.FiniteField(p)
      import field.given
      val basis = CupProduct.cohomologyBasis[G, field.Fp](x, 2)
      basis.length == 1 && !CupProduct.isCoboundary(x, 4, CupProduct.cup(x, 2, 2, basis.head, basis.head))
    "the Kuhnel-Banchoff triangulation has f-vector (9,36,84,90,36), Betti (1,0,1,0,1) and x^2 != 0" in {
      val k = complexProjectivePlaneKuhnel
      (k.fVector must beEqualTo(Vector(9, 36, 84, 90, 36)))
        .and(k.validate() must beEmpty)
        .and(betti(k, 2) must beEqualTo(Vector(1, 0, 1, 0, 1)))
        .and(betti(k, 3) must beEqualTo(Vector(1, 0, 1, 0, 1)))
        .and(squareOfH2Nonzero(k, 2) must beTrue)
        .and(squareOfH2Nonzero(k, 3) must beTrue)
    }
    "Sage's one-vertex model is valid, has the same Betti numbers and the same nonzero cup square" in {
      val m = complexProjectivePlane
      (m.validate() must beEmpty)
        .and(m.fVector must beEqualTo(Vector(1, 0, 2, 3, 3)))
        .and(betti(m, 2) must beEqualTo(Vector(1, 0, 1, 0, 1)))
        .and(betti(m, 3) must beEqualTo(Vector(1, 0, 1, 0, 1)))
        .and(squareOfH2Nonzero(m, 2) must beTrue)
        .and(squareOfH2Nonzero(m, 3) must beTrue)
    }
    "the cup square is what separates CP^2 from S^2 v S^4 (same Betti numbers, zero square)" in {
      import SimplicialSetFixtures.minimalSphere
      val wedge24 = minimalSphere(2).wedge(
        SimplicialSetFixtures.SphereGenerator.Vertex,
        minimalSphere(4),
        SimplicialSetFixtures.SphereGenerator.Vertex
      )
      (betti(wedge24, 2) must beEqualTo(Vector(1, 0, 1, 0, 1))).and(squareOfH2Nonzero(wedge24, 2) must beFalse)
    }
  }

  "the Hopf map" should {
    def squareNonzero[G](x: FiniteSimplicialSet[G], p: Int): Boolean =
      val field = new org.appliedtopology.tda4j.FiniteField(p)
      import field.given
      val basis = CupProduct.cohomologyBasis[G, field.Fp](x, 2)
      basis.length == 1 && !CupProduct.isCoboundary(x, 4, CupProduct.cup(x, 2, 2, basis.head, basis.head))
    "have a valid S^3 source with the homology of S^3, a valid target and a simplicial map" in {
      val f = hopfMap
      (f.source.validate() must beEmpty)
        .and(f.target.validate() must beEmpty)
        .and(f.validate() must beEmpty)
        .and(betti(f.source, 2) must beEqualTo(Vector(1, 0, 0, 1)))
        .and(betti(f.source, 3) must beEqualTo(Vector(1, 0, 0, 1)))
    }
    "have a mapping cone with the cohomology ring of CP^2: x^2 != 0, so Hopf invariant +-1" in {
      val cone = SSetMap.mappingCone(hopfMap)
      (cone.validate() must beEmpty)
        .and(betti(cone, 2) must beEqualTo(Vector(1, 0, 1, 0, 1)))
        .and(betti(cone, 3) must beEqualTo(Vector(1, 0, 1, 0, 1)))
        .and(squareNonzero(cone, 2) must beTrue)
        .and(squareNonzero(cone, 3) must beTrue)
    }
    "contrast with the constant map: its mapping cone is S^2 v S^4 with a zero square" in {
      val f = hopfMap
      val constant = SSetMap[HopfSphereGenerator, MinimalSphereGenerator](
        f.source,
        f.target,
        g => SSetElement(((f.source.dimOf(g) - 1) to 0 by -1).toList, MinimalSphereGenerator.Vertex)
      )
      val cone = SSetMap.mappingCone(constant)
      (constant.validate() must beEmpty)
        .and(betti(cone, 2) must beEqualTo(Vector(1, 0, 1, 0, 1)))
        .and(squareNonzero(cone, 2) must beFalse)
    }
  }
