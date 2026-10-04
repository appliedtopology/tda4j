package org.appliedtopology.tda4j

import collection.mutable
import scala.math.{pow, sqrt}
import org.apache.commons.math3.linear.{
  LUDecomposition,
  MatrixUtils,
  RealMatrix,
  RealVector,
  SingularValueDecomposition
}
import org.apache.commons.math3.linear.MatrixUtils.createRealVector
import scala.util.Random
import scala.util.chaining.*

import java.util.concurrent.*

/** The numerical tolerance of the triangulation's geometric tests (near-zero singular values and circumsphere margins).
  * `AlphaShapes(...)` supplies `Epsilon(1e-5)`; pass a `given Epsilon` to change it.
  */
final case class Epsilon(epsilon: Double)

/** Which alpha-complex construction `AlphaShapes(points, backend)` uses: `Helix` (Delaunay by the Helix algorithm, the
  * default), `DQP` (dual quadratic programs), or `Default` (whatever the library currently picks -- Helix).
  */
enum AlphaBackend:
  case Default, Helix, DQP

object AlphaBackend:
  /** The facade's string spelling (`"default"`, `"helix"`, `"dqp"`, any case). */
  def parse(name: String): AlphaBackend = name.toLowerCase match
    case "default" => Default
    case "helix"   => Helix
    case "dqp"     => DQP
    case other     =>
      throw IllegalArgumentException(s"Unknown alpha complex backend: '$other' (expected default/helix/DQP)")

abstract class AlphaShapes extends LevelwiseSimplexStream[Int, Double]() with DoubleFiltration[Simplex[Int]]():
  val metricSpace: FiniteMetricSpace[Int]

