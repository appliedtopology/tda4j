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

  // Where the CLI's error stream goes. The process's own `System.err` is shared with the specs running beside this one,
  // so it is neither swapped nor read here.
  private var errorBuffer = ByteArrayOutputStream()

  private def run(args: String*): (Int, String) =
    val buffer = ByteArrayOutputStream()
    val code = TDA4jCLI.run(args, PrintStream(buffer), PrintStream(errorBuffer, true, StandardCharsets.UTF_8))
    (code, buffer.toString)

  /** What the CLI writes to its error stream while `body` runs. */
  private def stderrOf(body: => Unit): String =
    val captured = ByteArrayOutputStream()
    val saved = errorBuffer
    errorBuffer = captured
    try body
    finally errorBuffer = saved
    captured.toString(StandardCharsets.UTF_8)

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

  "the look flags" should {
    def drawn(flags: String*): String =
      val out = tempPath(".svg")
      val (code, _) = run(Seq("--max-dimension", "1", "--plot", "diagram", "--output", out) ++ flags :+ circleFile*)
      code must beEqualTo(0)
      normalized(read(out))
    def expected(f: plot.Figure => plot.Figure): String = normalized(f(direct.plotDiagram()).svgFile(plot.Theme.Light))

    "title, label and size the plot, as the tweaks do" >> {
      val got =
        drawn("--title", "A circle", "--x-label", "born", "--y-label", "dies", "--width", "800", "--height", "300")
      got must beEqualTo(expected(_.withTitle("A circle").withXLabel("born").withYLabel("dies").withSize(800, 300)))
    }
    "write a caption, a panel tag and a description, as the tweaks do" >> {
      drawn("--caption", "Figure 1. Two loops", "--tag", "(a)", "--description", "a diagram of two loops") must
        beEqualTo(
          expected(_.withCaption("Figure 1. Two loops").withTag("(a)").withDescription("a diagram of two loops"))
        )
      drawn("--caption", "Figure 1. Two loops") must contain("Figure 1. Two loops")
      drawn("--tag", "(a)") must not(beEqualTo(expected(identity)))
    }
    "take a whole look in one flag, which the more specific flags override" >> {
      drawn("--look", "print") must beEqualTo(expected(_.withPalette("print").withStyle("print")))
      drawn("--look", "clear") must beEqualTo(expected(_.withPalette("clear").withStyle("clear")))
      drawn("--look", "default") must beEqualTo(expected(identity))
      drawn("--look", "print", "--palette", "reference") must
        beEqualTo(expected(_.withPalette("print").withStyle("print").withPalette("reference")))
      drawn("--look", "print", "--text-scale", "1.5") must
        beEqualTo(expected(_.withPalette("print").withStyle("print").withTextScale(1.5)))
    }
    "choose the palette, the scales and the text size" >> {
      drawn("--palette", "reference") must beEqualTo(expected(_.withPalette("reference")))
      drawn("--palette", "print", "--style", "print") must
        beEqualTo(expected(_.withPalette("print").withStyle("print")))
      // a style replaces the whole style, so --style and --text-scale have to be one change, not two
      drawn("--style", "print", "--text-scale", "1.5") must beEqualTo(expected(_.withStyle("print").withTextScale(1.5)))
      drawn("--style", "slides") must beEqualTo(expected(_.withStyle("slides")))
      // one flag for each change of an element, repeated
      drawn("--set", "title.size=18", "--set", "grid.dash=dotted", "--set", "x-axis=top") must
        beEqualTo(expected(_.withSetting("title.size=18").withSetting("grid.dash=dotted").withSetting("x-axis=top")))
      drawn("--set", "ticks.length=4") must not(beEqualTo(expected(identity)))
      drawn("--style", "print", "--set", "title.size=18") must
        beEqualTo(expected(_.withStyle("print").withSetting("title.size=18")))
      drawn("--set", "x-axis.reverse=true", "--set", "size.width=4in", "--set", "numbers.decimal=,") must
        beEqualTo(
          expected(_.withSetting("x-axis.reverse=true").withSetting("size.width=4in").withSetting("numbers.decimal=,"))
        )
      drawn("--set", "size.width=4in") must contain("width=\"384\"")
      drawn("--set", "numbers.decimal=,").contains("0,5") must beTrue
      drawn("--palette", "clear", "--style", "clear") must
        beEqualTo(expected(_.withPalette("clear").withStyle("clear")))
      drawn("--style", "print") must contain("Helvetica")
      drawn("--x-scale", "log", "--y-scale", "sqrt") must beEqualTo(expected(_.withXScale("log").withYScale("sqrt")))
      drawn("--text-scale", "1.5") must beEqualTo(expected(_.withTextScale(1.5)))
      drawn("--x-scale", "log") must not(beEqualTo(expected(identity)))
    }
    "draw in the theme asked for, light for an .svg file otherwise" >> {
      val out = tempPath(".svg")
      run(
        "--max-dimension",
        "1",
        "--plot",
        "diagram",
        "--theme",
        "dark",
        "--output",
        out,
        circleFile
      )._1 must beEqualTo(0)
      normalized(read(out)) must beEqualTo(normalized(direct.plotDiagram().svgFile(plot.Theme.Dark)))
      drawn() must beEqualTo(expected(identity))
    }
    "draw the clear look in white for a dark page" >> {
      val out = tempPath(".svg")
      run(
        "--max-dimension",
        "1",
        "--plot",
        "diagram",
        "--palette",
        "clear",
        "--style",
        "clear",
        "--theme",
        "dark",
        "--output",
        out,
        circleFile
      )._1 must beEqualTo(0)
      val expectedDark =
        direct.plotDiagram().withPalette("clear").withStyle("clear").svgFile(plot.Theme.Dark)
      normalized(read(out)) must beEqualTo(normalized(expectedDark))
      read(out) must contain("fill:#ffffff")
    }
    "reach a 3-D scene where a scene has the setting" >> {
      val out = tempPath(".svg")
      val code = run(
        "--max-dimension",
        "1",
        "--plot",
        "input-3d",
        "--title",
        "A cloud",
        "--width",
        "300",
        "--output",
        out,
        circleFile
      )._1
      (code must beEqualTo(0)) and (read(out) must contain("A cloud")) and (read(out) must contain("""width="300""""))
    }
  }

  "a misused look flag" should {
    def failure(args: String*): (Int, String) =
      var code = -1
      val err = stderrOf { code = run(args :+ circleFile*)._1 }
      (code, err)
    "be refused before anything is computed, so a typo costs no wait" >> {
      // the input does not exist: were the setting checked only when the plot is made, the missing file would be the error
      def early(args: String*): String = stderrOf(run(args :+ "no-such-input.csv"*))
      early("--plot", "diagram", "--set", "camera.zoom=2") must contain("a 2-D figure has none")
      early("--plot", "input-3d", "--set", "tick-label.size=9") must contain("a 3-D scene has a title")
      early("--plot", "input-3d", "--set", "camera.zoom=20") must contain("the zoom should be between 0.2 and 8")
      early("--plot", "diagram", "--set", "title.size=big") must contain("'big' is not a number")
      early("--plot", "diagram", "--look", "neon") must contain("expected one of default, print, clear")
      early("--plot", "input-3d", "--caption", "c") must contain("--caption is for 2-D plots")
      early("--plot", "input-3d", "--tag", "(a)") must contain("--tag is for 2-D plots")
      early("--plot", "input-3d", "--description", "d") must contain("--description is for 2-D plots")
      early("--caption", "c") must contain("--caption is for --plot")
      early("--look", "print") must contain("--look is for --plot")
    }
    "be an error that says what to do, never ignored" >> {
      val (c1, e1) = failure("--title", "A circle")
      val (c2, e2) = failure("--plot", "diagram", "--x-scale", "cubic")
      val (c3, e3) = failure("--plot", "diagram", "--palette", "neon")
      val (c4, e4) = failure("--plot", "diagram", "--theme", "sepia")
      val (c5, e5) = failure("--plot", "diagram", "--width", "20")
      val (c6, e6) = failure("--plot", "input-3d", "--x-scale", "log")
      val (c7, e7) = failure("--plot", "diagram", "--text-scale", "0")
      val (c8, e8) = failure("--plot", "diagram", "--style", "poster")
      val (c9, e9) = failure("--style", "print")
      val (c10, e10) = failure("--plot", "diagram", "--set", "title.size=big")
      val (c11, e11) = failure("--plot", "diagram", "--set", "x-axis=left")
      val (c12, e12) = failure("--plot", "input-3d", "--set", "tick-label.size=9")
      val (c13, e13) = failure("--set", "title.size=18")
      val (c14, e14) = failure("--plot", "diagram", "--set", "numbers.separator=,")
      val (c16, e16) = failure("--plot", "diagram", "--set", "camera.zoom=2")
      val (c17, e17) = failure("--plot", "input-3d", "--set", "camera.zoom=20")
      val (c15, e15) = failure("--plot", "diagram", "--set", "size.width=wide")
      (c1 must beEqualTo(1)) and (e1 must contain("--title is for --plot")) and
        (c2 must beEqualTo(1)) and (e2 must contain(
          "unknown scale 'cubic'; expected one of linear, log, sqrt, symlog"
        )) and
        (c3 must beEqualTo(1)) and (e3 must contain("expected one of brand, reference, print, clear")) and
        (c8 must beEqualTo(1)) and (e8 must contain("expected one of default, slides, print, clear")) and
        (c9 must beEqualTo(1)) and (e9 must contain("--style is for --plot")) and
        (c10 must beEqualTo(1)) and (e10 must contain("'big' is not a number")) and
        (c11 must beEqualTo(1)) and (e11 must contain("the x axis is at the bottom or the top")) and
        (c12 must beEqualTo(1)) and (e12 must contain("a 3-D scene has a title")) and
        (c13 must beEqualTo(1)) and (e13 must contain("--set is for --plot")) and
        (c14 must beEqualTo(1)) and (e14 must contain("expected one of locale, decimal, grouping, minus")) and
        (c15 must beEqualTo(1)) and (e15 must contain("is not a number")) and
        (c16 must beEqualTo(1)) and (e16 must contain("a 2-D figure has none")) and
        (c17 must beEqualTo(1)) and (e17 must contain("the zoom should be between 0.2 and 8")) and
        (c4 must beEqualTo(1)) and (e4 must contain("expected one of auto, light, dark")) and
        (c5 must beEqualTo(1)) and (e5 must contain("between 100 and 20000")) and
        (c6 must beEqualTo(1)) and (e6 must contain("--x-scale is for 2-D plots")) and
        (c7 must beEqualTo(1)) and (e7 must contain("textScale"))
    }
  }
