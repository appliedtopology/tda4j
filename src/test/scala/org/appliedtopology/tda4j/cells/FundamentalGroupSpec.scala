package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.groups.{ClassifyingSpace, FiniteGroup, NerveSimplex}
import org.specs2.mutable.Specification

class FundamentalGroupSpec extends Specification:
  import SimplicialSetFixtures.*
  import SimplicialSets.*

  /** Hurewicz, with no word-problem solver: `#generators - rank_p(exponent sums) = dim H_1(X; F_p)`. */
  private def hurewicz[G](x: FiniteSimplicialSet[G]) =
    Seq(2, 3).map(p => (FundamentalGroup.presentation(x).abelianRank(p), SSetBetti(x, p)(1)))

  "reduce" should {
    "leave a valid one-vertex set: a 3-simplex keeps its 6 - 3 = 3 non-tree edges, which its triangles kill" in {
      val r = FundamentalGroup.reduce(simplex(3))
      (r.validate() must beEmpty)
        .and(fVector(r).take(2) must beEqualTo(Vector(1, 3)))
        .and(FundamentalGroup.presentation(simplex(3)).abelianRank(2) must beEqualTo(0))
    }
    "reject a disconnected set" in {
      FundamentalGroup.reduce(fromSimplicialComplex(Seq(Simplex(0, 1), Simplex(2, 3)))) must throwAn[IllegalArgumentException]
    }
  }

  "Hurewicz: generators minus the F_p-rank of the exponent sums equals dim H_1(X; F_p)" should {
    "hold for the sphere, torus, RP^2, RP^3, Klein bottle, octahedron and a simplex" in {
      val triangles = for a <- List(1, 2); b <- List(3, 4); c <- List(5, 6) yield Simplex(a, b, c)
      Seq(
        hurewicz(minimalSphere(2)),
        hurewicz(torus),
        hurewicz(realProjectiveSpace(2)),
        hurewicz(realProjectiveSpace(3)),
        hurewicz(kleinBottle),
        hurewicz(fromSimplicialComplex(triangles)),
        hurewicz(simplex(3))
      ).forall(_.forall((a, b) => a == b)) must beTrue
    }
    "give the right values: RP^2 has H_1 = F_2 over F_2 and 0 over F_3; the Klein bottle (2, 1)" in {
      (hurewicz(realProjectiveSpace(2)) must beEqualTo(Seq((1, 1), (0, 0))))
        .and(hurewicz(kleinBottle) must beEqualTo(Seq((2, 2), (1, 1))))
    }
  }

  "presentations" should {
    "of the Klein bottle be <a,b,c | ab = c, ca = b> (3 generators, 2 relations)" in {
      val p = FundamentalGroup.presentation(kleinBottle)
      (p.generators.length must beEqualTo(3)).and(p.relations.length must beEqualTo(2))
    }
    "of the 2-sphere's model be trivial" in {
      val p = FundamentalGroup.presentation(minimalSphere(2))
      p.generators must beEmpty
    }
  }

  "the 2-skeleton of B(S_3)" should {
    "have a presentation of which the group table satisfies every relation" in {
      val s3 = FiniteGroup.symmetric(3)
      val skeleton = ClassifyingSpace(s3, 2)
      val p = FundamentalGroup.presentation(skeleton)
      val element: Int => Int = i => p.generators(i).entries.head
      (p.generators.length must beEqualTo(5))
        .and(p.relationsHold[Int](element, s3.identity, s3.multiply, s3.inverse) must beTrue)
        .and(p.abelianRank(2) must beEqualTo(1))
        .and(p.abelianRank(3) must beEqualTo(0))
    }
  }
