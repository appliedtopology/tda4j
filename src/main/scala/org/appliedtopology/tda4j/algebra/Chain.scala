package org.appliedtopology.tda4j

import cats.Show
import cats.syntax.show.showInterpolator

import scala.annotation.tailrec
import scala.collection.mutable
import scala.compiletime.asMatchable

/*
The library's own Chain storages: a HeapChain (a heap with arithmetic deferred), and a PackedChain (arrays of cell keys
and coefficients, decoded when read: the grid engines' representatives, .claude/WORKLOG-compact-representatives.md).
 */

/** A formal sum of cells with coefficients in a field, kept in an order on the cells: its leading term, the pivot of a
  * reduction, is the least cell. Made by `Chain(...)`, `Chain.from` and arithmetic.
  *
  * The storage is open to experiment: a subclass passes the order and the field to this constructor and implements
  * [[entryIterator]], its stored entries. Every other member has a default built on those two, which a storage may
  * override for speed; reductions (`Chain.reduceBy`), arithmetic, `boundary`, equality and the engines use these
  * members only. The library keeps chains in a heap (what `Chain(...)` and arithmetic make), and the grid engines keep
  * their representatives packed in arrays.
  */
// because scalafmt is too aggressive to use the `into` new scala3 syntax.
// format: off
abstract into class Chain[CellT: Ordering as order, CoefficientT: Field as field]:
  // format: on

  /** The order the chain is kept in: its leading term is the least cell under it. */
  final def cellOrdering: Ordering[CellT] = order

  /** The field of the coefficients. */
  final def coefficientField: CoefficientT is Field = field

  /** The stored entries: a cell can appear more than once (arithmetic may be deferred), or with a zero total. The one
    * member a storage must implement.
    */
  def entryIterator: Iterator[(CellT, CoefficientT)]

  /** Merges the stored entries of the least cell, dropping it if they sum to zero. Nothing to do by default, since the
    * default `terms` merges as it reads; a storage that keeps merged state (the heap) overrides it.
    */
  def collapseHead(): Unit = ()

  /** Merges the stored entries of every cell and drops the zero ones. Nothing to do by default (see [[collapseHead]]).
    */
  def collapseAll()(using fr: CoefficientT is Field): Unit = ()

  /** The terms of the chain: each cell with its (nonzero) coefficient, once, in cell order. */
  def terms: Seq[(CellT, CoefficientT)] =
    entryIterator.toSeq
      .groupMapReduce(_._1)(_._2)(field.plus)
      .filter((_, x) => !field.isEqual(x, field.zero))
      .toSeq
      .sortBy(_._1)(using order)

  /** The cells with a nonzero coefficient, in cell order. */
  def cells: Seq[CellT] = terms.map(_._1)

  /** The stored entries without collapsing: arithmetic is deferred, so a cell can appear more than once (`a + a` as two
    * `(a, 1)` pairs) or with a zero total. Cheap; use [[terms]] for one coefficient per cell.
    */
  def rawEntries: Seq[(CellT, CoefficientT)] = entryIterator.toSeq

  def isZero(): Boolean = terms.isEmpty

  /** The leading term: the least cell and its coefficient, `(None, zero)` for the zero chain. */
  def leadingTerm: (Option[CellT], CoefficientT) =
    terms.headOption match
      case Some((cell, x)) => (Some(cell), x)
      case None            => (None, field.zero)

  /** Whether the terms are stored packed (a [[PackedChain]]: decoded when read). */
  private[tda4j] final def isPacked: Boolean = this.isInstanceOf[PackedChain[?, ?]]

  /** Equality as formal sums: every cell has the same coefficient in both, by the field's own equality (a field element
    * can have several representations), whatever the order or the storage. Collapses both. Assumes `obj` has the same
    * cell and coefficient types (only `Chain` is checked at runtime). Not consistent with `hashCode`: do not use chains
    * as keys of a hash set or map.
    */
  override def equals(obj: Any): Boolean = obj.asMatchable match
    case other: Chain[CellT, CoefficientT] @unchecked =>
      val mine = terms
      val theirs = other.terms
      mine.size == theirs.size && {
        val byCell = theirs.toMap
        mine.forall((cell, x) => byCell.get(cell).exists(field.isEqual(x, _)))
      }
    case _ => false

  override def toString: String =
    val it = entryIterator
    if !it.hasNext then "Chain()"
    else it.map((c, x) => s"${x.toString}⊠${c.toString}").mkString(" + ")

/** A chain kept in a heap ordered so that the least cell is at the top, with arithmetic deferred: a cell can appear
  * several times, or with a zero total, until the chain is collapsed. Its order and field are `Chain`'s (taken as plain
  * parameters and passed up, so that they are stored once).
  */
