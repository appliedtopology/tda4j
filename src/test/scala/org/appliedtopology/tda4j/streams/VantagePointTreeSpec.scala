package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import scala.util.Random

/** `VantagePointTree` against a linear scan, on Euclidean and on metrics that are not (Hamming, Levenshtein, circular,
  * ultrametric, discrete), with many exact ties and with queries whose radius is exactly a distance in the data.
  *
  * The linear scan IS the specification: `withinDistance` is `dist <= radius` (inclusive), `nearest(k)` is the first
  * `k` of the stable sort of the elements by distance, so the tie-break is the construction order. A tree that returns
  * the right distances but a different tie order, or drops a point lying exactly at the radius, fails here.
  */
class VantagePointTreeSpec extends Specification:
  sequential

  private type Pt = Array[Double]

  private def euclid(a: Pt, b: Pt): Double =
    var s = 0.0
    var i = 0
    while i < a.length do
      val d = a(i) - b(i)
      s += d * d
      i += 1
    math.sqrt(s)
  private def manhattan(a: Pt, b: Pt): Double = a.indices.map(i => math.abs(a(i) - b(i))).sum
  private def chebyshev(a: Pt, b: Pt): Double = a.indices.map(i => math.abs(a(i) - b(i))).max

  private def hamming(a: String, b: String): Double = a.indices.count(i => a.charAt(i) != b.charAt(i)).toDouble

  private def levenshtein(a: String, b: String): Double =
    val prev = Array.tabulate(b.length + 1)(identity)
    for i <- 1 to a.length do
      var diagonal = prev(0)
      prev(0) = i
      for j <- 1 to b.length do
        val above = prev(j)
        prev(j) = math.min(
          math.min(prev(j) + 1, prev(j - 1) + 1),
          diagonal + (if a.charAt(i - 1) == b.charAt(j - 1) then 0 else 1)
        )
        diagonal = above
    prev(b.length).toDouble

  /** 2-adic ultrametric: 0 for equal numbers, else `2^-v` where `v` is the 2-adic valuation of the difference. */
  private def ultrametric(a: Int, b: Int): Double =
    if a == b then 0.0
    else
      val diff = math.abs(a - b)
      val v = Integer.numberOfTrailingZeros(diff)
      1.0 / (1 << v).toDouble

  /** Every disagreement between the tree and the linear scan, as a readable line. */
  private def disagreements[V](
    elems: IndexedSeq[V],
    dist: (V, V) => Double,
    queries: Seq[V],
    radii: Seq[Double],
    ks: Seq[Int],
    leafSize: Int
  ): Seq[String] =
    val tree = VantagePointTree(elems, dist, leafSize)
    queries.zipWithIndex.flatMap { (q, qi) =>
      val within = radii.flatMap { eps =>
        val expected = elems.indices.filter(i => dist(q, elems(i)) <= eps)
        val got = tree.indicesWithinDistance(q, eps)
        if got == expected then None
        else Some(s"leaf $leafSize query $qi radius $eps: expected $expected, got $got")
      }
      val nearest = ks.flatMap { k =>
        val expected = elems.indices.sortBy(i => dist(q, elems(i))).take(k)
        val got = tree.nearestIndices(q, k)
        if got == expected then None
        else Some(s"leaf $leafSize query $qi k $k: expected $expected, got $got")
      }
      within ++ nearest
    }

  private def randomPoints(rng: Random, n: Int, d: Int): IndexedSeq[Pt] =
    IndexedSeq.fill(n)(Array.fill(d)(rng.nextDouble()))

  "On random Euclidean points the tree agrees with a linear scan for every leaf size" >> {
    val rng = Random(20261017)
    val problems = for
      n <- Seq(1, 2, 3, 7, 50, 200)
      d <- Seq(1, 2, 3, 8)
      leaf <- Seq(1, 2, 5, 16)
      elems = randomPoints(rng, n, d)
      queries = randomPoints(rng, 8, d) ++ Seq(elems.head, elems(n / 2), elems.last)
      problem <- disagreements(
        elems,
        euclid,
        queries,
        Seq(0.0, 0.05, 0.2, 0.5, 1.0, 5.0, -1.0, Double.PositiveInfinity),
        Seq(1, 2, 5, n, n + 3),
        leaf
      )
    yield problem
    problems.take(5) must beEmpty
  }

  "A radius that is exactly the distance to a data point includes that point" >> {
    // The computed distance q-e is the radius, so e sits on the boundary to the last bit: rounding in the pruning bound
    // must never hide it.
    val rng = Random(7)
    val elems = randomPoints(rng, 300, 3)
    val tree = VantagePointTree(elems, euclid, 4)
    val misses = for
      _ <- 1 to 60
      q = Array.fill(3)(rng.nextDouble())
      e = rng.nextInt(elems.length)
      eps = euclid(q, elems(e))
      expected = elems.indices.filter(i => euclid(q, elems(i)) <= eps)
      got = tree.indicesWithinDistance(q, eps)
      if got != expected || !got.contains(e)
    yield (e, eps)
    misses must beEmpty
  }

  "On an integer lattice, radii equal to lattice distances include every point exactly on the boundary" >> {
    val grid2 = for x <- 0 to 7; y <- 0 to 7 yield Array(x.toDouble, y.toDouble)
    val grid3 = for x <- 0 to 3; y <- 0 to 3; z <- 0 to 3 yield Array(x.toDouble, y.toDouble, z.toDouble)
    val radii = (0 to 30).map(m => math.sqrt(m.toDouble)) ++ Seq(1.0, 2.0, 3.0, 4.0)
    val ks = Seq(1, 2, 3, 4, 5, 9, 10, 17, 33, 63, 64)
    val problems = for
      (name, elems, dist) <- Seq[(String, IndexedSeq[Pt], (Pt, Pt) => Double)](
        ("grid2 euclid", grid2, euclid),
        ("grid2 manhattan", grid2, manhattan),
        ("grid2 chebyshev", grid2, chebyshev),
        ("grid3 euclid", grid3, euclid),
        ("grid3 manhattan", grid3, manhattan)
      )
      leaf <- Seq(1, 3, 16)
      problem <- disagreements(elems, dist, elems.indices.filter(_ % 5 == 0).map(elems), radii, ks, leaf)
    yield s"$name: $problem"
    problems.take(5) must beEmpty
  }

  "k nearest neighbours break ties by construction order, for every k" >> {
    val grid = for x <- 0 to 5; y <- 0 to 5 yield Array(x.toDouble, y.toDouble)
    val tree = VantagePointTree(grid, euclid, 2)
    val everyK = for
      qi <- grid.indices
      k <- 1 to grid.length
      expected = grid.indices.sortBy(i => euclid(grid(qi), grid(i))).take(k)
      if tree.nearestIndices(grid(qi), k) != expected
    yield (qi, k)
    everyK must beEmpty
  }

  "A metric that is not Euclidean: Hamming distance on all binary strings of length 6" >> {
    val words = (0 until 64).map(i => (5 to 0 by -1).map(b => if ((i >> b) & 1) == 1 then '1' else '0').mkString)
    val problems = for
      leaf <- Seq(1, 4, 16)
      queries = words.indices.filter(_ % 3 == 0).map(words)
      problem <- disagreements(words, hamming, queries, (0 to 6).map(_.toDouble), 1 to 64, leaf)
    yield problem
    problems.take(5) must beEmpty
  }

  "A metric that is not Euclidean: Levenshtein distance on short words, with repeated words" >> {
    val rng = Random(3)
    val words = IndexedSeq.fill(120)(String(Array.fill(rng.nextInt(6))("abc".charAt(rng.nextInt(3)))))
    val queries = IndexedSeq.fill(10)(String(Array.fill(rng.nextInt(6))("abc".charAt(rng.nextInt(3))))) ++ words.take(5)
    val problems = for
      leaf <- Seq(1, 8)
      problem <- disagreements(words, levenshtein, queries, (0 to 6).map(_.toDouble), Seq(1, 3, 10, 120), leaf)
    yield problem
    problems.take(5) must beEmpty
  }

  "A metric that is not Euclidean: the circle Z/40, an ultrametric on integers, and the discrete metric" >> {
    val circle = IndexedSeq.range(0, 40)
    def onCircle(a: Int, b: Int): Double = math.min(math.abs(a - b), 40 - math.abs(a - b)).toDouble
    val ints = IndexedSeq.tabulate(150)(i => (i * 37) % 101)
    val discrete = IndexedSeq.tabulate(30)(_ % 10)
    def discreteMetric(a: Int, b: Int): Double = if a == b then 0.0 else 1.0
    val problems = for
      leaf <- Seq(1, 4, 16)
      problem <-
        disagreements(circle, onCircle, circle, (0 to 20).map(_.toDouble), Seq(1, 2, 7, 40), leaf) ++
          disagreements(
            ints,
            ultrametric,
            0 to 100 by 7,
            Seq(0.0, 0.125, 0.25, 0.5, 1.0, 2.0),
            Seq(1, 4, 50, 150),
            leaf
          ) ++
          disagreements(discrete, discreteMetric, 0 until 12, Seq(0.0, 0.5, 1.0), Seq(1, 3, 4, 30), leaf)
    yield problem
    problems.take(5) must beEmpty
  }

  "Manhattan and Chebyshev distances on random points" >> {
    val rng = Random(11)
    val elems = randomPoints(rng, 150, 4)
    val queries = randomPoints(rng, 10, 4)
    val problems =
      disagreements(elems, manhattan, queries, Seq(0.0, 0.3, 1.0, 2.5), Seq(1, 6, 150), 8) ++
        disagreements(elems, chebyshev, queries, Seq(0.0, 0.1, 0.3, 0.6), Seq(1, 6, 150), 8)
    problems.take(5) must beEmpty
  }

  "A distance that obeys the triangle inequality only up to rounding still finds every point on the boundary" >> {
    // Real distances come out of floating-point arithmetic, which breaks the triangle inequality in the last bits. Here
    // every pairwise distance is |a - b| times a symmetric factor within 1e-14 of 1, so the triangle inequality fails by
    // about that much; a radius equal to a computed distance puts a point exactly on the boundary and a pruning bound
    // without slack would sometimes skip the subtree holding it.
    def factor(a: Int, b: Int): Double =
      val h = (math.min(a, b) * 73856093) ^ (math.max(a, b) * 19349663)
      1.0 + 1e-14 * (((h & 0xffff) / 32768.0) - 1.0)
    def noisy(a: Int, b: Int): Double = math.abs(a - b) * factor(a, b)
    val elems = IndexedSeq.tabulate(400)(i => (i * 7) % 400)
    val tree = VantagePointTree(elems, noisy, 3)
    val rng = Random(13)
    val misses = for
      _ <- 1 to 4000
      q = rng.nextInt(400)
      target = elems(rng.nextInt(400))
      eps = noisy(q, target)
      expected = elems.indices.filter(i => noisy(q, elems(i)) <= eps)
      if tree.indicesWithinDistance(q, eps) != expected
    yield (q, target, eps)
    misses must beEmpty
  }

  "Collinear lattice points, where computed distances break the triangle inequality by one ulp: the point on the radius is found from every vantage point" >> {
    // (0,3), (3,6) and (4,7) are collinear, at distances sqrt(18), sqrt(2) and sqrt(32) from each other, and in floating
    // point sqrt(32) > sqrt(18) + sqrt(2). With (0,3) as the vantage point and (3,6) the farthest of its nearer half
    // (leafSize 3 makes these four points one inner node), a query at (4,7) of radius sqrt(2) puts (3,6) exactly on the
    // radius and exactly one ulp outside what the triangle inequality allows: a pruning bound without slack skips it.
    // Each of the 24 orders of the points gives a different vantage point.
    val v = Array(0.0, 3.0)
    val near = Array(1.0, 3.0)
    val onRadius = Array(3.0, 6.0)
    val q = Array(4.0, 7.0)
    val problems = for
      elems <- IndexedSeq(v, near, onRadius, q).permutations.toSeq
      problem <- disagreements(elems, euclid, Seq(q), Seq(math.sqrt(2.0)), Seq(1, 2, 4), 3)
    yield problem
    (euclid(v, q) must beGreaterThan(euclid(v, onRadius) + euclid(onRadius, q))) and (problems must beEmpty)
  }

  "Coincident points are all found, in construction order, without any special handling" >> {
    val same = IndexedSeq.fill(100)(Array(0.25, 0.5))
    val tree = VantagePointTree(same, euclid, 4)
    val q = Array(0.25, 0.5)
    (tree.indicesWithinDistance(q, 0.0) must be_==(IndexedSeq.range(0, 100))) and
      (tree.nearestIndices(q, 7) must be_==(IndexedSeq.range(0, 7))) and
      (tree.nearestIndices(Array(9.0, 9.0), 100) must be_==(IndexedSeq.range(0, 100)))
  }

  "Two clusters of coincident points" >> {
    val twoSites = IndexedSeq.tabulate(80)(i => if i % 2 == 0 then Array(0.0, 0.0) else Array(3.0, 4.0))
    val problems = for
      leaf <- Seq(1, 3, 16)
      problem <- disagreements(
        twoSites,
        euclid,
        Seq(Array(0.0, 0.0), Array(3.0, 4.0), Array(1.0, 1.0)),
        Seq(0.0, 1.0, 4.999, 5.0, 5.001),
        Seq(1, 2, 39, 40, 41, 80),
        leaf
      )
    yield problem
    problems.take(5) must beEmpty
  }

  "Radius 0 returns exactly the points coinciding with the query" >> {
    val rng = Random(5)
    val elems = randomPoints(rng, 60, 2) ++ randomPoints(Random(5), 10, 2)
    val tree = VantagePointTree(elems, euclid)
    val coincide = elems.indices.filter(i => elems(i).sameElements(elems.head))
    (tree.indicesWithinDistance(elems.head, 0.0) must be_==(coincide)) and
      (tree.indicesWithinDistance(Array(2.0, 2.0), 0.0) must beEmpty)
  }

  "Elements come back as themselves, in construction order, and the tree reports its size" >> {
    val names = Vector("delta", "alpha", "charlie", "bravo", "echo", "alpha")
    val tree = VantagePointTree(names, (a: String, b: String) => levenshtein(a, b), 2)
    (tree.size must be_==(6)) and
      (tree.withinDistance("alpha", 0.0) must be_==(Vector("alpha", "alpha"))) and
      (tree.withinDistance("alpha", 2.0) must be_==(names.filter(levenshtein(_, "alpha") <= 2.0))) and
      (tree.nearest("alpha", 3) must be_==(Vector("alpha", "alpha", "delta")))
  }

  "Edge cases of the query arguments" >> {
    val rng = Random(1)
    val elems = randomPoints(rng, 20, 2)
    val tree = VantagePointTree(elems, euclid, 3)
    val q = Array(0.5, 0.5)
    val empty = VantagePointTree(IndexedSeq.empty[Pt], euclid)
    (tree.indicesWithinDistance(q, -0.5) must beEmpty) and
      (tree.indicesWithinDistance(q, Double.NaN) must beEmpty) and
      (tree.indicesWithinDistance(q, Double.PositiveInfinity) must be_==(IndexedSeq.range(0, 20))) and
      (tree.nearestIndices(q, 0) must beEmpty) and
      (tree.nearestIndices(q, 100) must haveSize(20)) and
      (tree.nearestIndices(q, -1) must throwAn[IllegalArgumentException]) and
      (empty.size must be_==(0)) and
      (empty.indicesWithinDistance(q, 10.0) must beEmpty) and
      (empty.nearestIndices(q, 3) must beEmpty)
  }

  "Construction is deterministic: the same inputs give identical trees, whichever collection holds the elements" >> {
    val rng = Random(9)
    val elems = randomPoints(rng, 400, 3)
    val a = VantagePointTree(elems, euclid, 8)
    val b = VantagePointTree(elems.toList, euclid, 8)
    val c = VantagePointTree(elems.toArray.toSeq, euclid, 8)
    val queries = randomPoints(rng, 30, 3)
    val same = queries.forall { q =>
      a.indicesWithinDistance(q, 0.3) == b.indicesWithinDistance(q, 0.3) &&
      a.indicesWithinDistance(q, 0.3) == c.indicesWithinDistance(q, 0.3) &&
      a.nearestIndices(q, 9) == b.nearestIndices(q, 9) && a.nearestIndices(q, 9) == c.nearestIndices(q, 9)
    }
    same must beTrue
  }

  "Large and degenerate inputs build without deep recursion or quadratic work" >> {
    // 50,000 collinear points in sorted order and 50,000 identical points: the shapes that degrade a tree built from
    // an unbalanced split or a split by a threshold that ties.
    val line = IndexedSeq.range(0, 50000)
    val lineTree = VantagePointTree(line, (a: Int, b: Int) => math.abs(a - b).toDouble)
    val identical = IndexedSeq.fill(50000)(0)
    val identicalTree = VantagePointTree(identical, (_: Int, _: Int) => 0.0)
    (lineTree.withinDistance(25000, 3.0) must be_==(IndexedSeq(24997, 24998, 24999, 25000, 25001, 25002, 25003))) and
      (lineTree.nearest(10, 5) must be_==(IndexedSeq(10, 9, 11, 8, 12))) and
      (identicalTree.indicesWithinDistance(0, 0.0) must haveSize(50000)) and
      (identicalTree.nearestIndices(0, 4) must be_==(IndexedSeq(0, 1, 2, 3)))
  }
end VantagePointTreeSpec
