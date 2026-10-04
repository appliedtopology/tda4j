package org.appliedtopology.tda4j

import org.specs2.mutable

/** `BowyerWatsonDelaunay`: the triangulation's invariants after every insertion, agreement with Helix in general
  * position, and, on degenerate input, the property its exact predicates promise: the triangulation does not depend on
  * the insertion order (the tie-break depends only on the points' indices), and no point lies inside any cell's
  * circumsphere under that tie-break.
  */
class BowyerWatsonSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  def cloud(dim: Int, n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  def grid(side: Int, dim: Int): Array[Array[Double]] =
    Array.tabulate(math.pow(side, dim).toInt)(k =>
      Array.tabulate(dim)(a => ((k / math.pow(side, a).toInt) % side).toDouble)
    )

  /** Small integer coordinates: collinear, cocircular and repeated points everywhere. */
  def integerCloud(dim: Int, n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextInt(4).toDouble))

  def contents(a: DelaunayAlphaShapes, dim: Int): Map[Simplex[Int], Double] =
    (0 to dim)
      .filter(a.iterateDimension.isDefinedAt)
      .flatMap(k => a.iterateDimension(k))
      .map(s => s -> a.filtrationValue(s))
      .toMap

  "keeps its invariants after every insertion (orientation, neighbours, local Delaunay)" >> {
    val problems = for
      (dim, n) <- Seq((2, 40), (3, 30), (4, 20))
      seed <- 0 until 4
      t = BowyerWatsonTriangulation(cloud(dim, n, 7L * dim + seed), seed, checkEveryStep = true)
      problem <- t.insertionErrors
    yield s"dim $dim seed $seed: $problem"
    problems must beEmpty
  }

  "gives Helix's alpha complex in general position: same simplices, values within 1e-9" >> {
    val problems = for
      (dim, n, seeds) <- Seq((2, 80, 6), (3, 50, 5), (4, 30, 3))
      seed <- 0 until seeds
      pts = cloud(dim, n, 1000L * dim + seed)
      bw = contents(BowyerWatsonDelaunay(pts), dim)
      helix = contents(HelixDelaunay(pts), dim)
      problem <- Seq(
        Option.when(bw.keySet != helix.keySet)(s"different simplices (${bw.size} vs ${helix.size})"),
        Option.when(bw.keySet == helix.keySet && bw.exists((s, v) => math.abs(v - helix(s)) > 1e-9 * math.max(1, v)))(
          "different values"
        )
      ).flatten
    yield s"dim $dim seed $seed: $problem"
    problems must beEmpty
  }

  "on degenerate input the triangulation does not depend on the insertion order, and keeps its invariants" >> {
    val inputs = Seq(
      "6x6 grid" -> grid(6, 2),
      "4x4x4 grid" -> grid(4, 3),
      "3^4 grid" -> grid(3, 4),
      "integer 2-D" -> integerCloud(2, 40, 1),
      "integer 3-D" -> integerCloud(3, 40, 2),
      "integer 4-D" -> integerCloud(4, 30, 3)
    )
    val problems = for (name, pts) <- inputs yield
      val runs = (0 until 4).map(seed => BowyerWatsonTriangulation(pts, seed, checkEveryStep = seed == 0))
      val tops = runs.map(_.topSimplices.toSet)
      Seq(
        Option.when(tops.distinct.size != 1)(s"$name: ${tops.map(_.size).mkString("/")} cells across seeds"),
        Option.when(runs.head.insertionErrors.nonEmpty)(s"$name: ${runs.head.insertionErrors.head}"),
        Option.when(runs.map(_.duplicates).distinct.size != 1)(s"$name: duplicates depend on the seed")
      ).flatten
    problems.flatten must beEmpty
  }

  "no point lies inside any cell's circumsphere (brute force, with the tie-break), degenerate inputs included" >> {
    val inputs = Seq(grid(5, 2), grid(3, 3), integerCloud(2, 30, 4), integerCloud(3, 25, 5), cloud(3, 25, 6))
    val problems = for
      pts <- inputs
      t = BowyerWatsonTriangulation(pts, 1)
      p = DelaunayPredicates(pts)
      cell <- t.topSimplices
      cs = cell.toSeq.toArray
      positive = if p.orientation(cs) > 0 then cs else cs.updated(0, cs(1)).updated(1, cs(0))
      q <- pts.indices
      if !cs.contains(q) && !t.duplicates.contains(q) && p.inSphere(positive, q) > 0
    yield s"point $q inside the circumsphere of $cell"
    problems.take(3) must beEmpty
  }

  "repeated points: every input point is a vertex, each repeat joined to its original by an edge of value 0" >> {
    val pts = integerCloud(2, 40, 9)
    val alpha = BowyerWatsonDelaunay(pts)
    val vertices = alpha.iterateDimension(0).toSet
    val repeats = pts.indices.filter(i => pts.indices.exists(j => j < i && pts(j).sameElements(pts(i))))
    val zeroEdges =
      repeats.forall(i => alpha.iterateDimension(1).exists(e => e.contains(i) && alpha.filtrationValue(e) == 0.0))
    (vertices must be_==(pts.indices.map(Simplex(_)).toSet)) and (repeats must not(beEmpty)) and (zeroEdges must beTrue)
  }

  "points on a line in the plane are triangulated in their 1-D span" >> {
    val pts = Array.tabulate(10)(i => Array(i * 0.5, 1.0 + i * 0.25))
    val alpha = BowyerWatsonDelaunay(pts)
    (alpha.ambientDimension must be_==(1)) and (alpha.iterateDimension(1).size must be_==(9))
  }

  "refuses an input that spans more than 4 dimensions, saying what to use instead" >> {
    BowyerWatsonDelaunay(cloud(5, 10, 1)) must throwAn[IllegalArgumentException](message = "AlphaBackend.DQP")
  }
