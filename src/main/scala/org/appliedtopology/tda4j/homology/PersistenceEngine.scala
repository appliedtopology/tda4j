package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.barcode.PersistenceBar
import org.appliedtopology.tda4j.streams.{given, *}

/** A common shape for the three engines that consume an already-built `StratifiedCellStream` and are generic over
  * `CellT: OrderedCell` -- `CellularHomologyContext` (naive), `CellularPersistenceInChunksContext` (chunks), and
  * `CellularCohomologyContext` (cohomology). Each has its own incremental API beyond this (`advanceTo`/`diagramAt`
  * for naive and chunks), which stays available on the concrete class -- this trait exists only to give callers that
  * just want "the finished barcode" (the MATLAB/CLI facade) one shape to dispatch on, instead of hand-writing each
  * engine's own construct/advance/read dance at every call site.
  *
  * `PackedRipserCohomologyContext`/`RipserCohomologyContext` deliberately do NOT implement this: they consume a
  * `FiniteMetricSpace[Int]` directly (building their own internal sparse-Rips enumeration), not a stream, and are
  * specialized to `Simplex[Int]`/`DiameterIndex` rather than generic over `CellT` -- an honest asymmetry, not a
  * gap (see CLAUDE.md's persistence-engines section). Callers needing Ripser call it directly.
  *
  * Deliberately does NOT own the "build one dimension higher than requested, then drop it" dance
  * (`WORKLOG-maxdim-semantics-fix.md`'s `maxDim`-means-top-reported-degree fix): that dance is about how the INPUT
  * stream gets bounded, which is complex-type-specific (`LimitedCofaceSimplexStream` only wraps a
  * `CofaceSimplexStream[Int, Double]`, so it can truncate a Vietoris-Rips/Cech stream but not a cubical or
  * simplicial-set one -- see CLAUDE.md's `LimitedCofaceSimplexStream` note), not engine-specific. Callers build the
  * appropriately-bounded stream first (as they already did before this trait existed) and pass it in; this trait
  * only unifies what happens AFTER that -- running the engine and reading back a finished barcode.
  */
trait PersistenceEngine[CellT, C]:
  def barcode(stream: StratifiedCellStream[CellT, Double]): List[PersistenceBar[Double, Chain[CellT, C]]]

object PersistenceEngine:
  def naive[CellT: OrderedCell, C: Field]: PersistenceEngine[CellT, C] =
    new PersistenceEngine[CellT, C]:
      def barcode(stream: StratifiedCellStream[CellT, Double]): List[PersistenceBar[Double, Chain[CellT, C]]] =
        val state = CellularHomologyContext[CellT, C, Double]().persistentHomology(stream)
        state.advanceAll()
        state.barcodeAt(Double.PositiveInfinity)

  /** `maxDim` is a constructor-time parameter of `CellularPersistenceInChunksContext` itself (its own `maxDim`
    * constructor argument means "top reported degree," fixed at the source -- see
    * `.claude/WORKLOG-maxdim-semantics-fix.md`), not part of `barcode`'s signature: unlike `naive`/`cohomology`,
    * this engine needs no `LimitedCofaceSimplexStream` wrapping on the input stream at all.
    */
  def chunks[CellT: OrderedCell, C: Field](maxDim: Int): PersistenceEngine[CellT, C] =
    new PersistenceEngine[CellT, C]:
      def barcode(stream: StratifiedCellStream[CellT, Double]): List[PersistenceBar[Double, Chain[CellT, C]]] =
        CellularPersistenceInChunksContext[CellT, C](maxDim)
          .persistentHomology(stream)
          .barcodeAt(Double.PositiveInfinity)

  def cohomology[CellT: OrderedCell, C: Field]: PersistenceEngine[CellT, C] =
    new PersistenceEngine[CellT, C]:
      def barcode(stream: StratifiedCellStream[CellT, Double]): List[PersistenceBar[Double, Chain[CellT, C]]] =
        CellularCohomologyContext[CellT, C, Double]().persistentCohomology(stream)
