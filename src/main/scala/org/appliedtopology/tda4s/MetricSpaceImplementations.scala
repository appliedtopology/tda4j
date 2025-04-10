package org.appliedtopology.tda4s

import scala.math._

/** Represents a Metric Space with a generic method to compute distance between two points. */
trait MetricSpace[T] {
  def distance(x: T, y: T): Double
  def elements: Seq[T]
}

/** An implementation of MetricSpace based on a precomputed distance matrix. */
class DistanceMatrixMetricSpace(matrix: Array[Array[Double]]) extends MetricSpace[Int] {
  if (!matrix.forall(_.length == matrix.length)) {
    throw new IllegalArgumentException("Distance matrix must be square")
  }
  override def distance(x: Int, y: Int): Double = {
    if (x < 0 || y < 0 || x >= matrix.length || y >= matrix.length)
      throw new IndexOutOfBoundsException("Point index out of bounds")
    matrix(x)(y)
  }
  override def elements: Seq[Int] = (0 until matrix.length).toSeq
}

/** An implementation of MetricSpace for vectors with configurable distance metrics. */
class VectorMetricSpace(metric: VectorMetricSpace.DistanceMetric, points: Seq[Array[Double]]) extends MetricSpace[Int] {
  override def distance(x: Int, y: Int): Double = {
    metric(points(x), points(y))
  }
  override def elements: Seq[Int] = points.indices.toSeq
}

object VectorMetricSpace {
  // Type alias for different distance metric functions
  type DistanceMetric = (Array[Double], Array[Double]) => Double

  private val lpRegex = """lp\((\d+(\.\d+)?)\)""".r
  def apply(metricName: String): DistanceMetric = metricName match {
    case "euclidean" => lp(2.0)
    case "cosine" => cosine
    case "correlation" => correlation
    case "hamming" => lp(0.0)
    case "manhattan" => lp(1.0)
    case lpRegex(p) if p.toDouble > 0.0 => lp(p.toDouble)
    case _ => throw new IllegalArgumentException(s"Unknown metric: $metricName")
  }

  /** Lp distance metric for any value of p (p > 0). */
  def lp(p: Double): DistanceMetric = p match {
    case p if p < 0.0 => throw new IllegalArgumentException("p must be positive")
    case 0.0 => (x,y) => x.zip(y).count { case (xi, yi) => xi != yi }
    case Double.PositiveInfinity => (x,y) => x.zip(y).map { case (xi,yi) => abs(xi-yi) }.max
    case p if p < 1.0 => (x, y) => sqrt(x.zip(y).map { case (xi, yi) => pow(abs(xi - yi), p) }.sum)
    case p if p >= 1.0 => (x, y) => pow(x.zip(y).map { case (xi, yi) => pow(abs(xi - yi), p) }.sum, 1.0 / p)
  }

  def euclidean = lp(2.0)
  def hamming = lp(0.0)
  def manhattan = lp(1.0)

  /** Cosine distance metric (1 - cosine similarity). */
  val cosine: DistanceMetric = (x, y) => {
    val dotProduct = x.zip(y).map { case (xi, yi) => xi * yi }.sum
    val normX = sqrt(x.map(pow(_, 2)).sum)
    val normY = sqrt(y.map(pow(_, 2)).sum)
    1 - dotProduct / (normX * normY)
  }

  /** Correlation distance metric (1 - Pearson correlation). */
  val correlation: DistanceMetric = (x, y) => {
    val meanX = x.sum / x.length
    val meanY = y.sum / y.length
    val centeredX = x.map(_ - meanX)
    val centeredY = y.map(_ - meanY)
    val dotProduct = centeredX.zip(centeredY).map { case (xi, yi) => xi * yi }.sum
    val normX = sqrt(centeredX.map(pow(_, 2)).sum)
    val normY = sqrt(centeredY.map(pow(_, 2)).sum)
    1 - dotProduct / (normX * normY)
  }
}