package org.appliedtopology.tda4j

/** The Cech filtration value of a simplex: the radius of the smallest ball containing its vertices
  * ([[MinimumEnclosingBall]]). Values are cached, so a simplex has one value for the whole computation.
  */
object CechFiltration:

  /** The Cech filtration value (minimal enclosing radius) of a simplex, with its own cache. Each value is clamped to at
    * least its facets' values, so floating-point error cannot break monotonicity.
    */
  def apply(euclideanMetricSpace: EuclideanMetricSpace): PartialFunction[Simplex[Int], Double] =
    // FiltrationCache (a TrieMap on the JVM), not mutable.HashMap: RipserCofaceSimplexStream's parallelFiltrationValue pre-warm step calls
    // this PartialFunction's apply concurrently from multiple threads when enabled -- see
    // .claude/WORKLOG-parallelization-survey.md item 2. TrieMap's getOrElseUpdate is a genuine drop-in (same
    // signature) backed by a lock-free Ctrie, safe for concurrent reads and writes; computeRadius's own
    // facet-floor lookups (cache.getOrElse, a plain read) are likewise safe concurrently, since the parallel
    // phase never writes a NEW entry mid-computation -- only already-complete lower-dimension entries are
    // ever read while dimension d's own candidates are being computed.
    val cache = FiltrationCache.empty[Simplex[Int], Double]
    def computeRadius(spx: Simplex[Int]): Double =
      val vertices = spx.underlying.toArray
      val coords = vertices.map(euclideanMetricSpace.pts)
      val ball = MinimumEnclosingBall(coords)
      val raw = ball.radius
      def facet(i: Int) = (spx.underlying - vertices(i)).asSimplex
      val facetFloor = vertices.indices.map(i => cache.getOrElse(facet(i), 0.0)).max
      // A vertex strictly inside the ball is not on its boundary sphere, so the ball is also the smallest ball around
      // the facet without that vertex: reuse that facet's radius exactly, so their pair has length zero rather than a
      // few ULPs (the two radii are computed along different floating-point paths).
      val center = ball.center
      def distanceToCenter(i: Int) = math.sqrt(coords(i).indices.map(k => math.pow(coords(i)(k) - center(k), 2)).sum)
      val radius = vertices.indices
        .find(i => distanceToCenter(i) < raw * (1 - 1e-9))
        .flatMap(i => cache.get(facet(i)))
        .getOrElse(raw)
      math.max(radius, facetFloor)
    MemoizedFiltration(cache, spx => spx.forall(v => euclideanMetricSpace.contains(v)))(spx =>
      if spx.dim <= 0 then 0.0 else computeRadius(spx)
    )

/** The Cech complex, built like `RipserCofaceSimplexStream` builds Vietoris-Rips: dimension by dimension, each simplex
  * as a coface of an accepted simplex one dimension down. This finds every Cech simplex because the Cech complex is
  * closed under faces. (The table lookup of the New-VR construction relies on a flag complex and is not used.)
  *
  * `maxFiltrationValue` is a radius.
  */
open class CechCofaceSimplexStream(
  val euclideanMetricSpace: EuclideanMetricSpace,
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true },
  maxFiltrationValue: Option[Double] = None,
  // Threaded straight through to RipserCofaceSimplexStream's own parameter of the same name -- see its doc
  // there. Cech's own minimum-enclosing-ball radius computation is exactly the case this exists for: unlike a
  // cheap arithmetic filtration value, a real per-candidate ball solve is worth parallelizing once a
  // complex is large enough. See .claude/WORKLOG-parallelization-survey.md item 2.
  parallelFiltrationValue: Boolean = false
) extends RipserCofaceSimplexStream(
      euclideanMetricSpace,
      keepCriterion,
      maxFiltrationValue,
      Some(CechFiltration(euclideanMetricSpace)),
      parallelFiltrationValue
    )
