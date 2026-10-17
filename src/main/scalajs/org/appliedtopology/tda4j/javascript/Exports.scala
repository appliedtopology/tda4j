package org.appliedtopology.tda4j.javascript

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.{PersistenceResult, TDA4j}
import org.appliedtopology.tda4j.plot

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** The functions of the npm package `tda4j` (`import { persistence } from "tda4j"`): the facade `matlab.TDA4j` for
  * JavaScript. They take arrays of numbers and an options object, whose keys are the facade's option names
  * (`maxDimension`, `complex`, `engine`, `prime`, ...), and return a [[Result]]. A bad option or a malformed input
  * throws an `Error` whose message says what to change.
  */
object Exports:
  /** The version of the library. */
  @JSExportTopLevel("version")
  val version: String = LibraryVersion.value

  /** The persistent homology of a point cloud, one array of coordinates per point. */
  @JSExportTopLevel("persistence")
  def persistence(
    points: js.Array[js.Array[Double]],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): Result =
    guarded(Result(TDA4j.computeFromPoints(matrix(points), optionArray(options))))

  /** The persistent homology of a finite metric space, given as its full matrix of distances. */
  @JSExportTopLevel("persistenceFromDistances")
  def persistenceFromDistances(
    distances: js.Array[js.Array[Double]],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): Result =
    guarded(Result(TDA4j.computeFromDistanceMatrix(matrix(distances), optionArray(options))))

  /** The persistent homology of a grayscale image, `pixels[row][column]`, as a cubical complex. */
  @JSExportTopLevel("persistenceFromImage")
  def persistenceFromImage(
    pixels: js.Array[js.Array[Double]],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): Result =
    guarded(Result(TDA4j.computeFromImage(matrix(pixels), optionArray(options))))

  /** The persistent homology of a grid of any dimension: its `shape` and its values in row-major order. */
  @JSExportTopLevel("persistenceFromGrid")
  def persistenceFromGrid(
    shape: js.Array[Int],
    values: js.Array[Double],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): Result =
    guarded(Result(TDA4j.computeFromCubicalImage(shape.toArray, values.toArray, optionArray(options))))

  private[javascript] def matrix(rows: js.Array[js.Array[Double]]): Array[Array[Double]] =
    rows.map(_.toArray).toArray

  /** The facade takes its options as `[key, value, key, value, ...]`; a JavaScript object is turned into that, with its
    * numbers and booleans as the text they print as. An option set to `undefined` is not set.
    */
  private def optionArray(options: js.UndefOr[js.Dictionary[js.Any]]): Array[String] =
    options.toOption.fold(Array.empty[String]) { dictionary =>
      dictionary.toSeq.flatMap { (key, value) =>
        if js.isUndefined(value) then Nil
        else
          js.typeOf(value) match
            case "string" | "number" | "boolean" => Seq(key, value.toString)
            case other                           =>
              throw new IllegalArgumentException(s"option $key must be a string, a number or a boolean, not a $other")
      }.toArray
    }

  /** Runs `body`, turning a Scala exception into the JavaScript `Error` with the same message. */
  private[javascript] def guarded[A](body: => A): A =
    try body
    catch
      case e: js.JavaScriptException => throw e
      case e: Throwable => throw js.JavaScriptException(new js.Error(Option(e.getMessage).getOrElse(e.toString)))

