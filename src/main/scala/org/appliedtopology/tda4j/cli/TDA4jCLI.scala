package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.barcode.{given, *}
import org.appliedtopology.tda4j.io.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.matlab.{LandmarkSelectionResult, PersistenceResult, TDA4j}

import org.rogach.scallop.ScallopOption

import java.io.PrintWriter
import scala.collection.mutable

/** The `tda4j` executable: a command-line front end for `org.appliedtopology.tda4j.matlab.TDA4j`, the same facade the
  * MATLAB bridge uses -- see that class's own doc for what every `--complex`/`--engine`/`--field`/etc. option actually
  * means, and `.claude/WORKLOG-cli-executable.md` for why this file's own logic is split the way it is (`run` returns
  * an exit code rather than calling `sys.exit` itself, specifically so `CLISpec` can call it in-process without killing
  * the test JVM -- `main` is the only place that actually exits).
  *
  * Built via `sbt assembly` into a runnable fat jar (`java -jar target/scala-3.9.0/TDA4j-<version>-assembly.jar
  * [options] <input-file>`) -- see `build.sbt`'s `assembly / mainClass` setting.
  */
object TDA4jCLI:
  def main(args: Array[String]): Unit =
    val exitCode = run(args.toIndexedSeq, System.out)
    if exitCode != 0 then sys.exit(exitCode)

  /** The whole CLI, minus process exit -- `main` is a two-line wrapper around this. Returns `0` on success, `1` on any
    * recognized failure (a bad `TDA4j` option value, a malformed input file, an unsupported `--output-format`
    * combination), printing a one-line `tda4j: <message>` to `System.err` in that case rather than a stack trace,
    * matching `TDA4j`'s own convention of throwing `IllegalArgumentException` with an actionable message rather than
    * silently falling back to a default.
    *
    * '''Known, deliberate limitation, checked directly rather than assumed''': a PARSE-level error -- a malformed flag
    * (`--max-dimension notanumber`), a missing required argument, or `--help`/`--version` themselves -- is NOT caught
    * here at all. `new TDA4jConf(args)` (inside `Scallop`'s `verify()`) calls Scallop's own default `onError`, which
    * prints directly to stdout/stderr and calls `System.exit` unconditionally, before this method ever gets control
    * back -- confirmed by tracing Scallop's own `ScallopConfBase.onError`/`exitHandler` source, not assumed from the
    * library's documentation. This is the normal, desired behavior for a real CLI invocation (`main`, a fresh process)
    * and IS what a real user sees for e.g. `--help` -- but it means `run` is not safe to call from a long-lived
    * embedding process (a test suite included) with input that could hit this path; Scallop does offer an escape hatch
    * for exactly this (`org.rogach.scallop.throwError`, a `DynamicVariable[Boolean]` that makes `onError` re-throw
    * instead of exiting), deliberately not used here since it would also have to reimplement `--help`/`--version`'s own
    * printing by hand to keep working -- a real cost for a case `CLISpec` simply avoids exercising instead. See
    * `.claude/WORKLOG-cli-executable.md`.
    */
  private[cli] def run(args: Seq[String], out: java.io.PrintStream): Int =
    try
      val conf = new TDA4jConf(args)
      val options = buildOptions(conf)
      val resolved = resolveInput(conf.inputFormat(), conf.input())

      if conf.selectLandmarks() && conf.landmarksFile.isSupplied then
        throw new IllegalArgumentException(
          "--select-landmarks and --landmarks-file are mutually exclusive: the first PRODUCES a landmark set " +
            "(step 1), the second CONSUMES one (step 2) -- run them as two separate invocations"
        )

      // --complex is meaningless for a cubical grid (TDA4j.computeFromCubicalImage has no "complex" option at
      // all -- it would otherwise be silently ignored rather than validated, the exact "two flags can disagree
      // and nothing notices" trap this CLI's whole design (buildOptions's own doc above) exists to avoid). Same
      // reasoning for the two witness-recipe flags: a cubical grid has no landmarks to select or supply.
      resolved match
        case ResolvedInput.CubicalGrid(_, _) if conf.complex.isSupplied =>
          throw new IllegalArgumentException(
            s"--complex is not meaningful with --input-format=${conf.inputFormat()}: a cubical grid has no " +
              "'complex' option (see TDA4j.computeFromCubicalImage's own doc) -- remove --complex, or choose a " +
              "point-cloud/distance-matrix --input-format instead"
          )
        case ResolvedInput.CubicalGrid(_, _) if conf.selectLandmarks() || conf.landmarksFile.isSupplied =>
          throw new IllegalArgumentException(
            s"--select-landmarks/--landmarks-file are not meaningful with --input-format=${conf.inputFormat()}: " +
              "a cubical grid has no witness-complex landmarks to select or supply"
          )
        case _ => ()

      if conf.selectLandmarks() then
        if conf.representatives() then
          throw new IllegalArgumentException(
            "--representatives is meaningless with --select-landmarks: there is no barcode here, only a " +
              "landmark set"
          )
        if conf.outputFormat() != "text" then
          throw new IllegalArgumentException(
            s"--output-format=${conf.outputFormat()} is not supported with --select-landmarks -- only the " +
              "default text format is (one 0-based landmark index per line, plus a '# coveringRadius=...' line)"
          )
        val selection = resolved match
          case ResolvedInput.Points(points)       => TDA4j.selectLandmarksFromPoints(points, options)
          case ResolvedInput.Distances(distances) => TDA4j.selectLandmarksFromDistanceMatrix(distances, options)
          case ResolvedInput.CubicalGrid(_, _)    =>
            throw new IllegalStateException("unreachable: rejected by the CubicalGrid guard above")
        writeLandmarkSelection(conf, selection, out)
        0
      else
        val result = conf.landmarksFile.toOption match
          case Some(path) =>
            val landmarks = readLandmarksFile(path)
            resolved match
              case ResolvedInput.Points(points)       => TDA4j.computeFromPointsAndLandmarks(points, landmarks, options)
              case ResolvedInput.Distances(distances) =>
                TDA4j.computeFromDistanceMatrixAndLandmarks(distances, landmarks, options)
              case ResolvedInput.CubicalGrid(_, _) =>
                throw new IllegalStateException("unreachable: rejected by the CubicalGrid guard above")
          case None =>
            resolved match
              case ResolvedInput.Points(points)               => TDA4j.computeFromPoints(points, options)
              case ResolvedInput.Distances(distances)         => TDA4j.computeFromDistanceMatrix(distances, options)
              case ResolvedInput.CubicalGrid(shape, flatVals) => TDA4j.computeFromCubicalImage(shape, flatVals, options)
        writeOutput(conf, result, out)
        0
    catch
      case e: IllegalArgumentException =>
        System.err.println(s"tda4j: ${e.getMessage}")
        1
      case e: java.io.IOException =>
        // Covers FileNotFoundException (a missing/unreadable --input path OR --landmarks-file path) and any
        // other I/O failure from the io.* readers -- these are user-input problems (a typo'd path, a
        // permissions issue), not a `tda4j` bug, so they get the same clean one-line message as an
        // IllegalArgumentException rather than a raw stack trace.
        System.err.println(s"tda4j: ${e.getMessage}")
        1

  // -----------------------------------------------------------------------------------------------------------
  // Scallop -> TDA4j's flat key/value options array. Every key is omitted entirely when the user didn't pass the
  // corresponding flag, rather than filled in with a CLI-side default matching TDA4j's own -- so there is exactly
  // ONE place (TDA4j.dispatch) that knows what "unset" means for any given option, and it can never silently
  // drift out of sync with this file.
  // -----------------------------------------------------------------------------------------------------------

  private[cli] def buildOptions(conf: TDA4jConf): Array[String] =
    val pairs = mutable.ArrayBuffer.empty[String]
    def add[T](key: String, opt: ScallopOption[T]): Unit =
      opt.toOption.foreach { v =>
        pairs += key
        pairs += v.toString
      }
    add("complex", conf.complex)
    add("engine", conf.engine)
    add("alphaBackend", conf.alphaBackend)
    add("dtmK", conf.dtmK)
    add("dtmQ", conf.dtmQ)
    add("dtmP", conf.dtmP)
    add("sheehyEpsilon", conf.sheehyEpsilon)
    add("maxDimension", conf.maxDimension)
    add("maxFiltrationValue", conf.maxFiltrationValue)
    add("field", conf.field)
    add("prime", conf.prime)
    add("epsilon", conf.epsilon)
    add("sublevel", conf.sublevel)
    add("numLandmarks", conf.numLandmarks)
    add("witnessVariant", conf.witnessVariant)
    add("landmarkSelector", conf.landmarkSelector)
    add("landmarkSeed", conf.landmarkSeed)
    add("nu", conf.nu)
    pairs.toArray

  // -----------------------------------------------------------------------------------------------------------
  // input loading -- one --input-format value maps to exactly one io.* method and exactly one ResolvedInput
  // case, so there is no separate "kind" flag to keep consistent with the format choice. A three-way sealed
  // ADT, not a nested Either, since a cubical grid (shape + flat values) doesn't fit the "one Array[Array[Double]]
  // or the other" shape points/distances already share.
  // -----------------------------------------------------------------------------------------------------------

  private[cli] enum ResolvedInput:
    case Points(points: Array[Array[Double]])
    case Distances(distances: Array[Array[Double]])
    case CubicalGrid(shape: Array[Int], flatValues: Array[Double])

  /** Inverts `CubicalImage.fromFlatArray`'s own row-major, last-axis-fastest convention to recover a raw
    * `(shape, flatValues)` pair from an already-built `CubicalGridStream` -- used for `CubicalImage.fromFile` (a real
    * image/volume file, decoded via `javax.imageio`, so there's no raw-array layer underneath to read directly the way
    * `Perseus`/`Dipha`'s own text formats have). Always called with a stream built at `sublevel = true` (see the call
    * site below), so the recovered values are the ORIGINAL, un-negated ones: `TDA4j.computeFromCubicalImage` applies
    * the user's own `--sublevel` choice itself, exactly once -- reading with the caller's own `--sublevel` value here
    * too would silently double-apply it.
    */
  private[cli] def flattenGridStream(stream: CubicalGridStream): (Array[Int], Array[Double]) =
    val shape = stream.shape.toArray
    val strides = shape.scanRight(1)(_ * _).tail
    val total = shape.map(_.toLong).product.toInt
    val flatValues = Array.tabulate(total) { flatIdx =>
      val idx = shape.indices.map(axis => (flatIdx / strides(axis)) % shape(axis))
      stream.topCellValue(idx)
    }
    (shape, flatValues)

  private[cli] def resolveInput(format: String, path: String): ResolvedInput =
    format match
      case "csv-points"      => ResolvedInput.Points(CSV.readPointCloud(path))
      case "csv-distances"   => ResolvedInput.Distances(CSV.readFullDistanceMatrix(path))
      case "csv-lower"       => ResolvedInput.Distances(CSV.readLowerTriangularDistanceMatrix(path))
      case "ripser-points"   => ResolvedInput.Points(Ripser.readPointCloud(path))
      case "ripser-lower"    => ResolvedInput.Distances(Ripser.readLowerDistanceMatrix(path))
      case "ripser-upper"    => ResolvedInput.Distances(Ripser.readUpperDistanceMatrix(path))
      case "ripser-distance" => ResolvedInput.Distances(Ripser.readDistanceMatrix(path))
      case "ripser-binary"   => ResolvedInput.Distances(Ripser.readBinaryLowerDistanceMatrix(path))
      case "dipha-distance"  => ResolvedInput.Distances(Dipha.readDistanceMatrix(path))
      case "off"             => ResolvedInput.Points(Gudhi.readOff(path))
      case "perseus-cubical" =>
        val (shape, flatValues) = Perseus.readCubicalImageData(path)
        ResolvedInput.CubicalGrid(shape.toArray, flatValues.toArray)
      case "dipha-image" =>
        val (shape, flatValues) = Dipha.readImageData(path)
        ResolvedInput.CubicalGrid(shape.toArray, flatValues.toArray)
      case "image" =>
        val (shape, flatValues) = flattenGridStream(CubicalImage.fromFile(path, sublevel = true))
        ResolvedInput.CubicalGrid(shape, flatValues)
      case other =>
        throw new IllegalArgumentException(
          s"unrecognized --input-format '$other'; expected one of csv-points, csv-distances, csv-lower, " +
            "ripser-points, ripser-lower, ripser-upper, ripser-distance, ripser-binary, dipha-distance, off, " +
            "perseus-cubical, dipha-image, image"
        )

  // -----------------------------------------------------------------------------------------------------------
  // the two-step witness recipe's own file format: one 0-based landmark index per line, plus a leading
  // '# coveringRadius=...' comment line -- tda4j's own format (not an external one, so kept here rather than
  // in `io`, matching CLAUDE.md's io-module convention of only implementing formats verified against a real
  // primary source). Deliberately NOT reused for anything else: it exists purely to round-trip
  // --select-landmarks's own output back into --landmarks-file.
  // -----------------------------------------------------------------------------------------------------------

  private[cli] def writeLandmarkSelection(
    conf: TDA4jConf,
    selection: LandmarkSelectionResult,
    out: java.io.PrintStream
  ): Unit =
    val lines = s"# coveringRadius=${selection.coveringRadius()}" +: selection.landmarks().map(_.toString).toSeq
    conf.output.toOption match
      case Some(path) =>
        val writer = new PrintWriter(path)
        try lines.foreach(writer.println)
        finally writer.close()
      case None => lines.foreach(out.println)
    // Always to stderr, even when --output also wrote it as a comment line: the point is that a human running
    // this interactively sees R without having to open/grep the output file.
    System.err.println(
      s"tda4j: covering radius R = ${selection.coveringRadius()} -- e.g. pass '${2 * selection.coveringRadius()}' " +
        "as --max-filtration-value for step 2 (the JavaPlex tutorial's own 2R recipe)"
    )

  /** Reads a `--landmarks-file` written by `writeLandmarkSelection` above (or hand-edited in the same shape): one
    * 0-based landmark index per line, blank lines and `#`-prefixed comment lines ignored. Parse errors report the
    * OFFENDING LINE NUMBER (1-based, matching what a text editor shows), not just the bad token, since a hand-edited
    * landmarks file is exactly the case where "which line" matters for fixing it.
    */
  private[cli] def readLandmarksFile(path: String): Array[Int] =
    val source = scala.io.Source.fromFile(path)
    try
      source
        .getLines()
        .zipWithIndex
        .flatMap { case (raw, idx) =>
          val trimmed = raw.trim
          if trimmed.isEmpty || trimmed.startsWith("#") then None
          else
            Some(
              trimmed.toIntOption.getOrElse(
                throw new IllegalArgumentException(
                  s"$path:${idx + 1}: expected an integer landmark index, got '$raw'"
                )
              )
            )
        }
        .toArray
    finally source.close()

  // -----------------------------------------------------------------------------------------------------------
  // PersistenceResult -> PersistenceBar, using the SAME PersistenceBar.apply(dim, lower, [upper]) factories
  // TDA4j's own bars were built from -- see io/Endpoints.scala's doc for why this matters: reconstructing a
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

  private def requireOutput(conf: TDA4jConf, format: String): String =
    conf.output.toOption.getOrElse(
      throw new IllegalArgumentException(s"--output is required for --output-format=$format")
    )

  /** Perseus's own persistence-interval format stores INTEGER filtration-step indices, not raw filtration values (see
    * `Perseus.writePersistenceIntervals`'s own doc) -- silently rounding a typical Vietoris-Rips barcode's real-valued
    * birth/death (often well under 1.0) would collapse nearly every bar to `0 0` and produce output that LOOKS valid
    * but reports no real information. Refused outright with an actionable message rather than shipped as an
    * equal-looking output choice; a caller who genuinely has integer-valued bars (e.g. hand-built from an
    * already-step-indexed source) can still use it.
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

  private[cli] def writeOutput(conf: TDA4jConf, result: PersistenceResult, out: java.io.PrintStream): Unit =
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
      case "csv"     => CSV.writePersistenceDiagram(requireOutput(conf, "csv"), bars)
      case "gudhi"   => Gudhi.writePersistenceDiagram(requireOutput(conf, "gudhi"), bars)
      case "dipha"   => Dipha.writePersistenceDiagram(requireOutput(conf, "dipha"), bars)
      case "perseus" =>
        requireIntegralForPerseus(bars)
        Perseus.writePersistenceIntervals(requireOutput(conf, "perseus"), bars)
      case other =>
        throw new IllegalArgumentException(
          s"unrecognized --output-format '$other'; expected one of text, csv, gudhi, dipha, perseus"
        )
