package org.appliedtopology.tda4j

/** Barcode representation, operations and algebra
  *
  * This sub-package implements both a useful representation for persistence bars and barcodes, and also algebraic
  * operations on finitely presented persistence modules.
  */

import cats.Show
import org.apache.commons.math3.linear.*

// Sealed on purpose: the four cases are the endpoints of an interval on the extended line, a closed classification that
// `endpointOrdering` and every reader match on exhaustively.
sealed trait BarcodeEndpoint[FiltrationT: Ordering]:
  // Exchange open and closed for finite barcode endpoints; do nothing for infinite ones.
  def flip: BarcodeEndpoint[FiltrationT]
  def isFinite: Boolean

case class PositiveInfinity[FiltrationT: Ordering]() extends BarcodeEndpoint[FiltrationT]:
  override def flip: BarcodeEndpoint[FiltrationT] = this
  override val isFinite = false
case class NegativeInfinity[FiltrationT: Ordering]() extends BarcodeEndpoint[FiltrationT]:
  override def flip: BarcodeEndpoint[FiltrationT] = this
  override val isFinite = false
case class OpenEndpoint[FiltrationT: Ordering](value: FiltrationT) extends BarcodeEndpoint[FiltrationT]:
  override def flip: BarcodeEndpoint[FiltrationT] = ClosedEndpoint(value)
  override val isFinite = true
case class ClosedEndpoint[FiltrationT: Ordering](value: FiltrationT) extends BarcodeEndpoint[FiltrationT]:
  override def flip: BarcodeEndpoint[FiltrationT] = OpenEndpoint(value)
  override val isFinite = true

import math.Ordered.orderingToOrdered
object BarcodeEndpoint:
  /** `e.show` for any endpoint whose values have a `Show`: `[v]` closed, `(v)` open, `+∞`, `-∞`. Typed for every
    * subtype, so `ClosedEndpoint(1.0).show` works as well as an `e: BarcodeEndpoint[Double]`.
    */
  given endpointShow: [F: Show as sf, E <: BarcodeEndpoint[F]] => Show[E] = Show.show {
    case ClosedEndpoint(v)  => s"[${sf.show(v)}]"
    case OpenEndpoint(v)    => s"(${sf.show(v)})"
    case PositiveInfinity() => "+∞"
    case NegativeInfinity() => "-∞"
  }

  /** The endpoint written as the left end of an interval: `[v`, `(v` or `(-∞`. */
  def showLower[F](e: BarcodeEndpoint[F])(using sf: Show[F]): String = e match
    case ClosedEndpoint(v)  => s"[${sf.show(v)}"
    case OpenEndpoint(v)    => s"(${sf.show(v)}"
    case NegativeInfinity() => "(-∞"
    case PositiveInfinity() => "(+∞"

  /** The endpoint written as the right end of an interval: `v)`, `v]` or `∞)`. */
  def showUpper[F](e: BarcodeEndpoint[F])(using sf: Show[F]): String = e match
    case ClosedEndpoint(v)  => s"${sf.show(v)}]"
    case OpenEndpoint(v)    => s"${sf.show(v)})"
    case PositiveInfinity() => "∞)"
    case NegativeInfinity() => "-∞)"

  given endpointOrdering: [FiltrationT: Ordering as ord] => Ordering[BarcodeEndpoint[FiltrationT]]:
    def compare(
      x: BarcodeEndpoint[FiltrationT],
      y: BarcodeEndpoint[FiltrationT]
    ) = x match
      case NegativeInfinity() =>
        y match
          case NegativeInfinity() => 0
          case _                  => -1
      case PositiveInfinity() =>
        y match
          case PositiveInfinity() => 0
          case _                  => +1
      case ClosedEndpoint(xvalue) =>
        y match
          case NegativeInfinity()     => +1
          case PositiveInfinity()     => -1
          case ClosedEndpoint(yvalue) => ord.compare(xvalue, yvalue)
          case OpenEndpoint(yvalue)   =>
            if ord.compare(xvalue, yvalue) == 0 then -1
            else ord.compare(xvalue, yvalue)
      case OpenEndpoint(xvalue) =>
        y match
          case NegativeInfinity()     => +1
          case PositiveInfinity()     => -1
          case ClosedEndpoint(yvalue) =>
            if ord.compare(xvalue, yvalue) == 0 then +1
            else ord.compare(xvalue, yvalue)
          case OpenEndpoint(yvalue) => ord.compare(xvalue, yvalue)

