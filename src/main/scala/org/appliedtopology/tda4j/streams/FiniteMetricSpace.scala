package org.appliedtopology.tda4j

import math.{pow, sqrt}
import collection.immutable.Range
import util.chaining.scalaUtilChainingOps
import math.Ordering.Implicits.*

/** Interface for being a finite metric space
  *
  * @tparam VertexT
  *   Type of the vertex indices for the metric space
  */
trait FiniteMetricSpace[VertexT]:

  /** Distance in the metric space. Takes two indices and returns a non-negative real number.
    * @param x
    *   Index of first point
    * @param y
    *   Index of second point
    * @return
    *   Distance between x and y
    */
  def distance(x: VertexT, y: VertexT): Double

  /** Number of points represented by this metric space.
    * @return
    */
  def size: Int

  /** Access to all points in the metric space. Implemented by eg `scala.collections.Range` for simple Int-indexed
    * spaces, but this definition gives more space for different underlying possible representations.
    * @return
    *   Iterable that returns all points in the metric space
    */
  def elements: Iterable[VertexT]

  def contains(x: VertexT): Boolean

  /** Beyond this radius, the Vietoris-Rips complex is a cone and will have no further homological structure. See e.g.
    * the Ripser paper, page 412. The same holds for the Cech and alpha complexes, whose union of balls is then
    * contractible, so no barcode changes past it: it is the radius that `Persistence` runs to when it is not told
    * another. A space with infinite distances (a sparse distance matrix: a pair that is not listed has no edge) has no
    * such cone; its radius is then the largest finite distance, past which nothing enters.
    *
    * Found by looking for the point whose farthest point is nearest, with no allocation, and giving up on a point as
    * soon as a point at least as far as the best so far turns up: on spread-out points that takes a handful of
    * distances per point, not all of them.
    */
  lazy val minimumEnclosingRadius: Double = scanEnclosingRadius

  /** The scan behind [[minimumEnclosingRadius]]: a space that can find it faster than by asking for distances overrides
    * this (as [[EuclideanMetricSpace]] does) and answers the same number.
    */
  protected def scanEnclosingRadius: Double =
    val points = elements.toIndexedSeq
    val n = points.length
    if n == 0 then throw new UnsupportedOperationException("the empty metric space has no enclosing radius")
    var best = Double.PositiveInfinity
    var i = 0
    while i < n do
      val x = points(i)
      var farthest = 0.0
      var j = 0
      while j < n && farthest < best do
        val d = distance(x, points(j))
        if d > farthest then farthest = d
        j += 1
      if farthest < best then best = farthest
      i += 1
    if !best.isInfinite then best
    else
      // every point has an infinite distance: the largest finite one
      var longest = 0.0
      i = 0
      while i < n do
        var j = 0
        while j < n do
          val d = distance(points(i), points(j))
          if !d.isInfinite && d > longest then longest = d
          j += 1
        i += 1
      longest

/** Convenience functionality for metric spaces.
  */
object FiniteMetricSpace:

  /** Creates a filtration value partial function implementing the functionality of a [[Filtration]] for a filtration
    * generated from a metric space, where the filtration value is the maximum distance between vertices (or the
    * diameter) of a simplex.
    *
    * @param metricSpace
    *   An instance of a finite metric space.
    * @tparam VertexT
    *   Type of vertex indices / indices into the metric space
    */
  class MaximumDistanceFiltrationValue[VertexT: Ordering](
    val metricSpace: FiniteMetricSpace[VertexT]
  ) extends PartialFunction[Simplex[VertexT], Double]:
    def isDefinedAt(spx: Simplex[VertexT]): Boolean =
      spx.forall(v => metricSpace.contains(v))

    /** The largest distance between two vertices of `spx`. A plain nested iterator walk: this is called on every
      * comparison during reduction, so it allocates nothing beyond two iterators.
      */
    def apply(spx: Simplex[VertexT]): Double =
      if spx.dim <= 0 then 0.0
      else
        val vertices = spx.underlying
        var maxD = 0.0
        val outer = vertices.iterator
        while outer.hasNext do
          val v = outer.next()
          val inner = vertices.iteratorFrom(v)
          inner.next() // iteratorFrom(v) is inclusive of v itself -- discard it, we only want w > v
          while inner.hasNext do
            val d = metricSpace.distance(v, inner.next())
            if d > maxD then maxD = d
        maxD

