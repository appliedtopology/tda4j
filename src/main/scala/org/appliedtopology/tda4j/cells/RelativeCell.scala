package org.appliedtopology.tda4j

import cats.Show

/** A cell of the quotient complex of a pair `(X, A)`: a cell of `X` that is not in the subcomplex `A`, with a boundary
  * that leaves out the faces in `A`. Chains over these cells are the relative chains `C(X) / C(A)`, so the persistent
  * homology of a stream of them is that of the pair. `cell` is the cell of `X`; build them with [[Relative]].
  *
  * Two relative cells are equal when their cells are: the context is how the cell knows `A`, not part of its identity.
  */
// Not `open`: a value type whose equality is that of `cell` alone (openness reason 1 applies to the constructor, which
// takes the context unchecked; `Relative` is the checked way in).
final case class RelativeCell[CellT](cell: CellT)(val context: RelativeCell.Context[CellT]):
  override def toString: String = s"Relative($cell)"

object RelativeCell:

  /** What the cells of one quotient share: which cells of `X` are in `A`, and the structure of the cells of `X`. */
  final class Context[CellT](val inSubcomplex: CellT => Boolean, val ambient: CellT is OrderedCell)

  /** The cell structure of the quotient: the dimension and the order of the cell of `X`, the boundary of the cell of
    * `X` without the faces in `A`.
    */
  given relativeIsOrderedCell: [CellT] => (RelativeCell[CellT] is OrderedCell) =
    new (RelativeCell[CellT] is OrderedCell):
      override lazy val ordering: Ordering[RelativeCell[CellT]] = new Ordering[RelativeCell[CellT]]:
        def compare(x: RelativeCell[CellT], y: RelativeCell[CellT]): Int =
          x.context.ambient.ordering.compare(x.cell, y.cell)
      extension (c: RelativeCell[CellT])
        override def dim: Int = c.context.ambient.dim(c.cell)
        override def boundary[CoefficientT: Field]: Seq[(RelativeCell[CellT], CoefficientT)] =
          c.context.ambient.boundary(c.cell)[CoefficientT].collect {
            case (face, coefficient) if !c.context.inSubcomplex(face) => (RelativeCell(face)(c.context), coefficient)
          }

  given relativeShow: [CellT: Show] => Show[RelativeCell[CellT]] =
    Show.show(r => summon[Show[CellT]].show(r.cell))
end RelativeCell
