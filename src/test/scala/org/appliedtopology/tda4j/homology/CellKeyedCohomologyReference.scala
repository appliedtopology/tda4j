package org.appliedtopology.tda4j

import OrderedCell.cellOrdering

import scala.collection.mutable

/** The cell-keyed coboundary reduction `CellularCohomologyEngine` ran before it numbered cells densely: kept as the
  * oracle for `CellularCohomologySpec`'s term-for-term comparison. Test code only.
  */
class CellKeyedCohomologyReference[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]:

  def pairedCohomology(
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
