package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

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

  /** Creates a filtration value partial function implementing the functionality of a [[SimplexFiltration]] for a
    * filtration generated from a metric space, where the filtration value is the maximum distance between vertices (or
    * the diameter) of a simplex.
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

    def apply(spx: Simplex[VertexT]): Double =
      if spx.dim <= 0 then 0.0
      else
        spx
          .flatMap(v => spx.toSeq.filter(_ > v).map(w => metricSpace.distance(v, w)))
          .max

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

/** Takes in an point cloud and computes the Euclidean distance on demand.
  *
  * @param pts
  *   Point cloud matrix represented as a `Seq[Seq[Double]]`. The class expects but does not enforce:
  *
  *   - `pts(x1).size == pts(x2).size` for all `x1,x2`
  */

class EuclideanMetricSpace(val pts: Array[Array[Double]]) extends FiniteMetricSpace[Int]:
  def pointSqDistance(x: Array[Double], y: Array[Double]): Double =
    var acc: Double = 0.0
    val n = math.min(x.length, y.length)
    var i = 0
    while i < n do
      val d: Double = x(i) - y(i)
      acc += d * d
      i += 1
    acc

  def distance(x: Int, y: Int): Double =
    sqrt(pointSqDistance(pts(x), pts(y)))
  def size: Int = pts.size
  def elements: Iterable[Int] = Range(0, size)
  override def contains(x: Int): Boolean = 0 <= x & x < size

  lazy val vpdf: DistanceFunction[Array[Double]] =
    new DistanceFunction[Array[Double]]:
      override def getDistance(firstPoint: Array[Double], secondPoint: Array[Double]): Double =
        sqrt(pointSqDistance(firstPoint, secondPoint))

  lazy val vpt: VPTree[Array[Double], Array[Double]] =
    new VPTree(vpdf, pts.toSeq.asJavaCollection)

  def neighbors(qp: Array[Double], eps: Double): Seq[Int] =
    vpt.getAllWithinDistance(qp, eps).asScala.toSeq.map(pts.indexOf(_))

object EuclideanMetricSpace:
  def apply(points: Seq[Seq[Double]]): EuclideanMetricSpace =
    new EuclideanMetricSpace(points.map(_.toArray).toArray)
  def apply(points: Array[Array[Double]]): EuclideanMetricSpace =
    new EuclideanMetricSpace(points)

/** ******* Efficient Spatial Queries *******
  */

trait SpatialQuery[VertexT]:
  def neighbors(v: VertexT, epsilon: Double): Set[VertexT]

  /** The `k` nearest points to `v` (by `metricSpace.distance`), sorted ascending by distance, `v` itself included when
    * it belongs to the underlying metric space (a real metric always has `distance(v,v) = 0`, the smallest possible, so
    * `v` is always its own nearest neighbour) -- this is the convention `streams.DistanceToMeasure` needs
    * (Chazal-Cohen-Steiner-Merigot 2011's empirical DTM counts a point among its own `k` neighbours; verified against
    * GUDHI's own `DistanceToMeasure`/`KNearestNeighbors` docstring AND a worked numeric example, see
    * `.claude/WORKLOG-dtm-filtrations.md`). `require(1 <= k && k <= metricSpace.size)`: a `k` outside that range has no
    * sensible answer (jvptree's own `getNearestNeighbors` silently clamps to however many points exist, which would
    * silently under-deliver rather than fail loudly).
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
  // arbitrary/unverified FiniteMetricSpace should use BruteForce instead -- see streams.DistanceToMeasure, which
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
