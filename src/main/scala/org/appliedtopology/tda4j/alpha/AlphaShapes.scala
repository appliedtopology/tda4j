package org.appliedtopology.tda4j

import collection.mutable
import scala.math.{pow, sqrt}
import scala.util.Random
import scala.util.chaining.*

/** An absolute numerical tolerance of the triangulation's geometric tests (near-zero singular values and circumsphere
  * margins), in the units of the coordinates. The triangulations ([[HelixDelaunay]], [[BowyerWatsonDelaunay]]) take an
  * [[EpsilonFactor]] instead and derive this from the extent of their points, so that a barcode does not depend on the
  * units of the data.
  */
final case class Epsilon(epsilon: Double)

/** The tolerance of the triangulation's geometric tests as a fraction of the extent of the point set (its largest
  * coordinate range), so the same cloud in metres or in micrometres gives the same complex. `EpsilonFactor(1e-5)` is
  * the default, found without an import; pass a `given EpsilonFactor` to change it. The tolerance is `factor * extent`
  * (an extent of 0, a single point, counts as 1).
  */
final case class EpsilonFactor(factor: Double):
  require(factor > 0.0 && factor.isFinite, s"EpsilonFactor must be a positive number, got $factor")

  /** The absolute [[Epsilon]] for `points`. */
  def forPoints(points: Array[Array[Double]]): Epsilon =
    var extent = 0.0
    if points.nonEmpty then
      var k = 0
      while k < points.head.length do
        var lo = Double.PositiveInfinity
        var hi = Double.NegativeInfinity
        for p <- points do
          lo = math.min(lo, p(k))
          hi = math.max(hi, p(k))
        extent = math.max(extent, hi - lo)
        k += 1
    Epsilon(factor * (if extent > 0.0 && extent.isFinite then extent else 1.0))

object EpsilonFactor:
  given default: EpsilonFactor = EpsilonFactor(1e-5)

/** Which alpha-complex construction `AlphaShapes(points, backend)` uses: `BowyerWatson` (incremental Delaunay with
  * exact predicates, points spanning at most 4 dimensions), `Helix` (Delaunay by the Helix algorithm, any dimension),
  * `DQP` (dual quadratic programs, any dimension, built only up to a radius), or `Default` (whichever the library
  * expects to be fastest: see `AlphaShapes.apply`).
  */
enum AlphaBackend:
  case Default, Helix, DQP, BowyerWatson

object AlphaBackend:
  /** The facade's string spelling (`"default"`, `"helix"`, `"dqp"`, `"bowyer-watson"`, any case). */
  def parse(name: String): AlphaBackend = name.toLowerCase match
    case "default"                               => Default
    case "helix"                                 => Helix
    case "dqp"                                   => DQP
    case "bowyer-watson" | "bowyerwatson" | "bw" => BowyerWatson
    case other                                   =>
      throw IllegalArgumentException(
        s"Unknown alpha complex backend: '$other' (expected default, bowyer-watson, helix or DQP)"
      )

abstract class AlphaShapes extends LevelwiseSimplexStream[Int, Double]() with DoubleFiltration[Simplex[Int]]():
  val metricSpace: FiniteMetricSpace[Int]

/** Alpha complexes of point clouds: `AlphaShapes(points)`, or `Persistence(points, complex = AlphaShapes)`. */
object AlphaShapes extends PointCloudComplex:
  def fromPoints(points: PointCloud, maxDimension: Int, maxFiltrationValue: Option[Double]) =
    Truncated(apply(points, maxRadius = maxFiltrationValue, maxDimension = maxDimension), maxDimension)

  /** The alpha complex of `points`, built by `backend` (see [[AlphaBackend]]).
    *
    *   - `BowyerWatson` triangulates the whole point set (Delaunay, exact predicates: valid on grids and other
    *     degenerate input, and the same whatever the point order) and reads the alpha values off the triangulation.
    *     Points spanning at most 4 dimensions only; the fastest in 2-D and 3-D by far.
    *   - `Helix` triangulates the whole point set too, in any dimension, with floating-point predicates.
    *   - `DQP` decides each simplex on its own, dimension by dimension, among points within `2 maxRadius` of each
    *     other: it never builds the whole triangulation, so a small `maxRadius` (or a low `maxDimension` in high
    *     ambient dimension) makes it much cheaper, while without a radius it is far slower than a triangulation.
    *   - `Default` picks: without a `maxRadius`, BowyerWatson for points in at most 4 dimensions and Helix above; with
    *     one, DQP instead when it is expected to be faster for this radius, from the average number of points within
    *     `2 maxRadius` of a point (`AlphaShapes.prefersDQP`).
    *
    * In general position every backend gives the same complex: the simplices whose alpha value (radius) is at most
    * `maxRadius`, with the same values. On cospherical points DQP keeps the higher-dimensional simplex they span where
    * BowyerWatson and Helix triangulate it (each its own way); the barcode is the same up to zero-length bars.
    *
    * @param maxRadius
    *   keep only simplices with alpha value (radius) at most this. Default: no limit.
    * @param maxDimension
    *   the highest homological degree needed: `DQP` builds simplices up to dimension `maxDimension + 1` only. Default:
    *   all dimensions. (BowyerWatson and Helix always build every dimension; truncate the stream with [[Truncated]].)
    * @param requireValidTriangulation
    *   `Helix` only: raise an error rather than return a triangulation it could not repair (see [[HelixDelaunay]]).
    */
  def apply(
    points: PointCloud,
    backend: AlphaBackend = AlphaBackend.Default,
    requireValidTriangulation: Boolean = false,
    maxRadius: Optional[Double] = Optional.empty,
    maxDimension: Optional[Int] = Optional.empty
  )(using
    factor: EpsilonFactor
  ): AlphaShapes =
    val radius = maxRadius.toOption.filter(_.isFinite)
    backend match
      case AlphaBackend.Default =>
        val chosen =
          if points.size > 0 && radius.exists(r => !requireValidTriangulation && prefersDQP(points, r))
          then AlphaBackend.DQP
          else if points.points.headOption.forall(_.length <= 4) then AlphaBackend.BowyerWatson
          else AlphaBackend.Helix
        apply(points, chosen, requireValidTriangulation, maxRadius, maxDimension)
      case AlphaBackend.Helix =>
        val helix = HelixDelaunay(points.points, requireValidTriangulation = requireValidTriangulation)
        radius.fold[AlphaShapes](helix)(r => RadiusLimitedAlphaShapes(helix, r))
      case AlphaBackend.BowyerWatson =>
        // Exact predicates: the triangulation is always valid, so requireValidTriangulation holds as it is.
        val delaunay = BowyerWatsonDelaunay(points.points)
        radius.fold[AlphaShapes](delaunay)(r => RadiusLimitedAlphaShapes(delaunay, r))
      case AlphaBackend.DQP =>
        require(
          !requireValidTriangulation,
          "requireValidTriangulation=true is not valid for backend = AlphaBackend.DQP: AlphaShapeDQP has no facet-" +
            "multiplicity precondition to repair (FastAlphaHomologyEngine takes a Delaunay triangulation and never " +
            "consumes AlphaShapeDQP's output) -- this option would be a silent no-op there."
        )
        val ambient = points.points.headOption.map(_.length).getOrElse(0)
        val topDimension = maxDimension.toOption.fold(ambient)(k => math.min(ambient, k + 1))
        radius match
          case None =>
            if topDimension == ambient then AlphaShapeDQP(points.points)
            else
              AlphaComplexDQPStream(
                points.points,
                AlphaComplexDQP.euclidean(points.points, Double.PositiveInfinity, topDimension)
              )
          case Some(r) =>
            AlphaComplexDQPStream(points.points, AlphaComplexDQP.euclidean(points.points, r, topDimension))

  /** Whether `DQP` is expected to build the alpha complex truncated at radius `r` faster than a triangulation
    * (`BowyerWatson` up to 4 dimensions, `Helix` above) builds the whole one. DQP's cost grows with the number of
    * points within `2r` of each point (its neighbour graph); a triangulation's with the point count and the ambient
    * dimension. The thresholds are measured (`.claude/WORKLOG-helix-construction-speed.md`): the largest average
    * neighbour count at which DQP was still faster, by point count and ambient dimension.
    */
  def prefersDQP(points: PointCloud, r: Double): Boolean =
    val pts = points.points
    val n = pts.length
    val d = pts.head.length
    // DQP's cost is a sum over points of k_i^1.6, so the sampled k_i^1.6 are averaged, not k_i: on clustered data the
    // mean of k would understate it.
    meanNeighbourCost(pts, 2 * r) <= math.pow(dqpNeighbourThreshold(n, d), 1.6)

  /** The average of `k^1.6` over up to 64 evenly spaced sample points, `k` a point's number of other points within
    * `distance`.
    */
  def meanNeighbourCost(pts: Array[Array[Double]], distance: Double): Double =
    val n = pts.length
    val samples = math.min(n, 64)
    val limit = distance * distance
    var total = 0.0
    for s <- 0 until samples do
      val i = (s.toLong * n / samples).toInt
      var count = 0
      var j = 0
      while j < n do
        if j != i then
          var sq = 0.0
          var a = 0
          while a < pts(i).length do
            val diff = pts(i)(a) - pts(j)(a)
            sq += diff * diff
            a += 1
          if sq <= limit then count += 1
        j += 1
      total += math.pow(count, 1.6)
    total / samples

  /** The average neighbour count (within `2r`) up to which DQP is expected to be faster than the triangulation
    * `Default` would use, for `n` points in ambient dimension `d`. Per point, DQP took about `c_d k^1.6` ms (`c_d =
    * 0.004 * 2.35^(d-2)`, the constant from 1000-point clouds, the more expensive ones); BowyerWatson about
    * `b_d (n/1000)^0.2` ms, `b_d` = 0.03, 0.1, 1.1 for `d` = 2..4; Helix in 5-D about `150 (n/1000)^0.4` ms (uniform
    * clouds of 1000-10000 points, this library's own measurements). In dimension 6 and up Helix's triangulation grows
    * so fast that DQP is preferred whenever a radius is given.
    */
  def dqpNeighbourThreshold(n: Int, d: Int): Double =
    val growth = n / 1000.0
    val triangulationPerPoint = math.max(d, 2) match
      case 2 => 0.03 * math.pow(growth, 0.2)
      case 3 => 0.1 * math.pow(growth, 0.2)
      case 4 => 1.1 * math.pow(growth, 0.2)
      case 5 => 150.0 * math.pow(growth, 0.4)
      case _ => Double.PositiveInfinity
    val dqpConstant = 0.004 * math.pow(2.35, math.max(d, 2) - 2)
    math.pow(triangulationPerPoint / dqpConstant, 1 / 1.6)

  // utilities for Delaunay computations
  type Point = DenseVector
  object Point:
    def apply(coords: Array[Double]): Point = DenseVector.fromArray(coords)

import AlphaShapes.Point

/** The hyperplane `{ x : normal . x = offset }`; its "light" side is where `normal . x >= offset`. */
case class Hyperplane(normal: Point, offset: Double):
  def isLight(point: Point): Boolean = normal.dot(point) >= offset

  /** The signed distance of `point` from the plane (positive on the light side) when `normal` has length 1. */
  def dist(point: Point): Double = normal.dot(point) - offset

  /** The same plane with light and dark sides exchanged. */
  def reverse: Hyperplane = Hyperplane(-normal, -offset)

