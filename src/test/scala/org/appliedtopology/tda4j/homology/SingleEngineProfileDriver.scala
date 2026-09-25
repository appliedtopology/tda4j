package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.io.{given, *}

/** Test-scope driver, NOT a scratch file (unlike this arc's earlier `PackedProfileDriver.scala`/ `ProfileDriver.scala`,
  * each written fresh and deleted before its session ended) -- kept alongside `RipserPaperBenchmarkSpec.scala` as the
  * subprocess target a real time-and-memory comparison against `ripser.cpp` needs: one engine, one JVM process, no
  * sbt/timeout/daemon-thread machinery, invoked directly via
  * `java -Xmx<N> -cp $CP ... SingleEngineProfileDriver <engine> <format> <dataFile> <maxDim> [threshold] [trials]`
  * (optionally wrapped in `/usr/bin/time -l`, or with `-XX:StartFlightRecording=...` for JFR) -- see
  * `.claude/WORKLOG-ripser-profiling.md`'s cursor-redesign session for why this needs to be a real subprocess rather
  * than another `-DpackedOnly`-style in-process sbt spec: a memory measurement specifically needs one engine per OS
  * process, and `RipserPaperBenchmarkSpec`'s shared-JVM, per-cell-timeout harness has no cooperative cancellation, so a
  * case that runs long there keeps consuming CPU/heap in the background rather than actually stopping (see that spec's
  * own doc). Running the SAME case here instead gets a clean, single-purpose process a profiler/timeout can be pointed
  * at directly, and (unlike the sbt-hosted spec) can be left running for as long as needed with no per-cell budget at
  * all -- just don't wrap it in a shell `timeout` unless you actually want it killed.
  *
  * `engine` is `"sortedset"` (`RipserCohomologyContext`) or `"packed"` (`PackedRipserCohomologyContext`). `format` is
  * `"point-cloud"` (whitespace/comma-separated coordinates, one point per line -- `dataFile` becomes an
  * `EuclideanMetricSpace`) or `"distance"` (a full, not just lower-triangular, whitespace/comma-separated distance
  * matrix -- `dataFile` becomes an `ExplicitMetricSpace`; this is what `fractal-r`'s own data file is, per
  * `RipserPaperBenchmarkSpec`'s `DataCase`). `threshold` is either the literal string `none` (this engine's own
  * `minimumEnclosingRadius` default, matching real ripser's own no-`--threshold` behavior) or a `Double` (e.g. `1.8`
  * for `o3_1024`, matching the paper's own `--threshold` flag for that case) -- both cases in
  * `RipserPaperBenchmarkSpec.scala`'s `cases` Seq document their own exact `format`/`threshold` pairing, copy from
  * there rather than re-deriving it. `trials` (default 1; the paper's own harder cases are typically not worth
  * re-running for a median) controls only the in-process wall-clock median -- a memory measurement needs a fresh
  * process per sample instead, driven externally by whatever wraps this.
  *
  * Prints `substitutionCount`/`totalSimplexCount` (both engines expose them, see their own doc comments in
  * `Homology.scala`/`PackedRipserCohomology.scala`) alongside timing -- added specifically to let a profiling run on
  * an anomalous case (e.g. `o3_1024`'s S/pack ratio collapsing to ~1.7x when every other case shows 15-45x, or
  * `fractal-r` timing out for the packed engine while `RipserCohomologyContext` itself finishes) report the
  * apparent-pairs hit rate alongside the timing, not just the wall-clock number alone.
  */
object SingleEngineProfileDriver:
  def main(args: Array[String]): Unit =
    val engine = args(0)
    val format = args(1)
    val dataFile = args(2)
    val maxDim = args(3).toInt
    val thresholdArg = if args.length > 4 then args(4) else "none"
    val trials = if args.length > 5 then args(5).toInt else 1

    val threshold: Option[Double] = if thresholdArg == "none" then None else Some(thresholdArg.toDouble)

    val IntMod2 = new FiniteField(2)
    import IntMod2.Fp
    import IntMod2.given

    def buildMetricSpace(): FiniteMetricSpace[Int] = format match
      case "point-cloud" => EuclideanMetricSpace(CSV.readPointCloud(dataFile))
      case "distance"    => ExplicitMetricSpace(CSV.readFullDistanceMatrix(dataFile).map(_.toIndexedSeq).toIndexedSeq)
      case other         => throw new IllegalArgumentException(s"unknown format $other -- use point-cloud or distance")

    val times = (1 to trials).map { _ =>
      val ms = buildMetricSpace()
      val t0 = System.nanoTime()
      val (totalSimplices, substCount, barCounts) = engine match
        case "sortedset" =>
          val ctx = RipserCohomologyContext[Fp](ms, maxDim, maxFiltrationValue = threshold)
          val bars = ctx.persistentCohomology()
          (ctx.totalSimplexCount, ctx.substitutionCount, bars.groupBy(_.dim).view.mapValues(_.size).toMap)
        case "packed" =>
          val ctx = PackedRipserCohomologyContext[Fp](ms, maxDim, maxFiltrationValue = threshold)
          val bars = ctx.persistentCohomology()
          (ctx.totalSimplexCount, ctx.substitutionCount, bars.groupBy(_.dim).view.mapValues(_.size).toMap)
        case other => throw new IllegalArgumentException(s"unknown engine $other -- use sortedset or packed")
      val elapsedMs = (System.nanoTime() - t0) / 1e6
      (elapsedMs, totalSimplices, substCount, barCounts)
    }

    val sortedTimes = times.map(_._1).sorted
    val medianMs = sortedTimes(sortedTimes.size / 2)
    val (_, totalSimplices, substCount, barCounts) = times.last
    println(s"engine=$engine format=$format dataFile=$dataFile maxDim=$maxDim threshold=$thresholdArg trials=$trials")
    println(f"medianMs=$medianMs%.1f allTimes=${times.map(t => f"${t._1}%.1f").mkString(",")}")
    println(s"totalSimplexCount=$totalSimplices substitutionCount=$substCount bars=$barCounts")
