package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

/** `Simplex[VertexT] is OrderedCell`, with an injectable ordering so a stream can supply its own filtration order.
  *
  * Must live outside `Simplex.scala`: an opaque type is transparent throughout its defining file, so there
  * `spx.size`/`spx.iterator`/`spx - v` would resolve to `SortedSet`'s own members (or fail to compile) instead of
  * `SimplexOps`'s. See `.claude/WORKLOG-extension-companion-objects.md`.
  */
def Simplex_is_OrderedCell[VertexT](using
  vtxOrd: Ordering[VertexT]
)(setOrdering: Ordering[Simplex[VertexT]] = simplexOrdering(using vtxOrd)): Simplex[VertexT] is OrderedCell =
  new (Simplex[VertexT] is OrderedCell):
    override lazy val ordering = setOrdering
    extension (spx: Simplex[VertexT])
      override def dim = spx.size - 1
      // Face i (vertex i removed, vertices in sorted order) carries sign (-1)^i. Built from an ordered iterator:
      // collection ops on the underlying SortedSet that return a plain `Set` (zipWithIndex, map to a non-Ordering
      // type) are hash-ordered from 5 elements on, which silently mis-signs every simplex with >= 5 vertices.
      override def boundary[CoefficientT: Field as fr]: Seq[(Simplex[VertexT], CoefficientT)] =
        if spx.dim <= 0 then Seq.empty
        else
          spx.iterator.zipWithIndex.map { (vtx, i) =>
            (spx - vtx, if i % 2 == 0 then fr.one else fr.negate(fr.one))
          }.toVector
given default_Simplex_is_OrderedCell: [VertexT: Ordering] => (Simplex[VertexT] is OrderedCell) =
  Simplex_is_OrderedCell[VertexT]()
