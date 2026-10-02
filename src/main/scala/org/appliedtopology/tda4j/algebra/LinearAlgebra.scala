package org.appliedtopology.tda4j
package algebra

import scala.collection.mutable.ArrayBuffer

/** Small dense linear algebra over any [[Field]]: row reduction, rank and null space. Meant for the SMALL matrices that
  * come up when asking structural questions about a hand-sized complex (the rank of a map on homology, ...); the
  * persistence engines have their own sparse reduction and do not use this.
  */
object LinearAlgebra:

  /** Reduced row echelon form of `rows` (a list of rows, all of the same length), and the pivot column of each
    * nonzero row of the result.
    */
  def rref[F: Field as field](rows: Seq[Seq[F]]): (Vector[Vector[F]], Vector[Int]) =
    val m = ArrayBuffer.from(rows.map(r => ArrayBuffer.from(r)))
    val nCols = if m.isEmpty then 0 else m.head.length
    val pivots = ArrayBuffer[Int]()
    var r = 0
    var c = 0
    while r < m.length && c < nCols do
      val p = (r until m.length).find(i => !field.isEqual(m(i)(c), field.zero))
      p match
        case None    => c += 1
        case Some(i) =>
          val tmp = m(r); m(r) = m(i); m(i) = tmp
          val inv = field.invert(m(r)(c))
          for j <- 0 until nCols do m(r)(j) = field.times(m(r)(j), inv)
          for k <- m.indices if k != r do
            val factor = m(k)(c)
            if !field.isEqual(factor, field.zero) then
              for j <- 0 until nCols do m(k)(j) = field.minus(m(k)(j), field.times(factor, m(r)(j)))
          pivots += c
          r += 1
          c += 1
    (m.take(r).map(_.toVector).toVector, pivots.toVector)

  /** The rank of the matrix whose rows are `rows`. */
  def rank[F: Field](rows: Seq[Seq[F]]): Int = rref(rows)._2.length

  /** A basis of `{ x : A x = 0 }` for the matrix `A` given by `rows`, with `nCols` columns (needed because `rows` may be
    * empty); each basis vector has length `nCols`.
    */
  def nullspace[F: Field as field](rows: Seq[Seq[F]], nCols: Int): Vector[Vector[F]] =
    val (reduced, pivots) = rref(if rows.isEmpty then Seq(Seq.fill(nCols)(field.zero)) else rows)
    val pivotSet = pivots.toSet
    (0 until nCols).filterNot(pivotSet.contains).map { free =>
      val x = ArrayBuffer.fill(nCols)(field.zero)
      x(free) = field.one
      for (row, pivotColumn) <- reduced.zip(pivots) do x(pivotColumn) = field.negate(row(free))
      x.toVector
    }.toVector
