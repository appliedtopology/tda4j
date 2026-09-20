package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

/** Every simplex of a finitely-generated simplicial set is, by the same Eilenberg-Zilber normal-form theorem
  * `SSetElement` itself is built on, uniquely `(word, generator)` with `generator` non-degenerate of some dimension
  * `p <= n` and `word` a size-`(n - p)` subset of `{0, ..., n-1}` (sorted decreasing) -- so this enumerates ALL of
  * `X_n`, not just its generators, by pairing every generator at every dimension `p <= n` with every such subset.
  * Needed to build `product`, which has to consider every element of `X_n x Y_n`, not just the non-degenerate ones,
  * before filtering down to the pairs that are non-degenerate in the product.
  */
def elementsAtDim[G](sset: FiniteSimplicialSet[G], n: Int): IndexedSeq[SSetElement[G]] =
  sset.generatorsByDim.zipWithIndex.flatMap { case (gens, p) =>
    if p > n then IndexedSeq.empty
    else
      val k = n - p
      val words = (0 until n).toVector.combinations(k).map(_.sorted.reverse.toList).toIndexedSeq
      gens.toIndexedSeq.flatMap(g => words.map(w => SSetElement(w, g)))
  }

private def wordOrdering: Ordering[List[Int]] = new Ordering[List[Int]]:
  def compare(xs: List[Int], ys: List[Int]): Int = (xs, ys) match
    case (Nil, Nil)         => 0
    case (Nil, _)           => -1
    case (_, Nil)           => 1
    case (x :: xt, y :: yt) => if x != y then x - y else compare(xt, yt)

def ssetElementOrdering[G](using ordG: Ordering[G]): Ordering[SSetElement[G]] =
  new Ordering[SSetElement[G]]:
    def compare(a: SSetElement[G], b: SSetElement[G]): Int =
      val byGen = ordG.compare(a.generator, b.generator)
      if byGen != 0 then byGen else wordOrdering.compare(a.word, b.word)

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
  val ex = ssetElementOrdering[GX]
  val ey = ssetElementOrdering[GY]
  new Ordering[ProductGenerator[GX, GY]]:
    def compare(a: ProductGenerator[GX, GY], b: ProductGenerator[GX, GY]): Int =
      val byX = ex.compare(a.x, b.x)
      if byX != 0 then byX else ey.compare(a.y, b.y)

/** Product of two finite simplicial sets, `(X x Y)_n = X_n x Y_n` degreewise.
  *
  * Top dimension is provably `maxDim(x) + maxDim(y)`, not merely an analogy to CW-complex dimension: a non-degenerate
  * pair `(a,b)` at dimension `n` needs `a.word`/`b.word` to be disjoint subsets of an `n`-element set, and each has
  * size `n - dim(generator) >= n - maxDim` (since `dim(generator) <= maxDim`), so disjointness forces
  * `(n - maxDim(x)) + (n - maxDim(y)) <= n`, i.e. `n <= maxDim(x) + maxDim(y)` -- beyond that bound, no pair can
  * possibly be non-degenerate, permanently, not just at that one dimension.
  *
  * Face maps reuse `faceOf` on each side independently; the result can come back degenerate IN THE PRODUCT even when
  * the input pair wasn't (a nonempty common word entry can appear on both sides after taking a face), so it's
  * re-normalized by stripping the common degeneracy set `J` from both words and wrapping the stripped pair as a new
  * generator degenerate by exactly `J` -- valid for the same diagonal reason above: an `s_j` present in both sides' own
  * normal form at once is exactly the product's own `s_j` applied to what's left.
  */
