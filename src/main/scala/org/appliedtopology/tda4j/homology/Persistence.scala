package org.appliedtopology.tda4j

/** A grayscale image (or voxel grid) for `Persistence(Image(...))`: `values` in row-major order, `shape` its size per
  * axis. Sublevel filtration by default (dark regions first); `sublevel = false` for superlevel.
  */
final case class Image(values: IndexedSeq[Double], shape: IndexedSeq[Int], sublevel: Boolean = true):
  require(
    values.length == shape.product,
    s"Image: ${values.length} values for shape $shape (${shape.product} expected)"
  )

  /** The same image filtered from the bright end (superlevel sets). Filtration values are then negated intensities. */
  def superlevel: Image = copy(sublevel = false)

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
  * Persistence(stream)                                   // any complex you built yourself (witness, Dowker, ...)
  * }}}
  *
  * Returns a [[PersistenceDiagram]]: the bars of degree `0 .. maxDimension`, each with its representative (a cycle, or
  * a cocycle for the cohomology engines), as an immutable value. Zero-length bars are left out; short ones are one call
  * away (`diagram.longerThan(0.05)`, `diagram.significant()`). This runs the computation to the end; for a long run you
  * want to inspect while it goes (or keep if it dies), build an engine and use its cursor (`advanceFor`, `diagramAt`)
  * instead.
  *
  * @param input
  *   points (`Array[Array[Double]]`, `Seq[Seq[Double]]`, `Seq[Array[Double]]`), a `FiniteMetricSpace[Int]`, an
  *   [[Image]], or any `StratifiedCellStream` -- converted to [[Persistence.Input]] at the call site.
  * @param maxDimension
  *   the top homological degree to compute (default [[Persistence.DefaultMaxDimension]]). For a stream built for
  *   degrees `0..k` (such as `VietorisRips(points, maxDimension = k)`), the default is `k` and a larger value is an
  *   error.
  * @param maxFiltrationValue
  *   for point clouds and metric spaces: where to stop the filtration (a number; default: the minimum enclosing radius,
  *   past which nothing new happens). Ignored for images and streams.
  * @param complex
  *   for point clouds: `VietorisRips` (default), `Cech` or `AlphaShapes`.
  * @param characteristic
  *   the coefficient field: a prime `p` for `Z/p` (default `FiniteField.DefaultPrime`, 17), or `0` for real
  *   coefficients.
  * @param engine
  *   `Persistence.Engine.Chunks` (default), `Naive`, `Cohomology` or `Ripser` (Vietoris-Rips only, the fastest there);
  *   see [[Persistence.Engine]].
  * @param includeZeroLength
  *   also report zero-length bars `[v, v)` (cells paired with cells entering at the same value). Default `false`.
  */
