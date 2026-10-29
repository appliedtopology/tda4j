package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.EulerCharacteristicCurve

/** The Euler characteristic curve of a point cloud or a distance matrix (`TDA4j.eulerCurveFromPoints`,
  * `eulerCurveFromDistanceMatrix`): the number of vertices minus edges plus triangles, ... of the Vietoris-Rips complex
  * at each scale. A step function, so it keeps the scales where it steps and the value from there on.
  */
final class EulerCurveResult private[matlab] (private val curve: EulerCharacteristicCurve):
  /** The scales where the curve changes, strictly increasing; the first is `0`, where the vertices appear. */
  def scales(): Array[Double] = curve.scales.toArray

  /** `values()(i)` is the Euler characteristic from `scales()(i)` up to (not including) `scales()(i + 1)`. */
  def values(): Array[Long] = curve.values.toArray

  /** The Euler characteristic at scale `t`; `0` below the first scale. */
  def at(t: Double): Long = curve(t)

  /** The curve at `resolution` evenly spaced points across `[tMin, tMax]`, both ends included (as `landscape` is
    * sampled).
    */
  def sample(tMin: Double, tMax: Double, resolution: Int): Array[Long] = curve.sample(tMin, tMax, resolution)
