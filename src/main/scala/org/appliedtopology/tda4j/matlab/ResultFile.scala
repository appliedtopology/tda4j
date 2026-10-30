package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.*

import scala.collection.mutable

/** The saved form of a [[PersistenceResult]] (`result.save(path)`, `TDA4j.loadResult(path)`): one JSON object.
  *
  * {{{
  * {
  * "format": "tda4j-persistence-result", "formatVersion": 1, "tda4jVersion": "0.5.0",
  * "method": "computeFromPoints", "options": ["maxDimension", "1"], "complex": "vr",
  * "representativeType": "cycles", "field": "Z", "prime": 17, "persistenceThreshold": 0.0123,
  * "dimensions": [0, 0, 1], "births": [0.0, 0.0, 0.5], "deaths": ["Infinity", 0.3, 1.2],
  * "reported": [0, 2],
  * "representatives": [{"cells": [[0]], "coefficients": [1.0]}, {"cells": [[0, 1], [1, 2], [0, 2]], ...}],
  * "landmarks": [3, 17],
  * "input": {"kind": "points", "points": [[1.0, 0.0], ...]}
  * }
  * }}}
  *
  *   - `dimensions`, `births`, `deaths`: every bar computed, the threshold's hidden ones too.
  *   - `birthCells`, `deathCells` (optional): the cell that opens and the cell that closes each bar, parallel to
  *     `dimensions`, a cell as in `cycleVertices`; `[]` where the bar has none (an essential bar's death cell; both
  *     where the engine recorded no cells). Left out when no bar has a cell.
  *   - `reported`: the bars the threshold let through, as indices into those arrays (default: all of them);
  *     `representatives` is parallel to it (`cells` as in `cycleVertices`, `coefficients` as in `cycleCoefficients`;
  *     `null` for a bar without one).
  *   - `input`: `points` (one row per point), `image` (`shape`, and `values` in row-major order as given, before any
  *     superlevel negation), or a `kind` with no coordinates (`distance-matrix`, `relation`).
  *   - Non-finite numbers are the strings `"Infinity"`, `"-Infinity"`, `"NaN"`, so the file is standard JSON.
  *
  * Only `format`, `formatVersion` and the three bar arrays are required: a barcode from anywhere, written in this
  * shape, loads and plots. Unknown keys are skipped.
  */
