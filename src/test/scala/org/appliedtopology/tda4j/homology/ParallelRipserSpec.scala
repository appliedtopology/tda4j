package org.appliedtopology.tda4j

import Persistence.Engine
import SpecThrows.throwWith
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

  "the parallel Ripser engine" should {
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
