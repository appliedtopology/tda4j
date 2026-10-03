package org.appliedtopology.tda4j

import math.{pow, sqrt}
import collection.immutable.Range
import util.chaining.scalaUtilChainingOps
import math.Ordering.Implicits.*

import scala.jdk.CollectionConverters.*
import com.eatthepath.jvptree.*

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
    * the Ripser paper, page 412.
    */
  lazy val minimumEnclosingRadius =
    elements.map { x =>
      elements.map(y => distance(x, y)).max
    }.min

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
class IntMetricSpace[VertexT](val metricSpace: FiniteMetricSpace[VertexT]) extends FiniteMetricSpace[Int]:
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
  *   - The triangle inequality
  */
class ExplicitMetricSpace(val dist: Seq[Seq[Double]]) extends FiniteMetricSpace[Int]:
  def distance(x: Int, y: Int): Double = dist(x)(y)
  def size: Int = dist.size
  def elements: Iterable[Int] = Range(0, size)
  override def contains(x: Int): Boolean = 0 <= x & x < size

/** The Euclidean distances between the rows of `pts` (all of the same length).
  *
  * @param cacheDistances
  *   compute all pairwise distances on first use and look them up afterwards (default `true`). That is `n^2` doubles,
  *   about 8 MB at 1,000 points and 128 MB at 4,000; pass `false` for point clouds large enough for that to matter.
  *   Distances are looked up many times per pair during a Vietoris-Rips computation, so the cache usually pays off.
  */

class EuclideanMetricSpace(val pts: Array[Array[Double]], val cacheDistances: Boolean = true)
    extends FiniteMetricSpace[Int]:
  def pointSqDistance(x: Array[Double], y: Array[Double]): Double =
    var acc: Double = 0.0
    val n = math.min(x.length, y.length)
    var i = 0
    while i < n do
      val d: Double = x(i) - y(i)
      acc += d * d
      i += 1
    acc

  def size: Int = pts.size
  def elements: Iterable[Int] = Range(0, size)
  override def contains(x: Int): Boolean = 0 <= x & x < size

  // Flattened, not Array[Array[Double]] -- one allocation instead of n, and a single multiply-add index
  // computation per lookup rather than an extra array dereference. Filled via the full n x n range (not just
  // the upper triangle) -- doubling the fill cost of an already-cheap, one-time O(n^2 * ambientDim) pass in
  // exchange for a branch-free `distance` lookup afterward, which is the call this cache exists to make cheap.
  private lazy val distanceCache: Array[Double] =
    val n = size
    val cache = new Array[Double](n * n)
    var i = 0
    while i < n do
      var j = i
      while j < n do
        val d = sqrt(pointSqDistance(pts(i), pts(j)))
        cache(i * n + j) = d
        cache(j * n + i) = d
        j += 1
      i += 1
    cache

  def distance(x: Int, y: Int): Double =
    if cacheDistances then distanceCache(x * size + y) else sqrt(pointSqDistance(pts(x), pts(y)))

  lazy val vpdf: DistanceFunction[Array[Double]] =
    new DistanceFunction[Array[Double]]:
      override def getDistance(firstPoint: Array[Double], secondPoint: Array[Double]): Double =
        sqrt(pointSqDistance(firstPoint, secondPoint))

  lazy val vpt: VPTree[Array[Double], Array[Double]] =
    new VPTree(vpdf, pts.toSeq.asJavaCollection)

  def neighbors(qp: Array[Double], eps: Double): Seq[Int] =
    vpt.getAllWithinDistance(qp, eps).asScala.toSeq.map(pts.indexOf(_))

object EuclideanMetricSpace:
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

class JVPTree[VertexT](metricSpace: FiniteMetricSpace[VertexT]) extends SpatialQuery[VertexT]:
  val distanceFunction: DistanceFunction[VertexT] = new DistanceFunction[VertexT]:
    override def getDistance(firstPoint: VertexT, secondPoint: VertexT): Double =
      metricSpace.distance(firstPoint, secondPoint)
  val vpTree: VPTree[VertexT, VertexT] = new VPTree(distanceFunction, metricSpace.elements.asJavaCollection)

  override def neighbors(v: VertexT, epsilon: Double): Set[VertexT] =
    vpTree.getAllWithinDistance(v, epsilon).asScala.toSet

  // VP-tree pruning assumes the triangle inequality holds for `metricSpace.distance` -- true of every genuine
  // metric space in this codebase, but NOT of every FiniteMetricSpace instance (ExplicitMetricSpace enforces
  // nothing; a correlation-derived "distance" matrix, as GUDHI's own docs use, can violate it). A violated
  // triangle inequality means the tree can silently prune away a genuine nearest neighbour. Callers over an
  // arbitrary/unverified FiniteMetricSpace should use BruteForce instead -- see DistanceToMeasure, which
  // defaults to it for exactly this reason.
  override def nearestNeighbors(v: VertexT, k: Int): IndexedSeq[VertexT] =
    require(1 <= k && k <= metricSpace.size, s"k must be between 1 and ${metricSpace.size}, got $k")
    vpTree.getNearestNeighbors(v, k).asScala.toIndexedSeq

class BruteForce[VertexT](metricSpace: FiniteMetricSpace[VertexT]) extends SpatialQuery[VertexT]:
  override def nearestNeighbors(v: VertexT, k: Int): IndexedSeq[VertexT] =
    require(1 <= k && k <= metricSpace.size, s"k must be between 1 and ${metricSpace.size}, got $k")
    metricSpace.elements.toIndexedSeq.sortBy(metricSpace.distance(v, _)).take(k)

  override def neighbors(v: VertexT, epsilon: Double): Set[VertexT] =
    metricSpace.elements.toSet.filter(w => metricSpace.distance(v, w) <= epsilon)

/** ****** Sparse Metric Spaces and the Dory storage *******
  */

class SparseMetricSpace[VertexT: Ordering](metricSpace: FiniteMetricSpace[VertexT], diameter: Double)
    extends FiniteMetricSpace[VertexT]():
  val spatialQuery: SpatialQuery[VertexT] = JVPTree(metricSpace)

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
