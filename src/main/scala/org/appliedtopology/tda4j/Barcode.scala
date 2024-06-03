package org.appliedtopology.tda4j

/** Barcode representation, operations and algebra
  *
  * This sub-package implements both a useful representation for persistence bars and barcodes, and also algebraic
  * operations on finitely presented persistence modules.
  */
package barcode

import org.apache.commons.math3.linear.*

sealed trait BarcodeEndpoint[FiltrationT: Ordering] {
  // Exchange open and closed for finite barcode endpoints; do nothing for infinite ones.
  def flip: BarcodeEndpoint[FiltrationT]
  def isFinite: Boolean
}

case class PositiveInfinity[FiltrationT: Ordering]() extends BarcodeEndpoint[FiltrationT] {
  override def flip: BarcodeEndpoint[FiltrationT] = this
  override val isFinite = false
}
case class NegativeInfinity[FiltrationT: Ordering]() extends BarcodeEndpoint[FiltrationT] {
  override def flip: BarcodeEndpoint[FiltrationT] = this
  override val isFinite = false
}
case class OpenEndpoint[FiltrationT: Ordering](val value: FiltrationT) extends BarcodeEndpoint[FiltrationT] {
  override def flip: BarcodeEndpoint[FiltrationT] = ClosedEndpoint(value)
  override val isFinite = true
}
case class ClosedEndpoint[FiltrationT: Ordering](val value: FiltrationT) extends BarcodeEndpoint[FiltrationT] {
  override def flip: BarcodeEndpoint[FiltrationT] = OpenEndpoint(value)
  override val isFinite = true
}

import math.Ordered.orderingToOrdered
given [FiltrationT](using
  ord: Ordering[FiltrationT]
): Ordering[BarcodeEndpoint[FiltrationT]] with
  def compare(
    x: BarcodeEndpoint[FiltrationT],
    y: BarcodeEndpoint[FiltrationT]
  ) = x match {
    case NegativeInfinity() => -1
    case PositiveInfinity() => +1
    case ClosedEndpoint(xvalue) =>
      y match {
        case NegativeInfinity()     => +1
        case PositiveInfinity()     => -1
        case ClosedEndpoint(yvalue) => ord.compare(xvalue, yvalue)
        case OpenEndpoint(yvalue) =>
          if (ord.compare(xvalue, yvalue) == 0) -1
          else ord.compare(xvalue, yvalue)
      }
    case OpenEndpoint(xvalue) =>
      y match {
        case NegativeInfinity() => +1
        case PositiveInfinity() => -1
        case ClosedEndpoint(yvalue) =>
          if (ord.compare(xvalue, yvalue) == 0) +1
          else ord.compare(xvalue, yvalue)
        case OpenEndpoint(yvalue) => ord.compare(xvalue, yvalue)
      }
  }

/** A persistence bar has a lower and upper endpoint, where we assume (but do not enforce) that `lower < upper` in the
  * expected ordering on the filtration type; a dimension; and optionally some annotation (this will be used extensively
  * to carry representative chains in homology computations)
  *
  * @tparam FiltrationT
  *   Type of the filtration parameter
  * @tparam AnnotationT
  *   Type of the annotation (we would expect this to be [[Chain]]).
  */
case class PersistenceBar[FiltrationT: Ordering, AnnotationT](
  val dim: Int,
  val lower: BarcodeEndpoint[FiltrationT],
  val upper: BarcodeEndpoint[FiltrationT],
  val annotation: Option[AnnotationT] = None
) {
  override def toString: String = {
    val open: String = lower match {
      case PositiveInfinity()    => "(∞" // should never happen
      case NegativeInfinity()    => "(-∞"
      case OpenEndpoint(value)   => s"($value"
      case ClosedEndpoint(value) => s"[$value"
    }
    val closed: String = upper match {
      case PositiveInfinity()    => "∞)"
      case NegativeInfinity()    => "-∞)" // should never happen
      case OpenEndpoint(value)   => s"$value)"
      case ClosedEndpoint(value) => s"$value]"
    }
    val annotationString: String = (for annotationValue <- annotation
    yield s" $annotationValue").getOrElse("")

    s"""$dim: $open,$closed$annotationString"""
  }
}

/** Utility functions for working with persistence bars.
  *
  * For any cases not covered by these simplistic factory method, the programmer gets to instantiate their own
  * [[PersistenceBar]] object.
  */
