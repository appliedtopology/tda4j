package org.appliedtopology.tda4j
package alpha

import scala.util.Random

/** Test-scope driver, kept alongside `homology.SingleEngineProfileDriver`/`CubicalProfileDriver` as the same kind of
  * one-engine-per-process timing tool -- not a scratch file. Measures `AlphaComplexDQP.compute()`'s wall-clock time
  * with `AlphaDQPSettings.parallel` on vs off, on a synthetic random point cloud (a fixed seed per (n, dim) so both
  * settings solve the identical point cloud, hence the identical amount of QP work -- required for the comparison to
  * isolate scheduling, not workload size). Invoked directly via
  * `java -cp $CP org.appliedtopology.tda4j.alpha.AlphaDQPParallelProfileDriver <parallel> <n> <dim> [trials] [seed]`
  * (get `$CP` from `sbt "export Test/fullClasspath"`), not `sbt runMain`, to avoid sbt's own per-invocation startup
  * cost when sweeping many (parallel, n, dim) combinations.
  */
object AlphaDQPParallelProfileDriver:
  def main(args: Array[String]): Unit =
    val parallel = args(0).toBoolean
    val n = args(1).toInt
    val dim = args(2).toInt
    val trials = if args.length > 3 then args(3).toInt else 5
    val seed = if args.length > 4 then args(4).toLong else 42L

    val rng = new Random(seed)
    val points = Array.fill(n)(Array.fill(dim)(rng.nextDouble()))
    val settings = AlphaDQPSettings(parallel = parallel)

    val times = (1 to trials).map { _ =>
      val t0 = System.nanoTime()
      val complex = AlphaComplexDQP.euclidean(points, Double.PositiveInfinity, dim, settings)
      val elapsedMs = (System.nanoTime() - t0) / 1e6
      (elapsedMs, complex.sizeByDimension)
    }

    val sortedTimes = times.map(_._1).sorted
    val medianMs = sortedTimes(sortedTimes.size / 2)
    val sizeByDim = times.last._2
    println(
      s"parallel=$parallel n=$n dim=$dim trials=$trials seed=$seed cores=${Runtime.getRuntime.availableProcessors()}"
    )
    println(f"medianMs=$medianMs%.1f allTimes=${times.map(t => f"${t._1}%.1f").mkString(",")}")
    println(s"sizeByDim=${sizeByDim.mkString(",")}")
end AlphaDQPParallelProfileDriver