/** One bar of a barcode: a degree `dim`, a `lower` and an `upper` endpoint, and optionally an annotation -- in every
  * engine's output, the bar's representative chain.
  *
  * A finished bar is `[birth, death)`; a class still alive at the end of the filtration is `[birth, ∞)`; a class alive
  * at the value a diagram was truncated at, `f`, is `[birth, f]` (closed: it exists at `f`).
  *
  * @tparam FiltrationT
  *   Type of the filtration parameter
  * @tparam AnnotationT
  *   Type of the annotation (we would expect this to be [[Chain]]).
  */
case class PersistenceBar[FiltrationT: Ordering, AnnotationT](
  dim: Int,
  lower: BarcodeEndpoint[FiltrationT],
  upper: BarcodeEndpoint[FiltrationT],
  annotation: Option[AnnotationT] = None
):

  /** True for `[v, v)`: a cell paired with one entering at the same filtration value, so no class ever exists. Engines
    * leave these out unless asked (`includeZeroLength = true`).
    */
  def isZeroLength: Boolean = (lower, upper) match
    case (ClosedEndpoint(a), OpenEndpoint(b)) => summon[Ordering[FiltrationT]].equiv(a, b)
    case (OpenEndpoint(a), OpenEndpoint(b))   => summon[Ordering[FiltrationT]].equiv(a, b)
    case _                                    => false

  /** The representative recorded with this bar (every engine records one: a cycle, or a cocycle for the cohomology
    * engines).
    */
  def representative: AnnotationT =
    annotation.getOrElse(throw new NoSuchElementException(s"bar $this carries no representative"))

  override def toString: String =
    val open: String = lower match
      case PositiveInfinity()    => "(∞" // should never happen
      case NegativeInfinity()    => "(-∞"
      case OpenEndpoint(value)   => s"($value"
      case ClosedEndpoint(value) => s"[$value"
    val closed: String = upper match
      case PositiveInfinity()    => "∞)"
      case NegativeInfinity()    => "-∞)" // should never happen
      case OpenEndpoint(value)   => s"$value)"
      case ClosedEndpoint(value) => s"$value]"
    val annotationString: String = (for annotationValue <- annotation
    yield s" $annotationValue").getOrElse("")

    s"""$dim: $open,$closed$annotationString"""

/** Utility functions for working with persistence bars.
  *
  * For any cases not covered by these simplistic factory method, the programmer gets to instantiate their own
  * [[PersistenceBar]] object.
  */
