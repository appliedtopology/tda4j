package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

/** `Simplex[VertexT] is OrderedCell` -- split into its own file from `Simplex.scala` (where the opaque type and its
  * companion object live) rather than kept alongside it, and this split is required for correctness, not just
  * organizational preference.
  *
  * `opaque type Simplex[VertexT] = SortedSet[VertexT]` is transparent (known to equal its underlying representation)
  * throughout the WHOLE FILE it's declared in, not merely within `object Simplex` itself -- this is standard Scala 3
  * opaque-type scoping, and it matters here because this class's `boundary` implementation calls genuine
  * `Simplex[VertexT]`-receiver extension methods (`size`, `zipWithIndex`, `dropIndex`, `toSeq`, all really `SimplexOps`
  * members mixed into `object Simplex`, see `SimplexOps.scala`/`Simplex.scala`). Extension-method resolution that falls
  * through to a receiver type's implicit scope (the companion-object mechanism that makes `Simplex.scala`'s own move to
  * `object Simplex` a real fix for the naming-collision problem -- see
  * `.claude/WORKLOG-extension-companion-objects.md`) is keyed off the receiver's DEALIASED type once transparency
  * applies -- so from inside `Simplex.scala` itself, `spx.dropIndex(i)` would try to find `dropIndex` on
  * `SortedSet[VertexT]`'s own companion (which has no such member) instead of `Simplex`'s, and fail to compile;
  * `spx.size`/`spx.zipWithIndex`/`spx.toSeq` are worse -- they'd silently resolve to `SortedSet`'s own OWN same-named
  * members instead of `SimplexOps`'s intended overrides, a silent behavior swap rather than a compile error, since
  * `SortedSet` happens to already have members by those names. Confirmed empirically, not just reasoned through: moving
  * this class into `Simplex.scala` itself reproduces exactly this failure ("value dropIndex is not a member of
  * Simplex[VertexT]"); keeping it in a separate file (this one), where `Simplex[VertexT]` stays a genuinely opaque,
  * non-dealiased type, resolves cleanly through `object Simplex`'s companion scope instead. A minimal standalone
  * `scala-cli` repro isolating exactly this shape (opaque type + companion-object extension in one file, a same-file
  * vs. different-file consumer) is recorded in `.claude/WORKLOG-extension-companion-objects.md`.
  */
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
