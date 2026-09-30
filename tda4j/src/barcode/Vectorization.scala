package org.appliedtopology.tda4j
package barcode

import org.apache.commons.math3.special.Erf

/** Diagram vectorizations: persistence landscapes (Bubenik 2013) and persistence images (Adams et al. 2017), turning a
  * barcode into a fixed-size numeric array for downstream (e.g. ML) use. Per
  * `.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 8 -- pure array/geometry code on [[PersistenceBar]], no
  * relation to [[BarcodeDistance]]'s matching machinery beyond sharing [[DiagramPoint]]'s extraction helper.
  *
  * '''Essential (never-dying) bars''' are handled differently by the two vectorizations below, and deliberately so
  * rather than by one blanket policy -- each is documented at its own `def`:
  *   - [[landscape]] includes them: a tent function `max(0, min(t - birth, death - t))` degrades to the unbounded ramp
  *     `t - birth` exactly at `death = Infinity`, which is already finite and meaningful at every `t` the caller's own
  *     finite grid ever evaluates, so no special-casing is needed.
  *   - [[persistenceImage]] drops them: a Gaussian centered at `(birth, Infinity)` in birth-persistence coordinates has
  *     no overlap with any finite pixel grid, so silently keeping it would either underflow to an all-zero contribution
  *     (an implicit policy, not a decided one) or need an arbitrary finite substitute death value (which
  *     [[landscape]]'s case doesn't need and this one has no principled way to choose either).
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
  def landscape[A](
    diagram: Seq[PersistenceBar[Double, A]],
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

  /** The persistence image (Adams et al. 2017) of `diagram`: diagram points are first transformed to birth-persistence
    * coordinates `(birth, death - birth)` (the paper's own `T`), then each becomes an isotropic Gaussian bump of
    * standard deviation `sigma`, weighted by [[piecewiseLinearWeight]] evaluated at that point's own persistence value
    * (`weightCap` defaults to the diagram's own maximum finite persistence, the paper's suggested default). Each
    * pixel's value is the *exact* integral of the weighted surface over that pixel's box -- via the product of 1D
    * normal-CDF differences along each axis, since an isotropic Gaussian's mass over a rectangle factors along the two
    * axes -- not a point sample of the surface at the pixel center, cross-checked against `scikit-tda/persim`'s own
    * CDF-difference implementation.
    *
    * Returns `image(r)(c)`: `r` indexes `birthResolution` pixels evenly spanning `birthRange`, `c` indexes
    * `persistenceResolution` pixels evenly spanning `persistenceRange`.
    *
    * '''Essential (never-dying) bars are dropped''' -- see the class doc for why, unlike [[landscape]].
    */
  def persistenceImage[A](
    diagram: Seq[PersistenceBar[Double, A]],
    sigma: Double,
    birthRange: (Double, Double),
    persistenceRange: (Double, Double),
    birthResolution: Int,
    persistenceResolution: Int,
    weightCap: Option[Double] = None
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
    val cap = weightCap.getOrElse(points.map(_.persistence).maxOption.getOrElse(0.0))

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
