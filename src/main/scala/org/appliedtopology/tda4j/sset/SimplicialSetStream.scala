package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

/** A finite simplicial set as a stream with every generator at `0`, for its ordinary (unfiltered) homology; for a
  * filtration, use [[FilteredSimplicialSetStream]]. Generators are ordered by dimension, then by `ord`. The
  * `OrderedCell` instance comes from the simplicial set (its faces), so it is passed explicitly by the companion's
  * `apply`.
  */
open class SimplicialSetStream[G](sset: FiniteSimplicialSet[G])(using G is OrderedCell) extends CellStream[G, Int]:
  def filtrationValue: PartialFunction[G, Int] = { case _ => 0 }
  def iterator: Iterator[G] = sset.generatorsByDim.iterator.flatten
  val filtrationOrdering: Ordering[G] = Ordering.by[G, Int](sset.dimOf).orElse(sset.ord)
  export Filterable.IntIsFilterable.{largest, smallest}

object SimplicialSetStream:
  def apply[G](sset: FiniteSimplicialSet[G]): SimplicialSetStream[G] =
    given (G is OrderedCell) = sset.cellInstance
    new SimplicialSetStream(sset)

  // `private[sset]`: public as `SimplicialSetCatalog.fromStream`.
  /** The simplicial set of a stream of simplices. Every face of a simplex is a simplex, so no degeneracies occur. */
  private[sset] def fromStream[VertexT: Ordering](
    stream: CellStream[Simplex[VertexT], ?]
  ): FiniteSimplicialSet[Simplex[VertexT]] =
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
