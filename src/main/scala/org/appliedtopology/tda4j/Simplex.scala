package org.appliedtopology.tda4j

import scala.collection.mutable
import scala.collection.immutable.SortedSet
import math.Ordering.Implicits.sortedSetOrdering

/** Simplices really are just sets, outright. We provide an implementation of the [OrderedCell] typeclass
 * for simplicial complex structures, to enable their use.
 *
 */

type Simplex[VertexT] = SortedSet[VertexT]

extension [VertexT : Ordering](spx : Simplex[VertexT])
  def show : String = spx.mkString(s"∆(", ",", ")")

object Simplex:
  def from[VertexT : Ordering, T <: Seq[VertexT]](vertices : T) : Simplex[VertexT] = SortedSet.from(vertices)
  def apply[VertexT : Ordering](vertices : VertexT*) : Simplex[VertexT] = SortedSet.from(vertices)

/** Convenience method for defining simplices
 *
 * The character ∆ is typed as Alt+J on Mac GB layout, and has unicode code 0x0394.
 */
def ∆[VertexT : Ordering](vertices : VertexT*) : Simplex[VertexT] = SortedSet.from(vertices)

def simplexOrdering[VertexT](using vtxOrd : Ordering[VertexT]) : Ordering[Simplex[VertexT]] = sortedSetOrdering(vtxOrd)
def SortedSet_is_OrderedCell[VertexT](using vtxOrd : Ordering[VertexT])(setOrdering : Ordering[SortedSet[VertexT]] = simplexOrdering(using vtxOrd)): (SortedSet[VertexT] is OrderedCell) =
  new(SortedSet[VertexT] is OrderedCell) {
    override lazy val ordering = setOrdering
    extension (spx: SortedSet[VertexT]) {
      override def dim = spx.size - 1
      override def boundary[CoefficientT: Field as fr] =
        if (spx.dim <= 0) Chain()
        else Chain.from(
          spx.to(Seq)
            .zipWithIndex
            .map((vtx, i) => spx -- spx.slice(i, i + 1))
            .zip(Iterator.unfold(fr.one)(s => Some((s, fr.negate(s)))))
        )
    }
  }
given default_SortedSet_is_OrderedCell[VertexT : Ordering] : (SortedSet[VertexT] is OrderedCell) =
  SortedSet_is_OrderedCell[VertexT]()
