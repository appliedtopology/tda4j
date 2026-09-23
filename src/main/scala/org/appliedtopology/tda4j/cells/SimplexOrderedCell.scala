package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

/** `Simplex[VertexT] is OrderedCell`, with an injectable ordering so a stream can supply its own filtration order.
  *
  * Must live outside `Simplex.scala`: an opaque type is transparent throughout its defining file, so there
  * `spx.size`/`spx.iterator`/`spx - v` would resolve to `SortedSet`'s own members (or fail to compile) instead of
  * `SimplexOps`'s. See `.claude/WORKLOG-extension-companion-objects.md`.
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
// #given-example
given defaultSimplexIsOrderedCell: [VertexT: Ordering] => (Simplex[VertexT] is OrderedCell) =
  simplexIsOrderedCell[VertexT]()
// #given-example
