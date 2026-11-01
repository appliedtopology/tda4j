package org.appliedtopology.tda4j

import org.specs2.mutable.Specification
import org.appliedtopology.tda4j.SpecThrows.throwWith

/** Perea's sparse circular coordinates (`CircularCoordinates.sparse`, `numLandmarks = n`). The invariant is that the
  * extension from the landmarks is well defined: a point gets the same value from every landmark ball that holds it,
  * which makes the coordinate continuous where the nearest landmark changes. A sign or orientation error breaks exactly
  * that, so the circle example bounds every step between neighbouring points, besides the winding number. The torus
  * checks the toroidal wiring by degrees; the last examples tie the two entry points together and check the errors.
  */
class SparseCircularCoordinatesSpec extends Specification:

  private def wrapped(x: Double): Double = x - math.rint(x)
  private def circle(n: Int): Array[Array[Double]] =
    Array.tabulate(n)(i => Array(math.cos(2 * math.Pi * i / n), math.sin(2 * math.Pi * i / n)))

  "on a circle of 300 points from 12 landmarks: a coordinate for every point, continuous, going once around" >> {
    val metricSpace = EuclideanMetricSpace(circle(300))
    val (birth, death) = CircularCoordinates.h1Bars(metricSpace, numLandmarks = 12).head
    val result = CircularCoordinates.compute(metricSpace, (birth + death) / 2, numLandmarks = 12)
    val steps = (0 until 300).map(i => wrapped(result.theta((i + 1) % 300) - result.theta(i)))
    // Uniform steps would be 1/300; a value taken from the wrong ball jumps by a landmark edge's share, about 1/12.
    (result.theta.keySet must beEqualTo((0 until 300).toSet)) and
      (steps.map(math.abs).max must beLessThan(0.02)) and
      (math.abs(math.abs(steps.sum) - 1.0) must beLessThan(1e-6))
  }

  "on a flat torus: two coordinates whose degrees around its two circles form an invertible integer matrix" >> {
    val m = 20
    def index(i: Int, j: Int) = i * m + j
    val angle = 2 * math.Pi / m
    val torus = Array.tabulate(m * m) { p =>
      val (i, j) = (p / m, p % m)
      Array(math.cos(i * angle), math.sin(i * angle), math.cos(j * angle), math.sin(j * angle))
    }
    val metricSpace = EuclideanMetricSpace(torus)
    val bars = CircularCoordinates.h1Bars(metricSpace, numLandmarks = 36)
    val lo = math.max(bars(0)._1, bars(1)._1)
    val hi = math.min(bars(0)._2, bars(1)._2)
    val result = CircularCoordinates.computeToroidal(metricSpace, (lo + hi) / 2, Seq(0, 1), numLandmarks = 36)
    def degree(c: Int, alongFirst: Boolean, fixed: Int): Long =
      val theta = result.theta(c)
      math.round((0 until m).map { t =>
        val (a, b) =
          if alongFirst then (index(t, fixed), index((t + 1) % m, fixed))
          else (index(fixed, t), index(fixed, (t + 1) % m))
        wrapped(theta(b) - theta(a))
      }.sum)
    val degrees = Seq(0, 1).map(c => (degree(c, true, 0), degree(c, false, 0)))
    val sameOnEveryCircle =
      (0 until m).forall(f => Seq(0, 1).forall(c => (degree(c, true, f), degree(c, false, f)) == degrees(c)))
    val determinant = degrees(0)._1 * degrees(1)._2 - degrees(0)._2 * degrees(1)._1
    (result.theta.forall(_.size == m * m) must beTrue) and
      (sameOnEveryCircle must beTrue) and
      (math.abs(determinant) must beEqualTo(1L))
  }

  "sparse on a Dowker diagram of your own landmarks gives compute's coordinate with the same landmarks, up to rotation" >> {
    val metricSpace = EuclideanMetricSpace(circle(120))
    val relation = WitnessGeometry(metricSpace, LandmarkSelector.maxmin(metricSpace, 10).landmarks).D
    val diagram = Persistence(Dowker(relation, maxDimension = 1), characteristic = 47)
    val fromDiagram = CircularCoordinates.sparse(diagram, relation)
    val fromPoints = CircularCoordinates.compute(metricSpace, fromDiagram.r, numLandmarks = 10)
    val shift = fromDiagram.theta(0) - fromPoints.theta(0)
    (fromPoints.theta.keySet must beEqualTo(fromDiagram.theta.keySet)) and
      ((0 until 120).forall(i => math.abs(wrapped(fromDiagram.theta(i) - fromPoints.theta(i) - shift)) < 1e-6) must
        beTrue)
  }

  "the errors: more landmarks than points, and r outside the bar" >> {
    val metricSpace = EuclideanMetricSpace(circle(40))
    val (birth, _) = CircularCoordinates.h1Bars(metricSpace, numLandmarks = 8).head
    (CircularCoordinates.compute(metricSpace, 0.5, numLandmarks = 41) must
      throwWith[IllegalArgumentException]("at most the number of points")) and
      (CircularCoordinates.compute(metricSpace, birth / 2, numLandmarks = 8) must
        throwWith[IllegalArgumentException]("must lie in"))
  }
