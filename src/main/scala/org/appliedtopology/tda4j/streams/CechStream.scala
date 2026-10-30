package org.appliedtopology.tda4j

/** The Cech filtration value of a simplex: the radius of the smallest ball containing its vertices
  * ([[MinimumEnclosingBall]]). Values are cached, so a simplex has one value for the whole computation.
  */
object CechFiltration:

  /** The Cech filtration value (minimal enclosing radius) of a simplex, with its own cache. Each value is clamped to at
    * least its facets' values, so floating-point error cannot break monotonicity.
    */
  def apply(euclideanMetricSpace: EuclideanMetricSpace): PartialFunction[Simplex[Int], Double] =
    Values(euclideanMetricSpace)

  /** The Cech value of a simplex, as a function of [[Simplex]] values and of the vertices in an array (`value`), which
    * share one cache: a stream building its dimensions on arrays asks for no `Simplex` and finds the same values. Safe
    * to call from several threads at once.
    *
    * The value of a simplex depends on which of its facets are cached when it is first asked for (see `computeRadius`),
    * so the order of the calls matters, as it always has: a stream asks dimension by dimension.
    */
  final class Values(euclideanMetricSpace: EuclideanMetricSpace) extends PartialFunction[Simplex[Int], Double]:
    // A table keyed by the vertices (`VertexSetValues`), safe to fill from several threads: RipserCofaceSimplexStream's
    // parallelFiltrationValue pre-warm step calls this function concurrently when enabled -- see
    // .claude/WORKLOG-parallelization-survey.md item 2. computeRadius's own facet lookups are plain reads of
    // already-complete lower-dimension entries, since the parallel phase never writes a NEW entry of a lower dimension.
    private val cache = new VertexSetValues

    def isDefinedAt(spx: Simplex[Int]): Boolean = spx.forall(v => euclideanMetricSpace.contains(v))

    def apply(spx: Simplex[Int]): Double =
      val vertices = new Array[Int](spx.size)
      var i = 0
      spx.foreach { v =>
        vertices(i) = v
        i += 1
      }
      value(vertices, 0, vertices.length, new Scratch)

    override def applyOrElse[A1 <: Simplex[Int], B1 >: Double](spx: A1, default: A1 => B1): B1 =
      if isDefinedAt(spx) then apply(spx) else default(spx)

    /** The value of the simplex with the ascending vertices `vertices(from until from + width)`. */
    def value(vertices: Array[Int], from: Int, width: Int): Double = value(vertices, from, width, new Scratch)

    /** The same as a [[RipserCofaceSimplexStream.FlatFiltration]], whose runs of candidates each keep their buffers. */
    def flat: RipserCofaceSimplexStream.FlatFiltration =
      new RipserCofaceSimplexStream.FlatFiltration:
        def value(vertices: Array[Int], from: Int, width: Int, parentValue: Double): Double =
          Values.this.value(vertices, from, width)
        override def perRun: RipserCofaceSimplexStream.FlatFiltration = new Run

    private final class Run extends RipserCofaceSimplexStream.FlatFiltration:
      private val scratch = new Scratch
      def value(vertices: Array[Int], from: Int, width: Int, parentValue: Double): Double =
        Values.this.value(vertices, from, width, scratch)

    // The buffers of one call: the vertices' points and a facet's vertices.
    private final class Scratch:
      var points: Array[Array[Double]] = new Array[Array[Double]](0)
      var facet: Array[Int] = new Array[Int](0)

    private def value(vertices: Array[Int], from: Int, width: Int, scratch: Scratch): Double =
      val cached = cache.get(vertices, from, width)
      if !cached.isNaN then cached
      else
        cache.putIfAbsent(
          vertices,
          from,
          width,
          if width <= 1 then 0.0 else computeRadius(vertices, from, width, scratch)
        )

    private def computeRadius(vertices: Array[Int], from: Int, width: Int, scratch: Scratch): Double =
      if scratch.points.length != width then
        scratch.points = new Array[Array[Double]](width)
        scratch.facet = new Array[Int](width - 1)
      val coords = scratch.points
      var i = 0
      while i < width do
        coords(i) = euclideanMetricSpace.pointAt(vertices(from + i))
        i += 1
      val ball = MinimumEnclosingBall(coords)
      val raw = ball.radius
      val center = ball.center
      val facet = scratch.facet
      // A vertex strictly inside the ball is not on its boundary sphere, so the ball is also the smallest ball around
      // the facet without that vertex: reuse that facet's radius exactly, so their pair has length zero rather than a
      // few ULPs (the two radii are computed along different floating-point paths). The first such vertex decides; a
      // facet that is not cached leaves the ball's own radius.
      var facetFloor = 0.0
      var reused = Double.NaN
      var foundInside = false
      i = 0
      while i < width do
        System.arraycopy(vertices, from, facet, 0, i)
        System.arraycopy(vertices, from + i + 1, facet, i, width - 1 - i)
        val facetValue = cache.get(facet, 0, width - 1)
        if !facetValue.isNaN && facetValue > facetFloor then facetFloor = facetValue
        if !foundInside && distanceToCenter(coords(i), center) < raw * (1 - 1e-9) then
          foundInside = true
          reused = facetValue
        i += 1
      math.max(if reused.isNaN then raw else reused, facetFloor)

    private def distanceToCenter(point: Array[Double], center: Array[Double]): Double =
      var sum = 0.0
      var k = 0
      while k < point.length do
        sum += math.pow(point(k) - center(k), 2)
        k += 1
      math.sqrt(sum)

/** The Cech complex, built like `RipserCofaceSimplexStream` builds Vietoris-Rips: dimension by dimension, each simplex
  * as a coface of an accepted simplex one dimension down. This finds every Cech simplex because the Cech complex is
  * closed under faces. (The table lookup of the New-VR construction relies on a flag complex and is not used.)
  *
  * `maxFiltrationValue` is a radius.
  */
open class CechCofaceSimplexStream(
  val euclideanMetricSpace: EuclideanMetricSpace,
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = CofaceSimplexStream.keepAll,
  maxFiltrationValue: Option[Double] = None,
  // Threaded straight through to RipserCofaceSimplexStream's own parameter of the same name -- see its doc
  // there. Cech's own minimum-enclosing-ball radius computation is exactly the case this exists for: unlike a
  // cheap arithmetic filtration value, a real per-candidate ball solve is worth parallelizing once a
  // complex is large enough. See .claude/WORKLOG-parallelization-survey.md item 2.
  parallelFiltrationValue: Boolean = Parallel.byDefault
) extends RipserCofaceSimplexStream(
      euclideanMetricSpace,
      keepCriterion,
      maxFiltrationValue,
      Some(CechFiltration(euclideanMetricSpace)),
      parallelFiltrationValue
    ):
  // The radius is asked of the vertices in an array, from the same cache the `Simplex` function reads (unless a subclass
  // replaced `filtrationValue`: then each candidate is a `Simplex` for that function).
  override protected def flatFiltration: Option[RipserCofaceSimplexStream.FlatFiltration] =
    filtrationValue match
      case values: CechFiltration.Values => Some(values.flat)
      case other                         => Some(RipserCofaceSimplexStream.FlatFiltration.ofSimplices(other))
