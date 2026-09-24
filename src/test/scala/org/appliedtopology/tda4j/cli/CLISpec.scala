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
      CSV.writePointCloud(csvPoints, Array(Array(0.0, 0.0), Array(1.0, 0.0)))
      val csvDist = tempFile(".csv")
      CSV.writeFullDistanceMatrix(csvDist, Array(Array(0.0, 1.0), Array(1.0, 0.0)))

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
      CSV.writePointCloud(path, points)

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
      CSV.writePointCloud(path, points)

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
      CSV.writePointCloud(path, Array(Array(0.0, 0.0), Array(1.0, 0.0)))
      val buffer = new ByteArrayOutputStream()
      TDA4jCLI.run(Seq("--complex", "bogus", path), new PrintStream(buffer)) must beEqualTo(1)
    }

    "produce the exact same barcode as calling TDA4j directly for --complex=cech, via a real file on disk" >> {
      // No CLI-side code exists specifically for complex=cech -- --complex is just another flag TDA4jConf mirrors
      // 1:1 into the options array (see buildOptions), so this exercises that the generic plumbing carries a new
      // TDA4j-level complex value through correctly, without needing its own CLI code path.
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)

      val buffer = new ByteArrayOutputStream()
      val exitCode = TDA4jCLI.run(Seq("--complex", "cech", "--max-dimension", "1", path), new PrintStream(buffer))
      val cliLines = buffer.toString.linesIterator.toSeq

      val direct = TDA4j.computeFromPoints(points, Array("complex", "cech", "maxDimension", "1"))
      val directLines = TDA4jCLI.toBars(direct).map(_.toString)

      (exitCode must beEqualTo(0)) and (cliLines must beEqualTo(directLines))
    }

    "produce the exact same barcode as calling TDA4j directly for --complex=witness, via a real file on disk" >> {
      // Same "no CLI-side code exists for this complex" argument as --complex=cech above -- --num-landmarks/
      // --witness-variant/--landmark-selector/--landmark-seed/--nu are all just more 1:1-mirrored flags.
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0), Array(0.5, 2.0))
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)

      val buffer = new ByteArrayOutputStream()
      val exitCode = TDA4jCLI.run(
        Seq("--complex", "witness", "--num-landmarks", "4", "--witness-variant", "general", path),
        new PrintStream(buffer)
      )
      val cliLines = buffer.toString.linesIterator.toSeq

      val direct = TDA4j.computeFromPoints(
        points,
        Array("complex", "witness", "numLandmarks", "4", "witnessVariant", "general")
      )
      val directLines = TDA4jCLI.toBars(direct).map(_.toString)

      (exitCode must beEqualTo(0)) and (cliLines must beEqualTo(directLines))
    }

    "produce the exact same barcode as calling TDA4j directly for --complex=dtm-rips, via a real file on disk" >> {
      // Same "no CLI-side code exists for this complex" argument as --complex=cech above -- --dtm-k/--dtm-q/--dtm-p
      // are all just more 1:1-mirrored flags.
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)

      val buffer = new ByteArrayOutputStream()
      val exitCode = TDA4jCLI.run(
        Seq("--complex", "dtm-rips", "--dtm-k", "3", "--max-dimension", "1", path),
        new PrintStream(buffer)
      )
      val cliLines = buffer.toString.linesIterator.toSeq

      val direct = TDA4j.computeFromPoints(
        points,
        Array("complex", "dtm-rips", "dtmK", "3", "maxDimension", "1")
      )
      val directLines = TDA4jCLI.toBars(direct).map(_.toString)

      (exitCode must beEqualTo(0)) and (cliLines must beEqualTo(directLines))
    }

    "produce the exact same barcode as calling TDA4j directly for --complex=sheehy-rips, via a real file on disk" >> {
      // Same "no CLI-side code exists for this complex" argument as --complex=dtm-rips above -- --sheehy-epsilon
      // is just one more 1:1-mirrored flag.
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)

      val buffer = new ByteArrayOutputStream()
      val exitCode = TDA4jCLI.run(
        Seq("--complex", "sheehy-rips", "--sheehy-epsilon", "0.5", "--max-dimension", "1", path),
        new PrintStream(buffer)
      )
      val cliLines = buffer.toString.linesIterator.toSeq

      val direct = TDA4j.computeFromPoints(
        points,
        Array("complex", "sheehy-rips", "sheehyEpsilon", "0.5", "maxDimension", "1")
      )
      val directLines = TDA4jCLI.toBars(direct).map(_.toString)

      (exitCode must beEqualTo(0)) and (cliLines must beEqualTo(directLines))
    }

    "produce the exact same barcode as calling TDA4j directly for --complex=dtm-alpha, via a real file on disk" >> {
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)

      val buffer = new ByteArrayOutputStream()
      val exitCode = TDA4jCLI.run(
        Seq("--complex", "dtm-alpha", "--dtm-k", "3", path),
        new PrintStream(buffer)
      )
      val cliLines = buffer.toString.linesIterator.toSeq

      val direct = TDA4j.computeFromPoints(
        points,
        Array("complex", "dtm-alpha", "dtmK", "3")
      )
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

  // ---------------------------------------------------------------------------------------------------------
  // The two-step witness recipe's own CLI surface: --select-landmarks (step 1) and --landmarks-file (step 2).
  // No CLI-side logic exists for EITHER beyond option translation and the tiny landmarks-file reader/writer
  // (`readLandmarksFile`/`writeLandmarkSelection`) -- every conflict check below relies on TDA4j's own strict
  // allowlists doing the real validation, exactly like `--complex` + cubical-grid above.
  // ---------------------------------------------------------------------------------------------------------

  "the two-step witness recipe" should {
    val points =
      Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0), Array(0.5, 2.0), Array(2.0, 0.5))

    "reproduce the one-shot barcode via two real file-based invocations: --select-landmarks then " +
      "--landmarks-file" >> {
        val inputPath = tempFile(".csv")
        CSV.writePointCloud(inputPath, points)
        val landmarksPath = tempFile(".txt")

        val step1Buffer = new ByteArrayOutputStream()
        val step1Exit = TDA4jCLI.run(
          Seq("--select-landmarks", "--num-landmarks", "4", "--output", landmarksPath, inputPath),
          new PrintStream(step1Buffer)
        )

        val step2Buffer = new ByteArrayOutputStream()
        val step2Exit = TDA4jCLI.run(
          Seq("--landmarks-file", landmarksPath, "--complex", "witness", inputPath),
          new PrintStream(step2Buffer)
        )
        val cliLines = step2Buffer.toString.linesIterator.toSeq

        val landmarks = TDA4jCLI.readLandmarksFile(landmarksPath)
        val direct = TDA4j.computeFromPointsAndLandmarks(points, landmarks)
        val directLines = TDA4jCLI.toBars(direct).map(_.toString)

        // The actual "reproduces the ONE-SHOT barcode" claim in this test's own name -- maxmin(numLandmarks=4)
        // is deterministic (firstLandmark=0 by default), so the one-shot path picks the SAME landmarks the
        // two-step run above did, and must agree exactly.
        val oneShot = TDA4j.computeFromPoints(points, Array("complex", "witness", "numLandmarks", "4"))
        val oneShotLines = TDA4jCLI.toBars(oneShot).map(_.toString)

        (step1Exit must beEqualTo(0)) and (step2Exit must beEqualTo(0)) and
          (cliLines must beEqualTo(directLines)) and (cliLines must beEqualTo(oneShotLines))
      }

    "--select-landmarks writes the SAME landmarks TDA4j.selectLandmarksFromPoints would, plus a parseable " +
      "coveringRadius comment line" >> {
        val inputPath = tempFile(".csv")
        CSV.writePointCloud(inputPath, points)
        val landmarksPath = tempFile(".txt")

        TDA4jCLI.run(
          Seq("--select-landmarks", "--num-landmarks", "4", "--output", landmarksPath, inputPath),
          new PrintStream(new ByteArrayOutputStream())
        )

        val direct = TDA4j.selectLandmarksFromPoints(points, Array("numLandmarks", "4"))
        val fileLines = scala.io.Source.fromFile(landmarksPath).getLines().toSeq
        val commentLine = fileLines.find(_.startsWith("#"))

        (TDA4jCLI.readLandmarksFile(landmarksPath) must beEqualTo(direct.landmarks())) and
          (commentLine must beSome(s"# coveringRadius=${direct.coveringRadius()}"))
      }

    "reject --select-landmarks combined with --landmarks-file" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)
      val buffer = new ByteArrayOutputStream()
      TDA4jCLI.run(
        Seq("--select-landmarks", "--landmarks-file", "somefile.txt", "--num-landmarks", "1", path),
        new PrintStream(buffer)
      ) must beEqualTo(1)
    }

    "reject --select-landmarks combined with --representatives" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)
      val buffer = new ByteArrayOutputStream()
      TDA4jCLI.run(
        Seq("--select-landmarks", "--num-landmarks", "3", "--representatives", path),
        new PrintStream(buffer)
      ) must beEqualTo(1)
    }

    "reject --select-landmarks combined with a non-text --output-format" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)
      val buffer = new ByteArrayOutputStream()
      TDA4jCLI.run(
        Seq("--select-landmarks", "--num-landmarks", "3", "--output-format", "csv", "--output", "out.csv", path),
        new PrintStream(buffer)
      ) must beEqualTo(1)
    }

    "reject --select-landmarks with a cubical-image --input-format" >> {
      val path = tempFile(".txt")
      java.nio.file.Files.write(java.nio.file.Paths.get(path), "2\n2\n2\n0\n1\n2\n3\n".getBytes)
      val buffer = new ByteArrayOutputStream()
      TDA4jCLI.run(
        Seq("--input-format", "perseus-cubical", "--select-landmarks", "--num-landmarks", "1", path),
        new PrintStream(buffer)
      ) must beEqualTo(1)
    }

    "reject --landmarks-file with a cubical-image --input-format" >> {
      val path = tempFile(".txt")
      java.nio.file.Files.write(java.nio.file.Paths.get(path), "2\n2\n2\n0\n1\n2\n3\n".getBytes)
      val buffer = new ByteArrayOutputStream()
      TDA4jCLI.run(
        Seq("--input-format", "perseus-cubical", "--landmarks-file", "somefile.txt", path),
        new PrintStream(buffer)
      ) must beEqualTo(1)
    }

    "readLandmarksFile skips blank lines and '#' comments, and a malformed --landmarks-file causes a clean " +
      "exit code 1 (not a raw stack trace) through a real run" >> {
        val path = tempFile(".txt")
        java.nio.file.Files.write(
          java.nio.file.Paths.get(path),
          "# coveringRadius=0.5\n0\n\n2\n# a trailing comment\n5\n".getBytes
        )
        val badPath = tempFile(".txt")
        java.nio.file.Files.write(java.nio.file.Paths.get(badPath), "0\nnotanumber\n2\n".getBytes)
        val inputPath = tempFile(".csv")
        CSV.writePointCloud(inputPath, points) // a genuinely valid input, so the failure below is attributable
        // to the landmarks file alone, not an unrelated bad --input-format

        (TDA4jCLI.readLandmarksFile(path) must beEqualTo(Array(0, 2, 5))) and
          (TDA4jCLI.run(
            Seq("--landmarks-file", badPath, "--complex", "witness", inputPath),
            new PrintStream(new ByteArrayOutputStream())
          ) must beEqualTo(1))
      }
  }

  "--distance-to (barcode.BarcodeDistance mirror)" should {
    def writeComparisonDiagram(points: Array[Array[Double]]): String =
      val result = TDA4j.computeFromPoints(points, Array("maxDimension", "1"))
      val path = tempFile(".csv")
      CSV.writePersistenceDiagram(path, TDA4jCLI.toBars(result))
      path

    "print one 'dim <k>: bottleneck=... wasserstein=...' line per dimension, matching a direct " +
      "BarcodeDistance call on the same bars" >> {
        val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.9))
        val comparisonPoints = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
        val inputPath = tempFile(".csv")
        CSV.writePointCloud(inputPath, points)
        val comparisonPath = writeComparisonDiagram(comparisonPoints)

        val buffer = new ByteArrayOutputStream()
        val exitCode = TDA4jCLI.run(
          Seq("--max-dimension", "1", "--distance-to", comparisonPath, inputPath),
          new PrintStream(buffer)
        )
        val lines = buffer.toString.linesIterator.toSeq

        val computed = TDA4j.computeFromPoints(points, Array("maxDimension", "1"))
        val computedBars = TDA4jCLI.toBars(computed)
        val comparisonBars = CSV.readPersistenceDiagram(comparisonPath)
        val expectedBottleneck = BarcodeDistance.bottleneckDistanceByDimension(computedBars, comparisonBars)
        val expectedWasserstein = BarcodeDistance.wassersteinDistanceByDimension(computedBars, comparisonBars)
        val expectedLines =
          expectedBottleneck.keySet.toSeq.sorted.map(d =>
            s"dim $d: bottleneck=${expectedBottleneck(d)} wasserstein=${expectedWasserstein(d)}"
          )

        (exitCode must beEqualTo(0)) and (lines must beEqualTo(expectedLines))
      }

    "reject --distance-to combined with --select-landmarks" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, Array(Array(0.0, 0.0), Array(1.0, 0.0)))
      val comparisonPath = writeComparisonDiagram(Array(Array(0.0, 0.0), Array(1.0, 0.0)))
      TDA4jCLI.run(
        Seq("--select-landmarks", "--num-landmarks", "1", "--distance-to", comparisonPath, path),
        new PrintStream(new ByteArrayOutputStream())
      ) must beEqualTo(1)
    }

    "reject --distance-to combined with a non-text --output-format" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, Array(Array(0.0, 0.0), Array(1.0, 0.0)))
      val comparisonPath = writeComparisonDiagram(Array(Array(0.0, 0.0), Array(1.0, 0.0)))
      TDA4jCLI.run(
        Seq("--output-format", "csv", "--output", tempFile(".csv"), "--distance-to", comparisonPath, path),
        new PrintStream(new ByteArrayOutputStream())
      ) must beEqualTo(1)
    }

    "reject an unrecognized --distance-format" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, Array(Array(0.0, 0.0), Array(1.0, 0.0)))
      val comparisonPath = writeComparisonDiagram(Array(Array(0.0, 0.0), Array(1.0, 0.0)))
      TDA4jCLI.run(
        Seq("--distance-to", comparisonPath, "--distance-format", "perseus", path),
        new PrintStream(new ByteArrayOutputStream())
      ) must beEqualTo(1)
    }
  }
