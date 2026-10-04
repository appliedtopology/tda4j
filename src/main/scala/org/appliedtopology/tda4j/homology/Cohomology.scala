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
class CellularCohomologyEngine[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]:

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
    val (allPaired, olderFirst) = pairedCohomology(theStream)
    val paired = allPaired.filter(_._2.dim <= limit)
    val cycles = Involution.cycles[CellT, CoefficientT](
      paired.map(_._2).toIndexedSeq,
      olderFirst.reverse,
      (cell, _) => cell.boundary[CoefficientT]
    )
    val bars = paired.zip(cycles).map { case ((bar, _), (cycle, _)) =>
      new PersistenceBar(bar.dim, bar.lower, bar.upper, Some(cycle))
    }
    PersistenceBar.dropZeroLength(bars, includeZeroLength)

  /** Every bar, zero-length ones included, with the cells that open and close it, and the order (oldest first within a
    * dimension) the pairing was computed under.
    */
  private[tda4j] def pairedCohomology(
    stream: => CellStream[CellT, FiltrationT]
  ): (List[(PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]], Involution.Pair[CellT])], Ordering[CellT]) =
    val theStream = stream
    val cellsByDim: Map[Int, Vector[CellT]] = theStream.iterator.toVector.groupBy(_.dim)

    if cellsByDim.isEmpty then (List.empty, Ordering.by[CellT, Int](_ => 0))
    else
      val fv: PartialFunction[CellT, FiltrationT] = theStream.filtrationValue
      def cellFv(c: CellT): FiltrationT = fv.applyOrElse(c, (_: CellT) => theStream.smallest)

      // Ascending filtration value, then the stream's tie-break. Not `filtrationOrdering.reverse`: that would also flip
      // the tie-break. Only cells of one dimension are ever compared under it. Summoned before any `Chain` is built.
      given cohomologyOrdering: Ordering[CellT] =
        Ordering.by[CellT, FiltrationT](cellFv).orElse(theStream.filtrationOrdering)

      val chainRM = summon[Chain[CellT, CoefficientT] is RingModule]
      import chainRM.*

      val bars =
        mutable.ArrayDeque.empty[(PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]], Involution.Pair[CellT])]
      // Accumulated across every dimension, not per-dimension-rotated -- unlike
      // `PackedRipserCohomologyEngine.activeCleared`, whose rotation exists only to work around a bare `Long`
      // combinatorial index colliding across differently-sized simplices. `CellT` values carry their own
      // dimension intrinsically (a `Simplex`/`Cube`/`FiniteSimplicialSet` generator of one dimension can never
      // structurally equal one of another), so a single flat set is safe -- the same reasoning
      // `RipserCohomologyEngine.cleared` already relies on.
      val cleared: mutable.Set[CellT] = mutable.Set.empty
      val topDim = cellsByDim.keys.max
      // Belt-and-braces, not a genuine defensive necessity -- structurally impossible for every stream this
      // class targets (a `d`-cell forces its own faces, hence lower dimensions, to exist), unlike
      // `coboundaryOfChain`'s own `require`s, which sit on a real public boundary a caller can actually violate.
      // Kept anyway: a violation here would otherwise silently leave a whole dimension's coboundary block
      // empty rather than fail loudly, since `cellsByDim.getOrElse(d + 1, Vector.empty)` below treats a
      // missing dimension the same as a genuinely empty one.
      require(
        cellsByDim.keySet == (0 to topDim).toSet,
        s"CellularCohomologyEngine requires a dimension-contiguous cell set (0..$topDim, no gaps), got " +
          s"dimensions ${cellsByDim.keySet.toSeq.sorted.mkString(", ")}"
      )

      // Runs through `topDim` itself, not `topDim - 1`: at `d == topDim`, `cellsByDim.getOrElse(d + 1, ...)` is
      // empty by construction (there is no higher dimension in the materialized stream), so every top-dimension
      // cell's coboundary is trivially empty and it opens an essential class unless already claimed as some
      // `topDim - 1` cell's pivot -- exactly `RipserCohomologyEngine`'s own behavior at its requested top
      // dimension, reached here for free rather than via a separate post-loop special case.
      for d <- 0 to topDim do
        val coboundaryMap: mutable.Map[CellT, mutable.ArrayBuffer[(CellT, CoefficientT)]] = mutable.Map.empty
        cellsByDim.getOrElse(d + 1, Vector.empty).foreach { cof =>
          cof.boundary[CoefficientT].foreach { case (face, coeff) =>
            coboundaryMap.getOrElseUpdate(face, mutable.ArrayBuffer.empty) += ((cof, coeff))
          }
        }

        // Youngest first: Algorithm 1 processes columns in increasing [matrix] order, which under the
        // reversed coboundary matrix means decreasing real filtration order -- the same fact
        // `RipserCohomologyEngine.persistentCohomology`'s own comment documents. `.reverse` here is safe: it's
        // applied only to already-single-dimension data (`cellsByDim(d)`), never spanning dimensions.
        val cellsAtD = cellsByDim.getOrElse(d, Vector.empty).sorted(using cohomologyOrdering.reverse)

        // Reset per dimension, mirroring both existing cohomology engines: dimension d's coboundary matrix
        // delta: C^d -> C^{d+1} is reduced independently of every other dimension's.
        val basis: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty
        val generators: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty

        for sigma <- cellsAtD if !cleared.contains(sigma) do
          val sigmaFv = cellFv(sigma)
          val z = Chain.from(coboundaryMap.getOrElse(sigma, mutable.ArrayBuffer.empty).toSeq)
          // No `fallback` argument: its default (`_ => None`) is exactly right here, since there is no
          // apparent-pairs substitution to wire in -- see this class's own doc.
          val (reduced, log) = Chain.reduceBy(z, basis, Chain.empty)
          val vcol: Chain[CellT, CoefficientT] = log.rawEntries.foldLeft(Chain(sigma)) { case (acc, (pivot, coeff)) =>
            acc - coeff ⊠ generators.getOrElse(
              pivot,
              throw new IllegalStateException(s"pivot $pivot has a basis entry but no generators entry")
            )
          }
          vcol.collapseAll()
          if reduced.isZero() then
            bars.append(
              (
                PersistenceBar(d, ClosedEndpoint(sigmaFv), PositiveInfinity(), Some(vcol)),
                Involution.Pair(d, sigma, None)
              )
            )
          else
            val pivot = reduced.leadingCell.get
            basis(pivot) = reduced
            generators(pivot) = vcol
            cleared += pivot
            bars.append(
              (
                PersistenceBar(d, ClosedEndpoint(sigmaFv), OpenEndpoint(cellFv(pivot)), Some(vcol)),
                Involution.Pair(d, sigma, Some(pivot))
              )
            )
        // `coboundaryMap` goes out of scope here, at the end of this dimension's own iteration -- nothing
        // keeps it alive into the next one.

      (bars.toList, cohomologyOrdering)

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
