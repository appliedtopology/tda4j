package org.appliedtopology.tda4j.javascript

import org.appliedtopology.tda4j.SpecThrows.throwWith
import org.specs2.mutable.Specification

import scala.scalajs.js

/** The functions the npm package exports beyond `persistence`: landmarks, the Euler curve, circular and toroidal
  * coordinates, the text parsers, and the persistence image and boundary matrix of a `Result`.
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

  "bootstrapBand, tomatoClusters and tomatoDiagram" should {
    "return the band as a plain object and cluster two separated blobs" >> {
      val band = dynamic(Exports.bootstrapBand(circle(30), options("rounds" -> 6, "seed" -> 2)))
      val blobs =
        js.Array((0 until 20).map(i => js.Array(0.1 * i, 0.0)) ++ (0 until 20).map(i => js.Array(50.0 + 0.1 * i, 0.0))*)
      (band.threshold.asInstanceOf[Double] must beEqualTo(2.0 * band.width.asInstanceOf[Double])) and
        (band.distances.asInstanceOf[js.Array[Double]].length must beEqualTo(6)) and
        (Exports.tomatoClusters(blobs, 4, 100.0).toSet.size must beEqualTo(2)) and
        (Exports.tomatoDiagram(blobs, 4).count(_(1).isInfinite) must beEqualTo(2))
    }
    "match a result with itself" >> {
      val result = Exports.persistence(circle(20), options("maxDimension" -> 1))
      result.wassersteinMatching(result, 1).forall(row => row(0) == row(1)) must beTrue
    }
  }

  "takensEmbedding, takensEmbeddingOfVectors and delayByAutocorrelation" should
    "give the windows of a series, with the delay and stride optional, and a delay for a sinusoid" >> {
      val ramp = js.Array((0 to 5).map(_.toDouble)*)
      val sine = js.Array((0 until 60).map(t => math.sin(2 * math.Pi * t / 20))*)
      val rows = js.Array(js.Array(0.0, 10.0), js.Array(1.0, 11.0), js.Array(2.0, 12.0), js.Array(3.0, 13.0))
      (Exports.takensEmbedding(ramp, 3, 2).map(_.toSeq).toSeq must beEqualTo(
        Seq(Seq(0.0, 2.0, 4.0), Seq(1.0, 3.0, 5.0))
      )) and
        (Exports.takensEmbedding(ramp, 2).length must beEqualTo(5)) and
        (Exports.takensEmbedding(ramp, 2, 1, 2).length must beEqualTo(3)) and
        (Exports.takensEmbeddingOfVectors(rows, 2, 2).map(_.toSeq).toSeq must beEqualTo(
          Seq(Seq(0.0, 10.0, 2.0, 12.0), Seq(1.0, 11.0, 3.0, 13.0))
        )) and
        (Exports.delayByAutocorrelation(sine, 15) must beBetween(4, 6)) and
        (Exports.takensEmbedding(js.Array(1.0, 2.0), 3, 2) must throwA[js.JavaScriptException])
    }

  "eulerCurve" should {
    "give the unit square's steps: four vertices, then four edges close a loop, then the diagonals fill it" >> {
      val square = js.Array(js.Array(0.0, 0.0), js.Array(1.0, 0.0), js.Array(1.0, 1.0), js.Array(0.0, 1.0))
      val curve = Exports.eulerCurve(square)
      val sqrt2 = math.sqrt(2.0)
      (curve.scales.toSeq must beEqualTo(Seq(0.0, 1.0, sqrt2))) and
        (curve.values.toSeq must beEqualTo(Seq(4.0, 0.0, 2.0))) and
        (curve.at(0.5) must beEqualTo(4.0)) and (curve.at(-1.0) must beEqualTo(0.0)) and
        (curve.sample(0.0, 2.0, 3).toSeq must beEqualTo(Seq(4.0, 0.0, 2.0)))
    }
    "take a distance matrix and the facade's options, and refuse the ones it does not have" >> {
      val distances = js.Array(js.Array(0.0, 1.0, 2.0), js.Array(1.0, 0.0, 1.0), js.Array(2.0, 1.0, 0.0))
      val curve = Exports.eulerCurveFromDistances(
        distances,
        options("maxSimplexDimension" -> 1, "maxFiltrationValue" -> 2)
      )
      (curve.scales.toSeq must beEqualTo(Seq(0.0, 1.0, 2.0))) and (curve.values.toSeq must beEqualTo(
        Seq(3.0, 1.0, 0.0)
      )) and
        (Exports.eulerCurve(circle(5), options("maxDimension" -> 1)) must throwA[js.JavaScriptException])
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
    "give a Betti curve, a silhouette, an entropy and a sliced Wasserstein distance" >> {
      val curve = result.bettiCurve(1, 0.0, 2.0, 11)
      (curve.length must beEqualTo(11)) and (result.silhouette(1, 0.0, 2.0, 11).length must beEqualTo(11)) and
        (result.persistenceEntropy(0, true) must beBetween(0.0, 1.0)) and
        (result.slicedWassersteinDistance(result, 1) must beEqualTo(0.0))
    }
    "give the persistence image of its bars" >> {
      val image = result.persistenceImage(1, 0.1, 0.0, 1.0, 0.0, 2.0, 5, 6)
      (image.length must beEqualTo(5)) and (image.forall(_.length == 6) must beTrue) and
        (image.flatMap(_.toSeq).exists(_ > 0.0) must beTrue)
    }
    "give the cells that open and close a bar: a loop's edge and its triangle, an essential bar's vertex and no death" >> {
      val bars = result.bars
      val loop = bars.indexWhere(b => dynamic(b).dim.asInstanceOf[Int] == 1)
      val essential = bars.indexWhere(b => dynamic(b).death.asInstanceOf[Double].isInfinite)
      val loopCells = dynamic(result.barCells(loop))
      val essentialCells = dynamic(result.barCells(essential))
      (loop must be_>=(0)) and
        (loopCells.birth.asInstanceOf[js.Array[Int]].length must beEqualTo(2)) and
        (loopCells.death.asInstanceOf[js.Array[Int]].length must beEqualTo(3)) and
        (essentialCells.birth.asInstanceOf[js.Array[Int]].length must beEqualTo(1)) and
        (essentialCells.death == null must beTrue)
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
