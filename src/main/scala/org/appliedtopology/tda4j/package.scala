package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

/** Thin user-facing facade over `SimplicialHomologyContext`: brings `Chain`'s own `RingModule` arithmetic
  * (`+`/`-`/`⊠`/etc.) into scope on `Simplex` values directly, via `export` plus an implicit `Simplex -> Chain`
  * widening -- convenience for interactive/notebook-style use, never itself consulted by an engine (see CLAUDE.md's
  * generic-`given`-capture note).
  */
class TDAContext[VertexT: Ordering, CoefficientT: Field, FiltrationT: Ordering]
    extends SimplicialHomologyContext[VertexT, CoefficientT, FiltrationT]():
  val chainIsRingModule: Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT } =
    summon[Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT }]
  export chainIsRingModule.*
  import scala.language.implicitConversions
  given [T: Ordering] => Conversion[Simplex[T], Chain[Simplex[T], CoefficientT]] =
    Chain.apply
