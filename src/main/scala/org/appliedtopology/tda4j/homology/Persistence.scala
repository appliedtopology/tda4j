package org.appliedtopology.tda4j

/** A grayscale image (or voxel grid) for `Persistence(Image(...))`: `values` in row-major order, `shape` its size per
  * axis. Sublevel filtration by default (dark regions first); `sublevel = false` for superlevel.
  */
final case class Image(values: IndexedSeq[Double], shape: IndexedSeq[Int], sublevel: Boolean = true):
  require(values.length == shape.product, s"Image: ${values.length} values for shape $shape (${shape.product} expected)")

object Image:
  /** A 2-D image from its rows. */
  def apply(rows: Array[Array[Double]]): Image =
    Image(rows.flatten.toIndexedSeq, IndexedSeq(rows.length, if rows.isEmpty then 0 else rows.head.length))

/** Persistent homology in one call: `Persistence(points)`.
  *
  * {{{
  * Persistence(points)                                   // Vietoris-Rips, degrees 0..1, coefficients F_17
  * Persistence(points, complex = Cech, maxDimension = 2)
  * Persistence(points, maxFiltrationValue = 0.5)
  * Persistence(Image(pixels))                            // cubical, sublevel
  * Persistence(stream, maxDimension = 2)                 // any complex you built yourself (witness, Dowker, ...)
  * }}}
  *
  * Returns a [[PersistenceDiagram]]: every bar of degree `0 .. maxDimension` with its representative cycle, as an
  * immutable value. This runs the computation to the end; for a long run you want to inspect while it goes (or keep
  * if it dies), build an engine and use its cursor (`advanceFor`, `diagramAt`) instead.
  *
  * @param input
  *   points (`Array[Array[Double]]`, `Seq[Seq[Double]]`, `Seq[Array[Double]]`), a `FiniteMetricSpace[Int]`, an
  *   [[Image]], or any `StratifiedCellStream` -- converted to [[Persistence.Input]] at the call site.
  * @param maxDimension
  *   the top homological degree to compute (point clouds and metric spaces; for a stream, the degrees reported).
  * @param maxFiltrationValue
  *   for point clouds and metric spaces: where to stop the filtration (a number; default: the minimum enclosing radius,
  *   past which nothing new happens). Ignored for images and streams.
  * @param complex
  *   for point clouds: `VietorisRips` (default), `Cech` or `AlphaShapes`.
  * @param characteristic
  *   the coefficient field: a prime `p` for `Z/p` (default `FiniteField.DefaultPrime`, 17), or `0` for real
  *   coefficients.
  * @param engine
  *   `Engine.Chunks` (default: the clearing/chunks algorithm) or `Engine.Naive` (the reference algorithm).
  */
object Persistence:
  enum Engine:
    case Chunks, Naive

  /** What `Persistence` can take; never written by hand -- each kind of input converts to one where it's expected. */
  into sealed trait Input[CellT]:
    private[tda4j] def stream(
      maxDimension: Int,
      maxFiltrationValue: Option[Double],
      complex: PointCloudComplex
    ): StratifiedCellStream[CellT, Double]
    private[tda4j] def scale: Option[Double]
    private[tda4j] def cells: CellT is OrderedCell

  object Input:
    private def ofMetricSpace(ms: FiniteMetricSpace[Int], points: Option[PointCloud]): Input[Simplex[Int]] =
      new Input[Simplex[Int]]:
        def stream(maxDimension: Int, maxFiltrationValue: Option[Double], complex: PointCloudComplex) =
          points match
            case Some(pc) => complex.fromPoints(pc, maxDimension, maxFiltrationValue)
            case None     =>
              require(complex eq VietorisRips, "Persistence: a metric space only supports complex = VietorisRips")
              VietorisRips(ms, maxDimension, maxFiltrationValue)
        lazy val scale = Some(ms.minimumEnclosingRadius)
        def cells = summon[Simplex[Int] is OrderedCell]

    given fromPointCloud: Conversion[PointCloud, Input[Simplex[Int]]] = pc => ofMetricSpace(pc.metricSpace, Some(pc))
    given fromArrays: Conversion[Array[Array[Double]], Input[Simplex[Int]]] = a => fromPointCloud(PointCloud(a))
    given fromSeqs: Conversion[Seq[Seq[Double]], Input[Simplex[Int]]] = s => fromPointCloud(PointCloud.fromSeqs(s))
    given fromSeqOfArrays: Conversion[Seq[Array[Double]], Input[Simplex[Int]]] = s =>
      fromPointCloud(PointCloud.fromSeqOfArrays(s))
    given fromMetricSpace: Conversion[FiniteMetricSpace[Int], Input[Simplex[Int]]] = ms => ofMetricSpace(ms, None)

    given fromImage: Conversion[Image, Input[Cube]] = img =>
      new Input[Cube]:
        def stream(maxDimension: Int, maxFiltrationValue: Option[Double], complex: PointCloudComplex) =
          CubicalImage.fromFlatArray(img.shape, img.values, img.sublevel)
        def scale = None
        def cells = summon[Cube is OrderedCell]

    given fromStream: [CellT: OrderedCell as oc] => Conversion[StratifiedCellStream[CellT, Double], Input[CellT]] =
      s =>
        new Input[CellT]:
          def stream(maxDimension: Int, maxFiltrationValue: Option[Double], complex: PointCloudComplex) = s
          def scale = None
          def cells = oc

  def apply[CellT](
    input: Input[CellT],
    maxDimension: Int = 1,
    maxFiltrationValue: Optional[Double] = Optional.empty,
    complex: PointCloudComplex = VietorisRips,
    characteristic: Int = FiniteField.DefaultPrime,
    engine: Engine = Engine.Chunks
  ): PersistenceDiagram[CellT] =
    require(maxDimension >= 0, s"Persistence: maxDimension must be >= 0, got $maxDimension")
    given (CellT is OrderedCell) = input.cells
    compute(input.stream(maxDimension, maxFiltrationValue.toOption, complex), maxDimension, characteristic, engine, input.scale)

  private def compute[CellT: OrderedCell](
    stream: StratifiedCellStream[CellT, Double],
    maxDimension: Int,
    characteristic: Int,
    engine: Engine,
    scale: Option[Double]
  ): PersistenceDiagram[CellT] =
    val coefficients = Coefficients(characteristic)
    import coefficients.given
    val (bars, last) = engine match
      case Engine.Chunks =>
        val state = CellularPersistenceInChunksEngine[CellT, coefficients.C](maxDimension).persistentHomology(stream)
        (state.barcodeAt(Double.PositiveInfinity), state.lastFiltrationValue)
      case Engine.Naive =>
        val state = CellularHomologyEngine[CellT, coefficients.C, Double]().persistentHomology(stream)
        state.advanceAll()
        (state.barcodeAt(Double.PositiveInfinity), state.lastFiltrationValue.getOrElse(Double.NegativeInfinity))
    PersistenceDiagram[CellT, coefficients.C](bars.filter(_.dim <= maxDimension), maxDimension, last, scale)
