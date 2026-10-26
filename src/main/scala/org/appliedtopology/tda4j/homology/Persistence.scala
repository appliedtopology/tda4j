package org.appliedtopology.tda4j

/** A grayscale image (or voxel grid) for `Persistence(Image(...))`: `values` in row-major order, `shape` its size per
  * axis. Sublevel filtration by default (dark regions first); `sublevel = false` for superlevel. A pixel of value
  * `Double.NaN` is masked (missing data): it is not in the complex, in either direction (NaN has no sign). Infinite
  * values are ordinary values: `Double.PositiveInfinity` enters after every finite value of a sublevel image and before
  * them in a superlevel one, `Double.NegativeInfinity` the reverse. `nanMask = false` turns masks off, for data where a
  * NaN can only be a bug: a NaN pixel is then an error, naming the pixel.
  */
final case class Image(
  values: IndexedSeq[Double],
  shape: IndexedSeq[Int],
  sublevel: Boolean = true,
  nanMask: Boolean = true
):
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
  * @param computeAllRepresentatives
  *   read only by `Engine.ParallelRipser`: `true` (default) builds every bar's representative before returning, as the
  *   other engines do; `false` builds each when it is first read (`bar.representative.terms`), which costs nothing for
  *   the bars you never look at.
  * @param threads
  *   how many threads the computation may use, the representatives built later included (default: all the processors).
  *   `1` is the sequential computation, and makes `Engine.Auto` choose `Engine.Ripser`. The bars do not depend on it.
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
    *   - `Auto` (default): `Ripser` for the Vietoris-Rips complex of points or a metric space (`ParallelRipser` instead
    *     when there is more than one thread and it applies), `FastCubical` for an image (or cubical grid) of dimension
    *     2 and up when cycles are asked for, `Cohomology` for everything else.
    *   - `Ripser`: Bauer's Ripser, for the Vietoris-Rips complex of points or a metric space only; the fastest there.
    *     Cycles or cocycles.
    *   - `Cohomology`: persistent cohomology of any complex. Cycles or cocycles.
    *   - `FastCubical`: union-find on an image and its dual grid, for images of dimension 2 and up. Cycles only.
    *   - `Chunks`: homology by clearing and compression, with union-find in degrees 0 and 1. Cycles or cocycles. Slow
    *     in degree 2 and up on Vietoris-Rips and Čech complexes, which have many cells of the top dimension.
    *   - `Naive`: the reference algorithm, one cell at a time. Cycles or cocycles.
    *   - `ParallelRipser`: Ripser's reduction cut into blocks that run on every processor, for the Vietoris-Rips
    *     complex of points or a metric space with `Z/p` coefficients (p a prime up to 46,000), and bars of positive
    *     length only. `Auto` chooses it instead of `Ripser` when there is more than one thread and it applies. Cocycles
    *     are native; cycles cost one more (sequential) reduction. Its representatives are computed when first read
    *     unless `computeAllRepresentatives` is true (the default). On JavaScript it runs on its one thread. It ranks
    *     the edges within the threshold, so its memory grows with their number (and its construction with the square of
    *     the number of points). Ties between simplices of equal diameter are broken differently from `Ripser`'s, so the
    *     bars are the same and the representatives need not be.
    *
    * Each engine computes one kind natively and derives the other from its pairing ([[Involution]]), which costs one
    * more reduction: cocycles are native to `Ripser` and `Cohomology`, cycles to the others.
    */
  enum Engine:
    case Auto, Chunks, Naive, Cohomology, Ripser, FastCubical, ParallelRipser

  /** What `Persistence` can take. Points, metric spaces, images and streams convert to one where it's expected; to
    * teach `Persistence` a new kind of input, give a `Conversion[YourInput, Persistence.Input[CellT]]` that implements
    * `stream`, `scale` and `cells` (the rest are shortcuts to faster engines, off by default).
    */
  into trait Input[CellT]:
    /** The filtered complex to compute on, cut off at homological degree `maxDimension`. `complex` is the caller's
      * choice of construction, meaningful for point clouds.
      */
    def stream(
      maxDimension: Int,
      maxFiltrationValue: Option[Double],
      complex: PointCloudComplex
    ): StratifiedCellStream[CellT, Double]

    /** The scale `significant()` measures persistence against (a point cloud's minimum enclosing radius), if any. */
    def scale: Option[Double]

    /** The cell type's boundary and order. */
    def cells: CellT is OrderedCell

    /** Whether `ripser` computes the same diagram as the generic engines would on `stream` for this `complex`. */
    def ripserApplies(complex: PointCloudComplex): Boolean = false

    /** The grid, when the input is one: `Engine.FastCubical` and the packed cubical engine run on it directly. */
    def cubicalGrid: Option[CubicalGridStream] = None

    /** The diagram by the packed Ripser engine; only called when `ripserApplies(complex)`. */
    def ripser(
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

    /** Whether the block-parallel Ripser engine computes the same bars as `ripser` for this `complex`, with the
      * representatives asked for (`Engine.Auto` chooses it over `ripser` when it does and there are several threads).
      */
    def parallelRipserApplies(
      complex: PointCloudComplex,
      maxDimension: Int,
      characteristic: Int,
      includeZeroLength: Boolean,
      cycles: Boolean
    ): Boolean = false

    /** The diagram by the block-parallel Ripser engine; only called for `Engine.ParallelRipser`. */
    def parallelRipser(
      maxDimension: Int,
      maxFiltrationValue: Option[Double],
      complex: PointCloudComplex,
      characteristic: Int,
      includeZeroLength: Boolean,
      cycles: Boolean,
      computeAllRepresentatives: Boolean
    ): PersistenceDiagram[CellT] =
      throw new IllegalArgumentException(
        "Persistence: engine = ParallelRipser computes the Vietoris-Rips complex of points or a metric space; " +
          "for this input use Engine.Chunks, Engine.Naive or Engine.Cohomology"
      )

  object Input:
    // The longest edge within the threshold, one pair at a time: collecting every pairwise distance first is n^2/2
    // values (1.25e9 at 50,000 points).
    private def longestEdgeWithin(ms: FiniteMetricSpace[Int], threshold: Double): Double =
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
      longestEdge

    private def ofMetricSpace(ms: FiniteMetricSpace[Int], points: Option[PointCloud]): Input[Simplex[Int]] =
      new Input[Simplex[Int]]:
        require(ms.size > 0, "Persistence: the input has no points")
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
              (Simplex(engine.vertices(cell, bar.dim + 1)*), c)
            })
            new PersistenceBar(bar.dim, bar.lower, bar.upper, Some(chain))
          val longestEdge = longestEdgeWithin(ms, threshold)
          PersistenceDiagram[Simplex[Int], coefficients.C](
            (if cycles then engine.persistentHomology(includeZeroLength)
             else engine.persistentCohomology(includeZeroLength)).map(decode),
            maxDimension,
            longestEdge,
            scale,
            Some(kindOf(cycles))
          )
        override def parallelRipserApplies(
          complex: PointCloudComplex,
          maxDimension: Int,
          characteristic: Int,
          includeZeroLength: Boolean,
          cycles: Boolean
        ) =
          (complex eq VietorisRips) && !includeZeroLength &&
            ParallelRipser.supports(ms.size, maxDimension, characteristic, cycles)
        override def parallelRipser(
          maxDimension: Int,
          maxFiltrationValue: Option[Double],
          complex: PointCloudComplex,
          characteristic: Int,
          includeZeroLength: Boolean,
          cycles: Boolean,
          computeAllRepresentatives: Boolean
        ) =
          require(complex eq VietorisRips, "Persistence: engine = ParallelRipser needs complex = VietorisRips")
          require(
            !includeZeroLength,
            "Persistence: engine = ParallelRipser cannot report zero-length bars; use Engine.Ripser with " +
              "includeZeroLength = true"
          )
          require(
            characteristic != 0,
            "Persistence: engine = ParallelRipser works over Z/p for a prime p, not the reals; use Engine.Ripser"
          )
          val coefficients = Coefficients(characteristic) // checks that the characteristic is a prime
          require(
            ParallelRipser.supports(ms.size, maxDimension, characteristic, cycles),
            s"Persistence: engine = ParallelRipser cannot take ${ms.size} points to degree $maxDimension over " +
              s"Z/$characteristic${if cycles then " with cycles" else ""} (a prime up to 46,000 and simplex " +
              "indices that fit 63 bits); use Engine.Ripser"
          )
          import coefficients.given
          val threshold = maxFiltrationValue.getOrElse(ms.minimumEnclosingRadius)
          val run = ParallelRipser.run(ms, maxDimension, threshold, characteristic, withPairs = cycles)
          // The cycles of every bar come out of one pass over the pairing (Involution); the cocycles one by one.
          val threads = Parallel.limit // the representatives built later keep the caller's `threads`
          lazy val allCycles = Parallel.limited(threads)(
            ParallelRipserCycles.cycles[coefficients.C](
              run,
              coefficients.fromInt(1),
              coefficients.fromInt(characteristic - 1)
            )
          )
          def simplexChain(terms: Seq[(Array[Int], coefficients.C)]) =
            Chain.from(terms.map((vertices, c) => (Simplex(vertices*), c)))
          val made = run.bars.indices.map { i =>
            val (dim, birth, death) = run.bars(i)
            val representative = new LazyChain[Simplex[Int], coefficients.C](
              if cycles then () => simplexChain(allCycles(i).map((key, c) => (run.vertices(key, dim), c)))
              else () => simplexChain(run.cocycle(i).map((vertices, c) => (vertices, coefficients.fromInt(c))))
            )(summon[Ordering[Simplex[Int]]], summon[coefficients.C is Field])
            val bar = PersistenceBar[Double, Chain[Simplex[Int], coefficients.C]](
              dim,
              ClosedEndpoint(birth),
              if death.isPosInfinity then PositiveInfinity() else OpenEndpoint(death),
              Some(representative)
            )
            (bar, representative)
          }
          // Cocycles are independent of each other, so they are built on all threads; the cycles come from one pass.
          if computeAllRepresentatives then
            if cycles then made.headOption.foreach(_._2.force())
            Parallel.foreach(made)(_._2.force())
          PersistenceDiagram[Simplex[Int], coefficients.C](
            made.map(_._1).toList,
            maxDimension,
            longestEdgeWithin(ms, threshold),
            scale,
            Some(kindOf(cycles))
          )

    given fromPointCloud: Conversion[PointCloud, Input[Simplex[Int]]] = pc => ofMetricSpace(pc.metricSpace, Some(pc))
    given fromArrays: Conversion[Array[Array[Double]], Input[Simplex[Int]]] = a => fromPointCloud(PointCloud(a))
    given fromSeqs: Conversion[Seq[Seq[Double]], Input[Simplex[Int]]] = s => fromPointCloud(PointCloud.fromSeqs(s))
    given fromSeqOfArrays: Conversion[Seq[Array[Double]], Input[Simplex[Int]]] = s =>
      fromPointCloud(PointCloud.fromSeqOfArrays(s))
    given fromMetricSpace: Conversion[FiniteMetricSpace[Int], Input[Simplex[Int]]] = ms => ofMetricSpace(ms, None)

    given fromImage: Conversion[Image, Input[Cube]] = img =>
      new Input[Cube]:
        private lazy val grid =
          CubicalImage.fromFlatArray(img.shape, img.values, img.sublevel, nanMask = img.nanMask)
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

  private def kindOf(cycles: Boolean): Representatives =
    if cycles then Representatives.Cycles else Representatives.Cocycles

  def apply[CellT](
    input: Input[CellT],
    maxDimension: Optional[Int] = Optional.empty,
    maxFiltrationValue: Optional[Double] = Optional.empty,
    complex: PointCloudComplex = VietorisRips,
    characteristic: Int = FiniteField.DefaultPrime,
    engine: Engine = Engine.Auto,
    includeZeroLength: Boolean = false,
    representatives: Representatives = Representatives.Cycles,
    computeAllRepresentatives: Boolean = true,
    threads: Optional[Int] = Optional.empty
  ): PersistenceDiagram[CellT] =
    threads.toOption match
      case Some(n) =>
        require(n >= 1, s"Persistence: threads must be at least 1, got $n")
        Parallel.withThreads(n)(
          solve(
            input,
            maxDimension,
            maxFiltrationValue,
            complex,
            characteristic,
            engine,
            includeZeroLength,
            representatives,
            computeAllRepresentatives
          )
        )
      case None =>
        solve(
          input,
          maxDimension,
          maxFiltrationValue,
          complex,
          characteristic,
          engine,
          includeZeroLength,
          representatives,
          computeAllRepresentatives
        )

  private def solve[CellT](
    input: Input[CellT],
    maxDimension: Optional[Int],
    maxFiltrationValue: Optional[Double],
    complex: PointCloudComplex,
    characteristic: Int,
    engine: Engine,
    includeZeroLength: Boolean,
    representatives: Representatives,
    computeAllRepresentatives: Boolean
  ): PersistenceDiagram[CellT] =
    val cycles = representatives == Representatives.Cycles
    val requested = maxDimension.toOption
    requested.foreach(k => require(k >= 0, s"Persistence: maxDimension must be >= 0, got $k"))
    val chosen = engine match
      case Engine.Auto =>
        if input.ripserApplies(complex) then
          if Parallel.threads > 1 && input.parallelRipserApplies(
              complex,
              requested.getOrElse(DefaultMaxDimension),
              characteristic,
              includeZeroLength,
              cycles
            )
          then Engine.ParallelRipser
          else Engine.Ripser
        else if cycles && input.cubicalGrid.exists(_.ambientDim >= 2) then Engine.FastCubical
        else Engine.Cohomology
      case other => other
    if !cycles && chosen == Engine.FastCubical then
      throw new IllegalArgumentException(
        "Persistence: engine = FastCubical gives cycles only; for cocycles use Engine.Auto or Engine.Cohomology"
      )
    maxFiltrationValue.toOption.foreach(v => require(!v.isNaN, "Persistence: maxFiltrationValue is NaN"))
    given (CellT is OrderedCell) = input.cells
    if chosen == Engine.ParallelRipser then
      input.parallelRipser(
        requested.getOrElse(DefaultMaxDimension),
        maxFiltrationValue.toOption,
        complex,
        characteristic,
        includeZeroLength,
        cycles,
        computeAllRepresentatives
      )
    else if chosen == Engine.Ripser then
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
    PersistenceDiagram[Cube, coefficients.C](
      bars.filter(_.dim <= maxDimension),
      maxDimension,
      largestPixel(grid),
      None,
      Some(Representatives.Cycles)
    )

  // Every cell takes the smallest value of the top cells containing it, so the largest value is a top cell's.
  private def largestPixel(grid: CubicalGridStream): Double =
    val pixels = grid.topCellValues
    var last = pixels(0)
    for v <- pixels do if java.lang.Double.compare(v, last) > 0 then last = v
    last

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
        val state = CellularHomologyEngine[CellT, coefficients.C, Double]().persistentHomology(stream, maxDimension)
        state.advanceAll()
        val bars =
          if cycles then state.barcodeAt(Double.PositiveInfinity, includeZeroLength)
          else Involution.cocycleBars(stream, state.pairing, stream.filtrationOrdering.reverse, includeZeroLength)
        (bars, state.lastFiltrationValue.getOrElse(Double.NegativeInfinity))
      case Engine.Cohomology =>
        stream match
          case grid: CubicalGridStream =>
            // The same bars and representatives as the generic engine on the grid, on primitive arrays.
            val engine = PackedCubicalCohomologyEngine[coefficients.C](grid, grid.ambientDim)
            val bars =
              if cycles then engine.persistentHomology(includeZeroLength)
              else engine.persistentCohomology(includeZeroLength)
            (bars.asInstanceOf[List[PersistenceBar[Double, Chain[CellT, coefficients.C]]]], largestPixel(grid))
          case _ =>
            val engine = CellularCohomologyEngine[CellT, coefficients.C, Double]()
            val bars =
              if cycles then engine.persistentHomology(stream, includeZeroLength)
              else engine.persistentCohomology(stream, includeZeroLength)
            // The engine read every cell once; iterating the stream again would build the complex a second time.
            (bars, engine.largestFiltrationValue.getOrElse(0.0))
      case Engine.Ripser | Engine.Auto | Engine.FastCubical | Engine.ParallelRipser =>
        throw new IllegalStateException("unreachable: resolved before compute")
    PersistenceDiagram[CellT, coefficients.C](
      bars.filter(_.dim <= maxDimension),
      maxDimension,
      last,
      scale,
      Some(kindOf(cycles))
    )
