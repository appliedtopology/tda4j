package org.appliedtopology.tda4j

import scala.util.Random

/** Test-scope driver, kept next to `SingleEngineProfileDriver`: one chunks-engine run on a random point cloud, phase by
  * phase, for #51 (`WORKLOG-chunks-top-cells.md`). Invoked directly, one engine per JVM (optionally with
  * `-XX:StartFlightRecording=...`):
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.ChunksProfileDriver <n> <ambientDim> <maxDim> [seed] [trials] [prime] [skipReps]
  * }}}
  *
  * Phases, each on fresh objects: (1) the Vietoris-Rips stream, every dimension enumerated; (2) the engine's state
  * (`allCells`, the cell index); (3) the reduction (`pairing`); (4) the representatives (`barcodeAt`, skipped when
  * `skipReps` is `true`).
  */
object ChunksProfileDriver:
  def main(args: Array[String]): Unit =
    val n = args(0).toInt
    val ambient = args(1).toInt
    val maxDim = args(2).toInt
    val seed = if args.length > 3 then args(3).toInt else 42
    val trials = if args.length > 4 then args(4).toInt else 1
    val prime = if args.length > 5 then args(5).toInt else 17
    val skipReps = args.length > 6 && args(6).toBoolean

    val ff = FiniteField(prime)
    import ff.given

    def ms(t0: Long, t1: Long): Double = (t1 - t0) / 1e6

    val rows = (1 to trials).map { trial =>
      val rng = new Random(seed.toLong)
      val points = Array.fill(n)(Array.fill(ambient)(rng.nextDouble()))
      val t0 = System.nanoTime()
      val stream = VietorisRips(EuclideanMetricSpace(points), maxDim)
      val sizes = (0 to maxDim + 1).map(d => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).size)
      val t1 = System.nanoTime()
      val state = PersistenceInChunksEngine[Int, ff.Fp](maxDim).persistentHomology(stream)
      val cells = state.allCells.size
      val t2 = System.nanoTime()
      val pairing = state.pairing
      val t3 = System.nanoTime()
      val bars = if skipReps then Nil else state.barcodeAt(Double.PositiveInfinity)
      val t4 = System.nanoTime()
      val finite = pairing.count(_.death.isDefined)
      val essential = pairing.count(_.death.isEmpty)
      println(
        f"trial $trial: stream=${ms(t0, t1)}%.0fms state=${ms(t1, t2)}%.0fms reduce=${ms(t2, t3)}%.0fms " +
          f"reps=${ms(t3, t4)}%.0fms total=${ms(t0, t4)}%.0fms | cells/dim=${sizes.mkString(",")} (all=$cells) " +
          s"pairs=$finite essential=$essential bars=${bars.size}"
      )
      ms(t0, t4)
    }
    val sorted = rows.sorted
    println(
      f"n=$n ambient=$ambient maxDim=$maxDim p=$prime trials=$trials medianTotalMs=${sorted(sorted.size / 2)}%.0f"
    )