private[matlab] object ResultFile:
  val Format = "tda4j-persistence-result"
  val Version = 1

  /** The version of tda4j writing the file, from the source file the build generates. */
  private lazy val version: Option[String] = Some(LibraryVersion.value)

  /** Writes a result; `representative(k)` is reported bar `k`'s representative. */
  def write(
    path: String,
    dims: Array[Int],
    births: Array[Double],
    deaths: Array[Double],
    reported: Array[Int],
    threshold: Double,
    representative: Int => (Array[Array[Int]], Array[Double]),
    cells: Int => (Array[Int], Array[Int]),
    source: Option[ResultSource]
  ): String =
    val absolute = FileAccess.absolute(path)
    FileAccess.createParentDirectories(absolute)
    val out = FileAccess.writer(absolute)
    try
      val w = JsonWriter(out)
      def key(k: String): Unit =
        w.raw(",\n")
        w.string(k)
        w.raw(": ")
      w.raw("{\n")
      w.string("format"); w.raw(": "); w.string(Format)
      key("formatVersion"); w.int(Version)
      version.foreach { v => key("tda4jVersion"); w.string(v) }
      source.foreach { s =>
        key("method"); w.string(s.method)
        key("options"); w.strings(s.options)
        key("complex"); w.string(s.complex)
        key("representativeType"); w.string(if s.cocycles then "cocycles" else "cycles")
        if s.field.nonEmpty then
          key("field"); w.string(s.field)
          if s.field == "Z" then
            key("prime"); w.int(s.prime)
        if s.landmarks.nonEmpty then
          key("landmarks"); w.ints(s.landmarks)
      }
      key("persistenceThreshold"); w.double(threshold)
      key("dimensions"); w.ints(dims)
      key("births"); w.doubles(births)
      key("deaths"); w.doubles(deaths)
      val barCells = dims.indices.map(cells)
      if barCells.exists(_._1.nonEmpty) then
        key("birthCells")
        w.sequence(barCells.length)(k => w.ints(barCells(k)._1))
        key("deathCells")
        w.sequence(barCells.length)(k => w.ints(barCells(k)._2))
      key("reported"); w.ints(reported)
      key("representatives")
      w.raw("[")
      for k <- reported.indices do
        w.raw(if k == 0 then "\n" else ",\n")
        val (cells, coefficients) = representative(k)
        w.raw("{\"cells\": ")
        w.sequence(cells.length)(j => w.ints(cells(j)))
        w.raw(", \"coefficients\": ")
        w.doubles(coefficients)
        w.raw("}")
      w.raw("\n]")
      source.foreach { s =>
        key("input")
        s.input match
          case ResultInput.Points(points) =>
            w.raw("{\"kind\": \"points\", \"points\": [")
            for i <- points.indices do
              w.raw(if i == 0 then "\n" else ",\n")
              w.doubles(points(i))
            w.raw("\n]}")
          case ResultInput.Image(shape, values) =>
            w.raw("{\"kind\": \"image\", \"shape\": ")
            w.ints(shape)
            w.raw(", \"values\": ")
            w.doubles(values)
            w.raw("}")
          case ResultInput.Abstract(kind) =>
            w.raw("{\"kind\": ")
            w.string(kind)
            w.raw("}")
      }
      w.raw("\n}\n")
    finally out.close()
    absolute

  /** Reads a saved result, checking that its parts fit together. */
  def read(path: String): PersistenceResult =
    if !FileAccess.isFile(path) then throw FileAccess.noSuchFile(path)
    parse(FileAccess.readText(path), path)

  /** A saved result from its text; `path` names it in error messages. */
  def parse(text: String, path: String): PersistenceResult =
    val r = JsonReader(text, path)
    var format: Option[String] = None
    var formatVersion: Option[Int] = None
    var method = ""
    var options = Array.empty[String]
    var complex = "unknown"
    var cocycles = false
    var field = ""
    var prime = 0
    var landmarks = Array.empty[Int]
    var threshold = 0.0
    var dims: Option[Array[Int]] = None
    var births: Option[Array[Double]] = None
    var deaths: Option[Array[Double]] = None
    var birthCells: Option[Array[Array[Int]]] = None
    var deathCells: Option[Array[Array[Int]]] = None
    var reported: Option[Array[Int]] = None
    var representatives: Option[Array[Option[(Array[Array[Int]], Array[Double])]]] = None
    var input: ResultInput = ResultInput.Abstract("input that was not saved")

    def representative(): Option[(Array[Array[Int]], Array[Double])] =
      if r.isNull then None
      else
        var cells: Option[Array[Array[Int]]] = None
        var coefficients: Option[Array[Double]] = None
        r.obj {
          case "cells" =>
            val b = Array.newBuilder[Array[Int]]
            r.arr(() => b += r.ints())
            cells = Some(b.result())
          case "coefficients" => coefficients = Some(r.doubles())
          case _              => r.skip()
        }
        (cells, coefficients) match
          case (Some(c), Some(x)) if c.length == x.length => Some((c, x))
          case (Some(c), Some(x))                         =>
            r.fail(s"a representative has ${c.length} cells and ${x.length} coefficients")
          case _ => r.fail("a representative needs \"cells\" and \"coefficients\"")

    def cellList(): Array[Array[Int]] =
      val b = Array.newBuilder[Array[Int]]
      r.arr(() => b += r.ints())
      b.result()

    def readInput(): ResultInput =
      var kind: Option[String] = None
      var points: Option[Array[Array[Double]]] = None
      var shape: Option[Array[Int]] = None
      var values: Option[Array[Double]] = None
      r.obj {
        case "kind"   => kind = Some(r.string())
        case "points" =>
          val b = Array.newBuilder[Array[Double]]
          r.arr(() => b += r.doubles())
          points = Some(b.result())
        case "shape"  => shape = Some(r.ints())
        case "values" => values = Some(r.doubles())
        case _        => r.skip()
      }
      kind match
        case Some("points") =>
          ResultInput.Points(points.getOrElse(r.fail("input of kind \"points\" needs \"points\"")))
        case Some("image") =>
          (shape, values) match
            case (Some(s), Some(v)) if s.map(_.toLong).product == v.length.toLong => ResultInput.Image(s, v)
            case (Some(s), Some(v))                                               =>
              r.fail(s"an image of shape ${s.mkString(" x ")} has ${v.length} values")
            case _ => r.fail("input of kind \"image\" needs \"shape\" and \"values\"")
        case Some(other) => ResultInput.Abstract(other)
        case None        => r.fail("the input needs a \"kind\"")

    r.obj {
      case "format"               => format = Some(r.string())
      case "formatVersion"        => formatVersion = Some(r.readInt())
      case "method"               => method = r.string()
      case "options"              => options = r.strings()
      case "complex"              => complex = r.string()
      case "representativeType"   => cocycles = r.string().equalsIgnoreCase("cocycles")
      case "field"                => field = r.string()
      case "prime"                => prime = r.readInt()
      case "landmarks"            => landmarks = r.ints()
      case "persistenceThreshold" => threshold = r.readDouble()
      case "dimensions"           => dims = Some(r.ints())
      case "births"               => births = Some(r.doubles())
      case "deaths"               => deaths = Some(r.doubles())
      case "birthCells"           => birthCells = Some(cellList())
      case "deathCells"           => deathCells = Some(cellList())
      case "reported"             => reported = Some(r.ints())
      case "representatives"      =>
        val b = Array.newBuilder[Option[(Array[Array[Int]], Array[Double])]]
        r.arr(() => b += representative())
        representatives = Some(b.result())
      case "input" => input = readInput()
      case _       => r.skip()
    }
    r.end()

    if !format.contains(Format) then
      throw new IllegalArgumentException(
        s"$path is not a saved tda4j result: its \"format\" is ${format.fold("missing")(f => s"\"$f\"")}, " +
          s"not \"$Format\""
      )
    formatVersion match
      case Some(Version) => ()
      case Some(v)       =>
        throw new IllegalArgumentException(
          s"$path has format version $v; this version of tda4j reads format version $Version" +
            (if v > Version then ": read it with a newer tda4j" else "")
        )
      case None => throw new IllegalArgumentException(s"$path: \"formatVersion\" is missing")
    def required[T](name: String, v: Option[T]): T =
      v.getOrElse(throw new IllegalArgumentException(s"$path: \"$name\" is missing"))
    val ds = required("dimensions", dims)
    val bs = required("births", births)
    val es = required("deaths", deaths)
    if bs.length != ds.length || es.length != ds.length then
      throw new IllegalArgumentException(
        s"$path: ${ds.length} dimensions, ${bs.length} births and ${es.length} deaths: one of each per bar"
      )
    for (name, cells) <- List("birthCells" -> birthCells, "deathCells" -> deathCells); list <- cells do
      if list.length != ds.length then
        throw new IllegalArgumentException(s"$path: ${list.length} \"$name\" for ${ds.length} bars: one per bar")
    if birthCells.isDefined != deathCells.isDefined then
      throw new IllegalArgumentException(s"$path: \"birthCells\" and \"deathCells\" come together")
    val visible = reported.getOrElse(Array.range(0, ds.length))
    visible.find(k => k < 0 || k >= ds.length).foreach { k =>
      throw new IllegalArgumentException(s"$path: \"reported\" names bar $k, and there are ${ds.length} bars")
    }
    if visible.distinct.length != visible.length then
      throw new IllegalArgumentException(s"$path: \"reported\" names a bar twice")
    representatives.foreach { reps =>
      if reps.length != visible.length then
        throw new IllegalArgumentException(
          s"$path: ${reps.length} representatives for ${visible.length} reported bars: one per reported bar"
        )
    }
    val byBar = mutable.HashMap.empty[Int, Option[(Array[Array[Int]], Array[Double])]]
    representatives.foreach(reps => visible.indices.foreach(k => byBar(visible(k)) = reps(k)))
    // Copies, as a computed result builds fresh arrays on every call: a caller may change what it gets.
    val cycleProvider: Int => (Array[Array[Int]], Array[Double]) = k =>
      byBar.get(k) match
        case Some(Some((cells, coefficients))) => (cells.map(_.clone()), coefficients.clone())
        case Some(None)                        =>
          throw new UnsupportedOperationException(s"$path records no representative for this bar")
        case None =>
          throw new UnsupportedOperationException(
            s"$path does not hold this bar's representative: only the reported bars' representatives are saved"
          )
    val boundaryMatrix: () => BoundaryMatrixData = () =>
      throw new UnsupportedOperationException(
        "a saved result does not keep its complex; compute the result again for its boundary matrix"
      )
    val source = ResultSource(method, options, complex, cocycles, field, prime, input, landmarks)
    val cellProvider: Int => (Array[Int], Array[Int]) = (birthCells, deathCells) match
      case (Some(born), Some(died)) => k => (born(k).clone(), died(k).clone())
      case _                        => PersistenceResult.noCells
    new PersistenceResult(ds, bs, es, cycleProvider, boundaryMatrix, visible, threshold, Some(source), cellProvider)
