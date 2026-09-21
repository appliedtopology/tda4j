package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.barcode.{given, *}
import org.appliedtopology.tda4j.io.{given, *}
import org.appliedtopology.tda4j.matlab.{PersistenceResult, Tda4j}

import org.rogach.scallop.ScallopOption

import java.io.PrintWriter
import scala.collection.mutable

/** The `tda4j` executable: a command-line front end for `org.appliedtopology.tda4j.matlab.Tda4j`, the same facade
  * the MATLAB bridge uses -- see that class's own doc for what every `--complex`/`--engine`/`--field`/etc. option
  * actually means, and `.claude/WORKLOG-cli-executable.md` for why this file's own logic is split the way it is
  * (`run` returns an exit code rather than calling `sys.exit` itself, specifically so `CliSpec` can call it
  * in-process without killing the test JVM -- `main` is the only place that actually exits).
  *
  * Built via `sbt assembly` into a runnable fat jar (`java -jar target/scala-3.9.0/TDA4j-<version>-assembly.jar
  * [options] <input-file>`) -- see `build.sbt`'s `assembly / mainClass` setting.
  */
object Tda4jCli:
  def main(args: Array[String]): Unit =
    val exitCode = run(args.toIndexedSeq, System.out)
    if exitCode != 0 then sys.exit(exitCode)

  /** The whole CLI, minus process exit -- `main` is a two-line wrapper around this. Returns `0` on success, `1` on
    * any recognized failure (a bad `Tda4j` option value, a malformed input file, an unsupported `--output-format`
    * combination), printing a one-line `tda4j: <message>` to `System.err` in that case rather than a stack trace,
    * matching `Tda4j`'s own convention of throwing `IllegalArgumentException` with an actionable message rather
    * than silently falling back to a default.
    *
    * '''Known, deliberate limitation, checked directly rather than assumed''': a PARSE-level error -- a malformed
    * flag (`--max-dimension notanumber`), a missing required argument, or `--help`/`--version` themselves --
    * is NOT caught here at all. `new Tda4jConf(args)` (inside `Scallop`'s `verify()`) calls Scallop's own default
    * `onError`, which prints directly to stdout/stderr and calls `System.exit` unconditionally, before this method
    * ever gets control back -- confirmed by tracing Scallop's own `ScallopConfBase.onError`/`exitHandler` source,
    * not assumed from the library's documentation. This is the normal, desired behavior for a real CLI invocation
    * (`main`, a fresh process) and IS what a real user sees for e.g. `--help` -- but it means `run` is not safe to
    * call from a long-lived embedding process (a test suite included) with input that could hit this path; Scallop
    * does offer an escape hatch for exactly this (`org.rogach.scallop.throwError`, a `DynamicVariable[Boolean]`
    * that makes `onError` re-throw instead of exiting), deliberately not used here since it would also have to
    * reimplement `--help`/`--version`'s own printing by hand to keep working -- a real cost for a case `CliSpec`
    * simply avoids exercising instead. See `.claude/WORKLOG-cli-executable.md`.
    */
  private[cli] def run(args: Seq[String], out: java.io.PrintStream): Int =
    try
      val conf = new Tda4jConf(args)
      val options = buildOptions(conf)
      val result = resolveInput(conf.inputFormat(), conf.input()) match
        case Left(points)     => Tda4j.computeFromPoints(points, options)
        case Right(distances) => Tda4j.computeFromDistanceMatrix(distances, options)
      writeOutput(conf, result, out)
      0
    catch
      case e: IllegalArgumentException =>
        System.err.println(s"tda4j: ${e.getMessage}")
        1

  // -----------------------------------------------------------------------------------------------------------
  // Scallop -> Tda4j's flat key/value options array. Every key is omitted entirely when the user didn't pass the
  // corresponding flag, rather than filled in with a CLI-side default matching Tda4j's own -- so there is exactly
  // ONE place (Tda4j.dispatch) that knows what "unset" means for any given option, and it can never silently
  // drift out of sync with this file.
  // -----------------------------------------------------------------------------------------------------------

  private[cli] def buildOptions(conf: Tda4jConf): Array[String] =
    val pairs = mutable.ArrayBuffer.empty[String]
    def add[T](key: String, opt: ScallopOption[T]): Unit =
      opt.toOption.foreach { v =>
        pairs += key
        pairs += v.toString
      }
    add("complex", conf.complex)
    add("engine", conf.engine)
    add("alphaBackend", conf.alphaBackend)
    add("maxDimension", conf.maxDimension)
    add("maxFiltrationValue", conf.maxFiltrationValue)
    add("field", conf.field)
    add("prime", conf.prime)
    add("epsilon", conf.epsilon)
    pairs.toArray

  // -----------------------------------------------------------------------------------------------------------
  // input loading -- one --input-format value maps to exactly one io.* method and exactly one of
  // points-vs-distances, so there is no separate "kind" flag to keep consistent with the format choice.
  // -----------------------------------------------------------------------------------------------------------

  private[cli] def resolveInput(format: String, path: String): Either[Array[Array[Double]], Array[Array[Double]]] =
    format match
      case "csv-points"    => Left(Csv.readPointCloud(path))
      case "csv-distances" => Right(Csv.readFullDistanceMatrix(path))
      case "csv-lower"     => Right(Csv.readLowerTriangularDistanceMatrix(path))
      case "ripser-points" => Left(Ripser.readPointCloud(path))
      case "ripser-lower"  => Right(Ripser.readLowerDistanceMatrix(path))
      case "ripser-upper"  => Right(Ripser.readUpperDistanceMatrix(path))
      case "ripser-distance" => Right(Ripser.readDistanceMatrix(path))
      case "ripser-binary" => Right(Ripser.readBinaryLowerDistanceMatrix(path))
      case "dipha-distance" => Right(Dipha.readDistanceMatrix(path))
      case "off"            => Left(Gudhi.readOff(path))
      case other =>
        throw new IllegalArgumentException(
          s"unrecognized --input-format '$other'; expected one of csv-points, csv-distances, csv-lower, " +
            "ripser-points, ripser-lower, ripser-upper, ripser-distance, ripser-binary, dipha-distance, off"
        )

  // -----------------------------------------------------------------------------------------------------------
  // PersistenceResult -> PersistenceBar, using the SAME PersistenceBar.apply(dim, lower, [upper]) factories
  // Tda4j's own bars were built from -- see io/Endpoints.scala's doc for why this matters: reconstructing a
  // finite bar as ClosedEndpoint/ClosedEndpoint instead of the half-open ClosedEndpoint/OpenEndpoint convention
  // is exactly the class of bug that silently broke round-tripping in that module.
  // -----------------------------------------------------------------------------------------------------------

  private[cli] def toBars(result: PersistenceResult): IndexedSeq[PersistenceBar[Double, Nothing]] =
    (0 until result.size()).map { i =>
      val dim = result.dimension(i)
      val birth = result.birth(i)
      val death = result.death(i)
      if death.isPosInfinity then PersistenceBar[Double](dim, birth) else PersistenceBar[Double](dim, birth, death)
    }

  // -----------------------------------------------------------------------------------------------------------
  // output
  // -----------------------------------------------------------------------------------------------------------

  private def requireOutput(conf: Tda4jConf, format: String): String =
    conf.output.toOption.getOrElse(
      throw new IllegalArgumentException(s"--output is required for --output-format=$format")
    )

  /** Perseus's own persistence-interval format stores INTEGER filtration-step indices, not raw filtration values
    * (see `Perseus.writePersistenceIntervals`'s own doc) -- silently rounding a typical Vietoris-Rips barcode's
    * real-valued birth/death (often well under 1.0) would collapse nearly every bar to `0 0` and produce output
    * that LOOKS valid but reports no real information. Refused outright with an actionable message rather than
    * shipped as an equal-looking output choice; a caller who genuinely has integer-valued bars (e.g. hand-built
    * from an already-step-indexed source) can still use it.
    */
  private def requireIntegralForPerseus(bars: IndexedSeq[PersistenceBar[Double, Nothing]]): Unit =
    def isIntegral(v: Double): Boolean = v == math.round(v).toDouble
    def endpointIsIntegral(e: BarcodeEndpoint[Double]): Boolean = e match
      case ClosedEndpoint(v) => isIntegral(v)
      case OpenEndpoint(v)   => isIntegral(v)
      case _                 => true // +-infinity: fine, Perseus.writePersistenceIntervals handles these itself
    if bars.exists(b => !endpointIsIntegral(b.lower) || !endpointIsIntegral(b.upper)) then
      throw new IllegalArgumentException(
        "--output-format=perseus refuses non-integer birth/death values: Perseus's own format stores filtration " +
          "STEP INDICES, not raw filtration values, and rounding a typical real-valued Vietoris-Rips/alpha " +
          "barcode would silently collapse most bars to 0 0. Use --output-format=csv/gudhi/dipha/text instead, " +
          "or pre-quantize your own filtration to integer steps before computing."
      )

  private def formatRepresentative(result: PersistenceResult, i: Int): String =
    try
      val vertices = result.cycleVertices(i)
      val coefficients = result.cycleCoefficients(i)
      vertices
        .zip(coefficients)
        .map { case (vs, c) => s"$c*${vs.mkString("[", ",", "]")}" }
        .mkString(" + ")
    catch case _: UnsupportedOperationException => "(no representative recorded)"

  private[cli] def writeOutput(conf: Tda4jConf, result: PersistenceResult, out: java.io.PrintStream): Unit =
    val bars = toBars(result)
    conf.outputFormat() match
      case "text" =>
        val lines = bars.zipWithIndex.map { case (bar, i) =>
          if conf.representatives() then s"$bar   rep: ${formatRepresentative(result, i)}" else bar.toString
        }
        conf.output.toOption match
          case Some(path) =>
            val writer = new PrintWriter(path)
            try lines.foreach(writer.println)
            finally writer.close()
          case None => lines.foreach(out.println)
      case "csv"    => Csv.writePersistenceDiagram(requireOutput(conf, "csv"), bars)
      case "gudhi"  => Gudhi.writePersistenceDiagram(requireOutput(conf, "gudhi"), bars)
      case "dipha"  => Dipha.writePersistenceDiagram(requireOutput(conf, "dipha"), bars)
      case "perseus" =>
        requireIntegralForPerseus(bars)
        Perseus.writePersistenceIntervals(requireOutput(conf, "perseus"), bars)
      case other =>
        throw new IllegalArgumentException(
          s"unrecognized --output-format '$other'; expected one of text, csv, gudhi, dipha, perseus"
        )
