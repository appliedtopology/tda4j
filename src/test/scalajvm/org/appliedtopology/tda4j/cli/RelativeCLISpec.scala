package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.TDA4j

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** `--relative-to`: the Vietoris-Rips pair modulo the subcomplex on some points, as the facade computes it from the
  * same arguments; the flag refuses what it does not do.
  */
class RelativeCLISpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("relative-cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def run(args: Seq[String]): (Int, Seq[String], String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = TDA4jCLI.run(args, new PrintStream(out), new PrintStream(err))
    (code, out.toString.linesIterator.toSeq, err.toString)

  private val points =
    val rng = new scala.util.Random(6L)
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

  "--relative-to" should {
    "write the facade's pair of a point cloud, for the same options" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)
      val (code, lines, err) = run(Seq("--relative-to", "0,3,5", "--max-dimension", "1", path))
      val expected = linesOf(TDA4j.computeRelativeFromPoints(points, Array(0, 3, 5), Array("maxDimension", "1")))
      ((code aka err) must beEqualTo(0)) and (lines must beEqualTo(expected)) and (lines must not(beEmpty))
    }

    "differ from the absolute barcode, and take a distance matrix" >> {
      val pointsPath = tempFile(".csv")
      val matrixPath = tempFile(".csv")
      CSV.writePointCloud(pointsPath, points)
      CSV.writeFullDistanceMatrix(matrixPath, distances)
      val (_, absolute, _) = run(Seq("--max-dimension", "1", pointsPath))
      val (_, byPoints, _) = run(Seq("--relative-to", "0,3,5", "--max-dimension", "1", pointsPath))
      val (code, byMatrix, err) =
        run(Seq("--relative-to", "0,3,5", "--max-dimension", "1", "--input-format", "csv-distances", matrixPath))
      ((code aka err) must beEqualTo(0)) and (byPoints must not(beEqualTo(absolute))) and
        (byMatrix must beEqualTo(byPoints))
    }

    "refuse a complex, landmarks, an image, a saved result, a list that is not numbers and a point that is not there" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points)
      val saved = tempFile(".json")
      TDA4j.computeFromPoints(points, Array("maxDimension", "1")).save(saved)
      val image = tempFile(".txt")
      java.nio.file.Files.write(java.nio.file.Paths.get(image), "2\n3\n3\n0\n0\n0\n0\n-1\n0\n0\n0\n0\n".getBytes)
      val refused = List(
        Seq("--relative-to", "0,3", "--complex", "alpha", path),
        Seq("--relative-to", "0,3", "--select-landmarks", "--num-landmarks", "3", path),
        Seq("--relative-to", "0,3", saved),
        Seq("--relative-to", "0,3", "--input-format", "perseus-cubical", image),
        Seq("--relative-to", "0,x", path),
        Seq("--relative-to", "0,9", path)
      ).map(run)
      (refused.map(_._1) must beEqualTo(List.fill(6)(1))) and
        (refused(5)._3 must contain("out of range"))
    }
  }
