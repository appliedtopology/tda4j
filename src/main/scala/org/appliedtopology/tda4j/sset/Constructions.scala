package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

/* Implementations behind `FiniteSimplicialSet`'s construction methods (`x.product(y)`, `x.cone`, `x.wedge(...)`,
 * ...) and the orderings their generator types need. Users never call `Constructions` directly. */

private[sset] def ssetElementOrdering[G](using ordG: Ordering[G]): Ordering[SSetElement[G]] =
  given Ordering[List[Int]] = scala.math.Ordering.Implicits.seqOrdering
  Ordering.by(e => (e.generator, e.word))

/** A pair `(a, b) in X_n x Y_n` is degenerate in the product iff some single `j` degenerates both sides at once (`s_j`
  * on `X x Y` is literally the diagonal `(s_j, s_j)`) -- and `a` is `s_j`-degenerate exactly when `j` appears in
  * `a.word` (the normal form's own meaning: `word` is precisely the set of indices `a` is degenerate at, not just the
  * outermost one -- e.g. `s_1 s_0(v)` is degenerate at both `s_1` directly and, via `s_0 s_0 = s_1 s_0`, at `s_0` too,
  * matching both entries of its word `[1, 0]`). So non-degeneracy of the pair is exactly disjointness of the two words.
  * Cross-checked against the definitional `degeneracy(j, face(j, a)) == a` test in `SimplicialSetConstructionsSpec`.
  */
private[sset] def isNonDegeneratePair[GX, GY](a: SSetElement[GX], b: SSetElement[GY]): Boolean =
  a.word.toSet.intersect(b.word.toSet).isEmpty

/** A non-degenerate `n`-simplex of `X x Y`, presented as the pair of `SSetElement`s (each possibly itself degenerate
  * over its own non-degenerate generator) whose word-sets are disjoint -- NOT an Eilenberg-Zilber shuffle triple. The
  * shuffle family indexes the classical chain MAP between `C_*(X) tensor C_*(Y)` and `C_*(X x Y)`, not the product's
  * own non-degenerate simplices: `(e_X, e_Y)`, both non-degenerate of dimension 1, is a non-degenerate 1-simplex of
  * `X x Y` that no `(p,q)`-shuffle with `p+q=1` could ever produce, since a shuffle needs `p+q=n` but here `p=q=1=n`.
  * See `.claude/WORKLOG-simplicial-set-constructions.md`.
  */
case class ProductGenerator[GX, GY](x: SSetElement[GX], y: SSetElement[GY])

object ProductGenerator:
  given productGeneratorOrder: [GX: Ordering, GY: Ordering] => Ordering[ProductGenerator[GX, GY]] =
    productGeneratorOrdering[GX, GY]

private[sset] def productGeneratorOrdering[GX, GY](using
  ox: Ordering[GX],
  oy: Ordering[GY]
): Ordering[ProductGenerator[GX, GY]] =
  given ordX: Ordering[SSetElement[GX]] = ssetElementOrdering[GX]
  given ordY: Ordering[SSetElement[GY]] = ssetElementOrdering[GY]
  Ordering.by(g => (g.x, g.y))

private[sset] def eitherOrdering[GX, GY](using ox: Ordering[GX], oy: Ordering[GY]): Ordering[Either[GX, GY]] =
  new Ordering[Either[GX, GY]]:
    def compare(a: Either[GX, GY], b: Either[GX, GY]): Int = (a, b) match
      case (Left(x), Left(y))   => ox.compare(x, y)
      case (Right(x), Right(y)) => oy.compare(x, y)
      case (Left(_), Right(_))  => -1
      case (Right(_), Left(_))  => 1