object Hyperplane:
  /** The hyperplane through `pointSet` (at least as many points as the dimension, spanning a hyperplane): its normal is
    * the unit vector orthogonal to the points' differences from the first. Which of the two unit normals is returned is
    * fixed (the first coordinate of absolute value at least `0.5 / sqrt(dimension)` is positive), so that the plane
    * does not depend on the order of the points or on the decomposition that found it; callers that need a particular
    * side `reverse` it.
    */
  def from(pointSet: Seq[Point])(using epsilon: Epsilon): Hyperplane =
    assert(pointSet.size >= pointSet.head.size)
    val points = pointSet.map(p => p - pointSet.head)
    // now we need to find a basis of orthogonal complement to the space spanned by these
    val A = DenseMatrix(points.toSeq.map(_.toArray).toArray)
    val svdA = A.transpose.svd
    val nullSV = svdA.singularValues.zipWithIndex.filter(_._1.abs < epsilon.epsilon)
    val found =
      nullSV.map((s, i) => svdA.u.column(i)).head // should leave out head if we want higher codimensions
    // A unit vector has a coordinate of absolute value at least 1 / sqrt(dimension), so one is found.
    val threshold = 0.5 / math.sqrt(found.size.toDouble)
    val n =
      if (0 until found.size).find(i => math.abs(found(i)) >= threshold).exists(found(_) < 0.0) then -found else found
    val offset = n.dot(pointSet.head)
    Hyperplane(n, offset)

case class Hypersphere(center: Point, radius: Double)(using epsilon: Epsilon):
  def contains(point: Point): Boolean = (point - center).norm < radius - epsilon.epsilon

  def onSphere(point: Point): Boolean = math.abs((point - center).norm - radius) < epsilon.epsilon

object Hypersphere:
  def apply(pointSet: Seq[Point])(using epsilon: Epsilon): Hypersphere =
    val vvs = for
      v1 <- pointSet.indices
      v2 <- pointSet.indices
      if v1 < v2
    yield (pointSet(v1) - pointSet(v2), math.pow(pointSet(v1).norm, 2) - math.pow(pointSet(v2).norm, 2))
    val V = DenseMatrix(vvs.map(_._1.toArray).toArray)
    val b = DenseVector.fromArray(vvs.map(_._2).toArray) * 0.5
    val c = V.svd.solve(b)
    new Hypersphere(c, c.distance(pointSet.head))

case class DelaunaySimplex(simplex: Simplex[Int], circumsphere: Hypersphere)

/** Rank of `matrix`'s own row space, at THIS codebase's own `epsilon` tolerance -- not the decomposition's own `rank`,
  * whose internal tolerance (machine-epsilon-scale, relative to the matrix's own norm) is far tighter than
  * `epsilon.epsilon` (typically `1e-5`) and so treats near-but-not-exactly-degenerate input (e.g. points jittered at
  * the `1e-7` scale) as full rank when every OTHER degeneracy check in this file -- `Hyperplane. from`'s own `nullSV`
  * filter included -- would already call that same input degenerate. Used everywhere this file needs "how many
  * affinely-independent directions does this set of points actually span," so all of them agree on where the line is.
  */
// File-private: a public top-level def would join every user's `import org.appliedtopology.tda4j.*`.
private def rankAtEpsilon(matrix: DenseMatrix)(using epsilon: Epsilon): Int =
  matrix.svd.singularValues.count(_.abs > epsilon.epsilon)

/** Runs the frontier-walking bootstrap + main loop that finds every Delaunay simplex of `pts`, holding all of the
  * algorithm's own mutable working state (`validated`, `frontierCases`, `visitedFacets`, `cospherical`) so
  * `HelixDelaunay` itself only ever sees the finished, immutable result of `compute()` -- the same
  * mutable-builder/immutable-result split `AlphaComplexDQPBuilder`/`AlphaComplexDQP` use.
  */
