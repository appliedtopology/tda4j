package org.appliedtopology.tda4j

import DiscreteMorse.Strategy
import Persistence.Engine

import org.specs2.mutable.Specification

import scala.collection.mutable

/** Discrete Morse theory on filtered complexes. Oracles: the matching is checked for what a filtered acyclic matching
  * is by code of its own (faces of one dimension less, one filtration value, no cell twice, no cycle of the gradient);
  * the Morse boundary squares to zero over `Z/3`, `Z/17` and the rationals; and the persistent homology of the Morse
  * complex has the bars of the original complex, on fixtures full of ties (integer grids, where almost every level has
  * many cells).
  */
class DiscreteMorseSpec extends Specification:

  private val grid = for x <- 0 until 3; y <- 0 until 3 yield Array(x.toDouble, y.toDouble)

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))

  private def rips(points: Seq[Array[Double]], maxDimension: Int = 2) =
    VietorisRips(EuclideanMetricSpace(points.toArray), maxDimension = maxDimension, maxFiltrationValue = 100.0)

  // a `def`: the Vietoris-Rips layers are cached in the stream, and specs2 runs the examples concurrently
  private def fixtures: List[(String, StratifiedCellStream[Simplex[Int], Double])] = List(
    "3x3 grid" -> rips(grid),
    "random 8" -> rips(cloud(8, 3L)),
    "grid of 4 and noise" -> rips(grid.take(4) ++ cloud(3, 9L).toSeq)
  )

  private def explicit(items: (Double, Simplex[Int])*): StratifiedCellStream[Simplex[Int], Double] =
    val builder = ExplicitStreamBuilder[Int, Double]()
    items.foreach(builder += _)
    builder.result()

  private def rounded(bars: Seq[(Int, Double, Double)]) =
    def r(x: Double) = if x.isInfinite then x else math.round(x * 1e9) / 1e9
    bars.map((k, b, d) => (k, r(b), r(d))).sorted

  private val strategies = List(Strategy.Reduction, Strategy.Coreduction)

  /** What an acyclic matching of a filtered complex is, checked with no reference to how it was made. */
  private def matchingProblems(stream: StratifiedCellStream[Simplex[Int], Double], morse: MorseComplex[Simplex[Int]]) =
    val value = stream.filtrationValue
    val all = stream.iterator.toList
    def faces(b: Simplex[Int]): List[Simplex[Int]] =
      b.toList.indices.map(i => Simplex.from(b.toList.patch(i, Nil, 1))).toList
    val problems = mutable.ListBuffer.empty[String]
    for (a, b) <- morse.pairs do
      if b.dim != a.dim + 1 then problems += s"$a and $b differ by more than one dimension"
      else if !faces(b).contains(a) then problems += s"$a is not a face of $b"
      if value(a) != value(b) then problems += s"$a and $b enter at different values"
    val used = morse.pairs.keys.toList ++ morse.pairs.values.toList
    if used.distinct.size != used.size then problems += "a cell is in two pairs"
    if (used.toSet ++ morse.critical).size != all.size || used.toSet.intersect(morse.critical.toSet).nonEmpty then
      problems += "the pairs and the critical cells do not partition the cells"
    // a gradient path: from a lower member a up to its partner b and down to another lower member a' that is a face of b
    val lower = morse.pairs.keySet
    val edges = lower.map(a => a -> faces(morse.pairs(a)).filter(f => f != a && lower(f))).toMap
    val state = mutable.HashMap.empty[Simplex[Int], Int] // 1 on the path, 2 done
    def cyclic(a: Simplex[Int]): Boolean = state.get(a) match
      case Some(1) => true
      case Some(_) => false
      case None    =>
        state(a) = 1
        val found = edges(a).exists(cyclic)
        state(a) = 2
        found
    if lower.exists(cyclic) then problems += "the gradient has a cycle"
    problems.toList

  "the matching" should {
    "be an acyclic matching of the cells of one filtration value, for both strategies, on tie-heavy and generic fixtures" in {
      val failures = for
        (name, stream) <- fixtures
        strategy <- strategies
        morse = DiscreteMorse(stream, strategy)
        problem <- matchingProblems(stream, morse)
      yield s"$name $strategy: $problem"
      failures must beEmpty
    }

    "keep the Euler characteristic, and leave at least as many critical cells in each dimension as there are classes" in {
      val failures = for
        (name, stream) <- fixtures
        strategy <- strategies
        morse = DiscreteMorse(stream, strategy)
        bars = Persistence(stream, maxDimension = 2, characteristic = 3).bars
        problem <- List(
          Option.when(
            stream.iterator.toList.map(c => if c.dim % 2 == 0 then 1 else -1).sum !=
              morse.critical.map(c => if c.dim % 2 == 0 then 1 else -1).sum
          )("the Euler characteristic changed"),
          Option.when((0 to 2).exists(k => morse.criticalCount(k) < bars.count(b => b.dim == k && b.death.isInfinite)))(
            "fewer critical cells than essential classes"
          )
        ).flatten
      yield s"$name $strategy: $problem"
      failures must beEmpty
    }

    "leave a tied complex almost nothing: the hollow triangle keeps a vertex and an edge, the filled one a vertex" in {
      val vertices = (0 to 2).map(i => (0.0, Simplex(i)))
      val edges = List(Simplex(0, 1), Simplex(1, 2), Simplex(0, 2)).map(e => (0.0, e))
      val hollow = explicit((vertices ++ edges)*)
      val filled = explicit((vertices ++ edges :+ (0.0, Simplex(0, 1, 2)))*)
      val failures = for
        strategy <- strategies
        (stream, expected) <- List(hollow -> List(1, 1, 0), filled -> List(1, 0, 0))
        found = (0 to 2).map(DiscreteMorse(stream, strategy).criticalCount).toList
        if found != expected
      yield s"$strategy: $found, not $expected"
      failures must beEmpty
    }

    "pair nothing when every cell enters at its own value" in {
      val line = explicit((0.0, Simplex(0)), (0.0, Simplex(1)), (1.0, Simplex(0, 1)))
      DiscreteMorse(line).pairs must beEmpty
    }
  }

  "the Morse boundary" should {
    def squaresToZero(coefficients: Coefficients) =
      import coefficients.given
      val fr = summon[coefficients.C is Field]
      for
        (name, stream) <- fixtures
        strategy <- strategies
        morse = DiscreteMorse(stream, strategy)
        tau <- morse.critical
        total = mutable.HashMap.empty[Simplex[Int], coefficients.C]
        _ =
          for
            (sigma, k) <- morse.boundary[coefficients.C](tau)
            (rho, k2) <- morse.boundary[coefficients.C](sigma)
          do total(rho) = fr.plus(total.getOrElse(rho, fr.zero), fr.times(k, k2))
        if total.values.exists(v => !fr.isEqual(v, fr.zero))
      yield s"$name $strategy $tau"

    "square to zero over Z/3 and Z/17, and over the rationals" in {
      (squaresToZero(Coefficients(3)) must beEmpty) and (squaresToZero(Coefficients(17)) must beEmpty) and
        (squaresToZero(Coefficients.Rationals) must beEmpty)
    }

    "go to critical cells of one dimension less that entered no later" in {
      val coefficients = Coefficients(17)
      import coefficients.given
      val failures = for
        (name, stream) <- fixtures
        strategy <- strategies
        morse = DiscreteMorse(stream, strategy)
        tau <- morse.critical
        (sigma, _) <- morse.boundary[coefficients.C](tau)
        if !morse.isCritical(sigma) || sigma.dim != tau.dim - 1 || stream.filtrationValue(sigma) > stream
          .filtrationValue(tau)
      yield s"$name $strategy: $sigma in the boundary of $tau"
      failures must beEmpty
    }
  }

  "the lift" should {
    // The lift of a critical cell is a chain of the original complex, and lifting commutes with the boundary:
    // the original boundary of the lift of tau is the lift of tau's Morse boundary. Checked with the boundary of the
    // original cells alone, so a wrong sign or a missing term of the gradient flow shows.
    def commutes(coefficients: Coefficients) =
      import coefficients.given
      val fr = summon[coefficients.C is Field]
      val simplices = summon[Simplex[Int] is OrderedCell]
      for
        (name, stream) <- fixtures
        strategy <- strategies
        morse = DiscreteMorse(stream, strategy)
        tau <- morse.critical
        if tau.dim > 0
        total = mutable.HashMap.empty[Simplex[Int], coefficients.C]
        _ =
          def accumulate(cell: Simplex[Int], k: coefficients.C): Unit =
            total(cell) = fr.plus(total.getOrElse(cell, fr.zero), k)
          for (c, k) <- morse.lift[coefficients.C](tau); (face, k2) <- simplices.boundary(c)[coefficients.C] do
            accumulate(face, fr.times(k, k2))
          for (sigma, k) <- morse.boundary[coefficients.C](tau); (c, k2) <- morse.lift[coefficients.C](sigma) do
            accumulate(c, fr.negate(fr.times(k, k2)))
        if total.values.exists(v => !fr.isEqual(v, fr.zero))
      yield s"$name $strategy $tau"

    "commute with the boundary, over Z/3, Z/17 and the rationals" in {
      (commutes(Coefficients(3)) must beEmpty) and (commutes(Coefficients(17)) must beEmpty) and
        (commutes(Coefficients.Rationals) must beEmpty)
    }

    "be nontrivial somewhere: some critical cell lifts to more than itself" in {
      val coefficients = Coefficients(17)
      import coefficients.given
      val longer = for
        (_, stream) <- fixtures
        strategy <- strategies
        morse = DiscreteMorse(stream, strategy)
        tau <- morse.critical
        if morse.lift[coefficients.C](tau).size > 1
      yield tau
      longer must not(beEmpty)
    }
  }

  "the persistent homology of the Morse complex" should {
    "have the bars of the original complex, over Z/2, Z/3 and Z/5, with both strategies and two engines" in {
      val failures = for
        (name, stream) <- fixtures
        strategy <- strategies
        p <- List(2, 3, 5)
        engine <- List(Engine.Cohomology, Engine.Naive)
        morse = DiscreteMorse(stream, strategy)
        original = Persistence(stream, maxDimension = 2, characteristic = p, engine = engine)
        reduced = Persistence(morse.stream, maxDimension = 2, characteristic = p, engine = engine)
        if rounded(original.triples) != rounded(reduced.triples)
      yield s"$name $strategy p=$p $engine: ${rounded(original.triples)} against ${rounded(reduced.triples)}"
      failures.take(1) must beEmpty
    }

    "reach the cohomology engine with a dimension that has no critical cell under dimensions that have, which only a Morse complex may" in {
      val triangleWithoutEdges =
        explicit((0.0, Simplex(0)), (0.0, Simplex(1)), (0.0, Simplex(2)), (1.0, Simplex(0, 1, 2)))
      val gapped = for
        (name, stream) <- fixtures
        strategy <- strategies
        morse = DiscreteMorse(stream, strategy)
        counts = (0 to 3).map(morse.criticalCount)
        if (0 until 3).exists(k => counts(k) == 0 && counts(k + 1) > 0)
      yield (name, strategy, morse)
      val reached = gapped.map { (_, _, morse) =>
        rounded(Persistence(morse.stream, maxDimension = 2, engine = Engine.Cohomology).triples) ==
          rounded(Persistence(morse.stream, maxDimension = 2, engine = Engine.Naive).triples)
      }
      (gapped must not(beEmpty)) and (reached.forall(identity) must beTrue) and
        (Persistence(triangleWithoutEdges, maxDimension = 2, engine = Engine.Cohomology) must throwAn[
          IllegalArgumentException
        ])
    }

    "be computed on far fewer cells where the filtration has ties" in {
      val stream = fixtures.head._2
      val morse = DiscreteMorse(stream)
      morse.critical.size must beLessThan(stream.iterator.size / 2)
    }
  }

  "a lifted representative" should {
    "be a cycle of the original complex, supported on cells that entered by its birth" in {
      val failures = for
        (name, stream) <- fixtures
        strategy <- strategies
        morse = DiscreteMorse(stream, strategy)
        diagram = Persistence(
          morse.stream,
          maxDimension = 2,
          characteristic = 3,
          engine = Engine.Naive,
          representatives = Representatives.Cycles
        )
        problem <-
          import diagram.given
          val fr = summon[diagram.Coefficient is Field]
          for
            bar <- diagram.bars
            lifted = morse.lift(bar.representative)
            image = mutable.HashMap.empty[Simplex[Int], diagram.Coefficient]
            _ =
              for
                (cell, k) <- lifted.terms;
                (face, k2) <- summon[Simplex[Int] is OrderedCell].boundary(cell)[diagram.Coefficient]
              do image(face) = fr.plus(image.getOrElse(face, fr.zero), fr.times(k, k2))
            problem <- List(
              Option.when(image.values.exists(v => !fr.isEqual(v, fr.zero)))("not a cycle"),
              Option.when(lifted.terms.exists((cell, _) => stream.filtrationValue(cell) > bar.birth))(
                "support after the birth"
              )
            ).flatten
          yield s"bar ${bar.dim}: ${bar.birth}: $problem"
      yield s"$name $strategy: $problem"
      failures.take(2) must beEmpty
    }
  }
end DiscreteMorseSpec