/** Alpha complexes of point clouds: `AlphaShapes(points)`, or `Persistence(points, complex = AlphaShapes)`. */
object AlphaShapes extends PointCloudComplex:
  def fromPoints(points: PointCloud, maxDimension: Int, maxFiltrationValue: Option[Double]) =
    Truncated(apply(points, maxRadius = maxFiltrationValue, maxDimension = maxDimension), maxDimension)

  /** The alpha complex of `points`, built by `backend` (see [[AlphaBackend]]).
    *
    *   - `Helix` triangulates the whole point set (Delaunay) and reads the alpha values off the triangulation.
    *   - `DQP` decides each simplex on its own, dimension by dimension, among points within `2 maxRadius` of each
    *     other: it never builds the whole triangulation, so a small `maxRadius` (or a low `maxDimension` in high
    *     ambient dimension) makes it much cheaper, while without a radius it is far slower than Helix.
    *   - `Default` picks between them: Helix without a `maxRadius`, otherwise whichever is expected to be faster for
    *     this radius, from the average number of points within `2 maxRadius` of a point (`AlphaShapes.prefersDQP`).
    *
    * In general position every backend gives the same complex: the simplices whose alpha value (radius) is at most
    * `maxRadius`, with the same values. On cospherical points DQP keeps the higher-dimensional simplex they span where
    * Helix triangulates it; the barcode is the same up to zero-length bars.
    *
    * @param maxRadius
    *   keep only simplices with alpha value (radius) at most this. Default: no limit.
    * @param maxDimension
    *   the highest homological degree needed: `DQP` builds simplices up to dimension `maxDimension + 1` only. Default:
    *   all dimensions. (Helix always builds every dimension; truncate the stream with [[Truncated]].)
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
    epsilon: Epsilon = Epsilon(1e-5)
  ): AlphaShapes =
    val radius = maxRadius.toOption.filter(_.isFinite)
    backend match
      case AlphaBackend.Default =>
        val chosen =
          if points.size > 0 && radius.exists(r => !requireValidTriangulation && prefersDQP(points, r))
          then AlphaBackend.DQP
          else AlphaBackend.Helix
        apply(points, chosen, requireValidTriangulation, maxRadius, maxDimension)
      case AlphaBackend.Helix =>
        val helix = HelixDelaunay(points.points, requireValidTriangulation = requireValidTriangulation)
        radius.fold[AlphaShapes](helix)(r => RadiusLimitedAlphaShapes(helix, r))
      case AlphaBackend.DQP =>
        require(
          !requireValidTriangulation,
          "requireValidTriangulation=true is not valid for backend = AlphaBackend.DQP: AlphaShapeDQP has no facet-" +
            "multiplicity precondition to repair (FastAlphaHomologyEngine is specialized to HelixDelaunay's own " +
            "triangulation and never consumes AlphaShapeDQP's output) -- this option would be a silent no-op there."
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

  /** Whether `DQP` is expected to build the alpha complex truncated at radius `r` faster than `Helix` builds the whole
    * one. DQP's cost grows with the number of points within `2r` of each point (its neighbour graph); Helix's with the
    * point count and the ambient dimension. The thresholds are measured
    * (`.claude/WORKLOG-helix-construction-speed.md`): the largest average neighbour count at which DQP was still
    * faster, by point count and ambient dimension.
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
  private[tda4j] def meanNeighbourCost(pts: Array[Array[Double]], distance: Double): Double =
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

  /** The average neighbour count (within `2r`) up to which DQP is expected to be faster than Helix, for `n` points in
    * ambient dimension `d`. Per point, DQP took about `c_d k^1.6` ms (`c_d = 0.004 * 2.35^(d-2)`, the constant from
    * 1000-point clouds, the more expensive ones) and Helix about `h_d (n/1000)^0.4` ms, `h_d` = 0.53, 1.7, 10, 150 for
    * `d` = 2..5 (uniform clouds of 500-10000 points, this library's own measurements). In dimension 6 and up Helix's
    * triangulation grows so fast that DQP is preferred whenever a radius is given.
    */
  private[tda4j] def dqpNeighbourThreshold(n: Int, d: Int): Double =
    val helixPerPoint = math.max(d, 2) match
      case 2 => 0.53
      case 3 => 1.7
      case 4 => 10.0
      case 5 => 150.0
      case _ => Double.PositiveInfinity
    val dqpConstant = 0.004 * math.pow(2.35, math.max(d, 2) - 2)
    math.pow(helixPerPoint * math.pow(n / 1000.0, 0.4) / dqpConstant, 1 / 1.6)

  // utilities for Delaunay computations
  type Point = RealVector
  object Point:
    def apply(coords: Array[Double]): Point = MatrixUtils.createRealVector(coords)

import AlphaShapes.Point

case class Hyperplane(normal: Point, offset: Double):
  def isLight(point: Point): Boolean = normal.dotProduct(point) >= offset

  def dist(point: Point): Double = normal.dotProduct(point) - offset

  def reverse: Hyperplane = Hyperplane(normal.mapMultiply(-1.0), -offset)

object Hyperplane:
  def from(pointSet: Seq[Point])(using epsilon: Epsilon): Hyperplane =
    assert(pointSet.size >= pointSet.head.getDimension)
    val points = pointSet.map(p => p.subtract(pointSet.head))
    // now we need to find a basis of orthogonal complement to the space spanned by these
    val A = MatrixUtils.createRealMatrix(points.toSeq.map(_.toArray).toArray)
    val svdA = SingularValueDecomposition(A.transpose)
    val nullSV = svdA.getSingularValues.zipWithIndex.filter(_._1.abs < epsilon.epsilon)
    val n =
      nullSV.map((s, i) => svdA.getU.getColumnVector(i)).head // should leave out head if we want higher codimensions
    val offset = n.dotProduct(pointSet.head)
    Hyperplane(n, offset)

case class Hypersphere(center: Point, radius: Double)(using epsilon: Epsilon):
  def contains(point: Point): Boolean = point.subtract(center).getNorm < radius - epsilon.epsilon

  def onSphere(point: Point): Boolean = math.abs(point.subtract(center).getNorm - radius) < epsilon.epsilon

