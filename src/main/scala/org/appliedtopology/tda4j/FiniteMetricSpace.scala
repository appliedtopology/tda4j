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
trait FiniteMetricSpace[VertexT] {

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
}

/** Convenience functionality for metric spaces.
  */
object FiniteMetricSpace {

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
  ) extends PartialFunction[Simplex[VertexT], Double] {
    def isDefinedAt(spx: Simplex[VertexT]): Boolean =
      spx.vertices.forall(v => metricSpace.contains(v))

    def apply(spx: Simplex[VertexT]): Double =
      if (spx.dim <= 0)
        0.0
      else
        spx
          .vertices
          .flatMap(v => spx.vertices.filter(_ > v).map(w => metricSpace.distance(v, w)))
          .max
  }
}

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
class ExplicitMetricSpace(val dist: Seq[Seq[Double]]) extends FiniteMetricSpace[Int] {
  def distance(x: Int, y: Int): Double = dist(x)(y)
  def size: Int = dist.size
  def elements: Iterable[Int] = Range(0, size)
  override def contains(x: Int): Boolean = 0 <= x & x < size
}

/** Takes in an point cloud and computes the Euclidean distance on demand.
  *
  * @param pts
  *   Point cloud matrix represented as a `Seq[Seq[Double]]`. The class expects but does not enforce:
  *
  *   - `pts(x1).size == pts(x2).size` for all `x1,x2`
  */

class EuclideanMetricSpace(val pts: Seq[Seq[Double]]) extends FiniteMetricSpace[Int] {
  def distance(x: Int, y: Int): Double = {
    val sqdist = pts(x).zip(pts(y)).map((x, y) => pow(x - y, 2))
    sqdist.sum.pipe(sqrt)
  }
  def size: Int = pts.size
  def elements: Iterable[Int] = Range(0, size)
  override def contains(x: Int): Boolean = 0 <= x & x < size
}
