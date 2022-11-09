package org.appliedtopology.tda4j

import math.{pow, sqrt}
import collection.immutable.Range
import util.chaining.scalaUtilChainingOps

/** Interface for being a finite metric space
  *
  * @tparam PointIndexT
  *   Type of the vertex indices for the metric space
  */
trait FiniteMetricSpace[PointIndexT] {

  /** Distance in the metric space. Takes two indices and returns a non-negative
    * real number.
    * @param x
    *   Index of first point
    * @param y
    *   Index of second point
    * @return
    *   Distance between x and y
    */
  def distance(x: PointIndexT, y: PointIndexT): Double

  /** Number of points represented by this metric space.
    * @return
    */
  def size: Int

  /** Access to all points in the metric space. Implemented by eg
    * `scala.collections.Range` for simple Int-indexed spaces, but this
    * definition gives more space for different underlying possible
    * representations.
    * @return
    *   Iterable that returns all points in the metric space
    */
  def elements: Iterable[PointIndexT]
}

/** Takes in an explicit distance matrix, and performs lookups in this distance
  * matrix.
  *
  * @param dist
  *   Distance matrix represented as a `Seq[Seq[Double]]`. The class expects but
  *   does not enforce:
  *
  *   1. `dist(x1).size == dist(x2).size` for all `x1,x2`
 *    2. `dist(x).size == dist.size` for all `x`
 *    3. `dist(x)(x) == 0` for all `x`
 *    4. The triangle inequality
  */
class ExplicitMetricSpace(val dist: Seq[Seq[Double]])
    extends FiniteMetricSpace[Int] {
  def distance(x: Int, y: Int) = dist(x)(y)
  def size = dist.size
  def elements = Range(0, size)
}

/** Takes in an point cloud and computes the Euclidean distance on demand.
  *
  * @param dist
  *   Point cloud matrix represented as a `Seq[Seq[Double]]`. The class expects
  *   but does not enforce:
  *
  *   1. `pts(x1).size == pts(x2).size` for all `x1,x2`
  */

class EuclideanMetricSpace(val pts: Seq[Seq[Double]])
    extends FiniteMetricSpace[Int] {
  def distance(x: Int, y: Int) = {
    val sqdist = pts(x).zip(pts(y)).map((x, y) => pow(x - y, 2))
    return sqdist.sum.pipe(sqrt)
  }
  def size = pts.size
  def elements = Range(0, size)
}
