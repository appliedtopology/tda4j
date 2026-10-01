package org.appliedtopology.tda4j
package barcode

/** Dropping short bars -- the "noise" end of a barcode -- relative to a scale the data itself provides.
  *
  * Every engine returns EVERY bar, which is what the cross-validation machinery needs but is overwhelming to read: a
  * point cloud of a few hundred points has thousands of bars, nearly all of them negligible next to the few that carry
  * the shape. This object is the opt-in, post-hoc filter (the same approach `ripser.py`/GUDHI users take; engines are
  * deliberately NOT filtered, so nothing here changes what an engine computes or any representative it records). The
  * `matlab.TDA4j` facade (hence the CLI and MATLAB) applies it by default with [[DefaultFraction]]; Scala callers using
  * an engine directly call it themselves.
  *
  * '''The scale.''' [[connectivityScale]] is the filtration range from the first birth to the value at which the data
  * becomes connected: (largest finite dimension-0 death) - (smallest dimension-0 birth). For a point cloud under
  * Vietoris-Rips that is exactly "from 0 to the connectivity radius" (the last merge of the minimum spanning tree).
  * When no finite dimension-0 bar exists (a single connected component from the start, e.g. a one-basin image) it falls
  * back to the full finite range (largest finite endpoint - smallest finite birth); with no finite endpoint at all it
  * is `0`. Like the barcode itself the scale is in whatever units the complex reports (diameters for Vietoris-Rips,
  * radii for Cech/alpha), so "1% of the scale" is 1% in those units.
  *
  * '''The rule.''' A bar is kept iff it never dies (essential bars are always kept) or its persistence is strictly
  * greater than the threshold. A threshold `<= 0` keeps EVERYTHING, including zero-persistence bars.
  */
object PersistenceFilter:

  /** The default relative threshold: a bar must extend beyond 1% of [[connectivityScale]] to be kept. */
  val DefaultFraction: Double = 0.01

  /** Persistence of a bar given as raw `birth`/`death` values; `+Infinity` for an essential (never-dying) class. */
  def persistence(birth: Double, death: Double): Double =
    if death.isPosInfinity then Double.PositiveInfinity else death - birth

  /** The scale described in the object doc, over bars given as parallel `dims`/`births`/`deaths` arrays. */
  def connectivityScale(dims: Array[Int], births: Array[Double], deaths: Array[Double]): Double =
    def finite(x: Double) = !x.isInfinite && !x.isNaN
    val idx = dims.indices
    val finiteH0Deaths = idx.filter(i => dims(i) == 0 && finite(deaths(i)) && finite(births(i))).map(deaths)
    val scale =
      if finiteH0Deaths.nonEmpty then
        val h0Births = idx.filter(i => dims(i) == 0 && finite(births(i))).map(births)
        finiteH0Deaths.max - h0Births.min
      else
        val endpoints = idx.flatMap(i => Seq(births(i), deaths(i))).filter(finite)
        if endpoints.isEmpty then 0.0 else endpoints.max - endpoints.min
    math.max(scale, 0.0)

  /** The threshold to apply: `minPersistence` if given (absolute, in the barcode's own units), otherwise `fraction *
    * connectivityScale`. Rejects a negative or non-finite value of either parameter.
    */
  def threshold(
    dims: Array[Int],
    births: Array[Double],
    deaths: Array[Double],
    minPersistence: Option[Double] = None,
    fraction: Double = DefaultFraction
  ): Double =
    require(fraction >= 0.0 && !fraction.isInfinite, s"persistence fraction must be a finite number >= 0, got $fraction")
    minPersistence match
      case Some(p) =>
        require(p >= 0.0 && !p.isInfinite, s"minimum persistence must be a finite number >= 0, got $p")
        p
      case None => fraction * connectivityScale(dims, births, deaths)

  /** Indices of the bars kept under `threshold` (see the object doc for the rule), in their original order. */
  def keptIndices(births: Array[Double], deaths: Array[Double], threshold: Double): Array[Int] =
    births.indices.filter(i => threshold <= 0.0 || deaths(i).isPosInfinity || persistence(births(i), deaths(i)) > threshold).toArray

  private def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case OpenEndpoint(v)    => v
    case ClosedEndpoint(v)  => v
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity

  /** [[connectivityScale]] for a list of bars. */
  def connectivityScale[A](bars: Iterable[PersistenceBar[Double, A]]): Double =
    val bs = bars.toArray
    connectivityScale(bs.map(_.dim), bs.map(b => endpointValue(b.lower)), bs.map(b => endpointValue(b.upper)))

  /** The bars worth reporting: all of `bars` except those with persistence at or below the threshold -- `minPersistence`
    * (absolute) if given, else `fraction` of the [[connectivityScale]]. Essential bars are always kept; a threshold of
    * `0` (`minPersistence = Some(0.0)` or `fraction = 0.0`) keeps everything. Order and annotations (representative
    * chains) are preserved.
    */
  def significant[A](
    bars: List[PersistenceBar[Double, A]],
    minPersistence: Option[Double] = None,
    fraction: Double = DefaultFraction
  ): List[PersistenceBar[Double, A]] =
    val bs = bars.toArray
    val dims = bs.map(_.dim)
    val births = bs.map(b => endpointValue(b.lower))
    val deaths = bs.map(b => endpointValue(b.upper))
    val kept = keptIndices(births, deaths, threshold(dims, births, deaths, minPersistence, fraction))
    kept.toList.map(bs)
