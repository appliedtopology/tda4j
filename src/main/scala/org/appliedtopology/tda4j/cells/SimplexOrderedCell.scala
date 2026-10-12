package org.appliedtopology.tda4j

import cats.Show

/** `Simplex[VertexT] is OrderedCell`, with an injectable ordering so a stream can supply its own filtration order.
  *
  * Kept out of `Simplex.scala`: there the opaque type is transparent, and `spx.size`, `spx - v` would resolve to
  * `SortedSet`'s members instead of [[SimplexOps]]'.
  */
def simplexIsOrderedCell[VertexT](using
  vtxOrd: Ordering[VertexT]
)(setOrdering: Ordering[Simplex[VertexT]] = simplexOrdering(using vtxOrd)): Simplex[VertexT] is OrderedCell =
  new (Simplex[VertexT] is OrderedCell):
    override lazy val ordering = setOrdering
    extension (spx: Simplex[VertexT])
      // Delegates to `SimplexOps.dim` (qualified explicitly, not via `spx.dim`, to avoid this same extension
      // clause's own `dim` resolving to itself) rather than recomputing `size - 1` a second time.
      override def dim = Simplex.dim(spx)
      // Face i (vertex i removed, vertices in sorted order) carries sign (-1)^i. Built from an ordered iterator:
      // collection ops on the underlying SortedSet that return a plain `Set` (zipWithIndex, map to a non-Ordering
      // type) are hash-ordered from 5 elements on, which silently mis-signs every simplex with >= 5 vertices.
      override def boundary[CoefficientT: Field as fr]: Seq[(Simplex[VertexT], CoefficientT)] =
        if spx.dim <= 0 then Seq.empty
        else
          spx.iterator.zipWithIndex.map { (vtx, i) =>
            (spx - vtx, if i % 2 == 0 then fr.one else fr.negate(fr.one))
          }.toVector

/** `Simplex`'s default instances, mixed into `object Simplex` so they sit in the type's IMPLICIT scope: found with no
  * import at all, and consulted only when no given is lexically visible -- a stream's or a user's own
  * `Simplex[V] is OrderedCell`/`Ordering[Simplex[V]]` always wins, never ties. Lives here (not in `Simplex.scala`) for
  * the same opaque-transparency reason as `simplexIsOrderedCell`.
  */
trait SimplexInstances:
  /** The simplex on an already-sorted vertex set, without copying (the companion spelling of `.asSimplex`, so it is
    * reachable through a lab's re-exported `Simplex`).
    */
  def fromSortedSet[VertexT](vertices: scala.collection.immutable.SortedSet[VertexT]): Simplex[VertexT] =
    vertices.asSimplex

  /** The lexicographic order on simplices (`simplexOrdering`). */
  def ordering[VertexT: Ordering]: Ordering[Simplex[VertexT]] = simplexOrdering[VertexT]

  /** A `Simplex is OrderedCell` instance with a chosen order, e.g. a stream's filtration order
    * (`simplexIsOrderedCell`).
    */
  def isOrderedCell[VertexT: Ordering](order: Ordering[Simplex[VertexT]]): Simplex[VertexT] is OrderedCell =
    simplexIsOrderedCell[VertexT](order)

  // #given-example
  given defaultSimplexIsOrderedCell: [VertexT: Ordering] => (Simplex[VertexT] is OrderedCell) =
    simplexIsOrderedCell[VertexT]()
  // #given-example

  /** The order of whichever `Simplex[V] is OrderedCell` instance is in scope at the use site (a user's filtration-aware
    * one, else the lexicographic default above).
    */
  given simplexOrderingFromCell: [VertexT] => (cell: Simplex[VertexT] is OrderedCell) => Ordering[Simplex[VertexT]] =
    cell.ordering

  given simplexShow: [VertexT] => Show[Simplex[VertexT]] =
    Show.show(spx => Simplex.underlying(spx).mkString("∆(", ",", ")"))