// File-private: the walk's mutable state, alive only during `HelixDelaunay`'s construction (its public face).
private class HelixDelaunayBuilder(pts: Array[Array[Double]], seed: Long)(using epsilon: Epsilon):
  // `seed` controls, not merely reproduces: measured directly (varying seed 0-9 on a 3x3 grid and on 6 cospherical
  // points), the bootstrap shuffle changes WHICH of several valid tilings the frontier walk produces whenever the
  // input has cospherical/degenerate structure -- exactly the class doc's own "Accepted limitation" case, since a
  // different starting simplex can land the walk on a different side of an order-dependent tie. A fixed default
  // keeps unseeded call sites deterministic; it does not make the walk order-independent.
  private val rng: Random = new Random(seed)
  val points: Seq[Point] = pts.map(Point.apply).toIndexedSeq
  val ambientDimension: Int = points.head.size
  private val validated: mutable.Set[DelaunaySimplex] = mutable.Set.empty
  // The simplices of `validated`, for a hashed "already accepted?" test; a scan of `validated` comparing sorted sets
  // was most of the construction time (`.claude/WORKLOG-helix-construction-speed.md`).
  private val validatedSimplices: mutable.HashSet[Simplex[Int]] = mutable.HashSet.empty
  private def accept(ds: DelaunaySimplex): Unit =
    validated.add(ds)
    validatedSimplices.add(ds.simplex)

  private case class FrontierCase(
    facet: Simplex[Int],
    hyperplane: Hyperplane,
    complement: Int,
    cofacetHypersphere: Hypersphere
  )

  private object FrontierCase:
    def apply(cofacet: DelaunaySimplex, complement: Int): FrontierCase =
      apply(cofacet, complement, planeThrough(cofacet.simplex - complement))

    /** The case for the facet `cofacet` minus `complement`, with `hyperplane` from `planeThrough` for that facet. */
    def apply(cofacet: DelaunaySimplex, complement: Int, hyperplane: Hyperplane): FrontierCase =
      val facet = cofacet.simplex - complement
      if hyperplane.isLight(points(complement)) then
        FrontierCase(facet, hyperplane.reverse, complement, cofacet.circumsphere)
      else FrontierCase(facet, hyperplane, complement, cofacet.circumsphere)

  private def planeThrough(facet: Simplex[Int]): Hyperplane = Hyperplane.from(facet.toSeq.toSeq.map(points(_)))

  private val frontierCases: mutable.ArrayDeque[FrontierCase] = mutable.ArrayDeque.empty
  private val visitedFacets: mutable.Set[Simplex[Int]] = mutable.Set.empty
  private val cospherical: mutable.Set[Set[Int]] = mutable.Set.empty

  // The queued, not cancelled, frontier cases by facet. Removing a case from the middle of `frontierCases` scanned the
  // whole queue comparing sorted sets (`.claude/WORKLOG-helix-construction-speed.md`); instead a removed case is
  // marked cancelled (by identity) and skipped when it is taken, which leaves the order of the others unchanged.
  private val queuedByFacet: mutable.HashMap[Simplex[Int], mutable.ArrayBuffer[FrontierCase]] = mutable.HashMap.empty
  private val cancelled: java.util.Set[FrontierCase] =
    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[FrontierCase, java.lang.Boolean]())

  private def enqueue(fc: FrontierCase): Unit =
    frontierCases.append(fc)
    queuedByFacet.getOrElseUpdate(fc.facet, mutable.ArrayBuffer.empty) += fc

  private def cancel(facet: Simplex[Int]): Boolean =
    queuedByFacet.remove(facet) match
      case Some(cases) if cases.nonEmpty =>
        cases.foreach(cancelled.add)
        true
      case _ => false

  /** The next frontier case that was not cancelled, if any. */
  private def nextFrontierCase(): Option[FrontierCase] =
    var next: Option[FrontierCase] = None
    while next.isEmpty && frontierCases.nonEmpty do
      val fc = frontierCases.removeHead()
      if !cancelled.remove(fc) then
        queuedByFacet.get(fc.facet).foreach { cases =>
          cases.remove(cases.indexWhere(_ eq fc))
          if cases.isEmpty then queuedByFacet.remove(fc.facet)
        }
        next = Some(fc)
    next

  // Cospherical (to be tiled as a cluster) means on the sphere up to floating-point error, not up to `epsilon`: the
  // minimal-centre choice resolves near-ties exactly, and tiling a merely near-cospherical group as a cluster gives a
  // tiling that does not match its neighbours (at the default epsilon 1e-5 that happened routinely on a few thousand
  // random points; `.claude/WORKLOG-helix-construction-speed.md`). Exact degeneracies (grids) are off by ~1e-15.
  private def onSphere(distance: Double, radius: Double): Boolean =
    math.abs(distance - radius) <= 1e-10 * math.max(radius, Double.MinPositiveValue)

  /** Frontier cases whose cofacet came from `bySphereScan` because `byMinimalCentre` failed its own emptiness check. */
  private[tda4j] var sphereScanFallbacks: Int = 0

  /** What the walk learns about a frontier case from the points alone: the Delaunay simplex on the light side of the
    * facet (none for a hull facet), the points on its circumsphere (sorted), and whether the sphere scan had to replace
    * `byMinimalCentre`. It depends on no walk state, so several cases can be evaluated at the same time.
    */
  private case class Step(
    cofacet: Option[DelaunaySimplex],
    onCircumsphere: Array[Int],
    fellBack: Boolean,
    planes: Array[Hyperplane]
  )

  /** The Delaunay simplex on the light side of a frontier facet, if any: `byMinimalCentre`, checked to have an empty
    * circumsphere; `bySphereScan` if that check fails (floating-point trouble near a degenerate facet).
    */
  private def evaluate(fc: FrontierCase): Step =
    val (cofacet, fellBack) = facetCentre(fc.facet) match
      case None         => (bySphereScan(fc), true)
      case Some(centre) =>
        byMinimalCentre(fc.facet, fc.hyperplane, centre) match
          case None                                                                => (None, false) // a hull facet
          case Some(ds) if !pts.exists(sphereStrictlyContains(ds.circumsphere, _)) => (Some(ds), false)
          case Some(_)                                                             => (bySphereScan(fc), true)
    val onSphere = cofacet.fold(Array.emptyIntArray)(ds => pointsOn(ds.circumsphere))
    // The hyperplanes of the new cofacet's other facets, which the walk needs next unless a cospherical cluster is
    // tiled instead (then there are none): an SVD each, which is most of what the walk does outside this method.
    val planes = cofacet match
      case Some(ds) if onSphere.length <= ambientDimension + 1 =>
        fc.facet.toSeq.toArray.map(
          vi => // a plane that cannot be made is left for the walk to meet (and fail on) in its turn
            try planeThrough(ds.simplex - vi)
            catch case scala.util.control.NonFatal(_) => null
        )
      case _ => Array.empty[Hyperplane]
    Step(cofacet, onSphere, fellBack, planes)

  /** The indices of the points on `sphere`, within `onSphere`'s tolerance, ascending. */
  private def pointsOn(sphere: Hypersphere): Array[Int] =
    val c = sphere.center.toArray
    val found = mutable.ArrayBuilder.ofInt()
    var q = 0
    while q < pts.length do
      val p = pts(q)
      var sq = 0.0
      var i = 0
      while i < p.length do
        val d = p(i) - c(i)
        sq += d * d
        i += 1
      if onSphere(math.sqrt(sq), sphere.radius) then found += q
      q += 1
    found.result()

  // The cases evaluated ahead of the walk, by identity. A case's `Step` depends on the points only, and the walk takes
  // its cases in queue order whatever it has computed, so evaluating the next few queued cases at the same time changes
  // nothing but the time: a case that is cancelled or visited before its turn is evaluated for nothing.
  private val prepared = new java.util.IdentityHashMap[FrontierCase, Step]()
  // Enough points that a case costs more than handing it to another thread (about 500 coordinates to scan, three times).
  private val batchSize: Int =
    if Parallel.threads > 1 && pts.length.toLong * ambientDimension >= 500 then Parallel.threads * 8 else 1

  private def stepOf(fc: FrontierCase): Step =
    var step = prepared.remove(fc)
    if step == null then
      if batchSize > 1 then
        prepared.clear()
        val facets = mutable.HashSet(fc.facet)
        val batch = mutable.ArrayBuffer(fc)
        val queued = frontierCases.iterator
        var scanned = 0
        while batch.size < batchSize && scanned < 4 * batchSize && queued.hasNext do
          val next = queued.next()
          scanned += 1
          if !cancelled.contains(next) && !visitedFacets.contains(next.facet) && facets.add(next.facet) then
            batch += next
        // A case whose evaluation fails is left for the walk to evaluate, and fail on, if it ever reaches it.
        val steps = Parallel.map(batch.toVector)(c =>
          try evaluate(c)
          catch case scala.util.control.NonFatal(_) => null
        )
        batch.indices.foreach(i => if steps(i) != null then prepared.put(batch(i), steps(i)))
        step = prepared.remove(fc)
      if step == null then step = evaluate(fc)
    if step.fellBack then sphereScanFallbacks += 1
    step

  // Every sphere through the facet has its centre on the line c0 + t n (c0 the facet's circumcentre in its own
  // hyperplane, n the normal towards the light side), and passes through a light point p at
  //   t(p) = (|p - c0|² - r0²) / (2 n·(p - c0)).
  // Growing t from the side of the known cofacet, the first light point the sphere meets -- the smallest t -- gives
  // the empty one: the Delaunay cofacet. Ties (cospherical points) go to the smallest index. One O(n d) pass, no
  // sorting, no allocation per point; points within epsilon of the facet's hyperplane cannot span a simplex with it
  // and are skipped. (`.claude/WORKLOG-helix-construction-speed.md`.)
  private def byMinimalCentre(
    facet: Simplex[Int],
    hyperplane: Hyperplane,
    centre: (Array[Double], Double)
  ): Option[DelaunaySimplex] =
    val (c0, r0sq) = centre
    locally {
      val n = hyperplane.normal.toArray
      val facetVertices = facet.toSeq.toArray
      def isVertex(q: Int): Boolean =
        var i = 0
        while i < facetVertices.length && facetVertices(i) != q do i += 1
        i < facetVertices.length
      var best = -1
      var bestT = Double.PositiveInfinity
      var q = 0
      while q < pts.length do
        val p = pts(q)
        var s = 0.0
        var sq = 0.0
        var i = 0
        while i < ambientDimension do
          val d = p(i) - c0(i)
          s += n(i) * d
          sq += d * d
          i += 1
        // c0 lies on the facet's hyperplane, so s is q's signed distance from it.
        if s > epsilon.epsilon && !isVertex(q) then
          val tq = (sq - r0sq) / (2 * s)
          if tq < bestT then
            bestT = tq
            best = q
        q += 1
      // The sphere is the one the choice was made on: centre c0 + t n, radius² r0² + t². Solving for it again from the
      // simplex's vertices is ill-conditioned on slivers (near-coplanar points), where it can be far off.
      Option.when(best >= 0) {
        val centre = Array.tabulate(ambientDimension)(i => c0(i) + bestT * n(i))
        DelaunaySimplex(facet + best, new Hypersphere(Point(centre), math.sqrt(r0sq + bestT * bestT)))
      }
    }

  /** The facet's circumcentre in its own affine hull and squared circumradius; `None` if the facet is degenerate. */
  private def facetCentre(facet: Simplex[Int]): Option[(Array[Double], Double)] =
    val vs = facet.toSeq.toArray
    val p0 = pts(vs(0))
    val k = vs.length - 1
    val diffs = Array.tabulate(k, ambientDimension)((i, j) => pts(vs(i + 1))(j) - p0(j))
    def dot(a: Array[Double], b: Array[Double]) = a.indices.foldLeft(0.0)((s, j) => s + a(j) * b(j))
    if k == 0 then Some((p0.clone(), 0.0))
    else
      val gram = DenseMatrix.tabulate(k, k)((i, j) => 2 * dot(diffs(i), diffs(j)))
      val solver = gram.lu(1e-14)
      if !solver.isNonSingular then None
      else
        val lambda = solver.solve(DenseVector.tabulate(k)(i => dot(diffs(i), diffs(i)))).toArray
        val c0 = Array.tabulate(ambientDimension)(j => p0(j) + (0 until k).map(i => lambda(i) * diffs(i)(j)).sum)
        val r0sq = (0 until ambientDimension).map(j => (c0(j) - p0(j)) * (c0(j) - p0(j))).sum
        Some((c0, r0sq))

  private def sphereStrictlyContains(sphere: Hypersphere, p: Array[Double]): Boolean =
    val c = sphere.center
    var sq = 0.0
    var i = 0
    while i < p.length do
      val d = p(i) - c(i)
      sq += d * d
      i += 1
    math.sqrt(sq) < sphere.radius - epsilon.epsilon

  /** The walk's original candidate search: light points in order of distance to the facet's circumcentre, the first
    * whose simplex has an empty circumsphere. Quadratic overall; kept as the fallback.
    */
  private def bySphereScan(fc: FrontierCase): Option[DelaunaySimplex] =
    val circumsphere = Hypersphere(fc.facet.toSeq.toSeq.map(points))
    (points.indices.toSet -- fc.facet.toSeq).toSeq
      .filter(pi => fc.hyperplane.isLight(points(pi)))
      .sortBy(pi => circumsphere.center.distance(points(pi)))
      .view
      .map(pi => DelaunaySimplex(fc.facet + pi, Hypersphere((fc.facet + pi).toSeq.toSeq.map(points))))
      .collectFirst { case ds if !points.exists(ds.circumsphere.contains) => ds }

  private def addFrontierCase(simplex: DelaunaySimplex, complement: Int): Unit =
    if !cancel(simplex.simplex - complement) then enqueue(FrontierCase(simplex, complement))

  // `plane` is `planeThrough` the facet, made ahead of time by `evaluate`.
  private def addFrontierCase(simplex: DelaunaySimplex, complement: Int, plane: Hyperplane): Unit =
    if !cancel(simplex.simplex - complement) then enqueue(FrontierCase(simplex, complement, plane))

  private def handleCosphericalPoints(
    cosphericalPoints: Seq[Int],
    frontierCase: FrontierCase,
    newDelaunaySimplex: DelaunaySimplex
  ): Unit =
    val spherepoints: mutable.SortedSet[Int] = cosphericalPoints.to(mutable.SortedSet)
    // all of these work as extra point: every subset of the spherepoints is a valid Delaunay simplex with this empty circumsphere
    // so we need to pick a tiling subset of them. We start a local version of this frontier walking algorithm
    // we also need to make sure we don't come back inside this cospherical point set in a later iteration
    cospherical.add(spherepoints.toSet)
    val cosphericalSet = cosphericalPoints.toSet
    queuedByFacet.keys.filter(_.toSet.subsetOf(cosphericalSet)).toList.foreach(cancel)
    spherepoints.subtractAll(newDelaunaySimplex.simplex.toSeq)
    // EVERY facet of `newDelaunaySimplex`, not just the ones built from `frontierCase.facet`'s own vertices.
    // `frontierCase.facet` is exactly `newDelaunaySimplex.simplex` minus the complement vertex (the one the
    // main loop just added to resolve this frontier case) -- so iterating `fi` only over `frontierCase.facet`'s
    // own vertices can never produce `frontierCase.facet` itself as one of the generated (facet, cofacet) pairs
    // (`fi` never equals the complement). In the ordinary case that's harmless: there's nothing left to pull in
    // on that side, so omitting it changes nothing. But when a genuinely different point is ALSO a valid,
    // empty-circumsphere candidate for that SAME originating facet (near-cospherical with the one just chosen --
    // exactly the situation this method exists to handle), omitting it means that candidate is never reachable:
    // the cospherical branch never calls `addFrontierCase` for `frontierCase.facet` (only the non-cospherical
    // `else` branch does), and `visitedFacets` already marked it visited at the top of the main loop before this
    // method was ever called -- so the facet is structurally locked out from both the local search here AND the
    // main frontier walk, and the second candidate is silently lost. Confirmed directly on a real 13-point,
    // ambient-dimension-4 example: two points, 1 and 4, are each independently a genuine empty-circumsphere
    // coface of facet `{2,3,6,9}` (circumradii differing by ~1.5e-5, each within `epsilon` of the other's own
    // circumsphere), and `frontierCase.facet` there is exactly `{2,3,6,9}` -- the facet this omission drops.
    // Iterating over EVERY vertex of `newDelaunaySimplex.simplex` includes that facet too (oriented correctly by
    // this loop's own `candidateHP.isLight(points(complement))` check, exactly as for the other facets); in the
    // ordinary case it then finds no remaining light spherepoints and falls through to `addFrontierCase`, which
    // is a harmless no-op once picked up by the main loop (`visitedFacets` already has it) -- see
    // `.claude/WORKLOG-helix-bootstrap-fix.md`.
    val facets: mutable.ArrayDeque[(Simplex[Int], Simplex[Int])] =
      mutable.ArrayDeque.from(
        newDelaunaySimplex.simplex.toSeq.toSeq.map(fi => (newDelaunaySimplex.simplex - fi, newDelaunaySimplex.simplex))
      )
    while facets.nonEmpty do
      // take a facet
      val (facet, cofacet) = facets.removeHead()
      val complement = cofacet.toSeq.diff(facet.toSeq).head
      val hyperplane: Hyperplane = Hyperplane.from(facet.toSeq.toSeq.map(points)) match
        case candidateHP if candidateHP.isLight(points(complement)) => candidateHP.reverse
        case candidateHP                                            => candidateHP
      spherepoints.filter(hyperplane.isLight.compose(points)).headOption match
        case Some(pi) =>
          val nds = DelaunaySimplex(facet + pi, newDelaunaySimplex.circumsphere)
          accept(nds)
          facets.addAll(facet.toSeq.toSeq.map(pj => (Simplex.from(nds.simplex.toSet.diff(Set(pj)).toSeq), nds.simplex)))
          spherepoints.remove(pi)
        case None =>
          addFrontierCase(
            DelaunaySimplex(cofacet, newDelaunaySimplex.circumsphere),
            cofacet.toSeq.diff(facet.toSeq).head
          )

  /** Runs the full bootstrap + frontier walk once and returns every Delaunay simplex found. */
  // Greedily picks `ambientDimension` affinely-independent points from `candidates` (in the given order), by
  // rank -- the SAME technique already used below for refining an over-sized `vs`, applied here to the hull-
  // hyperplane-search loop's OWN candidate simplex too. This matters for a genuinely different reason than the
  // `vs` refinement: `Hyperplane.from` assumes its input spans a genuine (ambientDimension-1)-dimensional
  // hyperplane (rank exactly `ambientDimension - 1`). If the `ambientDimension` points handed to it are
  // THEMSELVES more degenerate than that (rank strictly less -- e.g. they lie in some lower-dimensional flat
  // within the ambient space, not just "coplanar" the way a genuine facet's points are), its SVD-based normal-
  // vector extraction is under-determined: there is an entire FAMILY of hyperplanes containing such a point
  // set, not one, and the specific one the code happens to compute can be an arbitrary, non-hull-supporting
  // plane -- one that can have points strictly on BOTH sides of it, yet still spuriously satisfy the loop's own
  // `lightPoints.isEmpty` termination check, converging the whole bootstrap onto a bogus, non-facet
  // `startingSimplex`. Confirmed as a real, distinct root cause (not the same one the `vs`-refinement fix
  // above addresses) via direct reproduction: a real point cloud's 5-point candidate simplex had rank 3, not
  // the required 4, and its resulting "hyperplane" had points with `dist` both well above and well below
  // `epsilon` -- see `.claude/WORKLOG-helix-bootstrap-fix.md`.
  private def affinelyIndependentPick(candidates: Seq[Int]): Set[Int] =
    val base = points(candidates.head)
    var chosen: Vector[Int] = Vector(candidates.head)
    var chosenVecs: Vector[Array[Double]] = Vector.empty
    val remaining = candidates.tail.iterator
    while chosen.size < ambientDimension && remaining.hasNext do
      val candidate = remaining.next()
      val trialVecs = chosenVecs :+ (points(candidate) - base).toArray
      val rank = rankAtEpsilon(DenseMatrix(trialVecs.toArray))
      if rank > chosenVecs.size then
        chosen = chosen :+ candidate
        chosenVecs = trialVecs
    chosen.toSet

  /** `ambientDimension` points spanning a facet of the convex hull, by gift wrapping: start at the lexicographically
    * smallest point with the supporting hyperplane `x_0 = min`, then repeatedly rotate the hyperplane about the points
    * chosen so far, towards a direction orthogonal to them and to its normal, until it first meets another point. Each
    * step is one pass over the points; ties go to the smallest index. Needs full affine rank (which `HelixDelaunay`'s
    * projection guarantees): then some direction always moves at least one point.
    */
  private def hullFacet(): Set[Int] =
    val d = ambientDimension
    def dot(a: Array[Double], b: Array[Double]) =
      var s = 0.0
      var i = 0
      while i < d do
        s += a(i) * b(i)
        i += 1
      s
    def unit(a: Array[Double]) =
      val norm = math.sqrt(dot(a, a))
      a.map(_ / norm)
    val p0 = pts.indices.minBy(i => pts(i).toSeq)(using Ordering.Implicits.seqOrdering[Seq, Double])
    val chosen = mutable.ArrayBuffer(p0)
    var normal = Array.tabulate(d)(i => if i == 0 then -1.0 else 0.0) // outward: every point has normal·(q - p0) <= 0
    while chosen.size < d do
      // Orthonormal basis of the span of the normal and the chosen points' differences; directions outside it rotate.
      val basis = mutable.ArrayBuffer(normal)
      def addOrthogonal(v: Array[Double]): Option[Array[Double]] =
        val w = v.clone()
        basis.foreach { b =>
          val c = dot(w, b); for i <- 0 until d do w(i) -= c * b(i)
        }
        Option.when(math.sqrt(dot(w, w)) > 1e-12)(unit(w))
      chosen.tail.foreach(c => addOrthogonal(Array.tabulate(d)(i => pts(c)(i) - pts(p0)(i))).foreach(basis += _))
      val directions =
        (0 until d).iterator.flatMap(axis => addOrthogonal(Array.tabulate(d)(i => if i == axis then 1.0 else 0.0)))
      var found = false
      while !found && directions.hasNext do
        val w = directions.next()
        // Rotating the normal to cos θ n + sin θ w, point q (a = n·(q-p0) <= 0, b = w·(q-p0)) reaches the hyperplane at
        // θ = atan2(-a, b); the first point met has the smallest θ in [0, π). θ = 0 is a point already on the hyperplane
        // with b > 0, which must join before any rotation. Points on the chosen flat (a = b = 0) are never met.
        var best = -1
        var bestTheta = Double.PositiveInfinity
        var q = 0
        while q < pts.length do
          if !chosen.contains(q) then
            val diff = Array.tabulate(d)(i => pts(q)(i) - pts(p0)(i))
            val a = math.min(dot(normal, diff), 0.0)
            val b = dot(w, diff)
            if b > epsilon.epsilon || -a > epsilon.epsilon then
              val theta = math.atan2(-a, b)
              if theta < bestTheta then
                bestTheta = theta
                best = q
          q += 1
        if best >= 0 then
          val c = math.cos(bestTheta)
          val s = math.sin(bestTheta)
          normal = unit(Array.tabulate(d)(i => c * normal(i) + s * w(i)))
          chosen += best
          found = true
      if !found then throw new IllegalStateException("HelixDelaunayBuilder: no hull facet found (input not full rank)")
    chosen.toSet

  def compute(): Set[DelaunaySimplex] =
    var startingSimplex: Set[Int] = hullFacet()
    val vs = points.indices.filter(pi =>
      Hyperplane.from(startingSimplex.map(points).toSeq).dist(points(pi)).abs < epsilon.epsilon
    )

    // vs has more points on the supporting hyperplane than needed for a non-degenerate
    // (ambientDimension-1)-simplex within it -- common for grid-like or otherwise partly-degenerate point
    // clouds. Picking just 2 of them regardless of ambientDimension leaves startingSimplex undersized for
    // ambientDimension > 2, starving the brute-force bootstrap search below of a well-posed circumsphere
    // (Hypersphere needs ambientDimension+1 points to be uniquely determined) and making it fail to find any
    // candidate. So instead, greedily grow an affinely-independent subset of exactly ambientDimension points,
    // rooted at `base`: start from `base`, and add each next candidate (by increasing distance from `base`,
    // NOT raw point-index order) only if it strictly increases the rank of the affine span built so far.
    //
    // Distance-based ordering matters, not just correctness of the final rank: a candidate subset that spans a
    // "long" edge/facet skipping over some OTHER vs point actually lying BETWEEN the chosen vertices dooms the
    // brute-force seed search below regardless of which third point it tries next -- that skipped-over point
    // ends up unconditionally inside every candidate circumsphere (a direct geometric fact: for any two points
    // p, q and a third point r on the segment between them, r's distance to the center of ANY circle through p
    // and q is always strictly less than that circle's own radius, since r sits on the foot of the
    // perpendicular from the center to the chord p-q). Confirmed as a real root cause via a grid-point
    // reproduction (a plain 3x3 integer grid, zero jitter: `vs = {(0,0),(1,0),(2,0)}`, and picking
    // `{(0,0),(2,0)}` skips over `(1,0)`, which then sits inside every circumcircle through the chosen pair
    // and any third grid point) -- see `.claude/WORKLOG-helix-bootstrap-fix.md`.
    //
    // A single greedy pass rooted at one fixed base (`vs.head`) is not a complete fix at higher ambient
    // dimension: nearest-to-ONE-base doesn't guarantee no OTHER vs point ends up inside the resulting
    // higher-dimensional simplex's own affine hull. Measured directly, not assumed, across three successive
    // attempts: the raw index-order pick failed a targeted grid-heavy stress sweep at 2226/30000; sorting one
    // greedy pass by distance from `vs.head` cut that to 744/30000 (fixed the 2D case outright, not the higher-
    // dimensional one); trying a nearest-neighbor candidate rooted at every vs point in turn cut it further to
    // 70/30000, but still not zero. So this instead enumerates every affinely-independent `ambientDimension`-
    // subset of `vs` (bounded -- `vs` is typically a handful of points sharing one exact hyperplane even in the
    // pathological cases that trigger this branch at all; capped at `maxCandidates` as a safety valve against a
    // pathological `vs` this reasoning doesn't anticipate), ordered by increasing total pairwise span so the
    // candidates least likely to skip over an interior point are tried first, and the brute-force search below
    // retries across ALL of them (not just the first) until one succeeds.
    val maxCandidates = 20000
    val candidateStartingSimplices: Seq[Set[Int]] =
      if vs.size == ambientDimension then Seq(vs.toSet)
      else
        vs
          .combinations(ambientDimension)
          .filter { combo =>
            val base = points(combo.head)
            val vecs = combo.tail.map(pi => (points(pi) - base).toArray)
            rankAtEpsilon(DenseMatrix(vecs.toArray)) == ambientDimension - 1
          }
          .take(maxCandidates)
          .map(_.toSet)
          .toSeq
          .sortBy(combo =>
            (for
              i <- combo.toSeq.indices
              j <- (i + 1) until combo.size
            yield points(combo.toSeq(i)).distance(points(combo.toSeq(j)))).sum
          )

    // brute force search for first delaunay simplex -- retried across every candidate starting simplex above,
    // not just the first, stopping at the first (candidate, third point) pair whose circumsphere is empty.
    var done = false
    val candidateIter = candidateStartingSimplices.iterator
    while !done && candidateIter.hasNext do
      val candidate = candidateIter.next()
      // The candidate is a hull facet: every point lies on one side of its hyperplane, so the Delaunay simplex on it
      // is the frontier walk's minimal-centre choice, found in one pass. The search below (every point, each with a
      // full emptiness scan: quadratic) remains for a candidate where that fails.
      val facet = Simplex.from(candidate.toSeq)
      val plane = Hyperplane.from(candidate.toSeq.map(points))
      val inward = if points.exists(p => plane.dist(p) < -epsilon.epsilon) then plane.reverse else plane
      for
        centre <- facetCentre(facet)
        ds <- byMinimalCentre(facet, inward, centre)
        if !pts.exists(sphereStrictlyContains(ds.circumsphere, _))
      do
        done = true
        startingSimplex = candidate
        accept(ds)
      for pi <- points.indices do
        if !done then
          if !candidate.contains(pi) then
            val circumsphere = Hypersphere((candidate + pi).map(p => points(p)).toSeq)
            val containedPoints = points.indices.toSet.filter(qi => circumsphere.contains(points(qi)))
            if containedPoints.isEmpty then
              done = true
              startingSimplex = candidate
              accept(DelaunaySimplex(Simplex.from((candidate + pi).toSeq), circumsphere))
    assert(
      validated.nonEmpty,
      s"HelixDelaunayBuilder: no empty-circumsphere seed simplex found across ${candidateStartingSimplices.size} " +
        s"candidate starting simplices (hull-supporting hyperplane has ${vs.size} coincident points) . This is a bug, " +
        "not an input error: please report it with the point cloud."
    )

    visitedFacets.add(Simplex.from(startingSimplex.toSeq))

    val seedDelaunaySimplex: DelaunaySimplex = validated.head
    points.indices
      .map(i => (i, seedDelaunaySimplex.circumsphere.center.distance(points(i))))
      .filter((i, d) => onSphere(d, seedDelaunaySimplex.circumsphere.radius))
      .map(_._1)
      .to(mutable.SortedSet) match
      case spherepoints if spherepoints.size > ambientDimension + 1 =>
        handleCosphericalPoints(
          spherepoints.toSeq,
          FrontierCase(seedDelaunaySimplex, seedDelaunaySimplex.simplex.toSet.diff(startingSimplex).head),
          seedDelaunaySimplex
        )
      case spherepoints => startingSimplex.foreach(pi => enqueue(FrontierCase(seedDelaunaySimplex, pi)))

    // handle a frontier case
    // Taken only after the previous case is processed: processing can cancel queued cases.
    var pending: Option[FrontierCase] = None
    while
      pending = nextFrontierCase()
      pending.isDefined
    do
      val frontierCase = pending.get
      if !visitedFacets.contains(frontierCase.facet) then
        visitedFacets.add(frontierCase.facet)
        val step = stepOf(frontierCase)
        step.cofacet match
          case Some(newDelaunaySimplex) if !validatedSimplices.contains(newDelaunaySimplex.simplex) =>
            // check whether we have "too many" cospherical points; in that case we have to tile them on our own
            val spherepoints: mutable.SortedSet[Int] = step.onCircumsphere.to(mutable.SortedSet)
            if spherepoints.size > ambientDimension + 1 then
              if !cospherical.contains(spherepoints.toSet) then
                accept(newDelaunaySimplex)
                handleCosphericalPoints(spherepoints.toSeq, frontierCase, newDelaunaySimplex)
            else
              accept(newDelaunaySimplex)
              frontierCase.facet.toSeq.toSeq.zipWithIndex
                .foreach((vi, at) =>
                  if step.planes(at) == null then addFrontierCase(newDelaunaySimplex, vi)
                  else addFrontierCase(newDelaunaySimplex, vi, step.planes(at))
                )
          case Some(newDelaunaySimplex) => ()
          case None                     => ()

    validated.toSet

