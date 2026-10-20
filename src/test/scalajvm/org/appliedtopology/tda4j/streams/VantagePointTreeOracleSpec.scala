package org.appliedtopology.tda4j

import com.eatthepath.jvptree.{DistanceFunction, VPTree}
import org.specs2.mutable.Specification

import scala.jdk.CollectionConverters.*
import scala.util.Random

/** JVM only: `VantagePointTree` against the library it replaces, jvptree, and against a linear scan. Radius queries
  * must return the same SETS of points, boundary included (`distance <= radius`), nearest-neighbour queries the same
  * DISTANCES (jvptree leaves the order of tied points undefined; ours is documented, and equal to a linear scan, which
  * `VantagePointTreeSpec` pins).
  *
  * The linear scan is the judge: `VantagePointTree` must equal it in every query, whatever jvptree does. jvptree is
  * held to the scan where it is right, and its two known defects (the last two examples) are counted and printed, not
  * asserted: it draws its vantage points at random, so an assertion on them would pass or fail with the build.
  */
class VantagePointTreeOracleSpec extends Specification:
  sequential

  private def euclid(a: Array[Double], b: Array[Double]): Double =
    math.sqrt(a.indices.map(i => (a(i) - b(i)) * (a(i) - b(i))).sum)

  /** jvptree over the indices `0 until pts.length`, with the same distance function the tree under test gets. */
  private def jvptree(pts: IndexedSeq[Array[Double]]): VPTree[Integer, Integer] =
    val df = new DistanceFunction[Integer]:
      override def getDistance(a: Integer, b: Integer): Double = euclid(pts(a), pts(b))
    new VPTree[Integer, Integer](df, (0 until pts.length).map(Integer.valueOf).asJavaCollection)

  // jvptree's nearest-neighbour answers were right in every one of 128,000 queries with k <= 5 (random clouds and
  // lattices, 2026-10-20) and wrong or short in some from k = 20: they are compared with the scan up to here only.
  private val JvptreeReliableK = 5

  // What jvptree got wrong in the examples so far; printed by the last one.
  private var boundaryMisses = 0
  private var shortAnswers = 0
  private var wrongAnswers = 0

  /** The disagreement on one radius query, if any: ours differs from the linear scan at all, or jvptree returns a point
    * outside the radius or loses one inside it. jvptree prunes without allowing for rounding, so it can lose a point
    * lying exactly on the radius (its computed distance is `radius`): those are counted, not reported.
    */
  private def radiusProblem(
    pts: IndexedSeq[Array[Double]],
    ours: VantagePointTree[Array[Double]],
    tree: VPTree[Integer, Integer],
    qi: Int,
    eps: Double
  ): Option[String] =
    val scan = pts.indices.filter(i => euclid(pts(qi), pts(i)) <= eps).toSet
    val got = ours.indicesWithinDistance(pts(qi), eps).toSet
    val theirs = tree.getAllWithinDistance(Integer.valueOf(qi), eps).asScala.map(_.intValue).toSet
    val lost = scan -- theirs
    if got != scan then Some(s"query $qi radius $eps: linear scan ${scan.toSeq.sorted}, ours ${got.toSeq.sorted}")
    else if !theirs.subsetOf(scan) || !lost.forall(i => euclid(pts(qi), pts(i)) == eps) then
      Some(s"query $qi radius $eps: linear scan ${scan.toSeq.sorted}, jvptree ${theirs.toSeq.sorted}")
    else
      if lost.nonEmpty then boundaryMisses += 1
      None

  /** The disagreements on `pts` for the given query points, radii and neighbour counts. */
  private def compare(
    pts: IndexedSeq[Array[Double]],
    queries: Seq[Int],
    radii: Seq[Double],
    ks: Seq[Int]
  ): Seq[String] =
    val ours = VantagePointTree(pts, euclid)
    val tree = jvptree(pts)
    queries.flatMap { qi =>
      val within = radii.flatMap(eps => radiusProblem(pts, ours, tree, qi, eps))
      val nearest = ks.flatMap { k =>
        val scan = pts.indices.map(i => euclid(pts(qi), pts(i))).sorted.take(k)
        val got = ours.nearestIndices(pts(qi), k).map(i => euclid(pts(qi), pts(i)))
        val theirs = tree.getNearestNeighbors(Integer.valueOf(qi), k).asScala.map(i => euclid(pts(qi), pts(i))).toSeq
        if got != scan then Some(s"query $qi k $k: linear scan distances $scan, ours $got")
        else if theirs == scan then None
        else if k <= JvptreeReliableK then Some(s"query $qi k $k: linear scan distances $scan, jvptree $theirs")
        else
          if theirs.length < scan.length then shortAnswers += 1 else wrongAnswers += 1
          None
      }
      within ++ nearest
    }

  "Random Euclidean points: ours equals a linear scan, and jvptree agrees with it where it is reliable" >> {
    val rng = Random(31)
    val problems = for
      n <- Seq(1, 2, 5, 40, 150, 600)
      d <- Seq(1, 2, 3, 6)
      pts = IndexedSeq.fill(n)(Array.fill(d)(rng.nextDouble()))
      queries = Seq.fill(10)(rng.nextInt(n))
      problem <- compare(pts, queries, Seq(0.0, 0.03, 0.1, 0.3, 0.7, 3.0), Seq(1, 2, 7, n))
    yield problem
    problems.take(5) must beEmpty
  }

  "A radius equal to a computed distance: ours includes the point on the boundary, and so does jvptree here" >> {
    val rng = Random(37)
    val pts = IndexedSeq.fill(400)(Array.fill(3)(rng.nextDouble()))
    val ours = VantagePointTree(pts, euclid)
    val theirs = jvptree(pts)
    val problems = for
      _ <- 1 to 200
      qi = rng.nextInt(pts.length)
      target = rng.nextInt(pts.length)
      eps = euclid(pts(qi), pts(target))
      problem <- radiusProblem(pts, ours, theirs, qi, eps).orElse(
        Option.when(!ours.indicesWithinDistance(pts(qi), eps).contains(target))(
          s"query $qi radius $eps: the point $target on the boundary was not found"
        )
      )
    yield problem
    problems must beEmpty
  }

  "Lattice points, with many exactly equal distances: radii on the lattice distances agree with the linear scan" >> {
    val grid = IndexedSeq.tabulate(8 * 8)(i => Array((i / 8).toDouble, (i % 8).toDouble))
    val radii = (0 to 20).map(m => math.sqrt(m.toDouble))
    val problems = compare(grid, grid.indices.filter(_ % 3 == 0), radii, Seq(1, 5, 20, 64))
    problems.take(5) must beEmpty
  }

  "jvptree 0.3.0 can return fewer than k neighbours (its vantage points are random); ours never does" >> {
    // 64 points on a line, the query at 21, k = 64: some builds of the jvptree answer with 35 points. (DistanceToMeasure
    // with k near the number of points through jvptree was therefore averaging over too few neighbours.)
    val line = IndexedSeq.tabulate(64)(i => Array(i.toDouble))
    val sizes = (1 to 100).map(_ => jvptree(line).getNearestNeighbors(Integer.valueOf(21), 64).size)
    val ours = VantagePointTree(line, euclid).nearestIndices(Array(21.0), 64)
    println(
      s"jvptree 0.3.0 answers k = 64 of 64 points with fewer than 64 neighbours in ${sizes.count(_ < 64)} of 100 builds; " +
        s"nearest-neighbour queries with k > $JvptreeReliableK in this spec where it answered with fewer than k: " +
        s"$shortAnswers, with k neighbours but not the nearest ones: $wrongAnswers"
    )
    (ours must haveSize(64)) and (ours.take(5) must be_==(IndexedSeq(21, 20, 22, 19, 23))) and
      (ours.map(i => math.abs(i - 21)) must be_==(ours.map(i => math.abs(i - 21)).sorted))
  }

  "jvptree 0.3.0 can lose a point exactly on the radius (its pruning allows no room for rounding); ours never does" >> {
    // On the 8 x 8 lattice the points (0,3), (3,6) and (4,7) are collinear, at distances sqrt(18), sqrt(2) and sqrt(32)
    // from each other, and in floating point sqrt(32) > sqrt(18) + sqrt(2) by one ulp. With (0,3) as the vantage point
    // and (3,6) the farthest of its nearer half, a query at (4,7) of radius sqrt(2) fails jvptree's test
    // `distance to the vantage point <= threshold + radius`, so it skips the half holding (3,6), which lies exactly on
    // the radius. It draws its vantage points at random: some builds lose the point, some do not.
    val grid = IndexedSeq.tabulate(8 * 8)(i => Array((i / 8).toDouble, (i % 8).toDouble))
    val radius = math.sqrt(2.0)
    val lost = (1 to 200).count(_ => !jvptree(grid).getAllWithinDistance(Integer.valueOf(39), radius).contains(30))
    println(
      s"jvptree 0.3.0 loses the point 30 on the radius sqrt(2) of the query 39 in $lost of 200 builds of the 8 x 8 lattice; " +
        s"radius queries in this spec where it lost a point on the boundary: $boundaryMisses"
    )
    VantagePointTree(grid, euclid).indicesWithinDistance(grid(39), radius) must be_==(
      IndexedSeq(30, 31, 38, 39, 46, 47)
    )
  }
end VantagePointTreeOracleSpec
