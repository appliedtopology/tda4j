package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.specs2.mutable
import org.specs2.main.Arguments

import scala.util.Random

/** Measures `EdgeCollapse`'s actual payoff (`.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 5's own
  * explicit instruction: "count actual cells-removed-vs-total on a real VR fixture... measure construction and
  * reduction separately... before claiming any speedup") -- construction and reduction timed separately, exactly
  * like `EngineComparisonBenchmarkSpec` already does for the same reason (a collapse-derived speedup, if any, is
  * expected on REDUCTION, not necessarily on ENUMERATION -- see `WORKLOG-edge-collapse.md` for the analysis of
  * why `EnumeratingCofaceSimplexStream`'s own combinatorial-index enumeration is `Theta(C(n,d+1))` regardless of
  * how sparse the underlying graph is, so a smaller graph alone does not shrink ITS enumeration cost).
  *
  * Like `ApparentPairsBenchmarkSpec`, a profiling script, not a correctness check: prints tables, only an
  * exception is a real failure. `sbt -DrunBenchmarks=true -DminSize=... testOnly
  * org.appliedtopology.tda4j.homology.EdgeCollapseBenchmarkSpec`.
  */
class EdgeCollapseBenchmarkSpec(args: Arguments) extends mutable.Specification:
  if !args.commandLine.boolOr("runBenchmarks", false) then skipAll

  given Double is Field = Field.DoubleApproximated(1e-9)

  "Edge-collapse cell-reduction and timing benchmark" >> {
    val minSize: Int = args.commandLine.intOr("minSize", 30)
    val maxSize: Int = args.commandLine.intOr("maxSize", 70)
    val step: Int = args.commandLine.intOr("step", 20)
    val trials: Int = args.commandLine.intOr("trials", 3)
    val maxDim: Int = args.commandLine.intOr("maxDim", 2)
    val ambientDim: Int = args.commandLine.intOr("ambientDim", 3)
    val seed: Int = args.commandLine.intOr("seed", 42)

    def randomCloud(n: Int, rng: Random): FiniteMetricSpace[Int] =
      EuclideanMetricSpace(HomologyFixtures.randomCloud(n, ambientDim, rng))

    def median(times: Seq[Long]): Double = times.sorted.apply(times.size / 2) / 1e6

    def timeMs[A](thunk: => A): (A, Long) =
      val start = System.nanoTime()
      val result = thunk
      (result, System.nanoTime() - start)

    println(
      f"${"n"}%-5s${"edges before"}%-13s${"edges after"}%-12s${"kept%"}%-7s${"collapse(ms)"}%-13s" +
        f"${"build plain"}%-12s${"build coll."}%-12s${"buildSpeedup"}%-13s" +
        f"${"reduce plain"}%-13s${"reduce coll."}%-13s${"reduceSpeedup"}%-13s"
    )

    for n <- minSize to maxSize by step do
      val clouds = Seq.tabulate(trials)(i => randomCloud(n, Random(seed.toLong * 1_000_003L + n * 1009L + i)))

      var edgesBefore = 0
      var edgesAfter = 0
      val collapseMs = clouds.map { space =>
        val (collapsed, ms) = timeMs(EdgeCollapse.collapse(space))
        edgesBefore += collapsed.stats.edgesBefore
        edgesAfter += collapsed.stats.edgesAfter
        ms
      }

      // `LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(space), maxDim + 1)` caches each dimension's
      // own cell bucket internally the first time it's visited (`currentDimensionCache`/`lastDimensionCache`,
      // see `EnumeratingCofaceSimplexStream`'s own doc) -- so materializing `.iterator.toVector` ONCE (timed as
      // "build") and then calling `persistentHomology` on the SAME stream instance affords the reduction
      // algorithm's own `.iterator` calls a full cache hit, isolating "reduction" from "construction" the same
      // way `EngineComparisonBenchmarkSpec` times the two phases separately, without needing a second stream
      // type just to replay an already-materialized cell list.
      def newStream(space: FiniteMetricSpace[Int]) =
        LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(space), maxDim + 1)

      // Warmup (not measured) primes the JIT for both arms, same discipline as ApparentPairsBenchmarkSpec.
      val warmupSpace = randomCloud(n, Random(seed.toLong * 7 - n))
      val warmupPlain = newStream(warmupSpace)
      warmupPlain.iterator.toVector
      SimplicialHomologyContext[Int, Double, Double]().persistentHomology(warmupPlain).diagramAt(Double.PositiveInfinity)
      val warmupColl = newStream(EdgeCollapse.collapse(warmupSpace))
      warmupColl.iterator.toVector
      SimplicialHomologyContext[Int, Double, Double]().persistentHomology(warmupColl).diagramAt(Double.PositiveInfinity)

      val plainStreams = clouds.map(newStream)
      val collStreams = clouds.map(c => newStream(EdgeCollapse.collapse(c)))

      val buildPlainMs = plainStreams.map(s => timeMs(s.iterator.toVector)._2)
      val buildCollMs = collStreams.map(s => timeMs(s.iterator.toVector)._2)

      val reducePlainMs = plainStreams.map { s =>
        timeMs(SimplicialHomologyContext[Int, Double, Double]().persistentHomology(s).diagramAt(Double.PositiveInfinity))._2
      }
      val reduceCollMs = collStreams.map { s =>
        timeMs(SimplicialHomologyContext[Int, Double, Double]().persistentHomology(s).diagramAt(Double.PositiveInfinity))._2
      }

      val bp = median(buildPlainMs)
      val bc = median(buildCollMs)
      val rp = median(reducePlainMs)
      val rc = median(reduceCollMs)
      val keptPct = 100.0 * edgesAfter / edgesBefore

      println(
        f"$n%-5d$edgesBefore%-13d$edgesAfter%-12d$keptPct%-7.1f${median(collapseMs)}%-13.3f" +
          f"$bp%-12.3f$bc%-12.3f${bp / bc}%-13.2f" +
          f"$rp%-13.3f$rc%-13.3f${rp / rc}%-13.2f"
      )

    success
  }