/** An alpha complex read off a Delaunay triangulation of `points`: the faces of the top-dimensional simplices, with
  * alpha values computed top-down (see `valueOf`). A subclass supplies the triangulation (`topSimplices`, distinct) and
  * the value of each top simplex (`topValue`, its circumradius); [[HelixDelaunay]] is one. [[FastAlphaHomologyEngine]]
  * works on any of them.
  */
abstract class DelaunayAlphaShapes(using epsilon: Epsilon) extends AlphaShapes with FlatLayerStream:
  /** The dimension of the triangulation (of the points' affine span). */
  def ambientDimension: Int

  /** The points, in the coordinates the triangulation was built in. */
  def points: Seq[Point]

  /** The top-dimensional simplices, each once. */
  protected def topSimplices: Iterable[Simplex[Int]]

  /** The alpha value of a top-dimensional simplex. */
  protected def topValue(s: Simplex[Int]): Double

  /** Points that coincide exactly with another one (the key), which is the one in the triangulation. Each becomes a
    * vertex joined to it by an edge of value 0, so every input point is in the complex and none adds an `H_0` class.
    */
  protected def duplicates: Map[Int, Int] = Map.empty

  // Every simplex once, per dimension, with its alpha value: flat arrays (a face table per dimension, faces found from
  // their immediate cofaces). The `Simplex` objects of a dimension are made when that dimension is asked for
  // (`simplicesOfDimension`) and a value is looked up in the tables, so a run that reads the first few dimensions
  // (the verb cuts the stream at `maxDimension + 1`) never pays for the millions of faces above them.
  private lazy val built: (Array[DelaunayAlphaShapes.FaceTable], Array[Array[Double]]) =
    val coords = points.map(_.toArray).toArray
    val top = ambientDimension
    val tops = topSimplices.toArray
    // Level `top`: the (top + 1)-subsets of the top simplices (the top simplices themselves when they have that size).
    val levels = new Array[DelaunayAlphaShapes.FaceTable](top + 1)
    val values = new Array[Array[Double]](top + 1)
    levels(top) = DelaunayAlphaShapes.FaceTable(top + 1, tops.length)
    val topIndex = mutable.ArrayBuffer.empty[(Int, Simplex[Int])]
    tops.foreach { t =>
      val vs = t.toArray
      if vs.length == top + 1 then
        val (i, fresh) = levels(top).add(vs)
        if fresh then topIndex += ((i, t))
      else
        vs.combinations(top + 1).foreach { c =>
          val (i, fresh) = levels(top).add(c)
          if fresh then topIndex += ((i, Simplex.from(c.toSeq)))
        }
    }
    values(top) = new Array[Double](levels(top).size)
    val topRows = topIndex.toArray
    // The values are independent of each other (`topValue` reads tables made before this point).
    Parallel.ranges(topRows.length, DelaunayAlphaShapes.ParallelGrain) { (from, until) =>
      var t = from
      while t < until do
        values(top)(topRows(t)._1) = topValue(topRows(t)._2)
        t += 1
    }
    // Each lower level: its faces with the cofaces' values and the Gabriel test against their opposite vertices.
    for k <- (top - 1) to 0 by -1 do
      val upper = levels(k + 1)
      // The faces of dimension `k` with `pf(p)` the row of pair `p` = (coface row `p / (k + 2)`, its vertex `p % (k + 2)`
      // dropped); the vertex dropped is the coface's one opposite the face.
      val (faces, pf) = DelaunayAlphaShapes.FaceTable.facesOf(upper)
      val cofaceWidth = k + 2
      val m = faces.size
      val vals = new Array[Double](m)
      if k == 0 then java.util.Arrays.fill(vals, 0.0)
      else
        val dim = coords.head.length
        val center = new Array[Double](m * dim)
        val radius = new Array[Double](m)
        Parallel.ranges(m, DelaunayAlphaShapes.ParallelGrain) { (from, until) =>
          val vs = new Array[Int](k + 1)
          var f = from
          while f < until do
            for i <- 0 to k do vs(i) = faces.vertex(f, i)
            radius(f) = DelaunayAlphaShapes.circumsphere(coords, vs, center, f * dim)
            f += 1
        }
        // The pairs grouped by face (a counting sort), so that the faces can be judged independently of each other.
        val first = new Array[Int](m + 1)
        for p <- pf.indices do first(pf(p) + 1) += 1
        for f <- 0 until m do first(f + 1) += first(f)
        val byFace = new Array[Int](pf.length)
        val filled = first.clone()
        for p <- pf.indices do
          byFace(filled(pf(p))) = p
          filled(pf(p)) += 1
        val upperValues = values(k + 1)
        // A Gabriel simplex with a coface vertex exactly on its sphere has exactly that coface's value (the same
        // sphere); computed separately the two differ in the last bits and would leave a bar of length ~1e-16.
        Parallel.ranges(m, DelaunayAlphaShapes.ParallelGrain) { (from, until) =>
          var f = from
          while f < until do
            var cofaceMin = Double.PositiveInfinity
            var gabriel = true
            var e = first(f)
            while e < first(f + 1) do
              val p = byFace(e)
              cofaceMin = math.min(cofaceMin, upperValues(p / cofaceWidth))
              val q = coords(upper.vertex(p / cofaceWidth, p % cofaceWidth))
              var d2 = 0.0
              var r = 0
              while r < dim do
                val x = q(r) - center(f * dim + r)
                d2 += x * x
                r += 1
              if math.sqrt(d2) < radius(f) - epsilon.epsilon then gabriel = false
              e += 1
            vals(f) = if gabriel && radius(f) < cofaceMin * (1 - 1e-12) then radius(f) else cofaceMin
            f += 1
        }
      levels(k) = faces
      values(k) = vals
    (levels, values)

  // Dimension 1 exists even for points spanning nothing when some of them are repeated (their value-0 edges).
  private lazy val highest: Int = if duplicates.nonEmpty then math.max(ambientDimension, 1) else ambientDimension

  // The simplices of dimension `k` in table order, then the repeated points' extras (value 0).
  private val unsorted: mutable.HashMap[Int, Seq[Simplex[Int]]] = mutable.HashMap.empty
  private def simplicesOfDimension(k: Int): Seq[Simplex[Int]] = synchronized {
    unsorted.getOrElseUpdate(
      k, {
        val level = if k <= ambientDimension then built._1(k) else DelaunayAlphaShapes.FaceTable(k + 1, 0)
        val simplices = new Array[Simplex[Int]](level.size)
        Parallel.ranges(simplices.length, DelaunayAlphaShapes.ParallelGrain) { (from, until) =>
          var f = from
          while f < until do
            simplices(f) = Simplex.from(Seq.tabulate(k + 1)(i => level.vertex(f, i)))
            f += 1
        }
        val extra: Seq[Simplex[Int]] = k match
          case 0 => duplicates.keys.toSeq.map(Simplex(_))
          case 1 => duplicates.toSeq.map((dup, kept) => Simplex(dup, kept))
          case _ => Nil
        simplices.toSeq ++ extra
      }
    )
  }

  lazy val simplicesMap: Map[Int, Seq[Simplex[Int]]] = (0 to highest).map(k => k -> simplicesOfDimension(k)).toMap

  /** The smallest sphere through every vertex of `s`: its centre lies in `s`'s own affine hull (`Hypersphere.apply` is
    * for full-dimensional simplices only).
    */
  def smallestCircumsphere(s: Simplex[Int]): (Point, Double) =
    val coords = s.toSeq.map(points(_).toArray).toArray
    val center = new Array[Double](coords.head.length)
    val r = DelaunayAlphaShapes.circumsphere(coords, coords.indices.toArray, center, 0)
    (Point(center), r)

  /** Alpha values, top dimension first:
    *   - a top-dimensional simplex: `topValue` (its circumradius);
    *   - a lower simplex `σ`: if `σ` is Gabriel -- no vertex of a coface strictly inside its smallest circumsphere --
    *     that sphere's radius, otherwise the smallest value among its immediate cofaces. A Gabriel radius within a
    *     relative `1e-12` of that smallest value is taken to be equal to it (a coface vertex on the sphere: the same
    *     sphere, computed twice), which also keeps the filtration monotone to the last bit.
    *   - a vertex: 0.
    */
  private def valueOf(s: Simplex[Int]): Double =
    val vs = s.toArray
    val k = vs.length - 1
    val row = if k >= 0 && k <= ambientDimension then built._1(k).indexOf(vs) else -1
    if row >= 0 then built._2(k)(row)
    else if isRepeatedPointFace(vs) then 0.0
    else throw new NoSuchElementException(s"key not found: $s")

  // A repeated point (a vertex the triangulation does not have) or the edge joining it to the one that was kept.
  private def isRepeatedPointFace(vs: Array[Int]): Boolean =
    duplicates.nonEmpty && (vs.length match
      case 1 => duplicates.contains(vs(0))
      case 2 => duplicates.get(vs(0)).contains(vs(1)) || duplicates.get(vs(1)).contains(vs(0))
      case _ => false)

  override def filtrationValue: PartialFunction[Simplex[Int], Double] = { case spx => valueOf(spx) }

  // Must be the exact reverse of filtrationOrdering below, not merely "ascending by filtrationValue" --
  // sortBy(filtrationValue) alone has no explicit tie-break (falls back to simplicesMap's own insertion
  // order among ties), which doesn't match filtrationOrdering's simplexOrdering[Int] tie-break. This
  // passes VietorisRipsSpec-style sortedness checks (value-only) but breaks PersistenceInChunksEngine,
  // whose chunk-boundary logic (Homology.scala's PersistenceInChunksEngine.allCells) relies on
  // iterateDimension's own emission order standing in for filtrationOrdering position -- found via
  // EngineComparisonBenchmarkSpec / AlphaFiltrationOrderingRegressionSpec (see CLAUDE.md).
  // Values are read from the face tables once per simplex; only equal values fall through to the full ordering.
  private val sorted: mutable.HashMap[Int, Seq[Simplex[Int]]] = mutable.HashMap.empty
  private def sortedDimension(k: Int): Seq[Simplex[Int]] = synchronized {
    sorted.getOrElseUpdate(
      k, {
        val reverse = filtrationOrdering.reverse
        // The table's values in table order; the repeated points' extras after them have value 0.
        val tableValues = if k <= ambientDimension then built._2(k) else Array.emptyDoubleArray
        val keyed = simplicesOfDimension(k).iterator.zipWithIndex
          .map((s, i) => (if i < tableValues.length then tableValues(i) else 0.0, s))
          .toArray
        Parallel.sort(
          keyed,
          (a: (Double, Simplex[Int]), b: (Double, Simplex[Int])) =>
            java.lang.Double.compare(a._1, b._1) match
              case 0 => reverse.compare(a._2, b._2)
              case c => c
        )
        keyed.toSeq.map(_._2)
      }
    )
  }

  lazy val simplicesSortedMap: Map[Int, Seq[Simplex[Int]]] = (0 to highest).map(k => k -> sortedDimension(k)).toMap

  /** The dimensions straight from the face tables, without a [[Simplex]] per cell: each in the order the cohomology
    * engines take it in, ascending value and, among equal values, ascending lexicographic order (the reverse of the
    * stream's own listing of them). `None` when points are repeated: their extra cells are not in the tables.
    */
  override def flatLayers: Option[Iterator[FlatLayerStream.Layer]] =
    Option.when(duplicates.isEmpty && (0 to highest).forall(built._1(_).size > 0)) {
      (0 to highest).iterator.map { k =>
        val level = built._1(k)
        val width = k + 1
        val vertices = level.allVertices
        val values = built._2(k)
        val order = RipserCofaceSimplexStream.layerOrder(width, values, vertices, colex = false)
        val sortedVertices = new Array[Int](vertices.length)
        val sortedValues = new Array[Double](values.length)
        Parallel.ranges(order.length, DelaunayAlphaShapes.ParallelGrain) { (from, until) =>
          var i = from
          while i < until do
            System.arraycopy(vertices, order(i) * width, sortedVertices, i * width, width)
            sortedValues(i) = values(order(i))
            i += 1
        }
        FlatLayerStream.Layer(k, sortedVertices, sortedValues)
      }
    }

  def simplicesInDimension(d: Int): Iterator[Simplex[Int]] =
    if d < 0 || d > highest then throw new NoSuchElementException(s"key not found: $d") else sortedDimension(d).iterator

  def simplices(): Iterator[Simplex[Int]] = (0 to highest).iterator.flatMap(simplicesInDimension)

  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if d >= 0 && d <= highest => sortedDimension(d).iterator
  }

  // The shared FiltrationOrdering.canonical shape: fv reversed, then dimension, then simplexOrdering.
  // `simplicesSortedMap` (above) is built as `.sorted(using filtrationOrdering.reverse)` specifically so it
  // stays the exact reverse of this ordering, tie-break included -- see RecursiveStackVietorisRipsSimplexStream's
  // identical fix (VietorisRips.scala) and CLAUDE.md for the full root-cause writeup. This used to have no
  // dimension key at all (`Ordering.by(filtrationValue).reverse.orElse(simplexOrdering[Int])`), which is wrong
  // for the cross-dimension comparisons `Chain.reduceBy` performs during reduction (see the identical fix's own
  // comment). Kept a `def`, not a `val`: `simplicesSortedMap` above uses it, and a `val` declared this late in the
  // class body could be read before it is initialized (see CLAUDE.md's `ExplicitStreamBuilder` NPE note).
  override def filtrationOrdering: Ordering[Simplex[Int]] =
    FiltrationOrdering.canonical(filtrationValue, _.size, simplexOrdering[Int])

