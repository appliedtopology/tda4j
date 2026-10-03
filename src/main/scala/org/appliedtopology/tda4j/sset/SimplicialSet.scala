package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

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

/** A simplicial set presented, as `FiniteSimplicialSet` is, by its non-degenerate simplices ("generators") and each
  * generator's faces -- but without insisting that every dimension be materialized, so it can be INFINITE (the nerve of
  * a finite group has `(|G|-1)^n` generators in every dimension `n`) and generated lazily.
  *
  * `skeleton(n)` materializes dimensions `0..n` as a [[FiniteSimplicialSet]]. The homology of an `n`-skeleton agrees
  * with the full set's only in degrees `< n`: degree `n` classes of the skeleton include ones the missing
  * `(n+1)`-simplices would kill. To get `H_k` of the full set, take `skeleton(k + 1)` and ignore the top degree.
  */
trait SimplicialSet[G]:
  /** Ordering used to tie-break generators (same role as in [[FiniteSimplicialSet]]). */
  def ord: Ordering[G]

  /** The generators (non-degenerate simplices) of dimension `n` -- the cells homology sees. Empty for a negative `n`,
    * or beyond a finite set's top dimension.
    */
  def generators(n: Int): Iterable[G]

  /** Dimension of a generator. */
  def dimOf(g: G): Int

  /** `faces(g)` for `g` of dimension `n`: exactly `n+1` normalized `SSetElement`s of dimension `n-1` (none if `n = 0`).
    */
  def faces: G => IndexedSeq[SSetElement[G]]

  /** Dimensions `0..n` as a finite simplicial set (see the trait doc for what its homology means). */
  def skeleton(n: Int): FiniteSimplicialSet[G] =
    require(n >= 0, s"skeleton dimension must be >= 0, got $n")
    new FiniteSimplicialSet(IndexedSeq.tabulate(n + 1)(d => generators(d).toSet), faces)(using ord)

/** Every ready-made simplicial set and constructor is here: `SimplicialSet.sphere(2)`, `SimplicialSet.torus`,
  * `SimplicialSet.fromSimplicialComplex(facets)`, `SimplicialSet(generatorsByDim, faces)`, ... (see
  * [[SimplicialSetCatalog]]). Operations on a set you already have are methods of [[FiniteSimplicialSet]].
  */
