package org.appliedtopology.tda4j

import scala.util.Random

/** Test-scope driver, the naive engine's counterpart of `ChunksProfileDriver` (#51): one naive-engine run on a random
  * point cloud, phase by phase, optionally capped at the homological degree `maxDim`. One engine per JVM:
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.NaiveProfileDriver <n> <ambientDim> <maxDim> <capped> [seed] [trials] [prime]
  * }}}
  *
  * Phases, each on fresh objects: (1) the Vietoris-Rips stream, every dimension enumerated; (2) the engine's cursor
  * (its sorted processing sequence); (3) the reduction (`advanceAll`); (4) the representatives (`barcodeAt`).
  */
object NaiveProfileDriver:
  def main(args: Array[String]): Unit =
    val n = args(0).toInt
    val ambient = args(1).toInt
    val maxDim = args(2).toInt
    val capped = args(3).toBoolean
    val seed = if args.length > 4 then args(4).toInt else 42
    val trials = if args.length > 5 then args(5).toInt else 1
    val prime = if args.length > 6 then args(6).toInt else 17

    val ff = FiniteField(prime)
    import ff.given

    def ms(t0: Long, t1: Long): Double = (t1 - t0) / 1e6

    val totals = (1 to trials).map { trial =>
      val rng = new Random(seed.toLong)
      val points = Array.fill(n)(Array.fill(ambient)(rng.nextDouble()))
      val t0 = System.nanoTime()
      val stream = VietorisRips(EuclideanMetricSpace(points), maxDim)
      val sizes = (0 to maxDim + 1).map(d => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).size)
      val t1 = System.nanoTime()
      val engine = SimplicialHomologyEngine[Int, ff.Fp, Double]()
      val state = if capped then engine.persistentHomology(stream, maxDim) else engine.persistentHomology(stream)
      val cells = state.totalCells
      val t2 = System.nanoTime()
      state.advanceAll()
      val t3 = System.nanoTime()
      val bars = state.barcodeAt(Double.PositiveInfinity).filter(_.dim <= maxDim)
      val t4 = System.nanoTime()
      val pairing = state.pairing.filter(_.dim <= maxDim)
      println(
        f"trial $trial: stream=${ms(t0, t1)}%.0fms cursor=${ms(t1, t2)}%.0fms reduce=${ms(t2, t3)}%.0fms " +
          f"reps=${ms(t3, t4)}%.0fms total=${ms(t0, t4)}%.0fms | cells/dim=${sizes.mkString(",")} (processed=$cells) " +
          s"pairs<=k=${pairing.count(_.death.isDefined)} essential<=k=${pairing.count(_.death.isEmpty)} bars<=k=${bars.size}"
      )
      ms(t0, t4)
    }
    val sorted = totals.sorted
    println(
      f"n=$n ambient=$ambient maxDim=$maxDim capped=$capped p=$prime medianTotalMs=${sorted(sorted.size / 2)}%.0f"
    )