object Hypersphere:
  def apply(pointSet: Seq[Point])(using epsilon: Epsilon): Hypersphere =
    val vvs = for
      v1 <- pointSet.indices
      v2 <- pointSet.indices
      if v1 < v2
    yield (pointSet(v1).subtract(pointSet(v2)), math.pow(pointSet(v1).getNorm, 2) - math.pow(pointSet(v2).getNorm, 2))
    val V = MatrixUtils.createRealMatrix(vvs.map(_._1.toArray).toArray)
    val b = MatrixUtils.createRealVector(vvs.map(_._2).toArray).mapMultiply(0.5)
    val c = SingularValueDecomposition(V).getSolver.solve(b)
    new Hypersphere(c, c.getDistance(pointSet.head))

case class DelaunaySimplex(simplex: Simplex[Int], circumsphere: Hypersphere)

/** Rank of `matrix`'s own row space, at THIS codebase's own `epsilon` tolerance -- not `SingularValueDecomposition`'s
  * default `getRank`, whose internal tolerance (machine-epsilon-scale, relative to the matrix's own norm) is far
  * tighter than `epsilon.epsilon` (typically `1e-5`) and so treats near-but-not-exactly-degenerate input (e.g. points
  * jittered at the `1e-7` scale) as full rank when every OTHER degeneracy check in this file -- `Hyperplane. from`'s
  * own `nullSV` filter included -- would already call that same input degenerate. Used everywhere this file needs "how
  * many affinely-independent directions does this set of points actually span," so all of them agree on where the line
  * is.
  */
private def rankAtEpsilon(matrix: org.apache.commons.math3.linear.RealMatrix)(using epsilon: Epsilon): Int =
  new SingularValueDecomposition(matrix).getSingularValues.count(_.abs > epsilon.epsilon)

/** Runs the frontier-walking bootstrap + main loop that finds every Delaunay simplex of `pts`, holding all of the
  * algorithm's own mutable working state (`validated`, `frontierCases`, `visitedFacets`, `cospherical`) so
  * `HelixDelaunay` itself only ever sees the finished, immutable result of `compute()` -- the same
  * mutable-builder/immutable-result split `AlphaComplexDQPBuilder`/`AlphaComplexDQP` use.
  */
