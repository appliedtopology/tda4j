package org.appliedtopology.tda4j

import scala.collection.mutable
import scala.collection.immutable.SortedSet
import math.Ordering.Implicits.sortedSetOrdering
import scala.reflect.ClassTag

/** Simplices really are just sets, outright. We provide an implementation of the [OrderedCell] typeclass for simplicial
  * complex structures, to enable their use.
  */

opaque type Simplex[VertexT] = SortedSet[VertexT]

/** Constructors and operations of [[Simplex]]: `Simplex(1, 2, 3)` (also spelled `∆(1, 2, 3)`), `Simplex.from(seq)`, and
  * the extension methods of [[SimplexOps]] (`dim`, `+`, `union`, `toList`, ...), found without an import.
  */
object Simplex extends SimplexOps, SimplexInstances:
  def from[VertexT: Ordering, T <: Seq[VertexT]](vertices: T): Simplex[VertexT] = SortedSet.from(vertices)
  def apply[VertexT: Ordering](vertices: VertexT*): Simplex[VertexT] = from(vertices)
  def unapplySeq[VertexT: Ordering](simplex: Simplex[VertexT]): Option[Seq[VertexT]] = Some(simplex.toSeq)

  extension [VertexT](spx: Simplex[VertexT]) def underlying: SortedSet[VertexT] = spx

extension [VertexT](vertices: SortedSet[VertexT]) def asSimplex: Simplex[VertexT] = vertices

/** Convenience method for defining simplices
  *
  * The character ∆ (U+2206 INCREMENT -- not the Greek capital Δ, U+0394) is typed as Alt+J on a Mac GB layout.
  */
val ∆ : Simplex.type = Simplex // `∆(1, 2, 3)` and `case ∆(a, b) =>`; a val alias rather than a def or an object:
// TDAlab can re-export a val without making it ambiguous for users who also import the package (a def would be), and
// scaladoc writes no page FILE named after it (an `object ∆` became `∆$.html`, which a POSIX-locale JVM cannot encode).

/** The lexicographic order of simplices, given an order of the vertices. */
def simplexOrdering[VertexT](using vtxOrd: Ordering[VertexT]): Ordering[Simplex[VertexT]] = sortedSetOrdering(using
  vtxOrd
)
