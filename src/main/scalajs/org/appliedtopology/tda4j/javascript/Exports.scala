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

  /** The relative persistent homology of the Vietoris-Rips complex of a point cloud modulo the subcomplex on the points
    * `subcomplex` (point numbers, from 0): the bars of `H(X_t, A_t)`. See `relativePersistence` in the user guide.
    */
  @JSExportTopLevel("relativePersistence")
  def relativePersistence(
    points: js.Array[js.Array[Double]],
    subcomplex: js.Array[Int],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): Result =
    guarded(Result(TDA4j.computeRelativeFromPoints(matrix(points), subcomplex.toArray, optionArray(options))))

  /** [[relativePersistence]] for a finite metric space given as its full matrix of distances. */
  @JSExportTopLevel("relativePersistenceFromDistances")
  def relativePersistenceFromDistances(
    distances: js.Array[js.Array[Double]],
    subcomplex: js.Array[Int],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): Result =
    guarded(
      Result(TDA4j.computeRelativeFromDistanceMatrix(matrix(distances), subcomplex.toArray, optionArray(options)))
    )

  /** The map on persistent homology induced by the inclusion of the Vietoris-Rips complex of a subsample (`subsample`:
    * point numbers, from 0) into that of the whole point cloud: see `inclusionMap` in the user guide. Returns
    * `{ maxDimension, characteristic, degrees }` with, for each degree, `{ dimension, sourceBars, targetBars, entries,
    * image, kernel, cokernel }`.
    */
  @JSExportTopLevel("inclusionMap")
  def inclusionMap(
    points: js.Array[js.Array[Double]],
    subsample: js.Array[Int],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object =
    guarded(mapObject(TDA4j.computeInclusionFromPoints(matrix(points), subsample.toArray, optionArray(options))))

  /** [[inclusionMap]] for a finite metric space given as its full matrix of distances. */
  @JSExportTopLevel("inclusionMapFromDistances")
  def inclusionMapFromDistances(
    distances: js.Array[js.Array[Double]],
    subsample: js.Array[Int],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object =
    guarded(
      mapObject(TDA4j.computeInclusionFromDistanceMatrix(matrix(distances), subsample.toArray, optionArray(options)))
    )

  /** The map induced by the simplicial map of Vietoris-Rips complexes that sends point `i` of `sourcePoints` to point
    * `vertexMap[i]` of `targetPoints`; the result is that of [[inclusionMap]].
    */
  @JSExportTopLevel("simplicialMap")
  def simplicialMap(
    sourcePoints: js.Array[js.Array[Double]],
    targetPoints: js.Array[js.Array[Double]],
    vertexMap: js.Array[Int],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object =
    guarded(
      mapObject(
        TDA4j.computeMapFromPoints(matrix(sourcePoints), matrix(targetPoints), vertexMap.toArray, optionArray(options))
      )
    )

  /** [[simplicialMap]] between two finite metric spaces given as full matrices of distances. */
  @JSExportTopLevel("simplicialMapFromDistances")
  def simplicialMapFromDistances(
    sourceDistances: js.Array[js.Array[Double]],
    targetDistances: js.Array[js.Array[Double]],
    vertexMap: js.Array[Int],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object =
    guarded(
      mapObject(
        TDA4j.computeMapFromDistanceMatrices(
          matrix(sourceDistances),
          matrix(targetDistances),
          vertexMap.toArray,
          optionArray(options)
        )
      )
    )

  private def rowsOf(rows: Array[Array[Double]]): js.Array[js.Array[Double]] =
    js.Array(rows.toSeq.map(row => js.Array(row*))*)

  private def mapObject(map: matlab.PersistenceMapResult): js.Object =
    js.Dynamic
      .literal(
        maxDimension = map.maxDimension(),
        characteristic = map.characteristic(),
        degrees = js.Array((0 to map.maxDimension()).map { k =>
          js.Dynamic.literal(
            dimension = k,
            sourceBars = rowsOf(map.sourceBars(k)),
            targetBars = rowsOf(map.targetBars(k)),
            entries = rowsOf(map.entries(k)),
            image = rowsOf(map.image(k)),
            kernel = rowsOf(map.kernel(k)),
            cokernel = rowsOf(map.cokernel(k))
          )
        }*)
      )
      .asInstanceOf[js.Object]

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

  /** The resampling confidence band of the bars of one degree of a point cloud (Fasy et al. 2014): resample the points,
    * compute each resample's diagram and measure its bottleneck distance to the original. Options: `dimension` (default
    * 1), `rounds` (100), `alpha` (0.05), `seed` (0), `subsampleSize` (draw that many points without replacement; by
    * default draw with replacement), `prime`, `maxFiltrationValue` and `threads`. Returns `{ width, threshold, alpha,
    * distances }`: a bar that persists longer than `threshold` (twice the width) survives the resampling.
    */
  @JSExportTopLevel("bootstrapBand")
  def bootstrapBand(
    points: js.Array[js.Array[Double]],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object = guarded {
    val result = TDA4j.bootstrapBand(matrix(points), optionArray(options))
    js.Dynamic
      .literal(
        width = result.width(),
        threshold = result.threshold(),
        alpha = result.alpha(),
        distances = js.Array(result.distances()*)
      )
      .asInstanceOf[js.Object]
  }

  /** The ToMATo cluster of every point of a cloud (0 for the cluster of the highest peak): the graph and the density
    * come from the `k` nearest neighbours, and two peaks merge when the valley between them is shallower than `tau`.
    */
  @JSExportTopLevel("tomatoClusters")
  def tomatoClusters(points: js.Array[js.Array[Double]], k: Int, tau: Double): js.Array[Int] =
    guarded(js.Array(TDA4j.tomatoClusters(matrix(points), k, tau)*))

  /** The valleys between the density peaks used by `tomatoClusters`: `[birth, death]` per bar, with death `Infinity`
    * for the highest peak of each component. A bar's persistence is the depth of its valley: choose `tau` in a gap.
    */
  @JSExportTopLevel("tomatoDiagram")
  def tomatoDiagram(points: js.Array[js.Array[Double]], k: Int): js.Array[js.Array[Double]] =
    guarded(grid(TDA4j.tomatoDiagram(matrix(points), k)))

  /** The sliding-window (Takens) embedding of a time series: the window `(x_t, x_{t+delay}, ..., x_{t+(dimension-1)
    * delay})` for every `stride`-th start `t` (`delay` and `stride` default to 1), as a point cloud.
    */
  @JSExportTopLevel("takensEmbedding")
  def takensEmbedding(
    series: js.Array[Double],
    dimension: Int,
    delay: js.UndefOr[Int] = js.undefined,
    stride: js.UndefOr[Int] = js.undefined
  ): js.Array[js.Array[Double]] =
    guarded(grid(TDA4j.takensEmbedding(series.toArray, dimension, delay.getOrElse(1), stride.getOrElse(1))))

  /** The same for a series of vectors, one array per time step: a point is the `dimension` arrays of a window, one
    * after the other.
    */
  @JSExportTopLevel("takensEmbeddingOfVectors")
  def takensEmbeddingOfVectors(
    series: js.Array[js.Array[Double]],
    dimension: Int,
    delay: js.UndefOr[Int] = js.undefined,
    stride: js.UndefOr[Int] = js.undefined
  ): js.Array[js.Array[Double]] =
    guarded(grid(TDA4j.takensEmbeddingOfVectors(matrix(series), dimension, delay.getOrElse(1), stride.getOrElse(1))))

  /** The first lag in `1..maxLag` at which the series' autocorrelation is zero or negative: a first guess for the
    * `delay` of `takensEmbedding` (a quarter of the period for a sinusoid).
    */
  @JSExportTopLevel("delayByAutocorrelation")
  def delayByAutocorrelation(series: js.Array[Double], maxLag: Int): Int =
    guarded(TDA4j.delayByAutocorrelation(series.toArray, maxLag))

  /** The discrete Morse reduction of the Vietoris-Rips complex of a point cloud. Options: `maxDimension` (default 2),
    * `maxFiltrationValue`, `strategy` (`"reduction"`, the default, or `"coreduction"`), `prime` and `threads`. Returns
    * `{ cellCounts, criticalCounts, pairCount, criticalCells, bars }`: the cells of the complex and the cells left
    * after pairing off, by dimension, the number of pairs, the cells left as arrays of vertex numbers, and the bars
    * computed on them (rows `[dimension, birth, death]`), which are the bars of the complex.
    */
  @JSExportTopLevel("morseReduction")
  def morseReduction(
    points: js.Array[js.Array[Double]],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object = guarded(morseObject(TDA4j.morseReductionFromPoints(matrix(points), optionArray(options))))

  /** [[morseReduction]] for a finite metric space given as its full matrix of distances. */
  @JSExportTopLevel("morseReductionFromDistances")
  def morseReductionFromDistances(
    distances: js.Array[js.Array[Double]],
    options: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Object = guarded(morseObject(TDA4j.morseReductionFromDistanceMatrix(matrix(distances), optionArray(options))))

  private def morseObject(result: matlab.MorseReductionResult): js.Object =
    js.Dynamic
      .literal(
        cellCounts = js.Array(result.cellCounts()*),
        criticalCounts = js.Array(result.criticalCounts()*),
        pairCount = result.pairCount(),
        criticalCells = js.Array(result.criticalCells().map(cell => js.Array(cell*))*),
        bars = js.Array(result.bars().map(row => js.Array(row*))*)
      )
      .asInstanceOf[js.Object]

  /** The persistent Laplacian of the Vietoris-Rips complex of a point cloud, on the simplices of dimension `dimension`
    * that have entered by `from`, for the complex at `to` (`from <= to`).
    */
  @JSExportTopLevel("persistentLaplacian")
  def persistentLaplacian(
    points: js.Array[js.Array[Double]],
    dimension: Int,
    from: Double,
    to: Double
  ): PersistentLaplacian =
    guarded(PersistentLaplacian(TDA4j.persistentLaplacianFromPoints(matrix(points), dimension, from, to)))

  /** [[persistentLaplacian]] for a finite metric space given as its full matrix of distances. */
  @JSExportTopLevel("persistentLaplacianFromDistances")
  def persistentLaplacianFromDistances(
    distances: js.Array[js.Array[Double]],
    dimension: Int,
    from: Double,
    to: Double
  ): PersistentLaplacian =
    guarded(
      PersistentLaplacian(TDA4j.persistentLaplacianFromDistanceMatrix(matrix(distances), dimension, from, to))
    )

  /** The barcode of the homology of the product of two filtered complexes from the barcodes of the factors (rows
    * `[dimension, birth, death]`): a cell of the product enters at the larger (`filtration: "max"`, the default) or the
    * sum (`"sum"`) of the values of its two factors.
    */
  @JSExportTopLevel("productBars")
  def productBars(
    first: js.Array[js.Array[Double]],
    second: js.Array[js.Array[Double]],
    filtration: js.UndefOr[String] = js.undefined
  ): js.Array[js.Array[Double]] =
    guarded(grid(TDA4j.productBars(matrix(first), matrix(second), filtration.getOrElse("max"))))

  /** The internal Hom of two barcodes (rows `[dimension, birth, death]`): the module of maps from the first to the
    * second shifted by `t`; a bar of dimension `p` and one of dimension `q` give a bar of dimension `q - p`.
    */
  @JSExportTopLevel("homBars")
  def homBars(
    first: js.Array[js.Array[Double]],
    second: js.Array[js.Array[Double]]
  ): js.Array[js.Array[Double]] =
    guarded(grid(TDA4j.homBars(matrix(first), matrix(second))))

  /** The dimension of the space of maps of persistence modules between two barcodes, degree by degree. */
  @JSExportTopLevel("homDimension")
  def homDimension(first: js.Array[js.Array[Double]], second: js.Array[js.Array[Double]]): Int =
    guarded(TDA4j.homDimension(matrix(first), matrix(second)))

  /** The dual of a barcode: the row `[dimension, birth, death]` of `[b, d)` becomes `[dimension, -d, -b]`, the interval
    * `(-d, -b]` (`-Infinity` as the first end for a bar that never dies).
    */
  @JSExportTopLevel("dualBars")
  def dualBars(bars: js.Array[js.Array[Double]]): js.Array[js.Array[Double]] =
    guarded(grid(TDA4j.dualBars(matrix(bars))))

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

/** The persistent Laplacian of one dimension and one pair of filtration values: a symmetric matrix on the simplices
  * that have entered by `from`, whose kernel has the dimension of the persistent Betti number from `from` to `to`.
  */
final class PersistentLaplacian private[javascript] (underlying: matlab.PersistentLaplacianResult):

  /** The dimension of the simplices the matrix is on. */
  @JSExport
  def dimension: Int = underlying.dimension()

  /** The simplices that have entered by this value are the rows and columns. */
  @JSExport
  def from: Double = underlying.start()

  /** The complex at this value is the one the chains live in. */
  @JSExport
  def to: Double = underlying.stop()

  /** The simplices as arrays of vertex numbers, one for each row and column of `matrix`. */
  @JSExport
  def cells: js.Array[js.Array[Int]] = js.Array(underlying.cells().map(c => js.Array(c*))*)

  /** The matrix, as an array of rows. */
  @JSExport
  def matrix: js.Array[js.Array[Double]] = js.Array(underlying.matrix().map(row => js.Array(row*))*)

  /** The eigenvalues, smallest first. */
  @JSExport
  def eigenvalues: js.Array[Double] = js.Array(underlying.eigenvalues()*)

  /** The dimension of the kernel: the persistent Betti number from `from` to `to`. */
  @JSExport
  def bettiNumber: Int = underlying.bettiNumber()

  /** The smallest eigenvalue that is not zero, `undefined` if there is none. */
  @JSExport
  def spectralGap: js.UndefOr[Double] = if underlying.hasSpectralGap() then underlying.spectralGap() else js.undefined

object PersistentLaplacian:
  private[javascript] def apply(underlying: matlab.PersistentLaplacianResult): PersistentLaplacian =
    new PersistentLaplacian(underlying)

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

  /** The cells that open and close `bars[i]`: `{ birth, death }`, each a cell as in `representative(i).cells`. `birth`
    * is `null` where the engine recorded no cells, `death` where no cell closes the bar (an essential one).
    */
  @JSExport
  def barCells(i: Int): js.Object = Exports.guarded {
    def cell(vertices: Array[Int]): js.Array[Int] = if vertices.isEmpty then null else js.Array(vertices*)
    js.Dynamic
      .literal(birth = cell(underlying.birthCell(i)), death = cell(underlying.deathCell(i)))
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

  /** An optimal matching of the dimension-`dimension` bars with those of `other` for the `order`-Wasserstein distance
    * (default 1): `[i, j]` pairs bar `i` here with bar `j` there, `[i, -1]` sends a bar here to the diagonal and
    * `[-1, j]` one there. Empty when the numbers of essential bars differ.
    */
  @JSExport
  def wassersteinMatching(
    other: Result,
    dimension: Int,
    order: js.UndefOr[Double] = js.undefined
  ): js.Array[js.Array[Int]] =
    Exports.guarded(
      js.Array(
        underlying.wassersteinMatching(other.wrapped, dimension, order.getOrElse(1.0)).map(row => js.Array(row*))*
      )
    )

  /** As `wassersteinMatching`, for the bottleneck distance. */
  @JSExport
  def bottleneckMatching(other: Result, dimension: Int): js.Array[js.Array[Int]] =
    Exports.guarded(
      js.Array(underlying.bottleneckMatching(other.wrapped, dimension).map(row => js.Array(row*))*)
    )

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
