package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}

/** Test-scope driver, kept alongside `CubicalBenchmarkSpec.scala` -- a single-process JVM target for profiling
  * `CellularHomologyContext` on a `CubicalGridStream`, avoiding the sbt-hosted benchmark harness's own
  * documented timeout/daemon-thread contamination risk (see `SingleEngineProfileDriver`'s own doc, same
  * reasoning). Invoked directly:
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.homology.CubicalProfileDriver <dims> <n> [seed]
  * }}}
  *
  * Built to root-cause CLAUDE.md's documented-but-unexplained finding: the naive cubical engine's per-cell cost
  * GROWS with `n` in 3D but stays flat in 2D. See `.claude/WORKLOG-autonomous-session-2026-09-19.md`.
  */
object CubicalProfileDriver:
  def main(args: Array[String]): Unit =
    val dims = args(0).toInt
    val n = args(1).toInt
    val seed = if args.length > 2 then args(2).toInt else 42

    given Double is Field = Field.DoubleApproximated(1e-9)
    given chc: CubicalHomologyContext[Double, Double] = CubicalHomologyContext()
    import chc.{*, given}

    val rng = new scala.util.Random(seed.toLong)
    val shape = IndexedSeq.fill(dims)(n)
    val total = shape.product
    val strides = shape.scanRight(1)(_ * _).tail
    val values = IndexedSeq.fill(total)(rng.nextDouble())
    val stream = CubicalGridStream(
      shape,
      idx => values(idx.zip(strides).map { case (i, s) => i * s }.sum)
    )
    val cellCount = stream.totalCellCount

    // Phase-separated timing: (1) CubicalGridStream.iterateDimension's own per-dimension sort/materialization
    // (dominated by containingTopCells during each bucket's OWN sort), (2) HomologyState's SECOND, GLOBAL sort
    // (processingOrder, across all dimensions combined -- see Homology.scala's own comment on why this exists),
    // (3) the actual advanceAll/reduction loop. Each phase gets a FRESH stream instance to avoid any accidental
    // cross-phase caching skewing the measurement.
    val t0 = System.nanoTime()
    val phase1Count = stream.iterator.size
    val t1 = System.nanoTime()

    val stream2 = CubicalGridStream(shape, idx => values(idx.zip(strides).map { case (i, s) => i * s }.sum))
    val state = persistentHomology(stream2)
    val t2 = System.nanoTime()

    val barcode = state.diagramAt(Double.PositiveInfinity)
    val t3 = System.nanoTime()

    val phase1Ms = (t1 - t0) / 1e6
    val phase2Ms = (t2 - t1) / 1e6
    val phase3Ms = (t3 - t2) / 1e6
    val totalMs = (t3 - t0) / 1e6

    def usPer(ms: Double): Double = ms * 1000.0 / cellCount

    println(
      s"dims=$dims n=$n cells=$cellCount phase1Count=$phase1Count bars=${barcode.size}\n" +
        f"  phase1(iterateDimension sort)   ms=$phase1Ms%10.1f  us/cell=${usPer(phase1Ms)}%8.3f\n" +
        f"  phase2(HomologyState global sort) ms=$phase2Ms%10.1f  us/cell=${usPer(phase2Ms)}%8.3f\n" +
        f"  phase3(advanceAll reduction)    ms=$phase3Ms%10.1f  us/cell=${usPer(phase3Ms)}%8.3f\n" +
        f"  total                            ms=$totalMs%10.1f  us/cell=${usPer(totalMs)}%8.3f"
    )
