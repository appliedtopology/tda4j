package org.appliedtopology.tda4j

import java.math.BigDecimal as BD
import org.specs2.mutable

/** `DelaunayPredicates` against an independent exact computation (Laplace expansion over `BigDecimal`, a different
  * algorithm from the predicates' permutation loop), on adversarial inputs: points collinear, coplanar or cospherical
  * to within a few ulps, and far from the origin, where a floating-point filter with a wrong error bound returns a
  * confident wrong sign. Plus the properties the triangulation relies on: the tie-break is consistent (permuting a cell
  * changes orientation and in-sphere signs together), and of four cocircular points exactly one diagonal is Delaunay.
  */
class DelaunayPredicatesSpec extends mutable.Specification:

  def laplace(m: IndexedSeq[IndexedSeq[BD]]): BD =
    if m.size == 1 then m(0)(0)
    else
      m.indices.foldLeft(BD.ZERO) { (acc, j) =>
        val minor = m.tail.map(row => row.patch(j, Nil, 1))
        val term = m(0)(j).multiply(laplace(minor))
        if j % 2 == 0 then acc.add(term) else acc.subtract(term)
      }

  def exactOrientation(pts: Array[Array[Double]], v: Array[Int]): Int =
    val d = pts.head.length
    laplace(IndexedSeq.tabulate(d, d)((i, j) => new BD(pts(v(i + 1))(j)).subtract(new BD(pts(v(0))(j))))).signum

  def exactSphere(pts: Array[Array[Double]], cell: Array[Int], q: Int): Int =
    val d = pts.head.length
    laplace(IndexedSeq.tabulate(d + 1) { i =>
      val diff = IndexedSeq.tabulate(d)(j => new BD(pts(cell(i))(j)).subtract(new BD(pts(q)(j))))
      diff :+ diff.foldLeft(BD.ZERO)((s, x) => s.add(x.multiply(x)))
    }).signum

  def ulps(x: Double, k: Int): Double = (0 until math.abs(k)).foldLeft(x)((y, _) => if k > 0 then math.nextUp(y) else math.nextDown(y))

  /** Points on a random affine flat of dimension `flatDim` in `d` dimensions, then nudged by a few ulps, offset far. */
  def nearFlat(rng: scala.util.Random, d: Int, n: Int, flatDim: Int, offset: Double): Array[Array[Double]] =
    val base = Array.fill(d)(offset + rng.nextDouble())
    val dirs = Array.fill(flatDim)(Array.fill(d)(rng.nextInt(5) - 2.0))
    Array.fill(n) {
      val coeffs = Array.fill(flatDim)(rng.nextInt(7) - 3.0)
      Array.tabulate(d)(j => ulps(base(j) + dirs.indices.map(k => coeffs(k) * dirs(k)(j)).sum, rng.nextInt(5) - 2))
    }

  /** Distinct cube vertices (which lie exactly on one sphere), each nudged by up to one ulp, offset far. */
  def nearSphere(rng: scala.util.Random, d: Int, n: Int, offset: Double): Array[Array[Double]] =
    val cube = (0 until (1 << d)).map(m => Array.tabulate(d)(j => if (m >> j & 1) == 1 then 1.0 else -1.0))
    rng.shuffle(cube).take(n).map(_.map(x => ulps(offset + x, rng.nextInt(3) - 1))).toArray

  "orientation agrees with an exact computation on near-degenerate and offset points, in 2-D to 4-D" >> {
    val rng = new scala.util.Random(1)
    val bad = for
      d <- 2 to 4
      trial <- 0 until 300
      offset <- Seq(0.0, 1e3, 1e8)
      pts = nearFlat(rng, d, d + 1, d - 1, offset)
      p = DelaunayPredicates(pts)
      v = (0 to d).toArray
      if p.orientation(v) != exactOrientation(pts, v)
    yield (d, trial, offset)
    bad must beEmpty
  }

  "the unperturbed in-sphere sign agrees with an exact computation on near-cospherical and offset points" >> {
    val rng = new scala.util.Random(2)
    val bad = for
      d <- 2 to 4
      trial <- 0 until 300
      offset <- Seq(0.0, 1e3, 1e8)
      pts = nearSphere(rng, d, d + 2, offset)
      if exactOrientation(pts, (0 to d).toArray) != 0
      p = DelaunayPredicates(pts)
      if p.sphereDeterminantSign((0 to d).toArray, d + 1) != exactSphere(pts, (0 to d).toArray, d + 1)
    yield (d, trial, offset)
    bad must beEmpty
  }

  "in-sphere: the centroid is inside, a far point outside, in every dimension" >> {
    val checks = for d <- 2 to 4 yield
      val simplex = Array.tabulate(d + 1)(i => Array.tabulate(d)(j => if i == j + 1 then 1.0 else 0.0))
      val pts = simplex :+ Array.fill(d)(1.0 / (d + 1)) :+ Array.fill(d)(10.0)
      val p = DelaunayPredicates(pts)
      val cell = (0 to d).toArray
      val positive = if p.orientation(cell) > 0 then cell else cell.updated(0, 1).updated(1, 0)
      (p.inSphere(positive, d + 1), p.inSphere(positive, d + 2))
    checks must contain(allOf((1, -1), (1, -1), (1, -1)))
  }

  "the tie-break is consistent: orientation times in-sphere is invariant under permuting the cell, ties included" >> {
    val rng = new scala.util.Random(3)
    val bad = for
      d <- 2 to 4
      trial <- 0 until 100
      pts = nearSphere(rng, d, d + 2, if trial % 2 == 0 then 0.0 else 1e3).map(_.map(x => math.rint(x * 4) / 4))
      p = DelaunayPredicates(pts)
      cell = (0 to d).toArray
      if p.orientation(cell) != 0
      reference = p.orientation(cell) * p.inSphere(cell, d + 1)
      perm <- cell.toSeq.permutations.take(24)
      if p.orientation(perm.toArray) * p.inSphere(perm.toArray, d + 1) != reference
    yield (d, trial, perm)
    bad must beEmpty
  }

  "of four cocircular points, exactly one diagonal is Delaunay under the tie-break" >> {
    // The square and its rotations/reflections in index order; the four points are exactly cocircular.
    val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
    val results = for order <- (0 until 4).permutations.toSeq yield
      val pts = order.map(square).toArray
      val p = DelaunayPredicates(pts)
      def positive(t: Array[Int]) = if p.orientation(t) > 0 then t else Array(t(1), t(0), t(2))
      // Index of each corner in pts.
      val Seq(a, b, c, d) = (0 until 4).map(k => order.indexOf(k))
      val acDelaunay = p.inSphere(positive(Array(a, b, c)), d) < 0 && p.inSphere(positive(Array(a, c, d)), b) < 0
      val bdDelaunay = p.inSphere(positive(Array(a, b, d)), c) < 0 && p.inSphere(positive(Array(b, c, d)), a) < 0
      acDelaunay != bdDelaunay
    results.forall(identity) must beTrue
  }
