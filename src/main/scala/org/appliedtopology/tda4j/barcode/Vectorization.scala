package org.appliedtopology.tda4j

/** Fixed-size arrays of numbers from a barcode, for statistics and machine learning: persistence landscapes (Bubenik
  * 2013), persistence images (Adams et al. 2017), Betti curves, persistence silhouettes (Chazal et al. 2014) and
  * persistence entropy (Rucco et al. 2016).
  *
  * Essential bars count in [[landscape]] (their tent is the ramp `t - birth`, finite on any grid) and in [[bettiCurve]]
  * (alive from their birth on); [[persistenceImage]], [[silhouette]] and [[persistenceEntropy]] drop them (a Gaussian,
  * a weight or a share of the total length at infinite persistence is not a number).
  */
object Vectorization:

  /** Samples the first `numLevels` persistence landscape functions of `diagram` (Bubenik 2013) on `resolution`
    * evenly-spaced points across `[tMin, tMax]` (inclusive of both ends). Returns `levels(k)(j)`, `k` from `0` (the
    * outer envelope, i.e. the pointwise max over all bars' tent functions) to `numLevels - 1`, `j` from `0` to
    * `resolution - 1` at `t_j = tMin + j * (tMax - tMin) / (resolution - 1)`. A `t` with fewer than `numLevels` bars
    * alive gets `0` for the missing levels, the standard "k-th order statistic of a smaller set is 0" convention.
    *
    * '''Closed-form check''' (used by the test suite as an exact oracle, not just eyeballing plots): integrating every
    * level and summing gives `sum_i (death_i - birth_i)^2 / 4` exactly, for a `[tMin, tMax]` containing every finite
    * bar's support -- each bar's tent function has triangular area `persistence * (persistence/2) / 2`, and
    * `sum_k integral(level_k) = sum_i integral(tent_i)` because integration is linear and, at each fixed t, the
    * multiset of order statistics `{level_k(t)}` is exactly the multiset `{tent_i(t)}` reordered.
    */
  def landscape(
    diagram: Seq[PersistenceBar[Double, ?]],
    numLevels: Int,
    tMin: Double,
    tMax: Double,
    resolution: Int
  ): Array[Array[Double]] =
    require(numLevels > 0, s"landscape requires numLevels > 0, got $numLevels")
    require(resolution >= 2, s"landscape requires resolution >= 2, got $resolution")
    require(tMax > tMin, s"landscape requires tMax > tMin, got tMin=$tMin, tMax=$tMax")
    val points = diagram.map(DiagramPoint.of)
    val ts = Array.tabulate(resolution)(j => tMin + j * (tMax - tMin) / (resolution - 1))
    val levels = Array.ofDim[Double](numLevels, resolution)
    for j <- 0 until resolution do
      val t = ts(j)
      val tents =
        points.map(p => math.max(0.0, math.min(t - p.birth, p.death - t))).sorted(using Ordering[Double].reverse)
      for k <- 0 until numLevels do levels(k)(j) = if k < tents.length then tents(k) else 0.0
    levels

  private def normalCdf(x: Double): Double =
    if x.isNegInfinity then 0.0
    else if x.isPosInfinity then 1.0
    else 0.5 * (1.0 + Erf.erf(x / math.sqrt(2.0)))

  /** The piecewise-linear weighting function Adams et al. 2017 use in their own experiments: `0` below persistence `0`,
    * ramping linearly to `1` at persistence `cap`, capped at `1` beyond that -- cross-checked against
    * `scikit-tda/persim`'s implementation (`pw_linear`), not recalled from memory. `cap <= 0.0` (every bar had
    * persistence `<= 0`, or the diagram is empty and a cap couldn't be derived from it) treats any positive persistence
    * as fully weighted, so a caller-supplied `weightCap` of `0.0` isn't silently a divide-by-zero.
    */
  private def piecewiseLinearWeight(persistence: Double, cap: Double): Double =
    if cap <= 0.0 then if persistence > 0.0 then 1.0 else 0.0
    else if persistence <= 0.0 then 0.0
    else if persistence >= cap then 1.0
    else persistence / cap

  /** The persistence image (Adams et al. 2017) of `diagram`. Each finite bar becomes the point
    * `(birth, death - birth)`, then a Gaussian of standard deviation `sigma` weighted by [[piecewiseLinearWeight]] of
    * its persistence (`weightCap` defaults to the largest finite persistence). Each pixel holds the exact integral of
    * the surface over the pixel, as in persim.
    *
    * Returns `image(r)(c)`: `r` indexes `birthResolution` pixels spanning `birthRange`, `c` indexes
    * `persistenceResolution` pixels spanning `persistenceRange`. Essential bars are dropped.
    */
  def persistenceImage(
    diagram: Seq[PersistenceBar[Double, ?]],
    sigma: Double,
    birthRange: (Double, Double),
    persistenceRange: (Double, Double),
    birthResolution: Int,
    persistenceResolution: Int,
    weightCap: Optional[Double] = Optional.empty
  ): Array[Array[Double]] =
    require(sigma > 0.0, s"persistenceImage requires sigma > 0.0, got $sigma")
    require(birthResolution > 0, s"persistenceImage requires birthResolution > 0, got $birthResolution")
    require(
      persistenceResolution > 0,
      s"persistenceImage requires persistenceResolution > 0, got $persistenceResolution"
    )
    val (bLo, bHi) = birthRange
    val (pLo, pHi) = persistenceRange
    require(bHi > bLo, s"persistenceImage requires birthRange._2 > birthRange._1, got $birthRange")
    require(pHi > pLo, s"persistenceImage requires persistenceRange._2 > persistenceRange._1, got $persistenceRange")

    val points = diagram.map(DiagramPoint.of).filterNot(DiagramPoint.isEssential)
    val cap = weightCap.toOption.getOrElse(points.map(_.persistence).maxOption.getOrElse(0.0))

    val bEdges = Array.tabulate(birthResolution + 1)(i => bLo + i * (bHi - bLo) / birthResolution)
    val pEdges = Array.tabulate(persistenceResolution + 1)(i => pLo + i * (pHi - pLo) / persistenceResolution)

    val image = Array.ofDim[Double](birthResolution, persistenceResolution)
    for pt <- points do
      val w = piecewiseLinearWeight(pt.persistence, cap)
      if w != 0.0 then
        val bCdf = bEdges.map(e => normalCdf((e - pt.birth) / sigma))
        val pCdf = pEdges.map(e => normalCdf((e - pt.persistence) / sigma))
        for r <- 0 until birthResolution do
          val bMass = bCdf(r + 1) - bCdf(r)
          if bMass != 0.0 then
            for c <- 0 until persistenceResolution do image(r)(c) += w * bMass * (pCdf(c + 1) - pCdf(c))
    image

  /** The Betti curve of `diagram`: the number of bars alive at each of `resolution` evenly-spaced points of `[tMin,
    * tMax]` (both ends included). A bar `[birth, death)` is alive at `t` when `birth <= t < death`, so a bar that ends
    * exactly at a sample is already gone there; an essential bar is alive from its birth on. The curve of one degree is
    * `Vectorization.bettiCurve(diagram.dim(k).bars, ...)`; of a whole diagram, the total number of alive bars.
    */
  def bettiCurve(
    diagram: Seq[PersistenceBar[Double, ?]],
    tMin: Double,
    tMax: Double,
    resolution: Int
  ): Array[Int] =
    require(resolution >= 2, s"bettiCurve requires resolution >= 2, got $resolution")
    require(tMax > tMin, s"bettiCurve requires tMax > tMin, got tMin=$tMin, tMax=$tMax")
    val points = diagram.map(DiagramPoint.of)
    Array.tabulate(resolution) { j =>
      val t = tMin + j * (tMax - tMin) / (resolution - 1)
      points.count(p => p.birth <= t && t < p.death)
    }

  /** The persistence silhouette of `diagram` (Chazal, Fasy, Lecci, Rinaldo, Wasserman 2014): at each of `resolution`
    * evenly-spaced points of `[tMin, tMax]`, the average of the bars' tent functions (the ones [[landscape]] takes the
    * order statistics of), each weighted by its persistence to the power `power`. `power = 1.0` (the default) weights
    * long bars more than short ones; a small `power` approaches the plain average of the tents, a large one the outline
    * of the longest bar. Essential bars are dropped. An empty diagram has the zero silhouette.
    */
  def silhouette(
    diagram: Seq[PersistenceBar[Double, ?]],
    tMin: Double,
    tMax: Double,
    resolution: Int,
    power: Double = 1.0
  ): Array[Double] =
    require(resolution >= 2, s"silhouette requires resolution >= 2, got $resolution")
    require(tMax > tMin, s"silhouette requires tMax > tMin, got tMin=$tMin, tMax=$tMax")
    require(power > 0.0 && power.isFinite, s"silhouette requires a finite power > 0, got $power")
    val points = diagram.map(DiagramPoint.of).filterNot(DiagramPoint.isEssential).filter(_.persistence > 0.0)
    val weights = points.map(p => math.pow(p.persistence, power))
    val total = weights.sum
    Array.tabulate(resolution) { j =>
      val t = tMin + j * (tMax - tMin) / (resolution - 1)
      if total == 0.0 then 0.0
      else
        points
          .zip(weights)
          .map((p, w) => w * math.max(0.0, math.min(t - p.birth, p.death - t)))
          .sum / total
    }

  /** The persistence entropy of `diagram` (Rucco, Castiglione, Merelli, Pettini 2016): with `l_i` the persistence of
    * finite bar `i` and `p_i = l_i / sum l`, the Shannon entropy `-sum p_i ln p_i`. It is `0` for one bar and `ln n`
    * for `n` bars of equal length, so a diagram dominated by a few long bars scores low and one made of many bars of
    * similar length scores high. With `normalized`, divided by `ln n` (the largest value for `n` bars), so it lies in
    * `[0, 1]`; a diagram with fewer than two finite bars of positive length has entropy `0`. Essential bars are
    * dropped.
    */
  def persistenceEntropy(diagram: Seq[PersistenceBar[Double, ?]], normalized: Boolean = false): Double =
    val lengths = diagram.map(DiagramPoint.of).filterNot(DiagramPoint.isEssential).map(_.persistence).filter(_ > 0.0)
    val total = lengths.sum
    if lengths.size < 2 then 0.0
    else
      val entropy = -lengths.map(l => (l / total) * math.log(l / total)).sum
      // Rounding can leave the quotient a few ulps outside [0, 1].
      if normalized then math.min(1.0, math.max(0.0, entropy / math.log(lengths.size.toDouble)))
      else math.max(0.0, entropy)