private[tda4j] final class HeapChain[CellT, CoefficientT](
  private[tda4j] var queue: mutable.PriorityQueue[(CellT, CoefficientT)]
)(order: Ordering[CellT], field: CoefficientT is Field)
    extends Chain[CellT, CoefficientT](using order, field):
  @tailrec
  override def collapseHead(): Unit =
    val fr = coefficientField
    val cmp = queue.ord
    queue.headOption match
      case None    => ()
      case Some(_) =>
        val head = queue.dequeue()
        val cell = head._1
        var acc = head._2
        while queue.headOption.map(cmp.compare(head, _)).contains(0) do
          val otherHead = queue.dequeue()
          acc = fr.plus(acc, otherHead._2)
        if fr.isEqual(acc, fr.zero) then collapseHead()
        else queue.enqueue((cell, acc))

  override def collapseAll()(using fr: CoefficientT is Field): Unit =
    queue = mutable.PriorityQueue.from(
      queue
        .groupMapReduce(_._1) // group by cell
        (x => x._2) // extract coefficient
        (fr.plus) // sum the coefficient parts
        .filter((c, x) => !fr.isEqual(x, fr.zero))
        .iterator
        .toSeq
    )(using queue.ord)

  override def isZero(): Boolean =
    collapseHead()
    val fr = coefficientField
    queue.isEmpty || fr.isEqual(queue.head._2, fr.zero)

  override def terms: Seq[(CellT, CoefficientT)] =
    collapseAll()(using coefficientField)
    queue.toSeq.sortBy(_._1)(using cellOrdering)

  override def rawEntries: Seq[(CellT, CoefficientT)] = queue.toSeq

  override def entryIterator: Iterator[(CellT, CoefficientT)] = queue.iterator

  override def leadingTerm: (Option[CellT], CoefficientT) =
    collapseHead()
    val head: Option[(CellT, CoefficientT)] = queue.headOption
    (head.map(_._1), head.map(_._2).getOrElse(coefficientField.zero))

/** Decodes the key of a packed cell (see [[PackedChain]]). A class rather than a function: an `Int => CellT` would box
  * every key it is called with.
  */
private[tda4j] abstract class CellDecoder[CellT]:
  def apply(key: Int): CellT

/** A chain stored packed, as the grid engines store representatives: `keys(i)` is a cell's key, which `decoder` turns
  * into the cell, and `coefficients(i)` its coefficient. The keys are distinct and their cells ascending under the
  * chain's order; every coefficient is nonzero. Nothing else is stored: reading the chain decodes its cells afresh
  * (nothing is cached, so reading it does not make it bigger), and arithmetic on it gives a heap chain. The decoder and
  * the order are kept by every chain they serve, so they must hold only what they need, never an engine.
  */
private[tda4j] final class PackedChain[CellT, CoefficientT](
  val keys: Array[Int],
  val coefficients: Array[AnyRef],
  val decoder: CellDecoder[CellT]
)(order: Ordering[CellT], field: CoefficientT is Field)
    extends Chain[CellT, CoefficientT](using order, field):
  require(keys.length == coefficients.length, "PackedChain: one coefficient per key")

  private def term(i: Int): (CellT, CoefficientT) = (decoder(keys(i)), coefficients(i).asInstanceOf[CoefficientT])

  override def entryIterator: Iterator[(CellT, CoefficientT)] = Iterator.tabulate(keys.length)(term)
  override def terms: Seq[(CellT, CoefficientT)] = IndexedSeq.tabulate(keys.length)(term)
  override def rawEntries: Seq[(CellT, CoefficientT)] = terms
  override def isZero(): Boolean = keys.isEmpty

  override def leadingTerm: (Option[CellT], CoefficientT) =
    if keys.isEmpty then (None, coefficientField.zero)
    else (Some(decoder(keys(0))), coefficients(0).asInstanceOf[CoefficientT])

