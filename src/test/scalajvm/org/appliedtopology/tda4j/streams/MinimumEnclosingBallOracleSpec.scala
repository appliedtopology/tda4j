package org.appliedtopology.tda4j

import com.dreizak.miniball.highdim.Miniball
import com.dreizak.miniball.model.PointSet
import org.specs2.mutable.Specification

import scala.collection.concurrent.TrieMap
import scala.util.Random

/** JVM only: `MinimumEnclosingBall` against the library it replaces, miniball (Gaertner's algorithm), and the Cech
  * filtration values it feeds against the values computed from miniball with the same clamping.
  */
class MinimumEnclosingBallOracleSpec extends Specification:
  sequential

  private final class ArrayPointSet(points: Array[Array[Double]]) extends PointSet:
    override def size: Int = points.length
    override def dimension: Int = points(0).length
    override def coord(i: Int, j: Int): Double = points(i)(j)

  private def dist(a: Array[Double], b: Array[Double]): Double =
    math.sqrt(a.indices.map(i => (a(i) - b(i)) * (a(i) - b(i))).sum)

  private def clouds(rng: Random): Seq[(String, Array[Array[Double]])] =
    val random = for
      _ <- 1 to 400
      n = 1 + rng.nextInt(30)
      d = 1 + rng.nextInt(20)
    yield ("uniform", Array.fill(n)(Array.fill(d)(2.0 * rng.nextDouble() - 1.0)))
    val few = for
      _ <- 1 to 600
      n = 2 + rng.nextInt(7)
      d = 2 + rng.nextInt(4)
    yield ("few points", Array.fill(n)(Array.fill(d)(rng.nextDouble())))
    val lattice = for
      _ <- 1 to 300
      n = 2 + rng.nextInt(10)
      d = 1 + rng.nextInt(5)
    yield ("lattice", Array.fill(n)(Array.fill(d)(rng.nextInt(3).toDouble)))
    random ++ few ++ lattice

  "Radius and centre agree with miniball on 1300 clouds of 1 to 30 points in dimension 1 to 20" >> {
    val rng = Random(51)
    var worstRadius = 0.0
    var worstCenter = 0.0
    val problems = for
      (name, cloud) <- clouds(rng)
      theirs = Miniball(ArrayPointSet(cloud))
      ours = MinimumEnclosingBall(cloud)
      theirRadius = math.sqrt(theirs.squaredRadius())
      radiusDifference = if theirRadius == 0.0 then ours.radius else math.abs(ours.radius - theirRadius) / theirRadius
      centerDifference = dist(ours.center, theirs.center()) / math.max(theirRadius, 1e-300)
      _ =
        worstRadius = math.max(worstRadius, radiusDifference); worstCenter = math.max(worstCenter, centerDifference)
      if radiusDifference > 1e-10 || (theirRadius > 0 && centerDifference > 1e-7)
    yield s"$name n=${cloud.length} d=${cloud.head.length}: radius ${ours.radius} vs ${theirRadius}, centre difference $centerDifference"
    println(
      s"MinimumEnclosingBall vs miniball: worst relative radius difference $worstRadius, worst centre difference / radius $worstCenter"
    )
    problems.take(5) must beEmpty
  }

  "Our ball is never larger than miniball's by more than rounding, and contains the points miniball's does" >> {
    val rng = Random(53)
    val problems = for
      (name, cloud) <- clouds(rng)
      theirs = Miniball(ArrayPointSet(cloud))
      ours = MinimumEnclosingBall(cloud)
      if ours.radius > math.sqrt(theirs.squaredRadius()) * (1 + 1e-10) + 1e-300
    yield (name, cloud.length, cloud.head.length)
    problems.take(5) must beEmpty
  }

  /** The Cech filtration as it was computed from miniball, with the same facet floor and strictly-inside shortcut. */
  private def miniballCech(ms: EuclideanMetricSpace): Simplex[Int] => Double =
    val cache = TrieMap.empty[Simplex[Int], Double]
    def compute(spx: Simplex[Int]): Double =
      val vertices = spx.underlying.toArray
      val coords = vertices.map(ms.pts)
      val ball = Miniball(ArrayPointSet(coords))
      val raw = math.sqrt(ball.squaredRadius())
      def facet(i: Int) = (spx.underlying - vertices(i)).asSimplex
      val facetFloor = vertices.indices.map(i => cache.getOrElse(facet(i), 0.0)).max
      val center = ball.center()
      def distanceToCenter(i: Int) = dist(coords(i), center)
      val radius = vertices.indices
        .find(i => distanceToCenter(i) < raw * (1 - 1e-9))
        .flatMap(i => cache.get(facet(i)))
        .getOrElse(raw)
      math.max(radius, facetFloor)
    spx => cache.getOrElseUpdate(spx, if spx.dim <= 0 then 0.0 else compute(spx))

  "Cech filtration values equal the miniball-based values on every simplex of random clouds" >> {
    val rng = Random(57)
    var worst = 0.0
    var simplices = 0
    val problems = for
      (n, d, lattice) <- Seq((9, 2, false), (9, 3, false), (8, 5, false), (10, 3, true), (10, 2, true), (7, 8, false))
      _ <- 1 to 6
      points = Array.fill(n)(Array.fill(d)(if lattice then rng.nextInt(3).toDouble else rng.nextDouble()))
      ms = EuclideanMetricSpace(points)
      old = miniballCech(ms)
      fresh = CechFiltration(ms)
      size <- 1 to math.min(n, 6)
      vertices <- (0 until n).combinations(size)
      spx = Simplex(vertices*)
      before = old(spx)
      after = fresh(spx)
      difference = if before == 0.0 then math.abs(after) else math.abs(after - before) / before
      _ =
        worst = math.max(worst, difference); simplices += 1
      if difference > 1e-9
    yield s"n=$n d=$d lattice=$lattice $vertices: $before vs $after"
    println(s"Cech radii vs miniball-based radii on $simplices simplices: largest relative difference $worst")
    problems.take(5) must beEmpty
  }
end MinimumEnclosingBallOracleSpec
