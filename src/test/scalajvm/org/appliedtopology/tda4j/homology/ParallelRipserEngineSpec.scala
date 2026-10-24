package org.appliedtopology.tda4j

import BlockParallelReductionSpec.integerMetric
import Persistence.Engine
import SpecThrows.throwWith
import org.specs2.mutable.Specification

/** `Engine.ParallelRipserBarsOnlyNoRepresentatives` through `Persistence`: the bars of `Engine.Ripser`, bar for bar
  * (generic point clouds over F_17, F_2 and F_3, and a metric of ties), whatever the number of threads, with no
  * representatives, and refused where it cannot answer as `Ripser` would.
  */
class ParallelRipserEngineSpec extends Specification:
  private val bars = Engine.ParallelRipserBarsOnlyNoRepresentatives

  private def cloud(n: Int, dim: Int, seed: Long): Array[Array[Double]] =
    val rng = scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  // A noisy circle: a long H1 bar to find, among short ones.
  private def circle(n: Int, seed: Long): Array[Array[Double]] =
    val rng = scala.util.Random(seed)
    Array.tabulate(n) { i =>
      val a = 2 * math.Pi * i / n
      Array(math.cos(a) + 0.05 * rng.nextGaussian(), math.sin(a) + 0.05 * rng.nextGaussian())
    }

  private def sortedTriples(d: PersistenceDiagram[?]): List[(Int, Double, Double)] = d.triples.sorted

  private def agreesWithRipser(
    input: Persistence.Input[Simplex[Int]],
    p: Int,
    maxDimension: Int,
    threshold: Optional[Double] = Optional.empty
  ) =
    val expected = Persistence(input, maxDimension, threshold, characteristic = p, engine = Engine.Ripser)
    val actual = Persistence(input, maxDimension, threshold, characteristic = p, engine = bars)
    (sortedTriples(actual) == sortedTriples(expected)).aka(s"the bars for p = $p, degrees 0..$maxDimension") must beTrue

  "the bars-only parallel engine" should {
    "give the bars of Ripser on a random cloud, in three dimensions, over F_17, F_2 and F_3" >> {
      val points = cloud(45, 3, 1L)
      agreesWithRipser(points, 17, 2)
      agreesWithRipser(points, 2, 2)
      agreesWithRipser(points, 3, 2)
    }
    "give the bars of Ripser on a noisy circle, with and without a threshold" >> {
      val points = circle(60, 2L)
      agreesWithRipser(points, 17, 2)
      agreesWithRipser(points, 17, 1, 0.8)
    }
    "give the bars of Ripser on a metric whose edges tie" >> {
      agreesWithRipser(integerMetric(14, 3L), 17, 2)
      agreesWithRipser(integerMetric(14, 4L), 3, 3)
    }
    "give the same bars on one thread and on every processor" >> {
      val points = circle(70, 5L)
      val parallel = Persistence(points, engine = bars)
      val sequential = Parallel.sequentially(Persistence(points, engine = bars))
      sortedTriples(parallel) === sortedTriples(sequential)
    }
    "find the circle: one long bar in degree 1, one essential bar in degree 0" >> {
      val d = Persistence(circle(60, 2L), maxDimension = 1, engine = bars)
      d.dim(1).longest.map(_.persistence).exists(_ > 0.8).aka("a long H1 bar") must beTrue
      d.dim(0).essential.size === 1
    }
    "record no representatives, and say so when one is asked for" >> {
      val d = Persistence(circle(30, 6L), maxDimension = 1, engine = bars)
      (d.bars.nonEmpty && d.bars.forall(_.annotation.isEmpty)).aka("every bar unannotated") must beTrue
      d.bars.head.representative must throwA[NoSuchElementException]
    }
    "ignore `representatives`, since it has none of either kind" >> {
      val points = circle(30, 7L)
      val cycles = Persistence(points, maxDimension = 1, engine = bars)
      val cocycles = Persistence(points, maxDimension = 1, engine = bars, representatives = Representatives.Cocycles)
      sortedTriples(cycles) === sortedTriples(cocycles)
    }
    "not be what Auto chooses: the default still gives every bar its representative" >> {
      val d = Persistence(circle(30, 8L), maxDimension = 1)
      d.bars.forall(_.annotation.isDefined) must beTrue
    }
    "refuse zero-length bars, the reals, a composite characteristic, and other complexes" >> {
      val points = circle(20, 9L)
      Persistence(points, engine = bars, includeZeroLength = true) must throwWith[IllegalArgumentException](
        "zero-length"
      )
      Persistence(points, engine = bars, characteristic = 0) must throwWith[IllegalArgumentException]("prime p")
      Persistence(points, engine = bars, characteristic = 15) must throwWith[IllegalArgumentException]("prime")
      Persistence(points, engine = bars, complex = Cech) must throwWith[IllegalArgumentException]("VietorisRips")
    }
    "refuse an input that is not points or a metric space" >> {
      val stream = VietorisRips(EuclideanMetricSpace(circle(20, 10L)), 2, 2.0)
      Persistence(stream, engine = bars) must throwWith[IllegalArgumentException]("Vietoris-Rips complex of points")
    }
  }
