package org.appliedtopology.tda4j

import java.util.concurrent.Executors

/** The bars of the Vietoris-Rips complex of a metric space by [[ParallelPackedCohomology]], on every processor (JVM).
  * The JavaScript build has the same object and refuses (`src/main/scalajs`). Bars only: that engine records no
  * representatives, which is what makes it the one to ask for by a deliberately long name
  * (`Persistence.Engine.ParallelRipserBarsOnlyNoRepresentatives`).
  */
// Restricted: a public top-level `ParallelRipser` would join every user's `import org.appliedtopology.tda4j.*`; the
// engine itself is public.
private[tda4j] object ParallelRipser:
  /** `(degree, birth, death)` of the bars of positive length in degrees `0 .. maxDimension`, sorted; `death` is
    * infinity for an essential class. `characteristic` must be a prime.
    */
  def bars(
    metricSpace: FiniteMetricSpace[Int],
    maxDimension: Int,
    threshold: Double,
    characteristic: Int
  ): List[(Int, Double, Double)] =
    val n = metricSpace.size
    // The engine ranks edges in an n x n table of Ints.
    require(
      n.toLong * n <= Int.MaxValue,
      s"Persistence: ParallelRipserBarsOnlyNoRepresentatives keeps an n x n table, so $n points are too many; " +
        "use Engine.Ripser"
    )
    val space = ParallelPackedCohomology.Space(n, metricSpace.distance(_, _), threshold, maxDimension, characteristic)
    val threads = Parallel.threads
    if threads == 1 then ParallelPackedCohomology.persistence(space, 1, 1, cocycles = false, null).bars.toList
    else
      // Daemon threads: a pool of non-daemon threads would keep the JVM alive if the shutdown below is skipped.
      val pool = Executors.newFixedThreadPool(
        threads,
        (r: Runnable) =>
          val thread = Thread(r)
          thread.setDaemon(true)
          thread
      )
      try ParallelPackedCohomology.persistence(space, threads, UnitsPerThread, cocycles = false, pool).bars.toList
      finally pool.shutdown()

  // Several units per thread so a unit that finishes early does not leave its thread idle (`PaperBenchmarkDriver`).
  private val UnitsPerThread = 8
