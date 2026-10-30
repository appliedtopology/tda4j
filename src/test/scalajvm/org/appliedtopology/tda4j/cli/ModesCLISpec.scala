package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.TDA4j

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** The modes `--bootstrap-band` and `--tomato` and the input transformation `--takens-dimension`: each is the facade's
  * call of the same arguments (the guarantee of the CLI), and each refuses the flags that would change a computation it
  * does not do.
  */
class ModesCLISpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("modes-cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def run(args: Seq[String]): (Int, Seq[String], String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = TDA4jCLI.run(args, new PrintStream(out), new PrintStream(err))
    (code, out.toString.linesIterator.toSeq, err.toString)

  private def circle(n: Int) =
    Array.tabulate(n)(i => Array(math.cos(2 * math.Pi * i / n), math.sin(2 * math.Pi * i / n)))

  private val blobs =
    ((0 until 20).map(i => Array(0.1 * i, 0.0)) ++ (0 until 20).map(i => Array(50.0 + 0.1 * i, 0.0))).toArray

  "--bootstrap-band" should {
    "write the facade's band for the same options, as key=value lines" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, circle(30))
      val (code, lines, _) = run(Seq("--bootstrap-band", "--band-rounds", "6", "--band-seed", "2", path))
      val band = TDA4j.bootstrapBand(circle(30), Array("rounds", "6", "seed", "2"))
      (code must beEqualTo(0)) and (lines must beEqualTo(
        Seq("dimension=1", "rounds=6", "alpha=0.05", s"width=${band.width()}", s"threshold=${band.threshold()}")
      ))
    }

    "take --band-dimension, --band-alpha, --band-subsample-size, --prime and --output" >> {
      val path = tempFile(".csv")
      val outPath = tempFile(".txt")
      CSV.writePointCloud(path, circle(30))
      val (code, lines, _) = run(
        Seq(
          "--bootstrap-band",
          "--band-dimension",
          "0",
          "--band-alpha",
          "0.2",
          "--band-rounds",
          "5",
          "--band-subsample-size",
          "20",
          "--prime",
          "3",
          "--output",
          outPath,
          path
        )
      )
      val band = TDA4j.bootstrapBand(
        circle(30),
        Array("dimension", "0", "alpha", "0.2", "rounds", "5", "subsampleSize", "20", "prime", "3")
      )
      (code must beEqualTo(0)) and (lines must beEmpty) and
        (scala.io.Source.fromFile(outPath).getLines().toSeq.last must beEqualTo(s"threshold=${band.threshold()}"))
    }

    "refuse what it does not do, and its own flags without it" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, circle(30))
      val (codeA, _, errA) = run(Seq("--bootstrap-band", "--engine", "chunks", path))
      val (codeB, _, errB) = run(Seq("--band-rounds", "5", path))
      val (codeC, _, errC) = run(Seq("--bootstrap-band", "--tomato", path))
      val (codeD, _, errD) = run(Seq("--bootstrap-band", "--input-format", "csv-distances", path))
      (codeA must beEqualTo(1)) and (errA must contain("--engine")) and (errA must contain("--band-* flags")) and
        (codeB must beEqualTo(1)) and (errB must contain("--band-rounds only go with --bootstrap-band")) and
        (codeC must beEqualTo(1)) and (errC must contain("--bootstrap-band and --tomato are modes of their own")) and
        (codeD must beEqualTo(1))
    }
  }

  "--tomato" should {
    "write one cluster per point with --tomato-tau, the facade's labels" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, blobs)
      val (code, lines, _) = run(Seq("--tomato", "--tomato-k", "4", "--tomato-tau", "100", path))
      (code must beEqualTo(0)) and
        (lines must beEqualTo(TDA4j.tomatoClusters(blobs, 4, 100.0).toSeq.map(_.toString))) and
        (lines.toSet.size must beEqualTo(2))
    }

    "write the valleys as birth,death lines without --tomato-tau" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, blobs)
      val (code, lines, _) = run(Seq("--tomato", "--tomato-k", "4", path))
      (code must beEqualTo(0)) and (lines.head must beEqualTo("birth,death")) and
        (lines.tail must beEqualTo(TDA4j.tomatoDiagram(blobs, 4).toSeq.map(bar => s"${bar(0)},${bar(1)}")))
    }

    "need --tomato-k, and refuse flags it does not use" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, blobs)
      val (codeA, _, errA) = run(Seq("--tomato", path))
      val (codeB, _, errB) = run(Seq("--tomato", "--tomato-k", "4", "--max-dimension", "1", path))
      val (codeC, _, errC) = run(Seq("--tomato-tau", "1", path))
      (codeA must beEqualTo(1)) and (errA must contain("--tomato needs --tomato-k")) and
        (codeB must beEqualTo(1)) and (errB must contain("--max-dimension")) and
        (codeC must beEqualTo(1)) and (errC must contain("--tomato-tau only go with --tomato"))
    }
  }

  "--takens-dimension" should {
    val series = Array.tabulate(120)(t => math.sin(2 * math.Pi * t / 40))

    def seriesFile(rows: Array[Array[Double]]): String =
      val path = tempFile(".csv")
      CSV.writePointCloud(path, rows)
      path

    "compute on the facade's embedding of the series, given the delay" >> {
      val path = seriesFile(series.map(Array(_)))
      val embedded = seriesFile(TDA4j.takensEmbedding(series, 2, 10))
      val (codeA, linesA, _) = run(Seq("--takens-dimension", "2", "--takens-delay", "10", "--max-dimension", "1", path))
      val (codeB, linesB, _) = run(Seq("--max-dimension", "1", embedded))
      (codeA must beEqualTo(0)) and (codeB must beEqualTo(0)) and (linesA must beEqualTo(linesB)) and
        (linesA.exists(_.contains("1")) must beTrue)
    }

    "choose the delay of a one-column series by autocorrelation, and say so" >> {
      val path = seriesFile(series.map(Array(_)))
      val (code, lines, err) = run(Seq("--takens-dimension", "2", "--takens-max-lag", "30", "--euler-curve", path))
      val chosen = TDA4j.delayByAutocorrelation(series, 30)
      val direct = TDA4j.eulerCurveFromPoints(TDA4j.takensEmbedding(series, 2, chosen))
      (code must beEqualTo(0)) and (err must contain(s"delay $chosen chosen by autocorrelation")) and
        (lines.drop(1) must beEqualTo(direct.scales().zip(direct.values()).toSeq.map((s, v) => s"$s,$v")))
    }

    "embed a series of vectors, the windows' rows one after the other" >> {
      val vectors = Array.tabulate(30)(t => Array(math.sin(0.3 * t), math.cos(0.3 * t)))
      val path = seriesFile(vectors)
      val embedded = seriesFile(TDA4j.takensEmbeddingOfVectors(vectors, 3, 2))
      val (codeA, linesA, _) = run(Seq("--takens-dimension", "3", "--takens-delay", "2", "--euler-curve", path))
      val (codeB, linesB, _) = run(Seq("--euler-curve", embedded))
      (codeA must beEqualTo(0)) and (linesA must beEqualTo(linesB))
    }

    "refuse a delay given twice, a lag for vectors, its flags without it, and an input that is not points" >> {
      val path = seriesFile(series.map(Array(_)))
      val vectorPath = seriesFile(Array.tabulate(30)(t => Array(t.toDouble, 1.0)))
      val (codeA, _, errA) = run(Seq("--takens-dimension", "2", "--takens-delay", "3", "--takens-max-lag", "9", path))
      val (codeB, _, errB) = run(Seq("--takens-dimension", "2", "--takens-max-lag", "9", vectorPath))
      val (codeC, _, errC) = run(Seq("--takens-delay", "3", path))
      val (codeD, _, errD) = run(Seq("--takens-dimension", "2", "--input-format", "csv-relation", path))
      (codeA must beEqualTo(1)) and (errA must contain("are alternatives")) and
        (codeB must beEqualTo(1)) and (errB must contain("series of one column")) and
        (codeC must beEqualTo(1)) and (errC must contain("--takens-delay only go with --takens-dimension")) and
        (codeD must beEqualTo(1)) and (errD must contain("point-cloud input"))
    }
  }
end ModesCLISpec
