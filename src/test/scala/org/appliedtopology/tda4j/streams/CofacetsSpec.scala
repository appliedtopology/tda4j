package org.appliedtopology.tda4j

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck

/** `CofacetIterator(simplex, space)` against a brute-force list: the vertices `w` outside `simplex` whose distance to
  * every vertex of `simplex` is finite in the sparse space, each once, in increasing order of the coface's diameter.
  * The simplex is a vertex and up to two of its neighbours, so that it is a simplex of the sparse complex.
  */
class CofacetsSpec extends org.specs2.mutable.Specification with ScalaCheck:
  // Half of the clouds lie on a coarse grid: repeated points and tied distances.
  private val coordinates: Gen[Gen[Double]] = Gen.oneOf(Gen.double, Gen.chooseNum(0, 3).map(_ / 3.0))
  private val cases = for
    coordinate <- coordinates
    points <- matrixGen[Double](coordinate, Gen.chooseNum(2, 5), Gen.chooseNum(10, 40))
    cutoff <- Gen.chooseNum(0.2, 1.5)
    anchor <- Gen.chooseNum(0, points.length - 1)
    extra <- Gen.chooseNum(0, 2)
  yield (points, cutoff, anchor, extra)

  "The Cofacets Iterator should" >> {
    "give every finite coface once, in order of diameter" >> forAll(cases) { case (points, cutoff, anchor, extra) =>
      val space = SparseMetricSpace(EuclideanMetricSpace(points), cutoff)
      val diameter = FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](space)
      val spx = Simplex((anchor +: space.neighborhoods(anchor).map(_._1).filter(_ != anchor).take(extra))*)
      val expected =
        space.elements.filter(w => !spx.contains(w) && spx.toSeq.forall(v => space.distance(v, w).isFinite))

      val cofacets = CofacetIterator(spx, space).toList
      val diameters: List[Double] = cofacets.map(w => diameter(spx + w))
      (cofacets.distinct must haveSize(cofacets.size)) and
        (cofacets.toSet must beEqualTo(expected.toSet)) and
        (diameters must beSorted)
    }
  }
