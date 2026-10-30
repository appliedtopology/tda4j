package org.appliedtopology.tda4j

import cats.Show

/** A critical cell of a discrete Morse complex ([[MorseComplex]]): the cell of the complex it comes from, with the
  * Morse boundary, the sum over the gradient paths from its boundary to critical cells. A stream of these
  * (`morse.stream`) has the persistent homology of the stream it comes from; `cell` is the cell of that stream, and
  * `morse.lift` takes a chain of these back to a chain of cells of it.
  *
  * Two Morse cells are equal when their cells are: the complex is how the cell finds its boundary, not part of its
  * identity.
  */
// Not `open`: a value type whose equality is that of `cell` alone (openness reason 1 applies to the constructor, which
// takes the complex unchecked; `MorseComplex.stream` is the checked way in).
final case class MorseCell[CellT](cell: CellT)(val complex: MorseComplex[CellT]):
  override def toString: String = s"Morse($cell)"

object MorseCell:

  /** The cell structure of the Morse complex: the dimension and order of the cell it comes from, the Morse boundary. */
  given morseIsOrderedCell: [CellT] => (MorseCell[CellT] is OrderedCell) =
    new (MorseCell[CellT] is OrderedCell):
      override lazy val ordering: Ordering[MorseCell[CellT]] = new Ordering[MorseCell[CellT]]:
        def compare(x: MorseCell[CellT], y: MorseCell[CellT]): Int =
          x.complex.cells.ordering.compare(x.cell, y.cell)
      extension (c: MorseCell[CellT])
        override def dim: Int = c.complex.cells.dim(c.cell)
        override def boundary[CoefficientT: Field]: Seq[(MorseCell[CellT], CoefficientT)] =
          c.complex.boundary[CoefficientT](c.cell).map((face, k) => (MorseCell(face)(c.complex), k))

  given morseShow: [CellT: Show] => Show[MorseCell[CellT]] =
    Show.show(m => summon[Show[CellT]].show(m.cell))
end MorseCell
