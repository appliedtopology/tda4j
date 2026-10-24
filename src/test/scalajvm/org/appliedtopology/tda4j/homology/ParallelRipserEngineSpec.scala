package org.appliedtopology.tda4j

import BlockParallelReductionSpec.integerMetric
import Persistence.Engine
import SpecThrows.throwWith
import org.specs2.mutable.Specification

/** `Engine.ParallelRipser` through `Persistence`: the bars of `Engine.Ripser` bar for bar (generic point clouds over
  * F_17, F_2 and F_3, and a metric of ties), whatever the number of threads; cocycles and cycles that are what they say
  * (a cocycle's coboundary vanishes on every simplex before the bar ends, a cycle has no boundary and lies in the
  * complex at its birth), built on demand or all at once with the same terms; chosen by `Auto` exactly when there are
  * threads; and refused where it cannot answer as `Ripser` would.
  */
class ParallelRipserEngineSpec extends Specification:
  private val parallel = Engine.ParallelRipser

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
    val actual = Persistence(input, maxDimension, threshold, characteristic = p, engine = parallel)
    (sortedTriples(actual) == sortedTriples(expected)).aka(s"the bars for p = $p, degrees 0..$maxDimension") must beTrue

  // The coefficient of each simplex of a representative, as an Int mod the characteristic.
  private def coefficients(
    d: PersistenceDiagram[Simplex[Int]],
    bar: PersistenceBar[Double, Chain[Simplex[Int], d.Coefficient]],
    p: Int
  ): Map[List[Int], Int] =
    import d.given
    bar.representative.terms
      .map((s, c) => s.toList -> ((summon[d.Coefficient is Field].toDouble(c).round.toInt % p + p) % p))
      .toMap

  // A cocycle z of a bar [birth, death) evaluates to zero on the boundary of every simplex that is there before death.
  private def isCocycleBefore(
    d: PersistenceDiagram[Simplex[Int]],
    points: Array[Array[Double]],
    p: Int
  ): Boolean =
    val space = EuclideanMetricSpace(points)
    val stream = VietorisRips(space, d.maxDimension + 1, Double.PositiveInfinity)
    val simplices = stream.iterator.toList
    d.bars.forall { bar =>
      val z = coefficients(d, bar, p)
      z.nonEmpty && simplices.forall { tau =>
        val vs = tau.toList
        vs.size != bar.dim + 2 || stream.filtrationValue(tau) >= bar.death || {
          val sum = vs.indices.map(i => (if i % 2 == 0 then 1 else -1) * z.getOrElse(vs.patch(i, Nil, 1), 0)).sum
          ((sum % p) + p) % p == 0
        }
      }
    }

  // A cycle has no boundary, and every simplex of it is there by the bar's birth.
  private def isCycleAtBirth(
    d: PersistenceDiagram[Simplex[Int]],
    points: Array[Array[Double]],
    p: Int
  ): Boolean =
    val space = EuclideanMetricSpace(points)
    val stream = VietorisRips(space, d.maxDimension + 1, Double.PositiveInfinity)
    d.bars.forall { bar =>
      val z = coefficients(d, bar, p)
      val boundary = scala.collection.mutable.Map.empty[List[Int], Int].withDefaultValue(0)
      for (vs, c) <- z; i <- vs.indices do
        val face = vs.patch(i, Nil, 1)
        boundary(face) = boundary(face) + (if i % 2 == 0 then c else -c)
      z.nonEmpty && z.keys.forall(vs => stream.filtrationValue(Simplex(vs*)) <= bar.birth) &&
      (bar.dim == 0 || boundary.values.forall(x => ((x % p) + p) % p == 0))
    }

  "the parallel Ripser engine" should {
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
    "give the same bars and the same representatives on one thread and on every processor" >> {
      val points = circle(70, 5L)
      val threads = Persistence(points, engine = parallel)
      val one = Parallel.sequentially(Persistence(points, engine = parallel))
      sortedTriples(threads) === sortedTriples(one)
      (threads.bars.map(_.representative) == one.bars.map(_.representative)).aka("the cycles") must beTrue
    }
    "find the circle: one long bar in degree 1, one essential bar in degree 0" >> {
      val d = Persistence(circle(60, 2L), maxDimension = 1, engine = parallel)
      d.dim(1).longest.map(_.persistence).exists(_ > 0.8).aka("a long H1 bar") must beTrue
      d.dim(0).essential.size === 1
    }
    "give cocycles whose coboundary vanishes before the bar ends, over F_17 and F_3" >> {
      val points = cloud(14, 3, 6L)
      for p <- List(17, 3) do
        val d = Persistence(
          points,
          maxDimension = 2,
          characteristic = p,
          engine = parallel,
          representatives = Representatives.Cocycles
        )
        isCocycleBefore(d, points, p).aka(s"cocycles over F_$p") must beTrue
      // The checker accepts Ripser's own cocycles, and the parallel engine's agree with its convention of signs.
      isCocycleBefore(
        Persistence(points, maxDimension = 2, engine = Engine.Ripser, representatives = Representatives.Cocycles),
        points,
        17
      ) must beTrue
      // The check fails a cycle, so it is not vacuous: a cycle of the circle is not a cocycle.
      val ring = circle(12, 7L)
      val cycles = Persistence(ring, maxDimension = 1, engine = parallel)
      isCocycleBefore(cycles, ring, 17).aka("cycles passed as cocycles") must beFalse
    }
    "give cycles without boundary that are there at the bar's birth, over F_17 and F_3" >> {
      val points = cloud(14, 3, 8L)
      for p <- List(17, 3) do
        val d = Persistence(points, maxDimension = 2, characteristic = p, engine = parallel)
        isCycleAtBirth(d, points, p).aka(s"cycles over F_$p") must beTrue
    }
    "build the representatives on demand with the same terms as all at once" >> {
      val points = cloud(16, 3, 9L)
      for reps <- List(Representatives.Cycles, Representatives.Cocycles) do
        val all = Persistence(points, engine = parallel, representatives = reps)
        val onDemand = Persistence(points, engine = parallel, representatives = reps, computeAllRepresentatives = false)
        sortedTriples(all) === sortedTriples(onDemand)
        (all.bars.map(_.representative) == onDemand.bars.map(_.representative)).aka(s"$reps") must beTrue
    }
    "be what Auto chooses with several threads, and Ripser on one" >> {
      val points = circle(40, 10L)
      val auto = Persistence(points, maxDimension = 1)
      val ripser = Persistence(points, maxDimension = 1, engine = Engine.Ripser)
      val sequentialAuto = Parallel.sequentially(Persistence(points, maxDimension = 1))
      sortedTriples(auto) === sortedTriples(ripser)
      // On one thread Auto is Ripser itself: its representatives, not merely its bars.
      (sequentialAuto.bars.map(_.representative) == ripser.bars.map(_.representative)).aka("Ripser's") must beTrue
      if Parallel.threads > 1 then
        (auto.bars
          .map(_.representative) == Persistence(points, maxDimension = 1, engine = parallel).bars.map(_.representative))
          .aka("the parallel engine's") must beTrue
      else ok
    }
    "leave Auto on Ripser for zero-length bars, which it cannot report" >> {
      val points = circle(20, 11L)
      Persistence(points, maxDimension = 1, includeZeroLength = true).triples.sorted ===
        Persistence(points, maxDimension = 1, includeZeroLength = true, engine = Engine.Ripser).triples.sorted
    }
    "refuse zero-length bars, the reals, a composite characteristic, a large prime and other complexes" >> {
      val points = circle(20, 12L)
      Persistence(points, engine = parallel, includeZeroLength = true) must throwWith[IllegalArgumentException](
        "zero-length"
      )
      Persistence(points, engine = parallel, characteristic = 0) must throwWith[IllegalArgumentException]("prime p")
      Persistence(points, engine = parallel, characteristic = 15) must throwWith[IllegalArgumentException]("prime")
      Persistence(points, engine = parallel, characteristic = 50021) must throwWith[IllegalArgumentException](
        "cannot take 20 points"
      )
      Persistence(points, engine = parallel, complex = Cech) must throwWith[IllegalArgumentException]("VietorisRips")
    }
    "refuse an input that is not points or a metric space" >> {
      val stream = VietorisRips(EuclideanMetricSpace(circle(20, 13L)), 2, 2.0)
      Persistence(stream, engine = parallel) must throwWith[IllegalArgumentException]("Vietoris-Rips complex of points")
    }
  }

  "The threads option" should {
    // 35 points in space to degree 2: thousands of tetrahedra, enough for the cycle pass to split its scan.
    "give the same bars and the same cycles on 1, 2 and 5 threads" >> {
      val points = cloud(35, 3, 14L)
      val reference = Persistence(points, engine = parallel, threads = 1)
      sortedTriples(reference) === sortedTriples(Persistence(points, engine = Engine.Ripser))
      for n <- List(2, 5) do
        val d = Persistence(points, engine = parallel, threads = n)
        sortedTriples(d) === sortedTriples(reference)
        (d.bars.map(_.representative) == reference.bars.map(_.representative))
          .aka(s"the cycles on $n threads") must beTrue
      ok
    }
    "give the same cocycles on 1 and 4 threads, also when they are built after the call returns" >> {
      val points = circle(50, 16L)
      val one = Persistence(points, engine = parallel, representatives = Representatives.Cocycles, threads = 1)
      val four = Persistence(
        points,
        engine = parallel,
        representatives = Representatives.Cocycles,
        computeAllRepresentatives = false,
        threads = 4
      )
      sortedTriples(four) === sortedTriples(one)
      (four.bars.map(_.representative) == one.bars.map(_.representative)).aka("the cocycles") must beTrue
    }
    "make Auto choose Ripser on one thread and the parallel engine on several, whatever the machine has" >> {
      val points = circle(40, 15L)
      val ripser = Persistence(points, maxDimension = 1, engine = Engine.Ripser)
      val one = Persistence(points, maxDimension = 1, threads = 1)
      (one.bars.map(_.representative) == ripser.bars.map(_.representative)).aka("Ripser's") must beTrue
      val three = Persistence(points, maxDimension = 1, threads = 3)
      val direct = Persistence(points, maxDimension = 1, engine = parallel, threads = 3)
      (three.bars.map(_.representative) == direct.bars.map(_.representative)).aka("the parallel engine's") must beTrue
    }
    "refuse fewer than one thread" >> {
      Persistence(circle(10, 17L), threads = 0) must throwWith[IllegalArgumentException]("threads must be at least 1")
    }
  }
