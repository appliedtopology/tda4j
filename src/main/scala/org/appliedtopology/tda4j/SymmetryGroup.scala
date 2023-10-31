package org.appliedtopology.tda4j

import scala.collection.immutable.SortedSet
import math.Ordering.Implicits.sortedSetOrdering

trait SymmetryGroup[KeyT, VertexT : Ordering]() {
  self =>

  def keys: Iterable[KeyT]

  def apply(groupElementKey : KeyT): (VertexT => VertexT)

  def orbit(simplex: AbstractSimplex[VertexT]): SortedSet[AbstractSimplex[VertexT]] =
    SortedSet(keys.map(k => simplex.map(apply(k))).toSeq: _*)

  def representative(simplex : AbstractSimplex[VertexT]): AbstractSimplex[VertexT] =
    orbit(simplex).min
    
  def isRepresentative(simplex : AbstractSimplex[VertexT]): Boolean = simplex == representative(simplex)
  
}






