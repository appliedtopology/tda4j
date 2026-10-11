package org.appliedtopology.tda4j

/** "Run to the end and give me the barcode", for the engines that take a stream and work for any cell type: naive
  * (`CellularHomologyEngine`), chunks (`CellularPersistenceInChunksEngine`) and cohomology
  * (`CellularCohomologyEngine`). The engines themselves offer more (a cursor, queries at intermediate values); this is
  * the common denominator, used where the engine is chosen at runtime.
  *
  * The Ripser engines are not instances: they take a metric space rather than a stream.
  *
  * For `naive` and `cohomology` the stream decides the top degree: to get degrees `0 .. k`, hand them a stream with
  * cells up to dimension `k + 1` and ignore the bars of degree `k + 1`. `chunks(maxDim)` takes the degree itself.
  */
trait PersistenceEngine[CellT, C]:
  /** The finished barcode of `stream`; zero-length bars only if `includeZeroLength`. */
  def barcode(
    stream: StratifiedCellStream[CellT, Double],
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[Double, Chain[CellT, C]]]

object PersistenceEngine:
  def naive[CellT: OrderedCell, C: Field]: PersistenceEngine[CellT, C] =
    new PersistenceEngine[CellT, C]:
      def barcode(
        stream: StratifiedCellStream[CellT, Double],
        includeZeroLength: Boolean
      ): List[PersistenceBar[Double, Chain[CellT, C]]] =
        val state = CellularHomologyEngine[CellT, C, Double]().persistentHomology(stream)
        state.advanceAll()
        state.barcodeAt(Double.PositiveInfinity, includeZeroLength)

  /** The chunks engine reporting degrees `0 .. maxDim`. */
  def chunks[CellT: OrderedCell, C: Field](maxDim: Int): PersistenceEngine[CellT, C] =
    new PersistenceEngine[CellT, C]:
      def barcode(
        stream: StratifiedCellStream[CellT, Double],
        includeZeroLength: Boolean
      ): List[PersistenceBar[Double, Chain[CellT, C]]] =
        CellularPersistenceInChunksEngine[CellT, C](maxDim)
          .persistentHomology(stream)
          .barcodeAt(Double.PositiveInfinity, includeZeroLength)

  /** The naive engine's pairing, with cocycles as representatives ([[Involution.cocycleBars]]). */
  def naiveCocycles[CellT: OrderedCell, C: Field]: PersistenceEngine[CellT, C] =
    new PersistenceEngine[CellT, C]:
      def barcode(
        stream: StratifiedCellStream[CellT, Double],
        includeZeroLength: Boolean
      ): List[PersistenceBar[Double, Chain[CellT, C]]] =
        val pairs = CellularHomologyEngine[CellT, C, Double]().persistentHomology(stream).pairing
        Involution.cocycleBars[CellT, C](stream, pairs, stream.filtrationOrdering.reverse, includeZeroLength)

  /** The chunks engine's pairing (degrees `0 .. maxDim`), with cocycles as representatives. */
  def chunksCocycles[CellT: OrderedCell, C: Field](maxDim: Int): PersistenceEngine[CellT, C] =
    new PersistenceEngine[CellT, C]:
      def barcode(
        stream: StratifiedCellStream[CellT, Double],
        includeZeroLength: Boolean
      ): List[PersistenceBar[Double, Chain[CellT, C]]] =
        val pairs = CellularPersistenceInChunksEngine[CellT, C](maxDim).persistentHomology(stream).pairing
        Involution.cocycleBars[CellT, C](stream, pairs, stream.filtrationOrdering.reverse, includeZeroLength)

  /** The cohomology engine's pairing, with cycles as representatives ([[Involution]]). */
  def cohomologyCycles[CellT: OrderedCell, C: Field]: PersistenceEngine[CellT, C] =
    new PersistenceEngine[CellT, C]:
      def barcode(
        stream: StratifiedCellStream[CellT, Double],
        includeZeroLength: Boolean
      ): List[PersistenceBar[Double, Chain[CellT, C]]] = stream match
        case grid: CubicalGridStream =>
          onGrid(PackedCubicalCohomologyEngine[C](grid, grid.ambientDim).persistentHomology(includeZeroLength))
        case _ => CellularCohomologyEngine[CellT, C, Double]().persistentHomology(stream, includeZeroLength)

  def cohomology[CellT: OrderedCell, C: Field]: PersistenceEngine[CellT, C] =
    new PersistenceEngine[CellT, C]:
      def barcode(
        stream: StratifiedCellStream[CellT, Double],
        includeZeroLength: Boolean
      ): List[PersistenceBar[Double, Chain[CellT, C]]] = stream match
        case grid: CubicalGridStream =>
          onGrid(PackedCubicalCohomologyEngine[C](grid, grid.ambientDim).persistentCohomology(includeZeroLength))
        case _ => CellularCohomologyEngine[CellT, C, Double]().persistentCohomology(stream, includeZeroLength)

  // On a cubical grid the packed engine gives exactly the generic engine's bars and representatives
  // (PackedCubicalCohomologySpec); the stream's cells are cubes, so its bars are the caller's.
  private def onGrid[CellT, C](
    bars: List[PersistenceBar[Double, Chain[Cube, C]]]
  ): List[PersistenceBar[Double, Chain[CellT, C]]] =
    bars.asInstanceOf[List[PersistenceBar[Double, Chain[CellT, C]]]]