def product[GX: Ordering, GY: Ordering](
  xs: FiniteSimplicialSet[GX],
  ys: FiniteSimplicialSet[GY]
): FiniteSimplicialSet[ProductGenerator[GX, GY]] =
  given prodOrd: Ordering[ProductGenerator[GX, GY]] = productGeneratorOrdering[GX, GY]

  val topDim = (xs.generatorsByDim.length - 1) + (ys.generatorsByDim.length - 1)

  val generatorsByDim: IndexedSeq[Set[ProductGenerator[GX, GY]]] =
    (0 to topDim).map { n =>
      val xsAtN = elementsAtDim(xs, n)
      val ysAtN = elementsAtDim(ys, n)
      (for
        a <- xsAtN
        b <- ysAtN
        if isNonDegeneratePair(a, b)
      yield ProductGenerator(a, b)).toSet
    }

  def facesOf(g: ProductGenerator[GX, GY]): IndexedSeq[SSetElement[ProductGenerator[GX, GY]]] =
    val n = xs.dimOf(g.x.generator) + g.x.word.length
    if n == 0 then IndexedSeq.empty
    else
      (0 to n).map { i =>
        val rawX = faceOf(i, g.x.word, g.x.generator, xs.faces)
        val rawY = faceOf(i, g.y.word, g.y.generator, ys.faces)
        val commonJ = rawX.word.toSet.intersect(rawY.word.toSet)
        // rawX.word/rawY.word are both subsets of the SAME shared gap-position domain {0, ..., n-2} (both sides
        // are simplices of the same dimension n-1). Stripping the shared gaps commonJ shrinks that domain, so the
        // remaining (private) gap positions must be RELABELED via the rank function -- the standard order
        // isomorphism from `domain \ commonJ` down to `{0, ..., m-1}` -- not merely deleted in place; the outer
        // wrapping word `commonJ` itself needs no such relabeling, since it's already expressed in the final
        // (n-1)-dimensional domain's own coordinates, exactly what an outer word is supposed to be.
        def relabel(word: List[Int]): List[Int] =
          word.filterNot(commonJ.contains).map(e => e - commonJ.count(_ < e))
        val strippedX = SSetElement(relabel(rawX.word), rawX.generator)
        val strippedY = SSetElement(relabel(rawY.word), rawY.generator)
        SSetElement(commonJ.toList.sorted.reverse, ProductGenerator(strippedX, strippedY))
      }

  new FiniteSimplicialSet(prodOrd)(generatorsByDim, facesOf)

def eitherOrdering[GX, GY](using ox: Ordering[GX], oy: Ordering[GY]): Ordering[Either[GX, GY]] =
  new Ordering[Either[GX, GY]]:
    def compare(a: Either[GX, GY], b: Either[GX, GY]): Int = (a, b) match
      case (Left(x), Left(y))   => ox.compare(x, y)
      case (Right(x), Right(y)) => oy.compare(x, y)
      case (Left(_), Right(_))  => -1
      case (Right(_), Left(_))  => 1

/** Coproduct (disjoint union) of two finite simplicial sets, `(X + Y)_n = X_n + Y_n` -- no degeneracy interaction
  * between the two factors, unlike `product`: a generator's faces stay entirely within whichever side it came from, so
  * this is just tagging generators with `Left`/`Right` and delegating.
  */
def coproduct[GX: Ordering, GY: Ordering](
  xs: FiniteSimplicialSet[GX],
  ys: FiniteSimplicialSet[GY]
): FiniteSimplicialSet[Either[GX, GY]] =
  given eitherOrd: Ordering[Either[GX, GY]] = eitherOrdering[GX, GY]

  val topDim = math.max(xs.generatorsByDim.length, ys.generatorsByDim.length) - 1
  val generatorsByDim: IndexedSeq[Set[Either[GX, GY]]] =
    (0 to topDim).map { d =>
      xs.generatorsByDim.lift(d).getOrElse(Set.empty[GX]).map(g => Left(g): Either[GX, GY]) ++
        ys.generatorsByDim.lift(d).getOrElse(Set.empty[GY]).map(g => Right(g): Either[GX, GY])
    }

  def facesOf(g: Either[GX, GY]): IndexedSeq[SSetElement[Either[GX, GY]]] = g match
    case Left(gx)  => xs.faces(gx).map(e => SSetElement(e.word, Left(e.generator): Either[GX, GY]))
    case Right(gy) => ys.faces(gy).map(e => SSetElement(e.word, Right(e.generator): Either[GX, GY]))

  new FiniteSimplicialSet(eitherOrd)(generatorsByDim, facesOf)
