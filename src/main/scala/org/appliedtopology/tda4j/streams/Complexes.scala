package org.appliedtopology.tda4j

/** A complex truncated at a homological degree. Like every complex entry point ([[VietorisRips]], [[Cech]],
  * [[Witness]], [[Dowker]], [[DtmRips]], [[SparseRips]]), `maxDimension` is the top homological degree: the stream
  * holds one dimension more, so an engine run on it also reports classes of degree `maxDimension + 1`, which are
  * incomplete (the `Persistence` verb drops them).
  */
object Truncated:

  /** `stream` cut off above homological degree `maxDimension` (keeps simplices of dimension `<= maxDimension + 1`). */
  def apply(stream: LevelwiseSimplexStream[Int, Double], maxDimension: Int): LevelwiseSimplexStream[Int, Double] =
    require(maxDimension >= 0, s"maxDimension must be >= 0, got $maxDimension")
    new TruncatedSimplexStream(stream, maxDimension + 1)

  /** The same cut for a coface stream, keeping the coface-cache members ([[CofaceSimplexStream]]) the engines read. */
  def ofCofaces(
    stream: CofaceSimplexStream[Int, Double],
    maxDimension: Int
  ): LevelwiseSimplexStream[Int, Double] =
    require(maxDimension >= 0, s"maxDimension must be >= 0, got $maxDimension")
    LimitedCofaceSimplexStream(stream, maxDimension + 1)

/** A stratified simplex stream cut off above simplex dimension `maxSimplexDimension`; filtration order and values are
  * the wrapped stream's own. (Use [[Truncated]], whose argument is a homological degree.)
  */
open class TruncatedSimplexStream(stream: LevelwiseSimplexStream[Int, Double], maxSimplexDimension: Int)
    extends LevelwiseSimplexStream[Int, Double]
    with FlatLayerStream
    with DoubleFiltration[Simplex[Int]]:
  override def flatLayers: Option[Iterator[FlatLayerStream.Layer]] = stream match
    case flat: FlatLayerStream => flat.flatLayers.map(_.take(maxSimplexDimension + 1))
    case _                     => None
  override def homologyDegreeLimit: Option[Int] =
    Some(stream.homologyDegreeLimit.fold(maxSimplexDimension - 1)(math.min(_, maxSimplexDimension - 1)))
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if d >= 0 && d <= maxSimplexDimension && stream.iterateDimension.isDefinedAt(d) => stream.iterateDimension(d)
  }
  override def filtrationOrdering: Ordering[Simplex[Int]] = stream.filtrationOrdering
  override def filtrationValue: PartialFunction[Simplex[Int], Double] = stream.filtrationValue

