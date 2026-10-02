package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

/** Steenrod squares `Sq^i : H^n(X; F_2) -> H^(n+i)(X; F_2)` of a finite simplicial set, from Steenrod's explicit cup-`i`
  * products on cochains:
  *
  * `(a ∪_i b)(σ) = Σ_{0 <= j_0 < ... < j_i <= n} a(σ[front]) · b(σ[back])`
  *
  * where, cutting `0..n` at `j_0 < ... < j_i` into consecutive blocks `[0,j_0], [j_0,j_1], ..., [j_i,n]`, the FRONT face keeps
  * the vertices of the even-numbered blocks and the BACK face those of the odd-numbered ones (neighbouring blocks share their
  * cut vertex). Signs are omitted, so this is valid over characteristic 2 only (enforced). `∪_0` is the ordinary cup
  * product, and `Sq^i x = x ∪_{n-i} x` for `x` of degree `n`. Faces on vertex subsets are composites of face maps, so this
  * works on simplicial SETS, not just complexes; a degenerate face contributes 0 (normalized cochains).
  *
  * Checked against Wu's formula `Sq^i(x^k) = C(k, i) x^(k+i)` on `B(Z/2)` (`SteenrodSpec`), which exercises `∪_i` for every `i`.
  */
object Steenrod:

  /** The face of the `n`-simplex `sigma` on the (ascending) vertex positions `vertices`: all other vertices removed,
    * highest position first, so each `d_k` still refers to a valid position.
    */
  def faceOnVertices[G](x: FiniteSimplicialSet[G], sigma: G, vertices: Seq[Int]): SSetElement[G] =
    val n = x.dimOf(sigma)
    val keep = vertices.toSet
    (n to 0 by -1).filterNot(keep.contains).foldLeft(SSetElement[G](Nil, sigma))((e, k) => x.dOp(k, e))

  private def requireCharacteristicTwo[F: Field as field]: Unit =
    require(field.isEqual(field.plus(field.one, field.one), field.zero), "Steenrod squares are implemented over F_2 only")

  /** `a ∪_i b` for a `p`-cochain `a` and a `q`-cochain `b`: a `(p + q - i)`-cochain. Characteristic 2 only. */
  def cupI[G, F: Field as field](
    x: FiniteSimplicialSet[G],
    i: Int,
    p: Int,
    q: Int,
    a: Map[G, F],
    b: Map[G, F]
  ): Map[G, F] =
    requireCharacteristicTwo[F]
    require(i >= 0 && i <= math.min(p, q), s"cup-$i of a $p-cochain and a $q-cochain is not defined")
    val n = p + q - i
    def value(c: Map[G, F], e: SSetElement[G]): F = if e.word.nonEmpty then field.zero else c.getOrElse(e.generator, field.zero)
    x.generatorsAt(n).flatMap { sigma =>
      val total = (0 to n).toList.combinations(i + 1).foldLeft(field.zero) { (acc, cuts) =>
        // block m = [cuts(m-1), cuts(m)] with cuts(-1) = 0, cuts(i+1) = n; even blocks go to the front face, odd ones to the back
        val bounds = 0 +: cuts :+ n
        val blocks = bounds.zip(bounds.tail).map((lo, hi) => (lo to hi).toList)
        val front = blocks.zipWithIndex.collect { case (blk, m) if m % 2 == 0 => blk }.flatten.distinct.sorted
        val back = blocks.zipWithIndex.collect { case (blk, m) if m % 2 == 1 => blk }.flatten.distinct.sorted
        if front.length != p + 1 || back.length != q + 1 then acc
        else
          val term = field.times(value(a, faceOnVertices(x, sigma, front)), value(b, faceOnVertices(x, sigma, back)))
          field.plus(acc, term)
      }
      if field.isEqual(total, field.zero) then None else Some(sigma -> total)
    }.toMap

  /** `Sq^i` of a degree-`degree` cocycle `c` (over `F_2`): `c ∪_{degree - i} c`, a `(degree + i)`-cochain. `Sq^0` is the
    * identity and `Sq^degree` is the cup square; `Sq^i = 0` for `i > degree`.
    */
  def sq[G, F: Field](x: FiniteSimplicialSet[G], i: Int, degree: Int, c: Map[G, F]): Map[G, F] =
    require(i >= 0, "i must be >= 0")
    if i > degree then Map.empty
    else if i == 0 then c
    else cupI(x, degree - i, degree, degree, c, c)
