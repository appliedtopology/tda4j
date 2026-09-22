package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

import scala.util.Random

/** Test-scope driver, kept alongside `AlphaDQPParallelProfileDriver`/`CubicalParallelProfileDriver` as the same kind
  * of one-engine-per-process timing tool. Measures `CechCofaceSimplexStream` construction (stream materialization
  * alone, isolating the Miniball-heavy filtration-value work) and full end-to-end persistent homology wall-clock time
  * with `parallelFiltrationValue` on vs off -- see `.claude/WORKLOG-parallelization-survey.md` item 2. Invoked
  * directly:
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.streams.CechParallelProfileDriver <parallel> <n> <dim> <maxDimCap> [seed] [trials]
  * }}}
  *
  * `maxDimCap` bounds the top simplex dimension built (Cech's own complex, like VR, blows up combinatorially at
  * `maxFiltrationValue = +Infinity` with no cap) -- passed as `keepCriterion` rejecting anything past that dimension.
  */
object CechParallelProfileDriver:
  def main(args: Array[String]): Unit =
    val parallel = args(0).toBoolean
    val n = args(1).toInt
    val dim = args(2).toInt
    val maxDimCap = args(3).toInt
    val seed = if args.length > 4 then args(4).toInt else 42
    val trials = if args.length > 5 then args(5).toInt else 5
    // The naive SimplicialHomologyContext engine's own reduction phase scales badly with cell count (a
    // known, pre-existing property of the reference-grade baseline engine, unrelated to this driver's own
    // parallelFiltrationValue measurement) -- skip it at larger n so that phase's cost doesn't swamp the
    // measurement this driver actually cares about (stream construction / filtration-value computation).
    val skipHomology = args.length > 6 && args(6) == "streamOnly"

    given Double is Field = Field.DoubleApproximated(1e-9)

    val rng = new Random(seed.toLong)
    val points = Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

    def freshStream() =
      val ms = EuclideanMetricSpace(points)
      CechCofaceSimplexStream(
        ms,
        keepCriterion = { case spx => spx.dim <= maxDimCap },
        maxFiltrationValue = Some(Double.PositiveInfinity),
        parallelFiltrationValue = parallel
      )

    val streamTimes = (1 to trials).map { _ =>
      val t0 = System.nanoTime()
      val s = freshStream()
      val cellCount = (0 to maxDimCap).map(d => s.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).size).sum
      val elapsedMs = (System.nanoTime() - t0) / 1e6
      (elapsedMs, cellCount)
    }
    val sortedStreamTimes = streamTimes.map(_._1).sorted
    val medianStreamMs = sortedStreamTimes(sortedStreamTimes.size / 2)
    val cellCount = streamTimes.last._2

    println(
      s"parallel=$parallel n=$n dim=$dim maxDimCap=$maxDimCap cells=$cellCount trials=$trials seed=$seed " +
        s"cores=${Runtime.getRuntime.availableProcessors()}"
    )
    println(f"streamOnly: medianMs=$medianStreamMs%.1f allTimes=${streamTimes.map(t => f"${t._1}%.1f").mkString(",")}")
    if !skipHomology then
      val homologyTimes = (1 to trials).map { _ =>
        val t0 = System.nanoTime()
        val barcode = SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(freshStream())
          .diagramAt(Double.PositiveInfinity)
        val elapsedMs = (System.nanoTime() - t0) / 1e6
        (elapsedMs, barcode.size)
      }
      val sortedHomologyTimes = homologyTimes.map(_._1).sorted
      val medianHomologyMs = sortedHomologyTimes(sortedHomologyTimes.size / 2)
      val bars = homologyTimes.last._2
      println(
        f"fullHomology: medianMs=$medianHomologyMs%.1f bars=$bars allTimes=${homologyTimes.map(t => f"${t._1}%.1f").mkString(",")}"
      )
end CechParallelProfileDriver
