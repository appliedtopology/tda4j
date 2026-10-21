package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** The alpha complex of `c * points` is `c` times the alpha complex of `points`, on every backend: the triangulations'
  * tolerance is a fraction of the extent of the points (`EpsilonFactor`), not an absolute number. With an absolute
  * `1e-5`, points spanning less than about 0.1 gave a different filtration (no circumsphere margin was left) and extra
  * H1 bars.
  */
class AlphaScaleInvarianceSpec extends Specification:
  private def barcode(points: Array[Array[Double]], backend: AlphaBackend, scale: Double) =
    val complex: LevelwiseSimplexStream[Int, Double] = AlphaShapes(PointCloud(points.map(_.map(_ * scale))), backend)
    Persistence(complex, maxDimension = 1, characteristic = 17, engine = Persistence.Engine.Cohomology).triples
      .map((d, b, e) => (d, BigDecimal(b / scale).setScale(5, BigDecimal.RoundingMode.HALF_UP), e / scale))
      .map((d, b, e) =>
        (d, b, if e.isInfinite then BigDecimal(-1) else BigDecimal(e).setScale(5, BigDecimal.RoundingMode.HALF_UP))
      )
      .sorted

  private def cloud(seed: Int, n: Int, dimension: Int) =
    val rnd = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dimension)(rnd.nextDouble()))

  "AlphaShapes" should {
    for
      backend <- Seq(AlphaBackend.Default, AlphaBackend.BowyerWatson, AlphaBackend.Helix, AlphaBackend.DQP)
      dimension <- Seq(2, 3)
    do
      s"give the same barcode for points scaled by 1e-4, 1 and 1e4 ($backend, $dimension-D)" in
        (0 until 6)
          .map { seed =>
            val points = cloud(seed, 12, dimension)
            val reference = barcode(points, backend, 1.0)
            (barcode(points, backend, 1e-4) must beEqualTo(reference))
              .and(barcode(points, backend, 1e4) must beEqualTo(reference))
          }
          .reduce(_ and _)
    "give the same barcode in 5-D on Helix at a small scale" in {
      val points = cloud(3, 12, 5)
      barcode(points, AlphaBackend.Helix, 1e-3) must beEqualTo(barcode(points, AlphaBackend.Helix, 1.0))
    }
  }

  "EpsilonFactor" should {
    "be a fraction of the largest coordinate range, 1 for a point set without extent" in {
      val spread = Array(Array(0.0, 0.0), Array(4.0, 1.0), Array(1.0, 2.0))
      (EpsilonFactor(1e-3).forPoints(spread).epsilon must beCloseTo(4e-3, 1e-12))
        .and(EpsilonFactor(1e-3).forPoints(Array(Array(2.0, 2.0))).epsilon must beCloseTo(1e-3, 1e-12))
        .and(EpsilonFactor(1e-3).forPoints(Array.empty[Array[Double]]).epsilon must beCloseTo(1e-3, 1e-12))
    }
    "refuse a factor that is not a positive number" in
      (EpsilonFactor(0.0) must throwA[IllegalArgumentException])
        .and(EpsilonFactor(Double.NaN) must throwA[IllegalArgumentException])
  }
