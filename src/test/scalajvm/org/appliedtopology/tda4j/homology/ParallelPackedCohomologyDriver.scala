package org.appliedtopology.tda4j

import ParallelPackedCohomology.*
import scala.util.Random

/** Command line for the block-parallel experiment:
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.ParallelPackedCohomologyDriver <dataset> <n> <maxDim> <threshold|mer> <threads> [units per thread] [trials] [seed] [p] [check] [ripser-apparent] [ties=<order>] [order=<direction>]
  * }}}
  * It runs on `threads` threads (`Parallel.withThreads`). `ties` is an `EdgeTies` name (`lexicographic`, the default,
  * `reverse-lexicographic`, `colexicographic`, `reverse-colexicographic`, `shuffled`); `order` a `BlockOrder` name
  * (`larger-index-first`, the default, `smaller-index-first`). Besides the bars it prints each degree's apparent pairs,
  * pivots cleared by the degree below and columns left to reduce, and a digest of the bars.
  */
object ParallelPackedCohomologyDriver:
  def main(arguments: Array[String]): Unit =
    val check = arguments.contains("check")
    val ripserApparent = arguments.contains("ripser-apparent")
    val ties = arguments
      .find(_.startsWith("ties="))
      .map(a => EdgeTies.parse(a.stripPrefix("ties=")))
      .getOrElse(EdgeTies.Lexicographic)
    val blockOrder = arguments
      .find(_.startsWith("order="))
      .map(a => BlockOrder.parse(a.stripPrefix("order=")))
      .getOrElse(BlockOrder.LargerIndexFirst)
    val args = arguments.filterNot(a =>
      a == "check" || a == "ripser-apparent" || a.startsWith("ties=") || a.startsWith("order=")
    )
    val dataset = args(0)
    val size = args(1).toInt // the number of points, or the side of a lattice
    val maxDim = args(2).toInt
    val threads = args(4).toInt
    val unitsPerThread = if args.length > 5 then args(5).toInt else 8
    val trials = if args.length > 6 then args(6).toInt else 5
    val seed = if args.length > 7 then args(7).toLong else 42L
    val p = if args.length > 8 then args(8).toInt else 17
    val pts = BlockParallelReduction.points(dataset, size, Random(seed))
    val n = pts.length
    val metricSpace = EuclideanMetricSpace(pts)
    val threshold = if args(3) == "mer" then metricSpace.minimumEnclosingRadius else args(3).toDouble
    val common = java.util.concurrent.ForkJoinPool.getCommonPoolParallelism
    println(
      s"$dataset n=$n maxDim=$maxDim threshold=$threshold p=$p threads=$threads units/thread=$unitsPerThread " +
        s"(common pool parallelism $common)${if ripserApparent then ", apparent pairs Ripser's way" else ""}, " +
        s"edge ties ${ties.name}, block order ${blockOrder.name}"
    )
    def once(): (Long, Result) =
      val t0 = System.nanoTime()
      val dist = new Array[Double](n * n)
      for i <- 0 until n; j <- 0 until n do dist(i * n + j) = metricSpace.distance(i, j)
      val space = Space(
        n,
        (i, j) => dist(i * n + j),
        threshold,
        maxDim,
        p,
        ripserApparent,
        edgeTies = ties,
        blockOrder = blockOrder
      )
      val r = Parallel.withThreads(threads)(persistence(space, threads, unitsPerThread, cocycles = true))
      (System.nanoTime() - t0, r)
    val runs = (1 to trials).map(_ => once())
    val times = runs.map(_._1).sorted
    val last = runs.last._2
    println(
      f"  bars ${last.bars.size}, total median ${times(times.size / 2) / 1e6}%.1f ms, min ${times.head / 1e6}%.1f ms"
    )
    last.degrees.foreach(d => println("  " + d.stats))
    last.degrees.foreach { d =>
      val s = d.stats
      println(
        f"  work degree ${d.degree}: columns ${s.columns}%,d apparent ${s.apparent}%,d cleared ${s.cleared}%,d " +
          f"left to reduce ${s.columns - s.cleared - s.apparent}%,d additions ${s.adds1 + s.adds2a + s.adds2b}%,d"
      )
    }
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(last.bars.map(b => s"${b._1}\t${b._2}\t${b._3}").mkString("\n").getBytes("UTF-8"))
      .take(8)
      .map(b => f"$b%02x")
      .mkString
    println(s"  barsDigest=$digest")
    if check then
      val packed = BlockParallelReduction.packedBars(metricSpace, maxDim, threshold, p)
      println(s"  same bars as the packed Ripser engine: ${last.bars == packed}")
