package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

def ssetElementOrdering[G](using ordG: Ordering[G]): Ordering[SSetElement[G]] =
  given Ordering[List[Int]] = scala.math.Ordering.Implicits.seqOrdering
  Ordering.by(e => (e.generator, e.word))

/** A pair `(a, b) in X_n x Y_n` is degenerate in the product iff some single `j` degenerates both sides at once (`s_j`
  * on `X x Y` is literally the diagonal `(s_j, s_j)`) -- and `a` is `s_j`-degenerate exactly when `j` appears in
  * `a.word` (the normal form's own meaning: `word` is precisely the set of indices `a` is degenerate at, not just the
  * outermost one -- e.g. `s_1 s_0(v)` is degenerate at both `s_1` directly and, via `s_0 s_0 = s_1 s_0`, at `s_0` too,
  * matching both entries of its word `[1, 0]`). So non-degeneracy of the pair is exactly disjointness of the two words.
  * Cross-checked against the definitional `sOp(j, dOp(j, a)) == a` test in `SimplicialSetConstructionsSpec`.
  */
def isNonDegeneratePair[GX, GY](a: SSetElement[GX], b: SSetElement[GY]): Boolean =
  a.word.toSet.intersect(b.word.toSet).isEmpty

/** A non-degenerate `n`-simplex of `X x Y`, presented as the pair of `SSetElement`s (each possibly itself degenerate
  * over its own non-degenerate generator) whose word-sets are disjoint -- NOT an Eilenberg-Zilber shuffle triple. The
  * shuffle family indexes the classical chain MAP between `C_*(X) tensor C_*(Y)` and `C_*(X x Y)`, not the product's
  * own non-degenerate simplices: `(e_X, e_Y)`, both non-degenerate of dimension 1, is a non-degenerate 1-simplex of
  * `X x Y` that no `(p,q)`-shuffle with `p+q=1` could ever produce, since a shuffle needs `p+q=n` but here `p=q=1=n`.
  * See `.claude/WORKLOG-simplicial-set-constructions.md`.
  */
case class ProductGenerator[GX, GY](x: SSetElement[GX], y: SSetElement[GY])

def productGeneratorOrdering[GX, GY](using ox: Ordering[GX], oy: Ordering[GY]): Ordering[ProductGenerator[GX, GY]] =
  given ordX: Ordering[SSetElement[GX]] = ssetElementOrdering[GX]
  given ordY: Ordering[SSetElement[GY]] = ssetElementOrdering[GY]
  Ordering.by(g => (g.x, g.y))

def eitherOrdering[GX, GY](using ox: Ordering[GX], oy: Ordering[GY]): Ordering[Either[GX, GY]] =
  new Ordering[Either[GX, GY]]:
    def compare(a: Either[GX, GY], b: Either[GX, GY]): Int = (a, b) match
      case (Left(x), Left(y))   => ox.compare(x, y)
      case (Right(x), Right(y)) => oy.compare(x, y)
      case (Left(_), Right(_))  => -1
      case (Right(_), Left(_))  => 1
