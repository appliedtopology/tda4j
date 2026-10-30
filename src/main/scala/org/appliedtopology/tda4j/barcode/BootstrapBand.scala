package org.appliedtopology.tda4j

/** A resampling confidence band for the bars of one degree, after Fasy, Lecci, Rinaldo, Wasserman, Balakrishnan and
  * Singh, "Confidence sets for persistence diagrams" (Annals of Statistics 42, 2014).
  *
  * Compute the diagram of the points, then of many resamples of them, and measure how far (in bottleneck distance) each
  * resample's diagram is from the original. The `1 - alpha` quantile of those distances is the band's `width`: a
  * resample moves the diagram by at most `width` in `1 - alpha` of the draws. A bar that persists longer than `2 *
  * width` cannot be matched to the diagonal by a perturbation of that size, so it is a feature that survives the
  * resampling; a bar shorter than that could be one of the noise. [[Band.threshold]] is `2 * width`, and
  * `diagram.longerThan(band.threshold)` keeps the bars beyond it.
  *
  * This is a resampling heuristic with a statistical reading, not a proof: the guarantee of the paper is for specific
  * estimators and sampling models. Resampling with replacement (`Resampling.Bootstrap`) follows the paper's recipe.
  * `Resampling.Subsample` draws fewer points without replacement and is cheaper; its distances are not rescaled to the
  * full sample, so its band is wider than the bootstrap's (the conservative direction). Essential bars are left out of
  * the comparison, so the band says nothing about them.
  */
object BootstrapBand:

  /** How the points are resampled. */
  enum Resampling:
    /** `n` draws with replacement from the `n` points. */
    case Bootstrap

    /** `size` points drawn without replacement. */
    case Subsample(size: Int)

  /** The band of one degree: `distances` are the bottleneck distances of the resamples to the original, in the order
    * drawn; `width` is their `1 - alpha` quantile.
    */
  final case class Band(width: Double, distances: IndexedSeq[Double], alpha: Double):
    /** The persistence above which a bar is a feature the resampling did not move to the diagonal: `2 * width`. */
    def threshold: Double = 2.0 * width

  /** The band for degree `dimension` of `points`, from `rounds` resamples drawn with `seed`. The diagrams are those of
    * `Persistence(sample, maxDimension = dimension, complex = complex, ...)`; the points of a bootstrap sample can
    * repeat, which the Vietoris-Rips and Cech complexes allow and an alpha complex does not.
    */
  def apply(
    points: PointCloud,
    dimension: Int = 1,
    rounds: Int = 100,
    alpha: Double = 0.05,
    resampling: Resampling = Resampling.Bootstrap,
    seed: Long = 0L,
    complex: PointCloudComplex = VietorisRips,
    maxFiltrationValue: Optional[Double] = Optional.empty,
    characteristic: Int = FiniteField.DefaultPrime
  ): Band =
    require(dimension >= 0, s"BootstrapBand: dimension must be >= 0, got $dimension")
    require(rounds >= 1, s"BootstrapBand: rounds must be at least 1, got $rounds")
    require(alpha > 0.0 && alpha < 1.0, s"BootstrapBand: alpha must be between 0 and 1, got $alpha")
    resampling match
      case Resampling.Subsample(size) =>
        require(
          size >= 2 && size <= points.size,
          s"BootstrapBand: a subsample takes between 2 and ${points.size} points, got $size"
        )
      case Resampling.Bootstrap => require(points.size >= 2, "BootstrapBand: needs at least 2 points")
    def finiteBars(cloud: PointCloud) =
      Persistence(
        cloud,
        maxDimension = dimension,
        maxFiltrationValue = maxFiltrationValue,
        complex = complex,
        characteristic = characteristic,
        representatives = Representatives.Cocycles,
        computeAllRepresentatives = false
      ).dim(dimension).bars.filterNot(_.death.isPosInfinity)
    val original = finiteBars(points)
    val random = new scala.util.Random(seed)
    val n = points.size
    val distances = IndexedSeq.fill(rounds) {
      val drawn = resampling match
        case Resampling.Bootstrap       => Array.fill(n)(random.nextInt(n))
        case Resampling.Subsample(size) => random.shuffle((0 until n).toList).take(size).toArray
      BarcodeDistance.bottleneckDistance(original, finiteBars(PointCloud(drawn.map(points.points))))
    }
    // The smallest distance that at least a (1 - alpha) share of the resamples do not exceed.
    val index = math.min(rounds - 1, math.max(0, math.ceil((1.0 - alpha) * rounds).toInt - 1))
    Band(distances.sorted.apply(index), distances, alpha)
end BootstrapBand
