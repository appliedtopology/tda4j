package org.appliedtopology.tda4j

import scala.collection.mutable

/** The product of two filtered complexes as a stream: a cell `σ × τ` for every pair of a cell of each, entering at the
  * larger of the two values (`Combine.Max`, the default: the sublevel set at `t` is the product of the two sublevel
  * sets) or at their sum (`Combine.Sum`: the tensor product of the two filtered chain complexes).
  *
  * {{{
  * val circle = VietorisRips(points, maxDimension = 2)
  * val interval = VietorisRips(otherPoints, maxDimension = 2)
  * Persistence(ProductComplex(circle, interval))        // the homology of the product, by any engine
  * Barcode.kunneth(bars1, bars2)                         // the same bars from the bars of the factors
  * }}}
  *
  * Both streams are held in memory, and the product has a cell for every pair, so this is for factors of a few hundred
  * cells each. A factor cut off at a homological degree `k` (`homologyDegreeLimit`) makes the product accurate to the
  * smaller of the two degrees. The bars carry chains of [[ProductCell]]s, `(left, right)` being the cells of the
  * factors.
  */
object ProductComplex:

  /** How the filtration values of the two factors combine in the product: `Max` for the larger one (the Künneth theorem
    * is `H_n(X × Y) = ⊕ H_p(X) ⊗ H_q(Y)`, `Barcode.tensor` of the barcodes), `Sum` for their sum (the tensor product of
    * filtered chain complexes: `Barcode.convolve` of the barcodes, plus the `Barcode.tor` terms).
    */
  enum Combine:
    case Max, Sum

  /** The product of `left` and `right`, in the filtration order of the combined values. */
  def apply[A: OrderedCell as leftCells, B: OrderedCell as rightCells](
    left: StratifiedCellStream[A, Double],
    right: StratifiedCellStream[B, Double],
    combine: Combine = Combine.Max
  ): StratifiedCellStream[ProductCell[A, B], Double] =
    val context = ProductCell.Context(leftCells, rightCells)
    def layers[C](stream: StratifiedCellStream[C, Double]): Vector[Vector[C]] =
      Iterator
        .from(0)
        .takeWhile(stream.iterateDimension.isDefinedAt)
        .map(d => stream.iterateDimension(d).toVector)
        .toVector
    val (leftLayers, rightLayers) = (layers(left), layers(right))
    val position = (
      mutable.HashMap.from(leftLayers.iterator.flatten.zipWithIndex),
      mutable.HashMap.from(rightLayers.iterator.flatten.zipWithIndex)
    )
    // Truncated factors: the product is whole up to the smaller degree limit plus one, so no cells above it.
    val homologyLimit = List(left.homologyDegreeLimit, right.homologyDegreeLimit).flatten.minOption
    val topDimension =
      if leftLayers.isEmpty || rightLayers.isEmpty then -1
      else
        val whole = leftLayers.length + rightLayers.length - 2
        homologyLimit.fold(whole)(k => math.min(whole, k + 1))
    val values = mutable.HashMap.empty[ProductCell[A, B], Double]
    val value: PartialFunction[ProductCell[A, B], Double] = new PartialFunction[ProductCell[A, B], Double]:
      def isDefinedAt(cell: ProductCell[A, B]): Boolean = values.contains(cell)
      def apply(cell: ProductCell[A, B]): Double = values(cell)
      override def applyOrElse[A1 <: ProductCell[A, B], B1 >: Double](cell: A1, default: A1 => B1): B1 =
        values.getOrElse(cell, default(cell))
    val order = FiltrationOrdering.canonical(
      value,
      (cell: ProductCell[A, B]) => leftCells.dim(cell.left) + rightCells.dim(cell.right),
      new Ordering[ProductCell[A, B]]:
        def compare(x: ProductCell[A, B], y: ProductCell[A, B]): Int =
          Integer.compare(position._1(x.left), position._1(y.left)) match
            case 0 => Integer.compare(position._2(x.right), position._2(y.right))
            case c => c
    )
    // Dimension by dimension, oldest first, each layer sorted by the one order the stream reports.
    val byDimension: Vector[Vector[ProductCell[A, B]]] =
      val leftValue = left.filtrationValue
      val rightValue = right.filtrationValue
      (0 to topDimension).map { d =>
        val cells = for
          p <- (0 to d).toVector
          if p < leftLayers.length && d - p < rightLayers.length
          a <- leftLayers(p)
          b <- rightLayers(d - p)
        yield
          val (fa, fb) = (leftValue(a), rightValue(b))
          val cell = ProductCell(a, b)(context)
          values(cell) = combine match
            case Combine.Max => math.max(fa, fb)
            case Combine.Sum => fa + fb
          cell
        cells.sorted(using order.reverse)
      }.toVector

    new StratifiedCellStream[ProductCell[A, B], Double]:
      val filtrationValue: PartialFunction[ProductCell[A, B], Double] = value
      val filtrationOrdering: Ordering[ProductCell[A, B]] = order
      def smallest: Double = left.smallest
      def largest: Double = left.largest
      override def homologyDegreeLimit: Option[Int] = homologyLimit
      def iterateDimension: PartialFunction[Int, Iterator[ProductCell[A, B]]] =
        case d if d >= 0 && d <= topDimension => byDimension(d).iterator
end ProductComplex