object PersistenceBar:

  /** `bar.show`: the degree, the interval and the representative, `1: [0.5, 1.2)  1 ⊠ ∆(0,1) + ...` (each part by its
    * own `Show`; the representative is left out when the bar has none).
    */
  given barShow: [F: Show, A: Show as sa] => Show[PersistenceBar[F, A]] =
    Show.show(bar => interval(bar) + bar.annotation.fold("")(a => "  " + sa.show(a)))

  /** `bar.show` for a bar with no annotation type (`PersistenceBar[Double](1, 0.5, 1.2)`): `1: [0.5, 1.2)`. */
  given unannotatedBarShow: [F: Show] => Show[PersistenceBar[F, Nothing]] = Show.show(interval)

  private def interval[F: Show](bar: PersistenceBar[F, ?]): String =
    s"${bar.dim}: ${BarcodeEndpoint.showLower(bar.lower)}, ${BarcodeEndpoint.showUpper(bar.upper)}"

  private def numeric(e: BarcodeEndpoint[Double]): Double = e match
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity

  /** Plain numbers for a bar over `Double` filtration values, so callers need not pattern-match on the endpoint types
    * (open/closed makes no difference to the numbers): `birth` and `death` (`Infinity` for an essential class),
    * `persistence = death - birth`, and `toTriple = (dim, birth, death)`, the same shape `diagramAt` returns.
    */
  extension [A](bar: PersistenceBar[Double, A])
    def birth: Double = numeric(bar.lower)
    def death: Double = numeric(bar.upper)
    def persistence: Double = death - birth
    def toTriple: (Int, Double, Double) = (bar.dim, birth, death)

  /** Filtering a list of bars (no import needed: these are found through the bar type's companion).
    *
    *   - `bars.longerThan(0.1)`: essential bars and bars with persistence strictly greater than `0.1`.
    *   - `bars.significant()`: the same, with the threshold 1% of the bars' own filtration range (the MATLAB/CLI
    *     default policy; see [[PersistenceFilter]] for the scale to pass).
    */
  extension [A](bars: List[PersistenceBar[Double, A]])
    def longerThan(minPersistence: Double): List[PersistenceBar[Double, A]] =
      bars.filter(b => b.death.isPosInfinity || b.persistence > minPersistence)
    def significant(
      fraction: Double = PersistenceFilter.DefaultFraction,
      scale: Optional[Double] = Optional.empty
    ): List[PersistenceBar[Double, A]] =
      PersistenceFilter.significant(bars, fraction = fraction, scale = scale)

  /** `bars` without the zero-length ones, unless `includeZeroLength`: what every engine applies to its output. */
  def dropZeroLength[F, A](
    bars: Iterable[PersistenceBar[F, A]],
    includeZeroLength: Boolean
  ): List[PersistenceBar[F, A]] =
    if includeZeroLength then bars.toList else bars.iterator.filterNot(_.isZeroLength).toList

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

open class BarcodeBuilder[FiltrationT: Ordering]():
  type Bar = PersistenceBar[FiltrationT, Nothing]

  /** Infix notation for hand-building explicit persistence bars: `lower <infix> upper` constructs a `BarAssembly`,
    * which `dim(d)(...)` then turns into a `PersistenceBar`. Each infix method's name spells out its endpoint kinds in
    * birth-then-death order: `cl` = closed, `op` = open, `inf` = infinite (only ever at the far end -- negative
    * infinity as a lower bound, positive infinity as an upper bound). So `a clop b` is the closed-open bar `[a, b)`
    * (the usual half-open persistence interval, and what the bare `bc` alias also means); `a clcl b` is `[a, b]`;
    * `a opop b` is `(a, b)`; `a opcl b` is `(a, b]`; `clinf(a)`/`opinf(a)` are `[a, ∞)`/`(a, ∞)`; `infcl(b)`/`infop(b)`
    * are `(-∞, b]`/`(-∞, b)`.
    * {{{
    * val ctx = BarcodeBuilder[Double]()
    * import ctx.*
    * ctx.dim(1)(3.0 clop 5.0)   // PersistenceBar(1, ClosedEndpoint(3.0), OpenEndpoint(5.0))
    * ctx.dim(0)(clinf(2.0))     // PersistenceBar(0, ClosedEndpoint(2.0), PositiveInfinity())
    * }}}
    */
  class BarAssembly(
    val lower: BarcodeEndpoint[FiltrationT],
    val upper: BarcodeEndpoint[FiltrationT]
  )
  extension (lower: FiltrationT)
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

/** Stateless: every method here is a pure function of its arguments, so this is an `object`, not a class you
  * instantiate per call (as it was before, `Barcode[F, A]().method(...)`, with no state ever carried between the
  * construction and the one call).
  */
