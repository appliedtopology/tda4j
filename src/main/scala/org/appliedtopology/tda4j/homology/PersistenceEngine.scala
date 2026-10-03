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

  def cohomology[CellT: OrderedCell, C: Field]: PersistenceEngine[CellT, C] =
    new PersistenceEngine[CellT, C]:
      def barcode(
        stream: StratifiedCellStream[CellT, Double],
        includeZeroLength: Boolean
      ): List[PersistenceBar[Double, Chain[CellT, C]]] =
        CellularCohomologyEngine[CellT, C, Double]().persistentCohomology(stream, includeZeroLength)
