package org.appliedtopology.tda4j

// format: off
/** A finite point cloud in Euclidean space, one row per point. Every entry point that takes points takes a
  * `PointCloud`, and `Array[Array[Double]]`, `Seq[Seq[Double]]` and `Seq[Array[Double]]` all convert to one where a
  * `PointCloud` is expected (`into`), so callers never need to care which collection their points are in.
  */
into final case class PointCloud(points: Array[Array[Double]]):
  def size: Int = points.length
  def ambientDimension: Int = if points.isEmpty then 0 else points.head.length
  lazy val metricSpace: EuclideanMetricSpace = EuclideanMetricSpace(points)
// format: on

object PointCloud:
  given fromArrays: Conversion[Array[Array[Double]], PointCloud] = PointCloud(_)
  given fromSeqs: Conversion[Seq[Seq[Double]], PointCloud] = s => PointCloud(s.map(_.toArray).toArray)
  given fromSeqOfArrays: Conversion[Seq[Array[Double]], PointCloud] = s => PointCloud(s.toArray)

/** A complex built from a point cloud alone -- implemented by the dispatcher objects `VietorisRips`, `Cech` and
  * `AlphaShapes`, so they double as the choice in `Persistence(points, complex = Cech)`. (Complexes that need more input
  * than points -- witness landmarks, a Dowker relation -- are built directly and passed to `Persistence` as a stream.)
  */
trait PointCloudComplex:
  def fromPoints(
    points: PointCloud,
    maxDimension: Int,
    maxFiltrationValue: Option[Double]
  ): LevelwiseSimplexStream[Int, Double]
