package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.TDA4j

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** `--bar-cells`: each text line of a bar gets the cell that opens it and the cell that closes it, as the facade's
  * `birthCell` and `deathCell` give them; the flag refuses what has no cells to print.
  */
class BarCellsCLISpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("bar-cells-cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def run(args: Seq[String]): (Int, Seq[String], String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = TDA4jCLI.run(args, new PrintStream(out), new PrintStream(err))
    (code, out.toString.linesIterator.toSeq, err.toString)

  private val circle = Array.tabulate(8)(i => Array(math.cos(2 * math.Pi * i / 8), math.sin(2 * math.Pi * i / 8)))

  private def brackets(cell: Array[Int]) = cell.mkString("[", ",", "]")

  "--bar-cells" should {
    "append the facade's birth and death cell to each bar" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, circle)
      val (code, lines, _) = run(Seq("--max-dimension", "1", "--bar-cells", path))
      val result = TDA4j.computeFromPoints(circle, Array("maxDimension", "1"))
      val suffixes = (0 until result.size()).map { i =>
        val died = result.deathCell(i)
        s"   cells: birth ${brackets(result.birthCell(i))} death ${if died.isEmpty then "none" else brackets(died)}"
      }
      (code must beEqualTo(0)) and (lines.length must beEqualTo(result.size())) and
        (lines.zip(suffixes).map((line, suffix) => line.endsWith(suffix)) must contain(beTrue).forall) and
        (lines.exists(_.endsWith("death none")) must beTrue) // the essential bars
    }

    "go with --representatives, after it" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, circle)
      val (code, lines, _) = run(Seq("--max-dimension", "1", "--representatives", "--bar-cells", path))
      (code must beEqualTo(0)) and (lines.forall(l => l.indexOf("rep:") < l.indexOf("cells:")) must beTrue)
    }

    "say when the engine recorded no cells" >> {
      val path = tempFile(".txt")
      // a 3x3 ring around a missing pixel, in Perseus's format
      java.nio.file.Files.write(java.nio.file.Paths.get(path), "2\n3\n3\n0\n0\n0\n0\n-1\n0\n0\n0\n0\n".getBytes)
      val (code, lines, err) =
        run(Seq("--input-format", "perseus-cubical", "--engine", "fast-cubical", "--bar-cells", path))
      (code aka err must beEqualTo(0)) and (lines must not(beEmpty)) and
        (lines.forall(_.endsWith("cells: (no cells recorded)")) must beTrue)
    }

    "be refused where there is no list of bars to put cells on, and with a format that has no place for them" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, circle)
      val refused = List(
        Seq("--select-landmarks", "--num-landmarks", "3", "--bar-cells", path),
        Seq("--distance-to", path, "--bar-cells", path),
        Seq("--euler-curve", "--bar-cells", path),
        Seq("--output-format", "csv", "--output", tempFile(".csv"), "--bar-cells", path)
      ).map(run)
      refused.map(_._1) must beEqualTo(List(1, 1, 1, 1))
    }
  }
