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
    * and no stream: `import OrderedCell.cellOrdering`. It is not a top-level given because it would compete with every
    * stream's filtration order and decide type inference.
    */
  given cellOrdering: [CellT: OrderedCell as oCell] => Ordering[CellT] = oCell.ordering

/** A formal sum with a leading term (its first cell under `CellT`'s order, and that cell's coefficient), which pivot
  * reduction needs. `Chain` is the instance.
  */
trait OrderedBasis[CellT: Ordering, CoefficientT: Field]:
  type Self
  extension (t: Self)
    def leadingCell: Option[CellT] = leadingTerm._1
    def leadingCoefficient: CoefficientT = leadingTerm._2
    def leadingTerm: (Option[CellT], CoefficientT)
