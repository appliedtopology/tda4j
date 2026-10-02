package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.cells.{given, *}

/** One entry point per kind of complex, each with the shape of [[VietorisRips]]: `maxDimension` is the top HOMOLOGICAL
  * degree you want (`H_0 .. H_maxDimension` computable by any engine; the stream itself contains one dimension more,
  * and an engine run on it reports incomplete classes in degree `maxDimension + 1` -- drop them), and the result is a
  * [[StratifiedSimplexStream]] you hand to an engine.
  *
  * These replace reaching for the implementation classes (`CechCofaceSimplexStream`, `LazyWitnessSimplexStream`,
  * `WitnessCofaceSimplexStream`, `DowkerCofaceSimplexStream`, `DtmRipsSimplexStream`, `SheehyRipsSimplexStream`) and
  * wrapping them in `LimitedCofaceSimplexStream(stream, k + 1)` by hand. See `.claude/DESIGN-stream-naming.md`.
  */
object Truncated:

  /** `stream` cut off above homological degree `maxDimension` (keeps simplices of dimension `<= maxDimension + 1`). */
  def apply(stream: CofaceSimplexStream[Int, Double], maxDimension: Int): StratifiedSimplexStream[Int, Double] =
    require(maxDimension >= 0, s"maxDimension must be >= 0, got $maxDimension")
    LimitedCofaceSimplexStream(stream, maxDimension + 1)

/** The Cech complex of a Euclidean point cloud; `maxFiltrationValue` is in Cech RADIUS units. */
object Cech:
  def apply(
    metricSpace: EuclideanMetricSpace,
    maxDimension: Int = 2,
    maxFiltrationValue: Option[Double] = None,
    parallelFiltrationValue: Boolean = false
  ): StratifiedSimplexStream[Int, Double] =
    Truncated(
      CechCofaceSimplexStream(
        metricSpace,
        maxFiltrationValue = maxFiltrationValue,
        parallelFiltrationValue = parallelFiltrationValue
      ),
      maxDimension
    )

/** The witness complex on chosen landmarks (vertex ids are LOCAL landmark indices `0 until landmarks.size`). */
object Witness:
  enum Variant:
    /** A flag complex (De Silva-Carlsson's lazy witness complex); `nu` in `0..2`. */
    case Lazy

    /** The general witness complex: not a flag complex; `nu` plays no role. */
    case General

  /** `maxFiltrationValue`: for [[Variant.Lazy]] `None` means the metric space's enclosing radius; for
    * [[Variant.General]] `None` means untruncated (`+Infinity`) -- cap the dimension, or a large landmark set explodes.
    */
  def apply(
    metricSpace: FiniteMetricSpace[Int],
    landmarks: IndexedSeq[Int],
    maxDimension: Int = 2,
    variant: Variant = Variant.Lazy,
    nu: Int = 2,
    maxFiltrationValue: Option[Double] = None
  ): StratifiedSimplexStream[Int, Double] =
    variant match
      case Variant.Lazy =>
        Truncated(
          LazyWitnessSimplexStream(metricSpace, landmarks, nu, maxFiltrationValue = maxFiltrationValue),
          maxDimension
        )
      case Variant.General =>
        Truncated(
          WitnessCofaceSimplexStream(metricSpace, landmarks, maxFiltrationValue.getOrElse(Double.PositiveInfinity)),
          maxDimension
        )

/** The Dowker complex of a relation `R: L x W -> [0, Infinity]` (vertices are the rows). `dual = true` gives the
  * complex on the other side (the transpose relation), which Dowker duality makes homotopy equivalent.
  */
object Dowker:
  def apply(
    relation: Array[Array[Double]],
    maxDimension: Int = 2,
    maxFiltrationValue: Double = Double.PositiveInfinity,
    dual: Boolean = false
  ): StratifiedSimplexStream[Int, Double] =
    val geometry = if dual then DowkerGeometry(relation).dual else DowkerGeometry(relation)
    Truncated(DowkerCofaceSimplexStream(geometry, maxFiltrationValue), maxDimension)

/** The distance-to-measure-weighted Rips complex (Anai et al.); `p` is `1.0` (default) or `2.0`. */
object DtmRips:

  /** With the DTM weights `f` given. */
  def apply(
    metricSpace: FiniteMetricSpace[Int],
    f: IndexedSeq[Double],
    maxDimension: Int = 2,
    p: Double = 1.0,
    maxFiltrationValue: Option[Double] = None
  ): StratifiedSimplexStream[Int, Double] =
    Truncated(DtmRipsSimplexStream(metricSpace, f, p, maxFiltrationValue = maxFiltrationValue), maxDimension)

  /** With the weights computed as the distance to the measure with `k` nearest neighbours (self-inclusive) and exponent
    * `q`.
    */
  def fromNeighbours(
    metricSpace: FiniteMetricSpace[Int],
    k: Int,
    maxDimension: Int = 2,
    q: Double = 2.0,
    p: Double = 1.0,
    maxFiltrationValue: Option[Double] = None
  ): StratifiedSimplexStream[Int, Double] =
    apply(metricSpace, DistanceToMeasure(metricSpace, k, q), maxDimension, p, maxFiltrationValue)

/** Sheehy's sparse (approximate) Rips complex, Cavanna-Jahanseir-Sheehy 2015; `epsilon` strictly in `(0, 1)`. */
object SparseRips:
  def apply(
    metricSpace: FiniteMetricSpace[Int],
    epsilon: Double,
    maxDimension: Int = 2,
    firstPoint: Int = 0,
    maxFiltrationValue: Option[Double] = None
  ): StratifiedSimplexStream[Int, Double] =
    Truncated(
      SheehyRipsSimplexStream(metricSpace, epsilon, firstPoint, maxFiltrationValue = maxFiltrationValue),
      maxDimension
    )