object SimplicialSet extends SimplicialSetCatalog

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
)(using val ord: Ordering[G])
    extends SimplicialSet[G]:

  // ----- enumeration

  /** The generators (non-degenerate simplices) of dimension `n`, in `ord` order -- the cells homology sees. */
  def generators(n: Int): IndexedSeq[G] =
    generatorsByDim.lift(n).fold(IndexedSeq.empty)(_.toIndexedSeq.sorted(using ord))

  /** Every generator, dimension by dimension, each dimension in `ord` order. */
  def allGenerators: IndexedSeq[G] = generatorsByDim.indices.flatMap(generators)

  /** EVERY `n`-simplex, degenerate ones included, each as `(degeneracy word, generator)` -- by the Eilenberg-Zilber
    * normal form, a generator `g` of dimension `p <= n` under each strictly decreasing word of `n - p` indices from
    * `0..n-1`. (`generators(n)` is the non-degenerate part.)
    */
  def simplices(n: Int): IndexedSeq[SSetElement[G]] = Constructions.simplices(this, n)

  /** The highest dimension with a generator (`-1` for the empty set). */
  def dimension: Int = generatorsByDim.lastIndexWhere(_.nonEmpty)

  /** Number of generators per dimension (Sage's `f_vector`). */
  def fVector: Vector[Int] = generatorsByDim.map(_.size).toVector

  /** `Σ (-1)^n · #generators(n)` -- equal to `Σ (-1)^n · β_n` over any field. */
  def eulerCharacteristic: Int = fVector.zipWithIndex.map((count, n) => if n % 2 == 0 then count else -count).sum

  /** Whether the 1-skeleton is connected (the empty set counts as not connected). */
  def isConnected: Boolean = Constructions.isConnected(this)

  /** `dim H_n(X; F_p)` for `n = 0 .. dimension` (see [[BettiNumbers]]). */
  def bettiNumbers(prime: Int): Vector[Int] = BettiNumbers(this, prime)

  // Precomputed once: dim is hot (sort keys, pivot lookups), and this also gives dimOf a real error on an
  // unregistered generator instead of silently returning -1.
  private val dimByGenerator: Map[G, Int] =
    generatorsByDim.zipWithIndex.flatMap((gs, d) => gs.map(_ -> d)).toMap

  override def dimOf(g: G): Int = dimByGenerator(g)

  // ----- face and degeneracy maps on arbitrary simplices

  /** `d_i e`, normalized. */
  def face(i: Int, e: SSetElement[G]): SSetElement[G] = faceOf(i, e.word, e.generator, faces)

  /** `s_j e`, normalized. */
  def degeneracy(j: Int, e: SSetElement[G]): SSetElement[G] = SSetElement(insertOuter(j, e.word), e.generator)

  // ----- homology

  /** The generators as an `OrderedCell` (normalized chain complex). `import x.given` before building an engine over
    * `x`'s generators.
    */
  given cellInstance: (G is OrderedCell) = finiteSimplicialSetIsOrderedCell(using ord)(dimOf, faces)

  /** Every generator at filtration value 0 (plain homology through a persistence engine). */
  def stream: SimplicialSetStream[G] = SimplicialSetStream(this)

  /** A filtered simplicial set: `filtrationValue(g)` is when generator `g` appears (must not precede its faces --
    * checked). `{ case V => 0.0; case E => 1.0 }` works as the argument.
    */
  def filtered(filtrationValue: G => Double): FilteredSimplicialSetStream[G] =
    FilteredSimplicialSetStream(this, PartialFunction.fromFunction(filtrationValue))

  // ----- constructions (implementations in `Constructions.scala`)

  /** The product `X × Y`, degreewise `(X × Y)_n = X_n × Y_n`. */
  def product[GY](that: FiniteSimplicialSet[GY]): FiniteSimplicialSet[ProductGenerator[G, GY]] =
    Constructions.product(this, that)(using ord, that.ord)

  /** The coproduct (disjoint union) `X ⊔ Y`. */
  def coproduct[GY](that: FiniteSimplicialSet[GY]): FiniteSimplicialSet[Either[G, GY]] =
    Constructions.coproduct(this, that)(using ord, that.ord)

  /** The wedge `X ∨ Y` at vertex `at` of `X` and vertex `thatAt` of `that`. */
  def wedge[GY](at: G, that: FiniteSimplicialSet[GY], thatAt: GY): FiniteSimplicialSet[Either[G, GY]] =
    Constructions.wedge(this, at, that, thatAt)

  /** The smash product `X ∧ Y = (X × Y) / (X ∨ Y)` with base points `at` and `thatAt`. */
  def smash[GY](at: G, that: FiniteSimplicialSet[GY], thatAt: GY): FiniteSimplicialSet[ProductGenerator[G, GY]] =
    Constructions.smash(this, at, that, thatAt)

  /** The join `X ⋆ Y` (`S^0 ⋆ X` is the suspension, `pt ⋆ X` the cone, `S^p ⋆ S^q = S^(p+q+1)`). */
  def join[GY](that: FiniteSimplicialSet[GY]): FiniteSimplicialSet[JoinGenerator[G, GY]] =
    Constructions.join(this, that)

  /** The unreduced cone `CX` (contractible). */
  def cone: FiniteSimplicialSet[ConeGenerator[G]] = Constructions.cone(this)

  /** The unreduced suspension `SX`: two cones glued along `X`. */
  def suspension: FiniteSimplicialSet[Either[ConeGenerator[G], ConeGenerator[G]]] = Constructions.suspension(this)

  /** The quotient by `quotientMap`: each generator goes to its representative, a surviving generator (`SSetElement(Nil,
    * rep)`, a fixed point) or a degenerate collapse onto one. Must resolve in ONE step.
    */
  def quotient(quotientMap: G => SSetElement[G]): FiniteSimplicialSet[G] =
    Constructions.quotient(this, quotientMap)(using ord)

  /** Glue generators of equal dimension pairwise (transitively); each class keeps its `ord`-least member. */
  def identify(pairs: Seq[(G, G)]): FiniteSimplicialSet[G] = Constructions.identify(this, pairs)(using ord)

  /** The sub-simplicial set on `keep`, which must be closed under faces. */
  def subcomplex(keep: Set[G]): FiniteSimplicialSet[G] = Constructions.subcomplex(this, keep)

  // ----- validation

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
        lhs = face(i, face(j, SSetElement[G](Nil, g)))
        rhs = face(j - 1, face(i, SSetElement[G](Nil, g)))
        if lhs != rhs
      yield s"$g (dim $n): d_$i(d_$j g) = $lhs but d_${j - 1}(d_$i g) = $rhs (should agree, d_i d_j = d_{j-1} d_i)"

  def validate(): Seq[String] =
    generatorsByDim.zipWithIndex.flatMap { case (gs, n) =>
      gs.toSeq.flatMap(g => structuralErrors(g, n) ++ identityErrors(g, n))
    }
