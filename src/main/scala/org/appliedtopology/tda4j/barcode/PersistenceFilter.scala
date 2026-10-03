package org.appliedtopology.tda4j

/** Dropping short bars -- the "noise" end of a barcode -- relative to the scale of the input.
  *
  * Every engine returns EVERY bar, which is what the cross-validation machinery needs but is overwhelming to read: a
  * point cloud of a few hundred points has thousands of bars, nearly all of them negligible next to the few that carry
  * the shape. This object is the opt-in, post-hoc filter (the same approach `ripser.py`/GUDHI users take; engines are
  * deliberately NOT filtered, so nothing here changes what an engine computes or any representative it records). The
  * `matlab.TDA4j` facade (hence the CLI and MATLAB) applies it by default with [[DefaultFraction]]; Scala callers using
  * an engine directly call it themselves.
  *
  * '''The scale.''' A bar is judged against a scale the caller supplies: for a point cloud or distance matrix,
  * `metricSpace.minimumEnclosingRadius` (Ripser's enclosing radius -- beyond it the Vietoris-Rips complex is a cone and
  * nothing new is born, so every bar lives in `[0, scale]`; it is also this library's default Vietoris-Rips
  * truncation); for a cubical image, the range of the pixel values. It is used as-is in the units the complex reports
  * (diameters for Vietoris-Rips, radii for Cech/alpha), so "1% of the scale" is 1% in those units. A caller with no
  * such quantity at hand can use [[filtrationRange]], the span of the barcode's own finite endpoints.
  *
  * '''The rule.''' A bar is kept iff it never dies (essential bars are always kept) or its persistence is strictly
  * greater than the threshold. A threshold `<= 0` keeps EVERYTHING, including zero-persistence bars.
  */
object PersistenceFilter:

  /** The default relative threshold: a bar must extend beyond 1% of the scale to be kept. */
  val DefaultFraction: Double = 0.01

  /** Persistence of a bar given as raw `birth`/`death` values; `+Infinity` for an essential (never-dying) class. */
  def persistence(birth: Double, death: Double): Double =
    if death.isPosInfinity then Double.PositiveInfinity else death - birth

  private def finite(x: Double): Boolean = !x.isInfinite && !x.isNaN

  /** The span of a barcode's own finite endpoints: (largest finite endpoint) - (smallest finite birth), `0` if there is
    * none. The fallback scale when nothing about the input itself is available (or the supplied scale is not finite).
    */
  def filtrationRange(births: Array[Double], deaths: Array[Double]): Double =
    val finiteBirths = births.filter(finite)
    val endpoints = (births ++ deaths).filter(finite)
    if finiteBirths.isEmpty || endpoints.isEmpty then 0.0 else math.max(endpoints.max - finiteBirths.min, 0.0)

  /** The threshold to apply: `minPersistence` if given (absolute, in the barcode's own units), otherwise `fraction *
    * scale`. `scale` is by-name and only evaluated when a fraction of it is actually needed (it can be expensive -- a
    * minimum enclosing radius is quadratic -- and is pointless for an absolute threshold or `fraction = 0`); if it
    * turns out not to be finite (a disconnected or infinite-distance input) `fallbackScale` is used instead. Rejects a
    * negative or non-finite `minPersistence`/`fraction`.
    */
  def threshold(
    minPersistence: Option[Double],
    fraction: Double,
    scale: => Double,
    fallbackScale: => Double
  ): Double =
    require(
      fraction >= 0.0 && !fraction.isInfinite,
      s"persistence fraction must be a finite number >= 0, got $fraction"
    )
    minPersistence match
      case Some(p) =>
        require(p >= 0.0 && !p.isInfinite, s"minimum persistence must be a finite number >= 0, got $p")
        p
      case None if fraction == 0.0 => 0.0
      case None                    =>
        val s = scale
        fraction * math.max(if finite(s) then s else fallbackScale, 0.0)

  /** Indices of the bars kept under `threshold` (see the object doc for the rule), in their original order. */
  def keptIndices(births: Array[Double], deaths: Array[Double], threshold: Double): Array[Int] =
    births.indices
      .filter(i => threshold <= 0.0 || deaths(i).isPosInfinity || persistence(births(i), deaths(i)) > threshold)
      .toArray

  private def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case OpenEndpoint(v)    => v
    case ClosedEndpoint(v)  => v
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity

  /** [[filtrationRange]] for a list of bars. */
  def filtrationRange[A](bars: Iterable[PersistenceBar[Double, A]]): Double =
    val bs = bars.toArray
    filtrationRange(bs.map(b => endpointValue(b.lower)), bs.map(b => endpointValue(b.upper)))

  /** The bars worth reporting: all of `bars` except those with persistence at or below the threshold --
    * `minPersistence` (absolute) if given, else `fraction` of `scale` (default: the bars' own [[filtrationRange]]; pass
    * `Some(metricSpace.minimumEnclosingRadius)` for the standard scale). Essential bars are always kept; a threshold of
    * `0` (`minPersistence = Some(0.0)` or `fraction = 0.0`) keeps everything. Order and annotations (representative
    * chains) are preserved.
    */
  def significant[A](
    bars: List[PersistenceBar[Double, A]],
    minPersistence: Option[Double] = None,
    fraction: Double = DefaultFraction,
    scale: Option[Double] = None
  ): List[PersistenceBar[Double, A]] =
    val bs = bars.toArray
    val births = bs.map(b => endpointValue(b.lower))
    val deaths = bs.map(b => endpointValue(b.upper))
    val fallback = filtrationRange(births, deaths)
    val kept = keptIndices(births, deaths, threshold(minPersistence, fraction, scale.getOrElse(fallback), fallback))
    kept.toList.map(bs)
