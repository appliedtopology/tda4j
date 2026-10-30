package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.*

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** `--barcode-op`: the barcode algebra of this run's diagram and the one in `--barcode-with`, as `Barcode` computes it.
  */
class BarcodeAlgebraCLISpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("barcode-op-cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def run(args: Seq[String]): (Int, Seq[String], String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = TDA4jCLI.run(args, new PrintStream(out), new PrintStream(err))
    (code, out.toString.linesIterator.toSeq, err.toString)

  // two points a distance 1 apart: one bar [0, 1) and one that never dies, in degree 0
  private val pair = Array(Array(0.0, 0.0), Array(1.0, 0.0))

  private def pairPaths() =
    val points = tempFile(".csv")
    val diagram = tempFile(".csv")
    CSV.writePointCloud(points, pair)
    CSV.writePersistenceDiagram(diagram, Seq(PersistenceBar[Double](0, 0.0, 1.0), PersistenceBar[Double](0, 0.0)))
    (points, diagram)

  "--barcode-op" should {
    "write the product of the diagram with the one in --barcode-with: the Tor class of the sum, [1, 2) in degree 1" >> {
      val (points, diagram) = pairPaths()
      val (code, lines, err) = run(
        Seq("--barcode-op", "product-sum", "--barcode-with", diagram, "--max-dimension", "1", points)
      )
      ((code aka err) must beEqualTo(0)) and (lines must contain("1,1.0,2.0")) and (lines.size must beEqualTo(5))
    }

    "write the maximum product, a dual and the number of maps" >> {
      val (points, diagram) = pairPaths()
      val (_, product, _) =
        run(Seq("--barcode-op", "product-max", "--barcode-with", diagram, "--max-dimension", "1", points))
      val (_, dual, _) = run(Seq("--barcode-op", "dual", "--max-dimension", "1", points))
      val (_, maps, _) =
        run(Seq("--barcode-op", "hom-dimension", "--barcode-with", diagram, "--max-dimension", "1", points))
      (product.size must beEqualTo(4)) and (dual.size must beEqualTo(2)) and (dual.exists(
        _.contains("-1.0")
      ) must beTrue) and
        (maps must beEqualTo(Seq("3")))
    }

    "name what is wrong: no second barcode, one given to dual, another operation, a flag that does not go with it" >> {
      val (points, diagram) = pairPaths()
      def message(args: Seq[String]) =
        val (code, _, err) = run(args :+ points)
        (code, err)
      (message(Seq("--barcode-op", "hom"))._2 must contain("--barcode-with")) and
        (message(Seq("--barcode-op", "dual", "--barcode-with", diagram))._2 must contain("one barcode")) and
        (message(Seq("--barcode-op", "tensor", "--barcode-with", diagram))._2 must contain("unrecognized")) and
        (message(Seq("--barcode-with", diagram))._2 must contain("goes with --barcode-op")) and
        (message(Seq("--barcode-op", "dual", "--representatives"))._2 must contain("--representatives"))
    }
  }
end BarcodeAlgebraCLISpec
