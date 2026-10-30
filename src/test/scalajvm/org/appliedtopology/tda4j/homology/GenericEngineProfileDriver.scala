package org.appliedtopology.tda4j

import Persistence.Engine

/** Test-scope driver for profiling the generic engines (the ones that reduce through `Chain.reduceBy`), one engine and
  * one workload per JVM so that a Java Flight Recorder run measures that pair alone:
  *
  * {{{
  * java -XX:StartFlightRecording=filename=p.jfr,settings=profile -cp $CP \
  *   org.appliedtopology.tda4j.GenericEngineProfileDriver <engine> <workload> <representatives> [trials] [seed]
  * }}}
  *
  * `engine` is a `Persistence.Engine` name (`Naive`, `Chunks`, `Cohomology`, `ParallelRipser`, `Ripser`).
  * `representatives` is `Cycles` or `Cocycles`. `workload` is `sphere:<n>` (n random points on S^2, degrees 0..2),
  * `cloud:<n>:<d>` (n uniform points in [0,1]^d, degrees 0..2) or `image:<side>` (a random side x side grayscale
  * image). Prints the median wall time of `trials` runs (default 3) and the number of bars. A sixth argument `terms`
  * also prints the total and largest number of terms among the representatives of the last run.
  */
object GenericEngineProfileDriver:
  def main(args: Array[String]): Unit =
    val engine = Engine.valueOf(args(0))
    val workload = args(1).split(':').toList
    val representatives = Representatives.valueOf(args(2))
    val trials = if args.length > 3 then args(3).toInt else 3
    val rng = scala.util.Random(if args.length > 4 then args(4).toLong else 17L)

    val countTerms = args.length > 5 && args(5) == "terms"
    var termCounts = Seq.empty[Int]

    def count(diagram: PersistenceDiagram[?]): Int =
      if countTerms then
        termCounts = diagram.bars.map(_.annotation.collect { case c: Chain[?, ?] => c.terms.size }.getOrElse(0))
      diagram.bars.size

    def run(): Int = workload match
      case "sphere" :: n :: Nil =>
        val points = Array.fill(n.toInt) {
          val v = Array.fill(3)(rng.nextGaussian())
          val norm = math.sqrt(v.map(x => x * x).sum)
          v.map(_ / norm)
        }
        count(Persistence(points, maxDimension = 2, engine = engine, representatives = representatives))
      case "cloud" :: n :: d :: Nil =>
        val points = Array.fill(n.toInt)(Array.fill(d.toInt)(rng.nextDouble()))
        count(Persistence(points, maxDimension = 2, engine = engine, representatives = representatives))
      case "image" :: side :: Nil =>
        val image = Image(Array.fill(side.toInt)(Array.fill(side.toInt)(rng.nextDouble())))
        count(Persistence(image, engine = engine, representatives = representatives))
      case other => throw new IllegalArgumentException(s"unknown workload $other")

    var bars = 0
    val times = (1 to trials).map { _ =>
      val t0 = System.nanoTime()
      bars = run()
      (System.nanoTime() - t0) / 1e6
    }
    println(
      f"$engine ${args(1)} $representatives: median ${times.sorted.apply(trials / 2)}%.0f ms of ${times.map(t => f"$t%.0f").mkString(", ")}, $bars bars"
    )
    if countTerms then
      println(
        s"representative terms: total ${termCounts.map(_.toLong).sum}, largest ${termCounts.maxOption.getOrElse(0)}"
      )
