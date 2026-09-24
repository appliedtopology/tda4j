package org.appliedtopology.tda4j.matlab

/** A landmark selection, in a shape callable directly from MATLAB (or any other plain-Java caller): every public
  * method here takes/returns only `int[]` or `double` -- see `TDA4j.selectLandmarksFromPoints`/
  * `selectLandmarksFromDistanceMatrix` (step 1 of the two-step witness-complex recipe) for how this gets
  * constructed, and `TDA4j.computeFromPointsAndLandmarks`/`computeFromDistanceMatrixAndLandmarks` (step 2) for
  * where `landmarks()` goes next.
  */
final class LandmarkSelectionResult private[matlab] (
  private val landmarkIndices: Array[Int],
  private val radius: Double
):
  /** Ambient 0-based indices into the point cloud/distance matrix `TDA4j.selectLandmarksFrom*` was called with --
    * pass this straight into `computeFrom*AndLandmarks` (or `coveringRadiusFrom*`) to continue the two-step recipe
    * with the SAME landmarks, rather than letting a one-shot call re-select a possibly different set.
    */
  def landmarks(): Array[Int] = landmarkIndices

  /** `R = max_x min_l d(x,l)` over the chosen landmarks -- the JavaPlex tutorial's own quantity for picking a
    * `maxFiltrationValue` (e.g. `2R`) before computing step 2. `TDA4j.coveringRadiusFromPoints`/
    * `coveringRadiusFromDistanceMatrix` compute the same quantity for a landmark set NOT obtained from this class
    * (e.g. hand-picked).
    */
  def coveringRadius(): Double = radius
