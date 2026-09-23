package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

/** Adapts a `FiniteSimplicialSet[G]` into the `CellStream[G, Int]` the existing homology engines actually require
  * (`CellularHomologyContext` takes a `stream: CellStream[CellT, FiltrationT]`, never a bare `OrderedCell` -- confirmed
  * by reading `Homology.scala`). Every generator sits at the same nominal filtration value `0`: this is ordinary
  * (unfiltered) homology of one fixed finite simplicial set, not real persistence -- the adapter exists only because
  * the engine has no entry point that skips the stream interface.
  *
  * `filtrationOrdering` is `Ordering.by(dimOf)` ascending, then `ord` as tiebreak -- traced from `Homology.scala`'s own
  * `CellularHomologyContext.HomologyState.processingOrder` derivation comment (the reverted first attempt at that
  * comparator used `filtrationOrdering.reverse` wholesale and got faces-before-cofaces backwards precisely because
  * `filtrationOrdering` itself already sorts smaller dimension as smaller, un-negated): this matches that established
  * convention exactly, not a fresh interpretation for this new case. With every generator's filtration value tied,
  * `processingOrder` collapses to exactly this ascending-dimension order, so it alone determines faces-before-cofaces
  * here.
  *
  * `G is OrderedCell` is threaded explicitly (via the companion `apply`) rather than resolved as an ambient global
  * given: unlike `Simplex`/`Cube`, a `FiniteSimplicialSet`'s `OrderedCell` instance depends on that one simplicial
  * set's own `faces` data, not on `G` alone, so it cannot be a single global instance for a given `G`.
  */
class SimplicialSetStream[G](sset: FiniteSimplicialSet[G])(using G is OrderedCell) extends CellStream[G, Int]:
  def filtrationValue: PartialFunction[G, Int] = { case _ => 0 }
  def iterator: Iterator[G] = sset.generatorsByDim.iterator.flatten
  val filtrationOrdering: Ordering[G] = Ordering.by[G, Int](sset.dimOf).orElse(sset.ord)
  export Filterable.IntIsFilterable.{largest, smallest}

object SimplicialSetStream:
  def apply[G](sset: FiniteSimplicialSet[G]): SimplicialSetStream[G] =
    given (G is OrderedCell) = sset.cellInstance
    new SimplicialSetStream(sset)

  /** Builds a `FiniteSimplicialSet` from any stream of simplices: the faces of a genuinely-ordered simplex (a strictly
    * increasing vertex tuple) are always non-degenerate -- removing one entry from a strictly increasing sequence
    * leaves it strictly increasing -- so every generator's face data is a bare (non-degenerate) generator, `word = Nil`
    * throughout. This exercises none of the degeneracy machinery in `SSetElement.scala`; it's a plumbing adapter,
    * cross-validated against `SimplicialHomologyContext` run directly on the same stream, not evidence that
    * `faceOf`/`insertOuter` themselves are correct -- that comes only from the hand-built fixtures.
    *
    * Takes `CellStream[Simplex[VertexT], ?]`, not the narrower `SimplexStream[VertexT, ?]`: the actual Vietoris-Rips
    * streams in this codebase (`EnumeratingCofaceSimplexStream` and its relatives) are
    * `CofaceSimplexStream`/`StratifiedCellStream`, a sibling of `SimplexStream` under `CellStream`, not a subtype of it
    * -- `SimplexStream` alone would silently reject the streams "any simplicial stream" actually means in practice,
    * caught by trying this against a real VR stream while validating this builder.
    */
  def fromStream[VertexT: Ordering](stream: CellStream[Simplex[VertexT], ?]): FiniteSimplicialSet[Simplex[VertexT]] =
    val all: Set[Simplex[VertexT]] = stream.iterator.toSet
    val maxDim = if all.isEmpty then -1 else all.iterator.map(_.dim).max
    val byDim: IndexedSeq[Set[Simplex[VertexT]]] =
      (0 to maxDim).map(d => all.filter(_.dim == d)).toIndexedSeq
    given Ordering[Simplex[VertexT]] = simplexOrdering[VertexT]
    new FiniteSimplicialSet(
      byDim,
      spx =>
        if spx.dim <= 0 then IndexedSeq.empty
        else spx.iterator.map(v => SSetElement[Simplex[VertexT]](Nil, spx - v)).toIndexedSeq // d_i drops vertex i
    )
