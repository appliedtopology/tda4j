package org.appliedtopology.tda4j

import scala.collection.mutable

/** A discrete Morse complex of a filtered complex: an acyclic matching of cells into pairs `(a, b)` with `a` a face of
  * `b` of one dimension less and both entering at the same filtration value, the cells left over (the critical cells)
  * and the Morse boundary between them. Its persistent homology is that of the filtered complex it comes from, and it
  * has fewer cells, often far fewer: `Persistence(morse.stream)` computes the barcode on the critical cells, and
  * `morse.lift` takes a representative back to the original complex. Build it with [[DiscreteMorse]].
  *
  * The Morse boundary of a critical cell `τ` is found by flowing its boundary down the gradient: a face `a` that is the
  * lower member of a pair `(a, b)` is replaced by the other faces of `b` (subtracting `∂b` times the coefficient of `a`
  * over the incidence of `a` in `b`), until only critical cells, and cells matched with a face below them, remain, and
  * the latter are dropped. It is computed in the coefficients asked for, so a pair needs an incidence of +1 or -1,
  * which is a unit in every field.
  */
// `final`: the pairs are the one piece of state and every method assumes they are acyclic, which `DiscreteMorse` ensures
// (openness reason 1); the constructor is `private[tda4j]` for the same reason.
final class MorseComplex[CellT] private[tda4j] (
  /** The complex the matching is on, as a stream. */
  val source: StratifiedCellStream[CellT, Double],
  /** The boundary and dimension of its cells. */
  val cells: CellT is OrderedCell,
  /** The pairs `(a, b)`, with the face `a` as the key. */
  val pairs: Map[CellT, CellT],
  /** The critical cells, in the order of the stream. */
  val critical: Vector[CellT]
):
  private val criticalSet: Set[CellT] = critical.toSet

  /** Whether `cell` is not in any pair. */
  def isCritical(cell: CellT): Boolean = criticalSet.contains(cell)

  /** The cell `cell` is paired with, its coface if it is a face in a pair and its face if it is the coface. */
  def partner(cell: CellT): Option[CellT] =
    pairs.get(cell).orElse(pairsReversed.get(cell))

  private lazy val pairsReversed: Map[CellT, CellT] = pairs.map(_.swap)

  /** The number of critical cells of dimension `k`. */
  def criticalCount(k: Int): Int = critical.count(cells.dim(_) == k)

  private def addTo[F: Field as fr](into: mutable.HashMap[CellT, F], cell: CellT, k: F): Boolean =
    val updated = fr.plus(into.getOrElse(cell, fr.zero), k)
    if fr.isEqual(updated, fr.zero) then
      into.remove(cell)
      false
    else
      into(cell) = updated
      true

  // The flow of the boundary of the critical cell `cell` down the pairs. It returns what is left of the boundary (the
  // critical cells of one dimension less, and cells that are paired with a face below them) and the lift: `cell` minus,
  // for each step that removes a face `a` with the coefficient `c` by subtracting `c / ∂b[a]` times `∂b`, the cell `b`
  // with that factor. Then `∂(lift) = what is left`.
  private def flow[F: Field as fr](cell: CellT): (mutable.HashMap[CellT, F], mutable.HashMap[CellT, F]) =
    require(isCritical(cell), s"MorseComplex: $cell is paired, only critical cells have a Morse boundary and a lift")
    val chain = mutable.HashMap.empty[CellT, F]
    val lifted = mutable.HashMap(cell -> fr.one)
    val queue = mutable.Queue.empty[CellT]
    for (face, k) <- cells.boundary(cell)[F] do if addTo(chain, face, k) then queue += face
    while queue.nonEmpty do
      val a = queue.dequeue()
      // `a` may have been cancelled, or reached again, since it was queued
      (chain.get(a), pairs.get(a)) match
        case (Some(coefficient), Some(b)) =>
          val boundaryOfB = cells.boundary(b)[F]
          val factor = fr.divide(coefficient, boundaryOfB.collectFirst { case (`a`, k) => k }.get)
          addTo(lifted, b, fr.negate(factor))
          for (face, k) <- boundaryOfB do if addTo(chain, face, fr.negate(fr.times(factor, k))) then queue += face
        case _ => ()
    (chain, lifted)

  /** The Morse boundary of the critical cell `cell`: critical cells of one dimension less with coefficients. */
  def boundary[F: Field](cell: CellT): Seq[(CellT, F)] =
    val (chain, _) = flow[F](cell)
    critical.iterator.filter(chain.contains).map(c => (c, chain(c))).toSeq

  /** The chain of the original complex that the critical cell `cell` stands for: the cell itself minus the cells it was
    * paired through, found by the flow of its boundary. Lifting commutes with the boundary (the boundary of the lift of
    * `cell` is the lift of its Morse boundary), so a cycle of the Morse complex lifts to a cycle with the same class,
    * supported on cells that entered no later.
    */
  def lift[F: Field](cell: CellT): Seq[(CellT, F)] = flow[F](cell)._2.toSeq

  /** [[lift]] of a chain of Morse cells, as a chain of cells of the original complex. */
  def lift[F: Field as fr](chain: Chain[MorseCell[CellT], F]): Chain[CellT, F] =
    val total = mutable.HashMap.empty[CellT, F]
    for (morseCell, k) <- chain.terms; (c, coefficient) <- lift[F](morseCell.cell) do
      addTo(total, c, fr.times(k, coefficient))
    Chain.from(total.toSeq)(using source.filtrationOrdering)

  /** The filtered Morse complex as a stream of [[MorseCell]]s, every critical cell entering when its cell did: what
    * `Persistence` takes to compute the barcode of the original complex on fewer cells.
    */
  lazy val stream: StratifiedCellStream[MorseCell[CellT], Double] =
    val complex = this
    val ambientValue = source.filtrationValue
    val byDimension = critical.groupBy(cells.dim(_))
    new StratifiedCellStream[MorseCell[CellT], Double]:
      val filtrationValue: PartialFunction[MorseCell[CellT], Double] = new PartialFunction[MorseCell[CellT], Double]:
        def isDefinedAt(m: MorseCell[CellT]): Boolean = ambientValue.isDefinedAt(m.cell)
        def apply(m: MorseCell[CellT]): Double = ambientValue(m.cell)
        override def applyOrElse[A1 <: MorseCell[CellT], B1 >: Double](m: A1, default: A1 => B1): B1 =
          ambientValue.applyOrElse(m.cell, (_: CellT) => default(m))
      val filtrationOrdering: Ordering[MorseCell[CellT]] = new Ordering[MorseCell[CellT]]:
        def compare(x: MorseCell[CellT], y: MorseCell[CellT]): Int =
          source.filtrationOrdering.compare(x.cell, y.cell)
      def smallest: Double = source.smallest
      def largest: Double = source.largest
      override def homologyDegreeLimit: Option[Int] = source.homologyDegreeLimit
      def iterateDimension: PartialFunction[Int, Iterator[MorseCell[CellT]]] =
        case d if source.iterateDimension.isDefinedAt(d) =>
          byDimension.getOrElse(d, Vector.empty).iterator.map(MorseCell(_)(complex))