private class HelixDelaunayBuilder(pts: Array[Array[Double]], seed: Long)(using epsilon: Epsilon):
  // `seed` controls, not merely reproduces: measured directly (varying seed 0-9 on a 3x3 grid and on 6 cospherical
  // points), the bootstrap shuffle changes WHICH of several valid tilings the frontier walk produces whenever the
  // input has cospherical/degenerate structure -- exactly the class doc's own "Accepted limitation" case, since a
  // different starting simplex can land the walk on a different side of an order-dependent tie. A fixed default
  // keeps unseeded call sites deterministic; it does not make the walk order-independent.
  private val rng: Random = new Random(seed)
  val points: Seq[Point] = pts.map(Point.apply).toIndexedSeq
  val ambientDimension: Int = points.head.getDimension
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
      val facet = cofacet.simplex - complement
      val hyperplane = Hyperplane.from(facet.toSeq.toSeq.map(points(_)))
      if hyperplane.isLight(points(complement)) then
        FrontierCase(facet, hyperplane.reverse, complement, cofacet.circumsphere)
      else FrontierCase(facet, hyperplane, complement, cofacet.circumsphere)

  private val frontierCases: LinkedBlockingDeque[FrontierCase] = LinkedBlockingDeque[FrontierCase]()
  private val visitedFacets: mutable.Set[Simplex[Int]] = mutable.Set.empty
  private val cospherical: mutable.Set[Set[Int]] = mutable.Set.empty

  // The queued, not cancelled, frontier cases by facet. Removing a case from the middle of `frontierCases` scanned the
  // whole queue comparing sorted sets (`.claude/WORKLOG-helix-construction-speed.md`); instead a removed case is
  // marked cancelled (by identity) and skipped when it is taken, which leaves the order of the others unchanged.
  private val queuedByFacet: mutable.HashMap[Simplex[Int], mutable.ArrayBuffer[FrontierCase]] = mutable.HashMap.empty
  private val cancelled: java.util.Set[FrontierCase] =
    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[FrontierCase, java.lang.Boolean]())

  private def enqueue(fc: FrontierCase): Unit =
    frontierCases.put(fc)
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
    while next.isEmpty && !frontierCases.isEmpty do
      val fc = frontierCases.take()
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

  /** The Delaunay simplex on the light side of a frontier facet, if any: `byMinimalCentre`, checked to have an empty
    * circumsphere; `bySphereScan` if that check fails (floating-point trouble near a degenerate facet).
    */
  private def delaunayCofacet(fc: FrontierCase): Option[DelaunaySimplex] =
    def fallback() =
      sphereScanFallbacks += 1
      bySphereScan(fc)
    facetCentre(fc.facet) match
      case None         => fallback()
      case Some(centre) =>
        byMinimalCentre(fc.facet, fc.hyperplane, centre) match
          case None                                                                => None // a hull facet
          case Some(ds) if !pts.exists(sphereStrictlyContains(ds.circumsphere, _)) => Some(ds)
          case Some(_)                                                             => fallback()

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
        if s > epsilon.epsilon && !facetVertices.contains(q) then
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
      val gram = MatrixUtils.createRealMatrix(Array.tabulate(k, k)((i, j) => 2 * dot(diffs(i), diffs(j))))
      val solver = new LUDecomposition(gram, 1e-14).getSolver
      if !solver.isNonSingular then None
      else
        val lambda = solver.solve(createRealVector(Array.tabulate(k)(i => dot(diffs(i), diffs(i))))).toArray
        val c0 = Array.tabulate(ambientDimension)(j => p0(j) + (0 until k).map(i => lambda(i) * diffs(i)(j)).sum)
        val r0sq = (0 until ambientDimension).map(j => (c0(j) - p0(j)) * (c0(j) - p0(j))).sum
        Some((c0, r0sq))

  private def sphereStrictlyContains(sphere: Hypersphere, p: Array[Double]): Boolean =
    val c = sphere.center
    var sq = 0.0
    var i = 0
    while i < p.length do
      val d = p(i) - c.getEntry(i)
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
      .sortBy(pi => circumsphere.center.getDistance(points(pi)))
      .view
      .map(pi => DelaunaySimplex(fc.facet + pi, Hypersphere((fc.facet + pi).toSeq.toSeq.map(points))))
      .collectFirst { case ds if !points.exists(ds.circumsphere.contains) => ds }

  private def addFrontierCase(simplex: DelaunaySimplex, complement: Int): Unit =
    if !cancel(simplex.simplex - complement) then enqueue(FrontierCase(simplex, complement))

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
      val trialVecs = chosenVecs :+ points(candidate).subtract(base).toArray
      val rank = rankAtEpsilon(MatrixUtils.createRealMatrix(trialVecs.toArray))
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
            val vecs = combo.tail.map(pi => points(pi).subtract(base).toArray)
            rankAtEpsilon(MatrixUtils.createRealMatrix(vecs.toArray)) == ambientDimension - 1
          }
          .take(maxCandidates)
          .map(_.toSet)
          .toSeq
          .sortBy(combo =>
            (for
              i <- combo.toSeq.indices
              j <- (i + 1) until combo.size
            yield points(combo.toSeq(i)).getDistance(points(combo.toSeq(j)))).sum
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
      .map(i => (i, seedDelaunaySimplex.circumsphere.center.getDistance(points(i))))
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
        delaunayCofacet(frontierCase) match
          case Some(newDelaunaySimplex) if !validatedSimplices.contains(newDelaunaySimplex.simplex) =>
            // check whether we have "too many" cospherical points; in that case we have to tile them on our own
            val spherepoints: mutable.SortedSet[Int] = points.indices
              .map(i => (i, newDelaunaySimplex.circumsphere.center.getDistance(points(i))))
              .filter((i, d) => onSphere(d, newDelaunaySimplex.circumsphere.radius))
              .map(_._1)
              .to(mutable.SortedSet)
            if spherepoints.size > ambientDimension + 1 then
              if !cospherical.contains(spherepoints.toSet) then
                accept(newDelaunaySimplex)
                handleCosphericalPoints(spherepoints.toSeq, frontierCase, newDelaunaySimplex)
            else
              accept(newDelaunaySimplex)
              frontierCase.facet.toSeq.toSeq
                .foreach(vi => addFrontierCase(newDelaunaySimplex, vi))
          case Some(newDelaunaySimplex) => ()
          case None                     => ()

    validated.toSet

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
class HelixDelaunay(pts: Array[Array[Double]], seed: Long = 0L, requireValidTriangulation: Boolean = false)(using
  epsilon: Epsilon
) extends AlphaShapes:
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
  private[tda4j] def sphereScanFallbacks: Int = _sphereScanFallbacks
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

  val simplicesMap: Map[Int, Seq[Simplex[Int]]] = Map.from(
    (0 to ambientDimension).map(d =>
      d -> validated
        .flatMap((ds: DelaunaySimplex) => ds.simplex.toSet.subsets(d + 1))
        .map((s: Set[Int]) => Simplex.from(s.toSeq))
        .toSeq
    )
  )

  /** The smallest sphere through every vertex of `s`: its centre lies in `s`'s own affine hull, at `p0 + Σ λ_i (p_i -
    * p0)` with `Σ_j 2 (p_i - p0)·(p_j - p0) λ_j = |p_i - p0|²`. (`Hypersphere.apply` is for full-dimensional simplices:
    * below full dimension its least-squares centre is the minimum-norm one, off the affine hull.)
    */
  def smallestCircumsphere(s: Simplex[Int]): (Point, Double) =
    val vs = s.toSeq.toIndexedSeq
    val p0 = points(vs.head)
    val k = vs.size - 1
    if k == 0 then (p0, 0.0)
    else
      val diffs = vs.tail.map(v => points(v).subtract(p0))
      val gram = MatrixUtils.createRealMatrix(k, k)
      val rhs = new Array[Double](k)
      for i <- 0 until k do
        rhs(i) = diffs(i).dotProduct(diffs(i))
        for j <- 0 until k do gram.setEntry(i, j, 2 * diffs(i).dotProduct(diffs(j)))
      val lambda = SingularValueDecomposition(gram).getSolver.solve(createRealVector(rhs))
      val center = (0 until k).foldLeft(p0)((c, i) => c.add(diffs(i).mapMultiply(lambda.getEntry(i))))
      (center, center.getDistance(p0))

  /** Alpha values, top dimension first:
    *   - a top-dimensional simplex: the smallest circumradius among the Delaunay simplices containing it (its own,
    *     unless a cospherical cluster was tiled as one larger cell);
    *   - a lower simplex `σ`: if `σ` is Gabriel -- no vertex of a coface strictly inside its smallest circumsphere --
    *     that sphere's radius, otherwise the smallest value among its immediate cofaces. A Gabriel simplex's radius is
    *     already at most its cofaces' values; taking the minimum anyway keeps the filtration monotone to the last bit.
    *   - a vertex: 0.
    */
  private lazy val alphaValues: Map[Simplex[Int], Double] =
    val values = mutable.HashMap.empty[Simplex[Int], Double]
    val containing: Map[Int, Seq[DelaunaySimplex]] =
      validated.toSeq.flatMap(ds => ds.simplex.toSeq.map(v => (v, ds))).groupMap(_._1)(_._2)
    simplicesMap(ambientDimension).foreach { s =>
      values(s) = containing(s.toSeq.head)
        .filter(ds => s.toSet.subsetOf(ds.simplex.toSet))
        .map(_.circumsphere.radius)
        .min
    }
    for k <- (ambientDimension - 1) to 1 by -1 do
      val cofaces = mutable.HashMap.empty[Simplex[Int], mutable.ArrayBuffer[(Simplex[Int], Int)]]
      simplicesMap(k + 1).foreach { t =>
        t.toSeq.foreach(v => cofaces.getOrElseUpdate(t - v, mutable.ArrayBuffer.empty) += ((t, v)))
      }
      simplicesMap(k).foreach { s =>
        val (center, r) = smallestCircumsphere(s)
        val cs = cofaces.getOrElse(s, mutable.ArrayBuffer.empty)
        val gabriel = cs.forall((_, v) => center.getDistance(points(v)) >= r - epsilon.epsilon)
        val cofaceMin = cs.map((t, _) => values(t)).minOption.getOrElse(Double.PositiveInfinity)
        values(s) = if gabriel then math.min(r, cofaceMin) else cofaceMin
      }
    simplicesMap(0).foreach(s => values(s) = 0.0)
    values.toMap

  override def filtrationValue: PartialFunction[Simplex[Int], Double] = { case spx => alphaValues(spx) }

  // Must be the exact reverse of filtrationOrdering below, not merely "ascending by filtrationValue" --
  // sortBy(filtrationValue) alone has no explicit tie-break (falls back to simplicesMap's own insertion
  // order among ties), which doesn't match filtrationOrdering's simplexOrdering[Int] tie-break. This
  // passes VietorisRipsSpec-style sortedness checks (value-only) but breaks PersistenceInChunksEngine,
  // whose chunk-boundary logic (Homology.scala's PersistenceInChunksEngine.allCells) relies on
  // iterateDimension's own emission order standing in for filtrationOrdering position -- found via
  // EngineComparisonBenchmarkSpec / AlphaFiltrationOrderingRegressionSpec (see CLAUDE.md).
  val simplicesSortedMap: Map[Int, Seq[Simplex[Int]]] =
    simplicesMap.map((d, v) => (d, v.sorted(using filtrationOrdering.reverse)))

  def simplicesInDimension(d: Int): Iterator[Simplex[Int]] = simplicesSortedMap(d).iterator

  def simplices(): Iterator[Simplex[Int]] = (0 to ambientDimension).iterator.flatMap(simplicesInDimension)

  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if simplicesSortedMap.contains(d) => simplicesSortedMap(d).iterator
  }

  // The shared FiltrationOrdering.canonical shape: fv reversed, then dimension, then simplexOrdering.
  // `simplicesSortedMap` (above) is built as `.sorted(using filtrationOrdering.reverse)` specifically so it
  // stays the exact reverse of this ordering, tie-break included -- see RecursiveStackVietorisRipsSimplexStream's
  // identical fix (VietorisRips.scala) and CLAUDE.md for the full root-cause writeup. This used to have no
  // dimension key at all (`Ordering.by(filtrationValue).reverse.orElse(simplexOrdering[Int])`), which is wrong
  // for the cross-dimension comparisons `Chain.reduceBy` performs during reduction (see the identical fix's own
  // comment). Kept a `def`, not a `val`: `simplicesSortedMap` above uses it during construction, before a `val`
  // declared this late in the class body would be initialized (see CLAUDE.md's `ExplicitStreamBuilder` NPE note
  // for the general hazard).
  override def filtrationOrdering: Ordering[Simplex[Int]] =
    FiltrationOrdering.canonical(filtrationValue, _.size, simplexOrdering[Int])

