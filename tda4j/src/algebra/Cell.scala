package org.appliedtopology.tda4j
package algebra

trait HasDimension:
  type Self
  extension (self: Self) def dim: Int

trait Cell extends HasDimension:
  type Self
  extension (self: Self) def boundary[CoefficientT: Field]: Seq[(Self, CoefficientT)]

trait OrderedCell extends Cell:
  type Self: Ordering as ordering

given [CellT: OrderedCell as oCell] => Ordering[CellT] = oCell.ordering

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