object Persistence:
  /** Which algorithm computes the diagram. All four give the same bars; they differ in cost and representatives.
    *
    *   - `Chunks` (default): clearing and compression, union-find in degrees 0 and 1. Representatives are cycles.
    *   - `Naive`: the reference algorithm, one cell at a time. Representatives are cycles.
    *   - `Cohomology`: persistent cohomology of the same complex. Representatives are cocycles.
    *   - `Ripser`: Bauer's Ripser, for the Vietoris-Rips complex of points or a metric space only; the fastest there.
    *     Representatives are cocycles.
    */
  enum Engine:
    case Chunks, Naive, Cohomology, Ripser

  /** What `Persistence` can take; never written by hand -- each kind of input converts to one where it's expected. */
  into sealed trait Input[CellT]:
    private[tda4j] def stream(
      maxDimension: Int,
      maxFiltrationValue: Option[Double],
      complex: PointCloudComplex
    ): StratifiedCellStream[CellT, Double]
    private[tda4j] def scale: Option[Double]
    private[tda4j] def cells: CellT is OrderedCell
    private[tda4j] def ripser(
      maxDimension: Int,
      maxFiltrationValue: Option[Double],
      complex: PointCloudComplex,
      characteristic: Int,
      includeZeroLength: Boolean
    ): PersistenceDiagram[CellT] =
      throw new IllegalArgumentException(
        "Persistence: engine = Ripser computes the Vietoris-Rips complex of points or a metric space; " +
          "for this input use Engine.Chunks, Engine.Naive or Engine.Cohomology"
      )

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
        override def ripser(
          maxDimension: Int,
          maxFiltrationValue: Option[Double],
          complex: PointCloudComplex,
          characteristic: Int,
          includeZeroLength: Boolean
        ) =
          require(complex eq VietorisRips, "Persistence: engine = Ripser needs complex = VietorisRips")
          val coefficients = Coefficients(characteristic)
          import coefficients.given
          val engine =
            PackedRipserCohomologyEngine[coefficients.C](ms, maxDimension, maxFiltrationValue = maxFiltrationValue)
          val threshold = maxFiltrationValue.getOrElse(ms.minimumEnclosingRadius)
          // Cells come back packed (diameter, combinatorial index): decode them to simplices.
          def decode(bar: PersistenceBar[Double, Chain[engine.DiameterIndex, coefficients.C]]) =
            val chain = Chain.from(bar.representative.terms.map { (cell, c) =>
              (Simplex(engine.si.decodeToArray(cell.index, bar.dim + 1)*), c)
            })
            new PersistenceBar(bar.dim, bar.lower, bar.upper, Some(chain))
          val distances = for x <- ms.elements; y <- ms.elements if x < y yield ms.distance(x, y)
          PersistenceDiagram[Simplex[Int], coefficients.C](
            engine.persistentCohomology(includeZeroLength).map(decode),
            maxDimension,
            distances.filter(_ <= threshold).maxOption.getOrElse(0.0),
            scale
          )

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

  /** The top homological degree computed when `maxDimension` is not given (and the input does not fix it). */
  val DefaultMaxDimension: Int = 1

  def apply[CellT](
    input: Input[CellT],
    maxDimension: Optional[Int] = Optional.empty,
    maxFiltrationValue: Optional[Double] = Optional.empty,
    complex: PointCloudComplex = VietorisRips,
    characteristic: Int = FiniteField.DefaultPrime,
    engine: Engine = Engine.Chunks,
    includeZeroLength: Boolean = false
  ): PersistenceDiagram[CellT] =
    val requested = maxDimension.toOption
    requested.foreach(k => require(k >= 0, s"Persistence: maxDimension must be >= 0, got $k"))
    given (CellT is OrderedCell) = input.cells
    if engine == Engine.Ripser then
      input.ripser(
        requested.getOrElse(DefaultMaxDimension),
        maxFiltrationValue.toOption,
        complex,
        characteristic,
        includeZeroLength
      )
    else
      val stream = input.stream(requested.getOrElse(DefaultMaxDimension), maxFiltrationValue.toOption, complex)
      val degree = (requested, stream.homologyDegreeLimit) match
        case (Some(k), Some(limit)) if k > limit =>
          throw new IllegalArgumentException(
            s"Persistence: this complex was built for degrees 0..$limit, so degree $k would be wrong; " +
              s"build it with maxDimension = $k"
          )
        case (Some(k), _)        => k
        case (None, Some(limit)) => limit
        case (None, None)        => DefaultMaxDimension
      compute(
        stream,
        degree,
        characteristic,
        engine,
        input.scale,
        includeZeroLength
      )

  private def compute[CellT: OrderedCell](
    stream: StratifiedCellStream[CellT, Double],
    maxDimension: Int,
    characteristic: Int,
    engine: Engine,
    scale: Option[Double],
    includeZeroLength: Boolean
  ): PersistenceDiagram[CellT] =
    val coefficients = Coefficients(characteristic)
    import coefficients.given
    val (bars, last) = engine match
      case Engine.Chunks =>
        val state = CellularPersistenceInChunksEngine[CellT, coefficients.C](maxDimension).persistentHomology(stream)
        (state.barcodeAt(Double.PositiveInfinity, includeZeroLength), state.lastFiltrationValue)
      case Engine.Naive =>
        val state = CellularHomologyEngine[CellT, coefficients.C, Double]().persistentHomology(stream)
        state.advanceAll()
        (
          state.barcodeAt(Double.PositiveInfinity, includeZeroLength),
          state.lastFiltrationValue.getOrElse(Double.NegativeInfinity)
        )
      case Engine.Cohomology =>
        val bars =
          CellularCohomologyEngine[CellT, coefficients.C, Double]().persistentCohomology(stream, includeZeroLength)
        val fv = stream.filtrationValue
        (bars, stream.iterator.map(c => fv.applyOrElse(c, _ => Double.NegativeInfinity)).maxOption.getOrElse(0.0))
      case Engine.Ripser => throw new IllegalStateException("unreachable: Ripser is dispatched before compute")
    PersistenceDiagram[CellT, coefficients.C](bars.filter(_.dim <= maxDimension), maxDimension, last, scale)
