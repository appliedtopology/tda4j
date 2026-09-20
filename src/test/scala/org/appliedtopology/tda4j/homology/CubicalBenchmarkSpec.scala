package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.specs2.mutable
import org.specs2.main.Arguments

import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.concurrent.duration.*

/** Naive (`CellularHomologyContext`) vs. chunks (`CellularPersistenceInChunksContext`) scaling on cubical complexes,
  * run on the SAME generated grid at each size for a fair comparison -- the naive engine's own scaling (2D roughly flat
  * per-cell cost, 3D per-cell cost GROWING with `n`, not yet root-caused) is documented in CLAUDE.md; this spec adds
  * chunks as a second engine to see whether it handles the 3D case differently.
  * `CellularPersistenceInChunksContext[Cube, ...]` had never been exercised anywhere in this codebase before -- see
  * `CubicalStreamSpec`'s own cross-validation section (naive-vs-chunks agreement on the tie-heavy fixtures, plus
  * `HomologyFixtures.totalBarsAccountForAllCells` on chunks' own output independently) before trusting any timing here,
  * and `.claude/WORKLOG-cubical-chunks-benchmark.md` for the full derivation.
  *
  * `maxDim` is pinned to the grid's own ambient dimension (`dims`) for chunks -- chunks' `maxDim` means "top
  * homological degree reported" and internally walks one dimension higher, whereas naive has no `maxDim` at all and
  * simply computes through the stream's actual top cube dimension, which for a `dims`-dimensional grid is `dims`
  * itself; pinning them to agree keeps a maxDim-semantics mismatch from masquerading as a timing or correctness
  * difference. Chunks runs under a timeout (default 30s, `-DtimeoutSeconds`) on a daemon-thread executor, matching
  * `EngineComparisonBenchmarkSpec`'s established pattern -- `PersistenceInChunksContext` x alpha is a known stall/OOM
  * risk at complex sizes far smaller than a 3D grid can reach (CLAUDE.md's "Cross-engine benchmark" section: 102k
  * simplices stalled/OOM'd), and a 32-cubed grid is 274,625 cells, so a timeout here is a real safety net, not
  * defensive boilerplate -- a stall prints `"timeout"` in the table rather than hanging the run. Like
  * `SparseRipsBenchmarkSpec`, prints a timing table rather than asserting behavior; not a correctness check, and kept
  * small by default so `sbt test` stays fast:
  *
  * {{{
  * sbt -DminN=8 -DmaxN=256 -DstepMultiplier=2 "testOnly org.appliedtopology.tda4j.CubicalBenchmarkSpec"
  * sbt -Ddims=3 -DmaxN=32 "testOnly org.appliedtopology.tda4j.CubicalBenchmarkSpec"
  * }}}
  *
  * Random (not constant/tie-heavy) pixel values on purpose -- `CubicalStreamSpec`'s hand fixtures deliberately stress
  * the TIE-BREAK path with few distinct values; this benchmark instead wants the generic case an actual photograph
  * would produce (see `totalCellCount` for the real cell count at each `n`, not the pixel count).
  */
class CubicalBenchmarkSpec(args: Arguments) extends mutable.Specification:
  "Cubical naive-vs-chunks engine scaling on square images" >> {
    val minN: Int = args.commandLine.intOr("minN", 4)
    val maxN: Int = args.commandLine.intOr("maxN", 32)
    val stepMultiplier: Int = args.commandLine.intOr("stepMultiplier", 2)
    val seed: Int = args.commandLine.intOr("seed", 42)
    val dims: Int = args.commandLine.intOr("dims", 2)
    val timeoutSeconds: Int = args.commandLine.intOr("timeoutSeconds", 30)

    given Double is Field = Field.DoubleApproximated(1e-9)
    given chc: CubicalHomologyContext[Double, Double] = CubicalHomologyContext()
    import chc.{*, given}

    // No cooperative cancellation exists in either engine, so a "timeout" only stops waiting -- the body keeps
    // running on its own thread. Daemon threads keep that from blocking JVM/sbt exit (see EngineComparisonBenchmarkSpec).
    val daemonExecutor = Executors.newCachedThreadPool(new ThreadFactory:
      def newThread(r: Runnable): Thread =
        val t = new Thread(r)
        t.setDaemon(true)
        t)
    given ExecutionContext = ExecutionContext.fromExecutor(daemonExecutor)

    def withTimeout[A](body: => A): Either[String, A] =
      try Right(Await.result(Future(body), timeoutSeconds.seconds))
      catch
        case _: TimeoutException => Left("timeout")
        case e: Throwable        => Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}".trim)

    val sizes: Seq[Int] = Iterator.iterate(minN)(_ * stepMultiplier).takeWhile(_ <= maxN).toSeq

    println(
      f"${"n (per axis)"}%-14s${"cells"}%-12s${"naive (ms)"}%-14s${"naive us/cell"}%-16s" +
        f"${"chunks (ms)"}%-14s${"chunks us/cell"}%-16s"
    )

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

      val naiveStart = System.nanoTime()
      persistentHomology(stream).diagramAt(Double.PositiveInfinity)
      val naiveMs = (System.nanoTime() - naiveStart) / 1e6
      val naiveUsPerCell = naiveMs * 1000.0 / cellCount

      val chunksResult = withTimeout {
        val start = System.nanoTime()
        CellularPersistenceInChunksContext[Cube, Double](dims)
          .persistentHomology(stream)
          .diagramAt(Double.PositiveInfinity)
        (System.nanoTime() - start) / 1e6
      }
      val (chunksMsStr, chunksUsPerCellStr) = chunksResult match
        case Right(chunksMs) => (f"$chunksMs%.1f", f"${chunksMs * 1000.0 / cellCount}%.3f")
        case Left(reason)    => (reason, "-")

      println(
        f"$n%-14d$cellCount%-12d$naiveMs%-14.1f$naiveUsPerCell%-16.3f" +
          f"$chunksMsStr%-14s$chunksUsPerCellStr%-16s"
      )
  }