object HelixDelaunay:

  /** Points whose affine span has lower dimension than their coordinates, re-expressed in an orthonormal basis of that
    * span (distances are unchanged, so the triangulation is the true one); other point sets are returned as they are.
    */
  private def projectToAffineRank(pts: Array[Array[Double]])(using epsilon: Epsilon): Array[Array[Double]] =
    if pts.length < 2 then pts
    else
      val dim = pts.head.length
      val base = pts.head
      val centered = pts.map(p => p.zip(base).map(_ - _))
      val matrix = MatrixUtils.createRealMatrix(centered)
      val rank = rankAtEpsilon(matrix)
      if rank >= dim then pts
      else
        val v = new SingularValueDecomposition(matrix).getV
        centered.map { row =>
          Array.tabulate(rank)(k => (0 until dim).map(j => row(j) * v.getEntry(j, k)).sum)
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
  private[tda4j] def looksValid(simps: Set[DelaunaySimplex], points: Seq[Point])(using epsilon: Epsilon): Boolean =
    val tops = simps.map(_.simplex)
    val vertices = tops.flatMap(_.toSeq)
    lazy val facetCounts = tops.toSeq.flatMap(t => t.toSeq.map(v => t - v)).groupMapReduce(identity)(_ => 1)(_ + _)
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
    vertices.size == points.size &&
    facetCounts.values.forall(_ <= 2) &&
    facetCounts.iterator.filter(_._2 == 1).forall((f, _) => onHull(f))

  private[tda4j] def badFacetsOf(simps: Set[DelaunaySimplex]): Map[Simplex[Int], Vector[DelaunaySimplex]] =
    // Distinct simplices: a cospherical tiling can record a simplex the walk also found, with the cluster's sphere
    // instead of its own; that is one simplex, not a third claimant.
    facetToSimplices(simps).filter { case (_, claimants) => claimants.map(_.simplex).distinct.size > 2 }

  /** A Delaunay triangulation fills its convex hull, so the whole complex has no homology in degree `d - 1`. `None` if
    * that holds; otherwise the vertices of the essential degree-`(d - 1)` representatives, the boundary of the hole,
    * which seed the repair. (A facet with one coface can be a missing simplex rather than a hull facet; only this
    * global check sees it.)
    */
  private[tda4j] def interiorVoidVertices(simps: Set[DelaunaySimplex], ambientDimension: Int): Option[Set[Int]] =
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
            yield jitterPoints(i).getDistance(jitterPoints(j))).min
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
private[tda4j] final class RadiusLimitedAlphaShapes(full: AlphaShapes, maxRadius: Double) extends AlphaShapes:
  override val metricSpace: FiniteMetricSpace[Int] = full.metricSpace
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if full.iterateDimension.isDefinedAt(d) =>
      full.iterateDimension(d).filter(full.filtrationValue(_) <= maxRadius)
  }
  override def filtrationOrdering: Ordering[Simplex[Int]] = full.filtrationOrdering
  override def filtrationValue: PartialFunction[Simplex[Int], Double] = full.filtrationValue

/** `helix` without its simplices of dimension greater than `maxDim`: what the fast alpha engine hands the chunks engine
  * for the degrees between 0 and the top. Filtration values and order are those of `helix`.
  */
class LimitedAlphaShapesStream(helix: HelixDelaunay, maxDim: Int)
    extends LevelwiseSimplexStream[Int, Double]
    with DoubleFiltration[Simplex[Int]]():
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d: Int if d >= 0 && d <= maxDim && helix.iterateDimension.isDefinedAt(d) => helix.iterateDimension(d)
  }
  override def filtrationOrdering: Ordering[Simplex[Int]] = helix.filtrationOrdering
  override def filtrationValue: PartialFunction[Simplex[Int], Double] = helix.filtrationValue
  // Cells up to dimension maxDim: degrees above maxDim - 1 are truncation artifacts.
  override def homologyDegreeLimit: Option[Int] = Some(maxDim - 1)
