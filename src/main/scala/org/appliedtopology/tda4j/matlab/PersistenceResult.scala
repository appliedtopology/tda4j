package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.TDA4j.sessionLook
import org.appliedtopology.tda4j.plot.{BarOrder, Figure, Plot, Scene, Svg}

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

  private def matchingRows(matching: Option[BarcodeDistance.Matching]): Array[Array[Int]] =
    matching.fold(Array.empty[Array[Int]]) { m =>
      (m.pairs.map((i, j) => Array(i, j)) ++ m.leftToDiagonal.map(i => Array(i, -1)) ++
        m.rightToDiagonal.map(j => Array(-1, j))).toArray
    }

  /** An optimal matching of the dimension-`dimension` bars of this result with those of `other` for the Wasserstein
    * distance of order `order`: one row `[i j]` per matched pair of bars, `[i -1]` for a bar of this result matched to
    * the diagonal and `[-1 j]` for one of `other`. The bars are numbered from 0 in the order of [[toArrayUnfiltered]]
    * restricted to that dimension. No rows if the numbers of essential bars differ (the distance is `Inf` then). See
    * `BarcodeDistance.wassersteinMatching`.
    */
  def wassersteinMatching(other: PersistenceResult, dimension: Int, order: Double): Array[Array[Int]] =
    matchingRows(
      BarcodeDistance.wassersteinMatching(barsOfDimension(dimension), other.barsOfDimension(dimension), order)
    )

  /** As [[wassersteinMatching]], for the bottleneck distance. */
  def bottleneckMatching(other: PersistenceResult, dimension: Int): Array[Array[Int]] =
    matchingRows(BarcodeDistance.bottleneckMatching(barsOfDimension(dimension), other.barsOfDimension(dimension)))

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

  /** The number of bars of the dimension-`dimension` bars alive at each of `resolution` points of `[tMin, tMax]` (a bar
    * `[birth, death)` is alive at `t` when `birth <= t < death`). See `Vectorization.bettiCurve`.
    */
  def bettiCurve(dimension: Int, tMin: Double, tMax: Double, resolution: Int): Array[Int] =
    Vectorization.bettiCurve(barsOfDimension(dimension), tMin, tMax, resolution)

  /** The persistence silhouette of the dimension-`dimension` bars, weighted by persistence to the power `power`,
    * sampled at `resolution` points of `[tMin, tMax]`. Essential bars are left out. See `Vectorization.silhouette`.
    */
  def silhouette(dimension: Int, power: Double, tMin: Double, tMax: Double, resolution: Int): Array[Double] =
    Vectorization.silhouette(barsOfDimension(dimension), tMin, tMax, resolution, power)

  /** The persistence entropy of the dimension-`dimension` bars; essential bars are left out. With `normalized`, divided
    * by the logarithm of the number of bars, so that it lies in `[0, 1]`. See `Vectorization.persistenceEntropy`.
    */
  def persistenceEntropy(dimension: Int, normalized: Boolean): Double =
    Vectorization.persistenceEntropy(barsOfDimension(dimension), normalized)

  /** The sliced Wasserstein distance between the dimension-`dimension` bars of this result and `other`, averaged over
    * `directions` directions. Essential bars are left out. See `BarcodeDistance.slicedWassersteinDistance`.
    */
  def slicedWassersteinDistance(other: PersistenceResult, dimension: Int, directions: Int): Double =
    BarcodeDistance.slicedWassersteinDistance(
      barsOfDimension(dimension),
      other.barsOfDimension(dimension),
      directions
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
    else f.copy(subtitle = f.subtitle + s"; ${hiddenCount()} no longer than ${Svg.number(threshold)} not shown")

  /** The reported bars as a barcode: one bar per class, grouped by dimension; hover a bar for its numbers. */
  def plotBarcode(): Figure = withHiddenNote(Plot.barcode(reportedBars))

  /** The barcode of the `longest` most persistent reported bars of each dimension. */
  def plotBarcode(longest: Int): Figure = withHiddenNote(Plot.barcode(reportedBars, longest = Some(longest)))

  /** The barcode with the bars of each dimension sorted by `order`: `birth` (the default), `persistence` (the longest
    * first) or `death`.
    */
  def plotBarcode(order: String): Figure = withHiddenNote(Plot.barcode(reportedBars, order = BarOrder.named(order)))

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

  // The refusals below name what cannot be drawn in words (`what`), not a method: the CLI shows them as they are.

  private def noCoordinates(what: String): Nothing =
    throw new UnsupportedOperationException(
      s"this result was computed from ${src.input.words}, which has no coordinates to draw $what on; the barcode, " +
        "the diagram, the landscapes and the persistence image can be drawn for every result"
    )

  private def points(what: String): Array[Array[Double]] = src.input match
    case ResultInput.Points(ps)  => ps
    case ResultInput.Image(_, _) =>
      throw new UnsupportedOperationException(
        s"$what is drawn for point clouds, and this result was computed from an image"
      )
    case ResultInput.Abstract(_) => noCoordinates(what)

  private def image2D(what: String): org.appliedtopology.tda4j.Image = src.input match
    case ResultInput.Image(shape, values) =>
      if shape.length != 2 then
        throw new UnsupportedOperationException(
          s"$what is drawn for 2-D images, and this result's image has shape ${shape.mkString(" x ")}"
        )
      org.appliedtopology.tda4j.Image(values.toIndexedSeq, shape.toIndexedSeq)
    case _ => throw new IllegalStateException("not an image")

  // The result's own complex over its points at the scale `at`, rebuilt from the options that computed it.
  private def drawn(what: String, at: Double): TDA4j.DrawnComplex =
    TDA4j.drawnComplex(src.options, src.complex, points(what), src.landmarks, at)

  /** The input: the points (their first two coordinates) or the 2-D image. */
  def plotInput(): Figure = src.input match
    case ResultInput.Points(ps)  => Plot.points(ps)
    case ResultInput.Image(_, _) => Plot.image(image2D("the input"))
    case ResultInput.Abstract(_) => noCoordinates("the input")

  /** The complex at scale `at` over the points, whichever complex the result was computed from (a witness complex over
    * its landmarks): with the balls whose overlaps a `vr`, `cech` or `alpha` complex records. It is the complex the
    * computation built, with the alpha backend, the landmarks and the DTM and sparse parameters it used; past the
    * result's `maxFiltrationValue` it stops there and says so.
    */
  def plotComplex(at: Double): Figure =
    val d = drawn("the complex", at)
    val figure =
      Plot.complex(points("the complex"), d.stream, d.scale, d.ball, s"${d.name} complex at ${Svg.number(d.scale)}")
    d.stoppedAt.fold(figure)(m =>
      figure.copy(subtitle = s"${figure.subtitle}; the computation stopped at ${Svg.number(m)}")
    )

  // The facade's own cells and coefficients, as `Plot` takes them: for `Z/p` the coefficients are already the balanced
  // integers `Plot.number` gives for a typed chain, so the plot is the same either way, and no field is invented.
  private def simplicialTerms(i: Int): Seq[(Simplex[Int], Double)] =
    cycleVertices(i).toSeq.map(vs => Simplex.from(vs.toIndexedSeq)).zip(cycleCoefficients(i))

  private def cubicalTerms(i: Int): Seq[(Cube, Double)] =
    cycleVertices(i).toSeq.map(c => Cube.fromEncoded(c.toIndexedSeq)).zip(cycleCoefficients(i))

  private def requireBar(i: Int): Unit =
    if i < 0 || i >= size() then
      throw new IllegalArgumentException(
        s"there is no bar $i: this result reports " +
          (if size() == 0 then "no bars" else s"${size()} bars, numbered 0 to ${size() - 1}")
      )

  private def representativeTitle(i: Int): String =
    val kind = if src.cocycles then "Cocycle" else "Cycle"
    val d = if death(i).isPosInfinity then "∞" else Svg.number(death(i))
    s"$kind of bar $i: H${dimension(i)} [${Svg.number(birth(i))}, $d)"

  /** Bar `i`'s representative over the input: a cycle's cells in its dimension's color, a cocycle's colored by value
    * (balanced integers for `Z/p`), or a cubical chain over its 2-D image; each cell's coefficient on hover.
    */
  def plotRepresentative(i: Int): Figure = representativeFigure(i, None)

  /** [[plotRepresentative]] at scale `at`, in the result's own complex (in its units): a cycle drawn over the complex
    * at `at`; a cocycle restricted to its cells in the complex at `at`. `at = Infinity` draws a cocycle whole.
    */
  def plotRepresentative(i: Int, at: Double): Figure = representativeFigure(i, Some(at))

  /** The cells of the result's complex with value at most `at`, up to dimension `dim`, numbered as the input's points.
    */
  private def complexAt(at: Double, dim: Int, plot: String): Set[Simplex[Int]] =
    val d = drawn(plot, at)
    d.stream.iterator
      .filter(c => c.dim <= dim && d.stream.filtrationValue(c) <= d.scale)
      .toSet

  private def representativeFigure(i: Int, at: Option[Double]): Figure =
    requireBar(i)
    src.input match
      case ResultInput.Points(ps) =>
        val terms = simplicialTerms(i)
        val title = representativeTitle(i)
        (src.cocycles, at) match
          case (true, Some(r)) if r.isPosInfinity => Plot.cocycle(ps, terms, Optional.empty, title)
          case (true, Some(r))                    =>
            val cells = complexAt(r, dimension(i), "a representative at a scale")
            val kept = terms.filter((s, _) => cells.contains(s))
            Plot
              .cocycle(ps, kept, Optional.empty, title)
              .copy(subtitle = s"${kept.size} of its ${terms.size} cells: those in the complex at ${Svg.number(r)}")
          case (true, None)     => representativeFigure(i, Some(birth(i)))
          case (false, None)    => Plot.cycle(ps, terms, title)
          case (false, Some(r)) =>
            Plot.cycle(ps, terms, title).over(plotComplex(r))
      case ResultInput.Image(_, _) =>
        if at.isDefined then
          throw new UnsupportedOperationException(
            "a representative at a scale is drawn for point clouds; draw an image's representative without a scale"
          )
        Plot.cubicalChain(image2D("a representative"), cubicalTerms(i), representativeTitle(i))
      case ResultInput.Abstract(_) => noCoordinates("a representative")

  /** The points in 3-D (their first three coordinates), to turn and zoom. */
  def plotInput3D(): Scene = Plot.points3D(points("a 3-D view"))

  /** The complex at scale `at` in 3-D, whichever complex the result was computed from. */
  def plotComplex3D(at: Double): Scene =
    val d = drawn("a 3-D view", at)
    Plot.complex3D(points("a 3-D view"), d.stream, d.scale, s"${d.name} complex at ${Svg.number(d.scale)}")

  /** Bar `i`'s cycle in 3-D over the points: its edges and triangles in its dimension's color. */
  def plotRepresentative3D(i: Int): Scene =
    requireBar(i)
    val ps = points("a 3-D view")
    if src.cocycles then
      throw new UnsupportedOperationException(
        "a cocycle is drawn in 2-D, colored by its values, which a 3-D view would not show; for a 3-D view, " +
          "compute cycles (representativeType cycles)"
      )
    Plot.cycle3D(ps, simplicialTerms(i), representativeTitle(i))