object PersistenceBar {

  /** If we know nothing, assume the user is asking for $(-\infty,\infty)$.
    */
  def apply[FiltrationT: Ordering](dim: Int) =
    new PersistenceBar[FiltrationT, Nothing](
      dim,
      NegativeInfinity[FiltrationT](),
      PositiveInfinity[FiltrationT]()
    )

  /** If we only get a lower endpoint, produce the ordinary persistence bar $[\ell, \infty)$.
    */
  def apply[FiltrationT: Ordering](dim: Int, lower: FiltrationT) =
    new PersistenceBar[FiltrationT, Nothing](
      dim,
      ClosedEndpoint(lower),
      PositiveInfinity[FiltrationT]()
    )

  /** If we get a lower and an upper endpoint, produce the ordinary persistence bar $[\ell, u)$
    */
  def apply[FiltrationT: Ordering](
    dim: Int,
    lower: FiltrationT,
    upper: FiltrationT
  ) =
    new PersistenceBar[FiltrationT, Nothing](
      dim,
      ClosedEndpoint(lower),
      OpenEndpoint(upper)
    )
}

class BarcodeContext[FiltrationT: Ordering]() {
  type Bar = PersistenceBar[FiltrationT, Nothing]

  /** Trying to create comfortable notation for inputting explicit barcodes....
    *
    * 3 dim 2 clop 5 3 dim 2 clcl 5 3 dim 2 opop 5 3 dim 2 opcl 5 3 dim infop 5 3 dim 2 clinf
    */
  class BarAssembly(
    val lower: BarcodeEndpoint[FiltrationT],
    val upper: BarcodeEndpoint[FiltrationT]
  )
  extension (lower: FiltrationT) {
    infix def bc(upper: FiltrationT) =
      new BarAssembly(ClosedEndpoint(lower), OpenEndpoint(upper))
    infix def clcl(upper: FiltrationT) =
      new BarAssembly(ClosedEndpoint(lower), ClosedEndpoint(upper))
    infix def clop(upper: FiltrationT) =
      new BarAssembly(ClosedEndpoint(lower), OpenEndpoint(upper))
    infix def opcl(upper: FiltrationT) =
      new BarAssembly(OpenEndpoint(lower), ClosedEndpoint(upper))
    infix def opop(upper: FiltrationT) =
      new BarAssembly(OpenEndpoint(lower), OpenEndpoint(upper))
  }
  def clinf(lower: FiltrationT): BarAssembly =
    new BarAssembly(ClosedEndpoint(lower), PositiveInfinity[FiltrationT]())
  def opinf(lower: FiltrationT): BarAssembly =
    new BarAssembly(OpenEndpoint(lower), PositiveInfinity[FiltrationT]())
  def infcl(upper: FiltrationT) =
    new BarAssembly(NegativeInfinity[FiltrationT](), ClosedEndpoint(upper))
  def infop(upper: FiltrationT) =
    new BarAssembly(NegativeInfinity[FiltrationT](), OpenEndpoint(upper))
  def dim(d: Int)(ba: BarAssembly) =
    new PersistenceBar[FiltrationT, Nothing](d, ba.lower, ba.upper)
}

class Barcode[FiltrationT: Ordering: Numeric, AnnotationT] {
  def isMap(
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  ): Boolean = (for {
    c <- (0 until matrix.getColumnDimension)
    r <- (0 until matrix.getRowDimension)
    if matrix.getEntry(r, c) != 0
  } yield (
    source(c).lower >= target(
      r
    ).lower && // target must be born when source is born
      source(c).upper >= target(
        r
      ).upper && // source must still live when target dies
      source(c).lower <= target(
        r
      ).upper && // source must be born before target dies
      source(c).upper >= target(
        r
      ).lower // target must be born before source dies
  )).forall(b => b)

  def imageMatrix(
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  ): RealMatrix = {
    val matrixT = matrix.transpose()
    val births = source.map(_.lower)
    val deaths = target.map(_.upper)
    val birthOrder = births.zipWithIndex.sortBy(_._1).map(_._2)
    val deathOrder = deaths.zipWithIndex.sortBy(_._1).map(_._2)
    val imagematrix = MatrixUtils.createRealMatrix(births.size, deaths.size)
    for
      birth <- (0 until births.size)
      death <- (0 until deaths.size)
    yield imagematrix.setEntry(
      birth,
      death,
      matrixT.getEntry(birthOrder(birth), deathOrder(death))
    )
    reduceMatrix(imagematrix)
  }