/** Wrapper class to make any metricspace into a metricspace defined on indices 0 through `metricSpace.size`. This way,
  * code can assume that the index set is contiguous.
  *
  * @param metricSpace
  *   Wrapped metric space
  * @tparam VertexT
  *   Type of the vertex indices for the wrapped metric space
  */
open class IntMetricSpace[VertexT](val metricSpace: FiniteMetricSpace[VertexT]) extends FiniteMetricSpace[Int]:
  override def distance(x: Int, y: Int): Double =
    metricSpace.distance(metricSpace.elements.toIndexedSeq(x), metricSpace.elements.toIndexedSeq(y))

  override def contains(x: Int): Boolean = (x < metricSpace.size) && (0 <= x)

  override lazy val minimumEnclosingRadius: Double = metricSpace.minimumEnclosingRadius

  override def size: Int = metricSpace.size

  override def elements: Iterable[Int] = 0 until size

/** Takes in an explicit distance matrix, and performs lookups in this distance matrix.
  *
  * @param dist
  *   Distance matrix represented as a `Seq[Seq[Double]]`. The class expects but does not enforce:
  *
  *   - `dist(x1).size == dist(x2).size` for all `x1,x2`
  *   - `dist(x).size == dist.size` for all `x`
  *   - `dist(x)(x) == 0` for all `x`
  *   - `dist(x)(y) == dist(y)(x)` for all `x,y`
  *   - The triangle inequality
  *
  * Only the lower triangle is read: `distance(x, y)` is `dist(max(x, y))(min(x, y))`, as in Ripser and GUDHI, so a
  * matrix that is not exactly symmetric gives the same results here as there.
  */
open class ExplicitMetricSpace(val dist: Seq[Seq[Double]]) extends FiniteMetricSpace[Int]:
  // Real-world distance-matrix files (e.g. the roadmap benchmark data's `fractal_9_5_2` set) can violate the
  // documented `dist(x)(y) == dist(y)(x)` expectation by ~1e-5/1e-6 (rounding in however the file was produced),
  // not enforced at load time. Reading only ONE triangle's entry, always via the same (max, min) ordering
  // regardless of which argument order the caller used, makes `distance` self-consistent for a given pair
  // REGARDLESS of call-site argument order -- `PackedRipserCohomologyContext.insertionDiameter` queries
  // `distance(existingVertex, insertedVertex)` with an argument order that depends on which facet a simplex was
  // reached from, so an unsymmetrized lookup could silently stamp the SAME combinatorial simplex with two
  // different diameters depending on path, corrupting its own basis/pivot bookkeeping (`.claude/
  // WORKLOG-o3-1024-fractal-r-session-2026-09-25.md`'s fractal-r investigation).
  def distance(x: Int, y: Int): Double =
    // The lower triangle (row >= column), the triangle ripser.cpp and GUDHI read.
    if x >= y then dist(x)(y) else dist(y)(x)
  def size: Int = dist.size
  def elements: Iterable[Int] = Range(0, size)
  override def contains(x: Int): Boolean = 0 <= x & x < size

/** The Euclidean distances between the rows of `pts` (all of the same length).
  *
  * Coordinates are copied on construction. [[pts]] returns a defensive copy, so mutating either the input arrays or a
  * returned array does not change this metric space or its caches.
  *
  * @param cacheDistances
  *   compute all pairwise distances on first use and look them up afterwards (default `true`). That is `n^2` doubles,
  *   about 8 MB at 1,000 points and 512 MB at 8,192, so the cache is only kept up to
  *   [[EuclideanMetricSpace.MaxCachedPoints]] points; above that every distance is computed when asked for. Pass
  *   `false` to compute every distance on demand at any size. Distances are looked up many times per pair during a
  *   Vietoris-Rips computation, so the cache usually pays off where it fits.
  */

