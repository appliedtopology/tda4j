package org.appliedtopology.tda4j

import org.specs2.mutable

import scala.util.Random

/** The hyperplane through the points of a facet and the sphere through the points of a simplex, the two pieces of
  * geometry the Helix walk is built on. Both rest on `DenseMatrix.svd`: the plane takes its normal from the null
  * direction of the facet's difference vectors, whose sign the decomposition leaves open, so `Hyperplane.from` fixes
  * it.
  */
class HyperplaneSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  private def point(coordinates: Double*): AlphaShapes.Point = AlphaShapes.Point(coordinates.toArray)

  private def randomFacet(rng: Random, d: Int): Seq[AlphaShapes.Point] =
    Seq.fill(d)(AlphaShapes.Point(Array.fill(d)(rng.nextGaussian())))

  "a hyperplane through the points of a facet" should {
    "have a unit normal orthogonal to the facet, with every vertex at distance 0" in {
      val rng = Random(31)
      val problems = for
        d <- 2 to 7
        trial <- 0 until 20
        facet = randomFacet(rng, d)
        plane = Hyperplane.from(facet)
        normalError = math.abs(plane.normal.norm - 1.0)
        spread = facet.map(p => math.abs(plane.dist(p))).max
        if normalError > 1e-12 || spread > 1e-12
      yield s"d=$d trial $trial: |n| - 1 = $normalError, vertex distances up to $spread"
      problems must beEmpty
    }
    "measure the distance of other points by the dot product with the normal" in {
      // z = 1 through (0,0,1), (1,0,1), (0,1,1), (1,1,1): more points than dimensions, all coplanar.
      val facet = Seq(point(0, 0, 1), point(1, 0, 1), point(0, 1, 1), point(1, 1, 1))
      val plane = Hyperplane.from(facet)
      math.abs(plane.dist(point(5, -3, 4)) - 3.0) must beLessThan(1e-12)
      math.abs(plane.dist(point(5, -3, -1)) + 2.0) must beLessThan(1e-12)
      plane.isLight(point(0, 0, 2)) must beTrue
      plane.isLight(point(0, 0, 0)) must beFalse
    }
    "have the same normal and offset whichever order the points are given in" in {
      val rng = Random(37)
      val problems = for
        d <- 2 to 6
        trial <- 0 until 20
        facet = randomFacet(rng, d)
        reference = Hyperplane.from(facet)
        reordered <- Seq(facet.reverse, facet.drop(1) ++ facet.take(1), rng.shuffle(facet))
        plane = Hyperplane.from(reordered)
        normalGap = (plane.normal - reference.normal).norm
        offsetGap = math.abs(plane.offset - reference.offset)
        if normalGap > 1e-9 || offsetGap > 1e-9
      yield s"d=$d trial $trial: normal differs by $normalGap (opposite: ${(plane.normal + reference.normal).norm}), offset by $offsetGap"
      problems must beEmpty
    }
    "orient its normal canonically: the first coordinate of size at least 0.5/sqrt(d) is positive" in {
      val rng = Random(41)
      val problems = for
        d <- 2 to 7
        trial <- 0 until 30
        plane = Hyperplane.from(randomFacet(rng, d))
        first = (0 until d).find(i => math.abs(plane.normal(i)) >= 0.5 / math.sqrt(d.toDouble)).get
        if plane.normal(first) <= 0.0
      yield s"d=$d trial $trial: ${plane.normal}"
      problems must beEmpty
    }
    "be the line x - y = 1 with normal (1, -1)/sqrt(2), not its negative, through (1, 0) and (0, -1)" in {
      val plane = Hyperplane.from(Seq(point(1, 0), point(0, -1)))
      (plane.normal - point(1 / math.sqrt(2), -1 / math.sqrt(2))).norm must beLessThan(1e-12)
      math.abs(plane.offset - 1 / math.sqrt(2)) must beLessThan(1e-12)
    }
    "be the plane z = 0 with normal (0, 0, 1) through the origin and the two unit vectors" in {
      val plane = Hyperplane.from(Seq(point(0, 0, 0), point(1, 0, 0), point(0, 1, 0)))
      (plane.normal - point(0, 0, 1)).norm must beLessThan(1e-12)
      math.abs(plane.offset) must beLessThan(1e-12)
    }
    "reverse to the same plane seen from the other side" in {
      val rng = Random(43)
      val facet = randomFacet(rng, 4)
      val plane = Hyperplane.from(facet)
      val probe = point(0.3, -1.2, 2.0, 0.7)
      plane.reverse.dist(probe) must beEqualTo(-plane.dist(probe))
      plane.reverse.isLight(probe) must beEqualTo(!plane.isLight(probe))
      plane.reverse.reverse must beEqualTo(plane)
    }
    "be a value: the same facet gives equal planes, and a set of them deduplicates" in {
      val facet = Seq(point(0, 0, 1), point(1, 0, 2), point(0, 1, 4))
      Hyperplane.from(facet) must beEqualTo(Hyperplane.from(facet))
      Set(Hyperplane.from(facet), Hyperplane.from(facet)).size must beEqualTo(1)
    }
  }

  "a sphere through the points of a simplex" should {
    "be the circle through three points of the unit circle: centre 0, radius 1" in {
      val angles = Seq(0.3, 2.0, 4.4)
      val sphere = Hypersphere(angles.map(a => point(math.cos(a), math.sin(a))))
      sphere.center.norm must beLessThan(1e-12)
      math.abs(sphere.radius - 1.0) must beLessThan(1e-12)
    }
    "be the circle with centre (1, 1) and radius sqrt(2) through (0,0), (2,0), (0,2)" in {
      val sphere = Hypersphere(Seq(point(0, 0), point(2, 0), point(0, 2)))
      (sphere.center - point(1, 1)).norm must beLessThan(1e-12)
      math.abs(sphere.radius - math.sqrt(2.0)) must beLessThan(1e-12)
    }
    "pass through every vertex, in every dimension up to 6" in {
      val rng = Random(47)
      val problems = for
        d <- 2 to 6
        trial <- 0 until 20
        vertices = Seq.fill(d + 1)(AlphaShapes.Point(Array.fill(d)(rng.nextGaussian())))
        sphere = Hypersphere(vertices)
        gap = vertices.map(v => math.abs(v.distance(sphere.center) - sphere.radius)).max
        if gap > 1e-8
      yield s"d=$d trial $trial: vertices off the sphere by $gap"
      problems must beEmpty
    }
    "contain a point strictly inside and not one outside, and know a point on it" in {
      val sphere = Hypersphere(Seq(point(0, 0), point(2, 0), point(0, 2)))
      sphere.contains(point(1, 1)) must beTrue
      sphere.contains(point(5, 5)) must beFalse
      sphere.onSphere(point(2, 2)) must beTrue
      sphere.onSphere(point(1, 1)) must beFalse
    }
    "be a value: the same simplex gives equal spheres, and a DelaunaySimplex holding one equals its twin" in {
      val vertices = Seq(point(0, 0), point(2, 0), point(0, 2))
      Hypersphere(vertices) must beEqualTo(Hypersphere(vertices))
      Hypersphere(vertices).hashCode must beEqualTo(Hypersphere(vertices).hashCode)
      val s = Simplex.from(Seq(0, 1, 2))
      Set(DelaunaySimplex(s, Hypersphere(vertices)), DelaunaySimplex(s, Hypersphere(vertices))).size must beEqualTo(1)
    }
  }
