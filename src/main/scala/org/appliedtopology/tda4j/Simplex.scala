package org.appliedtopology.tda4j

import scala.collection.mutable
import scala.collection.immutable.SortedSet
import math.Ordering.Implicits.sortedSetOrdering
import scala.reflect.ClassTag

/** Simplices really are just sets, outright. We provide an implementation of the [OrderedCell] typeclass for simplicial
  * complex structures, to enable their use.
  */

opaque type Simplex[VertexT] = SortedSet[VertexT]

/** `object Simplex` mixes in `SimplexOps` (`SimplexOps.scala`) rather than the trait's methods living as a separate
  * top-level `extension` clause: extension methods declared here, in the opaque type's own companion object, are found
  * via the receiver type's implicit scope, not via blanket top-level visibility across the package -- the fix for the
  * same-named top-level extension collisions documented in `.claude/WORKLOG-cubical.md` and
  * `.claude/WORKLOG-extension-companion-objects.md`. `underlying` moved in here for the same reason, even though
  * nothing currently collides on that name -- keeping every `Simplex[VertexT]`-receiver extension routed through one
  * place is what makes the guarantee "a future opaque type may reuse this name" actually hold.
  *
  * `asSimplex` (below, top-level, NOT moved in here) is a real exception to that, not an oversight: its RECEIVER is
  * `SortedSet[VertexT]`, not `Simplex[VertexT]` -- companion-object-based extension lookup is keyed by the receiver
  * type, so an extension on `SortedSet[VertexT]` placed inside `Simplex`'s companion is simply never found from a
  * `SortedSet[VertexT]` receiver (confirmed the hard way: moving it here broke every `.asSimplex` call site in this
  * file with "value asSimplex is not a member of SortedSet[VertexT]"). It stays a top-level extension on purpose; it
  * was never part of the collision in the first place (`asSimplex`/`asCube` don't share a name), so it doesn't need to
  * move for that reason either.
  */
object Simplex extends SimplexOps:
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

/** Stays in this file (needs `Simplex[VertexT]`'s own opaque-type transparency for the `Ordering[SortedSet[ VertexT]]
  * -> Ordering[Simplex[VertexT]]` coercion below), unlike `Simplex_is_OrderedCell` (`SimplexOrderedCell.scala`) -- this
  * function makes no `.someExtensionMethod` call on any `Simplex[VertexT]` value, so it isn't exposed to the same-file
  * dealiasing hazard that forced that one out. See `SimplexOrderedCell.scala`'s own doc for the full explanation.
  */
def simplexOrdering[VertexT](using vtxOrd: Ordering[VertexT]): Ordering[Simplex[VertexT]] = sortedSetOrdering(using
  vtxOrd
)
