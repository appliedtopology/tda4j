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
        (lines must contain(s"spectral-gap=${facade.spectralGap()}")) and
        (lines must contain(s"kernel-0=${facade.kernel().head.mkString(",")}")) and
        (lines.count(_.startsWith("kernel-")) must beEqualTo(1))
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
    "write the map's Laplacian with --laplacian-target and --laplacian-vertex-map, as the facade does" >> {
      val sourcePath = tempFile(".csv")
      val targetPath = tempFile(".csv")
      val segment = Array(Array(0.0, 0.0), Array(1.0, 0.0))
      CSV.writePointCloud(sourcePath, square)
      CSV.writePointCloud(targetPath, segment)
      val (code, lines, err) = run(
        Seq(
          "--persistent-laplacian",
          "--laplacian-dimension",
          "1",
          "--laplacian-from",
          "1.0",
          "--laplacian-to",
          "1.0",
          "--laplacian-target",
          targetPath,
          "--laplacian-vertex-map",
          "0,1,1,0",
          sourcePath
        )
      )
      val facade = TDA4j.persistentLaplacianOfMapFromPoints(square, segment, Array(0, 1, 1, 0), 1, 1.0, 1.0)
      ((code aka err) must beEqualTo(0)) and (lines must contain("betti-number=0")) and
        (lines must contain(s"eigenvalues=${facade.eigenvalues().mkString(",")}")) and
        (lines must contain("weights=2.0"))
    }

    "name what the map needs: the target goes with its vertex map and the other way round" >> {
      val sourcePath = tempFile(".csv")
      val targetPath = tempFile(".csv")
      CSV.writePointCloud(sourcePath, square)
      CSV.writePointCloud(targetPath, square)
      val base =
        Seq("--persistent-laplacian", "--laplacian-dimension", "1", "--laplacian-from", "1", "--laplacian-to", "1")
      def message(extra: Seq[String]) = run(base ++ extra :+ sourcePath)._3
      (message(Seq("--laplacian-target", targetPath)) must contain("--laplacian-vertex-map")) and
        (message(Seq("--laplacian-vertex-map", "0,1,2,3")) must contain("goes with --laplacian-target")) and
        (message(Seq("--laplacian-target", targetPath, "--laplacian-vertex-map", "0,1,x,3")) must contain("'x'")) and
        (message(Seq("--laplacian-target", targetPath, "--laplacian-vertex-map", "0,1")) must contain("entries"))
    }
  }
end PersistentLaplacianCLISpec
