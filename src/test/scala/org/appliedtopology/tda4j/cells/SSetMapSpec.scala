package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

class SSetMapSpec extends Specification:
  import SimplicialSetFixtures.*
  private val f2 = new FiniteField(2)
  private val f3 = new FiniteField(3)

  private def rank(f: SSetMap[?, ?], n: Int, p: Int): Int =
    val field = new FiniteField(p)
    import field.given
    f.homologyRank[field.Fp](n)

  "the linear algebra" should {
    "compute rank and nullspace over F_3" in {
      import f3.given
      val m = Seq(Seq(1, 2, 0), Seq(2, 1, 0), Seq(0, 0, 1)).map(_.map(f3.Fp(_)))
      (LinearAlgebra.rank(m) must beEqualTo(2))
        .and(LinearAlgebra.nullspace(m, 3).size must beEqualTo(1))
    }
  }

  "identity, composition and validation" should {
    "accept the identity of the torus and compose it with itself" in {
      val id = SSetMap.identity(torus)
      (id.validate() must beEmpty).and(id.andThen(id).validate() must beEmpty).and(id.isBijective must beTrue)
    }
    "reject a map that does not commute with the faces" in {
      // send the loop A to the vertex and keep B: A's triangle faces no longer match
      val bad = SSetMap[TorusGenerator, TorusGenerator](
        torus,
        torus,
        g => if g == TorusGenerator.A then SSetElement(Nil, TorusGenerator.B) else SSetElement(Nil, g)
      )
      bad.validate() must not(beEmpty)
    }
  }

  "inclusion of a circle in the torus" should {
    "be injective, not surjective, with an image equal to the circle, and have rank 1 on H_1 over F_2 and F_3" in {
      val circle = SimplicialSets.subcomplex(torus, Set(TorusGenerator.Vertex, TorusGenerator.A))
      val inc = SSetMap.inclusion(circle, torus)
      (inc.validate() must beEmpty)
        .and(inc.isInjective must beTrue)
        .and(inc.isSurjective must beFalse)
        .and(inc.image.generatorsByDim.map(_.size) must beEqualTo(Vector(1, 1)))
        .and(rank(inc, 1, 2) must beEqualTo(1))
        .and(rank(inc, 1, 3) must beEqualTo(1))
        .and(rank(inc, 0, 2) must beEqualTo(1))
    }
  }

  "the base circle into its cone" should {
    "have rank 0 on H_1 (the cone kills it) and rank 1 on H_0" in {
      val circle = minimalSphere(1)
      val cone = SimplicialSets.cone(circle)
      val base = SSetMap(circle, cone, g => SSetElement(Nil, ConeGenerator.Base(g)))
      (base.validate() must beEmpty).and(rank(base, 1, 2) must beEqualTo(0)).and(rank(base, 0, 2) must beEqualTo(1))
    }
  }

  "the projection of a product of circles" should {
    "have rank 1 on H_1 and be surjective but not injective" in {
      val circle = minimalSphere(1)
      val product = FiniteSimplicialSet.product(circle, circle)
      val p = SSetMap.projectionFirst(product, circle)
      (p.validate() must beEmpty)
        .and(rank(p, 1, 2) must beEqualTo(1))
        .and(rank(p, 1, 3) must beEqualTo(1))
        .and(p.isSurjective must beTrue)
        .and(p.isInjective must beFalse)
    }
  }

  "the quotient map of RP^2's triangle model" should {
    "be surjective, not injective, and valid" in {
      val q = SSetMap.quotientMap(triangle, realProjectiveSpaceViaQuotient, rp2QuotientMap)
      (q.validate() must beEmpty).and(q.isSurjective must beTrue).and(q.isInjective must beFalse)
    }
  }

  "mapping cones" should {
    "of the identity be acyclic" in {
      val c = SSetMap.mappingCone(SSetMap.identity(torus))
      (c.validate() must beEmpty).and(SSetBetti(c, 2) must beEqualTo(Vector(1, 0, 0, 0)))
    }
    "of the circle's inclusion in the torus have Betti numbers (1, 1, 1): the disk kills the circle" in {
      val circle = SimplicialSets.subcomplex(torus, Set(TorusGenerator.Vertex, TorusGenerator.A))
      val c = SSetMap.mappingCone(SSetMap.inclusion(circle, torus))
      (c.validate() must beEmpty)
        .and(SSetBetti(c, 2) must beEqualTo(Vector(1, 1, 1)))
        .and(SSetBetti(c, 3) must beEqualTo(Vector(1, 1, 1)))
    }
  }
