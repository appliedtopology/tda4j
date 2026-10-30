package org.appliedtopology.tda4j

/** Sliding-window (Takens delay) embedding of a time series into a point cloud: the point at time `t` is the window
  * `(x_t, x_{t+delay}, ..., x_{t+(dimension-1) delay})`. A periodic signal traces a closed curve in the window space,
  * so `Persistence(TakensEmbedding(signal, dimension = 2, delay = quarterPeriod))` shows the period as a long bar of
  * degree 1; a quasi-periodic one traces a torus.
  *
  * The `dimension` and `delay` decide what the embedding shows: too small a delay flattens the curve onto a line, too
  * large a one folds it. [[delayByAutocorrelation]] gives a first guess for the delay.
  */
object TakensEmbedding:

  /** The windows of `series` of `dimension` samples `delay` apart, one for every `stride`-th start; the cloud has
    * `(series.length - (dimension - 1) * delay - 1) / stride + 1` points.
    */
  def apply(series: Seq[Double], dimension: Int, delay: Int = 1, stride: Int = 1): PointCloud =
    val x = series.toArray
    require(dimension >= 1, s"TakensEmbedding: dimension must be at least 1, got $dimension")
    require(delay >= 1, s"TakensEmbedding: delay must be at least 1, got $delay")
    require(stride >= 1, s"TakensEmbedding: stride must be at least 1, got $stride")
    val span = (dimension - 1) * delay
    require(
      x.length > span,
      s"TakensEmbedding: a window of $dimension samples $delay apart spans ${span + 1} samples, the series has ${x.length}"
    )
    val count = (x.length - span - 1) / stride + 1
    PointCloud(Array.tabulate(count)(t => Array.tabulate(dimension)(j => x(t * stride + j * delay))))

  /** The same for a series of vectors (one row per time step): each point is the `dimension` rows of a window, one
    * after the other, so it has `dimension * (row length)` coordinates.
    */
  def multivariate(series: Seq[Seq[Double]], dimension: Int, delay: Int = 1, stride: Int = 1): PointCloud =
    require(series.nonEmpty, "TakensEmbedding: the series is empty")
    val width = series.head.length
    require(series.forall(_.length == width), "TakensEmbedding: the rows of the series have different lengths")
    val rows = series.map(_.toArray).toArray
    val index = Seq.range(0, rows.length).map(_.toDouble)
    val windows = apply(index, dimension, delay, stride).points.map(_.head.toInt)
    PointCloud(windows.map(t => Array.tabulate(dimension)(j => rows(t + j * delay)).flatten))

  /** The first lag at which the autocorrelation of `series` is zero or negative (searching lags `1 to maxLag`; `maxLag`
    * itself when there is none): a rule of thumb for the delay. For a sinusoid it is a quarter of the period, the delay
    * that turns a two-dimensional window into a round circle. Fails on a constant series, which has no autocorrelation.
    */
  def delayByAutocorrelation(series: Seq[Double], maxLag: Int): Int =
    val x = series.toArray
    require(
      maxLag >= 1 && maxLag < x.length,
      s"TakensEmbedding: maxLag must be between 1 and ${x.length - 1}, got $maxLag"
    )
    val mean = x.sum / x.length
    val centred = x.map(_ - mean)
    val variance = centred.map(v => v * v).sum
    require(variance > 0.0, "TakensEmbedding: the series is constant, it has no autocorrelation")
    def autocorrelation(lag: Int): Double =
      var sum = 0.0
      var i = 0
      while i + lag < centred.length do
        sum += centred(i) * centred(i + lag)
        i += 1
      sum / variance
    (1 to maxLag).find(autocorrelation(_) <= 0.0).getOrElse(maxLag)
end TakensEmbedding
