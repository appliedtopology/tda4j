package org.appliedtopology.tda4j

import OrderedCell.cellOrdering

import scala.collection.mutable

/** Persistent cohomology for any cell type (simplices, cubes, simplicial-set generators): the reduction of the
  * coboundary matrix, following Bauer's Ripser (arXiv:1908.02518) without its Vietoris-Rips-specific shortcuts. Over a
  * field the barcode is the same as the homology barcode; what this engine adds is cocycle representatives, and it
  * works where the Ripser engines do not (Cech, alpha, cubical, simplicial sets, non-flag complexes).
  *
  * The coboundary is built by inverting the boundaries of the stream's cells, so the stream is read in full first.
  * There is no `maxDim`: the stream decides the top degree. For degrees `0 .. k`, pass a stream with cells up to
  * dimension `k + 1` (`LimitedCofaceSimplexStream(stream, k + 1)` for a coface stream) and ignore the degree-`(k + 1)`
  * bars.
  *
  * Representatives: every bar carries its V-column. For an essential bar it is a cocycle of the whole complex; for a
  * finite bar its coboundary is the bar's reduced column, which is born at or after the bar's death, so it is a cocycle
  * on the subcomplex before the death. `coboundaryOfChain` checks either.
  */
open class CellularCohomologyEngine[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]:

  /** Every bar of `stream`'s persistent cohomology with its representative (see the class doc); zero-length bars only
    * if `includeZeroLength`. Works one degree at a time, so memory is bounded by the largest coboundary block.
    */
  def persistentCohomology(
    stream: => CellStream[CellT, FiltrationT],
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]] =
    PersistenceBar.dropZeroLength(pairedCohomology(stream)._1.map(_._1), includeZeroLength)

  /** The same bars with '''cycles''' as representatives: the pairing is computed by cohomology, then only the boundary
    * columns of the death cells are reduced ([[Involution]]). A finite bar's cycle is the reduced boundary of its death
    * cell; an essential bar's is a cycle whose youngest cell is its birth cell. For a stream built for degrees `0..k`
    * (its `homologyDegreeLimit`), bars of higher degree are left out: they are not the complex's homology.
    */
  def persistentHomology(
    stream: => CellStream[CellT, FiltrationT],
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]] =
    val theStream = stream
    // A stream truncated for degrees 0..k leaves its top cells as "essential" classes of degree k + 1 that are not
    // the complex's homology; reducing their boundaries would redo all the work the involution skips. Essential pairs
    // are never pivots, so leaving them out changes no other cycle.
    val limit = theStream match
      case s: StratifiedCellStream[?, ?] => s.homologyDegreeLimit.getOrElse(Int.MaxValue)
      case _                             => Int.MaxValue
    reduction(theStream) match
      case None    => List.empty
      case Some(r) =>
        // The involution runs on the same cell numbers: within one dimension, a larger number is younger.
        val kept = r.entries.filter(_.dim <= limit)
        val numberOf = mutable.Map.empty[Int, mutable.HashMap[CellT, Int]]
        def boundary(i: Int, dim: Int): Seq[(Int, CoefficientT)] =
          val faces = numberOf.getOrElseUpdate(dim - 1, r.numbering(dim - 1))
          r.cellsByDim(dim)(i).boundary[CoefficientT].flatMap((face, c) => faces.get(face).map(j => (j, c)))
        val cycles = Involution.cycles[Int, CoefficientT](
          kept.map(e => Involution.Pair(e.dim, e.birth, Option.when(e.death >= 0)(e.death))),
          Ordering.Int.reverse,
          boundary
        )
        val youngestFirst = r.olderFirst.reverse
        val bars = kept.zip(cycles).toList.map { case (e, (cycle, _)) =>
          val cells = r.cellsByDim(e.dim)
          val rep = Chain.from(cycle.terms.map((i, c) => (cells(i), c)))(using youngestFirst)
          new PersistenceBar(e.dim, r.lower(e), r.upper(e), Some(rep))
        }
        PersistenceBar.dropZeroLength(bars, includeZeroLength)

  /** Every bar, zero-length ones included, with the cells that open and close it, and the order (oldest first within a
    * dimension) the pairing was computed under.
    */
  def pairedCohomology(
    stream: => CellStream[CellT, FiltrationT]
  ): (List[(PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]], Involution.Pair[CellT])], Ordering[CellT]) =
    reduction(stream) match
      case None    => (List.empty, Ordering.by[CellT, Int](_ => 0))
      case Some(r) =>
        val paired = r.entries.toList.map { e =>
          val cells = r.cellsByDim(e.dim)
          val rep = Chain.from(e.cocycle.map((i, c) => (cells(i), c)))(using r.olderFirst)
          val death = Option.when(e.death >= 0)(r.cellsByDim(e.dim + 1)(e.death))
          (PersistenceBar(e.dim, r.lower(e), r.upper(e), Some(rep)), Involution.Pair(e.dim, cells(e.birth), death))
        }
        (paired, r.olderFirst)

  /** One bar of the reduction, over cell numbers: `birth` in dimension `dim`, `death` in `dim + 1` (`-1` if essential),
    * and the V-column of `birth` (its cocycle representative).
    */
  private final case class Entry(dim: Int, birth: Int, death: Int, cocycle: Seq[(Int, CoefficientT)])

  /** The reduction's result: the cells of each dimension, oldest first (a cell's number is its position), the bars in
    * the order they were found (degree ascending, youngest birth first), and that order on cells.
    */
  private final class Reduction(
    val cellsByDim: IndexedSeq[Vector[CellT]],
    val entries: IndexedSeq[Entry],
    val olderFirst: Ordering[CellT],
    cellFv: CellT => FiltrationT
  ):
    def numbering(d: Int): mutable.HashMap[CellT, Int] = numberCells(cellsByDim(d))
    def lower(e: Entry): BarcodeEndpoint[FiltrationT] = ClosedEndpoint(cellFv(cellsByDim(e.dim)(e.birth)))
    def upper(e: Entry): BarcodeEndpoint[FiltrationT] =
      if e.death < 0 then PositiveInfinity() else OpenEndpoint(cellFv(cellsByDim(e.dim + 1)(e.death)))

  /** Each cell's position in `cells`. */
  private def numberCells(cells: Vector[CellT]): mutable.HashMap[CellT, Int] =
    val m = new mutable.HashMap[CellT, Int](cells.length * 2, mutable.HashMap.defaultLoadFactor)
    var i = 0
    while i < cells.length do
      m(cells(i)) = i
      i += 1
    m

  // The coboundary reduction (Bauer's Ripser without its Vietoris-Rips shortcuts) over dense cell numbers: each
  // dimension's cells sorted oldest first under the engine's order, a cell's number its position. The reduction then
  // compares and hashes `Int`s instead of cells, with the same pivots, bars and V-columns as reducing over the cells
  // themselves (CohomologyNumberingSpec; WORKLOG-dense-numbering.md, WORKLOG-dense-cohomology.md).
  private def reduction(stream: => CellStream[CellT, FiltrationT]): Option[Reduction] =
    val theStream = stream
    val grouped: Map[Int, Vector[CellT]] = theStream.iterator.toVector.groupBy(_.dim)
    if grouped.isEmpty then None
    else
      val fv: PartialFunction[CellT, FiltrationT] = theStream.filtrationValue
      def cellFv(c: CellT): FiltrationT = fv.applyOrElse(c, (_: CellT) => theStream.smallest)

      // Ascending filtration value, then the stream's tie-break. Not `filtrationOrdering.reverse`: that would also flip
      // the tie-break. Only cells of one dimension are ever compared under it.
      val cohomologyOrdering: Ordering[CellT] =
        Ordering.by[CellT, FiltrationT](cellFv).orElse(theStream.filtrationOrdering)

      val topDim = grouped.keys.max
      // A d-cell forces its faces to exist, so a gap means a malformed stream; without this check a missing
      // dimension would silently reduce as an empty one.
      require(
        grouped.keySet == (0 to topDim).toSet,
        s"CellularCohomologyEngine requires a dimension-contiguous cell set (0..$topDim, no gaps), got " +
          s"dimensions ${grouped.keySet.toSeq.sorted.mkString(", ")}"
      )
      val cellsByDim: IndexedSeq[Vector[CellT]] = (0 to topDim).map(d => grouped(d).sorted(using cohomologyOrdering))

      val idOrdering: Ordering[Int] = Ordering.Int // ascending number = oldest first = a column's leading term
      val fr = summon[CoefficientT is Field]
      val entries = mutable.ArrayBuffer.empty[Entry]
      var numberOf = numberCells(cellsByDim(0))
      // cleared(i): cell i of the current dimension is the death of a bar one dimension down, so it opens no class.
      var cleared = new Array[Boolean](cellsByDim(0).length)

      for d <- 0 to topDim do
        val cells = cellsByDim(d)
        val up = if d < topDim then cellsByDim(d + 1) else Vector.empty

        // This dimension's coboundary block only: built here, dropped at the end of the iteration. Faces outside the
        // stream are skipped; they would never be reduced.
        val coboundary = Array.fill(cells.length)(mutable.ArrayBuffer.empty[(Int, CoefficientT)])
        var j = 0
        while j < up.length do
          up(j).boundary[CoefficientT].foreach { case (face, c) =>
            numberOf.get(face).foreach(i => coboundary(i) += ((j, c)))
          }
          j += 1

        val nextCleared = new Array[Boolean](up.length)
        val basis = mutable.HashMap.empty[Int, Chain[Int, CoefficientT]]
        val generators = mutable.HashMap.empty[Int, Seq[(Int, CoefficientT)]]
        var i = cells.length - 1
        while i >= 0 do // youngest first
          if !cleared(i) then
            val z = Chain.from(coboundary(i).toSeq)(using idOrdering)
            val (reduced, log) =
              Chain.reduceBy(z, basis, Chain.empty[Int, CoefficientT](using idOrdering))(using idOrdering)
            // V-column: i minus the V-columns of the pivots the reduction used, with their coefficients.
            val acc = mutable.HashMap(i -> fr.one)
            log.rawEntries.foreach { case (pivot, coeff) =>
              generators
                .getOrElse(
                  pivot,
                  throw new IllegalStateException(s"pivot $pivot has a basis entry but no generators entry")
                )
                .foreach { case (k, c) => acc(k) = fr.minus(acc.getOrElse(k, fr.zero), fr.times(coeff, c)) }
            }
            val vterms = acc.toSeq.filterNot((_, c) => fr.isEqual(c, fr.zero))
            if reduced.isZero() then entries += Entry(d, i, -1, vterms)
            else
              val pivot = reduced.leadingCell.get
              basis(pivot) = reduced
              generators(pivot) = vterms
              nextCleared(pivot) = true
              entries += Entry(d, i, pivot, vterms)
          i -= 1

        if d < topDim then numberOf = numberCells(up)
        cleared = nextCleared

      Some(Reduction(cellsByDim, entries.toIndexedSeq, cohomologyOrdering, cellFv))

  /** The coboundary of `chain`, a chain of dimension-`d` cells, computed against `cofacets`, the dimension-`(d + 1)`
    * cells to consider. For checking representatives: `coboundaryOfChain(rep, cellsOfDimension(d + 1)).isZero()`.
    * Requires every cell of `chain` to have the same dimension `d` and every cofacet dimension `d + 1`.
    */
  def coboundaryOfChain(
    chain: Chain[CellT, CoefficientT],
    cofacets: IterableOnce[CellT]
  ): Chain[CellT, CoefficientT] =
    val fr = summon[CoefficientT is Field]
    // groupMapReduce, not .toMap: rawEntries can hold duplicate (cell, coeff) pairs for one cell (deferred
    // arithmetic, e.g. `a + a`), and .toMap would silently keep only one of them.
    val chainMap: Map[CellT, CoefficientT] = chain.rawEntries.groupMapReduce(_._1)(_._2)(fr.plus)
    require(
      chainMap.keys.map(_.dim).toSet.sizeIs <= 1,
      s"coboundaryOfChain requires a homogeneous chain (every cell the same dimension), got dimensions " +
        s"${chainMap.keys.map(_.dim).toSet.toSeq.sorted.mkString(", ")}"
    )
    val chainDim: Option[Int] = chainMap.keys.headOption.map(_.dim)
    val terms = mutable.ArrayBuffer.empty[(CellT, CoefficientT)]
    cofacets.iterator.foreach { cof =>
      require(
        chainDim.forall(_ + 1 == cof.dim),
        s"coboundaryOfChain requires every cofacet to be exactly one dimension above the chain " +
          s"(expected dimension ${chainDim.map(_ + 1)}, got a cofacet of dimension ${cof.dim})"
      )
      cof.boundary[CoefficientT].foreach { case (face, coeff) =>
        chainMap.get(face).foreach { chainCoeff =>
          terms += ((cof, fr.times(chainCoeff, coeff)))
        }
      }
    }
    Chain.from(terms.toSeq)
