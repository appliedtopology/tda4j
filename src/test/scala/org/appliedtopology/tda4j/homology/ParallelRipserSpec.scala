package org.appliedtopology.tda4j

import Persistence.Engine
import SpecThrows.throwWith
import ParallelPackedCohomology.{persistence, Result, Space}
import org.specs2.mutable.Specification

/** `Engine.ParallelRipser` on the platform that runs this spec (one thread on JavaScript, the same answer): the bars of
  * `Engine.Ripser`, cycles that close and cocycles that are there for every bar, and the refusals. The threaded runs
  * are checked in `ParallelRipserEngineSpec` (JVM).
  */
class ParallelRipserSpec extends Specification:
  private val parallel = Engine.ParallelRipser

  private def circle(n: Int, seed: Long): Array[Array[Double]] =
    val rng = scala.util.Random(seed)
    Array.tabulate(n) { i =>
      val a = 2 * math.Pi * i / n
      Array(math.cos(a) + 0.05 * rng.nextGaussian(), math.sin(a) + 0.05 * rng.nextGaussian())
    }

  private def cloud(n: Int, dim: Int, seed: Long): Array[Array[Double]] =
    val rng = scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  private def sortedTriples(d: PersistenceDiagram[?]): List[(Int, Double, Double)] = d.triples.sorted

  private def reduce(points: Array[Array[Double]], threshold: Double, maxDim: Int, sparse: Boolean): Result =
    val metricSpace = EuclideanMetricSpace(points)
    val space = Space(points.length, metricSpace.distance(_, _), threshold, maxDim, 17, forceSparseRanks = sparse)
    persistence(space, 1, 1, cocycles = true, keepPairs = true)

  private def pairsAndLogs(r: Result) =
    r.degrees.map { d =>
      (d.pairs.sorted.toSeq, d.logs.toSeq.map((c, log) => (c, log.columns.toSeq, log.multipliers.toSeq)).sortBy(_._1))
    }

  "the parallel Ripser engine" should {
    "look edge ranks up the same way in a table and in sorted neighbour lists" >> {
      // A cloud, a circle, and a grid of integer points (many edges of equal length).
      val grid = Array.tabulate(27)(i => Array((i % 3).toDouble, (i / 3 % 3).toDouble, (i / 9).toDouble))
      for (points, threshold, maxDim) <- List((cloud(20, 3, 9L), 0.7, 2), (circle(30, 5L), 10.0, 2), (grid, 1.5, 3)) do
        val table = reduce(points, threshold, maxDim, sparse = false)
        val lists = reduce(points, threshold, maxDim, sparse = true)
        (table.space.sparseRanks, lists.space.sparseRanks) === (false, true)
        (pairsAndLogs(lists) == pairsAndLogs(table)).aka("pairs and cocycle logs") must beTrue
        (lists.bars == table.bars).aka("bars") must beTrue
      ok
    }
    "switch to neighbour lists above the table's limit, with the bars of Ripser" >> {
      // Points on a line, one apart: a path, so H0 only. 4,200 points are past the 4,096 of the table.
      val line = Array.tabulate(4200)(i => Array(i.toDouble, 0.0))
      val actual = Persistence(line, maxDimension = 1, maxFiltrationValue = 1.5, engine = parallel)
      val expected = Persistence(line, maxDimension = 1, maxFiltrationValue = 1.5, engine = Engine.Ripser)
      (sortedTriples(actual) == sortedTriples(expected)).aka("the bars") must beTrue
      actual.dim(0).bars.size === 4200
    }
    "find the loop of a square" >> {
      val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      // The four edges close a loop at 1; the diagonals and triangles fill it at the square root of 2.
      Persistence(square, maxDimension = 1, engine = parallel).dim(1).triples === List((1, 1.0, math.sqrt(2.0)))
    }
    "give the bars of Ripser over F_17, F_2 and F_3, to degree 2" >> {
      for (points, p) <- List((circle(24, 1L), 17), (cloud(14, 3, 2L), 2), (cloud(14, 3, 3L), 3)) do
        val expected = Persistence(points, maxDimension = 2, characteristic = p, engine = Engine.Ripser)
        val actual = Persistence(points, maxDimension = 2, characteristic = p, engine = parallel)
        (sortedTriples(actual) == sortedTriples(expected)).aka(s"the bars for p = $p") must beTrue
      ok
    }
    "give a cycle for every bar, and the cycles of degree 1 close" >> {
      val p = 17
      val d = Persistence(circle(30, 4L), maxDimension = 1, characteristic = p, engine = parallel)
      import d.given
      val field = summon[d.Coefficient is Field]
      val closed = d.bars.filter(_.dim == 1).forall { bar =>
        val net = scala.collection.mutable.Map.empty[Int, Long].withDefaultValue(0L)
        for (edge, c) <- bar.representative.terms do
          val vs = edge.toList
          val coefficient = field.toDouble(c).round
          net(vs(0)) -= coefficient
          net(vs(1)) += coefficient
        net.values.forall(v => ((v % p) + p) % p == 0)
      }
      closed.aka("every H_1 cycle has no boundary") must beTrue
      d.bars.forall(_.representative.terms.nonEmpty).aka("a representative for every bar") must beTrue
    }
    "give cocycles for every bar" >> {
      val d =
        Persistence(circle(24, 5L), maxDimension = 1, representatives = Representatives.Cocycles, engine = parallel)
      d.bars.forall(_.representative.terms.nonEmpty).aka("a representative for every bar") must beTrue
    }
    "refuse what it cannot answer as Ripser would" >> {
      val points = circle(20, 6L)
      Persistence(points, engine = parallel, includeZeroLength = true) must throwWith[IllegalArgumentException](
        "zero-length"
      )
      Persistence(points, engine = parallel, characteristic = 0) must throwWith[IllegalArgumentException]("prime p")
      Persistence(points, engine = parallel, complex = Cech) must throwWith[IllegalArgumentException]("VietorisRips")
    }
  }
