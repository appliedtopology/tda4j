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

/** Benchmarks `RipserCohomologyContext`'s `maxFiltrationValue` threshold (Session 2's sparse-Rips enumeration, see
  * WORKLOG-lazy-enumeration.md) against the same engine run untruncated on the same point cloud. Timing the same
  * algorithm with one parameter toggled, rather than a different engine entirely, isolates this specific change's
  * effect from any unrelated difference between engines -- same rationale as `ApparentPairsBenchmarkSpec`.
  *
  * Like `ProfilingSpec`/`ApparentPairsBenchmarkSpec`, this is a profiling script, not a correctness check: it prints a
  * timing table rather than asserting behavior. A failure here (an exception, not a slow number) is the only thing
  * worth treating as a real problem. Defaults are kept small and fast so this stays cheap under plain `sbt test`/CI;
  * pass larger values for an actual benchmarking run, e.g.:
  *
  * {{{
  * sbt -DminSize=40 -DmaxSize=100 -Dstep=20 -Dtrials=5 "testOnly org.appliedtopology.tda4j.SparseRipsBenchmarkSpec"
  * }}}
  *
  * The threshold is set relative to `n` (roughly the scale at which a uniform-random point in the unit square has a
  * handful of neighbors, `c / sqrt(n)`), not a fixed value -- a fixed threshold would make the complex progressively
  * DENSER as `n` grows (more points within any fixed radius), defeating the point of measuring sparse behavior at
  * increasing `n`.
  */
class SparseRipsBenchmarkSpec(args: Arguments) extends mutable.Specification:
  // Skipped by default so plain `sbt test` never pays for this -- pass -DrunBenchmarks=true to actually run it
  // (see CLAUDE.md's "Commands" section). One shared flag gates every *BenchmarkSpec/ProfilingSpec in this package.
  if !args.commandLine.boolOr("runBenchmarks", false) then skipAll
  "Sparse-threshold benchmark" >> {
    val minSize: Int = args.commandLine.intOr("minSize", 20)
    val maxSize: Int = args.commandLine.intOr("maxSize", 40)
    val step: Int = args.commandLine.intOr("step", 10)
    val trials: Int = args.commandLine.intOr("trials", 3)
    val maxDim: Int = args.commandLine.intOr("maxDim", 2)
    val seed: Int = args.commandLine.intOr("seed", 42)
    val thresholdScale: Double = args.commandLine.doubleOr("thresholdScale", 2.5)
    // Also lets this same spec answer a SEPARATE question from the sparse-vs-dense one above:
    // `memoizeFiltrationValue`'s own cost, previously unmeasured (WORKLOG-lazy-enumeration.md flags this
    // explicitly). Pass -Dmemoize=true and compare the "dense (ms)" column against a -Dmemoize=false run
    // at the same n -- the sparse column isn't the relevant one for that question, since the enumeration
    // path never calls the memoized filtrationValue at all regardless of this flag (see
    // `insertionDiameter`'s doc); it's `cohomologyOrdering`'s use inside `Chain.reduceBy`'s reduction
    // machinery -- consulted on every SortedMap comparison, present regardless of threshold -- that this
    // flag actually controls the cost of.
    val memoize: Boolean = args.commandLine.boolOr("memoize", false)

    given Double is Field = Field.DoubleApproximated(1e-9)

    def randomCloud(n: Int, rng: Random): FiniteMetricSpace[Int] =
      EuclideanMetricSpace(HomologyFixtures.randomCloud(n, 2, rng))

    def timeAndCount(metricSpace: FiniteMetricSpace[Int], maxFiltrationValue: Double): (Long, Int) =
      val ctx =
        RipserCohomologyContext[Double](
          metricSpace,
          maxDim,
          maxFiltrationValue = Some(maxFiltrationValue),
          memoizeFiltrationValue = memoize
        )
      val start = System.nanoTime()
      ctx.persistentCohomology()
      (System.nanoTime() - start, ctx.totalSimplexCount)

    def median(times: Seq[Long]): Double =
      times.sorted.apply(times.size / 2) / 1e6

    // Also reporting totalSimplexCount for both arms, not just wall-clock: a threshold produces a SMALLER
    // complex, not just a differently-enumerated one, so a raw time ratio conflates "the new mechanism
    // does less wasted work" with "it's simply a smaller problem." Printing counts lets a reader see how
    // much of the speedup is complex-size reduction (expected, and the actual point of a threshold) versus
    // enumeration-mechanism overhead per simplex (what this session's rewrite specifically targeted).
    println(
      f"${"n"}%-6s${"sparse (ms)"}%-14s${"dense (ms)"}%-14s${"speedup"}%-9s${"sparse #"}%-10s${"dense #"}%-10s${"size ratio"}%-10s"
    )

    for n <- minSize to maxSize by step do
      val threshold = thresholdScale / math.sqrt(n.toDouble)
      val clouds = Seq.tabulate(trials)(i => randomCloud(n, Random(seed.toLong * 1_000_003L + n * 1009L + i)))
      val warmupCloud = randomCloud(n, Random(seed.toLong * 7 - n))
      timeAndCount(warmupCloud, threshold)
      timeAndCount(warmupCloud, Double.PositiveInfinity)

      val sparseResults = clouds.map(c => timeAndCount(c, threshold))
      val denseResults = clouds.map(c => timeAndCount(c, Double.PositiveInfinity))
      val sparseMedian = median(sparseResults.map(_._1))
      val denseMedian = median(denseResults.map(_._1))
      val sparseCount = sparseResults.map(_._2).sorted.apply(sparseResults.size / 2)
      val denseCount = denseResults.map(_._2).sorted.apply(denseResults.size / 2)

      println(
        f"$n%-6d$sparseMedian%-14.3f$denseMedian%-14.3f${denseMedian / sparseMedian}%-9.2fx$sparseCount%-10d$denseCount%-10d${denseCount.toDouble / sparseCount}%-10.2f"
      )

    success
  }
