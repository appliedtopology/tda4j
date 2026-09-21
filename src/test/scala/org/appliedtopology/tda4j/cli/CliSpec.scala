package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.barcode.{given, *}
import org.appliedtopology.tda4j.io.{given, *}
import org.appliedtopology.tda4j.matlab.Tda4j

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** Tests the CLI's OWN logic (option translation, input-format dispatch, output writing) -- not a re-test of
  * `Tda4j`, which already has its own spec. The one end-to-end case compares the CLI's result against calling
  * `Tda4j` directly, which is the actual guarantee this CLI exists to provide: that going through Scallop and a
  * file on disk produces the identical barcode a direct library call would.
  */
class CliSpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  "buildOptions" should {
    "be empty when no optional flags are passed -- Tda4j's own defaults must apply, not a CLI-side copy" >> {
      val conf = new Tda4jConf(Seq("some-input-file"))
      Tda4jCli.buildOptions(conf) must beEmpty
    }

    "include exactly the flags the user passed, as key/value pairs" >> {
      val conf = new Tda4jConf(
        Seq("--complex", "alpha", "--max-dimension", "3", "--field", "R", "some-input-file")
      )
      val opts = Tda4jCli.buildOptions(conf)
      opts.toSeq must containAllOf(Seq("complex", "alpha", "maxDimension", "3", "field", "R"))
      opts.length must beEqualTo(6) // and nothing else -- engine/prime/epsilon/alphaBackend/maxFiltrationValue unset
    }
  }

  "resolveInput" should {
    "dispatch point-cloud formats to Left and distance-matrix formats to Right" >> {
      val csvPoints = tempFile(".csv")
      Csv.writePointCloud(csvPoints, Seq(Seq(0.0, 0.0), Seq(1.0, 0.0)))
      val csvDist = tempFile(".csv")
      Csv.writeFullDistanceMatrix(csvDist, Seq(Seq(0.0, 1.0), Seq(1.0, 0.0)))

      (Tda4jCli.resolveInput("csv-points", csvPoints) must beLeft) and
        (Tda4jCli.resolveInput("csv-distances", csvDist) must beRight)
    }

    "reject an unrecognized --input-format with a clear message" >> {
      Tda4jCli.resolveInput("not-a-format", "irrelevant") must throwA[IllegalArgumentException]
    }
  }

  "an end-to-end run" should {
    "produce the exact same barcode as calling Tda4j directly, via a real file on disk" >> {
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val path = tempFile(".csv")
      Csv.writePointCloud(path, points.map(_.toSeq).toSeq)

      val buffer = new ByteArrayOutputStream()
      val exitCode = Tda4jCli.run(Seq("--max-dimension", "1", path), new PrintStream(buffer))
      val cliLines = buffer.toString.linesIterator.toSeq

      val direct = Tda4j.computeFromPoints(points, Array("maxDimension", "1"))
      val directBars = Tda4jCli.toBars(direct)
      val directLines = directBars.map(_.toString)

      (exitCode must beEqualTo(0)) and (cliLines must beEqualTo(directLines))
    }

    "return exit code 1 and print a one-line message on a bad option, without throwing" >> {
      val path = tempFile(".csv")
      Csv.writePointCloud(path, Seq(Seq(0.0, 0.0), Seq(1.0, 0.0)))
      val buffer = new ByteArrayOutputStream()
      Tda4jCli.run(Seq("--complex", "bogus", path), new PrintStream(buffer)) must beEqualTo(1)
    }
  }
