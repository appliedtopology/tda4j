package org.appliedtopology.tda4j.matlab

/** A finished circular-coordinates computation (`homology.CircularCoordinates`, `.claude/WORKLOG-mainstream-
  * feature-gap-analysis.md` item 2), in the same MATLAB-marshalable shape `PersistenceResult` uses -- see
  * `TDA4j.circularCoordinates`'s own doc for how this gets constructed.
  */
final class CircularCoordinatesResult private[matlab] (
  private val thetaArray: Array[Double], // NaN for a point outside the relevant connected component
  private val birthValue: Double,
  private val deathValue: Double,
  private val rValue: Double,
  private val primeValue: Int
):
  /** One entry per input point (same row order as the `points`/`distances` this was computed from), each in
    * `[0, 1)` -- or `Double.NaN` for a point outside the connected component the chosen class lives in (see
    * `homology.CircularCoordinates`'s own class doc for why other components have no meaningful coordinate at
    * all, not a zero or an arbitrary default).
    */
  def theta(): Array[Double] = thetaArray

  def hasCoordinate(i: Int): Boolean = !thetaArray(i).isNaN

  /** The chosen bar's own full-filtration birth/death (`death` is `Double.POSITIVE_INFINITY` for an essential
    * bar) -- informational, matching what `cocycleIndex` selected.
    */
  def birth(): Double = birthValue
  def death(): Double = deathValue

  /** The `r` and `prime` this result was actually computed with (echoing the caller's own arguments back). */
  def r(): Double = rValue
  def prime(): Int = primeValue
