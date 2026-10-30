package org.appliedtopology.tda4j

import BruteForceComplexes.*

import org.specs2.mutable.Specification

/** The persistent Laplacian. Oracles: the dimension of its kernel is the persistent Betti number, which the engines
  * count from the barcode (with no Laplacian in it); at `s = t` it is the Hodge Laplacian; and its entries are the
  * Schur complement `D D^T - D R^T (R R^T)^-1 R D^T + A^T A` of the boundary matrices, solved here by Gaussian
  * elimination on matrices the test builds itself (the library goes through a singular value decomposition).
  */
class PersistentLaplacianSpec extends Specification:

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))

  private def entries(m: DenseMatrix): Seq[Seq[Double]] = m.toArray.toSeq.map(_.toSeq)

  private def explicit(items: (Double, Simplex[Int])*): StratifiedCellStream[Simplex[Int], Double] =
    val builder = ExplicitStreamBuilder[Int, Double]()
    items.foreach(builder += _)
    builder.result()

  private def close(a: Seq[Seq[Double]], b: Seq[Seq[Double]], tolerance: Double = 1e-9): Boolean =
    a.size == b.size && a
      .zip(b)
      .forall((x, y) => x.size == y.size && x.zip(y).forall((u, v) => math.abs(u - v) <= tolerance))

  "the persistent Laplacian" should {
    "keep the matrix of the pair of vertices and a new vertex: the edge from a to b and the one into the new vertex c" in {
      // K_s = {a, b}; K_t adds the vertex c and the edges ab, bc. The edge bc has a boundary outside K_s, so only ab
      // is a chain whose boundary lies in K_s: the Laplacian is that of the edge ab alone, not of both edges.
      val stream = explicit(
        (0.0, Simplex(0)),
        (0.0, Simplex(1)),
        (1.0, Simplex(2)),
        (1.0, Simplex(0, 1)),
        (1.0, Simplex(1, 2))
      )
      val laplacian = PersistentLaplacian(stream, dimension = 0, from = 0.0, to = 1.0)
      (entries(laplacian.matrix) must beEqualTo(Seq(Seq(1.0, -1.0), Seq(-1.0, 1.0)))) and
        (laplacian.bettiNumber must beEqualTo(1)) and
        (laplacian.eigenvalues.map(x => math.round(x * 1e9) / 1e9) must beEqualTo(Vector(0.0, 2.0))) and
        (laplacian.spectralGap.map(x => math.round(x * 1e9) / 1e9) must beSome(2.0))
    }

    "be the Hodge Laplacian when nothing enters between the two values: the hollow triangle has the eigenvalues 0, 3, 3" in {
      val triangle = explicit(
        (0.0, Simplex(0)),
        (0.0, Simplex(1)),
        (0.0, Simplex(2)),
        (0.0, Simplex(0, 1)),
        (0.0, Simplex(1, 2)),
        (0.0, Simplex(0, 2))
      )
      val laplacian = PersistentLaplacian(triangle, dimension = 1, from = 0.0, to = 0.0)
      (laplacian.eigenvalues.map(x => math.round(x * 1e9) / 1e9) must beEqualTo(Vector(0.0, 3.0, 3.0))) and
        (laplacian.bettiNumber must beEqualTo(1))
    }

    "see a loop die: the square's loop is there at its own value and gone when the diagonals come in" in {
      val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val rips = VietorisRips(EuclideanMetricSpace(square), maxDimension = 1)
      val diagonal = math.sqrt(2.0)
      (PersistentLaplacian(rips, 1, 1.0, 1.0).eigenvalues.map(x => math.round(x * 1e9) / 1e9) must beEqualTo(
        Vector(0.0, 2.0, 2.0, 4.0)
      )) and
        (PersistentLaplacian(rips, 1, 1.0, 1.0).bettiNumber must beEqualTo(1)) and
        (PersistentLaplacian(rips, 1, 1.0, diagonal).bettiNumber must beEqualTo(0)) and
        (PersistentLaplacian(rips, 0, 0.0, diagonal).bettiNumber must beEqualTo(1)) and
        (PersistentLaplacian(rips, 0, 0.0, 1.0).bettiNumber must beEqualTo(1))
    }

    "have a kernel as large as the persistent Betti number, which the barcode counts, for every pair of values" in {
      val failures = for
        seed <- 0L until 4L
        points = cloud(7, seed)
        rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 2, maxFiltrationValue = 100.0)
        bars = Persistence(rips, maxDimension = 2, characteristic = 32003).bars
        values = rips.iterator.map(rips.filtrationValue).toList.distinct.sorted
        k <- 0 to 2
        s <- values
        t <- values
        if s <= t
        expected = bars.count(b => b.dim == k && b.birth <= s && t < b.death)
        found = PersistentLaplacian(rips, k, s, t).bettiNumber
        if expected != found
      yield s"seed $seed, dimension $k, from $s to $t: the barcode says $expected, the kernel $found"
      failures.take(2) must beEmpty
    }

    "be the Schur complement of the boundary matrices, entry by entry, where the matrix to invert is invertible" in {
      var compared = 0
      val failures = for
        seed <- 0L until 8L
        points = cloud(6, seed + 20)
        stream = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
        // the brute-force complex, with the values the stream gives its simplices
        complex = rips(points, 3).map((simplex, _) => simplex -> stream.filtrationValue(simplex))
        values = complex.values.toList.distinct.sorted
        s <- values.filter(_ > 0.0)
        t <- values.filter(_ >= s)
        outcome = schurDisagreement(complex, 1, s, t, PersistentLaplacian(stream, 1, s, t))
        _ = if outcome.isDefined then compared += 1
        problem <- outcome.flatten
      yield s"seed $seed, from $s to $t: $problem"
      (failures.take(2) must beEmpty) and (compared must beGreaterThan(100))
    }
  }

  /** The library's matrix against the Schur complement computed from the boundary matrices of the brute-force complex:
    * none if the matrix to invert is singular, `Some(None)` if the two agree.
    */
  private def schurDisagreement(
    complex: Complex,
    k: Int,
    s: Double,
    t: Double,
    found: PersistentLaplacian.Result[Simplex[Int]]
  ): Option[Option[String]] =
    val cellsS = found.cells.toVector // the basis of the matrix, in the order the library gives it
    val later = ofDim(complex, k).filter(c => complex(c) > s && complex(c) <= t)
    val up = ofDim(complex, k + 1).filter(complex(_) <= t)
    val faces = ofDim(complex, k - 1).filter(complex(_) <= s)
    def toDouble(m: Seq[Seq[Int]]) = m.map(_.map(_.toDouble))
    val d = toDouble(matrixOf(cellsS, up)(boundary))
    val r = toDouble(matrixOf(later, up)(boundary))
    val a = toDouble(matrixOf(faces, cellsS)(boundary))
    import PlainMatrix.{product, transpose}
    val m = cellsS.size
    def zeros = Seq.fill(m)(Seq.fill(m)(0.0))
    val dd = if up.isEmpty then zeros else product(d, transpose(d))
    val down = if faces.isEmpty then zeros else product(transpose(a), a)
    val up_ : Option[Seq[Seq[Double]]] =
      if later.isEmpty || up.isEmpty then Some(dd)
      else
        val rt = transpose(r) // up x later
        // D R^T (R R^T)^-1 R D^T
        PlainMatrix.solve(product(r, rt), product(r, transpose(d))).map { y =>
          val correction = product(product(d, rt), y)
          dd.zip(correction).map((p, q) => p.zip(q).map(_ - _))
        }
    up_.map { laplacianUp =>
      val expected = laplacianUp.zip(down).map((p, q) => p.zip(q).map(_ + _))
      val actual = entries(found.matrix)
      if close(expected, actual, 1e-7) then None else Some(s"expected $expected, found $actual")
    }

