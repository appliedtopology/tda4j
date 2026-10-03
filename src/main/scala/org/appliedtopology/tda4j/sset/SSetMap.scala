package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

/** A simplicial map `f : X -> Y` between finite simplicial sets, given -- as everything here is -- on generators:
  * `onGenerators(g)` is the image of the non-degenerate simplex `g`, an arbitrary (possibly degenerate) element of `Y`
  * of the same dimension. A degenerate simplex `s_J g` goes to `s_J f(g)`, which is how `apply` extends it.
  *
  * Injectivity and surjectivity are properties of the map on SIMPLICES (the standard meaning). Sage's docs do not say
  * what its own `is_injective`/`is_surjective` test (`DESIGN-sage-simplicial-sets-comparison.md`), so no claim of
  * agreement.
  */
final class SSetMap[GX, GY](
  val source: FiniteSimplicialSet[GX],
  val target: FiniteSimplicialSet[GY],
  val onGenerators: GX => SSetElement[GY]
):

  /** `f` applied to an arbitrary element `s_J g` of the source: `s_J f(g)`, re-normalized. */
  def apply(e: SSetElement[GX]): SSetElement[GY] =
    val image = onGenerators(e.generator)
    SSetElement(e.word.foldRight(image.word)(insertOuter), image.generator)

  /** Everything that is wrong with this map; empty means it is a simplicial map: dimension-preserving, landing in
    * `target`, and commuting with every face map (`f(d_i g) = d_i f(g)`).
    */
  def validate(): Seq[String] =
    source.generatorsByDim.zipWithIndex.flatMap { (gens, n) =>
      gens.toSeq.flatMap { g =>
        val image = onGenerators(g)
        val known = target.generatorsByDim.exists(_.contains(image.generator))
        if !known then Seq(s"$g maps to ${image.generator}, which is not a generator of the target")
        else if target.dimOf(image.generator) + image.word.length != n then
          Seq(s"$g (dim $n) maps to an element of dimension ${target.dimOf(image.generator) + image.word.length}")
        else
          source.faces(g).zipWithIndex.collect {
            case (face, i) if apply(face) != target.face(i, image) =>
              s"$g (dim $n): f(d_$i g) = ${apply(face)} but d_$i f(g) = ${target.face(i, image)}"
          }
      }
    }

  /** The composite `this` then `next`. */
  def andThen[GZ](next: SSetMap[GY, GZ]): SSetMap[GX, GZ] =
    SSetMap(source, next.target, g => next(onGenerators(g)))

  /** The generators of `Y` that are hit as themselves (not degenerate): exactly the non-degenerate simplices of the
    * image, which is automatically closed under faces because `f` commutes with them.
    */
  private def hitGenerators: Set[GY] = source.generatorsByDim.flatten.flatMap { g =>
    val image = onGenerators(g)
    if image.word.isEmpty then Some(image.generator) else None
  }.toSet

  /** The image of `f`, as a sub-simplicial set of the target. */
  def image: FiniteSimplicialSet[GY] = target.subcomplex(hitGenerators)

  /** Every non-degenerate simplex of the target is `f` of a simplex of the source. */
  def isSurjective: Boolean = target.generatorsByDim.flatten.forall(hitGenerators.contains)

  /** `f` is one-to-one on simplices. Checked on every element of every dimension up to the larger top dimension of
    * source and target -- where a collision, if there is one, already shows (a collision among degenerate simplices
    * descends to one among the generators they come from).
    */
  def isInjective: Boolean =
    val top = math.max(source.generatorsByDim.length, target.generatorsByDim.length)
    (0 to top).forall { n =>
      val images = source.simplices(n).map(apply)
      images.distinct.length == images.length
    }

  def isBijective: Boolean = isInjective && isSurjective

  /** The induced map of normalized chain complexes on a generator: its image if that is a bare generator, else zero (a
    * degenerate simplex is zero in the normalized complex).
    */
  def chainMap[F: Field as field](g: GX): Seq[(GY, F)] =
    val image = onGenerators(g)
    if image.word.isEmpty then Seq((image.generator, field.one)) else Seq.empty

  /** The rank of `H_n(f; F)`: `dim(f(Z_n X) + B_n Y) - dim(B_n Y)`. Small complexes only (dense linear algebra). */
  def homologyRank[F: Field as field](n: Int): Int =
    given (GX is OrderedCell) = source.cellInstance
    given (GY is OrderedCell) = target.cellInstance
    val xs = source.generators(n).toVector.sorted(using source.ord)
    val ys = target.generators(n).toVector.sorted(using target.ord)
    if xs.isEmpty || ys.isEmpty then 0
    else
      val xIndex = xs.zipWithIndex.toMap
      val yIndex = ys.zipWithIndex.toMap
      val below = source.generators(n - 1).toVector.sorted(using source.ord)
      val belowIndex = below.zipWithIndex.toMap
      val boundaryX: Seq[Seq[F]] = below.map { b =>
        val row = scala.collection.mutable.ArrayBuffer.fill(xs.length)(field.zero)
        for x <- xs; (cell, coefficient) <- x.boundary[F] if cell == b do
          row(xIndex(x)) = field.plus(row(xIndex(x)), coefficient)
        row.toVector
      }
      val cycles = LinearAlgebra.nullspace(boundaryX, xs.length)
      val fCycles: Seq[Seq[F]] = cycles.map { z =>
        val out = scala.collection.mutable.ArrayBuffer.fill(ys.length)(field.zero)
        for x <- xs; (y, c) <- chainMap[F](x) do
          out(yIndex(y)) = field.plus(out(yIndex(y)), field.times(c, z(xIndex(x))))
        out.toVector
      }
      val above = target.generators(n + 1).toVector
      val boundariesY: Seq[Seq[F]] = above.map { a =>
        val out = scala.collection.mutable.ArrayBuffer.fill(ys.length)(field.zero)
        for (cell, c) <- a.boundary[F] do out(yIndex(cell)) = field.plus(out(yIndex(cell)), c)
        out.toVector
      }
      LinearAlgebra.rank(fCycles ++ boundariesY) - LinearAlgebra.rank(boundariesY)

