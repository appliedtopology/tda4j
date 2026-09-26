package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}

import org.specs2.mutable.Specification

/** Tests `CircularCoordinates.computeToroidal` (`.claude/WORKLOG-toroidal-coordinates.md`) -- the lattice-reduction
  * (LLL) extension of `CircularCoordinates.compute` combining SEVERAL simultaneously-alive H^1 classes into one
  * torus-valued map. `LatticeReductionSpec` already covers the reduction algorithm itself in isolation (Gram
  * matrices only, no geometry); this file is about the WIRING: class selection, the shared-component requirement,
  * and that `reduce=false` exactly reproduces calling `compute` per class.
  */
class ToroidalCoordinatesSpec extends Specification:
  given Double is Field = Field.DoubleApproximated(1e-9)

  /** A wedge of two circles of DIFFERENT radii (deliberately unequal -- breaks any birth/death tie between the two
    * loops), touching near a single point so the whole point cloud is one connected component but the two loops stay
    * topologically independent (H^1 rank 2: a shared single join point doesn't add a cycle, same as a wedge sum). No
    * randomness anywhere -- circle A's own sample point 0 and circle B's own sample point 0 are placed to land almost
    * on top of each other by construction, and every other pair of A/B points is far apart by comparison, so exactly
    * one bridging edge appears over a wide range of filtration values, never a second one that would create a spurious
    * extra cycle around the outside.
    */
  private def wedgeOfTwoCircles(n: Int = 14): Array[Array[Double]] =
    val circleA = Array.tabulate(n) { i =>
      val a = 2 * math.Pi * i / n
      Array(math.cos(a), math.sin(a)) // center (0,0), radius 1.0
    }
    val circleB = Array.tabulate(n) { i =>
      val a = math.Pi + 2 * math.Pi * i / n // offset so i=0 lands at angle pi, i.e. facing circle A
      Array(1.55 + 0.6 * math.cos(a), 0.6 * math.sin(a)) // center (1.55,0), radius 0.6
    }
    circleA ++ circleB

  private def circlePoints(n: Int, center: (Double, Double) = (0.0, 0.0), radius: Double = 1.0): Array[Array[Double]] =
    Array.tabulate(n) { i =>
      val a = 2 * math.Pi * i / n
      Array(center._1 + radius * math.cos(a), center._2 + radius * math.sin(a))
    }

  private def det2(m: Array[Array[Double]]): Double = m(0)(0) * m(1)(1) - m(0)(1) * m(1)(0)

  "computeToroidal on a wedge of two independent circles" should {
    "find (at least) two persistent H^1 bars, simultaneously alive over a shared range" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      val bars = CircularCoordinates.h1Bars(metricSpace)
      bars.length must beGreaterThanOrEqualTo(2)
      val (b0, d0) = bars(0)
      val (b1, d1) = bars(1)
      math.max(b0, b1) must beLessThan(math.min(d0, d1)) // a common r exists
    }

    "with reduce=false, reproduce compute's own per-class theta exactly, point for point" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      val bars = CircularCoordinates.h1Bars(metricSpace)
      val r = math.max(bars(0)._1, bars(1)._1) + 1e-6

      val toroidal = CircularCoordinates.computeToroidal(metricSpace, r, Seq(0, 1), reduce = false)
      val direct0 = CircularCoordinates.compute(metricSpace, r, cocycleIndex = 0)
      val direct1 = CircularCoordinates.compute(metricSpace, r, cocycleIndex = 1)

      toroidal.basisChange must beEqualTo(Array(Array(1, 0), Array(0, 1)))
      (toroidal.theta(0) must beEqualTo(direct0.theta)) and (toroidal.theta(1) must beEqualTo(direct1.theta))
    }

    "with reduce=true (default), produce a unimodular basis change onto a Gram matrix that is genuinely reduced" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      val bars = CircularCoordinates.h1Bars(metricSpace)
      val r = math.max(bars(0)._1, bars(1)._1) + 1e-6

      val toroidal = CircularCoordinates.computeToroidal(metricSpace, r, Seq(0, 1))
      val u = toroidal.basisChange.map(_.map(_.toDouble))
      val det = det2(u)

      (math.abs(math.abs(det) - 1.0) must beLessThan(1e-6)) and
        (LatticeReduction.isReduced(toroidal.reducedGram) must beTrue) and
        // same rank-2 sublattice of H^1 before/after -- covolume^2 = det(Gram) is a unimodular-change invariant
        (math.abs(det2(toroidal.reducedGram) - det2(toroidal.originalGram)) must beLessThan(
          1e-6 * (1.0 + math.abs(det2(toroidal.originalGram)))
        )) and
        (toroidal.theta.length must beEqualTo(2)) and
        (toroidal.cocycleIndices must beEqualTo(IndexedSeq(0, 1)))
    }

    "reject cocycleIndices whose classes don't share a connected component" >> {
      // The two circles are DELIBERATELY far apart (offset 1000) so they never share a component. A VR
      // filtration also generates plenty of short-lived small-scale H^1 "noise" bars well below the main
      // circle-completing loop's own persistence (ordinary VR behavior, not specific to this fixture) -- so
      // rather than assume a specific total bar count, just confirm the top 2 BY PERSISTENCE are the two real
      // (identical, since the circles are exact translates of each other) circle loops, which they must be
      // since nothing else in either circle's own filtration is anywhere near as persistent.
      val n = 12
      val nearCircle = circlePoints(n)
      val farCircle = circlePoints(n, center = (1000.0, 1000.0))
      val metricSpace = EuclideanMetricSpace(nearCircle ++ farCircle)
      val bars = CircularCoordinates.h1Bars(metricSpace, maxFiltrationValue = Some(3.0))
      bars.length must beGreaterThanOrEqualTo(2)
      val (b0, d0) = bars(0)
      val (b1, d1) = bars(1)
      (math.abs(b0 - b1) must beLessThan(1e-9)) and (math.abs(d0 - d1) must beLessThan(1e-9))
      val r = b0 + (d0 - b0) * 0.5

      CircularCoordinates.computeToroidal(metricSpace, r, Seq(0, 1), maxFiltrationValue = Some(3.0)) must
        throwA[IllegalArgumentException]
    }

    "reject an empty cocycleIndices" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      CircularCoordinates.computeToroidal(metricSpace, 0.5, Seq.empty) must throwA[IllegalArgumentException]
    }

    "reject duplicate cocycleIndices" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      val bars = CircularCoordinates.h1Bars(metricSpace)
      val r = math.max(bars(0)._1, bars(1)._1) + 1e-6
      CircularCoordinates.computeToroidal(metricSpace, r, Seq(0, 0)) must throwA[IllegalArgumentException]
    }

    "reject an out-of-range cocycleIndex" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      CircularCoordinates.computeToroidal(metricSpace, 0.5, Seq(0, 1000)) must throwA[IllegalArgumentException]
    }

    "reject an r outside the chosen classes' shared alive range" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      val bars = CircularCoordinates.h1Bars(metricSpace)
      val tooEarly = math.min(bars(0)._1, bars(1)._1) - 1.0
      CircularCoordinates.computeToroidal(metricSpace, tooEarly, Seq(0, 1)) must throwA[IllegalArgumentException]
    }
  }
