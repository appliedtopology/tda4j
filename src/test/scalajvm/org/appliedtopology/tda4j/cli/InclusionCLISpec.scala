package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.TDA4j

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** `--inclusion-of`: the image, kernel and cokernel of the map induced by the inclusion of a subsample, as the facade
  * computes them from the same arguments; a mode of its own, so it refuses the flags that would change another
  * computation.
  */
class InclusionCLISpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("inclusion-cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def run(args: Seq[String]): (Int, Seq[String], String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = TDA4jCLI.run(args, new PrintStream(out), new PrintStream(err))
    (code, out.toString.linesIterator.toSeq, err.toString)

  private val circle = Array.tabulate(24)(i => Array(math.cos(2 * math.Pi * i / 24), math.sin(2 * math.Pi * i / 24)))

  "--inclusion-of" should {
    "write the facade's image, kernel and cokernel, a line a bar" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, circle)
      val (code, lines, err) =
        run(Seq("--inclusion-of", "0,6,12,18", "--max-filtration-value", "2.5", "--max-dimension", "1", path))
      val map = TDA4j.computeInclusionFromPoints(
        circle,
        Array(0, 6, 12, 18),
        Array("maxFiltrationValue", "2.5", "maxDimension", "1")
      )
      def bar(kind: String, k: Int, b: Array[Double]) = s"$kind $k: [${b(0)},${b(1)})"
      val expected = for
        k <- 0 to 1
        (kind, bars) <- Seq("image" -> map.image(k), "kernel" -> map.kernel(k), "cokernel" -> map.cokernel(k))
        b <- bars.toSeq
      yield if b(1).isInfinite then s"$kind $k: [${b(0)},∞)" else bar(kind, k, b)
      ((code aka err) must beEqualTo(0)) and (lines must beEqualTo(expected)) and
        (lines.filter(_.startsWith("image 1")).size must beEqualTo(1)) and
        (lines.filter(_.startsWith("kernel 1")).size must beEqualTo(1)) and
        (lines.filter(_.startsWith("cokernel 1")).size must beEqualTo(1))
    }

    "take a distance matrix, and --output" >> {
      val matrix = Array.tabulate(24, 24)((i, j) =>
        math.sqrt(
          (circle(i)(0) - circle(j)(0)) * (circle(i)(0) - circle(j)(0)) + (circle(i)(1) - circle(j)(1)) * (circle(i)(
            1
          ) - circle(j)(1))
        )
      )
      val pointsPath = tempFile(".csv")
      val matrixPath = tempFile(".csv")
      val outPath = tempFile(".txt")
      CSV.writePointCloud(pointsPath, circle)
      CSV.writeFullDistanceMatrix(matrixPath, matrix)
      val (_, byPoints, _) = run(Seq("--inclusion-of", "0,6,12,18", "--max-dimension", "1", pointsPath))
      val (code, _, err) = run(
        Seq(
          "--inclusion-of",
          "0,6,12,18",
          "--max-dimension",
          "1",
          "--input-format",
          "csv-distances",
          "--output",
          outPath,
          matrixPath
        )
      )
      ((code aka err) must beEqualTo(0)) and (java.nio.file.Files
        .readAllLines(java.nio.file.Paths.get(outPath))
        .toArray
        .toSeq must beEqualTo(byPoints))
    }

    "refuse another mode, a flag that changes another computation, an image and a subsample that is not there" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, circle)
      val image = tempFile(".txt")
      java.nio.file.Files.write(java.nio.file.Paths.get(image), "2\n3\n3\n0\n0\n0\n0\n-1\n0\n0\n0\n0\n".getBytes)
      val refused = List(
        Seq("--inclusion-of", "0,6", "--euler-curve", path),
        Seq("--inclusion-of", "0,6", "--complex", "alpha", path),
        Seq("--inclusion-of", "0,6", "--relative-to", "0", path),
        Seq("--inclusion-of", "0,6", "--representatives", path),
        Seq("--inclusion-of", "0,6", "--engine", "naive", path),
        Seq("--inclusion-of", "0,6", "--input-format", "perseus-cubical", image),
        Seq("--inclusion-of", "0,24", path)
      ).map(run)
      (refused.map(_._1) must beEqualTo(List.fill(7)(1))) and (refused(6)._3 must contain("out of range"))
    }
  }
