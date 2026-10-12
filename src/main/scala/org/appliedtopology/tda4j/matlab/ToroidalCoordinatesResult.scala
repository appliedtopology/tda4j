package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.plot.{Figure, Plot}

/** Toroidal coordinates from `TDA4j.toroidalCoordinates` (see `CircularCoordinates.computeToroidal`): `k` angles per
  * point, with the lattice-reduction diagnostics.
  */
final class ToroidalCoordinatesResult private[matlab] (
  private val thetaArrays: Array[
    Array[Double]
  ], // row c = coordinate c, one entry per input point, NaN outside the component
  private val cocycleIndicesArray: Array[Int],
  private val basisChangeArray: Array[Array[Int]],
  private val originalGramArray: Array[Array[Double]],
  private val reducedGramArray: Array[Array[Double]],
  private val rValue: Double,
  private val primeValue: Int
):
  /** Number of combined coordinates (`k`, the length of `cocycleIndices`). */
  def dimension(): Int = thetaArrays.length

  /** Coordinate `c` at each point (in the input's row order), in `[0, 1)`, or `NaN` for a point outside the loops'
    * connected component.
    */
  def theta(c: Int): Array[Double] = thetaArrays(c)

  def hasCoordinate(i: Int): Boolean = !thetaArrays(0)(i).isNaN

  /** The loops combined (numbered as in `TDA4j.h1Bars`), in the order of the coordinates. */
  def cocycleIndices(): Array[Int] = cocycleIndicesArray

  /** The `k x k` integer matrix `U`: column `c` gives coordinate `c` in terms of the circular coordinates of the loops
    * in [[cocycleIndices]] (the identity without reduction, or for one loop).
    */
  def basisChange(): Array[Array[Int]] = basisChangeArray

  /** The Gram matrix of the loops' harmonic cocycles before ([[originalGram]]) and after ([[reducedGram]]) lattice
    * reduction: smaller off-diagonal entries after mean less correlated coordinates.
    */
  def originalGram(): Array[Array[Double]] = originalGramArray
  def reducedGram(): Array[Array[Double]] = reducedGramArray

  /** The `r` and `prime` this result was computed with. */
  def r(): Double = rValue
  def prime(): Int = primeValue

  /** Coordinates `c1` and `c2` against each other on the torus `[0, 1) x [0, 1)`: points tracing a closed curve that
    * wraps the square are a torus knot of the data.
    */
  def plot(c1: Int, c2: Int): Figure =
    def theta(c: Int): Map[Int, Double] =
      if c < 0 || c >= dimension() then
        throw new IllegalArgumentException(
          s"there is no coordinate $c: this result has ${dimension()}, numbered from 0"
        )
      thetaArrays(c).indices.filter(i => !thetaArrays(c)(i).isNaN).map(i => i -> thetaArrays(c)(i)).toMap
    Plot.torus(theta(c1), theta(c2), s"Toroidal coordinates $c1 and $c2")

  /** The first two coordinates against each other: [[plot(c1:Int,c2:Int)*]] with `0` and `1`. */
  def plot(): Figure =
    if dimension() < 2 then
      throw new IllegalArgumentException(
        "one coordinate has no torus to draw: compute it with circularCoordinates and plot that"
      )
    plot(0, 1)
