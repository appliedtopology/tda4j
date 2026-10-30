package org.appliedtopology.tda4j

import ParallelPackedCohomology.*
import scala.util.Random

/** Command line for the block-parallel experiment:
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.ParallelPackedCohomologyDriver <dataset> <n> <maxDim> <threshold|mer> <threads> [units per thread] [trials] [seed] [p] [check] [ripser-apparent] [tree]
  * }}}
  * It runs on `threads` threads (`Parallel.withThreads`).
  */
object ParallelPackedCohomologyDriver:
  def main(arguments: Array[String]): Unit =
    val check = arguments.contains("check")
    val ripserApparent = arguments.contains("ripser-apparent")
    val merge = if arguments.contains("tree") then Merge.Tree else Merge.Serial
    val args = arguments.filterNot(a => a == "check" || a == "ripser-apparent" || a == "tree")
    val dataset = args(0)
    val n = args(1).toInt
    val maxDim = args(2).toInt
    val threads = args(4).toInt
    val unitsPerThread = if args.length > 5 then args(5).toInt else 8
    val trials = if args.length > 6 then args(6).toInt else 5
    val seed = if args.length > 7 then args(7).toLong else 42L
    val p = if args.length > 8 then args(8).toInt else 17
    val pts = BlockParallelReduction.points(dataset, n, Random(seed))
    val metricSpace = EuclideanMetricSpace(pts)
    val threshold = if args(3) == "mer" then metricSpace.minimumEnclosingRadius else args(3).toDouble
    val common = java.util.concurrent.ForkJoinPool.getCommonPoolParallelism
    println(
      s"$dataset n=$n maxDim=$maxDim threshold=$threshold p=$p threads=$threads units/thread=$unitsPerThread " +
        s"(common pool parallelism $common)${
            if ripserApparent then ", apparent pairs Ripser's way" else ""
          }, merge $merge"
    )
    def once(): (Long, Result) =
      val t0 = System.nanoTime()
      val dist = new Array[Double](n * n)
      for i <- 0 until n; j <- 0 until n do dist(i * n + j) = metricSpace.distance(i, j)
      val space = Space(n, (i, j) => dist(i * n + j), threshold, maxDim, p, ripserApparent)
      val r = Parallel.withThreads(threads)(persistence(space, threads, unitsPerThread, cocycles = true, merge = merge))
      (System.nanoTime() - t0, r)
    val runs = (1 to trials).map(_ => once())
    val times = runs.map(_._1).sorted
    val last = runs.last._2
    println(
      f"  bars ${last.bars.size}, total median ${times(times.size / 2) / 1e6}%.1f ms, min ${times.head / 1e6}%.1f ms"
    )
    last.degrees.foreach(d => println("  " + d.stats))
    for d <- 1 to maxDim do
      println(
        s"  degree $d: columns deferred by unit (oldest unit first): ${last.degrees(d).stats.deferredByUnit.mkString(" ")}"
      )
    // Medians over the trials, per degree: where the time goes (ms).
    def median(xs: Seq[Long]): Double = xs.sorted.apply(xs.size / 2) / 1e6
    for d <- 0 to maxDim do
      val all = runs.map(_._2.degrees(d).stats)
      val tree =
        if all.head.treeBusy.isEmpty then ""
        else
          val depths = all.map(_.treeBusy.length).max
          "; tree by depth (busy/longest): " + (0 until depths)
            .map(k =>
              f"$k: ${median(all.map(a => a.treeBusy.lift(k).getOrElse(0L)))}%.1f/${median(all.map(a => a.treeLongest.lift(k).getOrElse(0L)))}%.1f"
            )
            .mkString(" ")
      println(
        f"  median degree $d: assemble ${median(all.map(_.nanosAssemble))}%.1f, phase 1 ${median(all.map(_.nanos1))}%.1f, merge ${median(all.map(_.nanosMerge))}%.1f$tree"
      )
    if check then
      val packed = BlockParallelReduction.packedBars(metricSpace, maxDim, threshold, p)
      println(s"  same bars as the packed Ripser engine: ${last.bars == packed}")