open class EuclideanMetricSpace(coordinates: Array[Array[Double]], val cacheDistances: Boolean = true)
    extends FiniteMetricSpace[Int]:
  private val pointCoordinates = coordinates.map(_.clone())
  private val ambientDimension = if pointCoordinates.isEmpty then 0 else pointCoordinates.head.length
  require(
    pointCoordinates.forall(_.length == ambientDimension),
    s"EuclideanMetricSpace: the points have different numbers of coordinates (the first has $ambientDimension, " +
      s"point ${pointCoordinates.indexWhere(_.length != ambientDimension)} has " +
      s"${pointCoordinates.find(_.length != ambientDimension).map(_.length).getOrElse(0)})"
  )
  require(
    pointCoordinates.forall(_.forall(_.isFinite)),
    s"EuclideanMetricSpace: point ${pointCoordinates.indexWhere(!_.forall(_.isFinite))} has a coordinate that is NaN or infinite"
  )

  /** A defensive copy of the points captured when this metric space was constructed. */
  def pts: Array[Array[Double]] = pointCoordinates.map(_.clone())

  // Restricted because returning owned arrays publicly would let callers invalidate this space's lazy caches.
  private[tda4j] def pointAt(index: Int): Array[Double] = pointCoordinates(index)

  def pointSqDistance(x: Array[Double], y: Array[Double]): Double =
    require(
      x.length == y.length,
      s"EuclideanMetricSpace.pointSqDistance: points have different numbers of coordinates (${x.length} and ${y.length})"
    )
    squaredDistance(x, y)

  private def squaredDistance(x: Array[Double], y: Array[Double]): Double =
    var acc: Double = 0.0
    val n = x.length
    var i = 0
    while i < n do
      val d: Double = x(i) - y(i)
      acc += d * d
      i += 1
    acc

  def size: Int = pointCoordinates.size
  def elements: Iterable[Int] = Range(0, size)
  override def contains(x: Int): Boolean = 0 <= x & x < size

  // Flattened, not Array[Array[Double]] -- one allocation instead of n, and a single multiply-add index
  // computation per lookup rather than an extra array dereference. Filled via the full n x n range (not just
  // the upper triangle) -- doubling the fill cost of an already-cheap, one-time O(n^2 * ambientDim) pass in
  // exchange for a branch-free `distance` lookup afterward, which is the call this cache exists to make cheap.
  private val useCache: Boolean = cacheDistances && size <= EuclideanMetricSpace.MaxCachedPoints

  private lazy val distanceCache: Array[Double] =
    val n = size
    val cache = new Array[Double](n * n)
    var i = 0
    while i < n do
      var j = i
      while j < n do
        val d = sqrt(squaredDistance(pointCoordinates(i), pointCoordinates(j)))
        cache(i * n + j) = d
        cache(j * n + i) = d
        j += 1
      i += 1
    cache

  def distance(x: Int, y: Int): Double =
    if useCache then distanceCache(x * size + y) else sqrt(squaredDistance(pointCoordinates(x), pointCoordinates(y)))

  // On squared distances, which gives the same radius (the square root is monotone and exactly rounded) without
  // filling the n^2 distance cache or taking a root per pair: an alpha complex never asks for the cache.
  override protected def scanEnclosingRadius: Double =
    val n = size
    var best = Double.PositiveInfinity
    var i = 0
    while i < n do
      var farthest = 0.0
      var j = 0
      while j < n && farthest < best do
        val d = squaredDistance(pointCoordinates(i), pointCoordinates(j))
        if d > farthest then farthest = d
        j += 1
      if farthest < best then best = farthest
      i += 1
    // no points, or coordinates whose squares overflow: let the distances decide
    if n == 0 || best.isInfinite then super.scanEnclosingRadius else sqrt(best)

  /** The Euclidean distance between two points, the metric of [[vpt]]. */
  lazy val vpdf: (Array[Double], Array[Double]) => Double = (x, y) => sqrt(pointSqDistance(x, y))

  /** A vantage-point tree over the points. */
  lazy val vpt: VantagePointTree[Array[Double]] = VantagePointTree(pointCoordinates.map(_.clone()).toSeq, vpdf)

  /** The indices of the points within distance `eps` of `qp` (ascending), `qp` itself need not be one of them. */
  def neighbors(qp: Array[Double], eps: Double): Seq[Int] = vpt.indicesWithinDistance(qp, eps)

