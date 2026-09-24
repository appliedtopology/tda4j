package org.appliedtopology.tda4j
package matlab

import org.appliedtopology.tda4j.barcode.{given, *}

import org.specs2.mutable.Specification

/** Tests the [[PersistenceResult]] additions from `.claude/WORKLOG-mainstream-feature-gap-analysis.md` items 4/8
  * (bottleneck/Wasserstein distance, landscapes, persistence images) -- specifically that this MATLAB-facing wrapper is
  * a faithful, correctly-marshalled pass-through to [[org.appliedtopology.tda4j.barcode.BarcodeDistance]]/
  * [[org.appliedtopology.tda4j.barcode.Vectorization]], which already have their own thorough, independently- oracled
  * test suites (`BarcodeDistanceSpec`/`VectorizationSpec`) -- not a re-test of the underlying math.
  */
class PersistenceResultSpec extends Specification:
  private val triangle = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.9))
  private val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))

  "bottleneckDistance / wassersteinDistance" should {
    "be exactly 0 between a result and itself, at every dimension it reports" >> {
      val result = TDA4j.computeFromPoints(triangle, Array("maxDimension", "1"))
      val dims = (0 until result.size()).map(result.dimension).distinct
      dims.forall { d =>
        (result.bottleneckDistance(result, d) == 0.0) && (result.wassersteinDistance(result, d) == 0.0)
      } must beTrue
    }

    "agree with a direct BarcodeDistance call on the same bars, extracted via cli-style toBars logic" >> {
      val r1 = TDA4j.computeFromPoints(triangle, Array("maxDimension", "1"))
      val r2 = TDA4j.computeFromPoints(square, Array("maxDimension", "1"))

      def barsOf(r: PersistenceResult, dim: Int): IndexedSeq[PersistenceBar[Double, Nothing]] =
        (0 until r.size())
          .filter(r.dimension(_) == dim)
          .map { i =>
            val upper: BarcodeEndpoint[Double] =
              if r.death(i).isPosInfinity then PositiveInfinity[Double]() else OpenEndpoint(r.death(i))
            PersistenceBar[Double, Nothing](dim, ClosedEndpoint(r.birth(i)), upper)
          }

      forall(Seq(0, 1)) { dim =>
        val expectedBottleneck = BarcodeDistance.bottleneckDistance(barsOf(r1, dim), barsOf(r2, dim))
        val expectedWasserstein = BarcodeDistance.wassersteinDistance(barsOf(r1, dim), barsOf(r2, dim))
        (r1.bottleneckDistance(r2, dim) must beEqualTo(expectedBottleneck)) and
          (r1.wassersteinDistance(r2, dim) must beEqualTo(expectedWasserstein))
      }
    }

    "are symmetric" >> {
      val r1 = TDA4j.computeFromPoints(triangle, Array("maxDimension", "1"))
      val r2 = TDA4j.computeFromPoints(square, Array("maxDimension", "1"))
      (r1.bottleneckDistance(r2, 0) must beEqualTo(r2.bottleneckDistance(r1, 0))) and
        (r1.wassersteinDistance(r2, 0) must beEqualTo(r2.wassersteinDistance(r1, 0)))
    }

    "the explicit-ground-norm overload with Double.PositiveInfinity matches the default (L-infinity) overload" >> {
      val r1 = TDA4j.computeFromPoints(triangle, Array("maxDimension", "1"))
      val r2 = TDA4j.computeFromPoints(square, Array("maxDimension", "1"))
      (r1.bottleneckDistance(r2, 0) must beEqualTo(r1.bottleneckDistance(r2, 0, Double.PositiveInfinity))) and
        (r1.wassersteinDistance(r2, 0) must beEqualTo(r1.wassersteinDistance(r2, 0, 1.0, Double.PositiveInfinity)))
    }

    "a finite ground norm (L-2) gives a genuinely different value than the L-infinity default, on a diagram " +
      "with more than one bar" >> {
        val r1 = TDA4j.computeFromPoints(triangle, Array("maxDimension", "1"))
        val r2 = TDA4j.computeFromPoints(square, Array("maxDimension", "1"))
        r1.bottleneckDistance(r2, 0, 2.0) must not(beEqualTo(r1.bottleneckDistance(r2, 0)))
      }
  }

  "landscape" should {
    "returns numLevels x resolution, matching the request exactly" >> {
      val result = TDA4j.computeFromPoints(triangle, Array("maxDimension", "1"))
      val levels = result.landscape(0, numLevels = 2, tMin = 0.0, tMax = 2.0, resolution = 21)
      (levels.length must beEqualTo(2)) and (levels.forall(_.length == 21) must beTrue)
    }

    "is all-zero for a dimension this result has no bars in" >> {
      val result = TDA4j.computeFromPoints(triangle, Array("maxDimension", "1"))
      val levels = result.landscape(5, numLevels = 1, tMin = 0.0, tMax = 1.0, resolution = 5)
      levels.flatten.forall(_ == 0.0) must beTrue
    }
  }

  "persistenceImage" should {
    "returns birthResolution x persistenceResolution, non-negative throughout" >> {
      val result = TDA4j.computeFromPoints(square, Array("maxDimension", "1"))
      val image = result.persistenceImage(
        1,
        sigma = 0.2,
        birthMin = 0.0,
        birthMax = 2.0,
        persistenceMin = 0.0,
        persistenceMax = 2.0,
        birthResolution = 10,
        persistenceResolution = 10
      )
      (image.length must beEqualTo(10)) and
        (image.forall(_.length == 10) must beTrue) and
        (image.flatten.forall(_ >= 0.0) must beTrue)
    }

    "the explicit-weightCap overload, given the diagram's own max finite persistence, matches the default " +
      "overload exactly" >> {
        val result = TDA4j.computeFromPoints(square, Array("maxDimension", "1"))
        val dim = 1
        val maxPersistence = (0 until result.size())
          .filter(result.dimension(_) == dim)
          .map(i => result.death(i) - result.birth(i))
          .filter(_.isFinite)
          .maxOption
          .getOrElse(0.0)

        val withoutCap =
          result.persistenceImage(dim, 0.3, 0.0, 2.0, 0.0, 2.0, 8, 8)
        val withExplicitCap =
          result.persistenceImage(dim, 0.3, 0.0, 2.0, 0.0, 2.0, 8, 8, maxPersistence)
        withoutCap.flatten.toSeq must beEqualTo(withExplicitCap.flatten.toSeq)
      }
  }
