package org.appliedtopology.tda4j
package algebra

import math.Ordering.Implicits.sortedSetOrdering
import scala.annotation.{tailrec, targetName}
import scala.collection.mutable
import scala.compiletime.asMatchable
import math.Fractional.Implicits.infixFractionalOps

trait HasDimension:
  type Self
  extension (self: Self) def dim: Int

trait Cell extends HasDimension:
  type Self
  extension (self: Self) def boundary[CoefficientT: Field]: Seq[(Self, CoefficientT)]

trait OrderedCell extends Cell:
  type Self: Ordering as ordering

given [CellT: OrderedCell as oCell] => Ordering[CellT] = oCell.ordering

/** Trait that defines what it means to have an ordered basis
  */
trait OrderedBasis[CellT: Ordering, CoefficientT: Field]:
  type Self
  extension (t: Self)
    def leadingCell: Option[CellT] = leadingTerm._1
    def leadingCoefficient: CoefficientT = leadingTerm._2
    def leadingTerm: (Option[CellT], CoefficientT)

/*
Implementation of the Chain trait using heaps for internal storage and deferred arithmetic.
 */

class Chain[CellT: Ordering, CoefficientT: Field] private[tda4j] (
  var entries: mutable.PriorityQueue[(CellT, CoefficientT)]
):
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

  def items: Seq[(CellT, CoefficientT)] = entries.toSeq

  /** WARNING - this is potentially an expensive operation
    *
    * `.asMatchable` (not a runtime operation -- purely a compile-time cast satisfying Scala 3's Matchable safety check,
    * since `equals` must take `Any`, which isn't itself `Matchable`) plus `@unchecked` on the pattern (the type test
    * can only check erasure-level `Chain[_, _]` at runtime, not that `other`'s own `CellT`/`CoefficientT` genuinely
    * match `this`'s -- accepted here exactly as it always has been: `other`'s type parameters are assumed to line up
    * with `this`'s so `other.collapseAll()` can reuse `this`'s own `Ordering`/`Field` givens, the same assumption every
    * generic-class `equals` in this style makes).
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

  given chain_is_ordered_basis: [CellT: Ordering, CoefficientT: Field as fld]
      => (Chain[CellT, CoefficientT] is OrderedBasis[CellT, CoefficientT]):
    extension (self: Self)
      def leadingTerm: (Option[CellT], CoefficientT) =
        self.collapseHead()
        { (x: (Option[CellT], Option[CoefficientT])) =>
          x.copy(_2 = x._2.getOrElse(fld.zero))
        }
          .apply(self.entries.headOption.unzip)

  /** Mutates `m` in place and returns `Unit`, NOT a new `SortedMap`, as of a later follow-up session (see
    * `.claude/WORKLOG-ripser-profiling.md`'s "the reduceLoop redesign" section): the persistent (immutable)
    * `SortedMap.updated`/`.removed` this used to call allocates O(log n) fresh red-black tree nodes on EVERY
    * elimination step, purely to preserve structural sharing that `reduceLoop`'s own accumulator never actually needs
    * -- `z`/`reductionLog` are built fresh at the top of `reduceByUntil` and never observed at any intermediate
    * (pre-mutation) state by anything else, so nothing here relies on the old, functional "each call returns an
    * independent snapshot" behavior. Measured (real `sphere3_96` paper data, packed engine): this was `Chain`'s own
    * accumulator churn, ~7% of total allocation weight once accurately attributed -- NOT the "48%" figure
    * `WORKLOG-ripser-profiling.md`'s first pass over this data reported, which turned out to conflate three unrelated
    * allocation sources sharing a `RedBlackTree` class-name prefix (see that section for the corrected breakdown; the
    * other two, larger sources were `insertionDiameter`'s repeated `SortedSet.iterator` calls and `SimplexIndexing`'s
    * own index-to-`Simplex` decode, fixed separately and NOT part of this change).
    */
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

  /** `fallback` is consulted only when `sigma` has no `basis` entry -- Ripser's `compute_pairs` on-the-fly
    * apparent-pair substitution (confirmed against `ripser.cpp` directly: it recomputes the substitute column fresh
    * every time a pivot is hit, with no cache anywhere). Deliberately NOT written into `basis` here, for the same
    * reason: a caller relying on a stale substitute would be trusting a value real Ripser itself never trusts twice.
    * `fallback(sigma)`, if `Some`, must return a chain whose `leadingCell` is `sigma` itself -- the caller is
    * responsible for that invariant (see `RipserCohomologyContext.zeroApparentFacet`'s doc for why it holds there).
    *
    * A `while` loop mutating `z`/`reductionLog` in place, not `@tailrec` recursion threading a fresh immutable
    * `SortedMap` through each step -- see `updateMap`'s doc above. `z.head`/`z.isEmpty` are used instead of
    * `z.headOption`: `mutable.TreeMap` does NOT override `headOption` itself, and `IterableOnceOps`'s inherited default
    * (`if (it.hasNext) Some(it.next())`, built on `.iterator`) would silently reintroduce a
    * `KeysIterator`/`TreeIterator` allocation on every single loop iteration -- exactly the class of cost this whole
    * session's investigation was chasing. `head` IS separately overridden (confirmed by decompiling `TreeMap.class`: it
    * calls `RedBlackTree.min` directly, one O(log n) descent, zero iterator) -- checked empirically, not assumed, per
    * this session's own "measure, don't infer" lesson from the `insertionDiameter` misattribution above.
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
    reductionLog: Chain[CellT, CoefficientT], // want to have a default empty here?
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
