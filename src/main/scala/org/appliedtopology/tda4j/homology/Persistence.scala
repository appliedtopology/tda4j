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
  * Persistence(points)                                   // Vietoris-Rips, degrees 0..2, coefficients F_17, cycles
  * Persistence(points, representatives = Representatives.Cocycles)   // the same bars, with cocycles
  * Persistence(points, complex = Cech, maxDimension = 1)
  * Persistence(points, maxFiltrationValue = 0.5)
  * Persistence(Image(pixels))                            // cubical, sublevel
  * Persistence(stream)                                   // any complex you built yourself (witness, Dowker, ...)
  * }}}
  *
  * Returns a [[PersistenceDiagram]]: the bars of degree `0 .. maxDimension`, each with its representative, as an
  * immutable value. Representatives are '''cycles''' by default (a loop's cycle runs around it, showing where it is);
  * `representatives = Representatives.Cocycles` gives '''cocycles''' instead (a loop's cocycle cuts across it; circular
  * coordinates are built from cocycles). The pairing is computed by cohomology either way, which is fast in degree 2;
  * cycles then cost one more reduction, of the boundaries of the cells that end bars. Zero-length bars are left out;
  * short ones are one call away (`diagram.longerThan(0.05)`, `diagram.significant()`). This runs the computation to the
  * end; for a long run you want to inspect while it goes (or keep if it dies), build an engine and use its cursor
  * (`advanceFor`, `diagramAt`) instead.
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
  *   `Persistence.Engine.Auto` (default) picks one for the input and `representatives`; see [[Persistence.Engine]].
  *   Most users never set it.
  * @param includeZeroLength
  *   also report zero-length bars `[v, v)` (cells paired with cells entering at the same value). Default `false`.
  * @param representatives
  *   `Representatives.Cycles` (default) or `Representatives.Cocycles`.
  */
/** Which representative `Persistence` gives each bar: a '''cycle''' (a chain without boundary, born at the bar's start
  * and a boundary at its end; for a loop, a path of edges around it) or a '''cocycle''' (a cochain whose coboundary is
  * zero; for a loop, a set of edges cutting across it, what circular coordinates are built from).
  */
enum Representatives:
  case Cycles, Cocycles

object Persistence:
  /** Which algorithm computes the diagram. All give the same bars; they differ in cost and in the representatives.
    *
    *   - `Auto` (default): `Ripser` for the Vietoris-Rips complex of points or a metric space, `FastCubical` for an
    *     image (or cubical grid) of dimension 2 and up when cycles are asked for, `Cohomology` for everything else.
    *   - `Ripser`: Bauer's Ripser, for the Vietoris-Rips complex of points or a metric space only; the fastest there.
    *     Cycles or cocycles.
    *   - `Cohomology`: persistent cohomology of any complex. Cycles or cocycles.
    *   - `FastCubical`: union-find on an image and its dual grid, for images of dimension 2 and up. Cycles only.
    *   - `Chunks`: homology by clearing and compression, with union-find in degrees 0 and 1. Cycles or cocycles. Slow
    *     in degree 2 and up on Vietoris-Rips and Čech complexes, which have many cells of the top dimension.
    *   - `Naive`: the reference algorithm, one cell at a time. Cycles or cocycles.
    *
    * Each engine computes one kind natively and derives the other from its pairing ([[Involution]]), which costs one
    * more reduction: cocycles are native to `Ripser` and `Cohomology`, cycles to the others.
    */
  enum Engine:
    case Auto, Chunks, Naive, Cohomology, Ripser, FastCubical

  /** What `Persistence` can take; never written by hand -- each kind of input converts to one where it's expected. */
  into sealed trait Input[CellT]:
    private[tda4j] def stream(
      maxDimension: Int,
      maxFiltrationValue: Option[Double],
      complex: PointCloudComplex
    ): StratifiedCellStream[CellT, Double]
    private[tda4j] def scale: Option[Double]
    private[tda4j] def cells: CellT is OrderedCell
    private[tda4j] def ripserApplies(complex: PointCloudComplex): Boolean = false
    private[tda4j] def cubicalGrid: Option[CubicalGridStream] = None
    private[tda4j] def ripser(
      maxDimension: Int,
      maxFiltrationValue: Option[Double],
      complex: PointCloudComplex,
      characteristic: Int,
      includeZeroLength: Boolean,
      cycles: Boolean
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
        override def ripserApplies(complex: PointCloudComplex) = complex eq VietorisRips
        override def ripser(
          maxDimension: Int,
          maxFiltrationValue: Option[Double],
          complex: PointCloudComplex,
          characteristic: Int,
          includeZeroLength: Boolean,
          cycles: Boolean
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
          // The longest edge within the threshold, one pair at a time: collecting every pairwise distance first is
          // n^2/2 values (1.25e9 at 50,000 points).
          var longestEdge = 0.0
          val n = ms.size
          var x = 0
          while x < n do
            var y = x + 1
            while y < n do
              val d = ms.distance(x, y)
              if d <= threshold && d > longestEdge then longestEdge = d
              y += 1
            x += 1
          PersistenceDiagram[Simplex[Int], coefficients.C](
            (if cycles then engine.persistentHomology(includeZeroLength)
             else engine.persistentCohomology(includeZeroLength)).map(decode),
            maxDimension,
            longestEdge,
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
        private lazy val grid = CubicalImage.fromFlatArray(img.shape, img.values, img.sublevel)
        def stream(maxDimension: Int, maxFiltrationValue: Option[Double], complex: PointCloudComplex) = grid
        override def cubicalGrid = Some(grid)
        def scale = None
        def cells = summon[Cube is OrderedCell]

    given fromStream: [CellT: OrderedCell as oc] => Conversion[StratifiedCellStream[CellT, Double], Input[CellT]] =
      s =>
        new Input[CellT]:
          def stream(maxDimension: Int, maxFiltrationValue: Option[Double], complex: PointCloudComplex) = s
          override def cubicalGrid = s match
            case g: CubicalGridStream => Some(g)
            case _                    => None
          def scale = None
          def cells = oc

  /** The top homological degree computed when `maxDimension` is not given (and the input does not fix it). */
  val DefaultMaxDimension: Int = 2

  def apply[CellT](
    input: Input[CellT],
    maxDimension: Optional[Int] = Optional.empty,
    maxFiltrationValue: Optional[Double] = Optional.empty,
    complex: PointCloudComplex = VietorisRips,
    characteristic: Int = FiniteField.DefaultPrime,
    engine: Engine = Engine.Auto,
    includeZeroLength: Boolean = false,
    representatives: Representatives = Representatives.Cycles
  ): PersistenceDiagram[CellT] =
    val cycles = representatives == Representatives.Cycles
    val chosen = engine match
      case Engine.Auto =>
        if input.ripserApplies(complex) then Engine.Ripser
        else if cycles && input.cubicalGrid.exists(_.ambientDim >= 2) then Engine.FastCubical
        else Engine.Cohomology
      case other => other
    if !cycles && chosen == Engine.FastCubical then
      throw new IllegalArgumentException(
        "Persistence: engine = FastCubical gives cycles only; for cocycles use Engine.Auto or Engine.Cohomology"
      )
    val requested = maxDimension.toOption
    requested.foreach(k => require(k >= 0, s"Persistence: maxDimension must be >= 0, got $k"))
    given (CellT is OrderedCell) = input.cells
    if chosen == Engine.Ripser then
      input.ripser(
        requested.getOrElse(DefaultMaxDimension),
        maxFiltrationValue.toOption,
        complex,
        characteristic,
        includeZeroLength,
        cycles
      )
    else if chosen == Engine.FastCubical then
      val grid = input.cubicalGrid.getOrElse(
        throw new IllegalArgumentException(
          "Persistence: engine = FastCubical computes the cubical complex of an image; for this input use " +
            "Engine.Chunks, Engine.Naive or Engine.Cohomology"
        )
      )
      require(grid.ambientDim >= 2, "Persistence: engine = FastCubical needs an image of dimension 2 or more")
      fastCubical(grid, requested.getOrElse(DefaultMaxDimension), characteristic, includeZeroLength)
        .asInstanceOf[PersistenceDiagram[CellT]]
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
        chosen,
        input.scale,
        includeZeroLength,
        cycles
      )

  private def fastCubical(
    grid: CubicalGridStream,
    maxDimension: Int,
    characteristic: Int,
    includeZeroLength: Boolean
  ): PersistenceDiagram[Cube] =
    val coefficients = Coefficients(characteristic)
    import coefficients.given
    val bars = FastCubicalHomologyEngine[coefficients.C]().persistentHomology(grid, includeZeroLength)
    // Every cell takes the smallest value of the top cells containing it, so the largest value is a top cell's.
    val topCells = grid.shape.foldLeft(Iterator(IndexedSeq.empty[Int]))((acc, n) =>
      acc.flatMap(prefix => (0 until n).iterator.map(prefix :+ _))
    )
    val last = topCells.map(grid.topCellValue).maxOption.getOrElse(0.0)
    PersistenceDiagram[Cube, coefficients.C](bars.filter(_.dim <= maxDimension), maxDimension, last, None)

  private def compute[CellT: OrderedCell](
    stream: StratifiedCellStream[CellT, Double],
    maxDimension: Int,
    characteristic: Int,
    engine: Engine,
    scale: Option[Double],
    includeZeroLength: Boolean,
    cycles: Boolean
  ): PersistenceDiagram[CellT] =
    val coefficients = Coefficients(characteristic)
    import coefficients.given
    val (bars, last) = engine match
      case Engine.Chunks =>
        val state = CellularPersistenceInChunksEngine[CellT, coefficients.C](maxDimension).persistentHomology(stream)
        val bars =
          if cycles then state.barcodeAt(Double.PositiveInfinity, includeZeroLength)
          else Involution.cocycleBars(stream, state.pairing, stream.filtrationOrdering.reverse, includeZeroLength)
        (bars, state.lastFiltrationValue)
      case Engine.Naive =>
        val state = CellularHomologyEngine[CellT, coefficients.C, Double]().persistentHomology(stream)
        state.advanceAll()
        val bars =
          if cycles then state.barcodeAt(Double.PositiveInfinity, includeZeroLength)
          else Involution.cocycleBars(stream, state.pairing, stream.filtrationOrdering.reverse, includeZeroLength)
        (bars, state.lastFiltrationValue.getOrElse(Double.NegativeInfinity))
      case Engine.Cohomology =>
        val engine = CellularCohomologyEngine[CellT, coefficients.C, Double]()
        val bars =
          if cycles then engine.persistentHomology(stream, includeZeroLength)
          else engine.persistentCohomology(stream, includeZeroLength)
        val fv = stream.filtrationValue
        (bars, stream.iterator.map(c => fv.applyOrElse(c, _ => Double.NegativeInfinity)).maxOption.getOrElse(0.0))
      case Engine.Ripser | Engine.Auto | Engine.FastCubical =>
        throw new IllegalStateException("unreachable: resolved before compute")
    PersistenceDiagram[CellT, coefficients.C](bars.filter(_.dim <= maxDimension), maxDimension, last, scale)
