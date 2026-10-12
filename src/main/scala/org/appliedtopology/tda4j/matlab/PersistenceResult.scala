package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.{Figure, Plot, Scene, Svg}

/** The boundary matrix of the complex a [[PersistenceResult]] was computed from, one column per cell in filtration
  * order; exposed only through `PersistenceResult`'s accessors.
  */
private[matlab] case class BoundaryMatrixData(
  rows: Array[Int],
  cols: Array[Int],
  values: Array[Double],
  columnDims: Array[Int],
  columnVertices: Array[Array[Int]],
  columnFiltrationValues: Array[Double]
)

/** A finished persistence computation, for MATLAB and Java: every method takes and returns only `int`, `double`, arrays
  * of those, or another `PersistenceResult`.
  *
  * Bars are numbered `0 until size()`, in no guaranteed order; an essential bar has `death(i) == Infinity`. The bars
  * reported are those that pass the persistence threshold (see [[persistenceThreshold]]); [[toArrayUnfiltered]] has all
  * of them. Each bar has a representative ([[cycleVertices]], [[cycleCoefficients]]): a cycle, or a cocycle with option
  * `representativeType` `cocycles`.
  *
  * The `plot...` methods draw the result (see the `plot` package): each returns a figure to `view()` in the browser or
  * `save("name.svg")` (or `.html`). [[save]] writes the whole result to a file that `TDA4j.loadResult` reads back, from
  * MATLAB, Java, Scala or the command line.
  */