/** The Delaunay triangulation of a point cloud, built by an incremental frontier walk
  * (https://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=10917453), filtered as an alpha complex.
  *
  * The walk starts from a hull facet found by gift wrapping and, across each frontier facet, takes the point whose
  * sphere through the facet is met first: one pass over the points per facet. Points that are exactly cospherical
  * (grids) are tiled as a cluster. Every result is checked cheaply (each point a vertex, no facet in three top
  * simplices, every boundary facet on the convex hull); a result that fails is re-triangulated from slightly perturbed
  * points, with every radius recomputed from the original coordinates. The perturbation (1e-4 of the point spacing)
  * decides how exact ties are broken, and can also flip a near-tie closer than that. The same `pts` and `seed` always
  * give the same triangulation.
  *
  * Limitation: a very small `Epsilon` (far below the default `1e-5`) can leave an exactly degenerate input (a 3-D grid)
  * with an invalid triangulation when the perturbed retriangulation does not converge.
  *
  * @param pts
  *   the points to triangulate
  * @param seed
  *   seeds the perturbation used when a triangulation has to be repaired; general-position input does not depend on it.
  * @param requireValidTriangulation
  *   off by default. When on, the triangulation is also checked for cavities (a homology computation), and a result
  *   that cannot be repaired raises an exception instead of being returned as it is.
  */
