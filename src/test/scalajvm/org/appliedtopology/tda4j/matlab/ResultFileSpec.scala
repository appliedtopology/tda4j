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

  /** Equal bit for bit: `-0.0` is not `0.0`, and a `NaN` equals itself. */
  private def sameArrays(a: Array[Array[Double]], b: Array[Array[Double]]): Boolean =
    def bits(x: Array[Array[Double]]) = x.toSeq.map(_.toSeq.map(java.lang.Double.doubleToRawLongBits))
    bits(a) == bits(b)

  /** The `"reported"` indices of a saved file. */
  private def reportedOf(path: String): Seq[Int] =
    "\"reported\": \\[([0-9,]*)\\]".r
      .findFirstMatchIn(read(path))
      .get
      .group(1)
      .split(",")
      .filter(_.nonEmpty)
      .map(_.toInt)
      .toSeq

  "a saved point-cloud result" should {
    // A threshold of 10% of the enclosing radius hides the short H0 bars.
    val original = TDA4j.computeFromPoints(noisyCircle, Array("maxDimension", "1", "minPersistenceFraction", "0.1"))
    val loaded = roundTrip(original)

    "have hidden bars to lose, one before a reported bar (or this spec would not test the threshold's split)" >> {
      val path = tempPath(".json")
      original.save(path)
      val reported = reportedOf(path)
      (original.hiddenCount() must beGreaterThan(0)) and
        (reported must not(beEqualTo(reported.indices.toSeq))) // some hidden bar comes before a reported one
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
    "keep the cells that open and close every bar, the hidden bars' too" >> {
      val path = tempPath(".json")
      original.save(path)
      val keptAll = "\"birthCells\": \\[".r.findFirstIn(read(path)).isDefined
      (keptAll must beTrue) and
        (original.birthCell(0).length must beGreaterThan(0)) and
        forall(0 until original.size()) { i =>
          (loaded.birthCell(i).toSeq must beEqualTo(original.birthCell(i).toSeq)) and
            (loaded.deathCell(i).toSeq must beEqualTo(original.deathCell(i).toSeq))
        }
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
      (loaded.numCells() must throwA[UnsupportedOperationException](message = "compute the result again")) and
        (loaded.boundaryRows() must throwA[UnsupportedOperationException](message = "compute the result again"))
    }
    "hand out copies of its representatives, as a computed result does" >> {
      val loop = loaded.mostPersistentBar(1)
      val before = loaded.cycleVertices(loop)(0)(0)
      loaded.cycleVertices(loop)(0)(0) = -7
      loaded.cycleCoefficients(loop)(0) = 99.0
      (loaded.cycleVertices(loop)(0)(0) must beEqualTo(before)) and
        (loaded.cycleCoefficients(loop)(0) must not(beEqualTo(99.0)))
    }
    "record the version of tda4j that wrote it" >> {
      val path = tempPath(".json")
      original.save(path)
      read(path) must beMatching("(?s).*\"tda4jVersion\": \"[0-9]+\\.[0-9]+\\.[0-9]+.*")
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

  "a saved superlevel image peaking at 0" should
    "keep its bars bit for bit: the birth at the 0 pixel is -0.0, not 0.0" >> {
      val peak = Array(Array(-1.0, -1.0, -1.0), Array(-1.0, 0.0, -1.0), Array(-1.0, -1.0, -1.0))
      val original = TDA4j.computeFromImage(peak, Array("sublevel", "false"))
      val negativeZero = java.lang.Double.doubleToRawLongBits(-0.0)
      (original.toArrayUnfiltered().exists(row => java.lang.Double.doubleToRawLongBits(row(1)) == negativeZero) must
        beTrue) and
        (sameArrays(roundTrip(original).toArrayUnfiltered(), original.toArrayUnfiltered()) must beTrue)
    }

  "a saved result with zero-length bars" should
    "keep them" >> {
      val original = TDA4j.computeFromPoints(noisyCircle, Array("maxDimension", "1", "includeZeroLength", "true"))
      val loaded = roundTrip(original)
      (original.toArrayUnfiltered().count(row => row(1) == row(2)) must beGreaterThan(0)) and
        (sameArrays(loaded.toArrayUnfiltered(), original.toArrayUnfiltered()) must beTrue) and
        (sameArrays(loaded.toArray(), original.toArray()) must beTrue)
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
        (r.cycleVertices(1) must throwA[UnsupportedOperationException]) and
        (r.birthCell(1) must beEmpty) and (r.deathCell(1) must beEmpty)
    }
    "read the cells of a file that has them, and refuse a list of the wrong length" >> {
      val head = """{"format": "tda4j-persistence-result", "formatVersion": 1,
                   | "dimensions": [0, 1], "births": [0, 0.5], "deaths": ["Infinity", 1.5],""".stripMargin
      val r = TDA4j.loadResult(write(head + """ "birthCells": [[3], [0, 2]], "deathCells": [[], [0, 1, 2]]}"""))
      (r.birthCell(1).toSeq must beEqualTo(Seq(0, 2))) and (r.deathCell(1).toSeq must beEqualTo(Seq(0, 1, 2))) and
        (r.deathCell(0) must beEmpty) and
        (TDA4j.loadResult(write(head + """ "birthCells": [[3]], "deathCells": [[], []]}""")) must
          throwA[IllegalArgumentException](message = "one per bar")) and
        (TDA4j.loadResult(write(head + """ "birthCells": [[3], [0, 2]]}""")) must
          throwA[IllegalArgumentException](message = "come together"))
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
    "read a file as Python writes it: bare Infinity and NaN, a byte-order mark, keys in any order, keys it skips" >> {
      val r = TDA4j.loadResult(
        write(
          "\uFEFF" +
            """{
              |  "deaths": [Infinity, 1.5, NaN],
              |  "comment": {"written by": ["a script", 1, null, true]},
              |  "births": [0.0, 0.5, 0.25],
              |  "dimensions": [0, 1, 1],
              |  "formatVersion": 1,
              |  "reported": [1, 0],
              |  "representatives": [{"cells": [[0, 1], [1, 2]], "coefficients": [1.0, -1.0]}, null],
              |  "format": "tda4j-persistence-result"
              |}""".stripMargin
        )
      )
      (r.size() must beEqualTo(2)) and (r.dimension(0) must beEqualTo(1)) and (r.death(1).isPosInfinity must beTrue) and
        (r.toArrayUnfiltered()(2)(2).isNaN must beTrue) and
        (r.cycleCoefficients(0).toSeq must beEqualTo(Seq(1.0, -1.0))) and
        (r.cycleVertices(1) must throwA[UnsupportedOperationException](message = "no representative"))
    }
    "fail with an IllegalArgumentException wherever a file is cut off" >> {
      val path = tempPath(".json")
      TDA4j.computeFromPoints(Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.9))).save(path)
      val text = read(path)
      val end = text.lastIndexOf('}')
      (0 until end).filter { n =>
        try
          ResultFile.parse(text.substring(0, n), "cut")
          true
        catch
          case _: IllegalArgumentException => false
          case _: Throwable                => true
      } must beEmpty
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
