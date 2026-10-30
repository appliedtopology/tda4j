package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

/** `TDA4j.computeInclusionFromPoints` and its relatives: the map of persistent homology induced by a map of
  * Vietoris-Rips complexes. The oracle for the numbers is the typed `PersistenceMap`, which `PersistenceMapSpec` checks
  * against the ranks of the structure maps of the modules; here the facade must be that, degree by degree, and tell the
  * story of a loop seen by a subsample worked out by hand.
  */
class MapFacadeSpec extends Specification:

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))

  private def circle(n: Int) =
    Array.tabulate(n)(i => Array(math.cos(2 * math.Pi * i / n), math.sin(2 * math.Pi * i / n)))

  private def rows(bars: Seq[PersistenceBar[Double, ?]]): Seq[Seq[Double]] =
    bars.map(b =>
      Seq(b.lower, b.upper).map {
        case ClosedEndpoint(v)  => v
        case OpenEndpoint(v)    => v
        case PositiveInfinity() => Double.PositiveInfinity
        case NegativeInfinity() => Double.NegativeInfinity
      }
    )

  private def sorted(a: Array[Array[Double]]): Seq[Seq[Double]] = a.toSeq.map(_.toSeq).sortBy(r => (r.head, r.last))

  "the map facade" should {
    "give the bars and the matrix of the typed map, for either engine, over Z/2, Z/3 and Z/17, with an unsorted subsample" in {
      val points = cloud(11, 4L)
      val subsample = Array(5, 0, 3, 9)
      val whole = EuclideanMetricSpace(points)
      val sub = EuclideanMetricSpace(subsample.map(points))
      val failures = for
        (name, engine) <- List("ripser" -> Persistence.Engine.Ripser, "cohomology" -> Persistence.Engine.Cohomology)
        p <- List(2, 3, 17)
        found = TDA4j.computeInclusionFromPoints(points, subsample, Array("engine", name, "prime", p.toString))
        diagram = (space: FiniteMetricSpace[Int]) =>
          Persistence(
            space,
            maxDimension = 2,
            maxFiltrationValue = whole.minimumEnclosingRadius,
            characteristic = p,
            engine = engine,
            includeZeroLength = true
          )
        (from, to) = (diagram(sub), diagram(whole))
        map = PersistenceMap.simplicial(from, to)(i => subsample(i))
        field = from.coefficientField
        k <- 0 to 2
        problem <- List(
          Option.when(sorted(found.sourceBars(k)) != rows(map.sourceBars(k)).sortBy(r => (r.head, r.last)))(
            "source bars"
          ),
          Option.when(sorted(found.targetBars(k)) != rows(map.targetBars(k)).sortBy(r => (r.head, r.last)))(
            "target bars"
          ),
          Option.when(sorted(found.image(k)) != rows(map.image(k)).sortBy(r => (r.head, r.last)))("image"),
          Option.when(sorted(found.kernel(k)) != rows(map.kernel(k)).sortBy(r => (r.head, r.last)))("kernel"),
          Option.when(sorted(found.cokernel(k)) != rows(map.cokernel(k)).sortBy(r => (r.head, r.last)))("cokernel"),
          Option.when(
            found.entries(k).toSeq.map(_.toSeq) !=
              map.entries(k).map((i, j, c) => Seq(i.toDouble, j.toDouble, field.toDouble(c)))
          )("entries")
        ).flatten
      yield s"$name p=$p degree $k: $problem"
      failures must beEmpty
    }

    "tell a subsample's loop from the whole circle's: the loop it sees, when the whole sample's loop is gone, and before" in {
      // 24 points on the unit circle, every sixth: a square, whose loop is born at its side, sqrt 2, and dies at its
      // diagonal, 2. The circle's own loop is born at a step of 15 degrees and dies at the chord of 120 degrees
      // (sqrt 3) or just after. Where both are alive the loop maps to the loop.
      val points = circle(24)
      // Past the square's diagonal, which is at the enclosing radius of the circle's points, rounded.
      val result = TDA4j.computeInclusionFromPoints(points, Array(0, 6, 12, 18), Array("maxFiltrationValue", "2.5"))
      val targetLoop = result.targetBars(1).head
      val side = math.sqrt(2.0)
      def close(a: Double, b: Double) = math.abs(a - b) < 1e-9
      (result.targetBars(1).length must beEqualTo(1)) and (result.sourceBars(1).length must beEqualTo(1)) and
        (close(result.sourceBars(1).head(0), side) must beTrue) and (close(
          result.sourceBars(1).head(1),
          2.0
        ) must beTrue) and
        (targetLoop(0) must beLessThan(side)) and (targetLoop(1) must beBetween(1.7, 2.0)) and
        // the image: from the side to the end of the circle's loop
        (result.image(1).length must beEqualTo(1)) and (close(result.image(1).head(0), side) must beTrue) and
        (result.image(1).head(1) must beEqualTo(targetLoop(1))) and
        // the kernel: the square's loop after the circle's is filled in
        (result.kernel(1).length must beEqualTo(1)) and (result.kernel(1).head(0) must beEqualTo(targetLoop(1))) and
        (close(result.kernel(1).head(1), 2.0) must beTrue) and
        // the cokernel: the circle's loop before the square sees it
        (result.cokernel(1).length must beEqualTo(1)) and (result.cokernel(1).head(0) must beEqualTo(targetLoop(0))) and
        (close(result.cokernel(1).head(1), side) must beTrue) and
        // and the map is an isomorphism from the square's loop where both are alive: one entry, a unit
        (result.entries(1).length must beEqualTo(1)) and (result.entries(1).head.take(2).toSeq must beEqualTo(
          Seq(0.0, 0.0)
        )) and
        (math.abs(result.entries(1).head(2)) must beEqualTo(1.0))
    }

    "take distance matrices, and two clouds with a map between them" in {
      val points = cloud(9, 2L)
      val distances =
        Array.tabulate(9, 9)((i, j) => math.sqrt(points(i).zip(points(j)).map((a, b) => (a - b) * (a - b)).sum))
      val subsample = Array(1, 4, 6, 8)
      val byPoints = TDA4j.computeInclusionFromPoints(points, subsample)
      val byMatrix = TDA4j.computeInclusionFromDistanceMatrix(distances, subsample, Array.empty[String])
      val between = TDA4j.computeMapFromPoints(subsample.map(points), points, subsample)
      val sameEverywhere = for
        other <- List(byMatrix, between)
        k <- 0 to 2
        found <- List(
          sorted(other.image(k)) == sorted(byPoints.image(k)),
          sorted(other.kernel(k)) == sorted(byPoints.kernel(k)),
          sorted(other.cokernel(k)) == sorted(byPoints.cokernel(k))
        )
      yield found
      (sameEverywhere.forall(identity) must beTrue) and (byPoints.maxDimension() must beEqualTo(2)) and
        (byPoints.characteristic() must beEqualTo(17))
    }

    "refuse a map that increases a distance, a map of the wrong size, an engine it does not have and a point not there" in {
      val near = Array(Array(0.0, 0.0), Array(0.1, 0.0))
      val far = Array(Array(0.0, 0.0), Array(5.0, 0.0))
      val points = cloud(5, 1L)
      def inclusion(subsample: Array[Int], options: String*) =
        TDA4j.computeInclusionFromPoints(points, subsample, options.toArray)
      (TDA4j.computeMapFromPoints(near, far, Array(0, 1)) must throwA[IllegalArgumentException](
        message = "not a map of persistence modules"
      )) and
        (TDA4j.computeMapFromPoints(near, far, Array(0)) must throwA[IllegalArgumentException](message =
          "one for each"
        )) and
        (TDA4j.computeMapFromPoints(near, far, Array(0, 2)) must throwA[IllegalArgumentException](
          message = "outside the target"
        )) and
        (inclusion(Array(0, 1), "engine", "naive") must throwA[IllegalArgumentException](message =
          "die with their bars"
        )) and
        (inclusion(Array(5)) must throwA[IllegalArgumentException](message = "1-based")) and
        (inclusion(Array.empty[Int]) must throwA[IllegalArgumentException](message = "at least one"))
    }

    "hand out copies" in {
      val result = TDA4j.computeInclusionFromPoints(cloud(6, 3L), Array(0, 2, 4))
      val before = result.sourceBars(0)(0)(0)
      result.sourceBars(0)(0)(0) = -99.0
      result.sourceBars(0)(0)(0) must beEqualTo(before)
    }
  }
