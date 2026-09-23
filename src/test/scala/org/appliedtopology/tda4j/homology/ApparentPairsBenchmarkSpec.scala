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

/** Benchmarks `RipserCohomologyContext`'s apparent-pairs shortcut (`zeroApparentCofacet`, wired into
  * `persistentCohomology` -- see WORKLOG-cohomology.md's "Apparent pairs: resolved" section for the full derivation)
  * against the exact same engine with the shortcut disabled via `useApparentPairs = false`. Timing the same algorithm
  * with one optimization toggled, rather than a different engine entirely, isolates this specific change's effect from
  * any unrelated difference between engines.
  *
  * Like `ProfilingSpec`, this is a profiling script, not a correctness check: it reads specs2 command-line args and
  * prints a timing table rather than asserting behavior. A failure here (an exception, not a slow number) is the only
  * thing worth treating as a real problem -- the timings themselves are informational. Defaults are kept small and fast
  * on purpose so this stays cheap under plain `sbt test`/CI; pass larger values for an actual benchmarking run, e.g.:
  *
  * {{{
  * sbt -DminSize=12 -DmaxSize=24 -Dstep=4 -Dtrials=7 "testOnly org.appliedtopology.tda4j.ApparentPairsBenchmarkSpec"
  * }}}
  *
  * (the `-D` flags go to the `sbt` JVM itself, before the `testOnly` command -- specs2's own `--` argument syntax is
  * for named specs2 arguments, not these, and silently falls back to defaults if used here.)
  *
  * The original measurement this spec reproduces (1.35x-1.8x, growing with n, on n=12-20 random point clouds) was done
  * ad hoc during development and not preserved -- this file is the durable, rerunnable replacement for that one-off
  * measurement.
  */
class ApparentPairsBenchmarkSpec(args: Arguments) extends mutable.Specification:
  // Skipped by default so plain `sbt test` never pays for this -- pass -DrunBenchmarks=true to actually run it
  // (see CLAUDE.md's "Commands" section). One shared flag gates every *BenchmarkSpec/ProfilingSpec in this package.
  if !args.commandLine.boolOr("runBenchmarks", false) then skipAll
  "Apparent-pairs optimization benchmark" >> {
    val minSize: Int = args.commandLine.intOr("minSize", 8)
    val maxSize: Int = args.commandLine.intOr("maxSize", 12)
    val step: Int = args.commandLine.intOr("step", 2)
    val trials: Int = args.commandLine.intOr("trials", 3)
    val maxDim: Int = args.commandLine.intOr("maxDim", 2)
    val ambientDim: Int = args.commandLine.intOr("ambientDim", 3)
    val seed: Int = args.commandLine.intOr("seed", 42)

    given Double is Field = Field.DoubleApproximated(1e-9)

    def randomCloud(n: Int, rng: Random): FiniteMetricSpace[Int] =
      EuclideanMetricSpace(HomologyFixtures.randomCloud(n, ambientDim, rng))

    def timeOne(metricSpace: FiniteMetricSpace[Int], useApparentPairs: Boolean): Long =
      val ctx = RipserCohomologyContext[Double](metricSpace, maxDim, useApparentPairs)
      val start = System.nanoTime()
      ctx.persistentCohomology()
      System.nanoTime() - start

    def median(times: Seq[Long]): Double =
      times.sorted.apply(times.size / 2) / 1e6

    println(f"${"n"}%-6s${"on (ms)"}%-14s${"off (ms)"}%-14s${"speedup"}%-8s")

    for n <- minSize to maxSize by step do
      // Same clouds are timed both with and against the shortcut, so JIT/GC noise affects both arms
      // equally; a separate warmup cloud (not included in the measured trials) primes the JIT for
      // both code paths before any measured run.
      val clouds = Seq.tabulate(trials)(i => randomCloud(n, Random(seed.toLong * 1_000_003L + n * 1009L + i)))
      val warmupCloud = randomCloud(n, Random(seed.toLong * 7 - n))
      timeOne(warmupCloud, useApparentPairs = true)
      timeOne(warmupCloud, useApparentPairs = false)

      val onMedian = median(clouds.map(c => timeOne(c, useApparentPairs = true)))
      val offMedian = median(clouds.map(c => timeOne(c, useApparentPairs = false)))

      println(f"$n%-6d$onMedian%-14.3f$offMedian%-14.3f${offMedian / onMedian}%-8.2fx")

    success
  }