object Chain:
  given chainShow: [CellT: {OrderedCell, Show}, CoefficientT: Field as field] => Show[Chain[CellT, CoefficientT]] =
    given Show[CoefficientT] = field.showForSelf
    Show.show(c => c.rawEntries.map((cell, coeff) => show"$coeff ⊠ $cell").mkString(" + "))

  def empty[CellT: Ordering, CoefficientT: Field] = from(Seq())

  def apply[CellT: Ordering, CoefficientT: Field](
    cs: (CellT, CoefficientT)*
  ): Chain[CellT, CoefficientT] =
    from(cs)

  def apply[CellT: Ordering, CoefficientT: Field as fld](c: CellT): Chain[CellT, CoefficientT] =
    apply(c -> fld.one)

  def from[CellT: Ordering as ord, CoefficientT: Field](
    cs: Seq[(CellT, CoefficientT)]
  ): Chain[CellT, CoefficientT] =
    new HeapChain(
      mutable.PriorityQueue.from(cs)(using Ordering.by[(CellT, CoefficientT), CellT](_._1)(using ord.reverse))
    )(
      ord,
      summon[CoefficientT is Field]
    )

  /** A [[PackedChain]]: `keys` decoded by `decoder`, distinct and ascending under `Ordering[CellT]`, with the matching
    * nonzero `coefficients`. The caller guarantees the order; nothing checks it.
    */
  private[tda4j] def packed[CellT: Ordering as ord, CoefficientT: Field as fld](
    keys: Array[Int],
    coefficients: Array[AnyRef],
    decoder: CellDecoder[CellT]
  ): Chain[CellT, CoefficientT] =
    new PackedChain[CellT, CoefficientT](keys, coefficients, decoder)(ord, fld)

  given chainIsOrderedBasis: [CellT: Ordering, CoefficientT: Field as fld]
      => (Chain[CellT, CoefficientT] is OrderedBasis[CellT, CoefficientT]):
    extension (self: Self) def leadingTerm: (Option[CellT], CoefficientT) = self.leadingTerm

  /** Adds `coeff` to the entry of `cell` in `m`, removing it if the result is zero. Mutates `m`. */
  private def updateMap[CellT: Ordering, CoefficientT: Field](
    m: mutable.TreeMap[CellT, CoefficientT],
    cell: CellT,
    coeff: CoefficientT
  ): Unit =
    val fr = summon[CoefficientT is Field]
    val newCoeff = fr.plus(m.getOrElse(cell, fr.zero), coeff)
    if fr.isEqual(newCoeff, fr.zero) then m.remove(cell)
    else m.update(cell, newCoeff)

  private def toMutableTreeMap[CellT: Ordering, CoefficientT: Field](
    z: Chain[CellT, CoefficientT]
  ): mutable.TreeMap[CellT, CoefficientT] =
    val m = mutable.TreeMap.empty[CellT, CoefficientT]
    z match
      case h: HeapChain[CellT, CoefficientT] @unchecked =>
        h.queue.foreach { case (cell, coeff) => updateMap(m, cell, coeff) }
      case _ => z.entryIterator.foreach { case (cell, coeff) => updateMap(m, cell, coeff) }
    m

  /** Reduces `z` by the columns in `basis`, in place. `fallback` is consulted when a pivot `sigma` has no `basis` entry
    * (Ripser's on-the-fly apparent pairs); its result must have leading cell `sigma`, and it is not cached. Each step
    * clears its pivot, so the pivot never decreases and, but for rounding over the reals, strictly increases; one that
    * moves back, or stays put for many steps, means a column whose leading cell is not the one it is stored under, and
    * throws instead of looping forever.
    */
  private def reduceLoop[CellT: Ordering, CoefficientT: Field](
    z: mutable.TreeMap[CellT, CoefficientT],
    basis: mutable.Map[CellT, Chain[CellT, CoefficientT]],
    reductionLog: mutable.TreeMap[CellT, CoefficientT],
    stop: CellT => Boolean,
    fallback: CellT => Option[Chain[CellT, CoefficientT]]
  ): Unit =
    val order = summon[Ordering[CellT]]
    var previous: Option[CellT] = None
    var repeats = 0
    var continue = true
    while continue do
      if z.isEmpty then continue = false
      else
        val (sigma, sigmaCoeff) = z.head
        previous.foreach { p =>
          val c = order.compare(sigma, p)
          // Rounding can leave a pivot behind for a step or two over the reals; it can never move back.
          repeats = if c == 0 then repeats + 1 else 0
          if c < 0 || repeats > 64 then
            throw new IllegalStateException(
              s"Chain.reduceBy: reducing by the column at $p left the pivot at $sigma: a basis column's leading cell " +
                "is not the cell it is stored under, or the column and the chain disagree on the order"
            )
        }
        previous = Some(sigma)
        if stop(sigma) then continue = false
        else
          basis.get(sigma).orElse(fallback(sigma)) match
            case None             => continue = false
            case Some(basisChain) =>
              val fr = summon[CoefficientT is Field]
              val redCoeff = sigmaCoeff / basisChain.leadingCoefficient
              basisChain match
                case h: HeapChain[CellT, CoefficientT] @unchecked =>
                  h.queue.foreach { case (bCell, bCoeff) =>
                    updateMap(z, bCell, fr.negate(fr.times(redCoeff, bCoeff)))
                  }
                case _ =>
                  basisChain.entryIterator.foreach { case (bCell, bCoeff) =>
                    updateMap(z, bCell, fr.negate(fr.times(redCoeff, bCoeff)))
                  }
              updateMap(reductionLog, sigma, redCoeff)

  final def reduceByUntil[CellT: Ordering, CoefficientT: Field](
    z: Chain[CellT, CoefficientT],
    basis: mutable.Map[CellT, Chain[CellT, CoefficientT]],
    // No default here (e.g. `= Chain.empty`): `[CellT: Ordering, CoefficientT: Field]`'s context bounds desugar to
    // a `using` clause appended AFTER this value parameter list, so a default value here cannot reference the
    // `Ordering`/`Field` givens `Chain.empty` itself needs. Every current caller passes one explicitly anyway.
    reductionLog: Chain[CellT, CoefficientT],
    stop: CellT => Boolean = (_: CellT) => false, // stop when the stop function tells you to
    fallback: CellT => Option[Chain[CellT, CoefficientT]] = (_: CellT) => Option.empty[Chain[CellT, CoefficientT]]
  ): (Chain[CellT, CoefficientT], Chain[CellT, CoefficientT]) =
    val accMap = toMutableTreeMap(z)
    val logMap = toMutableTreeMap(reductionLog)
    reduceLoop(accMap, basis, logMap, stop, fallback)
    (Chain.from(accMap.toSeq), Chain.from(logMap.toSeq))

  final def reduceBy[CellT: Ordering, CoefficientT: Field](
    z: Chain[CellT, CoefficientT],
    basis: mutable.Map[CellT, Chain[CellT, CoefficientT]],
    reductionLog: Chain[CellT, CoefficientT],
    fallback: CellT => Option[Chain[CellT, CoefficientT]] = (_: CellT) => Option.empty[Chain[CellT, CoefficientT]]
  ): (Chain[CellT, CoefficientT], Chain[CellT, CoefficientT]) =
    reduceByUntil(z, basis, reductionLog, (_: CellT) => false, fallback)

  given [CellT: Ordering, CoefficientT: Field as fr]
    => (Chain[CellT, CoefficientT] is RingModule {
      type R = CoefficientT
    }) = new:
    type R = CoefficientT

    import Numeric.Implicits.*

    override val zero: Chain[CellT, CoefficientT] = Chain()

    override def plus(
      x: Chain[CellT, CoefficientT],
      y: Chain[CellT, CoefficientT]
    ): Chain[CellT, CoefficientT] =
      x match
        case hx: HeapChain[CellT, CoefficientT] @unchecked =>
          y match
            case hy: HeapChain[CellT, CoefficientT] @unchecked =>
              new HeapChain[CellT, CoefficientT](hx.queue.clone().addAll(hy.queue))(summon[Ordering[CellT]], fr)
            case _ =>
              new HeapChain[CellT, CoefficientT](hx.queue.clone().addAll(y.entryIterator))(summon[Ordering[CellT]], fr)
        case _ =>
          // A packed operand is read, never changed: the sum is a heap chain, kept in `x`'s order.
          val order = Ordering.by[(CellT, CoefficientT), CellT](_._1)(using x.cellOrdering.reverse)
          new HeapChain[CellT, CoefficientT](
            mutable.PriorityQueue.from(x.entryIterator ++ y.entryIterator)(using order)
          )(
            summon[Ordering[CellT]],
            fr
          )

    override def scale(
      x: CoefficientT,
      y: Chain[CellT, CoefficientT]
    ): Chain[CellT, CoefficientT] =
      y match
        case hy: HeapChain[CellT, CoefficientT] @unchecked =>
          Chain.from[CellT, CoefficientT](hy.queue.map((cell, coeff) => (cell, x * coeff)).toSeq)
        case _ => Chain.from[CellT, CoefficientT](y.entryIterator.map((cell, coeff) => (cell, x * coeff)).toSeq)

    override def negate(
      x: Chain[CellT, CoefficientT]
    ): Chain[CellT, CoefficientT] =
      scale(-fr.one, x)

  extension [CellT: OrderedCell, CoefficientT: Field](z: Chain[CellT, CoefficientT])
    def boundary: Seq[(CellT, CoefficientT)] =
      z.entryIterator.flatMap { (cellO, coeffO) =>
        cellO
          .boundary[CoefficientT]
          .iterator
          .map((cellI, coeffI) => (cellI, coeffO * coeffI))
      }.toSeq