object Barcode:
  def isMap[FiltrationT: Ordering, AnnotationT](
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  ): Boolean = (for
      c <- 0 until matrix.getColumnDimension
      r <- 0 until matrix.getRowDimension
      if matrix.getEntry(r, c) != 0
    yield source(c).lower >= target(
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
  ).forall(b => b)

  def imageMatrix[FiltrationT: Ordering, AnnotationT](
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  ): RealMatrix =
    val matrixT = matrix.transpose()
    val births = source.map(_.lower)
    val deaths = target.map(_.upper)
    val birthOrder = births.zipWithIndex.sortBy(_._1).map(_._2)
    val deathOrder = deaths.zipWithIndex.sortBy(_._1).map(_._2)
    val imagematrix = MatrixUtils.createRealMatrix(births.size, deaths.size)
    for
      birth <- 0 until births.size
      death <- 0 until deaths.size
    do imagematrix.setEntry(birth, death, matrixT.getEntry(birthOrder(birth), deathOrder(death)))
    reduceMatrix(imagematrix)

  def image[FiltrationT: Ordering, AnnotationT](
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  ): List[PersistenceBar[FiltrationT, AnnotationT]] =
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

  def kernel[FiltrationT, AnnotationT](
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  )(using
    ord: Ordering[FiltrationT]
  ): List[PersistenceBar[FiltrationT, AnnotationT]] =
    val dualSource: List[PersistenceBar[FiltrationT, AnnotationT]] =
      target.map(pb => PersistenceBar(pb.dim, pb.upper, pb.lower))
    val dualTarget: List[PersistenceBar[FiltrationT, AnnotationT]] =
      source.map(pb => PersistenceBar(pb.dim, pb.upper, pb.lower))
    val cokernelIntervals =
      cokernel(dualSource, dualTarget, matrix.transpose())(using ord = ord.reverse)
    cokernelIntervals.map(pb => PersistenceBar(pb.dim, pb.upper, pb.lower))

  def cokernelMatrix[FiltrationT, AnnotationT](
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  )(using
    ord: Ordering[FiltrationT]
  ): RealMatrix =
    val births = target.map(_.lower)
    val deaths = source.map(_.lower.flip) ++ target.map(_.upper)
    val birthOrder = births.zipWithIndex.sortBy(_._1).map(_._2)
    val deathOrder = deaths.zipWithIndex.sortBy(_._1).map(_._2)
    val cokernelmatrix = MatrixUtils.createRealMatrix(births.size, deaths.size)
    for
      s <- 0 until source.size
      t <- 0 until target.size
    do cokernelmatrix.setEntry(t, s, matrix.getEntry(birthOrder(t), deathOrder(s)))
    (0 until target.size).foreach { t =>
      cokernelmatrix.setEntry(birthOrder(t), deathOrder(source.size + t), 1.0)
    }
    reduceMatrix(cokernelmatrix)

  def cokernel[FiltrationT, AnnotationT](
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: RealMatrix
  )(using
    ord: Ordering[FiltrationT]
  ): List[PersistenceBar[FiltrationT, AnnotationT]] =
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

  def reduceMatrix(matrix: RealMatrix): RealMatrix =
    for
      col <- 0 until matrix.getColumnDimension
      pivot <- List(matrix.getColumn(col).iterator.toSeq.lastIndexWhere(_ != 0))
      if pivot >= 0
      nextcol <- col + 1 until matrix.getColumnDimension
      if matrix.getEntry(pivot, nextcol) != 0
    do
      matrix.setColumnMatrix(
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

  def pivotsOf(matrix: RealMatrix): Seq[(Int, Int)] =
    for
      col <- 0 until matrix.getColumnDimension
      pivot = matrix.getColumn(col).iterator.toSeq.lastIndexWhere(_ != 0)
      if pivot >= 0
    yield (pivot, col)

type BarcodeGenerators[FiltrationT, CellT, CoefficientT] =
  List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]]
