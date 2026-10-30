package org.appliedtopology.tda4j
package matlab

import org.appliedtopology.tda4j.*

import org.specs2.mutable.Specification

/** `TDA4j.bootstrapBand`, `tomatoClusters` and `tomatoDiagram`, and the matchings of a `PersistenceResult`: the same
  * numbers as the library calls with the same arguments, each option reaching them, and the unknown options refused.
  */
class BootstrapBandResultSpec extends Specification:
  private def circle(n: Int, noise: Double, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.tabulate(n) { i =>
      val t = 2 * math.Pi * i / n
      Array(math.cos(t) + noise * rng.nextGaussian(), math.sin(t) + noise * rng.nextGaussian())
    }

  "bootstrapBand" should {
    "equal the library's band for the same arguments" >> {
      val points = circle(40, 0.05, 1L)
      val facade = TDA4j.bootstrapBand(points, Array("rounds", "12", "seed", "4", "alpha", "0.1"))
      val library = BootstrapBand(PointCloud(points), rounds = 12, alpha = 0.1, seed = 4L)
      (facade.width() must beEqualTo(library.width)) and (facade.threshold() must beEqualTo(2.0 * library.width)) and
        (facade.alpha() must beEqualTo(0.1)) and (facade.distances().toSeq must beEqualTo(library.distances))
    }

    "take a subsample size, and refuse an unknown option" >> {
      val points = circle(30, 0.05, 2L)
      val subsample = TDA4j.bootstrapBand(points, Array("rounds", "5", "subsampleSize", "10"))
      (subsample.distances().length must beEqualTo(5)) and
        (TDA4j.bootstrapBand(points, Array("rows", "5")) must throwAn[IllegalArgumentException])
    }
  }

  "tomatoClusters and tomatoDiagram" should {
    val rng = new scala.util.Random(8)
    def blob(x: Double) = Array.fill(30)(Array(x + 0.3 * rng.nextGaussian(), 0.3 * rng.nextGaussian()))
    val points = blob(0.0) ++ blob(10.0)

    "label two separated blobs with two clusters, as the library does" >> {
      val labels = TDA4j.tomatoClusters(points, 6, 100.0)
      (labels.toSet.size must beEqualTo(2)) and
        (labels.toSeq must beEqualTo(ToMATo.cluster(PointCloud(points), 6, 100.0).labels))
    }

    "list one essential bar for each component in the diagram" >> {
      TDA4j.tomatoDiagram(points, 6).count(_(1).isPosInfinity) must beEqualTo(2)
    }
  }

  "wassersteinMatching and bottleneckMatching" should {
    val a = FullBarcode.computeFromPoints(circle(20, 0.0, 1L), Array("maxDimension", "1"))
    val b = FullBarcode.computeFromPoints(circle(20, 0.1, 2L), Array("maxDimension", "1"))

    "match a result with itself bar to bar" >> {
      val rows = a.wassersteinMatching(a, 1, 1.0).map(_.toList).toSet
      (rows.forall(r => r.head == r(1)) must beTrue) and (rows must not(beEmpty))
    }

    "name every bar of both results exactly once" >> {
      val rows = b.bottleneckMatching(a, 1)
      val here = rows.map(_(0)).filter(_ >= 0).sorted.toList
      val there = rows.map(_(1)).filter(_ >= 0).sorted.toList
      (here must beEqualTo(here.distinct)) and (there must beEqualTo(there.distinct)) and
        (rows.forall(r => r(0) >= 0 || r(1) >= 0) must beTrue)
    }
  }
