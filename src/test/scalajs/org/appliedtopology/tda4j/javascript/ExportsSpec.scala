package org.appliedtopology.tda4j.javascript

import org.appliedtopology.tda4j.SpecThrows.throwWith
import org.specs2.mutable.Specification

import scala.scalajs.js

/** The functions the npm package exports beyond `persistence`: landmarks, circular and toroidal coordinates, the text
  * parsers, and the persistence image and boundary matrix of a `Result`.
  */
class ExportsSpec extends Specification:

  private def circle(n: Int): js.Array[js.Array[Double]] =
    js.Array((0 until n).map(i => js.Array(math.cos(2 * math.Pi * i / n), math.sin(2 * math.Pi * i / n)))*)

  private def options(entries: (String, js.Any)*): js.Dictionary[js.Any] = js.Dictionary(entries*)

  private def dynamic(o: js.Object): js.Dynamic = o.asInstanceOf[js.Dynamic]

  "selectLandmarks" should {
    "return the landmarks and the covering radius, which persistenceFromLandmarks accepts" >> {
      val points = circle(40)
      val selection = dynamic(Exports.selectLandmarks(points, options("numLandmarks" -> 8)))
      val landmarks = selection.landmarks.asInstanceOf[js.Array[Int]]
      val radius = selection.coveringRadius.asInstanceOf[Double]
      val result = Exports.persistenceFromLandmarks(
        points,
        landmarks,
        options("maxDimension" -> 1, "maxFiltrationValue" -> 2 * radius)
      )
      (landmarks.length must beEqualTo(8)) and (landmarks.toSet.size must beEqualTo(8)) and (radius must beGreaterThan(
        0.0
      )) and
        (Exports.coveringRadius(points, landmarks) must beEqualTo(radius)) and
        (result.bars.exists(bar => dynamic(bar).dim.asInstanceOf[Int] == 1) must beTrue)
    }
    "say what is missing" >> {
      Exports.selectLandmarks(circle(10)) must throwA[js.JavaScriptException]
    }
  }

  "circularCoordinates" should {
    "wind once round a circle: consecutive points differ by a 40th of a turn" >> {
      val n = 40
      val points = circle(n)
      val loops = Exports.h1Bars(points)
      val birth = loops(0)(0)
      val death = loops(0)(1)
      val coordinates = dynamic(Exports.circularCoordinates(points, (birth + death) / 2))
      val theta = coordinates.theta.asInstanceOf[js.Array[Double]]
      val steps = (0 until n).map { i =>
        val d = (theta((i + 1) % n) - theta(i) + 1.0) % 1.0
        math.min(d, 1.0 - d)
      }
      (loops.length must beGreaterThan(0)) and (theta.length must beEqualTo(n)) and
        (theta.forall(t => t >= 0.0 && t < 1.0) must beTrue) and
        (steps.forall(s => math.abs(s - 1.0 / n) < 0.02) must beTrue) and
        (coordinates.prime.asInstanceOf[Int] must beEqualTo(47))
    }
    "refuse an option it does not have" >> {
      Exports.circularCoordinates(circle(12), 0.5, options("primes" -> 5)) must throwWith[js.JavaScriptException](
        "unknown option primes"
      )
    }
  }

  "toroidalCoordinates" should
    "give one coordinate for one loop, with the identity as its change of basis" >> {
      val points = circle(40)
      val loops = Exports.h1Bars(points)
      val result = dynamic(Exports.toroidalCoordinates(points, (loops(0)(0) + loops(0)(1)) / 2, js.Array(0)))
      (result.theta.asInstanceOf[js.Array[js.Array[Double]]].length must beEqualTo(1)) and
        (result.basisChange.asInstanceOf[js.Array[js.Array[Int]]].map(_.toSeq).toSeq must beEqualTo(Seq(Seq(1))))
    }

  "the text parsers" should {
    "read a point cloud and a distance matrix" >> {
      val points = Exports.parsePointCloud("0,0\n1 0\n\n0 1\n")
      val lower = Exports.parseDistanceMatrix("1\n2 3\n", "lower")
      (points.map(_.toSeq).toSeq must beEqualTo(Seq(Seq(0.0, 0.0), Seq(1.0, 0.0), Seq(0.0, 1.0)))) and
        (lower.map(_.toSeq).toSeq must beEqualTo(Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0))))
    }
    "read a sparse matrix with Infinity for a pair that is not listed" >> {
      val m = Exports.parseDistanceMatrix("0 2 5\n", "ripser-sparse")
      (m.length must beEqualTo(3)) and (m(0)(2) must beEqualTo(5.0)) and (m(0)(1) must beEqualTo(
        Double.PositiveInfinity
      ))
    }
    "name the formats when asked for another" >> {
      Exports.parseDistanceMatrix("0", "nope") must throwWith[js.JavaScriptException]("unrecognized format 'nope'")
    }
  }

  "a Result" should {
    val result = Exports.persistence(circle(30), options("maxDimension" -> 1))
    "give the persistence image of its bars" >> {
      val image = result.persistenceImage(1, 0.1, 0.0, 1.0, 0.0, 2.0, 5, 6)
      (image.length must beEqualTo(5)) and (image.forall(_.length == 6) must beTrue) and
        (image.flatMap(_.toSeq).exists(_ > 0.0) must beTrue)
    }
    "export its boundary matrix, columns in filtration order" >> {
      val boundary = dynamic(result.boundary())
      val rows = boundary.rows.asInstanceOf[js.Array[Int]]
      val cols = boundary.cols.asInstanceOf[js.Array[Int]]
      val values = boundary.values.asInstanceOf[js.Array[Double]]
      (result.numCells must beGreaterThan(30)) and (rows.length must beEqualTo(cols.length)) and
        (values.length must beEqualTo(rows.length)) and (rows.zip(cols).forall((r, c) => r < c) must beTrue) and
        (result.columnDimension(0) must beEqualTo(0)) and (result.columnVertices(0).length must beEqualTo(1)) and
        (result.columnFiltrationValue(0) must beEqualTo(0.0))
    }
  }