/** The result of a computation: the bars, the representatives, and the plots. */
final class Result private[javascript] (underlying: PersistenceResult):

  /** The bars that pass the persistence threshold (by default, the essential ones and those longer than 1% of the scale
    * of the data), each `{ dim, birth, death }`, with `death` equal to `Infinity` for an essential bar.
    */
  @JSExport
  def bars: js.Array[js.Object] = barObjects(underlying.toArray())

  /** Every bar of positive length, the hidden ones included. */
  @JSExport
  def allBars: js.Array[js.Object] = barObjects(underlying.toArrayUnfiltered())

  /** How many bars the threshold hides: `bars.length + hiddenCount` is the length of `allBars`. */
  @JSExport
  def hiddenCount: Int = underlying.hiddenCount()

  /** The representative of `bars[i]`: `{ cells, coefficients }`, where `cells[k]` is a simplex's vertices (point
    * numbers, from 0), or a cube's doubled coordinates for an image, and `coefficients[k]` its coefficient.
    */
  @JSExport
  def representative(i: Int): js.Object = Exports.guarded {
    js.Dynamic
      .literal(
        cells = js.Array(underlying.cycleVertices(i).map(cell => js.Array(cell*))*),
        coefficients = js.Array(underlying.cycleCoefficients(i)*)
      )
      .asInstanceOf[js.Object]
  }

  /** The bottleneck distance to `other` between the dimension-`dimension` bars (all of them, not only the reported). */
  @JSExport
  def bottleneckDistance(other: Result, dimension: Int): Double =
    Exports.guarded(underlying.bottleneckDistance(other.wrapped, dimension))

  /** The `order`-Wasserstein distance (default 1) to `other` between the dimension-`dimension` bars. */
  @JSExport
  def wassersteinDistance(other: Result, dimension: Int, order: js.UndefOr[Double] = js.undefined): Double =
    Exports.guarded(underlying.wassersteinDistance(other.wrapped, dimension, order.getOrElse(1.0)))

  /** The first `numLevels` persistence landscapes of the dimension-`dimension` bars, `resolution` samples of
    * `[tMin, tMax]` each: `landscape[k][j]` is level `k` at sample `j`.
    */
  @JSExport
  def landscape(
    dimension: Int,
    numLevels: Int,
    tMin: Double,
    tMax: Double,
    resolution: Int
  ): js.Array[js.Array[Double]] =
    Exports.guarded(grid(underlying.landscape(dimension, numLevels, tMin, tMax, resolution)))

  /** The barcode, a bar per line. */
  @JSExport
  def plotBarcode(): Chart = Chart(underlying.plotBarcode())

  /** The persistence diagram. */
  @JSExport
  def plotDiagram(): Chart = Chart(underlying.plotDiagram())

  /** The complex at scale `at`, drawn over the input points. */
  @JSExport
  def plotComplex(at: Double): Chart = Exports.guarded(Chart(underlying.plotComplex(at)))

  /** The representative of `bars[i]` over the input, at scale `at` when given. */
  @JSExport
  def plotRepresentative(i: Int, at: js.UndefOr[Double] = js.undefined): Chart =
    Exports.guarded(Chart(at.fold(underlying.plotRepresentative(i))(underlying.plotRepresentative(i, _))))

  /** Writes the result as JSON to `path` (Node.js), and returns the absolute path. */
  @JSExport
  def save(path: String): String = Exports.guarded(underlying.save(path))

  private[javascript] def wrapped: PersistenceResult = underlying

  private def barObjects(rows: Array[Array[Double]]): js.Array[js.Object] =
    js.Array(
      rows.map(row => js.Dynamic.literal(dim = row(0).toInt, birth = row(1), death = row(2)).asInstanceOf[js.Object])*
    )

  private def grid(rows: Array[Array[Double]]): js.Array[js.Array[Double]] =
    js.Array(rows.map(row => js.Array(row*))*)

/** A plot: `svg()` for a picture to put in a page or a file, `html()` for a page of its own. */
final class Chart private[javascript] (underlying: plot.Plot):

  /** What the plot is about. */
  @JSExport
  def title: String = underlying.title

  /** The plot as an SVG document, in plain colors (`theme` is `"light"`, the default, or `"dark"`). */
  @JSExport
  def svg(theme: js.UndefOr[String] = js.undefined): String =
    Exports.guarded(underlying.svgFile(theme.fold(plot.Theme.Light)(plot.Theme.named)))

  /** The plot as a page of HTML that follows the reader's light or dark setting (`theme` is `"auto"`, the default). */
  @JSExport
  def html(theme: js.UndefOr[String] = js.undefined): String =
    Exports.guarded(underlying.html(theme.fold(plot.Theme.Auto)(plot.Theme.named)))

  /** Writes the plot to `path` (Node.js): an `.svg` file, or an HTML page for any other extension. */
  @JSExport
  def save(path: String): String = Exports.guarded(underlying.save(path))

  /** Shows the plot: appended to the page in a browser, opened in the default browser from Node.js. */
  @JSExport
  def show(): Unit = underlying.view()