  def image(
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  ): List[PersistenceBar[FiltrationT, AnnotationT]] = {
    val births = source.map(_.lower)
    val deaths = target.map(_.upper)
    val imagematrix = imageMatrix(source, target, matrix)
    (for (birth, death) <- pivotsOf(imagematrix)
    yield PersistenceBar[FiltrationT, AnnotationT](
      source(birth).dim,
      births(birth),
      deaths(death),
      source(birth).annotation
    )).toList
  }

  def kernel(
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  )(using
    ord: Ordering[FiltrationT]
  ): List[PersistenceBar[FiltrationT, AnnotationT]] = {
    val dualSource: List[PersistenceBar[FiltrationT, AnnotationT]] =
      target.map(pb => PersistenceBar(pb.dim, pb.upper, pb.lower))
    val dualTarget: List[PersistenceBar[FiltrationT, AnnotationT]] =
      source.map(pb => PersistenceBar(pb.dim, pb.upper, pb.lower))
    val cokernelIntervals =
      cokernel(dualSource, dualTarget, matrix.transpose())(using ord = ord.reverse)
    cokernelIntervals.map(pb => PersistenceBar(pb.dim, pb.upper, pb.lower))
  }

  def cokernelMatrix(
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  )(using
    ord: Ordering[FiltrationT]
  ): RealMatrix = {
    val births = target.map(_.lower)
    val deaths = source.map(_.lower.flip) ++ target.map(_.upper)
    val birthOrder = births.zipWithIndex.sortBy(_._1).map(_._2)
    val deathOrder = deaths.zipWithIndex.sortBy(_._1).map(_._2)
    val cokernelmatrix = MatrixUtils.createRealMatrix(births.size, deaths.size)
    for
      s <- (0 until source.size)
      t <- (0 until target.size)
    yield cokernelmatrix.setEntry(
      t,
      s,
      matrix.getEntry(birthOrder(t), deathOrder(s))
    )
    (0 until target.size).foreach { t =>
      cokernelmatrix.setEntry(birthOrder(t), deathOrder(source.size + t), 1.0)
    }
    reduceMatrix(cokernelmatrix)
  }

  def cokernel(
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  )(using
    ord: Ordering[FiltrationT]
  ): List[PersistenceBar[FiltrationT, AnnotationT]] = {
    val births = target.map(_.lower)
    val deaths = source.map(_.lower.flip) ++ target.map(_.upper)
    val birthOrder = births.zipWithIndex.sortBy(_._1).map(_._2)
    val deathOrder = deaths.zipWithIndex.sortBy(_._1).map(_._2)
    val cokernelmatrix = cokernelMatrix(source, target, matrix)
    (for
      (row, col) <- pivotsOf(cokernelmatrix)
      birth <- List(birthOrder.indexOf(row))
      death <- List(deathOrder.indexOf(col))
    yield PersistenceBar[FiltrationT, AnnotationT](
      target(birth).dim,
      births(birth),
      deaths(death),
      target(birth).annotation
    )).toList
  }

  def reduceMatrix(matrix: RealMatrix): RealMatrix = {
    for
      col <- (0 until matrix.getColumnDimension)
      pivot <- List(matrix.getColumn(col).iterator.toSeq.lastIndexWhere(_ != 0))
      if pivot >= 0
      nextcol <- (col + 1 until matrix.getColumnDimension)
      if matrix.getEntry(pivot, nextcol) != 0
    yield matrix.setColumnMatrix(
      nextcol,
      matrix
        .getColumnMatrix(nextcol)
        .subtract(
          matrix
            .getColumnMatrix(col)
            .scalarMultiply(
              matrix.getEntry(pivot, nextcol) / matrix.getEntry(pivot, col)
            )
        )
    )
    matrix
  }

  def pivotsOf(matrix: RealMatrix): Seq[(Int, Int)] =
    for
      col <- (0 until matrix.getColumnDimension)
      pivot = matrix.getColumn(col).iterator.toSeq.lastIndexWhere(_ != 0)
      if pivot >= 0
    yield (pivot, col)
}

//type Barcode[FiltrationT] = List[PersistenceBar[FiltrationT, Nothing]]

type BarcodeGenerators[FiltrationT, CellT, CoefficientT] =
  List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]]
