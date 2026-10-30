package org.appliedtopology.tda4j

// format: off
/** A finite point cloud in Euclidean space, one row per point. Every entry point that takes points takes a
  * `PointCloud`, and `Array[Array[Double]]`, `Seq[Seq[Double]]` and `Seq[Array[Double]]` all convert to one where a
  * `PointCloud` is expected (`into`), so callers never need to care which collection their points are in.
  */
into final case class PointCloud(points: Array[Array[Double]]):
  PointCloud.check(points, "PointCloud")
  def size: Int = points.length
  def ambientDimension: Int = if points.isEmpty then 0 else points.head.length
  lazy val metricSpace: EuclideanMetricSpace = new EuclideanMetricSpace(points)
// format: on

object PointCloud:
  // Every way into the library's Euclidean machinery checks its points here, so the messages agree: `owner` names the
  // class the user called. Returns normally when all rows have the first row's length and every entry is finite.
  // `private[tda4j]` keeps a generic helper name out of every user's wildcard import of the flat package.
  private[tda4j] def check(points: Array[Array[Double]], owner: String): Unit =
    val dimension = if points.isEmpty then 0 else points.head.length
    val ragged = points.indexWhere(_.length != dimension)
    require(
      ragged < 0,
      s"$owner: the points have different numbers of coordinates (the first has $dimension, point $ragged has " +
        s"${points(ragged).length})"
    )
    val bad = points.indexWhere(!_.forall(_.isFinite))
    require(bad < 0, s"$owner: point $bad has a coordinate that is NaN or infinite")

  given fromArrays: Conversion[Array[Array[Double]], PointCloud] = PointCloud(_)
  given fromSeqs: Conversion[Seq[Seq[Double]], PointCloud] = s => PointCloud(s.map(_.toArray).toArray)
  given fromSeqOfArrays: Conversion[Seq[Array[Double]], PointCloud] = s => PointCloud(s.toArray)

/** A complex built from a point cloud alone -- implemented by the dispatcher objects `VietorisRips`, `Cech` and
  * `AlphaShapes`, so they double as the choice in `Persistence(points, complex = Cech)`. (Complexes that need more
  * input than points -- witness landmarks, a Dowker relation -- are built directly and passed to `Persistence` as a
  * stream.)
  */
trait PointCloudComplex:
  /** The complex of `points` up to homological degree `maxDimension`, with simplices up to `maxFiltrationValue` (in the
    * complex's own unit: an edge length for `VietorisRips`, a radius for `Cech` and `AlphaShapes`). The three built-in
    * complexes stop at the points' minimum enclosing radius (`FiniteMetricSpace.minimumEnclosingRadius`) when none is
    * given: the barcode does not change after it.
    */
  def fromPoints(
    points: PointCloud,
    maxDimension: Int,
    maxFiltrationValue: Option[Double]
  ): LevelwiseSimplexStream[Int, Double]