/** Discrete Morse theory on a filtered complex: [[apply]] finds a matching that respects the filtration (a pair enters
  * at one value) and returns the [[MorseComplex]].
  *
  * {{{
  * val rips = VietorisRips(points, maxDimension = 2)
  * val morse = DiscreteMorse(rips)
  * morse.critical.size                      // far fewer cells than `rips`
  * Persistence(morse.stream, maxDimension = 2)   // the same bars as Persistence(rips, maxDimension = 2)
  * }}}
  *
  * The matching is made level by level, a level being the cells that enter at one value, by elementary collapses
  * (`Strategy.Reduction`: a cell with exactly one coface in the level, paired with it) or by elementary coreductions
  * (`Strategy.Coreduction`: a cell with exactly one face in the level, paired with it), and when neither applies the
  * cell of highest (respectively lowest) dimension is left critical. Pairs inside one level are acyclic, and a gradient
  * path never goes back up in the filtration, so the matching is acyclic as a whole; the pairs are the apparent pairs
  * of Ripser and more.
  */
object DiscreteMorse:

  /** How cells are paired inside a level: by faces that have a single coface (`Reduction`) or cofaces that have a
    * single face (`Coreduction`). The two find different matchings, and both are acyclic.
    */
  enum Strategy:
    case Reduction, Coreduction

  def apply[CellT: OrderedCell as cells](
    stream: StratifiedCellStream[CellT, Double],
    strategy: Strategy = Strategy.Reduction
  ): MorseComplex[CellT] =
    given Double is Field = Field.DoubleApproximated(1e-9)
    val value = stream.filtrationValue
    val all = stream.iterator.toVector
    val levels = mutable.LinkedHashMap.empty[Double, mutable.ArrayBuffer[CellT]]
    for cell <- all do levels.getOrElseUpdate(value(cell), mutable.ArrayBuffer.empty) += cell
    val pairs = mutable.HashMap.empty[CellT, CellT]
    for level <- levels.valuesIterator do pairLevel(level.toVector, strategy, pairs)
    val paired = pairs.keySet ++ pairs.valuesIterator
    new MorseComplex(stream, cells, pairs.toMap, all.filterNot(paired))

  /** Pairs the cells of one level, adding the pairs found to `pairs`. */
  private def pairLevel[CellT](level: Vector[CellT], strategy: Strategy, pairs: mutable.HashMap[CellT, CellT])(using
    cells: CellT is OrderedCell,
    field: Double is Field
  ): Unit =
    val inLevel = level.toSet
    val faces = mutable.HashMap.empty[CellT, mutable.LinkedHashSet[CellT]]
    val cofaces = mutable.HashMap.empty[CellT, mutable.LinkedHashSet[CellT]]
    val unit = mutable.HashSet.empty[(CellT, CellT)]
    for cell <- level do
      faces(cell) = mutable.LinkedHashSet.empty
      cofaces(cell) = mutable.LinkedHashSet.empty
    for b <- level; (a, k) <- cells.boundary(b)[Double] if inLevel(a) do
      faces(b) += a
      cofaces(a) += b
      if math.abs(math.abs(k) - 1.0) < 1e-9 then unit += ((a, b))
    val remaining = mutable.LinkedHashSet.from(level)
    val queue = mutable.Queue.empty[CellT]
    val reduction = strategy == Strategy.Reduction
    // The cell that could be paired, with its partner: a face with one coface, or a coface with one face.
    def partnerOf(c: CellT): Option[CellT] =
      val neighbours = if reduction then cofaces(c) else faces(c)
      if neighbours.size == 1 then
        val other = neighbours.head
        Option.when(if reduction then unit((c, other)) else unit((other, c)))(other)
      else None
    def retire(c: CellT): Unit =
      remaining -= c
      for f <- faces(c) do
        cofaces(f) -= c
        if reduction && remaining(f) then queue += f
      for u <- cofaces(c) do
        faces(u) -= c
        if !reduction && remaining(u) then queue += u
    queue ++= level
    while remaining.nonEmpty do
      while queue.nonEmpty do
        val c = queue.dequeue()
        if remaining(c) then
          partnerOf(c).foreach { other =>
            if reduction then pairs(c) = other else pairs(other) = c
            retire(c)
            retire(other)
          }
      // stuck: leave critical a cell nothing in the level sits above (or below), the one of highest (lowest) dimension
      if remaining.nonEmpty then
        val free = remaining.filter(c => if reduction then cofaces(c).isEmpty else faces(c).isEmpty)
        val chosen = if reduction then free.maxBy(cells.dim(_)) else free.minBy(cells.dim(_))
        retire(chosen)
