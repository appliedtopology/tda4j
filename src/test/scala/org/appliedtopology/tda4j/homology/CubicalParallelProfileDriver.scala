package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}

import scala.util.Random

/** Test-scope driver, kept alongside `AlphaDQPParallelProfileDriver`/`SingleEngineProfileDriver` as the same kind of
  * one-engine-per-process timing tool. Measures end-to-end `CellularHomologyContext.persistentHomology(...).
  * diagramAt(...)` wall-clock time on a `CubicalGridStream` with `parallelFiltrationValue` on vs off -- see
  * `.claude/WORKLOG-parallelization-survey.md` item 3. Invoked directly:
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.homology.CubicalParallelProfileDriver <parallel> <dims> <n> [seed] [trials]
  * }}}
  */
object CubicalParallelProfileDriver:
  def main(args: Array[String]): Unit =
    val parallel = args(0).toBoolean
    val dims = args(1).toInt
    val n = args(2).toInt
    val seed = if args.length > 3 then args(3).toInt else 42
    val trials = if args.length > 4 then args(4).toInt else 5

    given Double is Field = Field.DoubleApproximated(1e-9)

    val rng = new Random(seed.toLong)
    val shape = IndexedSeq.fill(dims)(n)
    val total = shape.product
    val strides = shape.scanRight(1)(_ * _).tail
    val values = IndexedSeq.fill(total)(rng.nextDouble())
    def freshStream() =
      CubicalGridStream(
        shape,
        idx => values(idx.zip(strides).map { case (i, s) => i * s }.sum),
        parallelFiltrationValue = parallel
      )
    val cellCount = freshStream().totalCellCount

    val times = (1 to trials).map { _ =>
      val t0 = System.nanoTime()
      given chc: CubicalHomologyContext[Double, Double] = CubicalHomologyContext()
      val barcode = chc.persistentHomology(freshStream()).diagramAt(Double.PositiveInfinity)
      val elapsedMs = (System.nanoTime() - t0) / 1e6
      (elapsedMs, barcode.size)
    }

    val sortedTimes = times.map(_._1).sorted
    val medianMs = sortedTimes(sortedTimes.size / 2)
    val bars = times.last._2
    println(
      s"parallel=$parallel dims=$dims n=$n cells=$cellCount trials=$trials seed=$seed " +
        s"cores=${Runtime.getRuntime.availableProcessors()}"
    )
    println(f"medianMs=$medianMs%.1f allTimes=${times.map(t => f"${t._1}%.1f").mkString(",")}")
    println(s"bars=$bars")
end CubicalParallelProfileDriver