final class PersistenceResult private[matlab] (
  private val allDims: Array[Int],
  private val allBirths: Array[Double],
  private val allDeaths: Array[Double],
  private val cycleProvider: Int => (Array[Array[Int]], Array[Double]),
  private val boundaryMatrixProvider: () => BoundaryMatrixData,
  private val visible: Array[Int],
  private val threshold: Double,
  private val source: Option[ResultSource] = None
):
  /** A result that reports every bar -- what `fromBars` builds; `TDA4j`'s dispatch then narrows it with
    * [[withPersistenceThreshold]].
    */
  private[matlab] def this(
    dims: Array[Int],
    births: Array[Double],
    deaths: Array[Double],
    cycleProvider: Int => (Array[Array[Int]], Array[Double]),
    boundaryMatrixProvider: () => BoundaryMatrixData
  ) = this(dims, births, deaths, cycleProvider, boundaryMatrixProvider, Array.range(0, dims.length), 0.0)

  // The REPORTED bars: row `i` below is full-barcode index `visible(i)`.
  private def dims: Array[Int] = visible.map(allDims)
  private def births: Array[Double] = visible.map(allBirths)
  private def deaths: Array[Double] = visible.map(allDeaths)

  /** The persistence a reported bar had to exceed (`0`: no threshold). Essential bars are always reported. */
  def persistenceThreshold(): Double = threshold

  /** How many computed bars the threshold hid: there are `size() + hiddenCount()` in all. */
  def hiddenCount(): Int = allDims.length - visible.length

  /** Every computed bar, threshold or not, as an N-by-3 matrix like [[toArray]]. */
  def toArrayUnfiltered(): Array[Array[Double]] =
    Array.tabulate(allDims.length)(i => Array(allDims(i).toDouble, allBirths(i), allDeaths(i)))

  /** This result reporting only the bars that pass the threshold (`PersistenceFilter.threshold`: `minPersistence` if
    * given, else `fraction` of `scale`, evaluated only if needed). Computed from all the bars, so it does not compound.
    */
  private[matlab] def withPersistenceThreshold(
    minPersistence: Option[Double],
    fraction: Double,
    scale: => Double
  ): PersistenceResult =
    val thr = PersistenceFilter.threshold(
      minPersistence,
      fraction,
      scale,
      PersistenceFilter.filtrationRange(allBirths, allDeaths)
    )
    new PersistenceResult(
      allDims,
      allBirths,
      allDeaths,
      cycleProvider,
      boundaryMatrixProvider,
      PersistenceFilter.keptIndices(allBirths, allDeaths, thr),
      thr,
      source
    )

  /** This result without its zero-length bars (`birth == death`): what every entry point returns unless the
    * `includeZeroLength` option is set. Applied before [[withPersistenceThreshold]].
    */
  private[matlab] def withoutZeroLength: PersistenceResult =
    val keep = allDims.indices.filter(i => allBirths(i) != allDeaths(i)).toArray
    new PersistenceResult(
      keep.map(allDims),
      keep.map(allBirths),
      keep.map(allDeaths),
      i => cycleProvider(keep(i)),
      boundaryMatrixProvider,
      Array.range(0, keep.length),
      0.0,
      source
    )

  def size(): Int = visible.length

  /** The reported bars as an N-by-3 matrix: dimension, birth, death (`Infinity` for an essential bar). */
  def toArray(): Array[Array[Double]] =
    Array.tabulate(visible.length) { i =>
      Array(allDims(visible(i)).toDouble, allBirths(visible(i)), allDeaths(visible(i)))
    }

  def dimension(i: Int): Int = allDims(visible(i))
  def birth(i: Int): Double = allBirths(visible(i))
  def death(i: Int): Double = allDeaths(visible(i))

  /** The cells of bar `i`'s representative, one row each:
    *
    *   - for a simplicial complex, the simplex's sorted vertices (0-based point numbers; for the witness complex, point
    *     numbers, not landmark numbers);
    *   - for an image, the cube's doubled coordinates: along each axis `2a` for a vertex coordinate `a`, `2a + 1` for
    *     the unit interval `[a, a + 1]` (a pixel `(i, j)` is `(2i + 1, 2j + 1)`).
    *
    * Every engine records a representative for every bar; an `UnsupportedOperationException` here is an engine bug.
    */
  def cycleVertices(i: Int): Array[Array[Int]] = cycleProvider(visible(i))._1

  /** The coefficients of bar `i`'s representative, parallel to `cycleVertices(i)`: over `Z/p` an integer representative
    * of the residue, over the reals the value.
    */
  def cycleCoefficients(i: Int): Array[Double] = cycleProvider(visible(i))._2

  /** All the bars of dimension `dim`, threshold or not, without representatives: what the distances and vectorizations
    * compare (so they do not depend on each result's own threshold).
    */
  private def barsOfDimension(dim: Int): IndexedSeq[PersistenceBar[Double, Nothing]] =
    (0 until allDims.length).filter(allDims(_) == dim).map(barAt)

  /** `Infinity` is the L∞ ground norm, a finite `p >= 1` the Lp norm. */
  private def toGroundNorm(groundNorm: Double): BarcodeDistance.GroundNorm =
    if groundNorm.isPosInfinity then BarcodeDistance.GroundNorm.LInfinity else BarcodeDistance.GroundNorm.LP(groundNorm)

  /** The bottleneck distance between the dimension-`dimension` bars of this result and `other` (L∞ ground norm; see
    * `BarcodeDistance`). `Infinity` when the numbers of essential bars differ: then no finite matching exists.
    */
  def bottleneckDistance(other: PersistenceResult, dimension: Int): Double =
    bottleneckDistance(other, dimension, Double.PositiveInfinity)

  /** [[bottleneckDistance]] under the ground norm `groundNorm` (`Infinity` for L∞). */
  def bottleneckDistance(other: PersistenceResult, dimension: Int, groundNorm: Double): Double =
    BarcodeDistance.bottleneckDistance(
      barsOfDimension(dimension),
      other.barsOfDimension(dimension),
      toGroundNorm(groundNorm)
    )

  /** The Wasserstein distance of order 1 between the dimension-`dimension` bars of this result and `other` (L∞ ground
    * norm; see `BarcodeDistance`).
    */
  def wassersteinDistance(other: PersistenceResult, dimension: Int): Double =
    wassersteinDistance(other, dimension, 1.0, Double.PositiveInfinity)

  /** [[wassersteinDistance]] of order `order` (finite, at least 1). */
  def wassersteinDistance(other: PersistenceResult, dimension: Int, order: Double): Double =
    wassersteinDistance(other, dimension, order, Double.PositiveInfinity)

  /** [[wassersteinDistance]] of order `order` under the ground norm `groundNorm` (`Infinity` for L∞). */
  def wassersteinDistance(other: PersistenceResult, dimension: Int, order: Double, groundNorm: Double): Double =
    BarcodeDistance.wassersteinDistance(
      barsOfDimension(dimension),
      other.barsOfDimension(dimension),
      order,
      toGroundNorm(groundNorm)
    )

  /** The first `numLevels` persistence landscapes of the dimension-`dimension` bars, sampled at `resolution` points of
    * `[tMin, tMax]`: `levels(k)(j)` is level `k` (`0` the outermost) at point `j`. See `Vectorization.landscape`.
    */
  def landscape(dimension: Int, numLevels: Int, tMin: Double, tMax: Double, resolution: Int): Array[Array[Double]] =
    Vectorization.landscape(barsOfDimension(dimension), numLevels, tMin, tMax, resolution)

  /** The persistence image of the dimension-`dimension` bars over `[birthMin, birthMax] x [persistenceMin,
    * persistenceMax]`, `birthResolution` by `persistenceResolution` pixels, Gaussian width `sigma`, with the weight
    * reaching 1 at the largest persistence. Essential bars are left out. See `Vectorization.persistenceImage`.
    */
  def persistenceImage(
    dimension: Int,
    sigma: Double,
    birthMin: Double,
    birthMax: Double,
    persistenceMin: Double,
    persistenceMax: Double,
    birthResolution: Int,
    persistenceResolution: Int
  ): Array[Array[Double]] =
    Vectorization.persistenceImage(
      barsOfDimension(dimension),
      sigma,
      (birthMin, birthMax),
      (persistenceMin, persistenceMax),
      birthResolution,
      persistenceResolution
    )

  /** [[persistenceImage]] with the weight reaching 1 at persistence `weightCap`. */
  def persistenceImage(
    dimension: Int,
    sigma: Double,
    birthMin: Double,
    birthMax: Double,
    persistenceMin: Double,
    persistenceMax: Double,
    birthResolution: Int,
    persistenceResolution: Int,
    weightCap: Double
  ): Array[Array[Double]] =
    Vectorization.persistenceImage(
      barsOfDimension(dimension),
      sigma,
      (birthMin, birthMax),
      (persistenceMin, persistenceMax),
      birthResolution,
      persistenceResolution,
      weightCap
    )

  /** Built on first use, then cached. */
  private lazy val boundaryMatrixData: BoundaryMatrixData = boundaryMatrixProvider()

  /** The number of cells of the complex: the size of the (square) boundary matrix. */
  def numCells(): Int = boundaryMatrixData.columnDims.length

  /** The row numbers of the boundary matrix's nonzero entries (0-based), parallel to [[boundaryCols]] and
    * [[boundaryValues]]: cell `cols(k)` has cell `rows(k)` in its boundary with coefficient `values(k)`. In MATLAB:
    * `sparse(rows + 1, cols + 1, values, n, n)`.
    */
  def boundaryRows(): Array[Int] = boundaryMatrixData.rows

  /** The column numbers of the nonzero entries, parallel to [[boundaryRows]]. */
  def boundaryCols(): Array[Int] = boundaryMatrixData.cols

  /** The nonzero entries, parallel to [[boundaryRows]], as in [[cycleCoefficients]]. */
  def boundaryValues(): Array[Double] = boundaryMatrixData.values

  /** The dimension of cell `j` (a column of the boundary matrix, not a bar). */
  def columnDimension(j: Int): Int = boundaryMatrixData.columnDims(j)

  /** Cell `j`, in the format of [[cycleVertices]]. */
  def columnVertices(j: Int): Array[Int] = boundaryMatrixData.columnVertices(j)

  /** The filtration value of cell `j`: keep the cells with value at most `r` for the complex at scale `r`. */
  def columnFiltrationValue(j: Int): Double = boundaryMatrixData.columnFiltrationValues(j)

  // ---------------------------------------------------------------------------------------------------------------
  // saving
  // ---------------------------------------------------------------------------------------------------------------

  /** Writes the result to `path` for `TDA4j.loadResult` (from MATLAB, Java, Scala or `tda4j --input-format result`):
    * every bar, the threshold and which bars it reported, the reported bars' representatives (computed now if they were
    * not yet), what it was computed with, and the points or image it was computed from. Not the boundary matrix. The
    * file is JSON, readable from any language; non-finite numbers are the strings `"Infinity"`, `"-Infinity"` and
    * `"NaN"` (Python's `float(x)` reads both kinds of entry). `loadResult` reads the whole file into memory. With
    * `representativeType` `cocycles` and every bar reported, a dimension-0 cocycle is a whole connected component, so
    * the file can grow with the square of the number of points. Returns the file's absolute path.
    */
  def save(path: String): String =
    ResultFile
      .write(path, allDims, allBirths, allDeaths, visible, threshold, k => cycleProvider(visible(k)), source)
      .toString

  // ---------------------------------------------------------------------------------------------------------------
  // plots
  // ---------------------------------------------------------------------------------------------------------------

  /** Every facade entry point attaches its source; a result without one was built by hand inside this package. */
  private def src: ResultSource =
    source.getOrElse(throw new IllegalStateException("this result does not record what it was computed from"))

  /** This result recording what it was computed from (what the plots draw on). */
  private[matlab] def withSource(s: ResultSource): PersistenceResult =
    new PersistenceResult(
      allDims,
      allBirths,
      allDeaths,
      cycleProvider,
      boundaryMatrixProvider,
      visible,
      threshold,
      Some(s)
    )

  private def barAt(k: Int): PersistenceBar[Double, Nothing] =
    val upper: BarcodeEndpoint[Double] =
      if allDeaths(k).isPosInfinity then PositiveInfinity[Double]() else OpenEndpoint(allDeaths(k))
    PersistenceBar[Double, Nothing](allDims(k), ClosedEndpoint(allBirths(k)), upper)

  private def reportedBars: IndexedSeq[PersistenceBar[Double, Nothing]] = visible.toIndexedSeq.map(barAt)
  private def everyBar: IndexedSeq[PersistenceBar[Double, Nothing]] = allDims.indices.map(barAt)

  /** The subtitle's note on the bars the threshold hid. */
  private def withHiddenNote(f: Figure): Figure =
    if hiddenCount() == 0 then f
    else f.copy(subtitle = f.subtitle + s"; ${hiddenCount()} shorter than ${Svg.number(threshold)} not shown")

  /** The reported bars as a barcode: one bar per class, grouped by dimension; hover a bar for its numbers. */
  def plotBarcode(): Figure = withHiddenNote(Plot.barcode(reportedBars))

  /** The barcode of the `longest` most persistent reported bars of each dimension. */
  def plotBarcode(longest: Int): Figure = withHiddenNote(Plot.barcode(reportedBars, longest = Some(longest)))

  /** The reported bars as a persistence diagram: a point `(birth, death)` per bar, essential bars on the `∞` line. */
  def plotDiagram(): Figure = withHiddenNote(Plot.diagram(reportedBars))

  /** The first three persistence landscapes of the dimension-`dimension` bars: every bar, as [[landscape]] uses. */
  def plotLandscape(dimension: Int): Figure = Plot.landscape(everyBar, dimension)

  /** The persistence image of the dimension-`dimension` bars: every finite bar, as [[persistenceImage]] uses. */
  def plotPersistenceImage(dimension: Int): Figure = Plot.persistenceImage(everyBar, dimension)

  /** The reported bar of dimension `dimension` with the largest persistence (an essential bar first; the lowest index
    * among equals): the one to pass to [[plotRepresentative]] for the most prominent feature.
    */
  def mostPersistentBar(dimension: Int): Int =
    val candidates = (0 until size()).filter(this.dimension(_) == dimension)
    if candidates.isEmpty then
      val present = (0 until size()).map(this.dimension).distinct.sorted
      throw new IllegalArgumentException(
        s"no reported bar has dimension $dimension" +
          (if present.isEmpty then "; this result reports no bars"
           else s"; reported dimensions: ${present.mkString(", ")}")
      )
    // a zero-length [Inf, Inf) bar would give Inf - Inf = NaN, which sorts above every number
    def persistence(i: Int) = if death(i) == birth(i) then 0.0 else death(i) - birth(i)
    candidates.maxBy(i => (persistence(i), -i))

  private def noCoordinates(plot: String): Nothing =
    throw new UnsupportedOperationException(
      s"$plot needs coordinates to draw on, and this result was computed from ${src.input.words}; plotBarcode, " +
        "plotDiagram, plotLandscape and plotPersistenceImage work for every result"
    )

  private def points(plot: String): Array[Array[Double]] = src.input match
    case ResultInput.Points(ps)  => ps
    case ResultInput.Image(_, _) =>
      throw new UnsupportedOperationException(s"$plot draws point clouds; this result was computed from an image")
    case ResultInput.Abstract(_) => noCoordinates(plot)

  private def image2D(plot: String): org.appliedtopology.tda4j.Image = src.input match
    case ResultInput.Image(shape, values) =>
      if shape.length != 2 then
        throw new UnsupportedOperationException(
          s"$plot draws 2-D images; this result's image has shape ${shape.mkString(" x ")}"
        )
      org.appliedtopology.tda4j.Image(values.toIndexedSeq, shape.toIndexedSeq)
    case _ => throw new IllegalStateException("not an image")

  private def complexKind(plot: String): PointCloudComplex = src.complex match
    case "vr"    => VietorisRips
    case "cech"  => Cech
    case "alpha" => AlphaShapes
    case other   =>
      throw new UnsupportedOperationException(
        s"$plot draws the vr, cech and alpha complexes; this result's complex is $other"
      )

  /** The input: the points (their first two coordinates) or the 2-D image. */
  def plotInput(): Figure = src.input match
    case ResultInput.Points(ps)  => Plot.points(ps)
    case ResultInput.Image(_, _) => Plot.image(image2D("plotInput"))
    case ResultInput.Abstract(_) => noCoordinates("plotInput")

  /** The complex at scale `at` over the points (`vr`, `cech` or `alpha`), with the balls whose overlaps it records. */
  def plotComplex(at: Double): Figure = Plot.complex(points("plotComplex"), at, complexKind("plotComplex"))

  // The facade's own cells and coefficients, as `Plot` takes them: for `Z/p` the coefficients are already the balanced
  // integers `Plot.number` gives for a typed chain, so the plot is the same either way, and no field is invented.
  private def simplicialTerms(i: Int): Seq[(Simplex[Int], Double)] =
    cycleVertices(i).toSeq.map(vs => Simplex.from(vs.toIndexedSeq)).zip(cycleCoefficients(i))

  private def cubicalTerms(i: Int): Seq[(Cube, Double)] =
    cycleVertices(i).toSeq.map(c => Cube.fromEncoded(c.toIndexedSeq)).zip(cycleCoefficients(i))

  private def requireBar(i: Int, plot: String): Unit =
    if i < 0 || i >= size() then
      throw new IllegalArgumentException(
        s"$plot: there is no bar $i; this result reports ${size()} bars, numbered 0 to ${size() - 1}"
      )

  private def representativeTitle(i: Int): String =
    val kind = if src.cocycles then "Cocycle" else "Cycle"
    val d = if death(i).isPosInfinity then "∞" else Svg.number(death(i))
    s"$kind of bar $i: H${dimension(i)} [${Svg.number(birth(i))}, $d)"

  /** Bar `i`'s representative over the input: a cycle's cells in its dimension's color, a cocycle's colored by value
    * (balanced integers for `Z/p`), or a cubical chain over its 2-D image; each cell's coefficient on hover.
    */
  def plotRepresentative(i: Int): Figure = representativeFigure(i, None)

  /** [[plotRepresentative]] at scale `at`, in the result's own complex (`vr`, `cech` or `alpha`, in its units): a cycle
    * drawn over the complex at `at`; a cocycle restricted to its cells in the complex at `at` (at `birth(i)`, the cells
    * that cut a loop as it closes: a bar's cocycle lives on the complex where the bar dies, across the data).
    */
  def plotRepresentative(i: Int, at: Double): Figure = representativeFigure(i, Some(at))

  /** The cells of the result's complex with value at most `at`, up to dimension `dim`. */
  private def complexAt(ps: Array[Array[Double]], at: Double, dim: Int, plot: String): Set[Simplex[Int]] =
    val kind = complexKind(plot)
    val stream = if kind eq AlphaShapes then kind.fromPoints(ps, dim, None) else kind.fromPoints(ps, dim, Some(at))
    stream.iterator.filter(c => c.dim <= dim && stream.filtrationValue(c) <= at).toSet

  private def representativeFigure(i: Int, at: Option[Double]): Figure =
    requireBar(i, "plotRepresentative")
    src.input match
      case ResultInput.Points(ps) =>
        val terms = simplicialTerms(i)
        val title = representativeTitle(i)
        (src.cocycles, at) match
          case (true, None)    => Plot.cocycle(ps, terms, Optional.empty, title)
          case (true, Some(r)) =>
            val cells = complexAt(ps, r, dimension(i), "plotRepresentative at a scale")
            val kept = terms.filter((s, _) => cells.contains(s))
            Plot
              .cocycle(ps, kept, Optional.empty, title)
              .copy(subtitle = s"${kept.size} of its ${terms.size} cells: those in the complex at ${Svg.number(r)}")
          case (false, None)    => Plot.cycle(ps, terms, title)
          case (false, Some(r)) =>
            Plot.cycle(ps, terms, title).over(Plot.complex(ps, r, complexKind("plotRepresentative at a scale")))
      case ResultInput.Image(_, _) =>
        if at.isDefined then
          throw new UnsupportedOperationException(
            "plotRepresentative at a scale is for point clouds; for an image, call plotRepresentative(i)"
          )
        Plot.cubicalChain(image2D("plotRepresentative"), cubicalTerms(i), representativeTitle(i))
      case ResultInput.Abstract(_) => noCoordinates("plotRepresentative")

  /** The points in 3-D (their first three coordinates), to turn and zoom. */
  def plotInput3D(): Scene = Plot.points3D(points("plotInput3D"))

  /** The complex at scale `at` in 3-D (`vr`, `cech` or `alpha`). */
  def plotComplex3D(at: Double): Scene = Plot.complex3D(points("plotComplex3D"), at, complexKind("plotComplex3D"))

  /** Bar `i`'s cycle in 3-D over the points: its edges and triangles in its dimension's color. */
  def plotRepresentative3D(i: Int): Scene =
    requireBar(i, "plotRepresentative3D")
    val ps = points("plotRepresentative3D")
    if src.cocycles then
      throw new UnsupportedOperationException(
        "plotRepresentative3D draws cycles: a cocycle needs its values, which only plotRepresentative shows; " +
          "compute with representativeType cycles for a 3-D view"
      )
    Plot.cycle3D(ps, simplicialTerms(i), representativeTitle(i))
