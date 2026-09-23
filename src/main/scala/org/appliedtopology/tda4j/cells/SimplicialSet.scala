package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

/** `OrderedCell` instance for the generators (non-degenerate simplices) of a finite simplicial set: `dim` is the
  * generator's own dimension, and `boundary` is the normalized-chain-complex differential -- only faces that are
  * themselves bare generators (`word.isEmpty`) contribute, with the usual alternating sign; a degenerate face
  * contributes nothing, since the normalized chain complex is quasi-isomorphic to the full one. This is the only place
  * degeneracy matters for homology -- computing it needs no recursive `faceOf`, only `faces(g)` itself.
  *
  * Mirrors `simplexIsOrderedCell`'s injectable-ordering pattern (`SimplexOrderedCell.scala`).
  */
def finiteSimplicialSetIsOrderedCell[G](using
  ord: Ordering[G]
)(dimOf: G => Int, faces: G => IndexedSeq[SSetElement[G]]): G is OrderedCell =
  new (G is OrderedCell):
    override lazy val ordering = ord
    extension (g: G)
      override def dim = dimOf(g)
      override def boundary[CoefficientT: Field as fr]: Seq[(G, CoefficientT)] =
        faces(g).zipWithIndex.collect { case (SSetElement(Nil, target), i) =>
          (target, if i % 2 == 0 then fr.one else fr.negate(fr.one))
        }

/** A finite simplicial set presented by generators (non-degenerate simplices) and, for each generator, its primitive
  * face data -- from which everything else (arbitrary `d_i`/`s_j`, the `OrderedCell` instance feeding the homology
  * engines) is inferred via `faceOf`/`insertOuter` (`SSetElement.scala`).
  *
  * `faces(g)`, for `g` of dimension `n`, must supply exactly `n+1` already-normalized `SSetElement`s of dimension `n-1`
  * each (empty for `n = 0`) -- `validate()` checks this contract plus the simplicial identities at runtime, since it's
  * easy to get a hand-written presentation subtly wrong with no crash, just silently wrong homology.
  */
class FiniteSimplicialSet[G](
  val generatorsByDim: IndexedSeq[Set[G]],
  val faces: G => IndexedSeq[SSetElement[G]]
)(using val ord: Ordering[G]):
  // Precomputed once: dim is hot (sort keys, pivot lookups), and this also gives dimOf a real error on an
  // unregistered generator instead of silently returning -1.
  private val dimByGenerator: Map[G, Int] =
    generatorsByDim.zipWithIndex.flatMap((gs, d) => gs.map(_ -> d)).toMap

  def dimOf(g: G): Int = dimByGenerator(g)

  given cellInstance: (G is OrderedCell) = finiteSimplicialSetIsOrderedCell(using ord)(dimOf, faces)

  def sOp(j: Int, elt: SSetElement[G]): SSetElement[G] = SSetElement(insertOuter(j, elt.word), elt.generator)
  def dOp(i: Int, elt: SSetElement[G]): SSetElement[G] = faceOf(i, elt.word, elt.generator, faces)

  private def isNormalWord(word: List[Int]): Boolean =
    word.forall(_ >= 0) && word.zip(word.drop(1)).forall((a, b) => a > b)

  private def structuralErrors(g: G, n: Int): Seq[String] =
    val fs = faces(g)
    // Dimension 0 is a genuine special case, not n=0 of the general n+1 rule: face maps target dimension
    // n-1, which doesn't exist below 0, so a 0-dimensional generator has no faces at all (same convention
    // `Simplex`/`Cube` already use).
    val expectedArity = if n == 0 then 0 else n + 1
    val arity =
      if fs.length != expectedArity then Seq(s"$g (dim $n): expected $expectedArity faces, found ${fs.length}")
      else Seq.empty
    val perFace = fs.zipWithIndex.flatMap { case (SSetElement(word, target), i) =>
      if !dimByGenerator.contains(target) then Seq(s"$g (dim $n): d_$i targets unregistered generator $target")
      else
        val wordErr =
          if !isNormalWord(word) then
            Seq(s"$g (dim $n): d_$i has malformed degeneracy word $word (must be strictly decreasing, all >= 0)")
          else Seq.empty
        val targetDim = dimByGenerator(target) + word.length
        val dimErr =
          if targetDim != n - 1 then Seq(s"$g (dim $n): d_$i has dimension $targetDim, expected ${n - 1}")
          else Seq.empty
        wordErr ++ dimErr
    }
    arity ++ perFace

  private def identityErrors(g: G, n: Int): Seq[String] =
    if n < 2 then Seq.empty
    else
      for
        i <- 0 until n
        j <- (i + 1) to n
        lhs = dOp(i, dOp(j, SSetElement[G](Nil, g)))
        rhs = dOp(j - 1, dOp(i, SSetElement[G](Nil, g)))
        if lhs != rhs
      yield s"$g (dim $n): d_$i(d_$j g) = $lhs but d_${j - 1}(d_$i g) = $rhs (should agree, d_i d_j = d_{j-1} d_i)"

  def validate(): Seq[String] =
    generatorsByDim.zipWithIndex.flatMap { case (gs, n) =>
      gs.toSeq.flatMap(g => structuralErrors(g, n) ++ identityErrors(g, n))
    }

