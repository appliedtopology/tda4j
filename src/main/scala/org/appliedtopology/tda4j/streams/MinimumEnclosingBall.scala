package org.appliedtopology.tda4j

/** A closed ball in `R^d`: `center` and `squaredRadius`. [[MinimumEnclosingBall.apply]] computes the smallest one
  * containing a finite set of points.
  *
  * @param center
  *   the centre, one coordinate per dimension
  * @param squaredRadius
  *   the squared radius, at least the squared distance from `center` to every point it was computed for
  */
open class MinimumEnclosingBall(val center: Array[Double], val squaredRadius: Double):
  /** The radius, `sqrt(squaredRadius)`. */
  def radius: Double = math.sqrt(squaredRadius)

  /** The ambient dimension. */
  def dimension: Int = center.length

object MinimumEnclosingBall:
  /** The smallest ball containing every point of `points` (the rows of the cloud, all of one length), for any number of
    * points in any dimension. The Cech filtration value of a simplex is the radius of this ball over its vertices.
    *
    * The ball is the unique one whose centre lies in the convex hull of the points on its boundary. It is found by an
    * active-set method on the dual problem (maximise `sum l_i |p_i|^2 - |sum l_i p_i|^2` over convex weights `l`,
    * Wolfe's minimum-norm-point algorithm applied to the lifted points): keep a set of affinely independent points on
    * the boundary with the centre in their hull, repeatedly add the point farthest from the centre, and drop points
    * whose weight reaches zero. Each step solves a Gram system of at most `d + 1` points, so the cost is polynomial in
    * `d` and the number of points, also for the many boundary points of a high-dimensional cloud (a regular simplex in
    * dimension 20 takes milliseconds).
    *
    * Duplicate, collinear and cospherical points are handled: a point (numerically) in the affine hull of the current
    * boundary set replaces a boundary point instead of joining them. The result is deterministic (no randomization, a
    * fixed order of work), and the radius is exactly the largest distance from the returned centre to a point, so the
    * ball always contains its points; it exceeds the true minimum radius by no more than the rounding of the
    * computation (about `1e-13` relative) except on inputs so degenerate that the iteration stops early, where the ball
    * is still a valid enclosing ball. Rescaling the points by a power of two rescales `center` and `squaredRadius`
    * exactly.
    *
    * @throws IllegalArgumentException
    *   for an empty cloud, points of different lengths, or a non-finite coordinate
    */
  def apply(points: PointCloud): MinimumEnclosingBall =
    val cloud = points.points
    require(cloud.nonEmpty, "a minimum enclosing ball needs at least one point")
    val dimension = cloud(0).length
    require(cloud.forall(_.length == dimension), "all points must have the same number of coordinates")
    require(cloud.forall(_.forall(x => !x.isNaN && !x.isInfinite)), "all coordinates must be finite")
    Solver(cloud, dimension).solve()

  // Plain private: the working state of one solve.

  /** Two points count as affinely dependent when the new one's component outside the span of the earlier ones is below
    * this fraction of its squared length (the sine squared of its angle to that span).
    */
  private val DependenceTolerance = 1e-13

  /** A point is inside the current ball when its squared distance to the centre is at most `(1 + this)` times the
    * squared radius.
    */
  private val OutsideTolerance = 1e-13

  /** Barycentric coordinates of the centre that are negative by less than this are rounding noise. */
  private val NegativeTolerance = 1e-12

  /** A boundary point whose weight falls to this is dropped. */
  private val ZeroWeight = 1e-14

  private final class Solver(points: Array[Array[Double]], d: Int):
    private val n = points.length
    private val origin = points(0)
    // The points relative to the first one: the working coordinates of every distance and Gram entry below.
    private val v: Array[Array[Double]] = Array.tabulate(n)(i => Array.tabulate(d)(k => points(i)(k) - origin(k)))

    // The boundary set: `support(0 until m)` are indices of points, `weight` the convex weights of the current centre
    // `c = sum weight(i) * v(support(i))`. One slot more than the largest affinely independent set (`d + 1`), for the
    // point being added.
    private val cap = math.min(n, d + 2)
    private val support = new Array[Int](cap)
    private val weight = new Array[Double](cap)
    private var m = 0
    private val c = new Array[Double](d)
    private var squaredRadius = 0.0

    // Cholesky factor `l` of the Gram matrix of `w(j) = v(support(j)) - v(support(0))`, j = 1 until m; `w` and `l` are
    // indexed from 1.
    private val w = Array.ofDim[Double](cap, d)
    private val l = Array.ofDim[Double](cap, cap)
    private val mu = new Array[Double](cap) // barycentric coordinates of the circumcentre of the boundary set
    private val scratch = new Array[Double](cap)
    private val candidate = new Array[Double](d) // the circumcentre of the boundary set

    private def dot(a: Array[Double], b: Array[Double]): Double =
      var s = 0.0
      var k = 0
      while k < d do
        s += a(k) * b(k)
        k += 1
      s

    private def squaredDistanceToCentre(point: Array[Double], centre: Array[Double]): Double =
      var s = 0.0
      var k = 0
      while k < d do
        val x = point(k) - centre(k)
        s += x * x
        k += 1
      s

    private def isSupport(point: Int): Boolean =
      var i = 0
      while i < m && support(i) != point do i += 1
      i < m

    /** Factors the Gram matrix of the first `t` boundary points. Returns -1 when they are affinely independent, else
      * the index of the first one lying (numerically) in the affine hull of those before it.
      */
    private def factorize(t: Int): Int =
      val base = v(support(0))
      var j = 1
      while j < t do
        val vj = v(support(j))
        val wj = w(j)
        var k = 0
        while k < d do
          wj(k) = vj(k) - base(k)
          k += 1
        j += 1
      var bad = -1
      j = 1
      while j < t && bad < 0 do
        val lj = l(j)
        var i = 1
        while i < j do
          var s = dot(w(j), w(i))
          var q = 1
          while q < i do
            s -= lj(q) * l(i)(q)
            q += 1
          lj(i) = s / l(i)(i)
          i += 1
        val gjj = dot(w(j), w(j))
        var pivot = gjj
        var q = 1
        while q < j do
          pivot -= lj(q) * lj(q)
          q += 1
        if !(gjj > 0.0) || pivot <= DependenceTolerance * gjj then bad = j else lj(j) = math.sqrt(pivot)
        j += 1
      bad

    /** Solves `G x = b` for the Gram matrix of `w(1 until t)`, `b` and `x` in `rhs(1 until t)`. */
    private def solveGram(t: Int, rhs: Array[Double]): Unit =
      var j = 1
      while j < t do
        var s = rhs(j)
        var q = 1
        while q < j do
          s -= l(j)(q) * rhs(q)
          q += 1
        rhs(j) = s / l(j)(j)
        j += 1
      j = t - 1
      while j >= 1 do
        var s = rhs(j)
        var q = j + 1
        while q < t do
          s -= l(q)(j) * rhs(q)
          q += 1
        rhs(j) = s / l(j)(j)
        j -= 1

    /** The circumcentre of the first `t` boundary points inside their affine hull (after a successful `factorize(t)`):
      * `candidate`, with barycentric coordinates `mu`. The point `base + sum x_j w_j` is equidistant from them when
      * `2 G x = diag G`.
      */
    private def circumcentre(t: Int): Unit =
      var j = 1
      while j < t do
        scratch(j) = 0.5 * dot(w(j), w(j))
        j += 1
      solveGram(t, scratch)
      var sum = 0.0
      j = 1
      while j < t do
        mu(j) = scratch(j)
        sum += scratch(j)
        j += 1
      mu(0) = 1.0 - sum
      val base = v(support(0))
      var k = 0
      while k < d do
        var s = base(k)
        j = 1
        while j < t do
          s += mu(j) * w(j)(k)
          j += 1
        candidate(k) = s
        k += 1

    private def recomputeCentre(): Unit =
      var k = 0
      while k < d do
        var s = 0.0
        var i = 0
        while i < m do
          s += weight(i) * v(support(i))(k)
          i += 1
        c(k) = s
        k += 1

    /** Removes boundary point `index` and rescales the remaining weights to sum to one. */
    private def remove(index: Int): Unit =
      var i = index
      while i < m - 1 do
        support(i) = support(i + 1)
        weight(i) = weight(i + 1)
        i += 1
      m -= 1
      var sum = 0.0
      i = 0
      while i < m do
        sum += weight(i)
        i += 1
      i = 0
      if sum > 0.0 then
        while i < m do
          weight(i) /= sum
          i += 1
      else
        while i < m do
          weight(i) = if i == 0 then 1.0 else 0.0
          i += 1

    /** Removes every boundary point whose weight has reached zero, except `keep`. */
    private def removeZeroWeights(keep: Int): Unit =
      var i = m - 1
      while i >= 0 && m > 1 do
        if weight(i) <= ZeroWeight && support(i) != keep then remove(i)
        i -= 1

    private def accept(): Unit =
      var sum = 0.0
      var i = 0
      while i < m do
        weight(i) = math.max(mu(i), 0.0)
        sum += weight(i)
        i += 1
      i = 0
      while i < m do
        weight(i) /= sum
        i += 1
      var k = 0
      while k < d do
        c(k) = candidate(k)
        k += 1
      squaredRadius = 0.0
      i = 0
      while i < m do
        squaredRadius = math.max(squaredRadius, squaredDistanceToCentre(v(support(i)), c))
        i += 1

    /** The last boundary point `p` lies in the affine hull of the others, so no circumsphere has them all. Moves weight
      * from the others onto `p` along the affine dependency, which leaves the centre where it is and enlarges the dual
      * objective, until one of the others reaches weight zero and leaves. False when `p` cannot be added.
      */
    private def exchange(p: Int): Boolean =
      val t = m - 1
      var j = 1
      while j < t do
        scratch(j) = dot(w(j), w(t))
        j += 1
      solveGram(t, scratch) // scratch(1 until t): p - base = sum beta_j w_j
      var betaSum = 0.0
      j = 1
      while j < t do
        betaSum += scratch(j)
        j += 1
      // the dependency delta: p with weight +1, the others -beta, the base betaSum - 1
      var rate = 0.0
      var theta = Double.PositiveInfinity
      var leaving = -1
      var i = 0
      while i <= t do
        val delta = if i == t then 1.0 else if i == 0 then betaSum - 1.0 else -scratch(i)
        rate += delta * dot(v(support(i)), v(support(i)))
        if i < t && delta < 0.0 then
          val limit = weight(i) / -delta
          if limit < theta then
            theta = limit
            leaving = i
        i += 1
      if !(rate > 0.0) || leaving < 0 then false
      else
        i = 0
        while i <= t do
          val delta = if i == t then 1.0 else if i == 0 then betaSum - 1.0 else -scratch(i)
          weight(i) = if i == t then theta else math.max(weight(i) + theta * delta, 0.0)
          i += 1
        weight(leaving) = 0.0
        removeZeroWeights(p)
        recomputeCentre()
        true

    /** The boundary set has just gained the point `p` with weight 0. Moves the centre toward the circumcentre of the
      * new boundary set, dropping points whose weight reaches zero on the way, until the circumcentre lies in the hull
      * of its points. False when `p` cannot be added, or the iteration did not settle.
      */
    private def addPoint(p: Int): Boolean =
      var guard = 0
      val limit = 4 * cap + 8
      var done = false
      var ok = true
      while !done && ok do
        guard += 1
        if guard > limit then ok = false
        else
          val bad = factorize(m)
          if bad < 0 then
            circumcentre(m)
            var smallest = mu(0)
            var i = 1
            while i < m do
              if mu(i) < smallest then smallest = mu(i)
              i += 1
            if smallest >= -NegativeTolerance then
              accept()
              done = true
            else
              // walk from the current weights toward `mu` until the first weight reaches zero
              var theta = Double.PositiveInfinity
              var leaving = -1
              i = 0
              while i < m do
                if mu(i) < 0.0 then
                  val step = weight(i) / (weight(i) - mu(i))
                  if step < theta then
                    theta = step
                    leaving = i
                i += 1
              i = 0
              while i < m do
                weight(i) = math.max((1.0 - theta) * weight(i) + theta * mu(i), 0.0)
                i += 1
              weight(leaving) = 0.0
              removeZeroWeights(p)
              recomputeCentre()
          else if bad == m - 1 then ok = exchange(p)
          else
            // a point in the span of those before it (rounding): leave it out
            remove(bad)
            recomputeCentre()
      ok

    def solve(): MinimumEnclosingBall =
      support(0) = 0
      weight(0) = 1.0
      m = 1
      val maxRounds = 50 * (n + d) + 200
      var rounds = 0
      var running = true
      while running do
        var farthest = -1
        var excess = -1.0
        var i = 0
        while i < n do
          val e = squaredDistanceToCentre(v(i), c)
          if e > excess then
            excess = e
            farthest = i
          i += 1
        if !(excess > squaredRadius * (1.0 + OutsideTolerance)) || isSupport(farthest) || rounds >= maxRounds then
          running = false
        else
          rounds += 1
          support(m) = farthest
          weight(m) = 0.0
          m += 1
          if !addPoint(farthest) then running = false
      val centre = Array.tabulate(d)(k => origin(k) + c(k))
      var radius2 = 0.0
      var i = 0
      while i < n do
        radius2 = math.max(radius2, squaredDistanceToCentre(points(i), centre))
        i += 1
      new MinimumEnclosingBall(centre, radius2)
end MinimumEnclosingBall
