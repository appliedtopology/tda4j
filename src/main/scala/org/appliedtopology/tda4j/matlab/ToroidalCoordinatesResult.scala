package org.appliedtopology.tda4j.matlab

/** A finished toroidal-coordinates computation (`homology.CircularCoordinates.computeToroidal`,
  * `.claude/WORKLOG-toroidal-coordinates.md`), in the same MATLAB-marshalable shape `CircularCoordinatesResult`
  * uses -- see `TDA4j.toroidalCoordinates`'s own doc for how this gets constructed. A separate class rather than
  * generalizing `CircularCoordinatesResult` itself (which stays exactly as it was: a single `theta` array,
  * unchanged for `mimaReportBinaryIssues`) since this result's shape is genuinely different -- `k` coordinates
  * per point, plus the basis-change/Gram-matrix diagnostics `CircularCoordinatesResult` has no field for at all.
  */
final class ToroidalCoordinatesResult private[matlab] (
  private val thetaArrays: Array[Array[Double]], // row c = coordinate c, one entry per input point, NaN outside the component
  private val cocycleIndicesArray: Array[Int],
  private val basisChangeArray: Array[Array[Int]],
  private val originalGramArray: Array[Array[Double]],
  private val reducedGramArray: Array[Array[Double]],
  private val rValue: Double,
  private val primeValue: Int
):
  /** Number of combined coordinates (`k`, the length of `cocycleIndices`). */
  def dimension(): Int = thetaArrays.length

  /** Coordinate `c`'s value at each input point (same row order as the `points`/`distances` this was computed
    * from), each in `[0, 1)` -- or `Double.NaN` for a point outside the shared connected component (see
    * `homology.CircularCoordinates.computeToroidal`'s own doc).
    */
  def theta(c: Int): Array[Double] = thetaArrays(c)

  def hasCoordinate(i: Int): Boolean = !thetaArrays(0)(i).isNaN

  /** Which persistent H¹ classes (same indexing as `h1Bars`/`circularCoordinates`'s own `cocycleIndex`) were
    * combined, in the same order as `theta`'s own rows.
    */
  def cocycleIndices(): Array[Int] = cocycleIndicesArray

  /** `U`, `k x k`: column `c` gives coordinate `c`'s integer coefficients against the ORIGINAL (un-reduced)
    * per-class circular coordinates, in `cocycleIndices`' own order -- the identity matrix if `reduce = false`
    * was passed to `toroidalCoordinates`, or if only one class was requested.
    */
  def basisChange(): Array[Array[Int]] = basisChangeArray

  /** The chosen classes' own harmonic-representative Gram matrix (the paper's dSMV inner product) before ([[originalGram]])
    * and after ([[reducedGram]]) lattice reduction -- smaller off-diagonal entries in `reducedGram` is the
    * evidence reduction actually decorrelated the coordinates on this data.
    */
  def originalGram(): Array[Array[Double]] = originalGramArray
  def reducedGram(): Array[Array[Double]] = reducedGramArray

  /** The `r` and `prime` this result was actually computed with (echoing the caller's own arguments back). */
  def r(): Double = rValue
  def prime(): Int = primeValue
