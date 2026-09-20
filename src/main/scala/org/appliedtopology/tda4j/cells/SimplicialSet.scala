package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

/** `OrderedCell` instance for the generators (non-degenerate simplices) of a finite simplicial set: `dim` is the
  * generator's own dimension, and `boundary` is the normalized-chain-complex differential -- only faces that are
  * themselves bare generators (`word.isEmpty`) contribute, with the usual alternating sign; a degenerate face
  * contributes nothing, since the normalized chain complex is quasi-isomorphic to the full one. This is the only place
  * degeneracy matters for homology -- computing it needs no recursive `faceOf`, only `faces(g)` itself.
  *
  * Mirrors `Simplex_is_OrderedCell`'s injectable-ordering pattern (`SimplexOrderedCell.scala`).
  */
def FiniteSimplicialSet_is_OrderedCell[G](using
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
class FiniteSimplicialSet[G](val ord: Ordering[G])(
  val generatorsByDim: IndexedSeq[Set[G]],
  val faces: G => IndexedSeq[SSetElement[G]]
):
  // Precomputed once: dim is hot (sort keys, pivot lookups), and this also gives dimOf a real error on an
  // unregistered generator instead of silently returning -1.
  private val dimByGenerator: Map[G, Int] =
    generatorsByDim.zipWithIndex.flatMap((gs, d) => gs.map(_ -> d)).toMap

  def dimOf(g: G): Int = dimByGenerator(g)

  given cellInstance: (G is OrderedCell) = FiniteSimplicialSet_is_OrderedCell(using ord)(dimOf, faces)

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
