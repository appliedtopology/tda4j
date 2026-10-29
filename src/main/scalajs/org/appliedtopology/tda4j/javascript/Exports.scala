package org.appliedtopology.tda4j.javascript

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab
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

  /** The persistent homology of the witness complex on `landmarks` (point numbers, from 0), as `persistence` computes
    * it with `complex: "witness"` but with the landmarks you chose (see `selectLandmarks`).
    */
  @JSExportTopLevel("persistenceFromLandmarks")
  def persistenceFromLandmarks(
    points: js.Array[js.Array[Double]],
    landmarks: js.Array[Int],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): Result =
    guarded(Result(TDA4j.computeFromPointsAndLandmarks(matrix(points), landmarks.toArray, optionArray(options))))

  /** [[persistenceFromLandmarks]] for a finite metric space given as its full matrix of distances. */
  @JSExportTopLevel("persistenceFromDistancesAndLandmarks")
  def persistenceFromDistancesAndLandmarks(
    distances: js.Array[js.Array[Double]],
    landmarks: js.Array[Int],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): Result =
    guarded(
      Result(TDA4j.computeFromDistanceMatrixAndLandmarks(matrix(distances), landmarks.toArray, optionArray(options)))
    )

  /** Picks landmarks from a point cloud: options `numLandmarks` (required), `landmarkSelector` (`"maxmin"` or
    * `"random"`) and `landmarkSeed`. Returns `{ landmarks, coveringRadius }`, the landmarks as point numbers from 0 and
    * the covering radius `R` (compute with `maxFiltrationValue: 2 * R`, the JavaPlex tutorials' recipe).
    */
  @JSExportTopLevel("selectLandmarks")
  def selectLandmarks(
    points: js.Array[js.Array[Double]],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object = guarded {
    val selection = TDA4j.selectLandmarksFromPoints(matrix(points), optionArray(options))
    landmarkObject(selection.landmarks(), selection.coveringRadius())
  }

  /** [[selectLandmarks]] for a finite metric space given as its full matrix of distances. */
  @JSExportTopLevel("selectLandmarksFromDistances")
  def selectLandmarksFromDistances(
    distances: js.Array[js.Array[Double]],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object = guarded {
    val selection = TDA4j.selectLandmarksFromDistanceMatrix(matrix(distances), optionArray(options))
    landmarkObject(selection.landmarks(), selection.coveringRadius())
  }

  /** The covering radius `R` of any set of landmarks (point numbers, from 0). */
  @JSExportTopLevel("coveringRadius")
  def coveringRadius(points: js.Array[js.Array[Double]], landmarks: js.Array[Int]): Double =
    guarded(TDA4j.coveringRadiusFromPoints(matrix(points), landmarks.toArray))

  /** The Euler characteristic curve of a point cloud's Vietoris-Rips complex: the vertices minus the edges plus the
    * triangles, ... at each scale, counted without building the complex. Options: `maxSimplexDimension` (default 2),
    * `maxFiltrationValue` (default: the enclosing radius) and `threads`.
    */
  @JSExportTopLevel("eulerCurve")
  def eulerCurve(
    points: js.Array[js.Array[Double]],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): EulerCurve =
    guarded(EulerCurve(TDA4j.eulerCurveFromPoints(matrix(points), optionArray(options))))

  /** [[eulerCurve]] for a finite metric space given as its full matrix of distances. */
  @JSExportTopLevel("eulerCurveFromDistances")
  def eulerCurveFromDistances(
    distances: js.Array[js.Array[Double]],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): EulerCurve =
    guarded(EulerCurve(TDA4j.eulerCurveFromDistanceMatrix(matrix(distances), optionArray(options))))

  /** The loops of the point cloud's Vietoris-Rips complex: `[birth, death]` for each persistent class of degree 1, the
    * most persistent first. Loop `i` is `cocycleIndex: i` of `circularCoordinates`; pick `r` between its birth and
    * death.
    */
  @JSExportTopLevel("h1Bars")
  def h1Bars(points: js.Array[js.Array[Double]]): js.Array[js.Array[Double]] =
    guarded(grid(TDA4j.h1Bars(matrix(points))))

  /** Circular coordinates of a point cloud at scale `r`: one angle in `[0, 1)` per point for the loop `cocycleIndex`
    * (option, default 0) of `h1Bars`, over the field with `prime` elements (option, an odd prime, default 47). Returns
    * `{ theta, birth, death, r, prime }`, where `theta[i]` is `NaN` for a point outside the loop's connected component.
    * Throws if the loop has no integer lift at `prime`: use a larger prime.
    */
  @JSExportTopLevel("circularCoordinates")
  def circularCoordinates(
    points: js.Array[js.Array[Double]],
    r: Double,
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object = guarded {
    val chosen = numbers(options, Set("cocycleIndex", "prime"))
    val result = TDA4j.circularCoordinates(
      matrix(points),
      r,
      chosen.get("cocycleIndex").fold(0)(integer("cocycleIndex", _)),
      chosen.get("prime").fold(47)(integer("prime", _))
    )
    js.Dynamic
      .literal(
        theta = js.Array(result.theta()*),
        birth = result.birth(),
        death = result.death(),
        r = result.r(),
        prime = result.prime()
      )
      .asInstanceOf[js.Object]
  }

  /** Toroidal coordinates of a point cloud at scale `r`: `k` angles per point, combining the loops `cocycleIndices` of
    * `h1Bars` that are alive together at `r`. Options: `prime` (default 47) and `reduce` (lattice reduction of the
    * coordinates, default `true`). Returns
    * `{ theta, cocycleIndices, basisChange, originalGram, reducedGram, r, prime }`, where `theta[c][i]` is coordinate
    * `c` of point `i`.
    */
  @JSExportTopLevel("toroidalCoordinates")
  def toroidalCoordinates(
    points: js.Array[js.Array[Double]],
    r: Double,
    cocycleIndices: js.Array[Int],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object = guarded {
    val dictionary = options.getOrElse(js.Dictionary[js.Any]())
    val unknown = dictionary.keys.filterNot(Set("prime", "reduce")).toSeq
    require(unknown.isEmpty, s"unknown option ${unknown.mkString(", ")}; the options are prime and reduce")
    val prime = dictionary.get("prime").fold(47)(integer("prime", _))
    val reduce = dictionary.get("reduce").fold(true) { value =>
      require(js.typeOf(value) == "boolean", s"option reduce must be a boolean, not a ${js.typeOf(value)}")
      value.asInstanceOf[Boolean]
    }
    val result = TDA4j.toroidalCoordinates(matrix(points), r, cocycleIndices.toArray, prime, reduce)
    js.Dynamic
      .literal(
        theta = js.Array((0 until result.dimension()).map(c => js.Array(result.theta(c)*))*),
        cocycleIndices = js.Array(result.cocycleIndices()*),
        basisChange = js.Array(result.basisChange().map(row => js.Array(row*))*),
        originalGram = grid(result.originalGram()),
        reducedGram = grid(result.reducedGram()),
        r = result.r(),
        prime = result.prime()
      )
      .asInstanceOf[js.Object]
  }

  /** A point cloud from the text of a file: one point per line, coordinates separated by commas or whitespace (CSV, or
    * Ripser's `point-cloud` format).
    */
  @JSExportTopLevel("parsePointCloud")
  def parsePointCloud(text: String): js.Array[js.Array[Double]] =
    guarded(grid(CSV.parsePointCloud(text)))

  /** A point cloud from the text of a GUDHI OFF or nOFF file. */
  @JSExportTopLevel("parseOff")
  def parseOff(text: String): js.Array[js.Array[Double]] =
    guarded(grid(Gudhi.parseOff(text)))

  /** The full matrix of distances from the text of a file. `format` is `"full"` (a square matrix, the default),
    * `"lower"` (row `i` has its `i` entries below the diagonal), or one of Ripser's formats: `"ripser-lower"`,
    * `"ripser-upper"`, `"ripser-dense"` (only the part below the diagonal is read) and `"ripser-sparse"` (`i j d` per
    * line; a pair not listed has distance `Infinity`).
    */
  @JSExportTopLevel("parseDistanceMatrix")
  def parseDistanceMatrix(text: String, format: js.UndefOr[String] = js.undefined): js.Array[js.Array[Double]] =
    guarded {
      val parsed = format.getOrElse("full") match
        case "full"          => CSV.parseFullDistanceMatrix(text)
        case "lower"         => CSV.parseLowerTriangularDistanceMatrix(text)
        case "ripser-lower"  => Ripser.parseLowerDistanceMatrix(text)
        case "ripser-upper"  => Ripser.parseUpperDistanceMatrix(text)
        case "ripser-dense"  => Ripser.parseDistanceMatrix(text)
        case "ripser-sparse" => Ripser.parseSparseDistanceMatrix(text)
        case other           =>
          throw new IllegalArgumentException(
            s"unrecognized format '$other'; expected full, lower, ripser-lower, ripser-upper, ripser-dense or ripser-sparse"
          )
      grid(parsed)
    }

  private[javascript] def matrix(rows: js.Array[js.Array[Double]]): Array[Array[Double]] =
    rows.map(_.toArray).toArray

  private[javascript] def grid(rows: Array[Array[Double]]): js.Array[js.Array[Double]] =
    js.Array(rows.map(row => js.Array(row*))*)

  private def landmarkObject(landmarks: Array[Int], coveringRadius: Double): js.Object =
    js.Dynamic
      .literal(landmarks = js.Array(landmarks*), coveringRadius = coveringRadius)
      .asInstanceOf[js.Object]

  /** The numeric options of `options`, refusing a key outside `allowed` and a value that is not a number. */
  private def numbers(options: js.UndefOr[js.Dictionary[js.Any]], allowed: Set[String]): Map[String, Double] =
    options.toOption.fold(Map.empty[String, Double]) { dictionary =>
      val unknown = dictionary.keys.filterNot(allowed).toSeq
      require(
        unknown.isEmpty,
        s"unknown option ${unknown.mkString(", ")}; the options are ${allowed.toSeq.sorted.mkString(", ")}"
      )
      dictionary.toMap.collect {
        case (key, value) if !js.isUndefined(value) =>
          require(js.typeOf(value) == "number", s"option $key must be a number, not a ${js.typeOf(value)}")
          key -> value.asInstanceOf[Double]
      }
    }

  private def integer(name: String, value: Double): Int =
    require(value == math.rint(value) && math.abs(value) < Int.MaxValue, s"option $name must be an integer, not $value")
    value.toInt

  private def integer(name: String, value: js.Any): Int =
    require(js.typeOf(value) == "number", s"option $name must be a number, not a ${js.typeOf(value)}")
    integer(name, value.asInstanceOf[Double])

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

/** An Euler characteristic curve: a step function of the scale, kept as the scales where it steps and the value from
  * each of them on.
  */
final class EulerCurve private[javascript] (underlying: matlab.EulerCurveResult):

  /** The scales where the curve changes, strictly increasing; the first is `0`, where the vertices appear. */
  @JSExport
  def scales: js.Array[Double] = js.Array(underlying.scales()*)

  /** `values[i]` is the Euler characteristic from `scales[i]` up to (not including) `scales[i + 1]`. */
  @JSExport
  def values: js.Array[Double] = js.Array(underlying.values().map(_.toDouble)*)

  /** The Euler characteristic at scale `t`; `0` below the first scale. */
  @JSExport
  def at(t: Double): Double = Exports.guarded(underlying.at(t).toDouble)

  /** The curve at `resolution` evenly spaced scales across `[tMin, tMax]`, both ends included. */
  @JSExport
  def sample(tMin: Double, tMax: Double, resolution: Int): js.Array[Double] =
    Exports.guarded(js.Array(underlying.sample(tMin, tMax, resolution).map(_.toDouble)*))

object EulerCurve:
  private[javascript] def apply(underlying: matlab.EulerCurveResult): EulerCurve = new EulerCurve(underlying)

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

  /** The number of dimension-`dimension` bars alive at each of `resolution` samples of `[tMin, tMax]`: a bar `[birth,
    * death)` is alive at `t` when `birth <= t < death`.
    */
  @JSExport
  def bettiCurve(dimension: Int, tMin: Double, tMax: Double, resolution: Int): js.Array[Int] =
    Exports.guarded(js.Array(underlying.bettiCurve(dimension, tMin, tMax, resolution)*))

  /** The persistence silhouette of the dimension-`dimension` bars at `resolution` samples of `[tMin, tMax]`: the
    * average of the bars' tents weighted by persistence to the power `power` (default 1). Essential bars are left out.
    */
  @JSExport
  def silhouette(
    dimension: Int,
    tMin: Double,
    tMax: Double,
    resolution: Int,
    power: js.UndefOr[Double] = js.undefined
  ): js.Array[Double] =
    Exports.guarded(js.Array(underlying.silhouette(dimension, power.getOrElse(1.0), tMin, tMax, resolution)*))

  /** The persistence entropy of the dimension-`dimension` bars, in `[0, 1]` when `normalized`. Essential bars are left
    * out.
    */
  @JSExport
  def persistenceEntropy(dimension: Int, normalized: js.UndefOr[Boolean] = js.undefined): Double =
    Exports.guarded(underlying.persistenceEntropy(dimension, normalized.getOrElse(false)))

  /** The sliced Wasserstein distance to `other` between the dimension-`dimension` bars, averaged over `directions`
    * directions (default 50). Essential bars are left out.
    */
  @JSExport
  def slicedWassersteinDistance(other: Result, dimension: Int, directions: js.UndefOr[Int] = js.undefined): Double =
    Exports.guarded(underlying.slicedWassersteinDistance(other.wrapped, dimension, directions.getOrElse(50)))

  /** The persistence image of the dimension-`dimension` bars over `[birthMin, birthMax] x [persistenceMin,
    * persistenceMax]`, `birthResolution` by `persistenceResolution` pixels with a Gaussian of width `sigma`:
    * `image[i][j]` is the pixel at birth sample `i` and persistence sample `j`. Essential bars are left out; the weight
    * of a bar grows with its persistence up to the largest persistence, or up to `weightCap` when given.
    */
  @JSExport
  def persistenceImage(
    dimension: Int,
    sigma: Double,
    birthMin: Double,
    birthMax: Double,
    persistenceMin: Double,
    persistenceMax: Double,
    birthResolution: Int,
    persistenceResolution: Int,
    weightCap: js.UndefOr[Double] = js.undefined
  ): js.Array[js.Array[Double]] = Exports.guarded {
    grid(weightCap.toOption match
      case Some(cap) =>
        underlying.persistenceImage(
          dimension,
          sigma,
          birthMin,
          birthMax,
          persistenceMin,
          persistenceMax,
          birthResolution,
          persistenceResolution,
          cap
        )
      case None =>
        underlying.persistenceImage(
          dimension,
          sigma,
          birthMin,
          birthMax,
          persistenceMin,
          persistenceMax,
          birthResolution,
          persistenceResolution
        ))
  }

  /** The number of cells of the complex the result was computed from: the size of its boundary matrix. */
  @JSExport
  def numCells: Int = Exports.guarded(underlying.numCells())

  /** The nonzero entries of the boundary matrix, in the cells' filtration order: cell `cols[k]` has cell `rows[k]` in
    * its boundary with coefficient `values[k]`. Cells are numbered from 0; `columnDimension`, `columnVertices` and
    * `columnFiltrationValue` describe them. Over a prime field, a coefficient is a residue.
    */
  @JSExport
  def boundary(): js.Object = Exports.guarded {
    js.Dynamic
      .literal(
        rows = js.Array(underlying.boundaryRows()*),
        cols = js.Array(underlying.boundaryCols()*),
        values = js.Array(underlying.boundaryValues()*)
      )
      .asInstanceOf[js.Object]
  }

  /** The dimension of cell `j` (a column of the boundary matrix, not a bar). */
  @JSExport
  def columnDimension(j: Int): Int = Exports.guarded(underlying.columnDimension(j))

  /** Cell `j` as its vertices (point numbers from 0), or a cube's doubled coordinates for an image. */
  @JSExport
  def columnVertices(j: Int): js.Array[Int] = Exports.guarded(js.Array(underlying.columnVertices(j)*))

  /** The filtration value of cell `j`: the cells with value at most `r` are the complex at scale `r`. */
  @JSExport
  def columnFiltrationValue(j: Int): Double = Exports.guarded(underlying.columnFiltrationValue(j))

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

  private def grid(rows: Array[Array[Double]]): js.Array[js.Array[Double]] = Exports.grid(rows)

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
