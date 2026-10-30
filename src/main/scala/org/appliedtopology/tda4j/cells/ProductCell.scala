package org.appliedtopology.tda4j

import cats.Show

/** A cell `left × right` of the product of two cell complexes, of dimension `dim(left) + dim(right)`, with the boundary
  * `∂(a × b) = ∂a × b + (-1)^dim(a) a × ∂b` (so `∂∂ = 0` over any field). Chains over these cells are the tensor
  * product of the chain complexes; build the cells of a filtered product with [[ProductComplex]].
  *
  * Two product cells are equal when their factors are: the context is how the cell knows the structure of its factors,
  * not part of its identity.
  */
// Not `open`: a value type whose equality is that of its two factors (openness reason 1 applies to the constructor,
// which takes the context unchecked; `ProductComplex` is the checked way in).
final case class ProductCell[A, B](left: A, right: B)(val context: ProductCell.Context[A, B]):
  override def toString: String = s"($left × $right)"

object ProductCell:

  /** The structure of the cells of the two factors, which the cells of one product share. */
  final class Context[A, B](val leftCells: A is OrderedCell, val rightCells: B is OrderedCell)

  /** The cell structure of the product: the dimensions add, the order is the lexicographic one, the boundary the
    * Leibniz rule.
    */
  given productIsOrderedCell: [A, B] => (ProductCell[A, B] is OrderedCell) =
    new (ProductCell[A, B] is OrderedCell):
      override lazy val ordering: Ordering[ProductCell[A, B]] = new Ordering[ProductCell[A, B]]:
        def compare(x: ProductCell[A, B], y: ProductCell[A, B]): Int =
          x.context.leftCells.ordering.compare(x.left, y.left) match
            case 0 => x.context.rightCells.ordering.compare(x.right, y.right)
            case c => c
      extension (c: ProductCell[A, B])
        override def dim: Int = c.context.leftCells.dim(c.left) + c.context.rightCells.dim(c.right)
        override def boundary[CoefficientT: Field as fr]: Seq[(ProductCell[A, B], CoefficientT)] =
          val context = c.context
          val sign = if context.leftCells.dim(c.left) % 2 == 0 then fr.one else fr.negate(fr.one)
          val fromLeft =
            context.leftCells.boundary(c.left)[CoefficientT].map((face, k) => (ProductCell(face, c.right)(context), k))
          val fromRight = context.rightCells
            .boundary(c.right)[CoefficientT]
            .map((face, k) => (ProductCell(c.left, face)(context), fr.times(sign, k)))
          fromLeft ++ fromRight

  given productShow: [A: Show, B: Show] => Show[ProductCell[A, B]] =
    Show.show(p => s"${summon[Show[A]].show(p.left)} × ${summon[Show[B]].show(p.right)}")
end ProductCell
