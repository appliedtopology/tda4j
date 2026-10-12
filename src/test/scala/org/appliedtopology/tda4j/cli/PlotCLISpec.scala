package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.matlab.TDA4j

import org.specs2.mutable.Specification

import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** `--plot`, `--save-result` and a saved result as input: the CLI draws what `PersistenceResult`'s plot methods draw, a
  * saved result prints and plots like the run that saved it, and every misused flag is an error (exit 1), never
  * ignored. No case here may hand Scallop a malformed flag: its parse errors exit the JVM.
  */
class PlotCLISpec extends Specification:
  sequential
  System.setProperty("tda4j.plot.browser", "none") // never open a browser from the test suite

  private def tempPath(suffix: String): String =
    val f = File.createTempFile("plot-cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def run(args: String*): (Int, String) =
    val buffer = ByteArrayOutputStream()
    val code = TDA4jCLI.run(args, PrintStream(buffer))
    (code, buffer.toString)

  /** Standard error while `body` runs. */
  private def stderrOf(body: => Unit): String =
    val buffer = ByteArrayOutputStream()
    val saved = System.err
    System.setErr(PrintStream(buffer, true, StandardCharsets.UTF_8))
    try body
    finally System.setErr(saved)
    buffer.toString(StandardCharsets.UTF_8)

  private def read(path: String): String = Files.readString(Paths.get(path), StandardCharsets.UTF_8)

  /** Each SVG render numbers its clip path; two renders of one figure differ only there. */
  private def normalized(svg: String): String = svg.replaceAll("tda4j[0-9]+", "tda4jN")

  private val circle =
    val rng = scala.util.Random(11)
    Array.tabulate(30) { i =>
      val t = 2 * math.Pi * i / 30
      val r = 1 + 0.1 * (rng.nextDouble() - 0.5)
      Array(r * math.cos(t), r * math.sin(t))
    }
  private val circleFile =
    val path = tempPath(".csv")
    CSV.writePointCloud(path, circle)
    path
  private lazy val direct = TDA4j.computeFromPoints(circle, Array("maxDimension", "1"))

  "a saved result" should {
    "print the same bars as the run that saved it, read as --input-format result or by itself" >> {
      val saved = tempPath(".json")
      val (code, original) = run("--max-dimension", "1", "--save-result", saved, circleFile)
      val (code2, explicit) = run("--input-format", "result", saved)
      val (code3, sniffed) = run(saved)
      (code must beEqualTo(0)) and (code2 must beEqualTo(0)) and (code3 must beEqualTo(0)) and
        (original must not(beEmpty)) and (explicit must beEqualTo(original)) and (sniffed must beEqualTo(original))
    }
    "convert to another diagram format, the same as the original run writes" >> {
      val saved = tempPath(".json")
      val (fromRun, fromSaved) = (tempPath(".csv"), tempPath(".csv"))
      run("--max-dimension", "1", "--save-result", saved, "--output-format", "csv", "--output", fromRun, circleFile)
      val (code, _) = run("--output-format", "csv", "--output", fromSaved, saved)
      (code must beEqualTo(0)) and (read(fromSaved) must beEqualTo(read(fromRun)))
    }
    "refuse a computation flag, naming it" >> {
      val saved = tempPath(".json")
      run("--max-dimension", "1", "--save-result", saved, circleFile)
      val err = stderrOf {
        run("--max-dimension", "2", "--min-persistence", "0", saved)._1 must beEqualTo(1)
      }
      err must contain("--max-dimension, --min-persistence")
    }
  }

  "--plot" should {
    "draw the diagram the facade draws, into an .svg file, from the points or from a saved result alike" >> {
      val (fromPoints, fromSaved, saved) = (tempPath(".svg"), tempPath(".svg"), tempPath(".json"))
      val (code, _) =
        run("--max-dimension", "1", "--save-result", saved, "--plot", "diagram", "--output", fromPoints, circleFile)
      val (code2, _) = run("--plot", "diagram", "--output", fromSaved, saved)
      val expected = normalized(direct.plotDiagram().svgFile(plot.Theme.Light))
      (code must beEqualTo(0)) and (code2 must beEqualTo(0)) and
        (normalized(read(fromPoints)) must beEqualTo(expected)) and
        (normalized(read(fromSaved)) must beEqualTo(expected))
    }
    "draw the most persistent loop by default, and say which bar it drew" >> {
      val out = tempPath(".svg")
      val loop = direct.mostPersistentBar(1)
      val err = stderrOf {
        run("--max-dimension", "1", "--plot", "representative", "--output", out, circleFile)._1 must beEqualTo(0)
      }
      (normalized(read(out)) must beEqualTo(normalized(direct.plotRepresentative(loop).svgFile(plot.Theme.Light)))) and
        (err must contain(s"drawing bar $loop: "))
    }
    "draw a chosen bar over the complex at a scale, and the complex itself" >> {
      val (rep, complex) = (tempPath(".svg"), tempPath(".html"))
      val loop = direct.mostPersistentBar(1)
      val at = direct.birth(loop).toString
      val args = Seq("--max-dimension", "1", "--plot", "representative", "--bar", loop.toString, "--at", at)
      (run(args ++ Seq("--output", rep, circleFile)*)._1 must beEqualTo(0)) and
        (normalized(read(rep)) must beEqualTo(
          normalized(direct.plotRepresentative(loop, direct.birth(loop)).svgFile(plot.Theme.Light))
        )) and
        (run("--max-dimension", "1", "--plot", "complex", "--at", "0.5", "--output", complex, circleFile)._1 must
          beEqualTo(0)) and
        (read(complex).toLowerCase must startWith("<!doctype html>"))
    }
    "write a page to the temporary directory without --output, and say where" >> {
      val err = stderrOf {
        run("--max-dimension", "1", "--plot", "barcode", circleFile)._1 must beEqualTo(0)
      }
      val page = "tda4j: wrote (\\S+\\.html)".r.findFirstMatchIn(err).map(_.group(1))
      (page must beSome) and (read(page.get) must contain("<svg"))
    }
  }

  "a misused plot flag" should {
    def fails(args: String*): Int = run(args :+ circleFile*)._1
    "be an error, never ignored" >> {
      (fails("--bar", "0") must beEqualTo(1)) and
        (fails("--plot", "barcode", "--at", "1") must beEqualTo(1)) and
        (fails("--plot", "landscape", "--bar", "0") must beEqualTo(1)) and
        (fails("--plot", "representative", "--bar", "0", "--dimension", "1") must beEqualTo(1)) and
        (fails("--plot", "complex") must beEqualTo(1)) and
        (fails("--plot", "bars") must beEqualTo(1)) and
        (fails("--plot", "diagram", "--output", tempPath(".png")) must beEqualTo(1)) and
        (fails("--plot", "diagram", "--output-format", "csv") must beEqualTo(1)) and
        (fails("--plot", "diagram", "--representatives") must beEqualTo(1)) and
        (fails("--save-result", tempPath(".json"), "--select-landmarks", "--num-landmarks", "5") must beEqualTo(1))
    }
    "be an error, not a stack trace, when the input has nothing to draw on" >> {
      val distances = tempPath(".csv")
      CSV.writeFullDistanceMatrix(distances, Array.tabulate(5, 5)((i, j) => math.abs(i - j).toDouble))
      val err = stderrOf {
        run("--input-format", "csv-distances", "--plot", "input", "--output", tempPath(".svg"), distances)._1 must
          beEqualTo(1)
      }
      err must contain("no coordinates")
    }
    "be an error for a bar that is not there" >> {
      fails("--plot", "representative", "--bar", "1000", "--output", tempPath(".svg")) must beEqualTo(1)
    }
  }
