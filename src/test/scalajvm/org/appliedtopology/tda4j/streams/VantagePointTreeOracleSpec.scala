package org.appliedtopology.tda4j

import com.eatthepath.jvptree.{DistanceFunction, VPTree}
import org.specs2.mutable.Specification

import scala.jdk.CollectionConverters.*
import scala.util.Random

/** JVM only: `VantagePointTree` against the library it replaces, jvptree. Radius queries must return the same SETS of
  * points, boundary included (jvptree's `getAllWithinDistance` is `distance <= radius`), nearest-neighbour queries the
  * same DISTANCES (jvptree leaves the order of tied points undefined; ours is documented, and equal to a linear scan,
  * which `VantagePointTreeSpec` pins).
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

  private var shortAnswers = 0

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
      val q = Integer.valueOf(qi)
      val within = radii.flatMap { eps =>
        val expected = tree.getAllWithinDistance(q, eps).asScala.map(_.intValue).toSet
        val got = ours.indicesWithinDistance(pts(qi), eps).toSet
        if expected == got then None
        else Some(s"query $qi radius $eps: jvptree ${expected.toSeq.sorted}, ours ${got.toSeq.sorted}")
      }
      val nearest = ks.flatMap { k =>
        val expected = tree.getNearestNeighbors(q, k).asScala.map(i => euclid(pts(qi), pts(i))).toSeq
        val got = ours.nearestIndices(pts(qi), k).map(i => euclid(pts(qi), pts(i)))
        // jvptree 0.3.0 sometimes returns fewer than `k` neighbours when `k` is a large part of the points (see the
        // example below), and then not even the nearest ones: such answers are not compared.
        if expected.length < math.min(k, pts.length) then
          shortAnswers += 1
          None
        else if expected == got then None
        else Some(s"query $qi k $k: jvptree distances $expected, ours $got")
      }
      within ++ nearest
    }

  "Random Euclidean points: the same neighbour sets and the same neighbour distances as jvptree" >> {
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

  "A radius equal to a computed distance: both libraries include the point on the boundary" >> {
    val rng = Random(37)
    val pts = IndexedSeq.fill(400)(Array.fill(3)(rng.nextDouble()))
    val ours = VantagePointTree(pts, euclid)
    val theirs = jvptree(pts)
    val problems = for
      _ <- 1 to 200
      qi = rng.nextInt(pts.length)
      target = rng.nextInt(pts.length)
      eps = euclid(pts(qi), pts(target))
      expected = theirs.getAllWithinDistance(Integer.valueOf(qi), eps).asScala.map(_.intValue).toSet
      got = ours.indicesWithinDistance(pts(qi), eps).toSet
      if expected != got || !got.contains(target)
    yield (qi, target, eps)
    problems must beEmpty
  }

  "Lattice points, with many exactly equal distances: radii on the lattice distances agree with jvptree" >> {
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
        s"queries skipped in this spec for that reason: $shortAnswers"
    )
    (ours must haveSize(64)) and (ours.take(5) must be_==(IndexedSeq(21, 20, 22, 19, 23))) and
      (ours.map(i => math.abs(i - 21)) must be_==(ours.map(i => math.abs(i - 21)).sorted))
  }
end VantagePointTreeOracleSpec
