package org.appliedtopology.tda4j

import cats.Show
import cats.syntax.show.showInterpolator

import scala.annotation.tailrec
import scala.collection.mutable
import scala.compiletime.asMatchable

/*
Implementation of the Chain trait using heaps for internal storage and deferred arithmetic.
 */

// because scalafmt is too aggressive to use the `into` new scala3 syntax.
// format: off
into class Chain[CellT: Ordering, CoefficientT: Field] private[tda4j](
  private var entries: mutable.PriorityQueue[(CellT, CoefficientT)]
):
  // format: on
  @tailrec
  final def collapseHead(): Unit =
    val fr = summon[CoefficientT is Field]
    val cmp = entries.ord
    entries.headOption match
      case None    => ()
      case Some(_) =>
        val head = entries.dequeue()
        val cell = head._1
        var acc = head._2
        while entries.headOption.map(cmp.compare(head, _)).contains(0) do
          val otherHead = entries.dequeue()
          acc = fr.plus(acc, otherHead._2)
        if fr.isEqual(acc, fr.zero) then collapseHead()
        else entries.enqueue((cell, acc))

  def collapseAll()(using fr: CoefficientT is Field): Unit =
    entries = mutable.PriorityQueue.from(
      entries
        .groupMapReduce(_._1) // group by cell
        (x => x._2) // extract coefficient
        (fr.plus) // sum the coefficient parts
        .filter((c, x) => !fr.isEqual(x, fr.zero))
        .iterator
        .toSeq
    )(using entries.ord)

  def isZero(): Boolean =
    collapseHead()
    val fr = summon[CoefficientT is Field]
    entries.isEmpty || fr.isEqual(entries.head._2, fr.zero)

  /** The terms of the chain: each cell with its (nonzero) coefficient, once, in cell order. */
  def terms: Seq[(CellT, CoefficientT)] =
    collapseAll()
    entries.toSeq.sortBy(_._1)

  /** The cells with a nonzero coefficient, in cell order. */
  def cells: Seq[CellT] = terms.map(_._1)

  /** The stored entries without collapsing: arithmetic is deferred, so a cell can appear more than once (`a + a` as two
    * `(a, 1)` pairs) or with a zero total. Cheap; use [[terms]] for one coefficient per cell.
    */
  def rawEntries: Seq[(CellT, CoefficientT)] = entries.toSeq

  /** Equality as formal sums: both chains are collapsed first, so this costs a pass over each. Assumes `obj` has the
    * same cell and coefficient types (only `Chain` is checked at runtime). Not consistent with `hashCode`: do not use
    * chains as keys of a hash set or map.
    */
  override def equals(obj: Any): Boolean = obj.asMatchable match
    case other: Chain[CellT, CoefficientT] @unchecked =>
      collapseAll()
      other.collapseAll()
      entries.iterator.toList.sorted(using entries.ord) == other.entries.iterator.toList.sorted(using other.entries.ord)
    case _ => false

  override def toString: String =
    if entries.iterator.isEmpty then "Chain()"
    else entries.iterator.map((c, x) => s"${x.toString}⊠${c.toString}").mkString(" + ")

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
    new Chain(mutable.PriorityQueue.from(cs)(using Ordering.by[(CellT, CoefficientT), CellT](_._1)(using ord.reverse)))

  given chainIsOrderedBasis: [CellT: Ordering, CoefficientT: Field as fld]
      => (Chain[CellT, CoefficientT] is OrderedBasis[CellT, CoefficientT]):
    extension (self: Self)
      def leadingTerm: (Option[CellT], CoefficientT) =
        self.collapseHead()
        val head: Option[(CellT, CoefficientT)] = self.entries.headOption
        (head.map(_._1), head.map(_._2).getOrElse(fld.zero))

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
    z.entries.foreach { case (cell, coeff) => updateMap(m, cell, coeff) }
    m

  /** Reduces `z` by the columns in `basis`, in place. `fallback` is consulted when a pivot `sigma` has no `basis` entry
    * (Ripser's on-the-fly apparent pairs); its result must have leading cell `sigma`, and it is not cached.
    */
  private def reduceLoop[CellT: Ordering, CoefficientT: Field](
    z: mutable.TreeMap[CellT, CoefficientT],
    basis: mutable.Map[CellT, Chain[CellT, CoefficientT]],
    reductionLog: mutable.TreeMap[CellT, CoefficientT],
    stop: CellT => Boolean,
    fallback: CellT => Option[Chain[CellT, CoefficientT]]
  ): Unit =
    var continue = true
    while continue do
      if z.isEmpty then continue = false
      else
        val (sigma, sigmaCoeff) = z.head
        if stop(sigma) then continue = false
        else
          basis.get(sigma).orElse(fallback(sigma)) match
            case None             => continue = false
            case Some(basisChain) =>
              val fr = summon[CoefficientT is Field]
              val redCoeff = sigmaCoeff / basisChain.leadingCoefficient
              basisChain.entries.foreach { case (bCell, bCoeff) =>
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
    ): Chain[CellT, CoefficientT] = new Chain[CellT, CoefficientT](x.entries.clone().addAll(y.entries))

    override def scale(
      x: CoefficientT,
      y: Chain[CellT, CoefficientT]
    ): Chain[CellT, CoefficientT] =
      Chain.from[CellT, CoefficientT](y.entries.map((cell, coeff) => (cell, x * coeff)).toSeq)

    override def negate(
      x: Chain[CellT, CoefficientT]
    ): Chain[CellT, CoefficientT] =
      scale(-fr.one, x)

  extension [CellT: OrderedCell, CoefficientT: Field](z: Chain[CellT, CoefficientT])
    def boundary: Seq[(CellT, CoefficientT)] =
      z.entries.iterator.flatMap { (cellO, coeffO) =>
        cellO
          .boundary[CoefficientT]
          .iterator
          .map((cellI, coeffI) => (cellI, coeffO * coeffI))
      }.toSeq
