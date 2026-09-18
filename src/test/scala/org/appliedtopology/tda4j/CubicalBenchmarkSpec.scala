package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.main.Arguments

/** Measures where `CellularHomologyContext` -- the naive, generic single-pivot-table reduction engine, the ONLY
  * one `Cube` currently plugs into (a specialized grid-exploiting engine like CubicalRipser or the Wagner-Chen-
  * Vuçini approach is deliberately deferred, see CLAUDE.md/WORKLOG-cubical.md) -- starts to strain on REAL image
  * sizes. Like `SparseRipsBenchmarkSpec`, prints a timing table rather than asserting behavior; not a correctness
  * check, and kept small by default so `sbt test` stays fast:
  *
  * {{{
  * sbt -DminN=8 -DmaxN=256 -DstepMultiplier=2 "testOnly org.appliedtopology.tda4j.CubicalBenchmarkSpec"
  * }}}
  *
  * Random (not constant/tie-heavy) pixel values on purpose -- `CubicalStreamSpec`'s hand fixtures deliberately
  * stress the TIE-BREAK path with few distinct values; this benchmark instead wants the generic case an actual
  * photograph would produce (see `totalCellCount` for the real cell count at each `n`, not the pixel count).
  */
class CubicalBenchmarkSpec(args: Arguments) extends mutable.Specification:
  "Cubical naive-engine scaling on square images" >> {
    val minN: Int = args.commandLine.intOr("minN", 4)
    val maxN: Int = args.commandLine.intOr("maxN", 32)
    val stepMultiplier: Int = args.commandLine.intOr("stepMultiplier", 2)
    val seed: Int = args.commandLine.intOr("seed", 42)
    val dims: Int = args.commandLine.intOr("dims", 2)

    given Double is Field = Field.DoubleApproximated(1e-9)
    given chc: CubicalHomologyContext[Double, Double] = CubicalHomologyContext()
    import chc.{*, given}

    val sizes: Seq[Int] = Iterator.iterate(minN)(_ * stepMultiplier).takeWhile(_ <= maxN).toSeq

    println(f"${"n (per axis)"}%-14s${"cells"}%-12s${"time (ms)"}%-14s${"us/cell"}%-10s")

    for n <- sizes do
      val rng = new scala.util.Random(seed.toLong * 1_000_003L + n)
      val shape = IndexedSeq.fill(dims)(n)
      val total = shape.product
      val strides = shape.scanRight(1)(_ * _).tail
      val values = IndexedSeq.fill(total)(rng.nextDouble())
      val stream = CubicalGridStream(
        shape,
        idx => values(idx.zip(strides).map { case (i, s) => i * s }.sum)
      )
      val cellCount = stream.totalCellCount
      val start = System.nanoTime()
      persistentHomology(stream).diagramAt(Double.PositiveInfinity)
      val elapsedMs = (System.nanoTime() - start) / 1e6
      val usPerCell = elapsedMs * 1000.0 / cellCount
      println(f"$n%-14d$cellCount%-12d$elapsedMs%-14.1f$usPerCell%-10.3f")
  }