open class HelixDelaunay(pts: Array[Array[Double]], seed: Long = 0L, requireValidTriangulation: Boolean = false)(using
  factor: EpsilonFactor
) extends DelaunayAlphaShapes(using factor.forPoints(pts)):
  private given epsilon: Epsilon = factor.forPoints(pts)
  // If `pts` is globally coplanar -- its own affine rank is strictly less than the declared ambient dimension
  // (the array width) -- there is no genuine full-ambient-dimensional Delaunay simplex to find at all: every
  // point lies in some lower-dimensional flat, so the bootstrap's search for a supporting hyperplane plus one
  // more "inside" point can never succeed no matter which candidate subset it tries (a structurally different
  // failure from a merely locally-bad subset choice -- no amount of subset selection fixes it). Rather than let
  // that surface as a crash, project onto an orthonormal basis of the point set's own actual affine span first
  // and run the whole construction there -- a no-op (identity, modulo re-centering) whenever the input already
  // has full rank, and exact (not approximate) when it doesn't: an orthogonal projection onto the affine span
  // containing every point changes no pairwise Euclidean distance among them, so the resulting triangulation is
  // the genuine Delaunay triangulation of the true (lower-dimensional) point configuration, not an approximation
  // of it. `ambientDimension` (below, via `builder`) then correctly reflects the data's own true dimensionality
  // -- e.g. 2D points accidentally stored with a spurious constant third coordinate behave exactly like a 2D
  // alpha complex, not a degenerate "3D" one. See `.claude/WORKLOG-helix-bootstrap-fix.md`.
  private val reducedPts: Array[Array[Double]] = HelixDelaunay.projectToAffineRank(pts)
  private val builder = HelixDelaunayBuilder(reducedPts, seed)
  private var _sphereScanFallbacks = 0

  /** Frontier facets where the walk fell back to its slow candidate search (diagnostics and tests). */
  def sphereScanFallbacks: Int = _sphereScanFallbacks
  val points: Seq[Point] = builder.points
  override val metricSpace: EuclideanMetricSpace = EuclideanMetricSpace(reducedPts)
  val ambientDimension: Int = builder.ambientDimension
  val validated: Set[DelaunaySimplex] =
    val raw = builder.compute()
    _sphereScanFallbacks = builder.sphereScanFallbacks
    // Tiling exactly cospherical clusters one at a time can leave gaps (adjacent clusters may split a shared face along
    // different diagonals; an exact grid in 3-D is the standard case). So every walk gets the cheap structural checks
    // of `looksValid` even when the caller did not ask, and the full check and jitter repair only if they fail; a
    // generic cloud passes and is returned as walked. Unasked, a repair that fails returns the raw walk rather than
    // throwing (`.claude/WORKLOG-helix-construction-speed.md`).
    if requireValidTriangulation then HelixDelaunay.repairByJitterRetriangulation(reducedPts, raw, seed)
    else if !HelixDelaunay.looksValid(raw, builder.points) then
      try HelixDelaunay.repairByJitterRetriangulation(reducedPts, raw, seed)
      catch case _: IllegalStateException => raw // the repair did not converge; anything else is a bug and propagates
    else raw

  protected def topSimplices: Iterable[Simplex[Int]] = validated.map(_.simplex)

  // The smallest circumradius among the Delaunay simplices containing a top-sized simplex: its own, unless a cospherical
  // cluster was tiled as one larger cell. A table with one entry per (ambient + 1)-subset of each cell, so a lookup is one
  // hash probe: scanning the cells around the simplex's first vertex (and building two sets per cell) made the faces
  // quadratic in the number of cells, ~75% of the time at n = 40 points in R^7 (`.claude/WORKLOG-helix-alpha-speed.md`).
  private lazy val topRadius: mutable.HashMap[Simplex[Int], Double] =
    HelixDelaunay.smallestCircumradii(validated, ambientDimension)
  protected def topValue(s: Simplex[Int]): Double = topRadius(s)

