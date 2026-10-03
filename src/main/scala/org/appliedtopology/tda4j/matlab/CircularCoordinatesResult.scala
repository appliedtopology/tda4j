package org.appliedtopology.tda4j.matlab

/** A circular coordinate from `TDA4j.circularCoordinates` (see `CircularCoordinates.compute`). */
final class CircularCoordinatesResult private[matlab] (
  private val thetaArray: Array[Double], // NaN for a point outside the relevant connected component
  private val birthValue: Double,
  private val deathValue: Double,
  private val rValue: Double,
  private val primeValue: Int
):
  /** The angle of each point (in the input's row order) in `[0, 1)`, or `NaN` for a point outside the loop's connected
    * component.
    */
  def theta(): Array[Double] = thetaArray

  def hasCoordinate(i: Int): Boolean = !thetaArray(i).isNaN

  /** The birth and death of the chosen loop (`Infinity` if it never dies). */
  def birth(): Double = birthValue
  def death(): Double = deathValue

  /** The `r` and `prime` this result was computed with. */
  def r(): Double = rValue
  def prime(): Int = primeValue
