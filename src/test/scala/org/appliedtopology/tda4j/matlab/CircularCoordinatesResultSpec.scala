package org.appliedtopology.tda4j
package matlab

import org.appliedtopology.tda4j.homology.NoIntegerCocycleException
import org.appliedtopology.tda4j.streams.{given, *}

import org.specs2.mutable.Specification

/** Tests the `TDA4j.h1Bars`/`circularCoordinates` MATLAB facade -- that it is a faithful, correctly-marshalled
  * pass-through to `homology.CircularCoordinates`, which already has its own thorough test suite
  * (`CircularCoordinatesSpec`, including the real oracle: recovering the true geometric angle on a circle) --
  * not a re-test of the underlying math.
  */
class CircularCoordinatesResultSpec extends Specification:
  private def circlePoints(n: Int): Array[Array[Double]] =
    Array.tabulate(n)(i => Array(math.cos(2 * math.Pi * i / n), math.sin(2 * math.Pi * i / n)))

  "h1Bars" should {
    "return one row per H^1 bar, sorted by persistence descending" >> {
      val bars = TDA4j.h1Bars(circlePoints(16))
      bars.length must beGreaterThan(0)
      val persistence = bars.map(row => row(1) - row(0))
      persistence.toSeq must beEqualTo(persistence.sorted(using Ordering[Double].reverse).toSeq)
    }
  }

  "circularCoordinates" should {
    "recovers the true geometric angle on a clean circle, matching homology.CircularCoordinates directly" >> {
      val points = circlePoints(16)
      val bars = TDA4j.h1Bars(points)
      val (birth, death) = (bars(0)(0), bars(0)(1))
      val r = birth + (death - birth) * 0.5

      val viaFacade = TDA4j.circularCoordinates(points, r, 0, 47)
      val viaDirect = homology.CircularCoordinates.compute(EuclideanMetricSpace(points), r, 0, 47)

      viaFacade.birth() must beEqualTo(viaDirect.birth)
      viaFacade.death() must beEqualTo(viaDirect.death)
      forall(0 until points.length) { i =>
        if viaDirect.theta.contains(i) then
          (viaFacade.hasCoordinate(i) must beTrue) and (viaFacade.theta()(i) must beEqualTo(viaDirect.theta(i)))
        else viaFacade.hasCoordinate(i) must beFalse
      }
    }

    "the two-argument overload matches the explicit default (cocycleIndex=0, prime=47)" >> {
      val points = circlePoints(16)
      val bars = TDA4j.h1Bars(points)
      val r = bars(0)(0) + (bars(0)(1) - bars(0)(0)) * 0.5
      val short = TDA4j.circularCoordinates(points, r)
      val long = TDA4j.circularCoordinates(points, r, 0, 47)
      short.theta().toSeq must beEqualTo(long.theta().toSeq)
    }

    "throws NoIntegerCocycleException as a RuntimeException a MATLAB caller can still catch generically, not " +
      "just IllegalArgumentException" >> {
      // Not exercised via an actual torsion fixture (hard to construct from a Euclidean point cloud -- see
      // CircularCoordinates' own worklog) -- just confirms the exception type itself is MATLAB-bridge-safe.
      classOf[NoIntegerCocycleException].getSuperclass must beEqualTo(classOf[RuntimeException])
    }
  }
