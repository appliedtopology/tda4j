package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

import java.io.{File, StringWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** A saved result (`PersistenceResult.save`, `TDA4j.loadResult`) is the result it was: the same bars, hidden ones
  * included, the same threshold, the same representatives and input -- so the same plots, byte for byte -- and saving
  * it again writes the same file.
  */
class ResultFileSpec extends Specification:
  sequential

  private def tempPath(suffix: String): String =
    val f = File.createTempFile("result-file-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def read(path: String): String = Files.readString(Paths.get(path), StandardCharsets.UTF_8)

  private def roundTrip(r: PersistenceResult): PersistenceResult =
    val path = tempPath(".json")
    r.save(path)
    TDA4j.loadResult(path)

  /** Each SVG render numbers its clip path; two renders of one figure differ only there. */
  private def svg(f: plot.Plot): String = f.svgFile(plot.Theme.Light).replaceAll("tda4j[0-9]+", "tda4jN")

  // 40 points near a circle of radius 1: one long H1 bar and, under the default threshold, hidden short ones.
  private val noisyCircle =
    val rng = scala.util.Random(7)
    Array.tabulate(40) { i =>
      val t = 2 * math.Pi * i / 40
      val r = 1 + 0.15 * (rng.nextDouble() - 0.5)
      Array(r * math.cos(t), r * math.sin(t))
    }

  private def sameArrays(a: Array[Array[Double]], b: Array[Array[Double]]): Boolean =
    a.length == b.length && a.indices.forall(i => a(i).sameElements(b(i)))

  "a saved point-cloud result" should {
    // A threshold of 10% of the enclosing radius hides the short H0 bars.
    val original = TDA4j.computeFromPoints(noisyCircle, Array("maxDimension", "1", "minPersistenceFraction", "0.1"))
    val loaded = roundTrip(original)

    "have hidden bars to lose (or this spec would not test the threshold's split)" >> {
      original.hiddenCount() must beGreaterThan(0)
    }
    "report the same bars, hide the same bars and keep the same threshold" >> {
      sameArrays(loaded.toArray(), original.toArray()) must beTrue
      sameArrays(loaded.toArrayUnfiltered(), original.toArrayUnfiltered()) must beTrue
      loaded.hiddenCount() must beEqualTo(original.hiddenCount())
      loaded.persistenceThreshold() must beEqualTo(original.persistenceThreshold())
    }
    "measure every bar, hidden ones too, in distances and vectorizations" >>
      forall(Seq(0, 1)) { d =>
        (loaded.bottleneckDistance(original, d) must beEqualTo(0.0)) and
          (loaded.wassersteinDistance(original, d) must beEqualTo(0.0)) and
          (sameArrays(loaded.landscape(d, 2, 0.0, 1.0, 20), original.landscape(d, 2, 0.0, 1.0, 20)) must beTrue)
      }
    "keep every reported bar's representative, cell for cell" >>
      forall(0 until original.size()) { i =>
        (loaded.cycleVertices(i).map(_.toSeq).toSeq must beEqualTo(original.cycleVertices(i).map(_.toSeq).toSeq)) and
          (loaded.cycleCoefficients(i).toSeq must beEqualTo(original.cycleCoefficients(i).toSeq))
      }
    "draw the same plots" >> {
      val loop = original.mostPersistentBar(1)
      (svg(loaded.plotBarcode()) must beEqualTo(svg(original.plotBarcode()))) and
        (svg(loaded.plotDiagram()) must beEqualTo(svg(original.plotDiagram()))) and
        (svg(loaded.plotRepresentative(loop)) must beEqualTo(svg(original.plotRepresentative(loop)))) and
        (svg(loaded.plotRepresentative(loop, 0.6)) must beEqualTo(svg(original.plotRepresentative(loop, 0.6)))) and
        (svg(loaded.plotInput()) must beEqualTo(svg(original.plotInput()))) and
        (svg(loaded.plotComplex(0.4)) must beEqualTo(svg(original.plotComplex(0.4))))
    }
    "save to the same file again" >> {
      val first = tempPath(".json")
      val second = tempPath(".json")
      original.save(first)
      TDA4j.loadResult(first).save(second)
      read(second) must beEqualTo(read(first))
    }
    "not keep the boundary matrix, and say so" >> {
      loaded.numCells() must throwA[UnsupportedOperationException](message = "compute the result again")
    }
  }

  "a saved cocycle result over F_17" should
    "keep -1 as -1, not as 16, and draw the same cocycle" >> {
      val original =
        TDA4j.computeFromPoints(noisyCircle, Array("maxDimension", "1", "representativeType", "cocycles"))
      val loaded = roundTrip(original)
      val loop = original.mostPersistentBar(1)
      val coefficients = (0 until original.size()).flatMap(original.cycleCoefficients(_))
      (coefficients.contains(-1.0) must beTrue) and
        (coefficients.forall(c => math.abs(c) <= 8) must beTrue) and
        ((0 until loaded.size()).flatMap(loaded.cycleCoefficients(_)) must beEqualTo(coefficients)) and
        (svg(loaded.plotRepresentative(loop)) must beEqualTo(svg(original.plotRepresentative(loop))))
    }

  "a saved image result" should {
    // A ring of bright pixels with a masked pixel, and infinities: superlevel, so values are negated internally.
    val values = Array[Double](
      0,
      0,
      0,
      0,
      0, //
      0,
      5,
      5,
      5,
      0, //
      0,
      5,
      Double.NaN,
      5,
      0, //
      0,
      5,
      5,
      Double.PositiveInfinity,
      0, //
      Double.NegativeInfinity,
      0,
      0,
      0,
      0
    )
    val original = TDA4j.computeFromCubicalImage(Array(5, 5), values, Array("sublevel", "false"))
    val path = tempPath(".json")
    original.save(path)
    val loaded = TDA4j.loadResult(path)

    "keep NaN, Infinity and -Infinity pixels, as given (not negated)" >> {
      val text = read(path)
      (text must contain("\"NaN\"")) and (text must contain("\"Infinity\"")) and (text must contain(
        "\"-Infinity\""
      )) and
        (text must contain("\"values\": [0.0,0.0,0.0,0.0,0.0,0.0,5.0"))
    }
    "draw the same image and representatives" >> {
      (svg(loaded.plotInput()) must beEqualTo(svg(original.plotInput()))) and
        forall(0 until original.size())(i =>
          svg(loaded.plotRepresentative(i)) must beEqualTo(svg(original.plotRepresentative(i)))
        )
    }
    "save to the same file again" >> {
      val again = tempPath(".json")
      loaded.save(again)
      read(again) must beEqualTo(read(path))
    }
  }

  "a saved result with no coordinates" should {
    "plot its bars, and say why it cannot plot a representative" >> {
      val distances = Array.tabulate(6, 6)((i, j) => math.abs(i - j).toDouble)
      val loaded = roundTrip(TDA4j.computeFromDistanceMatrix(distances, Array("maxDimension", "1")))
      (loaded.plotBarcode().svg() must contain("<svg")) and
        (loaded.plotRepresentative(0) must throwA[UnsupportedOperationException](message = "a distance matrix"))
    }
    "keep the landmarks of a two-step witness complex" >> {
      val path = tempPath(".json")
      TDA4j.computeFromPointsAndLandmarks(noisyCircle, Array(0, 10, 20, 30), Array("maxDimension", "1")).save(path)
      read(path) must contain("\"landmarks\": [0,10,20,30]")
    }
  }

  "loading a file" should {
    def write(text: String): String =
      val path = tempPath(".json")
      Files.writeString(Paths.get(path), text)
      path

    "accept the minimal file: the format, its version and the bars" >> {
      val r = TDA4j.loadResult(
        write(
          """{"format": "tda4j-persistence-result", "formatVersion": 1,
            | "dimensions": [0, 1], "births": [0, 0.5], "deaths": ["Infinity", 1.5]}""".stripMargin
        )
      )
      (r.size() must beEqualTo(2)) and (r.death(0) must beEqualTo(Double.PositiveInfinity)) and
        (r.plotDiagram().svg() must contain("<svg")) and
        (r.cycleVertices(1) must throwA[UnsupportedOperationException])
    }
    "refuse another kind of JSON file, naming the format it reads" >> {
      TDA4j.loadResult(write("""{"dimensions": []}""")) must throwA[IllegalArgumentException](
        message = "not a saved tda4j result"
      )
    }
    "refuse a newer format version, saying to use a newer tda4j" >> {
      TDA4j.loadResult(
        write(
          """{"format": "tda4j-persistence-result", "formatVersion": 2, "dimensions": [], "births": [], "deaths": []}"""
        )
      ) must throwA[IllegalArgumentException](message = "newer tda4j")
    }
    "say where a truncated file ends" >> {
      TDA4j.loadResult(write("""{"format": "tda4j-persistence-result", "formatVersion": 1, "births": [0.0, """)) must
        throwA[IllegalArgumentException](message = "at character")
    }
    "refuse bar arrays of different lengths" >> {
      TDA4j.loadResult(
        write(
          """{"format": "tda4j-persistence-result", "formatVersion": 1,
            | "dimensions": [0, 1], "births": [0.0], "deaths": [1.0, 2.0]}""".stripMargin
        )
      ) must throwA[IllegalArgumentException](message = "one of each per bar")
    }
    "refuse a missing file with a FileNotFoundException" >> {
      TDA4j.loadResult(tempPath(".json") + "-missing") must throwA[java.io.FileNotFoundException]
    }
  }

  "the JSON writer and reader" should {
    "read back every double exactly, the non-finite ones and -0.0 included" >> {
      val ds = Array(0.1 + 0.2, 1.0 / 3, -0.0, Double.MinPositiveValue, Double.MaxValue, -1e-300, 1e21, 123456789.0) ++
        Array(Double.PositiveInfinity, Double.NegativeInfinity, Double.NaN)
      val out = StringWriter()
      JsonWriter(out).doubles(ds)
      val back = JsonReader(out.toString, "test").doubles()
      back.map(java.lang.Double.doubleToRawLongBits).toSeq must beEqualTo(
        ds.map(java.lang.Double.doubleToRawLongBits).toSeq
      )
    }
    "read back any string" >> {
      val s = "quote \" backslash \\ newline \n tab \t control \u0001 infinity ∞"
      val out = StringWriter()
      JsonWriter(out).string(s)
      JsonReader(out.toString, "test").string() must beEqualTo(s)
    }
  }