/** The Cech complex of a Euclidean point cloud; `maxFiltrationValue` is in Cech RADIUS units. */
object Cech extends PointCloudComplex:
  def fromPoints(points: PointCloud, maxDimension: Int, maxFiltrationValue: Option[Double]) =
    apply(points.metricSpace, maxDimension, maxFiltrationValue)

  def apply(
    metricSpace: EuclideanMetricSpace,
    maxDimension: Int = 2,
    maxFiltrationValue: Optional[Double] = Optional.empty,
    parallelFiltrationValue: Boolean = Parallel.byDefault
  ): LevelwiseSimplexStream[Int, Double] =
    Truncated.ofCofaces(
      CechCofaceSimplexStream(
        metricSpace,
        maxFiltrationValue = maxFiltrationValue.toOption,
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
    maxFiltrationValue: Optional[Double] = Optional.empty
  ): LevelwiseSimplexStream[Int, Double] =
    variant match
      case Variant.Lazy =>
        Truncated.ofCofaces(
          LazyWitnessSimplexStream(metricSpace, landmarks, nu, maxFiltrationValue = maxFiltrationValue.toOption),
          maxDimension
        )
      case Variant.General =>
        Truncated.ofCofaces(
          WitnessCofaceSimplexStream(
            metricSpace,
            landmarks,
            maxFiltrationValue.toOption.getOrElse(Double.PositiveInfinity)
          ),
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
  ): LevelwiseSimplexStream[Int, Double] =
    val geometry = if dual then DowkerGeometry(relation).dual else DowkerGeometry(relation)
    Truncated.ofCofaces(DowkerCofaceSimplexStream(geometry, maxFiltrationValue), maxDimension)

  /** The same diagram with every representative moved to the other side of the relation: from the rows to the columns,
    * or with `dual = true` (for a diagram computed with `dual = true`) from the columns to the rows. The bars are the
    * same. A cycle goes by [[DowkerGeometry.lift]] to a cycle of the other side's complex at the bar's birth that
    * represents the same bar there; a cocycle goes by [[DowkerGeometry.liftCocycle]] to a cocycle of the other side's
    * complex below the bar's death, again of the same bar. Build the complex on the smaller side and read the
    * representatives on the side you want:
    *
    * {{{
    * val geometry = WitnessGeometry(metricSpace, landmarks)   // landmarks (rows) to every point (columns)
    * val diagram = Persistence(Dowker(geometry.D, maxDimension = 1))
    * val onPoints = Dowker.lift(diagram, geometry.D)          // representatives on the points' own numbers
    *
    * val cocycles = Persistence(Dowker(geometry.D, maxDimension = 1), representatives = Representatives.Cocycles)
    * CircularCoordinates(Dowker.lift(cocycles, geometry.D), Dowker(geometry.D, maxDimension = 1, dual = true))
    * }}}
    *
    * The last line gives every point a circle coordinate from a computation on the landmarks: `CircularCoordinates`
    * takes the lifted diagram with the complex on the points. A lifted cocycle is a cochain on every simplex of its
    * dimension on the other side below the bar's death (below the diagram's last value, for a bar that does not die),
    * which for many points and degree 2 is large. The lifted bars record no birth and death cells: those are cells of
    * the side the diagram was computed on.
    */
  def lift(
    diagram: PersistenceDiagram[Simplex[Int]],
    relation: Array[Array[Double]],
    dual: Boolean = false
  ): PersistenceDiagram.Of[Simplex[Int], diagram.Coefficient] =
    import diagram.given
    val geometry = if dual then DowkerGeometry(relation).dual else DowkerGeometry(relation)
    val cocycles = diagram.representatives.contains(Representatives.Cocycles)
    def moved(bar: PersistenceBar[Double, Chain[Simplex[Int], diagram.Coefficient]]) =
      if !cocycles then bar.annotation.map(geometry.lift(_))
      else
        val before = if bar.death.isPosInfinity then Math.nextUp(diagram.lastFiltrationValue) else bar.death
        bar.annotation.map(geometry.liftCocycle(_, before))
    PersistenceDiagram[Simplex[Int], diagram.Coefficient](
      diagram.bars.map(bar => bar.copy(annotation = moved(bar), cells = None)),
      diagram.maxDimension,
      diagram.lastFiltrationValue,
      diagram.scale,
      diagram.representatives,
      diagram.representativesDieWithTheirBars
    )

/** The distance-to-measure-weighted Rips complex (Anai et al.); `p` is `1.0` (default) or `2.0`. */
object DtmRips:

  /** With the DTM weights `f` given. */
  def apply(
    metricSpace: FiniteMetricSpace[Int],
    f: IndexedSeq[Double],
    maxDimension: Int = 2,
    p: Double = 1.0,
    maxFiltrationValue: Optional[Double] = Optional.empty
  ): LevelwiseSimplexStream[Int, Double] =
    Truncated.ofCofaces(
      DtmRipsSimplexStream(metricSpace, f, p, maxFiltrationValue = maxFiltrationValue.toOption),
      maxDimension
    )

  /** With the weights computed as the distance to the measure with `k` nearest neighbours (self-inclusive) and exponent
    * `q`.
    */
  def fromNeighbours(
    metricSpace: FiniteMetricSpace[Int],
    k: Int,
    maxDimension: Int = 2,
    q: Double = 2.0,
    p: Double = 1.0,
    maxFiltrationValue: Optional[Double] = Optional.empty
  ): LevelwiseSimplexStream[Int, Double] =
    apply(metricSpace, DistanceToMeasure(metricSpace, k, q), maxDimension, p, maxFiltrationValue.toOption)

/** Sheehy's sparse (approximate) Rips complex, Cavanna-Jahanseir-Sheehy 2015; `epsilon` strictly in `(0, 1)`. */
object SparseRips:
  def apply(
    metricSpace: FiniteMetricSpace[Int],
    epsilon: Double,
    maxDimension: Int = 2,
    firstPoint: Int = 0,
    maxFiltrationValue: Optional[Double] = Optional.empty
  ): LevelwiseSimplexStream[Int, Double] =
    Truncated.ofCofaces(
      SheehyRipsSimplexStream(metricSpace, epsilon, firstPoint, maxFiltrationValue = maxFiltrationValue.toOption),
      maxDimension
    )