object HelixDelaunay:

  /** For every `(top + 1)`-subset of a cell, the smallest circumradius among the cells that contain it: a cell of
    * exactly `top + 1` vertices gives its own radius to itself, a larger cell (a cospherical cluster taken whole) gives
    * its radius to each of its subsets, and a simplex that several cells claim keeps the smallest.
    */
  def smallestCircumradii(cells: Iterable[DelaunaySimplex], top: Int): mutable.HashMap[Simplex[Int], Double] =
    val radii = mutable.HashMap.empty[Simplex[Int], Double]
    cells.foreach { cell =>
      val radius = cell.circumsphere.radius
      val subsets =
        if cell.simplex.size == top + 1 then Iterator.single(cell.simplex)
        else cell.simplex.toArray.combinations(top + 1).map(c => Simplex.from(c.toSeq))
      subsets.foreach(s => radii.updateWith(s)(old => Some(old.fold(radius)(math.min(_, radius)))))
    }
    radii

  /** Points whose affine span has lower dimension than their coordinates, re-expressed in an orthonormal basis of that
    * span (distances are unchanged, so the triangulation is the true one); other point sets are returned as they are.
    */
  def projectToAffineRank(pts: Array[Array[Double]])(using epsilon: Epsilon): Array[Array[Double]] =
    if pts.length < 2 then pts
    else
      val dim = pts.head.length
      val base = pts.head
      val centered = pts.map(p => p.zip(base).map(_ - _))
      val matrix = DenseMatrix(centered)
      val rank = rankAtEpsilon(matrix)
      if rank >= dim then pts
      else
        val v = matrix.svd.v
        centered.map { row =>
          Array.tabulate(rank)(k => (0 until dim).map(j => row(j) * v(j, k)).sum)
        }

  private def facetsOf(ds: DelaunaySimplex): Seq[Simplex[Int]] = ds.simplex.toSeq.toSeq.map(v => ds.simplex - v)

  private def facetToSimplices(simps: Set[DelaunaySimplex]): Map[Simplex[Int], Vector[DelaunaySimplex]] =
    simps.toVector
      .flatMap(ds => facetsOf(ds).map(f => (f, ds)))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toVector)
      .toMap

  /** Cheap structural checks that a top-simplex set triangulates the convex hull of `points`: every point is a vertex,
    * no facet lies in more than two top simplices, and every facet in exactly one lies on the hull (all points on one
    * side of it). One pass over the points per boundary facet. A torn or partial walk fails the last.
    */
  def looksValid(simps: Set[DelaunaySimplex], points: Seq[Point])(using epsilon: Epsilon): Boolean =
    def onHull(facet: Simplex[Int]): Boolean =
      val plane = Hyperplane.from(facet.toSeq.map(points))
      var above = false
      var below = false
      val it = points.iterator
      while !(above && below) && it.hasNext do
        val s = plane.dist(it.next())
        if s > epsilon.epsilon then above = true
        if s < -epsilon.epsilon then below = true
      !(above && below)
    // The simplices with exactly `dimension + 1` vertices (all but the tiled cospherical clusters) are counted on flat
    // tables; a facet of a larger cell has more vertices than theirs, so the two kinds never share a facet.
    val dimension = points.headOption.fold(0)(_.size)
    val regular = DelaunayAlphaShapes.FaceTable(dimension + 1, simps.size)
    val clusters = mutable.ArrayBuffer.empty[Simplex[Int]]
    val seen = new Array[Boolean](points.size)
    var seenCount = 0
    simps.foreach { ds =>
      val vs = ds.simplex.toArray
      vs.foreach { v =>
        if !seen(v) then
          seen(v) = true
          seenCount += 1
      }
      if vs.length == dimension + 1 then regular.add(vs) else clusters += ds.simplex
    }
    lazy val regularFacets: Boolean =
      regular.size == 0 || {
        val (facets, facetOf) = DelaunayAlphaShapes.FaceTable.facesOf(regular)
        val counts = new Array[Int](facets.size)
        facetOf.foreach(f => counts(f) += 1)
        counts.forall(_ <= 2) &&
        counts.indices.forall(f =>
          counts(f) != 1 || onHull(Simplex.from(Seq.tabulate(dimension)(i => facets.vertex(f, i))))
        )
      }
    lazy val clusterFacets: Boolean =
      clusters.isEmpty || {
        val counts = clusters.toSeq.flatMap(t => t.toSeq.map(v => t - v)).groupMapReduce(identity)(_ => 1)(_ + _)
        counts.values.forall(_ <= 2) && counts.iterator.filter(_._2 == 1).forall((f, _) => onHull(f))
      }
    seenCount == points.size && regularFacets && clusterFacets

  /** The facets that more than two top simplices claim (a torn triangulation), with their claimants. */
  def badFacetsOf(simps: Set[DelaunaySimplex]): Map[Simplex[Int], Vector[DelaunaySimplex]] =
    // Distinct simplices: a cospherical tiling can record a simplex the walk also found, with the cluster's sphere
    // instead of its own; that is one simplex, not a third claimant.
    facetToSimplices(simps).filter { case (_, claimants) => claimants.map(_.simplex).distinct.size > 2 }

  /** A Delaunay triangulation fills its convex hull, so the whole complex has no homology in degree `d - 1`. `None` if
    * that holds; otherwise the vertices of the essential degree-`(d - 1)` representatives, the boundary of the hole,
    * which seed the repair. (A facet with one coface can be a missing simplex rather than a hull facet; only this
    * global check sees it.)
    */
  def interiorVoidVertices(simps: Set[DelaunaySimplex], ambientDimension: Int): Option[Set[Int]] =
    given Double is Field = Field.DoubleApproximated(1e-9)
    val builder = ExplicitStreamBuilder[Int, Double]()
    simps
      .flatMap(ds => ds.simplex.toSet.subsets().filter(_.nonEmpty).map(s => Simplex.from(s.toSeq)))
      .foreach(s => builder.addOne((0.0, s)))
    val bars = SimplicialHomologyEngine[Int, Double, Double]()
      .persistentHomology(builder.result())
      .barcodeAt(Double.PositiveInfinity)
    val voidBars = bars.filter { b =>
      b.dim == ambientDimension - 1 && (b.upper match
        case PositiveInfinity() => true
        case _                  => false)
    }
    if voidBars.isEmpty then None
    else Some(voidBars.flatMap(_.annotation).flatMap(_.rawEntries.map(_._1)).flatMap(_.toSet).toSet)

  /** The repair of `requireValidTriangulation = true`: move the vertices involved in a facet with too many cofaces, or
    * on the boundary of a hole ([[interiorVoidVertices]]), by a small random amount; triangulate the whole point set
    * again; recompute every circumsphere from the original coordinates. Retried with a fresh seed and a widened set of
    * moved points while either problem remains, up to `maxAttempts`, then an exception: never an unrepaired result.
    * Runs on the unrepaired input too, since a near tie between two valid cofaces of one facet can leave a hole with no
    * facet violation at all.
    */
  private def repairByJitterRetriangulation(
    pts: Array[Array[Double]],
    validatedIn: Set[DelaunaySimplex],
    seed: Long
  )(using epsilon: Epsilon): Set[DelaunaySimplex] =
    val originalPoints = pts.toIndexedSeq.map(Point(_))
    val initialBad = badFacetsOf(validatedIn)
    val initialVoid = interiorVoidVertices(validatedIn, pts.head.length)
    val structurallyValid = looksValid(validatedIn, originalPoints)
    if initialBad.isEmpty && initialVoid.isEmpty && structurallyValid then validatedIn
    else
      // A torn or partial walk (failing the structural check) has no reliable local culprit -- the parts left exactly
      // degenerate tear again -- so then every point is jittered; otherwise the vertices of the bad facets and voids.
      val culprits = initialBad.values.flatten.toSet.flatMap(_.simplex.toSet) ++ initialVoid.getOrElse(Set.empty)
      var jitterVertices: Set[Int] = if !structurallyValid || culprits.isEmpty then pts.indices.toSet else culprits
      var result: Option[Set[DelaunaySimplex]] = None
      var attempt = 0
      val maxAttempts = 8
      while result.isEmpty && attempt < maxAttempts do
        val localSeed = seed * 1000003L + attempt + 1
        val rng = new Random(localSeed)
        val jitterPoints = jitterVertices.toVector.map(i => Point(pts(i)))
        val minPairwiseSpacing =
          if jitterPoints.size < 2 then 1.0
          else
            (for
              i <- jitterPoints.indices
              j <- (i + 1) until jitterPoints.size
            yield jitterPoints(i).distance(jitterPoints(j))).min
        // Large enough that the jittered points are comfortably in general position: at 1e-6 of the spacing, a jittered
        // row of grid points is collinear to 1e-6 and its facets' circumcentres are too ill-conditioned for the walk.
        // The jitter only breaks ties; every value is recomputed from the original coordinates below.
        val jitterMagnitude = math.max(epsilon.epsilon * 100, minPairwiseSpacing * 1e-4)
        val perturbedPts: Array[Array[Double]] = pts.zipWithIndex.map { case (p, i) =>
          if jitterVertices.contains(i) then p.map(_ + (rng.nextDouble() - 0.5) * 2 * jitterMagnitude)
          else p
        }

        val perturbedValidated = HelixDelaunayBuilder(perturbedPts, localSeed).compute()
        val realized: Set[DelaunaySimplex] = perturbedValidated.map { ds =>
          DelaunaySimplex(ds.simplex, Hypersphere(ds.simplex.toSeq.toSeq.map(v => Point(pts(v)))))
        }
        val stillBad = badFacetsOf(realized)
        val stillVoid = interiorVoidVertices(realized, pts.head.length)
        val stillStructural = looksValid(realized, originalPoints)
        if stillBad.isEmpty && stillVoid.isEmpty && stillStructural then result = Some(realized)
        else if !stillStructural then jitterVertices = pts.indices.toSet
        else
          // `stillVoid.getOrElse` never falls through to a default here: reaching this `else` branch at all
          // means `stillBad.nonEmpty || stillVoid.nonEmpty`, so whenever `stillVoid` is `None` (no void),
          // `stillBad` is guaranteed nonempty and already contributes its own vertices below -- there is no
          // "void present but its own representative already fully jittered" case to special-case separately,
          // the essential representative's own vertex support IS the widened jitter set whenever a void persists.
          val extraJitterVertices =
            stillBad.values.flatten.toSet.flatMap(_.simplex.toSet) ++ stillVoid.getOrElse(Set.empty)
          jitterVertices = jitterVertices ++ extraJitterVertices
        attempt += 1

      result.getOrElse(
        throw new IllegalStateException(
          s"HelixDelaunay.repairByJitterRetriangulation: could not resolve the facet-multiplicity violation " +
            s"after $maxAttempts jitter attempts on vertex set $jitterVertices; the point cloud may have a " +
            "degeneracy this repair does not handle. Please report it with the point cloud; the DQP backend " +
            "(AlphaBackend.DQP; alphaBackend=DQP in MATLAB, --alpha-backend DQP on the command line) avoids the problem."
        )
      )

/** The simplices of `full` with alpha value at most `maxRadius`, in `full`'s order and with its values. Alpha values do
  * not decrease from a face to a coface, so this is a subcomplex.
  */
open class RadiusLimitedAlphaShapes(full: AlphaShapes, maxRadius: Double) extends AlphaShapes with FlatLayerStream:
  override val metricSpace: FiniteMetricSpace[Int] = full.metricSpace

  // The layers of `full` cut after the last cell of value at most `maxRadius` (a layer is ascending in value).
  override def flatLayers: Option[Iterator[FlatLayerStream.Layer]] = full match
    case flat: FlatLayerStream =>
      flat.flatLayers.map(_.map { layer =>
        var (low, high) = (0, layer.size)
        while low < high do
          val mid = (low + high) >>> 1
          if layer.values(mid) <= maxRadius then low = mid + 1 else high = mid
        FlatLayerStream.Layer(
          layer.dim,
          java.util.Arrays.copyOf(layer.vertices, low * (layer.dim + 1)),
          java.util.Arrays.copyOf(layer.values, low)
        )
      }.takeWhile(_.size > 0))
    case _ => None
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if full.iterateDimension.isDefinedAt(d) =>
      full.iterateDimension(d).filter(full.filtrationValue(_) <= maxRadius)
  }
  override def filtrationOrdering: Ordering[Simplex[Int]] = full.filtrationOrdering
  override def filtrationValue: PartialFunction[Simplex[Int], Double] = full.filtrationValue

/** `helix` without its simplices of dimension greater than `maxDim`: what the fast alpha engine hands the chunks engine
  * for the degrees between 0 and the top. Filtration values and order are those of `helix`.
  */
open class LimitedAlphaShapesStream(helix: DelaunayAlphaShapes, maxDim: Int)
    extends LevelwiseSimplexStream[Int, Double]
    with DoubleFiltration[Simplex[Int]]():
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d: Int if d >= 0 && d <= maxDim && helix.iterateDimension.isDefinedAt(d) => helix.iterateDimension(d)
  }
  override def filtrationOrdering: Ordering[Simplex[Int]] = helix.filtrationOrdering
  override def filtrationValue: PartialFunction[Simplex[Int], Double] = helix.filtrationValue
  // Cells up to dimension maxDim: degrees above maxDim - 1 are truncation artifacts.
  override def homologyDegreeLimit: Option[Int] = Some(maxDim - 1)

