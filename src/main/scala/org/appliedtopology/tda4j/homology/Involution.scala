package org.appliedtopology.tda4j

import scala.collection.mutable

/** Cycle representatives from a persistence pairing, by involuted persistent homology (Čufar and Virk, "Fast
  * computation of persistent homology representatives with involuted persistent homology", arXiv:2105.03629).
  *
  * Given the pairing a cohomology engine found, only the boundary columns of death cells are reduced: a column that
  * reduces to zero never supplies a pivot, so leaving those out changes no reduced column. For a finite bar `(σ, τ)` the
  * cycle is `R_τ`, the reduced boundary of `τ`: its youngest cell is `σ`, and it is the boundary of a chain whose
  * youngest cell is `τ`. For an essential bar it is the cycle `σ - Σ c V_τ'` that the reduction of `∂σ` to zero
  * produces.
  *
  * The pairing must come from the same total order as `youngestFirst`; every pivot is checked against the paired birth
  * cell, and a mismatch throws.
  */
private[tda4j] object Involution:

  /** A bar's cells: `birth` of dimension `dim`, `death` (if finite) of dimension `dim + 1`. */
  final case class Pair[CellT](dim: Int, birth: CellT, death: Option[CellT])

  /** A cycle per pair, in the order of `pairs`; with `withChains`, also the chain each finite cycle bounds. Every pair
    * of the pairing must be present, zero-length ones included: their columns are pivots for the others.
    *
    * @param youngestFirst
    *   the total order the pairing was computed under, youngest cell smallest (a `Chain`'s leading term).
    * @param boundary
    *   a cell's boundary, given the cell and its dimension.
    */
  def cycles[CellT, C: Field](
    pairs: IndexedSeq[Pair[CellT]],
    youngestFirst: Ordering[CellT],
    boundary: (CellT, Int) => Seq[(CellT, C)]
  ): IndexedSeq[(Chain[CellT, C], Option[Chain[CellT, C]])] =
    given Ordering[CellT] = youngestFirst
    val chainRM = summon[Chain[CellT, C] is RingModule]
    import chainRM.*
    val result = Array.fill[(Chain[CellT, C], Option[Chain[CellT, C]])](pairs.size)((Chain.empty, None))

    // Per death dimension: reduced columns (pivot -> R_τ) and their V-columns (pivot -> V_τ, with ∂V_τ = R_τ).
    // Keyed per dimension, so cells whose equality ignores dimension (packed indices) never collide.
    val basisByDim = mutable.Map.empty[Int, mutable.Map[CellT, Chain[CellT, C]]]
    val generatorsByDim = mutable.Map.empty[Int, mutable.Map[CellT, Chain[CellT, C]]]

    def reduce(z: Chain[CellT, C], seed: CellT, deathDim: Int): (Chain[CellT, C], Chain[CellT, C]) =
      val basis = basisByDim.getOrElseUpdate(deathDim, mutable.Map.empty)
      val generators = generatorsByDim.getOrElseUpdate(deathDim, mutable.Map.empty)
      val (reduced, log) = Chain.reduceBy(z, basis, Chain.empty)
      val vcol = log.rawEntries.foldLeft(Chain(seed)) { case (acc, (pivot, coeff)) =>
        acc - coeff ⊠ generators.getOrElse(
          pivot,
          throw new IllegalStateException(s"Involution: pivot $pivot has a reduced column but no V-column")
        )
      }
      vcol.collapseAll()
      reduced.collapseAll()
      (reduced, vcol)

    // Finite bars, one death dimension at a time, oldest death first (the order of the standard reduction).
    val finite = pairs.indices.filter(i => pairs(i).death.isDefined)
    for (deathDim, idxs) <- finite.groupBy(i => pairs(i).dim + 1).toSeq.sortBy(_._1) do
      val ordered = idxs.sortBy(i => pairs(i).death.get)(using youngestFirst.reverse)
      for i <- ordered do
        val Pair(_, sigma, Some(tau)) = (pairs(i): @unchecked)
        val (reduced, vcol) = reduce(Chain.from(boundary(tau, deathDim)), tau, deathDim)
        reduced.leadingCell match
          case Some(pivot) if youngestFirst.equiv(pivot, sigma) =>
            basisByDim(deathDim)(sigma) = reduced
            generatorsByDim(deathDim)(sigma) = vcol
            result(i) = (reduced, Some(vcol))
          case other =>
            throw new IllegalStateException(
              s"Involution: the boundary of $tau reduces to pivot $other, not to its paired birth cell $sigma " +
                "(the pairing and the order disagree)"
            )

    // Essential bars: reduce the birth cell's boundary to zero with the death columns one dimension up.
    for i <- pairs.indices if pairs(i).death.isEmpty do
      val Pair(dim, sigma, _) = pairs(i)
      val cycle =
        if dim == 0 then Chain(sigma)
        else
          val (reduced, vcol) = reduce(Chain.from(boundary(sigma, dim)), sigma, dim)
          if !reduced.isZero() then
            throw new IllegalStateException(
              s"Involution: the boundary of the essential cell $sigma does not reduce to zero"
            )
          vcol
      result(i) = (cycle, None)
    result.toIndexedSeq
