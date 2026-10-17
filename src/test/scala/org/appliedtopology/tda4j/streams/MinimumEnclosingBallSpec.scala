package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import scala.util.Random

/** `MinimumEnclosingBall` against closed forms (equilateral and thin triangles, regular simplices up to dimension 20,
  * hypercubes, cross-polytopes, antipodal pairs on a sphere), against a brute-force search over supporting subsets, and
  * for the properties every answer must have: every point inside, the radius between the Jung bounds, the same answer
  * on every call and on a rescaling by a power of two.
  */
class MinimumEnclosingBallSpec extends Specification:
  sequential

  private type Pt = Array[Double]

  private def dist(a: Pt, b: Pt): Double =
    var s = 0.0
    var i = 0
    while i < a.length do
      val d = a(i) - b(i)
      s += d * d
      i += 1
    math.sqrt(s)

  private def ball(points: Pt*): MinimumEnclosingBall = MinimumEnclosingBall(points.toArray)

  private def relative(actual: Double, expected: Double): Double =
    math.abs(actual - expected) / math.max(math.abs(expected), 1e-300)

  private def centerError(actual: Pt, expected: Pt): Double =
    dist(actual, expected)

  private def diameter(pts: Seq[Pt]): Double =
    (for a <- pts; b <- pts yield dist(a, b)).max

  /** The circumsphere of `t` inside its own affine hull, by Gaussian elimination with partial pivoting on the
    * barycentric coordinates of the centre, a different route from the library's. `None` when `t` is affinely
    * dependent.
    */
  private def circumsphere(t: IndexedSeq[Pt]): Option[(Pt, Double)] =
    val m = t.length
    if m == 1 then Some((t.head.clone(), 0.0))
    else
      def dot(a: Pt, b: Pt) = a.indices.map(i => a(i) * b(i)).sum
      val a = Array.ofDim[Double](m, m + 1)
      for j <- 0 until m do a(0)(j) = 1.0
      a(0)(m) = 1.0
      for i <- 1 until m do
        val diff = t(i).indices.map(k => t(i)(k) - t.head(k)).toArray
        for j <- 0 until m do a(i)(j) = 2.0 * dot(t(j), diff)
        a(i)(m) = dot(t(i), t(i)) - dot(t.head, t.head)
      var singular = false
      var col = 0
      while col < m && !singular do
        val pivotRow = (col until m).maxBy(r => math.abs(a(r)(col)))
        if math.abs(a(pivotRow)(col)) < 1e-10 then singular = true
        else
          val swap = a(col); a(col) = a(pivotRow); a(pivotRow) = swap
          for r <- col + 1 until m do
            val f = a(r)(col) / a(col)(col)
            for k <- col to m do a(r)(k) -= f * a(col)(k)
          col += 1
      if singular then None
      else
        val mu = Array.ofDim[Double](m)
        for i <- m - 1 to 0 by -1 do mu(i) = (a(i)(m) - (i + 1 until m).map(j => a(i)(j) * mu(j)).sum) / a(i)(i)
        val c = Array.tabulate(t.head.length)(k => (0 until m).map(j => mu(j) * t(j)(k)).sum)
        Some((c, dist(c, t.head)))

  /** The smallest ball containing `pts`: the smallest circumsphere of a subset of at most `d + 1` points that contains
    * every point. (The minimum enclosing ball is one of them, and every other one is at least as large.)
    */
  private def bruteForceRadius(pts: IndexedSeq[Pt]): Double =
    val d = pts.head.length
    val n = pts.length
    var best = Double.PositiveInfinity
    var mask = 1
    while mask < (1 << n) do
      if Integer.bitCount(mask) <= d + 1 then
        val subset = (0 until n).filter(i => ((mask >> i) & 1) == 1).map(pts)
        circumsphere(subset).foreach { (c, r) =>
          if r < best && pts.forall(p => dist(p, c) <= r * (1 + 1e-9) + 1e-12) then best = r
        }
      mask += 1
    best

  private def uniform(rng: Random, n: Int, d: Int): IndexedSeq[Pt] =
    IndexedSeq.fill(n)(Array.fill(d)(2.0 * rng.nextDouble() - 1.0))

  /** Integer coordinates in `{0, 1, 2}`: duplicates, collinear and cospherical subsets turn up all the time. */
  private def lattice(rng: Random, n: Int, d: Int): IndexedSeq[Pt] =
    IndexedSeq.fill(n)(Array.fill(d)(rng.nextInt(3).toDouble))

  /** Points in a random plane of `R^d`: every subset of three or more is affinely dependent in a tilted way. */
  private def planar(rng: Random, n: Int, d: Int): IndexedSeq[Pt] =
    val u = Array.fill(d)(rng.nextGaussian())
    val v = Array.fill(d)(rng.nextGaussian())
    IndexedSeq.fill(n) {
      val (a, b) = (2.0 * rng.nextDouble() - 1.0, 2.0 * rng.nextDouble() - 1.0)
      Array.tabulate(d)(k => a * u(k) + b * v(k))
    }

  /** Points with many exact repeats of a few sites. */
  private def repeated(rng: Random, n: Int, d: Int): IndexedSeq[Pt] =
    val sites = uniform(rng, math.max(1, n / 3), d)
    IndexedSeq.fill(n)(sites(rng.nextInt(sites.length)).clone())

  private val generators: Seq[(String, (Random, Int, Int) => IndexedSeq[Pt])] =
    Seq(("uniform", uniform), ("lattice", lattice), ("planar", planar), ("repeated", repeated))

  "A single point is its own ball of radius 0" >> {
    val b = ball(Array(1.5, -2.0, 7.0))
    (b.center.toSeq must be_==(Seq(1.5, -2.0, 7.0))) and (b.squaredRadius must be_==(0.0)) and
      (b.radius must be_==(0.0))
  }

  "Two points: the ball on the segment as diameter, in every dimension up to 20" >> {
    val rng = Random(2)
    val errors = for
      d <- 1 to 20
      _ <- 1 to 5
      pair = uniform(rng, 2, d)
      p = pair(0)
      q = pair(1)
      b = ball(p, q)
      mid = Array.tabulate(d)(k => (p(k) + q(k)) / 2)
      e = math.max(relative(b.radius, dist(p, q) / 2), centerError(b.center, mid))
      if e > 1e-14
    yield (d, e)
    errors must beEmpty
  }

  "An equilateral triangle: the circumradius s / sqrt 3, not the half side" >> {
    val s = 1.0
    val b = ball(Array(0.0, 0.0), Array(s, 0.0), Array(s / 2, s * math.sqrt(3) / 2))
    (relative(b.radius, s / math.sqrt(3)) must be_<(1e-14)) and
      (centerError(b.center, Array(s / 2, s * math.sqrt(3) / 6)) must be_<(1e-14))
  }

  "A right triangle: half the hypotenuse; an obtuse one: half the longest side, less than the circumradius" >> {
    val right = ball(Array(0.0, 0.0), Array(3.0, 0.0), Array(0.0, 4.0))
    val obtuse = ball(Array(0.0, 0.0), Array(10.0, 0.0), Array(5.0, 0.5))
    (relative(right.radius, 2.5) must be_<(1e-14)) and (centerError(right.center, Array(1.5, 2.0)) must be_<(1e-14)) and
      (relative(obtuse.radius, 5.0) must be_<(1e-14)) and
      (centerError(obtuse.center, Array(5.0, 0.0)) must be_<(1e-14))
  }

  "A thin acute triangle: the circumcircle, which the farthest pair alone would miss" >> {
    // Apex at the origin, base of half-width e at x = 1: acute for every e > 0, circumcentre ((1 + e^2) / 2, 0).
    val errors = for
      e <- Seq(1e-1, 1e-2, 1e-3, 1e-4)
      b = ball(Array(0.0, 0.0), Array(1.0, e), Array(1.0, -e))
      expected = (1 + e * e) / 2
      err = math.max(relative(b.radius, expected), centerError(b.center, Array(expected, 0.0)))
      if err > 1e-13
    yield (e, err)
    errors must beEmpty
  }

  "A nearly collinear obtuse triangle: the ball on the long side as diameter" >> {
    val b = ball(Array(0.0, 0.0), Array(1.0, 1e-8), Array(2.0, 0.0))
    (relative(b.radius, 1.0) must be_<(1e-13)) and (centerError(b.center, Array(1.0, 0.0)) must be_<(1e-13))
  }

  "Regular simplices in dimensions 1 to 20: circumradius s sqrt(d / (d + 1)), centre at the centroid" >> {
    // The d + 1 scaled unit vectors of R^(d+1): all pairwise distances s sqrt 2, every vertex on the boundary.
    val s = 3.7
    val errors = for
      d <- 1 to 20
      vertices = Array.tabulate(d + 1)(i => Array.tabulate(d + 1)(k => if k == i then s else 0.0))
      b = MinimumEnclosingBall(vertices)
      expectedRadius = s * math.sqrt(d.toDouble / (d + 1))
      err = math.max(
        relative(b.radius, expectedRadius),
        centerError(b.center, Array.fill(d + 1)(s / (d + 1))) / expectedRadius
      )
      if err > 1e-13
    yield (d, err)
    errors must beEmpty
  }

  "Redundant points change nothing: a regular simplex with its centroid, edge midpoints and repeated vertices" >> {
    val d = 6
    val s = 2.0
    val vertices = IndexedSeq.tabulate(d + 1)(i => Array.tabulate(d + 1)(k => if k == i then s else 0.0))
    val centroid = Array.fill(d + 1)(s / (d + 1))
    val midpoints = for i <- 0 to d; j <- i + 1 to d
    yield Array.tabulate(d + 1)(k => (vertices(i)(k) + vertices(j)(k)) / 2)
    val everything = (vertices ++ midpoints ++ Seq(centroid) ++ vertices.reverse).toArray
    val b = MinimumEnclosingBall(everything)
    relative(b.radius, s * math.sqrt(d.toDouble / (d + 1))) must be_<(1e-13)
  }

  "Collinear points: half the span, centred between the extreme two, in any order and any direction" >> {
    val rng = Random(4)
    val d = 5
    val errors = for
      _ <- 1 to 40
      direction = Array.fill(d)(rng.nextGaussian())
      base = Array.fill(d)(rng.nextGaussian())
      params = Seq.fill(12)(10.0 * rng.nextDouble() - 5.0)
      pts = params.map(t => Array.tabulate(d)(k => base(k) + t * direction(k)))
      b = MinimumEnclosingBall(rng.shuffle(pts).toArray)
      norm = math.sqrt(direction.map(x => x * x).sum)
      expectedRadius = (params.max - params.min) / 2 * norm
      expectedCenter = Array.tabulate(d)(k => base(k) + (params.max + params.min) / 2 * direction(k))
      err = math.max(relative(b.radius, expectedRadius), centerError(b.center, expectedCenter) / expectedRadius)
      if err > 1e-13
    yield err
    val fixed = ball(Array(0.0, 0.0), Array(1.0, 0.0), Array(3.0, 0.0))
    (errors must beEmpty) and (relative(fixed.radius, 1.5) must be_<(1e-14)) and
      (centerError(fixed.center, Array(1.5, 0.0)) must be_<(1e-14))
  }

  "Repeated points: a point repeated, a segment's endpoints repeated, a triangle with repeated vertices" >> {
    val p = Array(1.0, 2.0)
    val q = Array(4.0, 6.0)
    val r = Array(1.0, 6.0)
    val same = ball(p, p.clone(), p.clone())
    val segment = ball(p, q, p.clone(), q.clone(), q.clone())
    val triangle = ball(p, q, r, p.clone(), r.clone(), q.clone())
    (same.squaredRadius must be_==(0.0)) and (same.center.toSeq must be_==(p.toSeq)) and
      (relative(segment.radius, 2.5) must be_<(1e-14)) and (centerError(segment.center, Array(2.5, 4.0)) must be_<(
        1e-14
      )) and
      (relative(triangle.radius, 2.5) must be_<(1e-14)) and (centerError(triangle.center, Array(2.5, 4.0)) must be_<(
        1e-14
      ))
  }

  "Cospherical points: hypercube vertices, cross-polytope vertices, regular polygons, antipodal pairs on a sphere" >> {
    val rng = Random(6)
    val cubes = for
      d <- 1 to 5
      vertices = (0 until (1 << d)).map(i => Array.tabulate(d)(k => ((i >> k) & 1).toDouble))
      b = MinimumEnclosingBall(vertices.toArray)
      err = math.max(relative(b.radius, math.sqrt(d) / 2), centerError(b.center, Array.fill(d)(0.5)))
      if err > 1e-13
    yield s"cube $d: $err"
    val crosses = for
      d <- 1 to 10
      vertices = (0 until d).flatMap(i =>
        Seq(1.0, -1.0).map(sign => Array.tabulate(d)(k => if k == i then sign else 0.0))
      )
      b = MinimumEnclosingBall(vertices.toArray)
      err = math.max(relative(b.radius, 1.0), centerError(b.center, Array.fill(d)(0.0)))
      if err > 1e-13
    yield s"cross-polytope $d: $err"
    val polygons = for
      m <- Seq(3, 4, 5, 7, 12, 30)
      vertices = (0 until m).map(i =>
        Array(3.0 + 2.0 * math.cos(2 * math.Pi * i / m), -1.0 + 2.0 * math.sin(2 * math.Pi * i / m))
      )
      b = MinimumEnclosingBall(vertices.toArray)
      err = math.max(relative(b.radius, 2.0), centerError(b.center, Array(3.0, -1.0)))
      if err > 1e-13
    yield s"polygon $m: $err"
    val spheres = for
      d <- Seq(2, 3, 6, 10)
      centre = Array.fill(d)(rng.nextGaussian())
      radius = 2.5
      directions = IndexedSeq.fill(d + 6) {
        val g = Array.fill(d)(rng.nextGaussian())
        val norm = math.sqrt(g.map(x => x * x).sum)
        g.map(_ / norm)
      }
      pts = directions.flatMap(u =>
        Seq(1.0, -1.0).map(sign => Array.tabulate(d)(k => centre(k) + sign * radius * u(k)))
      )
      b = MinimumEnclosingBall(rng.shuffle(pts).toArray)
      err = math.max(relative(b.radius, radius), centerError(b.center, centre) / radius)
      if err > 1e-13
    yield s"antipodal pairs in R^$d: $err"
    (cubes ++ crosses ++ polygons ++ spheres) must beEmpty
  }

  "Every point is inside the ball, and the radius lies between the Jung bounds, on degenerate and generic clouds" >> {
    val rng = Random(8)
    val failures = for
      (name, generator) <- generators
      _ <- 1 to 150
      n = 1 + rng.nextInt(30)
      d = 1 + rng.nextInt(20)
      pts = generator(rng, n, d)
      b = MinimumEnclosingBall(pts.toArray)
      worst = pts.map(p => dist(p, b.center)).max
      diam = diameter(pts)
      jung = diam * math.sqrt(d.toDouble / (2.0 * (d + 1)))
      outside = worst > b.radius * (1 + 1e-12)
      tooSmall = b.radius < diam / 2 * (1 - 1e-12)
      tooLarge = b.radius > jung * (1 + 1e-12) + 1e-300
      selfConsistent = relative(b.radius * b.radius, b.squaredRadius) > 1e-14
      if outside || tooSmall || tooLarge || selfConsistent
    yield s"$name n=$n d=$d: worst ${worst} radius ${b.radius} diameter $diam jung $jung"
    failures.take(5) must beEmpty
  }

  "No smaller ball exists: the radius equals the brute-force minimum over supporting subsets" >> {
    val rng = Random(10)
    val failures = for
      (name, generator) <- generators
      _ <- 1 to 120
      n = 1 + rng.nextInt(10)
      d = 1 + rng.nextInt(5)
      pts = generator(rng, n, d)
      b = MinimumEnclosingBall(pts.toArray)
      expected = bruteForceRadius(pts)
      if relative(b.radius, expected) > 1e-9 && math.abs(b.radius - expected) > 1e-12
    yield s"$name n=$n d=$d: got ${b.radius}, brute force $expected"
    failures.take(5) must beEmpty
  }

  "The same input gives bit-identical answers on every call, from a copy of the input" >> {
    val rng = Random(12)
    val failures = for
      (name, generator) <- generators
      _ <- 1 to 40
      pts = generator(rng, 1 + rng.nextInt(20), 1 + rng.nextInt(10)).toArray
      a = MinimumEnclosingBall(pts)
      b = MinimumEnclosingBall(pts.map(_.clone()))
      c = MinimumEnclosingBall(pts)
      if !(a.center.sameElements(b.center) && a.center.sameElements(c.center) &&
        a.squaredRadius == b.squaredRadius && a.squaredRadius == c.squaredRadius)
    yield name
    failures must beEmpty
  }

  "Rescaling by a power of two rescales the answer exactly: the result depends on shape, not on units" >> {
    val rng = Random(14)
    val failures = for
      (name, generator) <- generators
      _ <- 1 to 25
      pts = generator(rng, 1 + rng.nextInt(15), 1 + rng.nextInt(8))
      shift <- Seq(-30, 20)
      scale = math.pow(2.0, shift)
      base = MinimumEnclosingBall(pts.toArray)
      scaled = MinimumEnclosingBall(pts.map(_.map(_ * scale)).toArray)
      if scaled.radius != base.radius * scale || !scaled.center.sameElements(base.center.map(_ * scale))
    yield s"$name shift $shift"
    failures.take(5) must beEmpty
  }

  "The order of the points does not change the answer beyond rounding" >> {
    val rng = Random(16)
    val failures = for
      (name, generator) <- generators
      _ <- 1 to 40
      pts = generator(rng, 2 + rng.nextInt(15), 1 + rng.nextInt(8))
      a = MinimumEnclosingBall(pts.toArray)
      b = MinimumEnclosingBall(rng.shuffle(pts).toArray)
      if relative(a.radius, b.radius) > 1e-12 && math.abs(a.radius - b.radius) > 1e-13
    yield s"$name: ${a.radius} vs ${b.radius}"
    failures.take(5) must beEmpty
  }

  "Translating the points moves the centre and keeps the radius" >> {
    val rng = Random(18)
    val failures = for
      _ <- 1 to 60
      d = 1 + rng.nextInt(6)
      pts = uniform(rng, 2 + rng.nextInt(10), d)
      offset = Array.fill(d)(10.0 * rng.nextGaussian())
      a = MinimumEnclosingBall(pts.toArray)
      b = MinimumEnclosingBall(pts.map(p => Array.tabulate(d)(k => p(k) + offset(k))).toArray)
      if relative(a.radius, b.radius) > 1e-12 ||
        centerError(b.center, Array.tabulate(d)(k => a.center(k) + offset(k))) > 1e-12
    yield d
    failures must beEmpty
  }

  "Points with no coordinates are all the same point" >> {
    val b = MinimumEnclosingBall(Array(Array.empty[Double], Array.empty[Double]))
    (b.center.length must be_==(0)) and (b.squaredRadius must be_==(0.0))
  }

  "An empty cloud and points of different dimensions are rejected" >> {
    (MinimumEnclosingBall(Array.empty[Array[Double]]) must throwAn[IllegalArgumentException]) and
      (MinimumEnclosingBall(Array(Array(0.0, 0.0), Array(1.0))) must throwAn[IllegalArgumentException])
  }

  "A thirty-point cloud in dimension 20 and a 21-vertex regular simplex are solved in milliseconds" >> {
    val rng = Random(20)
    val clouds = Seq.fill(40)(uniform(rng, 30, 20).toArray)
    val simplex = Array.tabulate(21)(i => Array.tabulate(21)(k => if k == i then 1.0 else 0.0))
    val start = System.nanoTime()
    clouds.foreach(MinimumEnclosingBall(_))
    for _ <- 1 to 40 do MinimumEnclosingBall(simplex)
    val seconds = (System.nanoTime() - start) / 1e9
    seconds must be_<(10.0)
  }
end MinimumEnclosingBallSpec
