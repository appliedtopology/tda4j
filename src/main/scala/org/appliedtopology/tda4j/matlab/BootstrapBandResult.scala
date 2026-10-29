package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.BootstrapBand

/** The resampling band of one degree (`TDA4j.bootstrapBand`): see `BootstrapBand`. A bar that persists longer than
  * `threshold()` is a feature the resampling did not move onto the diagonal.
  */
final class BootstrapBandResult private[matlab] (private val band: BootstrapBand.Band):
  /** The `1 - alpha` quantile of the bottleneck distances from the resamples' diagrams to the original diagram. */
  def width(): Double = band.width

  /** Twice the width: the persistence above which a bar survives the resampling. */
  def threshold(): Double = band.threshold

  /** The `alpha` the band was made for. */
  def alpha(): Double = band.alpha

  /** The bottleneck distance of each resample's diagram to the original, in the order drawn. */
  def distances(): Array[Double] = band.distances.toArray
