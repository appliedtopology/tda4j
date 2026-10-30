package org.appliedtopology.tda4j

/** Linear algebra over `Z/p` on plain `Int` rows, for the oracles of specs that must not share code with the library:
  * the rank and a nullspace basis of a matrix, row by row.
  */
object ZpLinearAlgebra:

  def rank(rows: Seq[Seq[Int]], p: Int): Int = reduced(rows, p).count(_.exists(_ != 0))

  /** Row echelon form over `Z/p`: pivots scaled to 1 and cleared above and below. */
  def reduced(rows: Seq[Seq[Int]], p: Int): Seq[Seq[Int]] =
    val m = rows.map(_.map(x => ((x % p) + p) % p).toArray).toArray
    def inverse(a: Int): Int = (1 until p).find(b => a * b % p == 1).get
    var r = 0
    val width = if m.isEmpty then 0 else m(0).length
    for c <- 0 until width if r < m.length do
      val pivot = (r until m.length).find(m(_)(c) != 0)
      pivot.foreach { k =>
        val tmp = m(r); m(r) = m(k); m(k) = tmp
        val inv = inverse(m(r)(c))
        for x <- 0 until width do m(r)(x) = m(r)(x) * inv % p
        for k2 <- m.indices if k2 != r && m(k2)(c) != 0 do
          val f = m(k2)(c)
          for x <- 0 until width do m(k2)(x) = (((m(k2)(x) - f * m(r)(x)) % p) + p) % p
        r += 1
      }
    m.toSeq.map(_.toSeq)

  /** A basis of the vectors `x` of length `width` with `a x = 0`. */
  def nullspace(a: Seq[Seq[Int]], width: Int, p: Int): Seq[Seq[Int]] =
    val rref = reduced(a, p).filter(_.exists(_ != 0))
    val pivots = rref.map(_.indexWhere(_ != 0))
    val free = (0 until width).filterNot(pivots.contains)
    free.map { f =>
      val x = Array.fill(width)(0)
      x(f) = 1
      for (row, pc) <- rref.zip(pivots) do x(pc) = ((p - row(f)) % p + p) % p
      x.toSeq
    }
end ZpLinearAlgebra
