package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.TDA4j

import org.specs2.mutable
import java.io.{ByteArrayOutputStream, File, PrintStream}

/** `--persistent-laplacian`: the facade's Betti number, spectral gap and eigenvalues of the same arguments, a mode of
  * its own that refuses the flags of other computations.
  */
class PersistentLaplacianCLISpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("laplacian-cli-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def run(args: Seq[String]): (Int, Seq[String], String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = TDA4jCLI.run(args, new PrintStream(out), new PrintStream(err))
    (code, out.toString.linesIterator.toSeq, err.toString)

  private val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))

  "--persistent-laplacian" should {
    "write the facade's numbers as key=value lines" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, square)
      val (code, lines, err) = run(
        Seq(
          "--persistent-laplacian",
          "--laplacian-dimension",
          "1",
          "--laplacian-from",
          "1.0",
          "--laplacian-to",
          "1.0",
          path
        )
      )
      val facade = TDA4j.persistentLaplacianFromPoints(square, 1, 1.0, 1.0)
      ((code aka err) must beEqualTo(0)) and (lines must contain("betti-number=1")) and
        (lines must contain(s"eigenvalues=${facade.eigenvalues().mkString(",")}")) and
        (lines must contain(s"spectral-gap=${facade.spectralGap()}"))
    }

    "name what is missing, what does not go with it, and the other modes" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, square)
      def message(args: Seq[String]) = run(args :+ path)._3
      (message(Seq("--persistent-laplacian", "--laplacian-dimension", "1")) must contain("--laplacian-from")) and
        (message(Seq("--laplacian-dimension", "1")) must contain("only go with --persistent-laplacian")) and
        (message(
          Seq(
            "--persistent-laplacian",
            "--euler-curve",
            "--laplacian-dimension",
            "1",
            "--laplacian-from",
            "0",
            "--laplacian-to",
            "1"
          )
        ) must contain("modes of their own")) and
        (message(
          Seq(
            "--persistent-laplacian",
            "--representatives",
            "--laplacian-dimension",
            "1",
            "--laplacian-from",
            "0",
            "--laplacian-to",
            "1"
          )
        ) must contain("--representatives"))
    }
  }
end PersistentLaplacianCLISpec
