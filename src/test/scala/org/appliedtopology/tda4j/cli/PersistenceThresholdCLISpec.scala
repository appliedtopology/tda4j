package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.io.{given, *}

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** The persistence-threshold flags, through the real `TDA4jCLI.run` (NOT `CliFull`, which switches the threshold off). */
class PersistenceThresholdCLISpec extends mutable.Specification:
  sequential // captures System.err

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("cli-threshold-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  // Collinear 0, 0.001, 1 -- see PersistenceThresholdSpec: 3 bars in all, one of them below the default cut.
  private val pointsFile =
    val path = tempFile(".csv")
    CSV.writePointCloud(path, Array(Array(0.0, 0.0), Array(0.001, 0.0), Array(1.0, 0.0)))
    path

  private def run(args: String*): (Int, Seq[String], String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val oldErr = System.err
    System.setErr(new PrintStream(err))
    val code =
      try TDA4jCLI.run(args.toSeq, new PrintStream(out))
      finally System.setErr(oldErr)
    (code, out.toString.linesIterator.toSeq, err.toString)

  "by default" should {
    "report 2 of the 3 bars and say on stderr how to see the rest" >> {
      val (code, lines, err) = run("--max-dimension", "1", pointsFile)
      (code must beEqualTo(0)) and (lines.size must beEqualTo(2)) and
        (err must contain("1 bar(s)")) and (err must contain("--min-persistence 0"))
    }
  }

  "--min-persistence 0 / --min-persistence-fraction 0" should {
    "report every bar, with no stderr note" >> {
      val (c1, l1, e1) = run("--min-persistence", "0", "--max-dimension", "1", pointsFile)
      val (c2, l2, e2) = run("--min-persistence-fraction", "0", "--max-dimension", "1", pointsFile)
      (c1 must beEqualTo(0)) and (l1.size must beEqualTo(3)) and (e1 must beEmpty) and
        (c2 must beEqualTo(0)) and (l2.size must beEqualTo(3)) and (e2 must beEmpty)
    }
  }

  "a different threshold" should {
    "be honoured: absolute in the barcode's units, or as a fraction of the scale" >> {
      val (_, absolute, _) = run("--min-persistence", "2", "--max-dimension", "1", pointsFile)
      val (_, fraction, _) = run("--min-persistence-fraction", "0.0005", "--max-dimension", "1", pointsFile)
      (absolute.size must beEqualTo(1)) and (fraction.size must beEqualTo(3))
    }
  }

  "invalid combinations" should {
    "be rejected: both flags together" >> {
      run("--min-persistence", "0.1", "--min-persistence-fraction", "0.1", pointsFile)._1 must beEqualTo(1)
    }
    "be rejected: a threshold with --select-landmarks, which produces no barcode" >> {
      val (code, _, err) = run("--select-landmarks", "--num-landmarks", "2", "--min-persistence", "0", pointsFile)
      (code must beEqualTo(1)) and (err must contain("--select-landmarks"))
    }
    "be rejected: a threshold with --distance-to, which always compares complete barcodes" >> {
      val other = tempFile(".csv")
      CSV.writePersistenceDiagram(other, Seq.empty)
      val (code, _, err) = run("--distance-to", other, "--min-persistence", "0", pointsFile)
      (code must beEqualTo(1)) and (err must contain("--distance-to"))
    }
  }

  "buildOptions" should {
    "forward exactly the threshold flags the user passed" >> {
      val conf = new TDA4jConf(Seq("--min-persistence", "0.25", "some-input-file"))
      TDA4jCLI.buildOptions(conf).toSeq must beEqualTo(Seq("minPersistence", "0.25"))
    }
  }
