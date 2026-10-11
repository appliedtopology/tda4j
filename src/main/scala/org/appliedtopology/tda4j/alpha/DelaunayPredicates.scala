package org.appliedtopology.tda4j

import java.math.BigDecimal as BD

/** Exact geometric predicates for Delaunay triangulation in dimensions 2 to 4: the sign of an orientation determinant
  * and of an in-sphere determinant, each computed in floating point first and, when the result is too close to zero for
  * the floating-point error bound, again in exact arithmetic (`BigDecimal`: a double is a binary fraction, so sums and
  * products of doubles are exact there). In-sphere ties (a point exactly on a circumsphere) are broken by a symbolic
  * perturbation of the lifted coordinate that depends only on the points' indices, so the result is the Delaunay
  * triangulation of an infinitesimally perturbed point set: unique, whatever the insertion order.
  *
  * Determinants are expanded over permutations, which is cheap up to 5x5 (in-sphere in 4-D) and grows as n! beyond.
  */
open class DelaunayPredicates(points: Array[Array[Double]]):
  val dimension: Int = points.head.length
  require(dimension >= 1 && dimension <= 4, s"DelaunayPredicates: dimension $dimension is not supported (1 to 4)")

  private val u = math.ulp(1.0) / 2 // unit roundoff

  // Permutations of 0 until n with their signs, for n = dimension (orientation) and dimension + 1 (in-sphere).
  private def permutations(n: Int): (Array[Array[Int]], Array[Int]) =
    val perms = (0 until n).permutations.map(_.toArray).toArray
    val signs = perms.map { p =>
      var inversions = 0
      for i <- 0 until n; j <- i + 1 until n if p(i) > p(j) do inversions += 1
      if inversions % 2 == 0 then 1 else -1
    }
    (perms, signs)
  private val (orientPerms, orientSigns) = permutations(dimension)
  private val (spherePerms, sphereSigns) = permutations(dimension + 1)

  /** `(sign, |sign| > 0 trusted)`: the floating-point determinant and whether its sign exceeds the error bound.
    * `entryError` bounds the relative error of each entry (computed with roundoff), in units of `u`.
    */
  private def filteredDet(
    m: Array[Array[Double]],
    perms: Array[Array[Int]],
    signs: Array[Int],
    entryError: Double
  ): Int =
    val n = m.length
    var sum = 0.0
    var magnitude = 0.0
    var k = 0
    while k < perms.length do
      val p = perms(k)
      var prod = signs(k).toDouble
      var i = 0
      while i < n do
        prod *= m(i)(p(i))
        i += 1
      sum += prod
      magnitude += math.abs(prod)
      k += 1
    // Each term: n entries with relative error <= entryError u each, n - 1 roundings in the product; the sum of N
    // terms adds at most N roundings. Doubled for the second-order terms. Generous on purpose: a doubt costs only an
    // exact evaluation.
    val bound = 2 * (n * entryError + n + perms.length) * u * magnitude
    if sum > bound then 1 else if sum < -bound then -1 else 0

  private def exactDet(m: Array[Array[BD]], perms: Array[Array[Int]], signs: Array[Int]): Int =
    var sum = BD.ZERO
    var k = 0
    while k < perms.length do
      val p = perms(k)
      var prod = BD.valueOf(signs(k).toLong)
      var i = 0
      while i < m.length do
        prod = prod.multiply(m(i)(p(i)))
        i += 1
      sum = sum.add(prod)
      k += 1
    sum.signum

  private def exact(x: Double): BD = new BD(x) // the exact binary value

  private var _exactOrientations = 0L
  private var _exactSpheres = 0L
  private var _perturbedSpheres = 0L

  /** How often the floating-point filter was not enough: orientations and in-sphere tests decided exactly, and
    * in-sphere ties broken by the symbolic perturbation.
    */
  def exactOrientations: Long = _exactOrientations
  def exactSpheres: Long = _exactSpheres
  def perturbedSpheres: Long = _perturbedSpheres

  /** The sign of the orientation of the simplex `v(0), ..., v(dimension)`: the determinant of the rows `p(v(i)) -
    * p(v(0))`, `i >= 1`. Zero exactly when the points are affinely dependent.
    */
  def orientation(v: Array[Int]): Int =
    val d = dimension
    val p0 = points(v(0))
    val m = Array.tabulate(d, d)((i, j) => points(v(i + 1))(j) - p0(j))
    val s = filteredDet(m, orientPerms, orientSigns, 1)
    if s != 0 then s
    else
      _exactOrientations += 1
      val e0 = p0.map(exact)
      exactDet(Array.tabulate(d, d)((i, j) => exact(points(v(i + 1))(j)).subtract(e0(j))), orientPerms, orientSigns)

  // The translated in-sphere determinant: rows (p_j - q, |p_j - q|^2) over the d + 1 vertices of the cell. In exact
  // arithmetic it equals the homogeneous determinant with rows (p, |p|^2, 1) over the cell and then q.
  private def sphereRowsExact(cell: Array[Int], q: Int): Array[Array[BD]] =
    val d = dimension
    val eq = points(q).map(exact)
    Array.tabulate(d + 1) { i =>
      val diff = Array.tabulate(d)(j => exact(points(cell(i))(j)).subtract(eq(j)))
      val lift = diff.foldLeft(BD.ZERO)((s, x) => s.add(x.multiply(x)))
      diff :+ lift
    }

  /** The unperturbed sign of the translated in-sphere determinant (exact). */
  def sphereDeterminantSign(cell: Array[Int], q: Int): Int =
    val d = dimension
    val pq = points(q)
    val m = Array.tabulate(d + 1) { i =>
      val row = new Array[Double](d + 1)
      var lift = 0.0
      var j = 0
      while j < d do
        val x = points(cell(i))(j) - pq(j)
        row(j) = x
        lift += x * x
        j += 1
      row(d) = lift
      row
    }
    val s = filteredDet(m, spherePerms, sphereSigns, d + 2)
    if s != 0 then s
    else
      _exactSpheres += 1
      exactDet(sphereRowsExact(cell, q), spherePerms, sphereSigns)

  // The sign of the homogeneous determinant (rows (p, w, 1) over the cell, then q; w the lifted coordinate) when the
  // lifted coordinate of row r is perturbed: the cofactor of (r, lifted column), i.e. the determinant with the lifted
  // column replaced by the unit vector e_r. Rows are taken in descending point index, the first non-zero one decides.
  private def perturbedSign(cell: Array[Int], q: Int): Int =
    _perturbedSpheres += 1
    val d = dimension
    val rows = cell :+ q // d + 2 rows
    val coords = rows.map(i => points(i).map(exact))
    val order = rows.indices.sortBy(r => -rows(r))
    val n = d + 2
    val (perms, signs) = permutations(n)
    order.iterator
      .map { r =>
        val m = Array.tabulate(n, n) { (i, j) =>
          if j < d then coords(i)(j)
          else if j == d then if i == r then BD.ONE else BD.ZERO
          else BD.ONE
        }
        exactDet(m, perms, signs)
      }
      .find(_ != 0)
      .getOrElse(0)

  // Calibration: the sign the in-sphere determinant takes for a point strictly inside the circumsphere of a positively
  // oriented cell, measured once on the standard simplex and its centroid.
  // Lazy, and the helper instance only uses the uncalibrated predicates, so this never recurses.
  private lazy val insideSign: Int =
    val d = dimension
    val ref = Array.tabulate(d + 2)(i =>
      if i < d + 1 then Array.tabulate(d)(j => if i == j + 1 then 1.0 else 0.0) else Array.fill(d)(1.0 / (d + 1))
    )
    val check = new DelaunayPredicates(ref)
    val cell = (0 to d).toArray
    check.sphereDeterminantSign(cell, d + 1) * check.orientation(cell)

  /** Whether `q` lies inside the circumsphere of the positively oriented cell `cell` (indices into the points), with
    * exact ties broken by the perturbation: never undecided for distinct points. `1` inside, `-1` outside.
    */
  def inSphere(cell: Array[Int], q: Int): Int =
    val s = sphereDeterminantSign(cell, q)
    val raw = if s != 0 then s else perturbedSign(cell, q)
    raw * insideSign