object SSetMap:
  def identity[G](x: FiniteSimplicialSet[G]): SSetMap[G, G] = SSetMap(x, x, g => SSetElement(Nil, g))

  /** The inclusion of a sub-simplicial set (same generator type) into an ambient one. */
  def inclusion[G](sub: FiniteSimplicialSet[G], ambient: FiniteSimplicialSet[G]): SSetMap[G, G] =
    SSetMap(sub, ambient, g => SSetElement(Nil, g))

  /** The quotient map `X -> quotient(X, quotientMap)`. */
  def quotientMap[G](
    x: FiniteSimplicialSet[G],
    quotient: FiniteSimplicialSet[G],
    quotientMap: G => SSetElement[G]
  ): SSetMap[G, G] =
    SSetMap(x, quotient, quotientMap)

  /** The projection of a product onto its first factor. */
  def projectionFirst[GX, GY](
    product: FiniteSimplicialSet[ProductGenerator[GX, GY]],
    x: FiniteSimplicialSet[GX]
  ): SSetMap[ProductGenerator[GX, GY], GX] = SSetMap(product, x, _.x)

  /** The projection of a product onto its second factor. */
  def projectionSecond[GX, GY](
    product: FiniteSimplicialSet[ProductGenerator[GX, GY]],
    y: FiniteSimplicialSet[GY]
  ): SSetMap[ProductGenerator[GX, GY], GY] = SSetMap(product, y, _.y)

  /** The mapping cone of `f : X -> Y`: the cone on `X` glued onto `Y` along `f` (a pushout of `CX <- X -> Y`). */
  def mappingCone[GX, GY](f: SSetMap[GX, GY]): FiniteSimplicialSet[Either[ConeGenerator[GX], GY]] =
    given Ordering[GX] = f.source.ord
    given Ordering[GY] = f.target.ord
    val cx = f.source.cone
    val both = cx.coproduct(f.target)
    type G = Either[ConeGenerator[GX], GY]
    def collapse(g: G): SSetElement[G] = g match
      case Left(ConeGenerator.Base(x)) =>
        val image = f.onGenerators(x)
        SSetElement(image.word, Right(image.generator): G)
      case other => SSetElement(Nil, other)
    both.quotient(collapse)
