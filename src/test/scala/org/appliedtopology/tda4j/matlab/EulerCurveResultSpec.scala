package org.appliedtopology.tda4j
package matlab

import org.appliedtopology.tda4j.*

import org.specs2.mutable.Specification

/** `TDA4j.eulerCurveFromPoints` and `eulerCurveFromDistanceMatrix`, the MATLAB, command-line and JavaScript way to
  * `EulerCharacteristicCurve`: the same curve as the library call with the same arguments (the library's own tests
  * check the curve against a count of a stream's cells), each option reaching it, and the options and inputs refused
  * with a message that says what to change.
  */
class EulerCurveResultSpec extends Specification:
  private def cloud(n: Int, dim: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  private val points = cloud(24, 3, 5L)

  /** The message of the `IllegalArgumentException` `body` throws, if it throws one. */
  private def refusal(body: => Any): Option[String] =
    try
      body
      None
    catch case e: IllegalArgumentException => Some(e.getMessage)

  private def curveOf(r: EulerCurveResult): (Seq[Double], Seq[Long]) = (r.scales().toSeq, r.values().toSeq)
  private def curveOf(c: EulerCharacteristicCurve): (Seq[Double], Seq[Long]) = (c.scales, c.values)

  "eulerCurveFromPoints" should {
    "be the unit square's curve by hand: 4 points, then 4 edges, then the diagonals and the 4 triangles" in {
      val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val r = TDA4j.eulerCurveFromPoints(square)
      // 4 vertices; at 1 the 4 edges (4 - 4); at sqrt 2 the 2 diagonals and 4 triangles (0 - 2 + 4)
      (curveOf(r) must beEqualTo((Seq(0.0, 1.0, math.sqrt(2.0)), Seq(4L, 0L, 2L))))
        .and(r.at(0.5) must beEqualTo(4L))
        .and(r.at(1.0) must beEqualTo(0L))
        .and(r.at(10.0) must beEqualTo(2L))
        .and(r.at(-1.0) must beEqualTo(0L))
    }

    "be the library's curve of the same points" in {
      curveOf(TDA4j.eulerCurveFromPoints(points)) must beEqualTo(curveOf(EulerCharacteristicCurve(points)))
    }

    "pass maxSimplexDimension on: each value gives the library's curve, and they differ" in {
      def viaFacade(d: Int) =
        curveOf(TDA4j.eulerCurveFromPoints(points, Array("maxSimplexDimension", d.toString)))
      def viaLibrary(d: Int) = curveOf(EulerCharacteristicCurve(points, maxSimplexDimension = d))
      (viaFacade(1) must beEqualTo(viaLibrary(1)))
        .and(viaFacade(3) must beEqualTo(viaLibrary(3)))
        .and(viaFacade(1) must not(beEqualTo(viaFacade(3))))
        // the default is 2
        .and(curveOf(TDA4j.eulerCurveFromPoints(points)) must beEqualTo(viaLibrary(2)))
    }

    "pass maxFiltrationValue on, in any capitalisation of the key" in {
      val cut = 0.4
      val r = TDA4j.eulerCurveFromPoints(points, Array("MaxFiltrationValue", cut.toString))
      (curveOf(r) must beEqualTo(curveOf(EulerCharacteristicCurve(points, maxFiltrationValue = cut))))
        .and(r.scales().last must beLessThanOrEqualTo(cut))
        .and(r.scales().length must beLessThan(TDA4j.eulerCurveFromPoints(points).scales().length))
    }

    "not depend on the number of threads" in {
      curveOf(TDA4j.eulerCurveFromPoints(points, Array("threads", "1"))) must
        beEqualTo(curveOf(TDA4j.eulerCurveFromPoints(points, Array("threads", "4"))))
    }

    "sample like the library: resolution points across [tMin, tMax], both ends included" in {
      val r = TDA4j.eulerCurveFromPoints(points)
      val samples = r.sample(0.0, 0.8, 9)
      (samples.toSeq must beEqualTo(EulerCharacteristicCurve(points).sample(0.0, 0.8, 9).toSeq))
        .and(samples.length must beEqualTo(9))
        .and(samples.head must beEqualTo(points.length.toLong))
    }
  }

  "eulerCurveFromDistanceMatrix" should {
    "give the curve of the points it is the distance matrix of" in {
      val space = EuclideanMetricSpace(points)
      val distances = Array.tabulate(points.length, points.length)(space.distance(_, _))
      curveOf(TDA4j.eulerCurveFromDistanceMatrix(distances)) must beEqualTo(curveOf(TDA4j.eulerCurveFromPoints(points)))
    }

    "pass the options on and refuse a matrix that is not square" in {
      val distances = Array(Array(0.0, 1.0, 2.0), Array(1.0, 0.0, 1.0), Array(2.0, 1.0, 0.0))
      val r =
        TDA4j.eulerCurveFromDistanceMatrix(distances, Array("maxSimplexDimension", "1", "maxFiltrationValue", "2"))
      // 3 vertices, 2 edges at 1, the long edge at 2: 3 - 2 = 1 at scale 1, then 3 - 3 = 0
      (curveOf(r) must beEqualTo((Seq(0.0, 1.0, 2.0), Seq(3L, 1L, 0L))))
        // by default the scales stop at the enclosing radius, here 1, so the long edge never comes
        .and(curveOf(TDA4j.eulerCurveFromDistanceMatrix(distances)) must beEqualTo((Seq(0.0, 1.0), Seq(3L, 1L))))
        .and(refusal(TDA4j.eulerCurveFromDistanceMatrix(Array(Array(0.0, 1.0)))) must beSome(contain("square")))
    }
  }

  "the options and the input" should {
    "refuse an option that is not one of the three, naming them" in {
      refusal(TDA4j.eulerCurveFromPoints(points, Array("maxDimension", "2"))) must
        beSome(contain("maxfiltrationvalue, maxsimplexdimension, threads"))
    }

    "refuse a value that is not a number, a negative dimension, no points, and options of odd length" in
      (refusal(TDA4j.eulerCurveFromPoints(points, Array("maxSimplexDimension", "two"))) must
        beSome(contain("maxSimplexDimension")))
        .and(
          refusal(TDA4j.eulerCurveFromPoints(points, Array("maxFiltrationValue", "far"))) must
            beSome(contain("maxFiltrationValue"))
        )
        .and(
          refusal(TDA4j.eulerCurveFromPoints(points, Array("maxSimplexDimension", "-1"))) must
            beSome(contain("maxSimplexDimension"))
        )
        .and(refusal(TDA4j.eulerCurveFromPoints(Array.empty[Array[Double]])) must beSome(contain("at least one row")))
        .and(refusal(TDA4j.eulerCurveFromPoints(points, Array("threads"))) must beSome(contain("even length")))
        .and(refusal(TDA4j.eulerCurveFromPoints(points, Array("threads", "0"))) must beSome(contain("threads")))
  }