/** `product`/`coproduct`/`quotient`/`identify`/`elementsAtDim` are scoped here, on `FiniteSimplicialSet`'s companion,
  * rather than as top-level `cells` package defs: names this generic (especially `product`) are exactly the kind of
  * top-level-name collision hazard documented elsewhere in this codebase (CLAUDE.md, the extension-companion-object
  * pattern) -- every file that does `import cells.{given, *}` would otherwise have them ambient. Callers write
  * `FiniteSimplicialSet.product(...)`, or `import FiniteSimplicialSet.*` locally where the ergonomics matter.
  */
object FiniteSimplicialSet:

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
    * generator degenerate by exactly `J` -- valid for the same diagonal reason above: an `s_j` present in both sides'
    * own normal form at once is exactly the product's own `s_j` applied to what's left.
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

    new FiniteSimplicialSet(generatorsByDim, facesOf)

  /** Coproduct (disjoint union) of two finite simplicial sets, `(X + Y)_n = X_n + Y_n` -- no degeneracy interaction
    * between the two factors, unlike `product`: a generator's faces stay entirely within whichever side it came from,
    * so this is just tagging generators with `Left`/`Right` and delegating.
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

    new FiniteSimplicialSet(generatorsByDim, facesOf)

  /** Quotient of a finite simplicial set by an arbitrary map from generators to elements: `quotientMap(g)` says what
    * `g` becomes in the quotient -- either a genuine surviving representative (`SSetElement(Nil, g)`, a FIXED POINT) or
    * a properly degenerate collapse (`SSetElement(word, rep)` for some OTHER representative `rep`). This is
    * deliberately more general than `G => G` (generator-to-generator only), because identifying cells can crush one of
    * them down a dimension, not just merge same-dimension cells with a peer. Concretely: Hatcher's own single-2-simplex
    * Delta-complex model of RP^2 (`Algebraic Topology`, Example 2.4 -- cross-validated against the
    * independently-hand-built `SimplicialSetFixtures.realProjectiveSpace(2)` in `SimplicialSetConstructionsSpec`) glues
    * two of a filled triangle's three edges together into one loop, but the THIRD edge doesn't glue to anything else --
    * it collapses entirely to a degenerate point over the surviving vertex. A `G => G` quotient map cannot express that
    * third case at all, only `G => SSetElement[G]` can (`identify`, below, covers the common generator-to-generator
    * case ergonomically without ever needing this extra generality itself).
    *
    * `quotientMap` must be dimension-consistent (`dimOf(quotientMap(g).generator) + quotientMap(g).word.length ==
    * dimOf(g)`) and every generator must resolve to a fixed point IN ONE STEP (some generator `rep` with
    * `quotientMap(rep) == SSetElement(Nil, rep)`) -- `quotientMap` is not itself iterated to a fixpoint, so a *chain*
    * (`quotientMap(a) = SSetElement(Nil, b)`, `quotientMap(b) = SSetElement(Nil, c)`, `b` never a fixed point) is a
    * caller error, checked explicitly below rather than left to `validate()`: `validate()`'s own structural check only
    * inspects `faces(g)` for `g` already in the surviving `generatorsByDim`, so a chain would slip through silently
    * whenever no surviving cell's face happens to target the broken link directly (`identify` is immune to this by
    * construction -- its own `find` always path-compresses to a genuine root -- so this exposure is specific to a
    * hand-written `quotientMap` passed to `quotient` directly).
    *
    * `facesOf` reuses the ORIGINAL face data of a surviving representative, then pushes each face's own target through
    * `quotientMap` too, composing the two degeneracy words via `insertOuter` one step at a time
    * (`word.foldRight(mapped.word)(insertOuter)`) -- a face that was already degenerate, whose target ALSO collapses
    * further under the quotient, needs both effects combined into one normalized word, exactly the composition
    * `s_word(s_word2(rep2))` that `insertOuter` is built to accumulate.
    *
    * `validate()` on the RESULT is a necessary precondition beyond the fixed-point check above -- it will flag a
    * `quotientMap` that isn't dimension-consistent as a structural error -- but NOT a sufficient correctness check: it
    * verifies the simplicial identities hold, not that the quotient is the intended one, and an over-eager
    * `quotientMap` can produce an internally-consistent but topologically wrong space. Homology cross-checks against an
    * independently-derived expectation are what actually establish correctness.
    */
  def quotient[G: Ordering](
    sset: FiniteSimplicialSet[G],
    quotientMap: G => SSetElement[G]
  ): FiniteSimplicialSet[G] =
    def isFixedPoint(g: G): Boolean = quotientMap(g) == SSetElement(Nil, g)

    val generatorsByDim: IndexedSeq[Set[G]] =
      sset.generatorsByDim.map(_.filter(isFixedPoint))

    for g <- sset.generatorsByDim.flatten do
      require(
        isFixedPoint(quotientMap(g).generator),
        s"quotient: quotientMap($g) = ${quotientMap(g)}, whose own generator is not a fixed point -- " +
          "quotientMap must resolve every generator in one step, not via a multi-step chain"
      )

    def facesOf(rep: G): IndexedSeq[SSetElement[G]] =
      sset.faces(rep).map { case SSetElement(word, target) =>
        val mapped = quotientMap(target)
        SSetElement(word.foldRight(mapped.word)(insertOuter), mapped.generator)
      }

    new FiniteSimplicialSet(generatorsByDim, facesOf)

  /** Ergonomic layer over `quotient` for the common case: identify PAIRS of same-dimension generators with each other
    * directly (never a degenerate collapse down a dimension -- see `quotient`'s own doc for that more general case).
    * Computes the quotient map via a small union-find over the transitive closure of `pairs`, implemented fresh right
    * here rather than reusing `streams.UnionFind`: `cells` sits below `streams` in this codebase's package layering
    * (`algebra -> cells -> streams -> homology`), so importing it here would be a backwards dependency, and a
    * hand-built, small-scale set of generators has no performance need for anything beyond the simplest union-find
    * anyway. Each connected component's `Ordering[G]`-minimum member is its canonical representative -- a
    * deterministic, reproducible choice rather than an arbitrary one.
    */
  def identify[G: Ordering](
    sset: FiniteSimplicialSet[G],
    pairs: Seq[(G, G)]
  ): FiniteSimplicialSet[G] =
    val ord = summon[Ordering[G]]
    val parent = scala.collection.mutable.Map.from(sset.generatorsByDim.flatten.map(g => g -> g))

    def find(g: G): G =
      val p = parent(g)
      if p == g then g
      else
        val root = find(p)
        parent(g) = root
        root

    for (a, b) <- pairs do
      require(
        sset.dimOf(a) == sset.dimOf(b),
        s"identify: cannot identify generators of different dimension ($a at dim ${sset.dimOf(a)}, " +
          s"$b at dim ${sset.dimOf(b)})"
      )
      val (ra, rb) = (find(a), find(b))
      if ra != rb then if ord.lt(ra, rb) then parent(rb) = ra else parent(ra) = rb

    quotient(sset, g => SSetElement(Nil, find(g)))
