package org.appliedtopology.tda4j

import scala.collection.mutable
import scala.collection.immutable.SortedSet
import math.Ordering.Implicits.sortedSetOrdering
import scala.reflect.ClassTag

/** Simplices really are just sets, outright. We provide an implementation of the [OrderedCell] typeclass for simplicial
  * complex structures, to enable their use.
  */

opaque type Simplex[VertexT] = SortedSet[VertexT]

object Simplex:
  def from[VertexT: Ordering, T <: Seq[VertexT]](vertices: T): Simplex[VertexT] = SortedSet.from(vertices)
  def apply[VertexT: Ordering](vertices: VertexT*): Simplex[VertexT] = from(vertices)
  def unapplySeq[VertexT: Ordering](simplex: Simplex[VertexT]): Option[Seq[VertexT]] = Some(simplex.toSeq)

extension [VertexT](spx: Simplex[VertexT]) def underlying: SortedSet[VertexT] = spx

extension [VertexT](vertices: SortedSet[VertexT]) def asSimplex: Simplex[VertexT] = vertices

/** Convenience method for defining simplices
  *
  * The character ∆ is typed as Alt+J on Mac GB layout, and has unicode code 0x0394.
  */
def ∆[VertexT: Ordering](vertices: VertexT*): Simplex[VertexT] = Simplex.from(vertices)

def simplexOrdering[VertexT](using vtxOrd: Ordering[VertexT]): Ordering[Simplex[VertexT]] = sortedSetOrdering(using
  vtxOrd
)
def Simplex_is_OrderedCell[VertexT](using
  vtxOrd: Ordering[VertexT]
)(setOrdering: Ordering[Simplex[VertexT]] = simplexOrdering(using vtxOrd)): Simplex[VertexT] is OrderedCell =
  new (Simplex[VertexT] is OrderedCell):
    override lazy val ordering = setOrdering
    extension (spx: Simplex[VertexT])
      override def dim = spx.size - 1
      override def boundary[CoefficientT: Field as fr]: Seq[(Simplex[VertexT], CoefficientT)] =
        if spx.dim <= 0 then Seq.empty
        else
          spx.zipWithIndex
            .map((vtx, i) => spx.dropIndex(i))
            .toSeq
            .zip(Iterator.unfold(fr.one)(s => Some((s, fr.negate(s)))))
given default_Simplex_is_OrderedCell: [VertexT: Ordering] => (Simplex[VertexT] is OrderedCell) =
  Simplex_is_OrderedCell[VertexT]()
