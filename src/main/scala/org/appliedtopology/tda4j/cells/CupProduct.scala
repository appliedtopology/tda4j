package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

/** Cochains, the Alexander-Whitney cup product and cohomology classes of a finite simplicial set, over a [[Field]].
  *
  * A `p`-cochain is a `Map[G, F]` on the non-degenerate `p`-simplices (absent = 0; the normalized cochain complex, so a
  * degenerate simplex always evaluates to 0). For the cup product, `(a ∪ b)(σ) = a(front_p σ) · b(back_q σ)` with the
  * front face `d_{p+1} ∘ ... ∘ d_n σ` and the back face `d_0^p σ` (no sign). Linear algebra is dense: SMALL complexes.
  */
object CupProduct:

  /** The front `p`-face and back `(n-p)`-face of an `n`-simplex (as elements: either may be degenerate). */
  def alexanderWhitney[G](x: FiniteSimplicialSet[G], sigma: G, p: Int): (SSetElement[G], SSetElement[G]) =
    val n = x.dimOf(sigma)
    require(p >= 0 && p <= n, s"split degree $p outside 0..$n")
    val start = SSetElement[G](Nil, sigma)
    val front = (n until p by -1).foldLeft(start)((e, i) => x.dOp(i, e))
    val back = (0 until p).foldLeft(start)((e, _) => x.dOp(0, e))
    (front, back)

  private def value[G, F: Field as field](c: Map[G, F], e: SSetElement[G]): F =
    if e.word.nonEmpty then field.zero else c.getOrElse(e.generator, field.zero)

  /** The cup product of a `p`-cochain and a `q`-cochain, a `(p+q)`-cochain. */
  def cup[G, F: Field as field](
    x: FiniteSimplicialSet[G],
    p: Int,
    q: Int,
    a: Map[G, F],
    b: Map[G, F]
  ): Map[G, F] =
    x.generatorsAt(p + q)
      .flatMap { sigma =>
        val (front, back) = alexanderWhitney(x, sigma, p)
        val v = field.times(value(a, front), value(b, back))
        if field.isEqual(v, field.zero) then None else Some(sigma -> v)
      }
      .toMap

  private def gens[G](x: FiniteSimplicialSet[G], n: Int): Vector[G] = x.generatorsAt(n).toVector.sorted(using x.ord)

  private def toVector[G, F: Field as field](x: FiniteSimplicialSet[G], n: Int, c: Map[G, F]): Vector[F] =
    gens(x, n).map(g => c.getOrElse(g, field.zero))

  /** `∂_n` as a matrix with one row per `(n-1)`-simplex and one column per `n`-simplex. */
  private def boundaryRows[G, F: Field as field](x: FiniteSimplicialSet[G], n: Int): Vector[Vector[F]] =
    given (G is OrderedCell) = x.cellInstance
    val cols = gens(x, n)
    val rowsIndex = gens(x, n - 1).zipWithIndex.toMap
    val m = Array.fill(rowsIndex.size)(scala.collection.mutable.ArrayBuffer.fill(cols.length)(field.zero))
    for (c, j) <- cols.zipWithIndex; (cell, coeff) <- c.boundary[F] do
      m(rowsIndex(cell))(j) = field.plus(m(rowsIndex(cell))(j), coeff)
    m.map(_.toVector).toVector

  /** `B^n`: the coboundaries `δφ` of `(n-1)`-cochains, as vectors in the `n`-cochain space (rows of `∂_n`). */
  private def coboundaries[G, F: Field](x: FiniteSimplicialSet[G], n: Int): Vector[Vector[F]] =
    if n == 0 then Vector.empty else boundaryRows[G, F](x, n)

  /** `Z^n`: the `n`-cochains `φ` with `φ ∘ ∂_{n+1} = 0`. */
  private def cocycles[G, F: Field](x: FiniteSimplicialSet[G], n: Int): Vector[Vector[F]] =
    val size = gens(x, n).length
    val rows = gens(x, n + 1).indices.map(j => boundaryRows[G, F](x, n + 1).map(_(j)))
    LinearAlgebra.nullspace(rows, size)

  def isCocycle[G, F: Field as field](x: FiniteSimplicialSet[G], n: Int, c: Map[G, F]): Boolean =
    val v = toVector(x, n, c)
    boundaryRows[G, F](x, n + 1).forall(row => field.isEqual(dot(row, v), field.zero))

  private def dot[F: Field as field](u: Seq[F], v: Seq[F]): F =
    u.zip(v).foldLeft(field.zero)((acc, uv) => field.plus(acc, field.times(uv._1, uv._2)))

  /** Whether the `n`-cochain `c` is a coboundary (cohomologous to zero). */
  def isCoboundary[G, F: Field](x: FiniteSimplicialSet[G], n: Int, c: Map[G, F]): Boolean =
    val b = coboundaries[G, F](x, n)
    val v = toVector(x, n, c)
    LinearAlgebra.rank(b :+ v) == LinearAlgebra.rank(b)

  /** Cocycles whose classes form a basis of `H^n(X; F)`. */
  def cohomologyBasis[G, F: Field](x: FiniteSimplicialSet[G], n: Int): Vector[Map[G, F]] =
    val b = coboundaries[G, F](x, n)
    val gs = gens(x, n)
    var current = b
    var rank = LinearAlgebra.rank(b)
    val chosen = scala.collection.mutable.ArrayBuffer.empty[Vector[F]]
    for z <- cocycles[G, F](x, n) do
      val r = LinearAlgebra.rank(current :+ z)
      if r > rank then
        chosen += z
        current = current :+ z
        rank = r
    chosen.toVector.map(z => gs.zip(z).toMap)