end PersistentLaplacianSpec

/** Matrices as nested sequences, outside the specification so that its implicit conversions do not get in the way. */
private object PlainMatrix:
  def product(x: Seq[Seq[Double]], y: Seq[Seq[Double]]): Seq[Seq[Double]] =
    val columns = y.transpose
    x.map(row => columns.map(column => row.zip(column).map { case (p, q) => p * q }.sum))

  def transpose(x: Seq[Seq[Double]]): Seq[Seq[Double]] = if x.isEmpty then Seq.empty else x.transpose

  /** The solution of `a x = b` by Gaussian elimination with partial pivoting; none if `a` is singular. */
  def solve(a: Seq[Seq[Double]], b: Seq[Seq[Double]]): Option[Seq[Seq[Double]]] =
    val n = a.size
    val m = Array.tabulate(n)(i => (a(i) ++ b(i)).toArray)
    var singular = false
    for c <- 0 until n if !singular do
      val pivot = (c until n).maxBy(r => math.abs(m(r)(c)))
      if math.abs(m(pivot)(c)) < 1e-9 then singular = true
      else
        val tmp = m(c); m(c) = m(pivot); m(pivot) = tmp
        for r <- 0 until n if r != c do
          val f = m(r)(c) / m(c)(c)
          for j <- c until m(r).length do m(r)(j) -= f * m(c)(j)
    if singular then None else Some(Seq.tabulate(n)(i => m(i).drop(n).toSeq.map(_ / m(i)(i))))
