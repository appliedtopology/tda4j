package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import scala.collection.mutable

/** The Cech value on arrays (`CechFiltration.Values`, on a [[VertexSetValues]] table) must be the value the earlier
  * `Simplex`-keyed implementation gave, bit for bit, whatever order the simplices are asked in: the value of a simplex
  * depends on which of its facets are cached, so the oracle here is that implementation (a `Simplex`-keyed map) fed the
  * same sequence of calls.
  */
class CechValuesSpec extends Specification:

  private def bits(x: Double) = java.lang.Double.doubleToLongBits(x)

  "VertexSetValues finds every set it stored and no other, keeps the first value of a set, and holds many sets of " +
    "several widths" >> {
      val table = new VertexSetValues
      val sets = for
        width <- 0 to 5
        set <- (0 until 14).combinations(width)
      yield set.toArray
      val value = (set: Array[Int]) => set.foldLeft(0.5)((acc, v) => acc * 31 + v)
      sets.foreach(set => table.putIfAbsent(set, 0, set.length, value(set)))
      val stored = sets.forall(set => bits(table.get(set, 0, set.length)) == bits(value(set)))
      val kept = sets.forall(set => bits(table.putIfAbsent(set, 0, set.length, -1.0)) == bits(value(set)))
      val absent = table.get(Array(3, 99), 0, 2).isNaN && table.get(Array(1, 2, 3, 4, 5, 6, 7), 0, 7).isNaN &&
        table.get(Array(2, 1), 0, 2).isNaN
      val inTheMiddle = table.get(Array(9, 0, 1, 2, 9), 1, 3) == value(Array(0, 1, 2))
      (stored must beTrue) and (kept must beTrue) and (absent must beTrue) and (inTheMiddle must beTrue) and
        (table.size must beEqualTo(sets.size.toLong))
    }

  "VertexSetValues takes inserts from several threads without losing or changing a value" >> {
    val table = new VertexSetValues
    val sets = (0 until 40).combinations(3).map(_.toArray).toVector
    Parallel.foreach(sets)(set => table.putIfAbsent(set, 0, 3, set.sum.toDouble))
    Parallel.foreach(sets)(set => table.putIfAbsent(set, 0, 3, -1.0))
    (sets.forall(set => table.get(set, 0, 3) == set.sum.toDouble) must beTrue) and
      (table.size must beEqualTo(sets.size.toLong))
  }

  // The value as the implementation before the vertex-array cache computed it: a `Simplex`-keyed map, the facets
  // looked up in it, the first strictly interior vertex deciding.
  private def earlier(space: EuclideanMetricSpace): Simplex[Int] => Double =
    val cache = mutable.HashMap.empty[Simplex[Int], Double]
    def computeRadius(spx: Simplex[Int]): Double =
      val vertices = spx.underlying.toArray
      val coords = vertices.map(space.pts)
      val ball = MinimumEnclosingBall(coords)
      val raw = ball.radius
      def facet(i: Int) = (spx.underlying - vertices(i)).asSimplex
      val facetFloor = vertices.indices.map(i => cache.getOrElse(facet(i), 0.0)).max
      val center = ball.center
      def distanceToCenter(i: Int) = math.sqrt(coords(i).indices.map(k => math.pow(coords(i)(k) - center(k), 2)).sum)
      val radius = vertices.indices
        .find(i => distanceToCenter(i) < raw * (1 - 1e-9))
        .flatMap(i => cache.get(facet(i)))
        .getOrElse(raw)
      math.max(radius, facetFloor)
    spx => cache.getOrElseUpdate(spx, if spx.dim <= 0 then 0.0 else computeRadius(spx))

  private def cloud(n: Int, dim: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  // Grids have cospherical points and vertices in the interior of a ball; the repeated point has zero distances.
  private val grid = (for i <- 0 until 3; j <- 0 until 3 yield Array(i.toDouble, j.toDouble)).toArray
  private val repeated = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.0, 0.0), Array(0.5, 0.9), Array(0.2, 0.3))
  private val inputs = Seq(
    "grid" -> grid,
    "points in the plane" -> cloud(8, 2, 1L),
    "points in space" -> cloud(8, 3, 2L),
    "a repeated point" -> repeated
  )

  // Dimension by dimension, as a stream asks, except for the simplices `skip` names: their values are never asked for,
  // so they stay out of the cache the later ones read.
  private def sequence(n: Int, skip: Simplex[Int] => Boolean): Seq[Array[Int]] =
    (1 to math.min(n, 6)).flatMap(width =>
      (0 until n).combinations(width).map(_.toArray).filterNot(a => skip(Simplex(a*)))
    )

  "the Cech value on arrays equals the earlier implementation's, bit for bit, in a stream's order and with faces left out" >> {
    val skips = Seq[(String, Simplex[Int] => Boolean)](
      "nothing left out" -> (_ => false),
      "triangles with an odd smallest vertex left out" -> (s => s.size == 3 && s.min % 2 == 1)
    )
    val results = for
      (inputName, points) <- inputs
      (skipName, skip) <- skips
    yield
      val space = EuclideanMetricSpace(points)
      val oracle = earlier(space)
      val simplices = CechFiltration(space)
      val flat = new CechFiltration.Values(space)
      val run = flat.flat.perRun
      val sets = sequence(points.length, skip)
      val matches = sets.forall { set =>
        val expected = oracle(Simplex(set*))
        bits(simplices(Simplex(set*))) == bits(expected) && bits(run.value(set, 0, set.length, Double.NaN)) == bits(
          expected
        )
      }
      s"$inputName, $skipName" -> matches
    results.filterNot(_._2).map(_._1) must beEmpty
  }
