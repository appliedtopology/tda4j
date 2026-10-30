package org.appliedtopology.tda4j

/** Barcode representation, operations and algebra
  *
  * This sub-package implements both a useful representation for persistence bars and barcodes, and also algebraic
  * operations on finitely presented persistence modules.
  */

import cats.Show

import scala.compiletime.asMatchable

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
  * @param cells
  *   the cell that opens the bar and the one that closes it, when the engine records them
  *   ([[PersistenceBar.birthCell]], [[PersistenceBar.deathCell]] read them typed). Not part of equality.
  */
case class PersistenceBar[FiltrationT: Ordering, AnnotationT](
  dim: Int,
  lower: BarcodeEndpoint[FiltrationT],
  upper: BarcodeEndpoint[FiltrationT],
  annotation: Option[AnnotationT] = None,
  cells: Option[Involution.Pair[?]] = None
):

  // A bar is its interval and its annotation. `cells` says how the engine found it, and an engine that does not record
  // them leaves it empty, so it is left out of equality.
  override def equals(other: Any): Boolean = other.asMatchable match
    case that: PersistenceBar[?, ?] =>
      dim == that.dim && lower == that.lower && upper == that.upper && annotation == that.annotation
    case _ => false

  override def hashCode: Int = scala.util.hashing.MurmurHash3.orderedHash(List(dim, lower, upper, annotation))

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

  /** The cells of a bar whose annotation is a chain over them, which is how the cell type is known: `bar.birthCell` is
    * the cell whose entry creates the class (of the bar's dimension, valued at its birth) and `bar.deathCell` the one
    * that kills it (one dimension up, valued at its death; none for an essential class). Both are `None` for a bar from
    * an engine that does not record cells, `bar.cells.isDefined` telling that apart from an essential bar.
    */
  extension [CellT](bar: PersistenceBar[?, ? <: Chain[CellT, ?]])
    def birthCell: Option[CellT] = bar.cells.map(_.birth.asInstanceOf[CellT])
    def deathCell: Option[CellT] = bar.cells.flatMap(_.death).map(_.asInstanceOf[CellT])

  /** Filtering a list of bars (no import needed: these are found through the bar type's companion).
    *
    *   - `bars.longerThan(0.1)`: essential bars and bars with persistence strictly greater than `0.1`.
    *   - `bars.longerThanFraction()`: the same, with the threshold 1% of the bars' own filtration range (the MATLAB/CLI
    *     default policy; see [[PersistenceFilter]] for the scale to pass).
    */
  extension [A](bars: List[PersistenceBar[Double, A]])
    def longerThan(minPersistence: Double): List[PersistenceBar[Double, A]] =
      bars.filter(b => b.death.isPosInfinity || b.persistence > minPersistence)
    def longerThanFraction(
      fraction: Double = PersistenceFilter.DefaultFraction,
      scale: Optional[Double] = Optional.empty
    ): List[PersistenceBar[Double, A]] =
      PersistenceFilter.longerThanFraction(bars, fraction = fraction, scale = scale)

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

  /** The endpoints of some bars as numbers, for [[MapAlgebra]]: the rank of an endpoint's value among the distinct
    * values of all of them, so the algebra sees only their order, whatever the filtration values are. Open and closed
    * make no difference (a bar is the half-open interval between the values of its endpoints, as for
    * [[PersistenceBar.birth]]); infinite endpoints stay infinite.
    */
  private final class Ranking[F](bars: Seq[PersistenceBar[F, ?]])(using ord: Ordering[F]):
    private val values: Vector[F] =
      bars
        .flatMap(b => List(b.lower, b.upper))
        .collect {
          case ClosedEndpoint(v) => v
          case OpenEndpoint(v)   => v
        }
        .sorted(using ord)
        .foldLeft(Vector.empty[F])((kept, v) => if kept.nonEmpty && ord.equiv(kept.last, v) then kept else kept :+ v)
    private val rank = scala.collection.immutable.TreeMap.from(values.zipWithIndex)(using ord)

    def number(e: BarcodeEndpoint[F]): Double = e match
      case NegativeInfinity() => Double.NegativeInfinity
      case PositiveInfinity() => Double.PositiveInfinity
      case ClosedEndpoint(v)  => rank(v).toDouble
      case OpenEndpoint(v)    => rank(v).toDouble

    def interval(bar: PersistenceBar[F, ?]): MapAlgebra.Interval =
      MapAlgebra.Interval(number(bar.lower), number(bar.upper))

    /** The value `x` stands for, as the closed lower end of a bar, or `-Infinity`. */
    def lower(x: Double): BarcodeEndpoint[F] =
      if x.isInfinite then NegativeInfinity[F]() else ClosedEndpoint(values(x.toInt))

    /** The value `x` stands for, as the open upper end of a bar, or `Infinity`. */
    def upper(x: Double): BarcodeEndpoint[F] =
      if x.isInfinite then PositiveInfinity[F]() else OpenEndpoint(values(x.toInt))

  // The matrix is `target.size x source.size`, a column to each source bar: the columns as the maps from bar indices.
  private def columnsOf(matrix: DenseMatrix): IndexedSeq[Map[Int, Double]] =
    (0 until matrix.cols).map(c =>
      (0 until matrix.rows).collect { case r if matrix(r, c) != 0.0 => r -> matrix(r, c) }.toMap
    )

  private def checked[F: Ordering, A](
    what: String,
    source: List[PersistenceBar[F, A]],
    target: List[PersistenceBar[F, A]],
    matrix: DenseMatrix
  ): Unit =
    require(
      matrix.rows == target.size && matrix.cols == source.size,
      s"Barcode.$what: the matrix is ${matrix.rows} x ${matrix.cols}, but a map from ${source.size} bars to ${target.size} bars is ${target.size} x ${source.size} (a row for each target bar, a column for each source bar)"
    )
    require(
      isMap(source, target, matrix),
      s"Barcode.$what: the matrix is not a map of persistence modules; Barcode.isMap says which entries must be zero (a source bar [b, d) goes only to target bars [b', d') with b' <= b < d' <= d)"
    )

  /** Whether `matrix` is a map of persistence modules from the bars `source` to the bars `target`: a row for each
    * target bar, a column for each source bar, and a nonzero entry only where the target bar `[b', d')` is born no
    * later than the source bar `[b, d)`, is still alive when it is born, and dies no later than the source does: `b' <=
    * b < d' <= d`.
    */
  def isMap[FiltrationT: Ordering, AnnotationT](
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: DenseMatrix
  ): Boolean =
    val ranking = Ranking(source ++ target)
    val (from, to) = (source.map(ranking.interval).toVector, target.map(ranking.interval).toVector)
    (for
      c <- 0 until matrix.cols
      r <- 0 until matrix.rows
      if matrix(r, c) != 0
    yield MapAlgebra.isLegal(from(c), to(r))).forall(identity)

  /** The bars of the image of `matrix`, a map of persistence modules from `source` to `target` ([[isMap]]), as a
    * barcode: each carries the degree and the annotation of the source bar it grows from. The matrix is over the real
    * numbers, an entry counting as zero within `1e-9`; [[MapAlgebra]] does the same over any field.
    */
  def image[FiltrationT: Ordering, AnnotationT](
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: DenseMatrix
  ): List[PersistenceBar[FiltrationT, AnnotationT]] =
    checked("image", source, target, matrix)
    given Double is Field = Field.DoubleApproximated(1e-9)
    val ranking = Ranking(source ++ target)
    MapAlgebra
      .image(source.map(ranking.interval).toVector, target.map(ranking.interval).toVector, columnsOf(matrix))
      .map((i, bar) =>
        PersistenceBar(source(i).dim, ranking.lower(bar.birth), ranking.upper(bar.death), source(i).annotation)
      )

  /** The bars of the kernel of `matrix` ([[image]] says what it is), each carrying the degree and annotation of its
    * source bar.
    */
  def kernel[FiltrationT: Ordering, AnnotationT](
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: DenseMatrix
  ): List[PersistenceBar[FiltrationT, AnnotationT]] =
    checked("kernel", source, target, matrix)
    given Double is Field = Field.DoubleApproximated(1e-9)
    val ranking = Ranking(source ++ target)
    MapAlgebra
      .kernel(source.map(ranking.interval).toVector, target.map(ranking.interval).toVector, columnsOf(matrix))
      .map((i, bar) =>
        PersistenceBar(source(i).dim, ranking.lower(bar.birth), ranking.upper(bar.death), source(i).annotation)
      )

  /** The bars of the cokernel of `matrix` ([[image]] says what it is), each carrying the degree and annotation of its
    * target bar.
    */
  def cokernel[FiltrationT: Ordering, AnnotationT](
    source: List[PersistenceBar[FiltrationT, AnnotationT]],
    target: List[PersistenceBar[FiltrationT, AnnotationT]],
    matrix: DenseMatrix
  ): List[PersistenceBar[FiltrationT, AnnotationT]] =
    checked("cokernel", source, target, matrix)
    given Double is Field = Field.DoubleApproximated(1e-9)
    val ranking = Ranking(source ++ target)
    MapAlgebra
      .cokernel(source.map(ranking.interval).toVector, target.map(ranking.interval).toVector, columnsOf(matrix))
      .map((j, bar) =>
        PersistenceBar(target(j).dim, ranking.lower(bar.birth), ranking.upper(bar.death), target(j).annotation)
      )

  /** `matrix` with every column reduced by the columns to its left, by their lowest nonzero entries (the standard
    * persistence reduction over the reals): afterwards no two nonzero columns have the same lowest nonzero row. The
    * argument is not changed.
    */
  def reduceMatrix(matrix: DenseMatrix): DenseMatrix =
    val a = matrix.toArray
    // Each column's pivot is read after the columns to its left have changed it.
    for col <- 0 until matrix.cols do
      val pivot = lowestNonzero(a, matrix.rows, col)
      if pivot >= 0 then
        for nextcol <- col + 1 until matrix.cols do
          if a(pivot)(nextcol) != 0 then
            val ratio = a(pivot)(nextcol) / a(pivot)(col)
            for row <- 0 until matrix.rows do a(row)(nextcol) = a(row)(nextcol) - a(row)(col) * ratio
    DenseMatrix(a)

  /** The pairs `(row, column)` of the lowest nonzero entry of each nonzero column. */
  def pivotsOf(matrix: DenseMatrix): Seq[(Int, Int)] =
    val a = matrix.toArray
    for
      col <- 0 until matrix.cols
      pivot = lowestNonzero(a, matrix.rows, col)
      if pivot >= 0
    yield (pivot, col)

  // The last row index of column `col` holding a nonzero entry, or -1.
  private def lowestNonzero(a: Array[Array[Double]], rows: Int, col: Int): Int =
    var row = rows - 1
    while row >= 0 && a(row)(col) == 0 do row -= 1
    row

  // ---- Products, Hom and the dual of barcodes ----

  private def requireFiniteBirths(what: String, bars: Seq[PersistenceBar[Double, ?]]): Unit =
    require(
      bars.forall(_.birth.isFinite),
      s"Barcode.$what: every bar needs a finite birth (a bar from -Infinity has no shift to take)"
    )

  // A bar from `lower` to `upper`; an `upper` of Infinity is a bar that never dies.
  private def plainBar(dim: Int, lower: Double, upper: Double): PersistenceBar[Double, Nothing] =
    if upper.isPosInfinity then PersistenceBar[Double](dim, lower) else PersistenceBar[Double](dim, lower, upper)

  private def inOrder(bars: Iterable[PersistenceBar[Double, Nothing]]): List[PersistenceBar[Double, Nothing]] =
    bars.toList.sortBy(b => (b.dim, b.birth, b.death))

  /** The pointwise tensor product of two barcodes, the module `t ↦ M_t ⊗ N_t`: the bars `[a, b)` and `[c, d)` give the
    * bar `[max(a, c), min(b, d))` when that is not empty, and the degrees add (`p` and `q` give `p + q`). It is the
    * homology of the product of two filtered complexes with [[ProductComplex.Combine.Max]] (a persistent Künneth
    * theorem, [[kunneth]]). Only the numbers of the endpoints count; the bars come back with no annotation, ordered by
    * degree, birth and death.
    */
  def tensor(
    first: Seq[PersistenceBar[Double, ?]],
    second: Seq[PersistenceBar[Double, ?]]
  ): List[PersistenceBar[Double, Nothing]] =
    inOrder(for
      m <- first
      n <- second
      lower = math.max(m.birth, n.birth)
      upper = math.min(m.death, n.death)
      if lower < upper
    yield plainBar(m.dim + n.dim, lower, upper))

  /** The convolution of two barcodes, the tensor product over the filtration parameter (the Day convolution): the
    * module whose value at `t` is generated by the products of an element of `M` at `s` and of `N` at `u` with `s + u
    * <= t`. The bars `[a, b)` and `[c, d)` give `[a + c, min(b + c, a + d))`, the degrees add. It is the homology of
    * the product of two filtered complexes with [[ProductComplex.Combine.Sum]] up to the terms [[tor]] adds
    * ([[kunneth]]), and the left adjoint of [[hom]].
    */
  def convolve(
    first: Seq[PersistenceBar[Double, ?]],
    second: Seq[PersistenceBar[Double, ?]]
  ): List[PersistenceBar[Double, Nothing]] =
    requireFiniteBirths("convolve", first ++ second)
    inOrder(for
      m <- first
      n <- second
    yield plainBar(m.dim + n.dim, m.birth + n.birth, math.min(m.death + n.birth, m.birth + n.death)))

  /** The first Tor of two barcodes over the filtration parameter: for two finite bars `[a, b)` and `[c, d)`, the bar
    * `[max(b + c, a + d), b + d)`, in degree `p + q + 1`. A bar that never dies is free and contributes nothing.
    */
  def tor(
    first: Seq[PersistenceBar[Double, ?]],
    second: Seq[PersistenceBar[Double, ?]]
  ): List[PersistenceBar[Double, Nothing]] =
    requireFiniteBirths("tor", first ++ second)
    inOrder(for
      m <- first
      n <- second
      if m.death.isFinite && n.death.isFinite
    yield plainBar(m.dim + n.dim + 1, math.max(m.death + n.birth, m.birth + n.death), m.death + n.death))

  /** The barcode of the homology of the product of two filtered complexes, from the barcodes of the factors (all of
    * their degrees; the result is right in the degrees below the highest degree both were computed to). A cell `(σ, τ)`
    * of the product enters at `Max` or `Sum` of the values of `σ` and `τ` ([[ProductComplex.Combine]]):
    *
    *   - `Max`: `H_n(X × Y) = ⊕_{p + q = n} H_p(X) ⊗ H_q(Y)`, naturally in the filtration value, so the barcode is
    *     [[tensor]] of the factors;
    *   - `Sum`: the Künneth sequence over the filtration parameter, `⊕_{p + q = n} H_p(X) ⊛ H_q(Y)` (the [[convolve]])
    *     and `⊕_{p + q = n - 1} Tor(H_p(X), H_q(Y))` (the [[tor]]).
    *
    * [[ProductComplex]] builds the product itself, which the tests compute the same bars from.
    */
  def kunneth(
    first: Seq[PersistenceBar[Double, ?]],
    second: Seq[PersistenceBar[Double, ?]],
    combine: ProductComplex.Combine = ProductComplex.Combine.Max
  ): List[PersistenceBar[Double, Nothing]] = combine match
    case ProductComplex.Combine.Max => tensor(first, second)
    case ProductComplex.Combine.Sum => inOrder(convolve(first, second) ++ tor(first, second))

  /** The internal Hom of two barcodes, the module `[M, N]_t = Hom(M, N shifted by t)` of the maps from `M` to `N`
    * shifted back by `t`: the bars `[a, b)` of `M` and `[c, d)` of `N` give `[max(c - a, d - b), d - a)` when that is
    * not empty (none, for instance, from a bar that dies to one that never does). The degree of a bar is `q - p`: the
    * maps of degree `q - p` from a degree `p` bar to a degree `q` bar. This is the right adjoint of [[convolve]]:
    * `Hom(M ⊛ N, P) ≅ Hom(M, [N, P])`; the module of maps that exist, not the derived one (no Ext).
    */
  def hom(
    first: Seq[PersistenceBar[Double, ?]],
    second: Seq[PersistenceBar[Double, ?]]
  ): List[PersistenceBar[Double, Nothing]] =
    requireFiniteBirths("hom", first ++ second)
    inOrder(for
      m <- first
      n <- second
      lower = math.max(n.birth - m.birth, if m.death.isPosInfinity then Double.NegativeInfinity else n.death - m.death)
      upper = n.death - m.birth
      if lower < upper
    yield plainBar(n.dim - m.dim, lower, upper))

  /** The dimension of the space of maps of persistence modules from `first` to `second` (natural transformations degree
    * by degree, with no shift): the number of pairs of a bar `[b, d)` of the first and a bar `[b', d')` of the second
    * with `b' <= b < d' <= d`, each contributing one.
    */
  def homDimension(first: Seq[PersistenceBar[Double, ?]], second: Seq[PersistenceBar[Double, ?]]): Int =
    (for
      m <- first
      n <- second
      if m.dim == n.dim && MapAlgebra.isLegal(
        MapAlgebra.Interval(m.birth, m.death),
        MapAlgebra.Interval(n.birth, n.death)
      )
    yield 1).sum

  /** The dual of a barcode, the module of linear functionals `t ↦ (M_t)*` with the arrows reversed, indexed by `-t` to
    * run forward again: the interval is reflected, so the bar `[b, d)` becomes `(-d, -b]` (open below, closed above;
    * from `-Infinity` for a bar that never dies), and the dual of the dual is the barcode you started from. The degrees
    * are kept: the dual of the homology in degree `k` is the cohomology in degree `k`, indexed by `-t`, so for a finite
    * complex over a field the two have the same pairs of values.
    */
  def dual[A](bars: Seq[PersistenceBar[Double, A]]): List[PersistenceBar[Double, Nothing]] =
    def reflected(end: BarcodeEndpoint[Double]): BarcodeEndpoint[Double] = end match
      case ClosedEndpoint(v)  => ClosedEndpoint(-v)
      case OpenEndpoint(v)    => OpenEndpoint(-v)
      case PositiveInfinity() => NegativeInfinity[Double]()
      case NegativeInfinity() => PositiveInfinity[Double]()
    inOrder(bars.map(bar => PersistenceBar[Double, Nothing](bar.dim, reflected(bar.upper), reflected(bar.lower))))

type BarcodeGenerators[FiltrationT, CellT, CoefficientT] =
  List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]]
