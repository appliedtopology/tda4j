package org.appliedtopology.tda4j

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable.Specification

/** `EulerCharacteristicCurve` by lens-graph clique counts against counting the cells of
  * `EnumeratingCofaceSimplexStream` (cell for cell the reference of every other Vietoris-Rips construction), on points
  * in general position and on tie-heavy metrics, where the edges' lens graphs depend on the order the tied edges are
  * taken in; and against the alternating sum of the Betti numbers, which uses no simplex count at all.
  */
class EulerCharacteristicCurveSpec extends Specification with PlatformScalaCheck:
  // The first scale where two curves differ, if any (a failed `must beEqualTo` would render both curves).
  private def firstDifference(a: EulerCharacteristicCurve, b: EulerCharacteristicCurve): Option[String] =
    Option.when(a != b) {
      val i = a.scales.indices.find(i => i >= b.scales.size || a.scales(i) != b.scales(i) || a.values(i) != b.values(i))
      s"${a.scales.size} and ${b.scales.size} steps, first difference at $i: ${i.flatMap(a.scales.lift)} -> ${i.flatMap(a.values.lift)} vs ${i.flatMap(b.scales.lift)} -> ${i.flatMap(b.values.lift)}"
    }

  private def counted(metricSpace: FiniteMetricSpace[Int], dim: Int, threshold: Option[Double]) =
    EulerCharacteristicCurve.ofStream(
      LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = threshold), dim)
    )

  private def disagreements(metricSpace: FiniteMetricSpace[Int], thresholds: Seq[Option[Double]]): List[String] =
    for
      dim <- (0 to math.min(4, metricSpace.size - 1)).toList
      t <- thresholds.toList
      difference <- firstDifference(
        EulerCharacteristicCurve.vietorisRips(metricSpace, dim, Optional(t)),
        counted(metricSpace, dim, t)
      )
    yield s"simplices up to dimension $dim, threshold $t: $difference"

  private def thresholdsOf(metricSpace: FiniteMetricSpace[Int]): Seq[Option[Double]] =
    Seq(None, Some(Double.PositiveInfinity), Some(0.6 * metricSpace.minimumEnclosingRadius))

  private val octagon = EuclideanMetricSpace(
    Array.tabulate(8)(i => Array(math.cos(i * math.Pi / 4), math.sin(i * math.Pi / 4)))
  )
  private val grid = EuclideanMetricSpace(
    (for x <- 0 to 2; y <- 0 to 2; z <- 0 to 1 yield Array(x.toDouble, y.toDouble, z.toDouble)).toArray
  )
  private val threeValues = ExplicitMetricSpace(
    Seq.tabulate(9, 9)((i, j) => if i == j then 0.0 else 1.0 + ((i * 7 + j * 7 + i * j) % 3))
  )

  "EulerCharacteristicCurve" should {
    "step as a right triangle's cells appear, by hand" in {
      val triangle = Array(Array(0.0, 0.0), Array(3.0, 0.0), Array(0.0, 4.0))
      // Three vertices; the edges of length 3 and 4 each take one off; the edge of length 5 takes one off and the
      // triangle that appears with it (its lens is the third vertex) puts one back, so the curve does not step there.
      val curve =
        EulerCharacteristicCurve(triangle, maxSimplexDimension = 2, maxFiltrationValue = Double.PositiveInfinity)
      curve.scales === Vector(0.0, 3.0, 4.0)
      curve.values === Vector(3L, 2L, 1L)
      val edgesOnly = EulerCharacteristicCurve(triangle, 1, Double.PositiveInfinity)
      edgesOnly.scales === Vector(0.0, 3.0, 4.0, 5.0)
      edgesOnly.values === Vector(3L, 2L, 1L, 0L)
      EulerCharacteristicCurve(triangle, 0, Double.PositiveInfinity).values === Vector(3L)
      (curve(-1.0), curve(0.0), curve(2.9), curve(3.0), curve(4.5), curve(100.0)) === (0L, 3L, 3L, 2L, 1L, 1L)
      curve.sample(0.0, 5.0, 6).toSeq === Seq(3L, 3L, 3L, 2L, 1L, 1L)
    }

    "count the cells of the Enumerating stream on random clouds in general position" in
      forAll(matrixGen(Gen.choose(0.0, 1.0), Gen.chooseNum(2, 5), Gen.chooseNum(2, 14))) { pts =>
        val metricSpace = EuclideanMetricSpace(pts)
        disagreements(metricSpace, thresholdsOf(metricSpace)).take(3) must beEmpty
      }.set(minTestsOk = TestPlatform.propertyCases(60))

    "count the cells on the octagon, a grid and a metric with three distance values (heavy ties)" in {
      disagreements(octagon, thresholdsOf(octagon)).take(3) must beEmpty
      disagreements(grid, thresholdsOf(grid)).take(3) must beEmpty
      disagreements(threeValues, thresholdsOf(threeValues)).take(3) must beEmpty
    }

    "count the cells on random integer distances (heavy ties)" in
      forAll(Gen.chooseNum(4, 10), Gen.long) { (n, seed) =>
        val rng = scala.util.Random(seed)
        val values = Array.fill(n, n)(1.0 + rng.nextInt(3))
        val metricSpace =
          ExplicitMetricSpace(Seq.tabulate(n, n)((i, j) => if i == j then 0.0 else values(i max j)(i min j)))
        disagreements(metricSpace, thresholdsOf(metricSpace)).take(3) must beEmpty
      }.set(minTestsOk = TestPlatform.propertyCases(60))

    // The full complex's Euler characteristic is the alternating sum of its Betti numbers, which the persistence engine
    // gives with no simplex count. Seven points: the 6-simplex is the largest cell, and no 6-cycle exists in it.
    "equal the alternating sum of the Betti numbers of the full complex" in
      forAll(matrixGen(Gen.choose(0.0, 1.0), Gen.chooseNum(2, 3), Gen.const(7))) { pts =>
        val curve = EulerCharacteristicCurve(pts, maxSimplexDimension = 6, maxFiltrationValue = Double.PositiveInfinity)
        val triples = Persistence(pts, maxDimension = 5, maxFiltrationValue = Double.PositiveInfinity).triples
        val wrong = curve.scales.indices.filter { i =>
          val t = curve.scales(i)
          val alternating =
            triples.filter((_, birth, death) => birth <= t && t < death).map((k, _, _) => 1 - 2 * (k % 2)).sum
          alternating.toLong != curve.values(i)
        }
        wrong.take(3) must beEmpty
      }.set(minTestsOk = TestPlatform.propertyCases(40))

    "give the same curve on one thread as on several" in {
      val rng = scala.util.Random(11L)
      val points = Array.fill(70)(Array.fill(3)(rng.nextDouble()))
      val one = Parallel.sequentially(EulerCharacteristicCurve(points, 4, Double.PositiveInfinity))
      val several = Parallel.withThreads(3)(EulerCharacteristicCurve(points, 4, Double.PositiveInfinity))
      firstDifference(one, several) must beNone
      val small = EuclideanMetricSpace(points.take(25))
      firstDifference(
        Parallel.withThreads(3)(EulerCharacteristicCurve.vietorisRips(small, 3, Double.PositiveInfinity)),
        counted(small, 3, Some(Double.PositiveInfinity))
      ) must beNone
    }

    "be empty below the first scale and a single step for one point" in {
      val one = EulerCharacteristicCurve(Array(Array(0.5, 0.5)))
      one.scales === Vector(0.0)
      one.values === Vector(1L)
      one(-0.1) === 0L
      EulerCharacteristicCurve(Array(Array(0.0), Array(1.0)), 1, Double.PositiveInfinity).values === Vector(2L, 1L)
    }

    "refuse a negative dimension" in {
      EulerCharacteristicCurve(Array(Array(0.0), Array(1.0)), maxSimplexDimension = -1) must
        throwAn[IllegalArgumentException]
    }
  }
