package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.barcode.{given, *}
import org.appliedtopology.tda4j.io.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.matlab.TDA4j

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** Tests the CLI's OWN logic (option translation, input-format dispatch, output writing) -- not a re-test of `TDA4j`,
  * which already has its own spec. The one end-to-end case compares the CLI's result against calling `TDA4j` directly,
  * which is the actual guarantee this CLI exists to provide: that going through Scallop and a file on disk produces the
  * identical barcode a direct library call would.
  */
class CLISpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  "buildOptions" should {
    "be empty when no optional flags are passed -- TDA4j's own defaults must apply, not a CLI-side copy" >> {
      val conf = new TDA4jConf(Seq("some-input-file"))
      TDA4jCLI.buildOptions(conf) must beEmpty
    }

    "include exactly the flags the user passed, as key/value pairs" >> {
      val conf = new TDA4jConf(
        Seq("--complex", "alpha", "--max-dimension", "3", "--field", "R", "some-input-file")
      )
      val opts = TDA4jCLI.buildOptions(conf)
      opts.toSeq must containAllOf(Seq("complex", "alpha", "maxDimension", "3", "field", "R"))
      opts.length must beEqualTo(6) // and nothing else -- engine/prime/epsilon/alphaBackend/maxFiltrationValue unset
    }
  }

  "resolveInput" should {
    "dispatch point-cloud and distance-matrix formats to their own ResolvedInput cases" >> {
      val csvPoints = tempFile(".csv")
      CSV.writePointCloud(csvPoints, Seq(Seq(0.0, 0.0), Seq(1.0, 0.0)))
      val csvDist = tempFile(".csv")
      CSV.writeFullDistanceMatrix(csvDist, Seq(Seq(0.0, 1.0), Seq(1.0, 0.0)))

      (TDA4jCLI.resolveInput("csv-points", csvPoints) must beAnInstanceOf[TDA4jCLI.ResolvedInput.Points]) and
        (TDA4jCLI.resolveInput("csv-distances", csvDist) must beAnInstanceOf[TDA4jCLI.ResolvedInput.Distances])
    }

    "dispatch a cubical-image format to ResolvedInput.CubicalGrid, round-tripping shape and values exactly" >> {
      val path = tempFile(".txt")
      // Perseus's own toplex format: dimension, then one size per axis, then values in Perseus's own
      // FIRST-axis-fastest order (see Perseus.scala's own doc) -- (0,1,2,3) laid out for a 2x2 grid.
      java.nio.file.Files.write(java.nio.file.Paths.get(path), "2\n2\n2\n0\n1\n2\n3\n".getBytes)

      TDA4jCLI.resolveInput("perseus-cubical", path) match
        case TDA4jCLI.ResolvedInput.CubicalGrid(shape, flatValues) =>
          (shape must beEqualTo(Array(2, 2))) and (flatValues.length must beEqualTo(4))
        case other => ko(s"expected CubicalGrid, got $other")
    }

    "reject an unrecognized --input-format with a clear message" >> {
      TDA4jCLI.resolveInput("not-a-format", "irrelevant") must throwA[IllegalArgumentException]
    }
  }

  "flattenGridStream" should
    "round-trip exactly through CubicalImage.fromFlatArray for a non-square shape" >> {
      val shape = IndexedSeq(2, 3)
      val values = IndexedSeq(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
      val stream = CubicalImage.fromFlatArray(shape, values)
      val (recoveredShape, recoveredValues) = TDA4jCLI.flattenGridStream(stream)
      (recoveredShape must beEqualTo(shape.toArray)) and (recoveredValues must beEqualTo(values.toArray))
    }

  "an end-to-end run" should {
    "produce the exact same barcode as calling TDA4j directly, via a real file on disk" >> {
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points.map(_.toSeq).toSeq)

      val buffer = new ByteArrayOutputStream()
      val exitCode = TDA4jCLI.run(Seq("--max-dimension", "1", path), new PrintStream(buffer))
      val cliLines = buffer.toString.linesIterator.toSeq

      val direct = TDA4j.computeFromPoints(points, Array("maxDimension", "1"))
      val directBars = TDA4jCLI.toBars(direct)
      val directLines = directBars.map(_.toString)

      (exitCode must beEqualTo(0)) and (cliLines must beEqualTo(directLines))
    }

    "produce the exact same barcode with --engine cohomology as calling TDA4j directly, via a real file on disk" >> {
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points.map(_.toSeq).toSeq)

      val buffer = new ByteArrayOutputStream()
      val exitCode =
        TDA4jCLI.run(Seq("--engine", "cohomology", "--max-dimension", "1", path), new PrintStream(buffer))
      val cliLines = buffer.toString.linesIterator.toSeq

      val direct = TDA4j.computeFromPoints(points, Array("engine", "cohomology", "maxDimension", "1"))
      val directBars = TDA4jCLI.toBars(direct)
      val directLines = directBars.map(_.toString)

      (exitCode must beEqualTo(0)) and (cliLines must beEqualTo(directLines))
    }

    "return exit code 1 and print a one-line message on a bad option, without throwing" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, Seq(Seq(0.0, 0.0), Seq(1.0, 0.0)))
      val buffer = new ByteArrayOutputStream()
      TDA4jCLI.run(Seq("--complex", "bogus", path), new PrintStream(buffer)) must beEqualTo(1)
    }

    "produce the exact same barcode as calling TDA4j directly for --complex=cech, via a real file on disk" >> {
      // No CLI-side code exists specifically for complex=cech -- --complex is just another flag TDA4jConf mirrors
      // 1:1 into the options array (see buildOptions), so this exercises that the generic plumbing carries a new
      // TDA4j-level complex value through correctly, without needing its own CLI code path.
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points.map(_.toSeq).toSeq)

      val buffer = new ByteArrayOutputStream()
      val exitCode = TDA4jCLI.run(Seq("--complex", "cech", "--max-dimension", "1", path), new PrintStream(buffer))
      val cliLines = buffer.toString.linesIterator.toSeq

      val direct = TDA4j.computeFromPoints(points, Array("complex", "cech", "maxDimension", "1"))
      val directLines = TDA4jCLI.toBars(direct).map(_.toString)

      (exitCode must beEqualTo(0)) and (cliLines must beEqualTo(directLines))
    }

    "produce the exact same barcode as calling TDA4j.computeFromCubicalImage directly for a cubical-image file" >> {
      val path = tempFile(".txt")
      // A 3x3 ring with a permanently missing center (-1 -> +Infinity, Perseus's own convention) -- the same
      // discriminating fixture TDA4jSpec's own complex=cubical tests use, laid out in Perseus's FIRST-axis-
      // fastest order (identical to CLAUDE.md/Perseus.scala's documented axis-order reversal).
      java.nio.file.Files.write(
        java.nio.file.Paths.get(path),
        "2\n3\n3\n0\n0\n0\n0\n-1\n0\n0\n0\n0\n".getBytes
      )

      val buffer = new ByteArrayOutputStream()
      val exitCode = TDA4jCLI.run(Seq("--input-format", "perseus-cubical", path), new PrintStream(buffer))
      val cliLines = buffer.toString.linesIterator.toSeq

      val (shape, flatValues) =
        TDA4jCLI.flattenGridStream(Perseus.readCubicalToplex(path, sublevel = true))
      val direct = TDA4j.computeFromCubicalImage(shape, flatValues)
      val directLines = TDA4jCLI.toBars(direct).map(_.toString)

      (exitCode must beEqualTo(0)) and (cliLines must beEqualTo(directLines))
    }

    "reject --complex combined with a cubical-image --input-format" >> {
      val path = tempFile(".txt")
      java.nio.file.Files.write(java.nio.file.Paths.get(path), "2\n2\n2\n0\n1\n2\n3\n".getBytes)
      val buffer = new ByteArrayOutputStream()
      TDA4jCLI.run(
        Seq("--input-format", "perseus-cubical", "--complex", "vr", path),
        new PrintStream(buffer)
      ) must beEqualTo(1)
    }
  }