object DelaunayAlphaShapes:
  /** Faces handled by one task when the flat tables are filled in parallel (about 100 microseconds of work). */
  private[tda4j] val ParallelGrain = 512

  /** The simplices of one dimension, each a row of `width` sorted vertex indices, numbered as first added. */
  class FaceTable(val width: Int, expected: Int):
    private var rows = new Array[Int](math.max(expected, 4) * width)
    var size = 0
    // The slots of an open-addressing table: -1 or a row. One table, grown as rows are added, unless the table was made
    // by `facesOf`: then `1 << partitionBits` tables of a fixed size, a face in the one its hash's top bits select.
    private var tables = Array(Array.fill(Integer.highestOneBit(math.max(expected, 4) * 2) * 2)(-1))
    private var partitionBits = 0

    def vertex(row: Int, i: Int): Int = rows(row * width + i)

    /** The vertices of rows `0 until size`, `width` of each, in row order. */
    def allVertices: Array[Int] = java.util.Arrays.copyOf(rows, size * width)

    // The rows and tables that `facesOf` made, in place of the empty ones.
    private def install(newRows: Array[Int], newSize: Int, newTables: Array[Array[Int]], bits: Int): Unit =
      rows = newRows
      size = newSize
      tables = newTables
      partitionBits = bits

    private def hash(vs: Array[Int], at: Int): Int =
      var h = 0x9e3779b9
      for i <- 0 until width do h = (h ^ vs(at + i)) * 0x01000193 + (h >>> 15)
      h ^ (h >>> 16)

    private def tableOf(h: Int): Array[Int] =
      if partitionBits == 0 then tables(0) else tables(h >>> (32 - partitionBits))

    private def sameRow(row: Int, vs: Array[Int]): Boolean =
      var i = 0
      while i < width && rows(row * width + i) == vs(i) do i += 1
      i == width

    /** The row of the sorted vertices `vs`, or -1 if there is none. */
    def indexOf(vs: Array[Int]): Int =
      if vs.length != width then -1
      else
        val h = hash(vs, 0)
        val slots = tableOf(h)
        val mask = slots.length - 1
        var s = h & mask
        while slots(s) >= 0 && !sameRow(slots(s), vs) do s = (s + 1) & mask
        slots(s)

    /** The row of the sorted vertices `vs`, and whether it is new. Only for a table that was not made by `facesOf`. */
    def add(vs: Array[Int]): (Int, Boolean) =
      require(partitionBits == 0, "FaceTable.add: a table made by facesOf is complete")
      if size * 2 >= tables(0).length then grow()
      val slots = tables(0)
      val mask = slots.length - 1
      var s = hash(vs, 0) & mask
      while slots(s) >= 0 && !sameRow(slots(s), vs) do s = (s + 1) & mask
      if slots(s) >= 0 then (slots(s), false)
      else
        if (size + 1) * width > rows.length then rows = java.util.Arrays.copyOf(rows, rows.length * 2)
        System.arraycopy(vs, 0, rows, size * width, width)
        slots(s) = size
        size += 1
        (size - 1, true)

    private def grow(): Unit =
      val slots = Array.fill(tables(0).length * 2)(-1)
      val mask = slots.length - 1
      for row <- 0 until size do
        var s = hash(rows, row * width) & mask
        while slots(s) >= 0 do s = (s + 1) & mask
        slots(s) = row
      tables = Array(slots)

  object FaceTable:
    /** Below this many (face, coface) pairs `facesOf` adds them one at a time: sharing the work costs more than it
      * saves.
      */
    private val ParallelPairs = 1 << 16

    /** The faces of the rows of `upper` (each a sorted `width`-subset), numbered in the order in which they first occur
      * when the rows are taken in order and, in each row, the vertex omitted runs from the first to the last; and for
      * each of the `upper.size * upper.width` (row, omitted vertex) pairs, in that order, the row of its face. The
      * result is the same however many threads share the work: the faces are sorted into the partitions of a hash by
      * the thread that owns the partition, which finds the first occurrence of each of its faces, and the numbers are
      * the ranks of those first occurrences.
      */
    def facesOf(upper: FaceTable): (FaceTable, Array[Int]) =
      val w = upper.width
      val width = w - 1
      val pairs = upper.size * w
      val rowOf = new Array[Int](pairs)
      val threads = Parallel.threads
      if threads == 1 || pairs < ParallelPairs then
        val faces = FaceTable(width, upper.size * 2)
        val face = new Array[Int](width)
        var p = 0
        while p < pairs do
          val t = p / w
          val drop = p % w
          var j = 0
          for i <- 0 until w if i != drop do
            face(j) = upper.vertex(t, i)
            j += 1
          rowOf(p) = faces.add(face)._1
          p += 1
        (faces, rowOf)
      else
        val rows = upper.rows
        // The hash `hash` gives to the face of a pair, without making the face.
        val hashes = new Array[Int](pairs)
        Parallel.ranges(upper.size, 512) { (from, until) =>
          var t = from
          while t < until do
            var drop = 0
            while drop < w do
              var h = 0x9e3779b9
              var i = 0
              while i < w do
                if i != drop then h = (h ^ rows(t * w + i)) * 0x01000193 + (h >>> 15)
                i += 1
              hashes(t * w + drop) = h ^ (h >>> 16)
              drop += 1
            t += 1
        }
        def sameFace(p: Int, q: Int): Boolean =
          val (tp, dp, tq, dq) = (p / w, p % w, q / w, q % w)
          var i = 0
          var j = 0
          var same = true
          while same && i < w do
            if i == dp then i += 1
            else if j == dq then j += 1
            else
              same = rows(tp * w + i) == rows(tq * w + j)
              i += 1
              j += 1
          same
        val bits = math.max(1, 32 - Integer.numberOfLeadingZeros(threads - 1))
        val partitions = 1 << bits
        val firstOf = rowOf // reused: first the first occurrence of each pair's face, then the row
        val tables = new Array[Array[Int]](partitions)
        Parallel.foreach((0 until partitions).toVector) { part =>
          val slots = Array.fill(Integer.highestOneBit(math.max(pairs / partitions, 4)) * 4)(-1)
          val mask = slots.length - 1
          var used = 0
          var p = 0
          while p < pairs do
            val h = hashes(p)
            if (h >>> (32 - bits)) == part then
              var s = h & mask
              while slots(s) >= 0 && !sameFace(slots(s), p) do s = (s + 1) & mask
              if slots(s) < 0 then
                slots(s) = p
                used += 1
                // Far more faces in one partition than a hash gives: the table would fill and the probe never end.
                if used > slots.length / 4 * 3 then throw new IllegalStateException("FaceTable.facesOf: partition full")
              firstOf(p) = slots(s)
            p += 1
          tables(part) = slots
        }
        // The rank of each first occurrence among the first occurrences: the row of its face.
        val rank = new Array[Int](pairs)
        var count = 0
        var p = 0
        while p < pairs do
          if firstOf(p) == p then
            rank(p) = count
            count += 1
          p += 1
        val faceRows = new Array[Int](count * width)
        Parallel.ranges(pairs, 4096) { (from, until) =>
          var p = from
          while p < until do
            val first = firstOf(p)
            if first == p then
              val (t, drop) = (p / w, p % w)
              var j = 0
              var i = 0
              while i < w do
                if i != drop then
                  faceRows(rank(p) * width + j) = rows(t * w + i)
                  j += 1
                i += 1
            p += 1
        }
        Parallel.ranges(pairs, 4096) { (from, until) =>
          var p = from
          while p < until do
            firstOf(p) = rank(firstOf(p))
            p += 1
        }
        Parallel.foreach(tables.toVector) { slots =>
          var s = 0
          while s < slots.length do
            if slots(s) >= 0 then slots(s) = rank(slots(s))
            s += 1
        }
        val faces = FaceTable(width, 0)
        faces.install(faceRows, count, tables, bits)
        (faces, rowOf)

  /** The smallest sphere through the points `vs` (indices into `coords`): its centre, in their affine hull, is written
    * to `center` from `at`, and its radius returned. With `D` the matrix whose columns are the edge vectors `p_i - p0`
    * and `D = Q R` its Householder QR factorization, the centre is `p0 + Q y` with `R^T y = |p_i - p0|^2 / 2`. (The
    * Gram system `D^T D` squares the condition number, which on slivers loses about half the digits.)
    */
  def circumsphere(coords: Array[Array[Double]], vs: Array[Int], center: Array[Double], at: Int): Double =
    val p0 = coords(vs(0))
    val dim = p0.length
    val k = vs.length - 1
    if k == 0 then
      System.arraycopy(p0, 0, center, at, dim)
      0.0
    else
      // a(c)(r): column c of D, overwritten by R above the diagonal and the reflector below.
      // Plain loops: this runs once per face of the triangulation (millions in 9-D), and the `tabulate`/`map`/`sum`
      // closures box a Double per coordinate. The sums are taken in the same order, so the values are bit-for-bit the same.
      val a = new Array[Array[Double]](k)
      val rhs = new Array[Double](k)
      for c <- 0 until k do
        val q = coords(vs(c + 1))
        val col = new Array[Double](dim)
        var sq = 0.0
        for r <- 0 until dim do
          val x = q(r) - p0(r)
          col(r) = x
          sq += x * x
        a(c) = col
        rhs(c) = sq / 2
      val diag = new Array[Double](k)
      val reflectors = new Array[Array[Double]](k)
      for c <- 0 until k do
        val col = a(c)
        var norm = 0.0
        for r <- c until dim do norm += col(r) * col(r)
        norm = math.sqrt(norm)
        val alpha = if col(c) > 0 then -norm else norm
        val v = new Array[Double](dim)
        for r <- c until dim do v(r) = col(r)
        v(c) -= alpha
        var vn = 0.0
        for r <- c until dim do vn += v(r) * v(r)
        if vn > 0 then
          for c2 <- c + 1 until k do
            var dot = 0.0
            for r <- c until dim do dot += v(r) * a(c2)(r)
            val f = 2 * dot / vn
            for r <- c until dim do a(c2)(r) -= f * v(r)
        reflectors(c) = v
        diag(c) = alpha
      // Forward substitution for R^T y = rhs; R(j, i) = a(i)(j) for j < i, R(i, i) = diag(i).
      val z = new Array[Double](dim)
      for i <- 0 until k do
        var acc = rhs(i)
        for j <- 0 until i do acc -= a(i)(j) * z(j)
        z(i) = acc / diag(i)
      // offset = Q (y, 0): apply the reflectors in reverse order.
      for c <- (k - 1) to 0 by -1 do
        val v = reflectors(c)
        var vn = 0.0
        var dot = 0.0
        for r <- c until dim do
          vn += v(r) * v(r)
          dot += v(r) * z(r)
        if vn > 0 then
          val f = 2 * dot / vn
          for r <- c until dim do z(r) -= f * v(r)
      var r2 = 0.0
      for r <- 0 until dim do
        center(at + r) = p0(r) + z(r)
        r2 += z(r) * z(r)
      math.sqrt(r2)