private[sset] object Constructions:
  import ConeGenerator.*

  /** Every simplex of a finitely-generated simplicial set is, by the same Eilenberg-Zilber normal-form theorem
    * `SSetElement` itself is built on, uniquely `(word, generator)` with `generator` non-degenerate of some dimension
    * `p <= n` and `word` a size-`(n - p)` subset of `{0, ..., n-1}` (sorted decreasing) -- so this enumerates ALL of
    * `X_n`, not just its generators, by pairing every generator at every dimension `p <= n` with every such subset.
    * Needed to build `product`, which has to consider every element of `X_n x Y_n`, not just the non-degenerate ones,
    * before filtering down to the pairs that are non-degenerate in the product.
    */
  def simplices[G](sset: FiniteSimplicialSet[G], n: Int): IndexedSeq[SSetElement[G]] =
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
        val xsAtN = simplices(xs, n)
        val ysAtN = simplices(ys, n)
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
    * here rather than reusing `UnionFind`: `cells` sits below `streams` in this codebase's package layering (`algebra
    * -> cells -> streams -> homology`), so importing it here would be a backwards dependency, and a hand-built,
    * small-scale set of generators has no performance need for anything beyond the simplest union-find anyway. Each
    * connected component's `Ordering[G]`-minimum member is its canonical representative -- a deterministic,
    * reproducible choice rather than an arbitrary one.
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

  /** The sub-simplicial set on `keep` -- which must be closed under taking faces. */
  def subcomplex[G](sset: FiniteSimplicialSet[G], keep: Set[G]): FiniteSimplicialSet[G] =
    for g <- keep; face <- sset.faces(g) do
      require(keep.contains(face.generator), s"subcomplex: $g is kept but its face generator ${face.generator} is not")
    val byDim = sset.generatorsByDim.map(_.filter(keep.contains))
    new FiniteSimplicialSet(byDim.reverse.dropWhile(_.isEmpty).reverse, sset.faces)(using sset.ord)

  /** The unreduced cone `CX = X ⋆ pt`: apex last, so `d_{n+1} Cone(g) = g` and `d_i Cone(g) = Cone(d_i g)` (a
    * degenerate face `s_J h` becomes `s_J Cone(h)`: no degeneracy ever touches the apex). Contractible.
    */
  def cone[G](x: FiniteSimplicialSet[G]): FiniteSimplicialSet[ConeGenerator[G]] =
    given Ordering[G] = x.ord
    val byDim: IndexedSeq[Set[ConeGenerator[G]]] =
      IndexedSeq.tabulate(x.generatorsByDim.length + 1) { d =>
        val base: Set[ConeGenerator[G]] = x.generators(d).map(g => Base(g): ConeGenerator[G]).toSet
        val cones: Set[ConeGenerator[G]] = x.generators(d - 1).map(g => Cone(g): ConeGenerator[G]).toSet
        (if d == 0 then Set[ConeGenerator[G]](Apex) else Set.empty[ConeGenerator[G]]) ++ base ++ cones
      }
    def mapped(e: SSetElement[G])(wrap: G => ConeGenerator[G]): SSetElement[ConeGenerator[G]] =
      SSetElement(e.word, wrap(e.generator))
    def facesOf(g: ConeGenerator[G]): IndexedSeq[SSetElement[ConeGenerator[G]]] = g match
      case Apex    => IndexedSeq.empty
      case Base(h) => x.faces(h).map(mapped(_)(Base(_)))
      case Cone(h) =>
        val n = x.dimOf(h)
        if n == 0 then IndexedSeq(SSetElement(Nil, Apex), SSetElement(Nil, Base(h)))
        else x.faces(h).map(mapped(_)(Cone(_))) :+ SSetElement(Nil, Base(h))
    new FiniteSimplicialSet(byDim, facesOf)

  /** The unreduced suspension `SX`: two cones on `X` glued along their common base. */
  def suspension[G](x: FiniteSimplicialSet[G]): FiniteSimplicialSet[Either[ConeGenerator[G], ConeGenerator[G]]] =
    given Ordering[G] = x.ord
    val c = cone(x)
    val both = coproduct(c, c)
    given Ordering[Either[ConeGenerator[G], ConeGenerator[G]]] = both.ord
    val glue = x.generatorsByDim.flatten.toSeq.map { g =>
      (
        Left(Base(g)): Either[ConeGenerator[G], ConeGenerator[G]],
        Right(Base(g)): Either[ConeGenerator[G], ConeGenerator[G]]
      )
    }
    identify(both, glue)

  /** The wedge `X ∨ Y` at the vertices `vx` of `X` and `vy` of `Y` (a base point is just a chosen vertex). */
  def wedge[GX, GY](
    x: FiniteSimplicialSet[GX],
    vx: GX,
    y: FiniteSimplicialSet[GY],
    vy: GY
  ): FiniteSimplicialSet[Either[GX, GY]] =
    require(x.dimOf(vx) == 0 && y.dimOf(vy) == 0, "wedge points must be vertices")
    given Ordering[GX] = x.ord
    given Ordering[GY] = y.ord
    val both = coproduct(x, y)
    given Ordering[Either[GX, GY]] = both.ord
    identify(both, Seq((Left(vx): Either[GX, GY], Right(vy): Either[GX, GY])))

  /** The smash product `X ∧ Y = (X × Y) / (X ∨ Y)` of pointed simplicial sets (base points = the chosen vertices `vx`,
    * `vy`): the product with the whole wedge `X × {vy} ∪ {vx} × Y` collapsed to the single base vertex `(vx, vy)`.
    * Built with [[FiniteSimplicialSet.quotient]], sending each wedge simplex of dimension `n` to the `n`-fold
    * degeneracy of that vertex.
    */
  def smash[GX, GY](
    x: FiniteSimplicialSet[GX],
    vx: GX,
    y: FiniteSimplicialSet[GY],
    vy: GY
  ): FiniteSimplicialSet[ProductGenerator[GX, GY]] =
    require(x.dimOf(vx) == 0 && y.dimOf(vy) == 0, "smash base points must be vertices")
    given Ordering[GX] = x.ord
    given Ordering[GY] = y.ord
    val prod = product(x, y)
    given Ordering[ProductGenerator[GX, GY]] = prod.ord
    val base = ProductGenerator(SSetElement[GX](Nil, vx), SSetElement[GY](Nil, vy))
    def inWedge(g: ProductGenerator[GX, GY]): Boolean = g.x.generator == vx || g.y.generator == vy
    def collapse(g: ProductGenerator[GX, GY]): SSetElement[ProductGenerator[GX, GY]] =
      if g == base || !inWedge(g) then SSetElement(Nil, g)
      else
        val n = prod.dimOf(g)
        SSetElement(((n - 1) to 0 by -1).toList, base)
    quotient(prod, collapse)

  /** The join `X ⋆ Y`. A simplex of the join is a pair `(a, b)` with `a ∈ X_i ∪ {∅}`, `b ∈ Y_j ∪ {∅}` (not both empty),
    * of dimension `i + j + 1`; `(a, b)` is non-degenerate iff `a` and `b` both are (an empty side is never degenerate).
    * Faces: `d_k (a, b) = (d_k a, b)` for `k <= i` and `(a, d_{k-i-1} b)` for `k > i`, where removing the only vertex
    * of a side leaves the other side alone, and a degenerate face on the `Y` side shifts its degeneracy indices by
    * `i + 1`. `S^0 ⋆ X` is the unreduced suspension, `pt ⋆ X` the cone, and `S^p ⋆ S^q = S^(p+q+1)`.
    */
  def join[GX, GY](x: FiniteSimplicialSet[GX], y: FiniteSimplicialSet[GY]): FiniteSimplicialSet[JoinGenerator[GX, GY]] =
    import JoinGenerator.*
    given Ordering[GX] = x.ord
    given Ordering[GY] = y.ord
    type J = JoinGenerator[GX, GY]
    val topDim = (x.generatorsByDim.length - 1) + (y.generatorsByDim.length - 1) + 1
    val byDim: IndexedSeq[Set[J]] = IndexedSeq.tabulate(topDim + 1) { n =>
      val ofX: Set[J] = x.generators(n).map(g => OfX(g): J).toSet
      val ofY: Set[J] = y.generators(n).map(g => OfY(g): J).toSet
      val both: Set[J] =
        (for
          i <- 0 until n
          a <- x.generators(i)
          b <- y.generators(n - 1 - i)
        yield Both(a, b): J).toSet
      ofX ++ ofY ++ both
    }
    def facesOf(g: J): IndexedSeq[SSetElement[J]] = g match
      case OfX(a)     => x.faces(a).map(e => SSetElement(e.word, OfX(e.generator): J))
      case OfY(b)     => y.faces(b).map(e => SSetElement(e.word, OfY(e.generator): J))
      case Both(a, b) =>
        val i = x.dimOf(a)
        val j = y.dimOf(b)
        IndexedSeq.tabulate(i + j + 2) { k =>
          if k <= i then
            if i == 0 then SSetElement(Nil, OfY(b): J)
            else
              val e = x.faces(a)(k)
              SSetElement(e.word, Both(e.generator, b): J)
          else
            val m = k - i - 1
            if j == 0 then SSetElement(Nil, OfX(a): J)
            else
              val e = y.faces(b)(m)
              SSetElement(e.word.map(_ + i + 1), Both(a, e.generator): J)
        }
    new FiniteSimplicialSet(byDim.reverse.dropWhile(_.isEmpty).reverse, facesOf)(using summon[Ordering[J]])

  /** Whether the 1-skeleton is connected (the empty set counts as not connected). */
  def isConnected[G](x: FiniteSimplicialSet[G]): Boolean =
    val vertices = x.generators(0).toVector
    if vertices.isEmpty then false
    else
      val parent = scala.collection.mutable.Map.from(vertices.map(v => v -> v))
      def find(v: G): G = if parent(v) == v then v
      else
        val r = find(parent(v)); parent(v) = r; r
      for e <- x.generators(1) do
        val ends = x.faces(e).map(f => find(f.generator))
        if ends(0) != ends(1) then parent(ends(0)) = ends(1)
      vertices.map(find).distinct.size == 1
