package org.appliedtopology.tda4j
package matlab

import org.appliedtopology.tda4j.*

import org.specs2.mutable.Specification

/** The `complex` argument of `TDA4j.h1Bars`, `circularCoordinates` and `toroidalCoordinates` (#237): `"cech"` and
  * `"alpha"` read the loops of the diagram of that complex, `"vr"` is the route the shorter forms take.
  *
  * The oracle is the geometry again (the coordinate of the one big loop of a noisy circle follows the true angle) and
  * the difference of the complexes: a Cech or alpha loop is born and dies at radii where the Vietoris-Rips loop of the
  * same points is born and dies at diameters, so a facade that ignored `complex` returns the wrong bar, and a scale
  * taken from the right bar then lies outside the wrong one.
  */
class CircularCoordinatesComplexSpec extends Specification:
  private val prime = 47
  private val n = 23
  private val noisyCircle: Array[Array[Double]] =
    val rng = new scala.util.Random(2026)
    Array.tabulate(n) { i =>
      val angle = 2 * math.Pi * i / n
      val radius = 1.0 + 0.05 * (rng.nextDouble() - 0.5)
      Array(radius * math.cos(angle), radius * math.sin(angle))
    }

  private def trueAngle(i: Int): Double =
    val a = math.atan2(noisyCircle(i)(1), noisyCircle(i)(0)) / (2 * math.Pi)
    a - math.floor(a)

  private def resultantLength(xs: Seq[Double]): Double =
    val cos = xs.map(x => math.cos(2 * math.Pi * x)).sum
    val sin = xs.map(x => math.sin(2 * math.Pi * x)).sum
    math.sqrt(cos * cos + sin * sin) / xs.size

  // how well `theta` follows the true angle, in the better of the two orientations
  private def agreement(theta: Array[Double]): Double =
    math.max(
      resultantLength(theta.indices.map(i => theta(i) - trueAngle(i))),
      resultantLength(theta.indices.map(i => theta(i) + trueAngle(i)))
    )

  // two circles of unequal radii touching at one point: two independent loops, so the right answer has a diagonal Gram
  // matrix (the diagram spec uses the same cloud)
  val m = 14
  val circleA = Array.tabulate(m)(i => Array(math.cos(2 * math.Pi * i / m), math.sin(2 * math.Pi * i / m)))
  val circleB = Array.tabulate(m) { i =>
    val a = math.Pi + 2 * math.Pi * i / m
    Array(1.55 + 0.6 * math.cos(a), 0.6 * math.sin(a))
  }
  val wedge = circleA ++ circleB

  private def messageOf(thunk: => Any): String =
    scala.util.Try(thunk).failed.toOption.collect { case e: IllegalArgumentException => e.getMessage }.getOrElse("")

  private def loopOf(bars: Array[Array[Double]]): (Double, Double) = (bars(0)(0), bars(0)(1))

  for (name, complex) <- Seq("cech" -> Cech, "alpha" -> AlphaShapes) do
    s"h1Bars of complex '$name'" should {
      "be the loops of the diagram of that complex, not those of the Vietoris-Rips complex" >> {
        val facade = TDA4j.h1Bars(noisyCircle, 0, name)
        val diagram = Persistence(
          noisyCircle,
          maxDimension = 1,
          complex = complex,
          characteristic = prime,
          representatives = Representatives.Cocycles
        )
        val direct = CircularCoordinates.h1Bars(diagram)
        facade.map(row => (row(0), row(1))).toSeq must beEqualTo(direct.toSeq)
        // radii against diameters: the big loop is born at about half the scale
        loopOf(facade)._1 must beLessThan(0.75 * loopOf(TDA4j.h1Bars(noisyCircle))._1)
      }

      "give coordinates that follow the true angle, over the bar of that complex" >> {
        val (birth, death) = loopOf(TDA4j.h1Bars(noisyCircle, 0, name))
        val r = birth + 0.25 * (death - birth)
        val result = TDA4j.circularCoordinates(noisyCircle, r, 0, prime, 0, name)
        (result.birth(), result.death(), result.r(), result.prime()) must beEqualTo((birth, death, r, prime))
        result.theta().count(!_.isNaN) must beEqualTo(n)
        agreement(result.theta()) must beGreaterThan(0.99)
      }

      "compute over the field it is given" >> {
        val (birth, death) = loopOf(TDA4j.h1Bars(noisyCircle, 0, name, 101))
        val result = TDA4j.circularCoordinates(noisyCircle, birth + 0.5 * (death - birth), 0, 101, 0, name)
        result.prime() must beEqualTo(101)
        agreement(result.theta()) must beGreaterThan(0.99)
      }

      "be the coordinates the Scala entry point gives, value for value" >> {
        val (birth, death) = loopOf(TDA4j.h1Bars(noisyCircle, 0, name))
        val r = birth + 0.5 * (death - birth)
        val facade = TDA4j.circularCoordinates(noisyCircle, r, 0, prime, 0, name)
        val diagram = Persistence(
          noisyCircle,
          maxDimension = 1,
          complex = complex,
          characteristic = prime,
          representatives = Representatives.Cocycles
        )
        val direct = CircularCoordinates(diagram, noisyCircle, bar = 0, r = r, complex = complex)
        val facadeTheta = facade.theta().toSeq
        facadeTheta must beEqualTo(noisyCircle.indices.map(i => direct.theta.getOrElse(i, Double.NaN)))
      }
    }

  "circularCoordinates of complex 'vr'" should {
    "be the shorter form's result, which does not read a diagram" >> {
      val (birth, death) = loopOf(TDA4j.h1Bars(noisyCircle))
      val r = birth + 0.5 * (death - birth)
      TDA4j.h1Bars(noisyCircle, 0, "vr").map(_.toSeq).toSeq must beEqualTo(TDA4j.h1Bars(noisyCircle).map(_.toSeq).toSeq)
      val long = TDA4j.circularCoordinates(noisyCircle, r, 0, prime, 0, "vr")
      long.theta().toSeq must beEqualTo(TDA4j.circularCoordinates(noisyCircle, r, 0, prime).theta().toSeq)
    }

    "take the landmarks as the shorter form does" >> {
      val sparse = TDA4j.h1Bars(noisyCircle, 12, "vr").map(_.toSeq).toSeq
      sparse must beEqualTo(TDA4j.h1Bars(noisyCircle, 12).map(_.toSeq).toSeq)
    }
  }

  "circularCoordinates of complex 'cech' on two loops" should
    "take the loop it is asked for" >> {
      val bars = TDA4j.h1Bars(wedge, 0, "cech")
      val (birth, death) = (bars(1)(0), bars(1)(1))
      val r = birth + 0.5 * (death - birth)
      val facade = TDA4j.circularCoordinates(wedge, r, 1, prime, 0, "cech")
      (facade.birth(), facade.death()) must beEqualTo((birth, death))
      val diagram = Persistence(
        wedge,
        maxDimension = 1,
        complex = Cech,
        characteristic = prime,
        representatives = Representatives.Cocycles
      )
      val direct = CircularCoordinates(diagram, wedge, bar = 1, r = r, complex = Cech)
      facade.theta().toSeq must beEqualTo(wedge.indices.map(i => direct.theta.getOrElse(i, Double.NaN)))
    }

  "toroidalCoordinates of complex 'cech'" should
    "be the toroidal coordinates of the Cech diagram, value for value, and not those of the Vietoris-Rips complex" >> {
      val bars = TDA4j.h1Bars(wedge, 0, "cech")
      val both = bars.take(2)
      // alive together: the later birth to the earlier death
      val r = (both.map(_(0)).max + both.map(_(1)).min) / 2
      val facade = TDA4j.toroidalCoordinates(wedge, r, Array(0, 1), prime, true, 0, "cech")
      val diagram = Persistence(
        wedge,
        maxDimension = 1,
        complex = Cech,
        characteristic = prime,
        representatives = Representatives.Cocycles
      )
      val direct = CircularCoordinates.toroidal(diagram, wedge, bars = Seq(0, 1), r = r, complex = Cech)
      facade.dimension() must beEqualTo(2)
      facade.cocycleIndices().toSeq must beEqualTo(direct.cocycleIndices)
      facade.theta(0).toSeq must beEqualTo(wedge.indices.map(i => direct.theta(0).getOrElse(i, Double.NaN)))
      facade.theta(1).toSeq must beEqualTo(wedge.indices.map(i => direct.theta(1).getOrElse(i, Double.NaN)))
      // the Vietoris-Rips loops are other bars, so a facade that ignored `complex` could not give these
      TDA4j.h1Bars(wedge).take(2).map(_.toSeq).toSeq must not(beEqualTo(both.map(_.toSeq).toSeq))
    }

  "the complex argument" should {
    "refuse landmarks for a complex that has no sparse form, saying what to do" >> {
      messageOf(TDA4j.h1Bars(noisyCircle, 5, "cech")) must contain("no sparse form")
      messageOf(TDA4j.circularCoordinates(noisyCircle, 0.5, 0, prime, 5, "alpha")) must contain("numLandmarks=0")
      messageOf(TDA4j.toroidalCoordinates(noisyCircle, 0.5, Array(0), prime, true, 5, "cech")) must
        contain("no sparse form")
    }

    "refuse the complexes whose loops the coordinates cannot read, naming the three that work" >> {
      messageOf(TDA4j.h1Bars(noisyCircle, 0, "witness")) must contain("'vr', 'cech' or 'alpha'")
      messageOf(TDA4j.circularCoordinates(noisyCircle, 0.5, 0, prime, 0, "dtm-rips")) must contain(
        "'vr', 'cech' or 'alpha'"
      )
    }

    "refuse a name that is no complex" >> {
      messageOf(TDA4j.h1Bars(noisyCircle, 0, "nonsense")) must contain("unrecognized complex 'nonsense'")
    }

    "refuse the characteristic 2, as the Scala entry point does" >> {
      messageOf(TDA4j.circularCoordinates(noisyCircle, 0.5, 0, 2, 0, "cech")) must contain("odd prime")
    }
  }
