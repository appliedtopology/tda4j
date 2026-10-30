package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.TDA4j

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** `--morse-reduction`: the facade's counts for the same arguments, a mode of its own that refuses the flags of other
  * computations.
  */
class MorseReductionCLISpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("morse-cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def run(args: Seq[String]): (Int, Seq[String], String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = TDA4jCLI.run(args, new PrintStream(out), new PrintStream(err))
    (code, out.toString.linesIterator.toSeq, err.toString)

  private val grid = Array.tabulate(16)(i => Array((i / 4).toDouble, (i % 4).toDouble))

  "--morse-reduction" should {
    "write the facade's counts as key=value lines, for either strategy" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, grid)
      List("reduction", "coreduction")
        .map { strategy =>
          val (code, lines, err) =
            run(Seq("--morse-reduction", "--morse-strategy", strategy, "--max-dimension", "2", path))
          val facade = TDA4j.morseReductionFromPoints(grid, Array("maxDimension", "2", "strategy", strategy))
          ((code aka err) must beEqualTo(0)) and (lines must contain(s"cells=${facade.cellCounts().mkString(",")}")) and
            (lines must contain(s"critical-cells=${facade.criticalCounts().mkString(",")}")) and
            (lines must contain(s"pairs=${facade.pairCount()}"))
        }
        .reduce(_ and _)
    }

    "refuse a strategy without the mode, other modes, and the flags of other computations" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, grid)
      def message(args: Seq[String]) = run(args :+ path)._3
      (message(Seq("--morse-strategy", "reduction")) must contain("only go with --morse-reduction")) and
        (message(Seq("--morse-reduction", "--euler-curve")) must contain("modes of their own")) and
        (message(Seq("--morse-reduction", "--representatives")) must contain("--representatives")) and
        (message(Seq("--morse-reduction", "--morse-strategy", "greedy")) must contain("strategy"))
    }
  }
end MorseReductionCLISpec
