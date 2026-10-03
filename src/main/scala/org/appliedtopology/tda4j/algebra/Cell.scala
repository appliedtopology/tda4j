package org.appliedtopology.tda4j

import scala.annotation.implicitNotFound

trait HasDimension:
  type Self
  extension (self: Self) def dim: Int

trait Cell extends HasDimension:
  type Self
  extension (self: Self) def boundary[CoefficientT: Field]: Seq[(Self, CoefficientT)]

// format: off
@implicitNotFound("tda4j: no cell structure for ${Self}. Simplex and Cube have one built in; for the generators of a simplicial set `x`, write `import x.given` first; for your own cell type, provide a `given (${Self} is OrderedCell)`.")
// format: on
trait OrderedCell extends Cell:
  type Self: Ordering as ordering

object OrderedCell:
  /** A cell type's intrinsic order (`Simplex`: lexicographic), for generic code that holds only `CellT: OrderedCell`
    * and no stream: `import OrderedCell.cellOrdering`.
    *
    * Deliberately NOT a top-level given. A top-level `[CellT: OrderedCell] => Ordering[CellT]` is visible everywhere in
    * the package and matches `Ordering[?T]` for any not-yet-inferred `T`, so it competed at equal priority with a
    * stream's filtration ordering and silently decided type inference (`SimplicialHomologyEngine()` inferred
    * `VertexT = BarcodeEndpoint[Cube]`). Concrete cell types get their default `Ordering` from their own companion
    * (implicit scope), which any lexically visible given beats. `.claude/WORKLOG-package-flatten.md`.
    */
  given cellOrdering: [CellT: OrderedCell as oCell] => Ordering[CellT] = oCell.ordering

/** The "leading term" (highest-priority cell and its coefficient, under `CellT`'s own order) a formal sum needs to
  * support pivot-based reduction. Currently has exactly one instance in this codebase, `Chain`'s own
  * `chainIsOrderedBasis` (`Chain.scala`) -- kept as a separate typeclass, in the same `is`-typeclass style as
  * `Cell`/`OrderedCell`, rather than folded into `Chain` as ordinary methods, so `leadingCell`/`leadingCoefficient`
  * read as a documented contract rather than incidental `Chain` API.
  */
trait OrderedBasis[CellT: Ordering, CoefficientT: Field]:
  type Self
  extension (t: Self)
    def leadingCell: Option[CellT] = leadingTerm._1
    def leadingCoefficient: CoefficientT = leadingTerm._2
    def leadingTerm: (Option[CellT], CoefficientT)
