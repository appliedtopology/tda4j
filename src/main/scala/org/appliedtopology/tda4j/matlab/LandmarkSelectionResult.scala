package org.appliedtopology.tda4j.matlab

/** Landmarks chosen by `TDA4j.selectLandmarksFromPoints` or `selectLandmarksFromDistanceMatrix`, step 1 of the two-step
  * witness complex.
  */
final class LandmarkSelectionResult private[matlab] (
  private val landmarkIndices: Array[Int],
  private val radius: Double
):
  /** The landmarks, as 0-based row numbers of the input: pass them to `computeFromPointsAndLandmarks` (or
    * `computeFromDistanceMatrixAndLandmarks`) for step 2.
    */
  def landmarks(): Array[Int] = landmarkIndices

  /** The covering radius `R = max over x of min over landmarks l of d(x, l)`; a common threshold for step 2 is `2R`. */
  def coveringRadius(): Double = radius
