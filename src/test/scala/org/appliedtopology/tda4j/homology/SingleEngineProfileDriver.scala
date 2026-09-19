package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}

import scala.io.Source

/** Test-scope driver, NOT a scratch file (unlike this arc's earlier `PackedProfileDriver.scala`/
  * `ProfileDriver.scala`, each written fresh and deleted before its session ended) -- kept alongside
  * `RipserPaperBenchmarkSpec.scala` as the subprocess target a real time-and-memory comparison against `ripser.cpp`
  * needs: one engine, one JVM process, no sbt/timeout/daemon-thread machinery, invoked directly via
  * `java -Xmx<N> -cp $CP ... SingleEngineProfileDriver <engine> <dataFile> <maxDim> [trials]` (optionally wrapped in
  * `/usr/bin/time -l` for peak RSS) -- see `.claude/WORKLOG-ripser-profiling.md`'s cursor-redesign session for why
  * this needs to be a real subprocess rather than another `-DpackedOnly`-style in-process sbt spec: a memory
  * comparison specifically needs one engine per OS process, which `RipserPaperBenchmarkSpec`'s shared-JVM,
  * per-cell-timeout harness doesn't give. `engine` is `"sortedset"` (`RipserCohomologyContext`) or `"packed"`
  * (`PackedRipserCohomologyContext`); `trials` (default 3) controls only the in-process wall-clock median -- a
  * memory measurement needs a fresh process per sample instead, driven externally by whatever wraps this.
  */
object SingleEngineProfileDriver:
  def loadPointCloud(path: String): Array[Array[Double]] =
    val src = Source.fromFile(path)
    try
      src
        .getLines()
        .filter(_.trim.nonEmpty)
        .map(_.trim.split("[\\s,]+").map(_.toDouble))
        .toArray
    finally src.close()

  def main(args: Array[String]): Unit =
    val engine = args(0)
    val dataFile = args(1)
    val maxDim = args(2).toInt
    val trials = if args.length > 3 then args(3).toInt else 3

    val IntMod2 = new FiniteField(2)
    import IntMod2.Fp
    import IntMod2.given

    val points = loadPointCloud(dataFile)

    val times = (1 to trials).map { _ =>
      val ms = EuclideanMetricSpace(points)
      val t0 = System.nanoTime()
      val (totalSimplices, barCounts) = engine match
        case "sortedset" =>
          val ctx = RipserCohomologyContext[Fp](ms, maxDim)
          val bars = ctx.persistentCohomology()
          (ctx.totalSimplexCount, bars.groupBy(_.dim).view.mapValues(_.size).toMap)
        case "packed" =>
          val ctx = PackedRipserCohomologyContext[Fp](ms, maxDim)
          val bars = ctx.persistentCohomology()
          (ctx.totalSimplexCount, bars.groupBy(_.dim).view.mapValues(_.size).toMap)
        case other => throw new IllegalArgumentException(s"unknown engine $other")
      val elapsedMs = (System.nanoTime() - t0) / 1e6
      (elapsedMs, totalSimplices, barCounts)
    }

    val sortedTimes = times.map(_._1).sorted
    val medianMs = sortedTimes(sortedTimes.size / 2)
    val (_, totalSimplices, barCounts) = times.last
    println(s"engine=$engine dataFile=$dataFile maxDim=$maxDim trials=$trials")
    println(f"medianMs=$medianMs%.1f allTimes=${times.map(t => f"${t._1}%.1f").mkString(",")}")
    println(s"totalSimplexCount=$totalSimplices bars=$barCounts")
