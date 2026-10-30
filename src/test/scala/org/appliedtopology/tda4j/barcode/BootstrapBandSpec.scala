package org.appliedtopology.tda4j

import scala.language.experimental.modularity

import org.specs2.mutable.Specification

class BootstrapBandSpec extends Specification:

  private def noisyCircle(n: Int, noise: Double, seed: Long): PointCloud =
    val rng = new scala.util.Random(seed)
    PointCloud(Array.tabulate(n) { i =>
      val t = 2 * math.Pi * i / n
      Array(math.cos(t) + noise * rng.nextGaussian(), math.sin(t) + noise * rng.nextGaussian())
    })

  private def square(n: Int, seed: Long): PointCloud =
    val rng = new scala.util.Random(seed)
    PointCloud(Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble())))

  "BootstrapBand" should {
    "be reproducible from its seed and take its width from the sorted distances" >> {
      val points = noisyCircle(40, 0.05, 1L)
      val a = BootstrapBand(points, rounds = 20, seed = 5L)
      val b = BootstrapBand(points, rounds = 20, seed = 5L)
      // 20 rounds, alpha 0.05: the smallest value that 19 of the 20 distances do not exceed
      (a.distances must beEqualTo(b.distances)) and
        (a.width must beEqualTo(a.distances.sorted.apply(18))) and
        (a.threshold must beEqualTo(2.0 * a.width)) and
        (a.distances.forall(_ >= 0.0) must beTrue)
    }

    "keep the loop of a noisy circle beyond its band, and not the bars of a uniform square" >> {
      val circle = noisyCircle(60, 0.03, 2L)
      val circleBand = BootstrapBand(circle, rounds = 30, seed = 3L)
      val loop = Persistence(circle, maxDimension = 1).dim(1).longest.get
      val noise = square(60, 4L)
      val noiseBand = BootstrapBand(noise, rounds = 30, seed = 3L)
      val noiseBars = Persistence(noise, maxDimension = 1).dim(1)
      (loop.persistence must beGreaterThan(circleBand.threshold)) and
        (noiseBars.longerThan(noiseBand.threshold).bars.filterNot(_.death.isPosInfinity) must beEmpty)
    }

    "give a wider band from a smaller subsample" >> {
      val points = noisyCircle(60, 0.05, 6L)
      val full = BootstrapBand(points, rounds = 40, seed = 9L)
      val small = BootstrapBand(points, rounds = 40, seed = 9L, resampling = BootstrapBand.Resampling.Subsample(15))
      small.width must beGreaterThan(full.width)
    }

    "reject rounds, alpha and subsample sizes that make no sense" >> {
      val points = noisyCircle(10, 0.0, 1L)
      (BootstrapBand(points, rounds = 0) must throwAn[IllegalArgumentException]) and
        (BootstrapBand(points, alpha = 1.5) must throwAn[IllegalArgumentException]) and
        (BootstrapBand(points, resampling = BootstrapBand.Resampling.Subsample(11)) must
          throwAn[IllegalArgumentException])
    }
  }
