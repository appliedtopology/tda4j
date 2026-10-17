package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.matlab.TDA4j.sessionLook
import org.appliedtopology.tda4j.plot.{Figure, Plot}

/** A circular coordinate from `TDA4j.circularCoordinates` (see `CircularCoordinates.compute`). */
final class CircularCoordinatesResult private[matlab] (
  private val points: Array[Array[Double]],
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

  /** The points (their first two coordinates) on a cyclic color map by angle; points with no coordinate hollow-gray. */
  def plot(): Figure =
    Plot.circularCoordinates(points, thetaArray.indices.filter(hasCoordinate).map(i => i -> thetaArray(i)).toMap)
