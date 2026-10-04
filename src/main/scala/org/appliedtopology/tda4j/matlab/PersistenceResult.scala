package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.*

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
  * of them. Each bar has a representative ([[cycleVertices]], [[cycleCoefficients]]): a cocycle for the `ripser` and
  * `cohomology` engines, a cycle for the others.
  */
final class PersistenceResult private[matlab] (
  private val allDims: Array[Int],
  private val allBirths: Array[Double],
  private val allDeaths: Array[Double],
  private val cycleProvider: Int => (Array[Array[Int]], Array[Double]),
  private val boundaryMatrixProvider: () => BoundaryMatrixData,
  private val visible: Array[Int],
  private val threshold: Double
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
      thr
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
      boundaryMatrixProvider
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
    (0 until allDims.length)
      .filter(allDims(_) == dim)
      .map { i =>
        val upper: BarcodeEndpoint[Double] =
          if allDeaths(i).isPosInfinity then PositiveInfinity[Double]() else OpenEndpoint(allDeaths(i))
        PersistenceBar[Double, Nothing](dim, ClosedEndpoint(allBirths(i)), upper)
      }

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
