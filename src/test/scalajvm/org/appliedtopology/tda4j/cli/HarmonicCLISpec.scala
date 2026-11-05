package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.TDA4j

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** `--harmonic chains` and `--harmonic representatives`: the facade's bars of the same arguments, written as any result
  * is, with the flags of the computations they are not refused by name.
  */
class HarmonicCLISpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("harmonic-cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def run(args: Seq[String]): (Int, Seq[String], String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = TDA4jCLI.run(args, new PrintStream(out), new PrintStream(err))
    (code, out.toString.linesIterator.toSeq, err.toString)

  private val points =
    val rng = new scala.util.Random(8L)
    Array.fill(9)(Array(rng.nextDouble(), rng.nextDouble()))

  private val distances = Array.tabulate(points.length, points.length)((i, j) =>
    math.sqrt(points(i).zip(points(j)).map((a, b) => (a - b) * (a - b)).sum)
  )

  /** The text lines the CLI writes for the facade's `result`, which the default threshold has already narrowed. */
  private def linesOf(result: matlab.PersistenceResult): Seq[String] =
    (0 until result.size()).map { i =>
      val upper = if result.death(i).isPosInfinity then "∞)" else s"${result.death(i)})"
      s"${result.dimension(i)}: [${result.birth(i)},$upper"
    }

  "--harmonic" should {
    "write the facade's harmonic chain barcode, and the ordinary bars with harmonic representatives" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)
      val (code, chains, err) = run(Seq("--harmonic", "chains", "--max-dimension", "1", path))
      val (codeR, representatives, errR) = run(Seq("--harmonic", "representatives", "--max-dimension", "1", path))
      val harmonicBars = linesOf(TDA4j.harmonicChainBarcodeFromPoints(points, Array("maxDimension", "1")))
      val ordinaryBars = linesOf(TDA4j.harmonicRepresentativesFromPoints(points, Array("maxDimension", "1")))
      ((code aka err) must beEqualTo(0)) and ((codeR aka errR) must beEqualTo(0)) and
        (chains must beEqualTo(harmonicBars)) and (chains must not(beEmpty)) and
        (representatives must beEqualTo(ordinaryBars))
    }

    "give each bar's chain with --representatives, over the reals or the rationals, from a matrix as from points" >> {
      val pointsPath = tempFile(".csv")
      val matrixPath = tempFile(".csv")
      CSV.writePointCloud(pointsPath, points)
      CSV.writeFullDistanceMatrix(matrixPath, distances)
      val base = Seq("--harmonic", "chains", "--max-dimension", "1", "--representatives")
      val (code, byPoints, err) = run(base :+ pointsPath)
      val (_, exact, _) = run(base ++ Seq("--field", "Q", pointsPath))
      val (codeM, byMatrix, errM) = run(base ++ Seq("--input-format", "csv-distances", matrixPath))
      ((code aka err) must beEqualTo(0)) and ((codeM aka errM) must beEqualTo(0)) and
        // the chain is written after each bar's interval
        (byPoints.map(_.length).sum must beGreaterThan(
          linesOf(TDA4j.harmonicChainBarcodeFromPoints(points)).map(_.length).sum
        )) and
        (byPoints.forall(_.contains("[")) must beTrue) and (exact must not(beEmpty)) and
        (byMatrix must beEqualTo(byPoints))
    }

    "refuse a finite field, the flags of other complexes, other modes, landmarks, an image and a saved result" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)
      val saved = tempFile(".json")
      TDA4j.computeFromPoints(points, Array("maxDimension", "1")).save(saved)
      val image = tempFile(".txt")
      java.nio.file.Files.write(java.nio.file.Paths.get(image), "2\n3\n3\n0\n0\n0\n0\n-1\n0\n0\n0\n0\n".getBytes)
      val refused = List(
        Seq("--harmonic", "chains", "--field", "Z", path),
        Seq("--harmonic", "chains", "--engine", "ripser", path),
        Seq("--harmonic", "representatives", "--include-zero-length", "true", path),
        Seq("--harmonic", "chains", "--representative-type", "cocycles", path),
        Seq("--harmonic", "chains", "--relative-to", "0,3", path),
        Seq("--harmonic", "chains", "--euler-curve", path),
        Seq("--harmonic", "chains", "--select-landmarks", "--num-landmarks", "3", path),
        Seq("--harmonic", "chains", saved),
        Seq("--harmonic", "chains", "--input-format", "perseus-cubical", image),
        Seq("--harmonic", "all", path)
      ).map(run)
      (refused.map(_._1) must beEqualTo(List.fill(10)(1))) and
        (refused(0)._3 must contain("inner product")) and
        (refused(1)._3 must contain("--engine")) and
        (refused(2)._3 must contain("--include-zero-length")) and
        (refused(5)._3 must contain("modes of their own")) and
        (refused(9)._3 must contain("'chains' or 'representatives'"))
    }
  }
