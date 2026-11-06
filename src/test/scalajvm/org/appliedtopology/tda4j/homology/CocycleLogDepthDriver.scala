package org.appliedtopology.tda4j

import ParallelPackedCohomology.*
import scala.collection.mutable
import scala.util.Random

/** How deep are the reduction logs of `Engine.ParallelRipser`, and how much does `cocycle` hold while it expands one
  * (#287)? For every bar of a cloud this expands the bar's cocycle as `ParallelPackedCohomology.cocycle` does (a copy
  * of its loop with counters, checked against it) and reports the distribution, per degree, of the length of the
  * column's own log, the longest chain of substitutions under it, the columns the memo holds at the end and the terms
  * in all of them (the quantity that grows with the square of the chain), against the cocycle's own size.
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.CocycleLogDepthDriver <circle|torus|sphere|cube<d>> <n> <maxDim> <threshold> [threads] [seed] [p] [noise]
  * }}}
  */
object CocycleLogDepthDriver:
  final case class Stats(logLength: Int, depth: Int, memoColumns: Int, memoTerms: Long, size: Int)

  def circle(n: Int, noise: Double, rng: Random): Array[Array[Double]] =
    Array.fill(n) {
      val t = 2 * math.Pi * rng.nextDouble()
      Array(math.cos(t) + noise * rng.nextGaussian(), math.sin(t) + noise * rng.nextGaussian())
    }

  /** The loop of `cocycle`, counting; returns the cocycle and the statistics of the expansion of `column`. */
  def expand(degree: Degree, p: Int, column: Long): (Map[Long, Int], Stats) =
    val memo = mutable.LongMap.empty[Map[Long, Int]]
    val depth = mutable.LongMap.empty[Int]
    val pending = mutable.Stack((column, false))
    while pending.nonEmpty do
      val (c, expanded) = pending.pop()
      if !memo.contains(c) then
        degree.logs.get(c) match
          case None =>
            memo(c) = Map(c -> 1)
            depth(c) = 0
          case Some(log) if !expanded =>
            pending.push((c, true))
            val added = log.columns
            var i = added.length - 1
            while i >= 0 do
              if !memo.contains(added(i)) then pending.push((added(i), false))
              i -= 1
          case Some(log) =>
            val acc = mutable.LongMap(c -> 1)
            val (added, factors) = (log.columns, log.multipliers)
            var i = 0
            var d = 0
            while i < added.length do
              for (e, x) <- memo(added(i)) do acc(e) = (acc.getOrElse(e, 0) + factors(i) * x) % p
              d = math.max(d, depth(added(i)))
              i += 1
            memo(c) = acc.filter(_._2 != 0).toMap
            depth(c) = d + 1
    val result = memo(column)
    val own = degree.logs.get(column).map(_.columns.length).getOrElse(0)
    (result, Stats(own, depth(column), memo.size, memo.valuesIterator.map(_.size.toLong).sum, result.size))

  def percentile(xs: Seq[Long], q: Double): Long = if xs.isEmpty then 0L else xs.sorted.apply(((xs.size - 1) * q).toInt)

  def main(args: Array[String]): Unit =
    val dataset = args(0)
    val n = args(1).toInt
    val maxDim = args(2).toInt
    val threshold = args(3).toDouble
    val threads = if args.length > 4 then args(4).toInt else 4
    val seed = if args.length > 5 then args(5).toLong else 42L
    val p = if args.length > 6 then args(6).toInt else 17
    val noise = if args.length > 7 then args(7).toDouble else 0.05
    val rng = Random(seed)
    val pts = if dataset == "circle" then circle(n, noise, rng) else BlockParallelReduction.points(dataset, n, rng)
    val metricSpace = EuclideanMetricSpace(pts)
    val dist = new Array[Double](n * n)
    for i <- 0 until n; j <- 0 until n do dist(i * n + j) = metricSpace.distance(i, j)
    val space = Space(n, (i, j) => dist(i * n + j), threshold, maxDim, p, false)
    val t0 = System.nanoTime()
    val result = Parallel.withThreads(threads)(persistence(space, threads, 8, cocycles = true))
    println(
      f"$dataset n=$n maxDim=$maxDim threshold=$threshold p=$p: ${result.bars.size} bars, ${(System.nanoTime() - t0) / 1e9}%.1f s"
    )
    for degree <- result.degrees do
      val columns = degree.barColumns.toVector
      val withLog = degree.logs.size
      val logTotal = degree.logs.valuesIterator.map(_.columns.length.toLong).sum
      println(
        f"  degree ${degree.degree}: ${columns.size}%,d bars, ${degree.logs.size}%,d columns with a log (additions $logTotal%,d)"
      )
      if degree.components != null then println("    (its cocycles are the components of a union-find: no log)")
      else if columns.nonEmpty then
        val stats = columns.map { c =>
          val (v, s) = expand(degree, p, c)
          require(v == cocycle(degree, p, c), s"the counted copy differs from cocycle at column $c")
          s
        }
        def row(name: String, f: Stats => Long): Unit =
          val xs = stats.map(f)
          println(
            f"    $name%-26s median ${percentile(xs, 0.5)}%,d  p90 ${percentile(xs, 0.9)}%,d  p99 ${percentile(xs, 0.99)}%,d  max ${xs.max}%,d"
          )
        row("own log length", _.logLength)
        row("longest substitution chain", _.depth)
        row("columns in the memo", _.memoColumns)
        row("terms in all memo entries", _.memoTerms)
        row("terms of the cocycle", _.size)
        val worst = stats.maxBy(_.memoTerms)
        println(
          f"    worst bar: chain ${worst.depth}, ${worst.memoColumns}%,d columns, ${worst.memoTerms}%,d terms (~${worst.memoTerms * 64 / 1e6}%.1f MB at 64 bytes a term), cocycle ${worst.size}%,d terms"
        )