object EuclideanMetricSpace:
  /** The largest point count whose distances are cached: `8192^2` doubles is 512 MB. */
  val MaxCachedPoints: Int = 8192

  // Two overloads, not one with a default `cacheDistances`: the class's own constructor carries that default. Every
  // point shape (Array[Array[Double]], Seq[Seq[Double]], Seq[Array[Double]]) converts to a PointCloud here.
  def apply(points: PointCloud): EuclideanMetricSpace = new EuclideanMetricSpace(points.points)
  def apply(points: PointCloud, cacheDistances: Boolean): EuclideanMetricSpace =
    new EuclideanMetricSpace(points.points, cacheDistances)

/** ******* Efficient Spatial Queries *******
  */

trait SpatialQuery[VertexT]:
  def neighbors(v: VertexT, epsilon: Double): Set[VertexT]

  /** The `k` points nearest to `v`, nearest first, `v` itself included when it is a point of the space (the convention
    * of the distance to measure, as in GUDHI). Requires `1 <= k <= size`.
    */
  def nearestNeighbors(v: VertexT, k: Int): IndexedSeq[VertexT]

/** Neighbour search by a [[VantagePointTree]] over the points of `metricSpace`. Fast on a genuine metric space; see
  * [[VantagePointTree]] for the tie-break of `nearestNeighbors` (earlier in `metricSpace.elements` first).
  */
open class VantagePointQuery[VertexT](metricSpace: FiniteMetricSpace[VertexT]) extends SpatialQuery[VertexT]:
  val vpTree: VantagePointTree[VertexT] = VantagePointTree(metricSpace.elements.toIndexedSeq, metricSpace.distance)

  override def neighbors(v: VertexT, epsilon: Double): Set[VertexT] =
    vpTree.withinDistance(v, epsilon).toSet

  // VP-tree pruning assumes the triangle inequality holds for `metricSpace.distance` -- true of every genuine
  // metric space in this codebase, but NOT of every FiniteMetricSpace instance (ExplicitMetricSpace enforces
  // nothing; a correlation-derived "distance" matrix, as GUDHI's own docs use, can violate it). A violated
  // triangle inequality means the tree can silently prune away a genuine nearest neighbour. Callers over an
  // arbitrary/unverified FiniteMetricSpace should use BruteForce instead -- see DistanceToMeasure, which
  // defaults to it for exactly this reason.
  override def nearestNeighbors(v: VertexT, k: Int): IndexedSeq[VertexT] =
    require(1 <= k && k <= metricSpace.size, s"k must be between 1 and ${metricSpace.size}, got $k")
    vpTree.nearest(v, k)

open class BruteForce[VertexT](metricSpace: FiniteMetricSpace[VertexT]) extends SpatialQuery[VertexT]:
  override def nearestNeighbors(v: VertexT, k: Int): IndexedSeq[VertexT] =
    require(1 <= k && k <= metricSpace.size, s"k must be between 1 and ${metricSpace.size}, got $k")
    metricSpace.elements.toIndexedSeq.sortBy(metricSpace.distance(v, _)).take(k)

  override def neighbors(v: VertexT, epsilon: Double): Set[VertexT] =
    metricSpace.elements.toSet.filter(w => metricSpace.distance(v, w) <= epsilon)

/** ****** Sparse Metric Spaces and the Dory storage *******
  */

open class SparseMetricSpace[VertexT: Ordering](metricSpace: FiniteMetricSpace[VertexT], diameter: Double)
    extends FiniteMetricSpace[VertexT]():
  val spatialQuery: SpatialQuery[VertexT] = VantagePointQuery(metricSpace)

  val neighborhoods: Map[VertexT, Seq[(VertexT, Double)]] =
    Map.from(
      metricSpace.elements.map { x =>
        x ->
          spatialQuery.neighbors(x, diameter).toSeq.map(y => (y, metricSpace.distance(x, y))).sortBy(_._2)
      }
    )

  /** Delegated */
  override def contains(x: VertexT): Boolean = metricSpace.contains(x)
  override def elements: Iterable[VertexT] = metricSpace.elements
  override def size: Int = metricSpace.size

  override def distance(x: VertexT, y: VertexT): Double =
    metricSpace
      .distance(x, y)
      .pipe(d => if d > diameter then Double.PositiveInfinity else d)
