package org.appliedtopology.tda4j
package matlab

import org.appliedtopology.tda4j.streams.{given, *}

import org.specs2.mutable.Specification

/** Tests the `TDA4j.toroidalCoordinates` MATLAB facade -- that it is a faithful, correctly-marshalled pass-through to
  * `homology.CircularCoordinates.computeToroidal`, which already has its own thorough test suite
  * (`ToroidalCoordinatesSpec`) -- not a re-test of the underlying math, mirroring `CircularCoordinatesResultSpec`'s own
  * scope exactly.
  */
class ToroidalCoordinatesResultSpec extends Specification:
  /** Same wedge-of-two-circles fixture as `homology.ToroidalCoordinatesSpec` -- see that file's own doc for why this
    * particular construction (unequal radii, a single controlled near-touching point) reliably gives two
    * simultaneously-alive, same-component H^1 classes with no randomness anywhere.
    */
  private def wedgeOfTwoCircles(n: Int = 14): Array[Array[Double]] =
    val circleA = Array.tabulate(n) { i =>
      val a = 2 * math.Pi * i / n
      Array(math.cos(a), math.sin(a))
    }
    val circleB = Array.tabulate(n) { i =>
      val a = math.Pi + 2 * math.Pi * i / n
      Array(1.55 + 0.6 * math.cos(a), 0.6 * math.sin(a))
    }
    circleA ++ circleB

  "toroidalCoordinates" should {
    "matches homology.CircularCoordinates.computeToroidal directly, point for point, coordinate for coordinate" >> {
      val points = wedgeOfTwoCircles()
      val bars = TDA4j.h1Bars(points)
      val r = math.max(bars(0)(0), bars(1)(0)) + 1e-6

      val viaFacade = TDA4j.toroidalCoordinates(points, r, Array(0, 1), 47, true)
      val viaDirect =
        homology.CircularCoordinates.computeToroidal(EuclideanMetricSpace(points), r, Seq(0, 1), 47, true)

      (viaFacade.dimension() must beEqualTo(2)) and
        (viaFacade.cocycleIndices().toSeq must beEqualTo(Seq(0, 1))) and
        (viaFacade.basisChange().map(_.toSeq).toSeq must beEqualTo(viaDirect.basisChange.map(_.toSeq).toSeq)) and
        forall(0 until 2) { c =>
          forall(0 until points.length) { i =>
            if viaDirect.theta(c).contains(i) then
              (viaFacade.hasCoordinate(i) must beTrue) and (viaFacade.theta(c)(i) must beEqualTo(viaDirect.theta(c)(i)))
            else viaFacade.theta(c)(i).isNaN must beTrue
          }
        }
    }

    "the 3-argument overload matches the explicit default (prime=47, reduce=true)" >> {
      val points = wedgeOfTwoCircles()
      val bars = TDA4j.h1Bars(points)
      val r = math.max(bars(0)(0), bars(1)(0)) + 1e-6

      val short = TDA4j.toroidalCoordinates(points, r, Array(0, 1))
      val long = TDA4j.toroidalCoordinates(points, r, Array(0, 1), 47, true)
      (short.theta(0).toSeq must beEqualTo(long.theta(0).toSeq)) and
        (short.theta(1).toSeq must beEqualTo(long.theta(1).toSeq))
    }

    "reduce=false gives the identity basisChange and matches circularCoordinates called per class" >> {
      val points = wedgeOfTwoCircles()
      val bars = TDA4j.h1Bars(points)
      val r = math.max(bars(0)(0), bars(1)(0)) + 1e-6

      val toroidal = TDA4j.toroidalCoordinates(points, r, Array(0, 1), 47, false)
      val circ0 = TDA4j.circularCoordinates(points, r, 0, 47)
      val circ1 = TDA4j.circularCoordinates(points, r, 1, 47)

      (toroidal.basisChange().map(_.toSeq).toSeq must beEqualTo(Seq(Seq(1, 0), Seq(0, 1)))) and
        (toroidal.theta(0).toSeq must beEqualTo(circ0.theta().toSeq)) and
        (toroidal.theta(1).toSeq must beEqualTo(circ1.theta().toSeq))
    }

    "exposes originalGram/reducedGram as symmetric k x k matrices" >> {
      val points = wedgeOfTwoCircles()
      val bars = TDA4j.h1Bars(points)
      val r = math.max(bars(0)(0), bars(1)(0)) + 1e-6

      val result = TDA4j.toroidalCoordinates(points, r, Array(0, 1))
      val og = result.originalGram()
      val rg = result.reducedGram()
      (og.length must beEqualTo(2)) and (og(0).length must beEqualTo(2)) and
        (rg.length must beEqualTo(2)) and (rg(0).length must beEqualTo(2)) and
        (og(0)(1) must beEqualTo(og(1)(0))) and (rg(0)(1) must beEqualTo(rg(1)(0)))
    }
  }
