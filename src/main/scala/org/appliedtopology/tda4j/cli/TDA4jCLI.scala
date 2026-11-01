package org.appliedtopology.tda4j
package cli

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.{LandmarkSelectionResult, PersistenceMapResult, PersistenceResult, TDA4j}
import org.appliedtopology.tda4j.plot.{
  Board,
  Figure,
  Labels,
  Look,
  Palette,
  Plot,
  Scale,
  Scene,
  Settings,
  Size,
  Style,
  Theme,
  Transform,
  Tweak,
  Viewer
}

import org.rogach.scallop.ScallopOption

import scala.collection.mutable

/** The `tda4j` executable, a command-line front end for [[org.appliedtopology.tda4j.matlab.TDA4j]] (flags in
  * [[TDA4jConf]]). Built by `sbt assembly`: `java -jar tda4j-<version>-assembly.jar [options] <input-file>`.
  */
object TDA4jCLI:
  def main(args: Array[String]): Unit =
    val exitCode = run(args.toIndexedSeq, System.out)
    if exitCode != 0 then ProcessExit.withCode(exitCode)

  /** The CLI without the process exit, so it can run in-process: returns `0` on success and `1` on a reported failure
    * (a bad option, a malformed file), which is printed as one line `tda4j: <message>` on `err` (standard error by
    * default).
    *
    * Parse errors, `--help` and `--version` are handled by Scallop, which prints and calls `System.exit` before this
    * method returns: do not call it with such arguments from a process that must keep running.
    */
  private[cli] def run(args: Seq[String], out: java.io.PrintStream, err: java.io.PrintStream = System.err): Int =
    try
      val conf = new TDA4jConf(args)
      val options = buildOptions(conf)
      val format = inputFormatOf(conf)
      checkPlotFlags(conf)
      if conf.maxSimplexDimension.isSupplied && !conf.eulerCurve() then
        throw new IllegalArgumentException("--max-simplex-dimension is for --euler-curve, which counts simplices")
      if conf.saveResult.isSupplied && conf.selectLandmarks() then
        throw new IllegalArgumentException(
          "--save-result is meaningless with --select-landmarks: there is no result here, only a landmark set"
        )
      checkModes(conf)
      val resolved = applyTakens(conf, format, resolveInput(format, conf.input()), err)

      if conf.selectLandmarks() && conf.landmarksFile.isSupplied then
        throw new IllegalArgumentException(
          "--select-landmarks and --landmarks-file are mutually exclusive: the first PRODUCES a landmark set " +
            "(step 1), the second CONSUMES one (step 2) -- run them as two separate invocations"
        )

      // A persistence threshold only means something for a reported barcode: --select-landmarks produces none, and
      // --distance-to always compares COMPLETE barcodes (a distance must not depend on this run's own
      // scale) -- silently ignoring a flag the user typed is exactly the trap this CLI avoids.
      if conf.minPersistence.isSupplied || conf.minPersistenceFraction.isSupplied then
        if conf.selectLandmarks() then
          throw new IllegalArgumentException(
            "--min-persistence/--min-persistence-fraction are meaningless with --select-landmarks: there is no " +
              "barcode here, only a landmark set"
          )
        if conf.distanceTo.isSupplied then
          throw new IllegalArgumentException(
            "--min-persistence/--min-persistence-fraction are meaningless with --distance-to: the comparison " +
              "always uses the complete barcode"
          )

      if conf.representativeType.isSupplied && conf.selectLandmarks() then
        throw new IllegalArgumentException(
          "--representative-type is meaningless with --select-landmarks: there is no barcode here, only a landmark set"
        )

      if conf.includeZeroLength.isSupplied && conf.selectLandmarks() then
        throw new IllegalArgumentException(
          "--include-zero-length is meaningless with --select-landmarks: there is no barcode here, only a landmark set"
        )

      if conf.barCells() && conf.outputFormat() != "text" then
        throw new IllegalArgumentException(
          s"--bar-cells adds the cells to the text output, and --output-format=${conf.outputFormat()} has no place for " +
            "them: use --output-format=text, or --save-result, which keeps them"
        )

      if conf.distanceTo.isSupplied then
        if conf.selectLandmarks() then
          throw new IllegalArgumentException(
            "--distance-to is meaningless with --select-landmarks: there is no barcode here, only a landmark set"
          )
        if conf.representatives() then
          throw new IllegalArgumentException(
            "--representatives is meaningless with --distance-to: a bottleneck/Wasserstein comparison has no " +
              "representative chains, only a distance per dimension"
          )
        if conf.barCells() then
          throw new IllegalArgumentException(
            "--bar-cells is meaningless with --distance-to: a bottleneck/Wasserstein comparison has no cells, only " +
              "a distance per dimension"
          )
        if conf.outputFormat() != "text" then
          throw new IllegalArgumentException(
            s"--output-format=${conf.outputFormat()} is not supported with --distance-to -- only the default " +
              "text format is (one 'dim <k>: bottleneck=... wasserstein=...' line per dimension)"
          )

      if conf.barcodeOp.isSupplied then
        val op = conf.barcodeOp()
        if !barcodeOps.contains(op) then
          throw new IllegalArgumentException(
            s"unrecognized --barcode-op '$op'; expected one of ${barcodeOps.mkString(", ")}"
          )
        if op != "dual" && !conf.barcodeWith.isSupplied then
          throw new IllegalArgumentException(s"--barcode-op $op needs --barcode-with, the second barcode")
        if op == "dual" && conf.barcodeWith.isSupplied then
          throw new IllegalArgumentException("--barcode-op dual takes one barcode: --barcode-with does not go with it")
        val others = Seq(
          "--distance-to" -> conf.distanceTo.isSupplied,
          "--select-landmarks" -> conf.selectLandmarks(),
          "--representatives" -> conf.representatives(),
          "--bar-cells" -> conf.barCells(),
          "--plot" -> conf.plot.isSupplied,
          "--min-persistence" -> conf.minPersistence.isSupplied,
          "--min-persistence-fraction" -> conf.minPersistenceFraction.isSupplied,
          "--output-format" -> (conf.outputFormat() != "text")
        ).collect { case (flag, true) => flag }
        if others.nonEmpty then
          throw new IllegalArgumentException(
            s"${others.mkString(", ")} cannot go with --barcode-op: it writes the bars of the barcode algebra, whole, as text"
          )
      else if conf.barcodeWith.isSupplied then
        throw new IllegalArgumentException("--barcode-with goes with --barcode-op")

      // --complex is meaningless for a cubical grid (TDA4j.computeFromCubicalImage has no "complex" option at
      // all -- it would otherwise be silently ignored rather than validated, the exact "two flags can disagree
      // and nothing notices" trap this CLI's whole design (buildOptions's own doc above) exists to avoid). Same
      // reasoning for the two witness-recipe flags: a cubical grid/Dowker relation has no landmarks to select or
      // supply, and --complex is equally meaningless for a Dowker relation (TDA4j.computeFromRelation has its
      // own, separate, much smaller option set -- see that method's own doc).
      if conf.relativeTo.isSupplied then
        if conf.selectLandmarks() || conf.landmarksFile.isSupplied then
          throw new IllegalArgumentException(
            "--relative-to computes the pair of Vietoris-Rips complexes, which has no landmarks: " +
              "--select-landmarks and --landmarks-file are for the witness complex"
          )
        resolved match
          case ResolvedInput.Points(_) | ResolvedInput.Distances(_) => ()
          case ResolvedInput.Result(_)                              =>
            throw new IllegalArgumentException(
              s"${conf.input()} is a saved result, computed already: --relative-to would change the computation. " +
                "Compute again from the original input"
            )
          case _ =>
            throw new IllegalArgumentException(
              s"--relative-to takes a point cloud or a distance matrix (the Vietoris-Rips complex), not --input-format=$format"
            )

      resolved match
        case ResolvedInput.Result(_) =>
          val flags = options.grouped(2).map(pair => flagName(pair(0))).toSeq ++
            (if conf.selectLandmarks() then Seq("--select-landmarks") else Nil) ++
            conf.landmarksFile.toOption.map(_ => "--landmarks-file")
          if flags.nonEmpty then
            throw new IllegalArgumentException(
              s"${conf.input()} is a saved result, computed already: ${flags.mkString(", ")} would change the " +
                "computation, so they do not apply to it. Compute again from the original input to change it"
            )
        case ResolvedInput.CubicalGrid(_, _) if conf.complex.isSupplied =>
          throw new IllegalArgumentException(
            s"--complex is not meaningful with --input-format=$format: a cubical grid has no " +
              "'complex' option. Remove --complex, or choose a " +
              "point-cloud/distance-matrix --input-format instead"
          )
        case ResolvedInput.CubicalGrid(_, _) if conf.selectLandmarks() || conf.landmarksFile.isSupplied =>
          throw new IllegalArgumentException(
            s"--select-landmarks/--landmarks-file are not meaningful with --input-format=$format: " +
              "a cubical grid has no witness-complex landmarks to select or supply"
          )
        case ResolvedInput.Relation(_) if conf.complex.isSupplied =>
          throw new IllegalArgumentException(
            s"--complex is not meaningful with --input-format=$format: TDA4j.computeFromRelation " +
              "builds the Dowker complex and takes no --complex. Remove --complex, or choose a " +
              "point-cloud/distance-matrix --input-format instead"
          )
        case ResolvedInput.Relation(_) if conf.selectLandmarks() || conf.landmarksFile.isSupplied =>
          throw new IllegalArgumentException(
            s"--select-landmarks/--landmarks-file are not meaningful with --input-format=$format: " +
              "a Dowker relation has no witness-complex landmarks to select or supply"
          )
        case _ => ()

      if conf.eulerCurve() then
        writeEulerCurve(conf, options, format, resolved, out)
        0
      else if conf.persistentLaplacian() then
        writePersistentLaplacian(conf, options, format, resolved, out)
        0
      else if conf.morseReduction() then
        writeMorseReduction(conf, options, format, resolved, out)
        0
      else if conf.bootstrapBand() then
        writeBootstrapBand(conf, options, format, resolved, out)
        0
      else if conf.tomato() then
        writeTomato(conf, options, format, resolved, out)
        0
      else if conf.inclusionOf.isSupplied then
        writeInclusion(conf, options, format, resolved, out)
        0
      else if conf.selectLandmarks() then
        if conf.representatives() then
          throw new IllegalArgumentException(
            "--representatives is meaningless with --select-landmarks: there is no barcode here, only a " +
              "landmark set"
          )
        if conf.barCells() then
          throw new IllegalArgumentException(
            "--bar-cells is meaningless with --select-landmarks: there is no barcode here, only a landmark set"
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
          case ResolvedInput.Relation(_) =>
            throw new IllegalStateException("unreachable: rejected by the Relation guard above")
          case ResolvedInput.Result(_) =>
            throw new IllegalStateException("unreachable: rejected by the Result guard above")
        writeLandmarkSelection(conf, selection, out, err)
        0
      else
        val result = conf.landmarksFile.toOption match
          case _ if conf.relativeTo.isSupplied =>
            val subcomplex = parseVertexList("--relative-to", conf.relativeTo())
            resolved match
              case ResolvedInput.Points(points)       => TDA4j.computeRelativeFromPoints(points, subcomplex, options)
              case ResolvedInput.Distances(distances) =>
                TDA4j.computeRelativeFromDistanceMatrix(distances, subcomplex, options)
              case _ => throw new IllegalStateException("unreachable: rejected by the --relative-to guard above")
          case Some(path) =>
            val landmarks = readLandmarksFile(path)
            resolved match
              case ResolvedInput.Points(points)       => TDA4j.computeFromPointsAndLandmarks(points, landmarks, options)
              case ResolvedInput.Distances(distances) =>
                TDA4j.computeFromDistanceMatrixAndLandmarks(distances, landmarks, options)
              case ResolvedInput.CubicalGrid(_, _) =>
                throw new IllegalStateException("unreachable: rejected by the CubicalGrid guard above")
              case ResolvedInput.Relation(_) =>
                throw new IllegalStateException("unreachable: rejected by the Relation guard above")
              case ResolvedInput.Result(_) =>
                throw new IllegalStateException("unreachable: rejected by the Result guard above")
          case None =>
            resolved match
              case ResolvedInput.Points(points)               => TDA4j.computeFromPoints(points, options)
              case ResolvedInput.Distances(distances)         => TDA4j.computeFromDistanceMatrix(distances, options)
              case ResolvedInput.CubicalGrid(shape, flatVals) => TDA4j.computeFromCubicalImage(shape, flatVals, options)
              case ResolvedInput.Relation(relation)           => TDA4j.computeFromRelation(relation, options)
              case ResolvedInput.Result(saved)                => saved
        val loaded = resolved.isInstanceOf[ResolvedInput.Result]
        // Saved first: a plot or output that fails must not lose a long computation.
        conf.saveResult.foreach(result.save)
        if conf.plot.isSupplied then writePlot(conf, result, loaded, err)
        else if conf.distanceTo.isSupplied then writeDistance(conf, result, out)
        else if conf.barcodeOp.isSupplied then writeBarcodeOp(conf, result, out)
        else
          writeOutput(conf, result, out)
          noteHiddenBars(result, loaded, err)
        0
    catch
      case e: IllegalArgumentException =>
        err.println(s"tda4j: ${e.getMessage}")
        1
      // what a result cannot draw (a representative with no coordinates, a complex the plots do not build)
      case e: UnsupportedOperationException =>
        err.println(s"tda4j: ${e.getMessage}")
        1
      case e: java.io.IOException =>
        // Covers FileNotFoundException (a missing/unreadable --input path OR --landmarks-file path) and any
        // other I/O failure from the io.* readers -- these are user-input problems (a typo'd path, a
        // permissions issue), not a `tda4j` bug, so they get the same clean one-line message as an
        // IllegalArgumentException rather than a raw stack trace.
        err.println(s"tda4j: ${e.getMessage}")
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
    add("threads", conf.threads)
    add("alphaBackend", conf.alphaBackend)
    add("requireValidTriangulation", conf.requireValidTriangulation)
    add("dtmK", conf.dtmK)
    add("dtmQ", conf.dtmQ)
    add("dtmP", conf.dtmP)
    add("sparseEpsilon", conf.sparseEpsilon)
    add("edgeCollapse", conf.edgeCollapse)
    add("maxDimension", conf.maxDimension)
    add("maxFiltrationValue", conf.maxFiltrationValue)
    add("minPersistence", conf.minPersistence)
    add("minPersistenceFraction", conf.minPersistenceFraction)
    add("includeZeroLength", conf.includeZeroLength)
    add("representativeType", conf.representativeType)
    add("field", conf.field)
    add("prime", conf.prime)
    add("epsilon", conf.epsilon)
    add("sublevel", conf.sublevel)
    add("nanMask", conf.nanMask)
    add("numLandmarks", conf.numLandmarks)
    add("witnessVariant", conf.witnessVariant)
    add("landmarkSelector", conf.landmarkSelector)
    add("landmarkSeed", conf.landmarkSeed)
    add("nu", conf.nu)
    add("dual", conf.dual)
    add("representativesOn", conf.representativesOn)
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
    case Relation(relation: Array[Array[Double]])
    case Result(result: PersistenceResult)

  /** `--input-format`, or `result` when it is not given and the file starts with `{` (a saved result is a JSON object;
    * no point cloud, matrix or image file starts so). A missing file is left to the reader, which says so.
    */
  private[cli] def inputFormatOf(conf: TDA4jConf): String =
    if conf.inputFormat.isSupplied then conf.inputFormat()
    else
      val start =
        try String(FileAccess.readHead(conf.input(), 256), "UTF-8")
        catch case _: java.io.IOException => ""
      if start.stripPrefix("\uFEFF").dropWhile(_.isWhitespace).startsWith("{") then "result" else conf.inputFormat()

  /** The flag of a facade option key: `maxDimension` is `--max-dimension`. */
  private[cli] def flagName(key: String): String =
    "--" + key.flatMap(c => if c.isUpper then s"-${c.toLower}" else c.toString)

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
      case "ripser-sparse"   => ResolvedInput.Distances(Ripser.readSparseDistanceMatrix(path))
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
      // Reuses CSV.readPointCloud outright, not a new reader: a Dowker relation is exactly the same shape
      // that format already parses (arbitrary rows x columns, no squareness requirement) -- the only
      // difference is what TDA4j entry point the resulting matrix is routed to (see `run`'s own match below),
      // not how it's read off disk.
      case "csv-relation" => ResolvedInput.Relation(CSV.readPointCloud(path))
      case "result"       => ResolvedInput.Result(TDA4j.loadResult(path))
      case other          =>
        throw new IllegalArgumentException(
          s"unrecognized --input-format '$other'; expected one of csv-points, csv-distances, csv-lower, " +
            "ripser-points, ripser-lower, ripser-upper, ripser-distance, ripser-sparse, ripser-binary, dipha-distance, off, " +
            "perseus-cubical, dipha-image, image, csv-relation, result"
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
    out: java.io.PrintStream,
    err: java.io.PrintStream = System.err
  ): Unit =
    val lines = s"# coveringRadius=${selection.coveringRadius()}" +: selection.landmarks().map(_.toString).toSeq
    conf.output.toOption match
      case Some(path) =>
        val writer = FileAccess.printWriter(path)
        try lines.foreach(writer.println)
        finally writer.close()
      case None => lines.foreach(out.println)
    // Always to stderr, even when --output also wrote it as a comment line: the point is that a human running
    // this interactively sees R without having to open/grep the output file.
    err.println(
      s"tda4j: covering radius R = ${selection.coveringRadius()} -- e.g. pass '${2 * selection.coveringRadius()}' " +
        "as --max-filtration-value for step 2 (the JavaPlex tutorial's own 2R recipe)"
    )

  private val eulerCurveOptions = Set("maxFiltrationValue", "threads")

  /** `--euler-curve`: the curve of the Vietoris-Rips complex of a point cloud or distance matrix, one `scale,value`
    * line for every scale where it steps. A flag that would change a computation this mode does not do is refused.
    */
  private[cli] def writeEulerCurve(
    conf: TDA4jConf,
    options: Array[String],
    format: String,
    resolved: ResolvedInput,
    out: java.io.PrintStream
  ): Unit =
    val others = Seq(
      "--plot" -> conf.plot.isSupplied,
      "--distance-to" -> conf.distanceTo.isSupplied,
      "--select-landmarks" -> conf.selectLandmarks(),
      "--landmarks-file" -> conf.landmarksFile.isSupplied,
      "--representatives" -> conf.representatives(),
      "--bar-cells" -> conf.barCells(),
      "--relative-to" -> conf.relativeTo.isSupplied,
      "--output-format" -> conf.outputFormat.isSupplied,
      "--save-result" -> conf.saveResult.isSupplied
    ).collect { case (flag, true) => flag } ++
      options.grouped(2).map(_(0)).filterNot(eulerCurveOptions).map(flagName)
    if others.nonEmpty then
      throw new IllegalArgumentException(
        s"--euler-curve counts the cells of the Vietoris-Rips complex, and ${others.mkString(", ")} cannot go with it " +
          "(it takes --max-simplex-dimension, --max-filtration-value and --threads)"
      )
    val chosen = options.grouped(2).filter(pair => eulerCurveOptions(pair(0))).flatten.toArray ++
      conf.maxSimplexDimension.toOption.toSeq.flatMap(d => Seq("maxSimplexDimension", d.toString))
    val curve = resolved match
      case ResolvedInput.Points(points)       => TDA4j.eulerCurveFromPoints(points, chosen)
      case ResolvedInput.Distances(distances) => TDA4j.eulerCurveFromDistanceMatrix(distances, chosen)
      case _                                  =>
        throw new IllegalArgumentException(
          s"--euler-curve needs a point cloud or a distance matrix; --input-format=$format reads something else"
        )
    val lines = "scale,value" +: curve.scales().zip(curve.values()).toSeq.map((scale, value) => s"$scale,$value")
    conf.output.toOption match
      case Some(path) =>
        val writer = FileAccess.printWriter(path)
        try lines.foreach(writer.println)
        finally writer.close()
      case None => lines.foreach(out.println)

  /** `--euler-curve`, `--bootstrap-band`, `--tomato` and `--inclusion-of` are modes of their own: at most one, and the
    * flags of a mode only with it.
    */
  private[cli] def checkModes(conf: TDA4jConf): Unit =
    val chosen = Seq(
      "--euler-curve" -> conf.eulerCurve(),
      "--persistent-laplacian" -> conf.persistentLaplacian(),
      "--morse-reduction" -> conf.morseReduction(),
      "--bootstrap-band" -> conf.bootstrapBand(),
      "--tomato" -> conf.tomato(),
      "--inclusion-of" -> conf.inclusionOf.isSupplied
    ).collect { case (flag, true) => flag }
    if chosen.size > 1 then
      throw new IllegalArgumentException(s"${chosen.mkString(" and ")} are modes of their own: choose one")
    val strays = Seq(
      (
        "--morse-reduction",
        !conf.morseReduction(),
        Seq("--morse-strategy" -> conf.morseStrategy.isSupplied)
      ),
      (
        "--persistent-laplacian",
        !conf.persistentLaplacian(),
        Seq(
          "--laplacian-dimension" -> conf.laplacianDimension.isSupplied,
          "--laplacian-from" -> conf.laplacianFrom.isSupplied,
          "--laplacian-to" -> conf.laplacianTo.isSupplied
        )
      ),
      (
        "--bootstrap-band",
        !conf.bootstrapBand(),
        Seq(
          "--band-dimension" -> conf.bandDimension.isSupplied,
          "--band-rounds" -> conf.bandRounds.isSupplied,
          "--band-alpha" -> conf.bandAlpha.isSupplied,
          "--band-seed" -> conf.bandSeed.isSupplied,
          "--band-subsample-size" -> conf.bandSubsampleSize.isSupplied
        )
      ),
      (
        "--tomato",
        !conf.tomato(),
        Seq(
          "--tomato-k" -> conf.tomatoK.isSupplied,
          "--tomato-tau" -> conf.tomatoTau.isSupplied
        )
      ),
      (
        "--takens-dimension",
        !conf.takensDimension.isSupplied,
        Seq(
          "--takens-delay" -> conf.takensDelay.isSupplied,
          "--takens-max-lag" -> conf.takensMaxLag.isSupplied
        )
      )
    )
    for (mode, absent, flags) <- strays if absent do
      val supplied = flags.collect { case (flag, true) => flag }
      if supplied.nonEmpty then throw new IllegalArgumentException(s"${supplied.mkString(", ")} only go with $mode")

  /** `--takens-dimension`: the input is a time series, one sample per line, and the computation is on its
    * sliding-window embedding. A series of one column may have its delay chosen by autocorrelation
    * (`--takens-max-lag`), the choice going to `err`.
    */
  private[cli] def applyTakens(
    conf: TDA4jConf,
    format: String,
    resolved: ResolvedInput,
    err: java.io.PrintStream
  ): ResolvedInput =
    conf.takensDimension.toOption match
      case None            => resolved
      case Some(dimension) =>
        resolved match
          case ResolvedInput.Points(rows) =>
            if conf.takensDelay.isSupplied && conf.takensMaxLag.isSupplied then
              throw new IllegalArgumentException(
                "--takens-delay and --takens-max-lag are alternatives: the first gives the delay, the second chooses it"
              )
            val oneColumn = rows.nonEmpty && rows.forall(_.length == 1)
            if conf.takensMaxLag.isSupplied && !oneColumn then
              throw new IllegalArgumentException(
                "--takens-max-lag chooses the delay of a series of one column; for a series of vectors give --takens-delay"
              )
            val delay = conf.takensMaxLag.toOption match
              case Some(lag) =>
                val chosen = TDA4j.delayByAutocorrelation(rows.map(_(0)), lag)
                err.println(
                  s"tda4j: delay $chosen chosen by autocorrelation (the first lag up to $lag where it is zero or negative)"
                )
                chosen
              case None => conf.takensDelay.getOrElse(1)
            ResolvedInput.Points(
              if oneColumn then TDA4j.takensEmbedding(rows.map(_(0)), dimension, delay)
              else TDA4j.takensEmbeddingOfVectors(rows, dimension, delay)
            )
          case _ =>
            throw new IllegalArgumentException(
              s"--takens-dimension reads a time series, one sample per line, as a point-cloud input; " +
                s"--input-format=$format reads something else"
            )

  /** Refuses the flags a mode does not use, naming them: a flag that would change a computation this mode does not do
    * must not be silently ignored.
    */
  private def refuseOtherFlags(
    mode: String,
    doing: String,
    takes: String,
    conf: TDA4jConf,
    options: Array[String],
    allowed: Set[String]
  ): Unit =
    val others = Seq(
      "--plot" -> conf.plot.isSupplied,
      "--distance-to" -> conf.distanceTo.isSupplied,
      "--select-landmarks" -> conf.selectLandmarks(),
      "--landmarks-file" -> conf.landmarksFile.isSupplied,
      "--representatives" -> conf.representatives(),
      "--bar-cells" -> conf.barCells(),
      "--relative-to" -> conf.relativeTo.isSupplied,
      "--output-format" -> conf.outputFormat.isSupplied,
      "--save-result" -> conf.saveResult.isSupplied
    ).collect { case (flag, true) => flag } ++
      options.grouped(2).map(_(0)).filterNot(allowed).map(flagName)
    if others.nonEmpty then
      throw new IllegalArgumentException(
        s"$mode $doing, and ${others.mkString(", ")} cannot go with it (it takes $takes)"
      )

  private def writeLines(conf: TDA4jConf, lines: Seq[String], out: java.io.PrintStream): Unit =
    conf.output.toOption match
      case Some(path) =>
        val writer = FileAccess.printWriter(path)
        try lines.foreach(writer.println)
        finally writer.close()
      case None => lines.foreach(out.println)

  private val bootstrapBandOptions = Set("maxFiltrationValue", "threads", "prime")

  /** `--bootstrap-band`: the width and threshold of the resampling band of one degree of a point cloud, as `key=value`
    * lines.
    */
  private[cli] def writeBootstrapBand(
    conf: TDA4jConf,
    options: Array[String],
    format: String,
    resolved: ResolvedInput,
    out: java.io.PrintStream
  ): Unit =
    refuseOtherFlags(
      "--bootstrap-band",
      "resamples a point cloud and compares the diagrams of the Vietoris-Rips complexes",
      "the --band-* flags, --prime, --max-filtration-value and --threads",
      conf,
      options,
      bootstrapBandOptions
    )
    val chosen = options.grouped(2).filter(pair => bootstrapBandOptions(pair(0))).flatten.toArray ++
      Seq(
        conf.bandDimension.toOption.map("dimension" -> _.toString),
        conf.bandRounds.toOption.map("rounds" -> _.toString),
        conf.bandAlpha.toOption.map("alpha" -> _.toString),
        conf.bandSeed.toOption.map("seed" -> _.toString),
        conf.bandSubsampleSize.toOption.map("subsampleSize" -> _.toString)
      ).flatten.flatMap((key, value) => Seq(key, value))
    val band = resolved match
      case ResolvedInput.Points(points) => TDA4j.bootstrapBand(points, chosen)
      case _                            =>
        throw new IllegalArgumentException(
          s"--bootstrap-band needs a point cloud; --input-format=$format reads something else"
        )
    writeLines(
      conf,
      Seq(
        s"dimension=${conf.bandDimension.getOrElse(1)}",
        s"rounds=${band.distances().length}",
        s"alpha=${band.alpha()}",
        s"width=${band.width()}",
        s"threshold=${band.threshold()}"
      ),
      out
    )

  /** `--persistent-laplacian`: the persistent Betti number, spectral gap and eigenvalues of the Laplacian of the
    * Vietoris-Rips complex of a point cloud or distance matrix.
    */
  private[cli] def writePersistentLaplacian(
    conf: TDA4jConf,
    options: Array[String],
    format: String,
    resolved: ResolvedInput,
    out: java.io.PrintStream
  ): Unit =
    refuseOtherFlags(
      "--persistent-laplacian",
      "computes the Laplacian of a Vietoris-Rips complex",
      "the --laplacian-* flags",
      conf,
      options,
      Set.empty
    )
    def required[T](flag: String, value: ScallopOption[T]): T =
      value.toOption.getOrElse(throw new IllegalArgumentException(s"--persistent-laplacian needs $flag"))
    val dimension = required("--laplacian-dimension", conf.laplacianDimension)
    val (from, to) = (required("--laplacian-from", conf.laplacianFrom), required("--laplacian-to", conf.laplacianTo))
    val laplacian = resolved match
      case ResolvedInput.Points(points)       => TDA4j.persistentLaplacianFromPoints(points, dimension, from, to)
      case ResolvedInput.Distances(distances) =>
        TDA4j.persistentLaplacianFromDistanceMatrix(distances, dimension, from, to)
      case _ =>
        throw new IllegalArgumentException(
          s"--persistent-laplacian needs a point cloud or a distance matrix; --input-format=$format reads something else"
        )
    writeLines(
      conf,
      Seq(
        s"dimension=$dimension",
        s"from=$from",
        s"to=$to",
        s"betti-number=${laplacian.bettiNumber()}",
        s"spectral-gap=${if laplacian.hasSpectralGap() then laplacian.spectralGap().toString else "none"}",
        s"eigenvalues=${laplacian.eigenvalues().mkString(",")}"
      ),
      out
    )

  private val morseReductionOptions = Set("maxDimension", "maxFiltrationValue", "prime", "threads")

  /** `--morse-reduction`: the cells and critical cells of the Vietoris-Rips complex of a point cloud or distance matrix
    * by dimension, and the number of pairs, as `key=value` lines.
    */
  private[cli] def writeMorseReduction(
    conf: TDA4jConf,
    options: Array[String],
    format: String,
    resolved: ResolvedInput,
    out: java.io.PrintStream
  ): Unit =
    refuseOtherFlags(
      "--morse-reduction",
      "reduces a Vietoris-Rips complex by discrete Morse theory",
      "--morse-strategy, --max-dimension, --max-filtration-value, --prime and --threads",
      conf,
      options,
      morseReductionOptions
    )
    val chosen = options.grouped(2).filter(pair => morseReductionOptions(pair(0))).flatten.toArray ++
      conf.morseStrategy.toOption.toSeq.flatMap(strategy => Seq("strategy", strategy))
    val reduction = resolved match
      case ResolvedInput.Points(points)       => TDA4j.morseReductionFromPoints(points, chosen)
      case ResolvedInput.Distances(distances) => TDA4j.morseReductionFromDistanceMatrix(distances, chosen)
      case _                                  =>
        throw new IllegalArgumentException(
          s"--morse-reduction needs a point cloud or a distance matrix; --input-format=$format reads something else"
        )
    writeLines(
      conf,
      Seq(
        s"cells=${reduction.cellCounts().mkString(",")}",
        s"critical-cells=${reduction.criticalCounts().mkString(",")}",
        s"pairs=${reduction.pairCount()}"
      ),
      out
    )

  /** `--tomato`: the cluster of every point, or, without `--tomato-tau`, the valleys between the density peaks. */
  private[cli] def writeTomato(
    conf: TDA4jConf,
    options: Array[String],
    format: String,
    resolved: ResolvedInput,
    out: java.io.PrintStream
  ): Unit =
    refuseOtherFlags(
      "--tomato",
      "clusters a point cloud by the k nearest neighbours",
      "--tomato-k and --tomato-tau",
      conf,
      options,
      Set.empty
    )
    if !conf.tomatoK.isSupplied then
      throw new IllegalArgumentException("--tomato needs --tomato-k, the number of nearest neighbours")
    val points = resolved match
      case ResolvedInput.Points(points) => points
      case _                            =>
        throw new IllegalArgumentException(s"--tomato needs a point cloud; --input-format=$format reads something else")
    val lines = conf.tomatoTau.toOption match
      case Some(tau) => TDA4j.tomatoClusters(points, conf.tomatoK(), tau).toSeq.map(_.toString)
      case None      =>
        "birth,death" +: TDA4j.tomatoDiagram(points, conf.tomatoK()).toSeq.map(bar => s"${bar(0)},${bar(1)}")
    writeLines(conf, lines, out)

  private val inclusionOptions = Set("engine", "maxDimension", "maxFiltrationValue", "prime", "threads")

  /** `--inclusion-of`: the image, kernel and cokernel, per degree, of the map induced by the inclusion of a subsample's
    * Vietoris-Rips complex into the input's, one bar a line: `image 1: [birth,death)`.
    */
  private[cli] def writeInclusion(
    conf: TDA4jConf,
    options: Array[String],
    format: String,
    resolved: ResolvedInput,
    out: java.io.PrintStream
  ): Unit =
    refuseOtherFlags(
      "--inclusion-of",
      "writes the map on persistent homology of the inclusion of a subsample's Vietoris-Rips complex",
      "--max-dimension, --max-filtration-value, --prime, --engine as ripser or cohomology, and --threads",
      conf,
      options,
      inclusionOptions
    )
    val subsample = parseVertexList("--inclusion-of", conf.inclusionOf())
    val map = resolved match
      case ResolvedInput.Points(points)       => TDA4j.computeInclusionFromPoints(points, subsample, options)
      case ResolvedInput.Distances(distances) =>
        TDA4j.computeInclusionFromDistanceMatrix(distances, subsample, options)
      case _ =>
        throw new IllegalArgumentException(
          s"--inclusion-of needs a point cloud or a distance matrix; --input-format=$format reads something else"
        )
    writeLines(conf, inclusionLines(map), out)

  private[cli] def inclusionLines(map: PersistenceMapResult): Seq[String] =
    for
      k <- 0 to map.maxDimension()
      (kind, bars) <- Seq("image" -> map.image(k), "kernel" -> map.kernel(k), "cokernel" -> map.cokernel(k))
      bar <- bars
    yield s"$kind $k: [${bar(0)},${if bar(1).isPosInfinity then "∞" else bar(1).toString})"

  /** `flag`'s comma-separated 0-based point numbers, `0,1,2`. */
  private[cli] def parseVertexList(flag: String, text: String): Array[Int] =
    text
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(item =>
        item.toIntOption.getOrElse(
          throw new IllegalArgumentException(s"$flag: expected comma-separated point numbers like 0,1,2, got '$text'")
        )
      )

  /** Reads a `--landmarks-file` written by `writeLandmarkSelection` above (or hand-edited in the same shape): one
    * 0-based landmark index per line, blank lines and `#`-prefixed comment lines ignored. Parse errors report the
    * OFFENDING LINE NUMBER (1-based, matching what a text editor shows), not just the bad token, since a hand-edited
    * landmarks file is exactly the case where "which line" matters for fixing it.
    */
  private[cli] def readLandmarksFile(path: String): Array[Int] =
    val source = FileAccess.source(path)
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

  private def asBar(dim: Int, birth: Double, death: Double): PersistenceBar[Double, Nothing] =
    if death.isPosInfinity then PersistenceBar[Double](dim, birth) else PersistenceBar[Double](dim, birth, death)

  /** The bars the persistence threshold lets through -- what every output format writes. */
  private[cli] def toBars(result: PersistenceResult): IndexedSeq[PersistenceBar[Double, Nothing]] =
    (0 until result.size()).map(i => asBar(result.dimension(i), result.birth(i), result.death(i)))

  /** EVERY bar, threshold or not: `--distance-to` compares against an external diagram (typically unfiltered Ripser/
    * GUDHI output), and a distance that depended on this run's own scale would not be comparable.
    */
  private[cli] def toBarsUnfiltered(result: PersistenceResult): IndexedSeq[PersistenceBar[Double, Nothing]] =
    result.toArrayUnfiltered().toIndexedSeq.map(row => asBar(row(0).toInt, row(1), row(2)))

  /** One stderr line when the threshold hid anything, so a user is never left wondering where bars went. A saved result
    * keeps the threshold it was saved with.
    */
  private[cli] def noteHiddenBars(
    result: PersistenceResult,
    loaded: Boolean = false,
    err: java.io.PrintStream = System.err
  ): Unit =
    if result.hiddenCount() > 0 then
      err.println(
        s"tda4j: ${result.hiddenCount()} bar(s) with persistence <= ${result.persistenceThreshold()} not reported " +
          s"(${result.size()} reported); " +
          (if loaded then "the saved result keeps its threshold: compute again with --min-persistence 0 to report them"
           else "pass --min-persistence 0 to report them")
      )

  // -----------------------------------------------------------------------------------------------------------
  // output
  // -----------------------------------------------------------------------------------------------------------

  private def requireOutput(conf: TDA4jConf, format: String): String =
    conf.output.toOption.getOrElse(
      throw new IllegalArgumentException(s"--output is required for --output-format=$format")
    )

  /** Perseus's interval format stores integer filtration steps, so bars with non-integral values are refused rather
    * than rounded.
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

  private def formatCells(result: PersistenceResult, i: Int): String =
    val born = result.birthCell(i)
    if born.isEmpty then "(no cells recorded)"
    else
      val died = result.deathCell(i)
      s"birth ${born.mkString("[", ",", "]")} death ${if died.isEmpty then "none" else died.mkString("[", ",", "]")}"

  /** Reads the saved diagram of `--distance-to` in format `csv`, `gudhi` or `dipha`. */
  private[cli] def readComparisonDiagram(format: String, path: String): Seq[PersistenceBar[Double, Nothing]] =
    format match
      case "csv"   => CSV.readPersistenceDiagram(path)
      case "gudhi" => Gudhi.readPersistenceDiagram(path)
      case "dipha" => Dipha.readPersistenceDiagram(path)
      case other   =>
        throw new IllegalArgumentException(s"unrecognized --distance-format '$other'; expected csv, gudhi, or dipha")

  private[cli] def writeDistance(conf: TDA4jConf, result: PersistenceResult, out: java.io.PrintStream): Unit =
    val bars = toBarsUnfiltered(result)
    val comparison = readComparisonDiagram(conf.distanceFormat(), conf.distanceTo())
    val order = conf.distanceOrder.toOption.getOrElse(1.0)
    val groundNorm = conf.distanceGroundNorm.toOption match
      case Some(p) => BarcodeDistance.GroundNorm.LP(p)
      case None    => BarcodeDistance.GroundNorm.LInfinity
    val bottleneck = BarcodeDistance.bottleneckDistanceByDimension(bars, comparison, groundNorm)
    val wasserstein = BarcodeDistance.wassersteinDistanceByDimension(bars, comparison, order, groundNorm)
    val dims = (bottleneck.keySet ++ wasserstein.keySet).toSeq.sorted
    val lines = dims.map(d => s"dim $d: bottleneck=${bottleneck(d)} wasserstein=${wasserstein(d)}")
    conf.output.toOption match
      case Some(path) =>
        val writer = FileAccess.printWriter(path)
        try lines.foreach(writer.println)
        finally writer.close()
      case None => lines.foreach(out.println)

  private val barcodeOps = Seq("product-max", "product-sum", "hom", "hom-dimension", "dual")

  /** `--barcode-op`: the bars of the barcode algebra of this run's whole diagram (and that of `--barcode-with`), one
    * `dimension,birth,death` line each; `hom-dimension` writes the number of maps.
    */
  private[cli] def writeBarcodeOp(conf: TDA4jConf, result: PersistenceResult, out: java.io.PrintStream): Unit =
    val first = toBarsUnfiltered(result).toList
    def second = CSV.readPersistenceDiagram(conf.barcodeWith()).toList
    val lines = conf.barcodeOp() match
      case "product-max" => Barcode.kunneth(first, second, ProductComplex.Combine.Max).map(CSV.formatBar)
      case "product-sum" => Barcode.kunneth(first, second, ProductComplex.Combine.Sum).map(CSV.formatBar)
      case "hom"         => Barcode.hom(first, second).map(CSV.formatBar)
      case "dual"        => Barcode.dual(first).map(CSV.formatBar)
      case _             => List(Barcode.homDimension(first, second).toString)
    writeLines(conf, lines, out)

  private[cli] def writeOutput(conf: TDA4jConf, result: PersistenceResult, out: java.io.PrintStream): Unit =
    val bars = toBars(result)
    conf.outputFormat() match
      case "text" =>
        val lines = bars.zipWithIndex.map { case (bar, i) =>
          val rep = if conf.representatives() then s"   rep: ${formatRepresentative(result, i)}" else ""
          val cells = if conf.barCells() then s"   cells: ${formatCells(result, i)}" else ""
          s"$bar$rep$cells"
        }
        conf.output.toOption match
          case Some(path) =>
            val writer = FileAccess.printWriter(path)
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

  // -----------------------------------------------------------------------------------------------------------
  // plots: each --plot kind is one PersistenceResult method, so MATLAB and the CLI draw the same figure
  // -----------------------------------------------------------------------------------------------------------

  private val plotKinds = Seq(
    "barcode",
    "diagram",
    "representative",
    "input",
    "complex",
    "landscape",
    "persistence-image",
    "input-3d",
    "complex-3d",
    "representative-3d"
  )

  /** The kinds of a `--plot` value: one, or a comma list that is drawn as a board. */
  private def plotKindsOf(spec: String): Seq[String] = spec.split(",", -1).toSeq.map(_.trim)

  private val layoutText = """(\d{1,3})\s*[xX]\s*(\d{1,3})""".r

  /** The rows and columns of a `--layout`: `2x3`. */
  private def parseLayout(text: String): (Int, Int) =
    text.trim match
      case layoutText(rows, cols) => (rows.toInt, cols.toInt)
      case _ => throw new IllegalArgumentException(s"--layout is rows x columns, such as 2x2, not '$text'")

  /** The plot flags, checked before anything is read or computed: a typo should not cost a long computation, and a flag
    * is never silently ignored.
    */
  private[cli] def checkPlotFlags(conf: TDA4jConf): Unit =
    val usedBy = Map(
      "--bar" -> (conf.bar.isSupplied, Set("representative", "representative-3d")),
      "--dimension" -> (
        conf.dimension.isSupplied,
        Set(
          "representative",
          "representative-3d",
          "landscape",
          "persistence-image"
        )
      ),
      "--at" -> (conf.at.isSupplied, Set("representative", "complex", "complex-3d"))
    )
    val looks = Seq(
      "--title" -> conf.title.isSupplied,
      "--x-label" -> conf.xLabel.isSupplied,
      "--y-label" -> conf.yLabel.isSupplied,
      "--caption" -> conf.caption.isSupplied,
      "--tag" -> conf.tag.isSupplied,
      "--description" -> conf.description.isSupplied,
      "--look" -> conf.look.isSupplied,
      "--width" -> conf.width.isSupplied,
      "--height" -> conf.height.isSupplied,
      "--palette" -> conf.palette.isSupplied,
      "--style" -> conf.style.isSupplied,
      "--theme" -> conf.theme.isSupplied,
      "--text-scale" -> conf.textScale.isSupplied,
      "--set" -> conf.set.isSupplied,
      "--x-scale" -> conf.xScale.isSupplied,
      "--y-scale" -> conf.yScale.isSupplied
    ).collect { case (flag, true) => flag }
    conf.plot.toOption match
      case None =>
        usedBy.foreach { case (flag, (supplied, kinds)) =>
          if supplied then
            throw new IllegalArgumentException(s"$flag is for --plot ${kinds.toSeq.sorted.mkString(" or ")}")
        }
        if looks.nonEmpty then throw new IllegalArgumentException(s"${looks.head} is for --plot")
        Seq("--layout" -> conf.layout.isSupplied, "--tagged" -> conf.tagged())
          .collect { case (flag, true) => flag }
          .foreach(flag => throw new IllegalArgumentException(s"$flag is for --plot"))
      case Some(spec) =>
        val listed = plotKindsOf(spec)
        listed.foreach { kind =>
          if !plotKinds.contains(kind) then
            throw new IllegalArgumentException(
              s"unrecognized --plot '$kind'; expected one of ${plotKinds.mkString(", ")}"
            )
        }
        usedBy.foreach { case (flag, (supplied, kinds)) =>
          if supplied && !listed.exists(kinds.contains) then
            throw new IllegalArgumentException(
              s"$flag is not used by --plot $spec, only by --plot ${kinds.toSeq.sorted.mkString(" or ")}"
            )
        }
        if listed.size > 1 then
          listed.filter(_.endsWith("-3d")).headOption.foreach { kind =>
            throw new IllegalArgumentException(s"a 3-D plot cannot go on a board: $kind (draw it with its own --plot)")
          }
          if conf.tag.isSupplied then
            throw new IllegalArgumentException("--tag labels one plot; --tagged letters the panels of a board")
          conf.layout.toOption.foreach { text =>
            val (rows, cols) = parseLayout(text)
            // the grid itself refuses a size it cannot draw, now and not after the computation
            Plot.layout(rows, cols, conf.width.getOrElse(960), conf.height.getOrElse(600))
            if listed.size > rows * cols then
              throw new IllegalArgumentException(
                s"${listed.size} plots do not fit a ${rows}x$cols layout; give --layout with at least ${listed.size} cells"
              )
          }
        else
          Seq("--layout" -> conf.layout.isSupplied, "--tagged" -> conf.tagged())
            .collect { case (flag, true) => flag }
            .foreach(flag =>
              throw new IllegalArgumentException(s"$flag is for a list of plots, as --plot barcode,diagram")
            )
        if conf.bar.isSupplied && conf.dimension.isSupplied then
          throw new IllegalArgumentException(
            "give --bar (one bar) or --dimension (its most persistent bar), not both"
          )
        listed.find(kind => kind == "complex" || kind == "complex-3d").foreach { kind =>
          if !conf.at.isSupplied then
            throw new IllegalArgumentException(s"--plot $kind needs --at, the scale of the complex to draw")
        }
        // a typo or a number out of range should not cost a long computation: build the changes now
        appearance(conf)
        conf.theme.foreach(Theme.named)
        if listed.size == 1 && listed.head.endsWith("-3d") then
          val kind = listed.head
          Seq("--x-label", "--y-label", "--x-scale", "--y-scale", "--caption", "--tag", "--description").foreach { flag =>
            if looks.contains(flag) then
              throw new IllegalArgumentException(s"$flag is for 2-D plots, and --plot $kind draws a 3-D scene")
          }
          // a setting of an element a scene has not (a tick label) is refused now, not after the computation
          conf.set.toOption.foreach(specs => Settings.parseAll(specs).foreach(change => Scene() + change))
        else
          // and the camera of a 3-D scene is refused for a 2-D plot
          conf.set.toOption.foreach(specs => Settings.parseAll(specs).foreach(change => Figure() + change))
        val others = Seq(
          "--distance-to" -> conf.distanceTo.isSupplied,
          "--select-landmarks" -> conf.selectLandmarks(),
          "--representatives" -> conf.representatives(),
          "--bar-cells" -> conf.barCells(),
          "--output-format" -> conf.outputFormat.isSupplied
        ).collect { case (flag, true) => flag }
        if others.nonEmpty then
          throw new IllegalArgumentException(
            s"--plot draws the result instead of writing it: ${others.mkString(", ")} cannot go with it"
          )
        conf.output.toOption.foreach { path =>
          val lower = path.toLowerCase
          if !(lower.endsWith(".svg") || lower.endsWith(".html") || lower.endsWith(".htm")) then
            throw new IllegalArgumentException(
              s"--plot writes an .svg file or an .html page; --output '$path' is neither"
            )
        }

  /** The changes the look flags ask for, in one tweak; `None` when none was given. */
  private def appearance(conf: TDA4jConf): Option[Tweak] =
    val look = conf.look.toOption.map(Look.named)
    // --text-scale changes the style it is given, so --look's style is the one it scales unless --style names another
    val lookStyle = look.flatMap(_.tweaks.collect { case s: Style => s }.lastOption)
    val asked: Seq[Option[Tweak]] = Seq(
      // the look first: the more specific flags after it win
      look.flatMap(_.tweaks.reduceOption(_ + _)),
      Option.when(
        conf.title.isSupplied || conf.xLabel.isSupplied || conf.yLabel.isSupplied || conf.caption.isSupplied ||
          conf.tag.isSupplied || conf.description.isSupplied
      )(
        Labels(
          title = conf.title.toOption,
          x = conf.xLabel.toOption,
          y = conf.yLabel.toOption,
          caption = conf.caption.toOption,
          tag = conf.tag.toOption,
          description = conf.description.toOption
        )
      ),
      Option.when(conf.width.isSupplied || conf.height.isSupplied)(
        Size(width = conf.width.toOption, height = conf.height.toOption)
      ),
      conf.palette.toOption.map(Palette.named),
      // a Style replaces the whole style, so --style and --text-scale are one change: the preset, then its text scale
      Option.when(conf.style.isSupplied || conf.textScale.isSupplied) {
        val preset = conf.style.toOption.map(Style.named).orElse(lookStyle).getOrElse(Style.default)
        conf.textScale.toOption.fold(preset)(factor => preset.copy(textScale = factor))
      },
      conf.xScale.toOption.map(name => Scale.x(Transform.named(name))),
      conf.yScale.toOption.map(name => Scale.y(Transform.named(name))),
      conf.set.toOption.flatMap(Settings.parseAll)
    )
    asked.flatten.reduceOption(_ + _)

  /** Draws `--plot` into `--output`, or into a page in the temporary directory opened in the browser. */
  private[cli] def writePlot(
    conf: TDA4jConf,
    result: PersistenceResult,
    loaded: Boolean,
    err: java.io.PrintStream = System.err
  ): Unit =
    def chosenBar(): Int =
      val i = conf.bar.toOption.getOrElse(result.mostPersistentBar(conf.dimension.toOption.getOrElse(1)))
      if i >= 0 && i < result.size() then err.println(s"tda4j: drawing bar $i: ${toBars(result)(i)}")
      i
    def dimension = conf.dimension.toOption.getOrElse(1)
    def figureOf(kind: String): Plot = kind match
      case "barcode"        => result.plotBarcode()
      case "diagram"        => result.plotDiagram()
      case "representative" =>
        val i = chosenBar()
        conf.at.toOption.fold(result.plotRepresentative(i))(result.plotRepresentative(i, _))
      case "input"             => result.plotInput()
      case "complex"           => result.plotComplex(conf.at())
      case "landscape"         => result.plotLandscape(dimension)
      case "persistence-image" => result.plotPersistenceImage(dimension)
      case "input-3d"          => result.plotInput3D()
      case "complex-3d"        => result.plotComplex3D(conf.at())
      case "representative-3d" => result.plotRepresentative3D(chosenBar())
      case other               => throw new IllegalStateException(s"unreachable: --plot $other was checked")
    val listed = plotKindsOf(conf.plot())
    val kind = listed.mkString(",")
    // one plot, or a board: a row, or the panels in row-major order on the grid --layout asks for
    val figure: Plot = listed.map(figureOf) match
      case Seq(single) => single
      case several     =>
        val panels = several.map {
          case f: Figure => f
          case other     => throw new IllegalStateException(s"unreachable: --plot $kind drew a $other on a board")
        }
        conf.layout.toOption.fold[Board](Plot.side(panels*)) { text =>
          val (rows, cols) = parseLayout(text)
          panels.zipWithIndex.foldLeft(Plot.layout(rows, cols, conf.width.getOrElse(960), conf.height.getOrElse(600))) {
            case (board, (panel, i)) => board.place(panel, i / cols, i % cols)
          }
        }
    if listed.exists(k => k == "barcode" || k == "diagram") then noteHiddenBars(result, loaded, err)
    val tweaked: Plot = appearance(conf).fold(figure) { tweak =>
      figure match
        case f: Figure => f + tweak
        case s: Scene  => s + tweak
        case b: Board  => b + tweak
        case other     => throw new IllegalStateException(s"unreachable: --plot $kind drew a $other")
    }
    val drawn: Plot = tweaked match
      case b: Board if conf.tagged() => b.tagged()
      case other                     => other
    val theme = conf.theme.toOption.map(Theme.named)
    conf.output.toOption match
      case Some(path) => drawn.save(path, theme)
      case None       =>
        val page = FileAccess.tempFile(s"tda4j-${listed.mkString("-")}-", ".html")
        FileAccess.writeText(page, drawn.html(theme.getOrElse(Theme.Auto)))
        val opened = Viewer.browse(FileAccess.fileUri(page))
        err.println(
          s"tda4j: wrote $page" + (if opened then " (opened in your browser)" else "; open it in a browser")
        )
