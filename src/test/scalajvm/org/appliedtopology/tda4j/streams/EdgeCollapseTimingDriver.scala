package org.appliedtopology.tda4j

/** Times `EdgeCollapse.collapse` alone (the graph and its distances are built before the clock starts): the median of
  * the trials, one JVM per run.
  *
  * {{{
  * java -cp <test classpath> org.appliedtopology.tda4j.EdgeCollapseTimingDriver n [ambientDim] [radius|mer] [trials] [seed] [dense|maps]
  * }}}
  *
  * The cloud is `n` uniform points in the unit cube of `ambientDim` dimensions (default 3); `radius` is the threshold
  * of the collapse (default `mer`, the enclosing radius). Prints the edges before and after and the milliseconds per
  * trial.
  */
object EdgeCollapseTimingDriver:
  def main(args: Array[String]): Unit =
    val n = args(0).toInt
    val ambient = args.lift(1).fold(3)(_.toInt)
    val radiusArg = args.lift(2).getOrElse("mer")
    val trials = args.lift(3).fold(3)(_.toInt)
    val seed = args.lift(4).fold(1L)(_.toLong)
    val denseLimit = if args.lift(5).contains("maps") then 0 else Int.MaxValue
    val rng = new scala.util.Random(seed)
    val space = EuclideanMetricSpace(Array.fill(n)(Array.fill(ambient)(rng.nextDouble())))
    val radius = if radiusArg == "mer" then space.minimumEnclosingRadius else radiusArg.toDouble
    // Fill the distance cache before the clock starts.
    for i <- 0 until n; j <- i + 1 until n do space.distance(i, j)
    val timings = (1 to trials).map { _ =>
      val start = System.nanoTime()
      val collapsed = EdgeCollapse.collapseWith(space, Some(radius), denseLimit)
      ((System.nanoTime() - start) / 1e6, collapsed.stats)
    }
    val sorted = timings.map(_._1).sorted
    println(
      f"collapse n=$n ambient=$ambient radius=$radius%.3f: median ${sorted(sorted.size / 2)}%.0f ms of ${timings.map(t => f"${t._1}%.0f").mkString(", ")}; edges ${timings.head._2.edgesBefore} to ${timings.head._2.edgesAfter}"
    )
