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
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.util.Random

/** Cross-product benchmark: every (complex construction x homology engine) pairing this codebase actually supports,
  * timed across a sweep of point count, ambient point-cloud dimension, and max homology dimension. Like
  * `ProfilingSpec`/`ApparentPairsBenchmarkSpec`/`SparseRipsBenchmarkSpec`, this is a profiling script, not a
  * correctness check: it prints a timing table rather than asserting behavior (an exception is caught and reported as a
  * per-cell finding, not a spec failure). Defaults are kept small so this stays cheap under plain `sbt test`/CI; pass
  * larger values for a real sweep, e.g.:
  *
  * {{{
  * sbt -DminSize=20 -DmaxSize=60 -DsizeStep=20 -DmaxAmbientDim=4 -DmaxMaxDim=3 -Dtrials=5 \
  *   "testOnly org.appliedtopology.tda4j.EngineComparisonBenchmarkSpec"
  * }}}
  *
  * '''The grid is not a clean cross product, and the table says so rather than papering over it with N/A cells.'''
  * Three engines exist (see `Homology.scala`'s class docs): `SimplicialHomologyContext` (naive) and
  * `PersistenceInChunksContext` (chunked clear&compress) both take an arbitrary `CellStream`/`StratifiedCellStream`, so
  * either can run on any of the 7 constructions below. `RipserCohomologyContext` is different in kind, not degree: it
  * takes a `FiniteMetricSpace[Int]` directly and builds its own internal sparse-Rips enumeration -- it cannot be
  * pointed at a stream at all, and specifically cannot touch an alpha complex (which isn't a metric-space clique
  * complex). It appears as its own bundled row (`construction = "VR (built-in)"`, `engine = "RipserCohomology"`), not
  * decomposed into a (construction, engine) pair like the other 14 cells.
  *
  * '''Alpha complexes paired with `PersistenceInChunksContext` are a known, unresolved scale risk''' --
  * `HomologySpec.scala`'s `BarcodeRegressionSpec` is `skipAll`'d with "currently stalls out" for exactly this
  * combination. A first attempt to un-skip it, based on a single small-sample run, wrongly declared it fixed; a later
  * run on a larger sample (still well inside the same test's own generator range) hit `OutOfMemoryError` after nearly 3
  * minutes -- measured cause was `AlphaShapeDQP`'s always-untruncated construction producing over 100,000 simplices
  * from a completely unremarkable-looking 40-point, dimension-4 input, not anything specific to
  * `PersistenceInChunksContext`'s own algorithm (see CLAUDE.md's cross-engine benchmark section for the full account,
  * including what was actually measured before re-`skipAll`ing it). Kept the per-cell timeout below regardless --
  * rather than exclude alpha x Chunks (and lose the chance to quantify it) or let a future regression hang the whole
  * run, every cell here runs under `timeoutSeconds` (default 3) on a daemon-thread executor and reports `"timeout"` if
  * it doesn't finish -- a timeout is itself a finding, printed in the table, not a reason to abort. Because none of
  * these engines expose cooperative cancellation, a timed-out computation's thread keeps running in the background
  * after `withTimeout` gives up on it; daemon threads only guarantee this can't block JVM/sbt exit, not that the work
  * stops -- expect elevated CPU/memory usage for the remainder of a run that hits several timeouts back to back.
  *
  * '''Timing is split into construction and reduction phases, on purpose.''' `persistentHomology(stream)`'s
  * `HomologyState` constructor does almost nothing by itself -- the real work happens inside `diagramAt`, driven by
  * pulling `stream.iterator`. But how much work a given *construction* front-loads varies enormously: an
  * `IncrementalVietorisRipsSimplexStream`'s whole complex is a `lazy val` built on first touch,
  * `EnumeratingCofaceSimplexStream` computes everything on demand inside `iterateDimension`, and `AlphaShapeDQP` runs
  * its QP solve eagerly in its own constructor. Starting the clock after constructing the stream object would measure
  * wildly different things per row. Instead, "construction" here means "build the stream AND force full enumeration"
  * (`.iterator.toVector`, dimension-major, matching `StratifiedCellStream.iterator`'s own contract -- see its doc), and
  * "reduction" means "run the chosen engine over that already-materialized, `Vector`-backed `StratifiedCellStream`" --
  * so the reduction column measures only the homology algorithm, never a second pass through a possibly-expensive
  * original enumeration. `RipserCohomologyContext`'s bundled row has no such split (its enumeration and reduction are
  * the same call); only its `total(ms)` column is filled.
  *
  * '''Alpha and VR filtration values are not the same quantity''' (circumradius vs. diameter -- see CLAUDE.md's
  * alpha-complex section), so this benchmark makes no attempt to compare *barcodes* across construction families;
  * `#bars` is reported per cell purely as a structural sanity signal (e.g. catching a construction that silently
  * returns nothing), not for cross-row comparison. Comparing bar counts within one family (all VR rows at the same
  * `n`/dims, say) is meaningful; comparing an alpha row's bar count to a VR row's is not.
  *
  * `bounded` (below) is a generic `maxDim` cap usable for both VR and alpha streams alike, because
  * `LimitedCofaceSimplexStream` only accepts the narrower `CofaceSimplexStream` interface that alpha streams don't
  * implement -- see `SimplexStream.scala`. `IncrementalVietorisRipsSimplexStream` already takes `maxDimension` as a
  * constructor argument and so skips this wrapper entirely.
  *
  * '''All five VR constructions now default `maxFiltrationValue` to `metricSpace.minimumEnclosingRadius`, not
  * `+Infinity`''' (see CLAUDE.md/WORKLOG-mst-and-perf.md) -- none of the constructions below pass it explicitly, so
  * every VR row here reflects that default, not the previously-complete flag complex. This changes the `cells`/`bars`
  * numbers (smaller complex) but not the point of the benchmark: comparing engines/constructions against each other
  * under whatever complex actually gets built. Alpha rows are unaffected (alpha construction was never touched by this
  * change).
  */
class EngineComparisonBenchmarkSpec(args: Arguments) extends mutable.Specification:
  // Re-skipped 2026-09-16: this doc comment's own "stays cheap under plain sbt test/CI" claim didn't hold up --
  // observed taking 15+ minutes and still not done (deep into timeout after timeout at n=135) during an unrelated
  // MATLAB-API session, and being a single `sbt` invocation, it holds the project-wide sbt lock for that whole
  // span, blocking any other `sbt` command against this project from even starting. Not a correctness regression
  // (this spec asserts nothing, just prints a timing table -- see the doc above), so skipping costs nothing for
  // CI's actual pass/fail signal. See WORKLOG-matlab-api.md.
  //
  // Gated on -DrunBenchmarks=true (not a hardcoded skipAll) as of the test-suite-memory session, so running this
  // deliberately no longer means editing source and remembering to revert it -- see CLAUDE.md's "Commands" section.
  // Still the slowest thing this flag turns on (15+ minutes): scope a real run with `testOnly` rather than setting
  // the flag on a plain `sbt test`, unless you actually want every gated benchmark to run.
  if !args.commandLine.boolOr("runBenchmarks", false) then skipAll
  "Engine x construction comparison benchmark" >> {
    val minSize: Int = args.commandLine.intOr("minSize", 10)
    val maxSize: Int = args.commandLine.intOr("maxSize", 85)
    val sizeStep: Int = args.commandLine.intOr("sizeStep", 25)
    val minAmbientDim: Int = args.commandLine.intOr("minAmbientDim", 2)
    val maxAmbientDim: Int = args.commandLine.intOr("maxAmbientDim", 3)
    val minMaxDim: Int = args.commandLine.intOr("minMaxDim", 1)
    val maxMaxDim: Int = args.commandLine.intOr("maxMaxDim", 2)
    val trials: Int = args.commandLine.intOr("trials", 1)
    val seed: Int = args.commandLine.intOr("seed", 42)
    val timeoutSeconds: Int = args.commandLine.intOr("timeoutSeconds", 3)

    given Double is Field = Field.DoubleApproximated(1e-9)

    // No cooperative cancellation exists anywhere in these engines, so a "timeout" only means withTimeout stops
    // waiting -- the body keeps running on its own thread. Daemon threads keep that from blocking JVM/sbt exit.
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

    def bounded(stream: StratifiedSimplexStream[Int, Double], maxDim: Int): StratifiedSimplexStream[Int, Double] =
      new StratifiedSimplexStream[Int, Double]:
        def filtrationValue = stream.filtrationValue
        def filtrationOrdering = stream.filtrationOrdering
        val smallest = stream.smallest
        val largest = stream.largest
        def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
          case d if d >= 0 && d <= maxDim => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty)
        }

    val constructions: Seq[(String, (Array[Array[Double]], Int) => StratifiedSimplexStream[Int, Double])] = Seq(
      "VR-Enumerating" -> ((pts, maxDim) => bounded(EnumeratingCofaceSimplexStream(EuclideanMetricSpace(pts)), maxDim)),
      "VR-RipserCoface" -> ((pts, maxDim) => bounded(RipserCofaceSimplexStream(EuclideanMetricSpace(pts)), maxDim)),
      "VR-Inorder" -> ((pts, maxDim) => bounded(InorderCofaceSimplexStream(EuclideanMetricSpace(pts)), maxDim)),
      "VR-RecStack" -> ((pts, maxDim) =>
        bounded(RecursiveStackVietorisRipsSimplexStream(EuclideanMetricSpace(pts)), maxDim)
      ),
      "VR-NewVR" -> ((pts, maxDim) => IncrementalVietorisRipsSimplexStream(EuclideanMetricSpace(pts), maxDim)),
      "Alpha-DQP" -> ((pts, maxDim) => bounded(AlphaShapes(pts.toIndexedSeq, "DQP"), maxDim)),
      "Alpha-Helix" -> ((pts, maxDim) => bounded(AlphaShapes(pts.toIndexedSeq, "helix"), maxDim))
    )

    val engines: Seq[(String, (StratifiedCellStream[Simplex[Int], Double], Int) => Int)] = Seq(
      "Naive" -> ((stream, _) =>
        SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(stream)
          .diagramAt(Double.PositiveInfinity)
          .size
      ),
      "Chunks" -> ((stream, maxDim) =>
        PersistenceInChunksContext[Int, Double](maxDim)
          .persistentHomology(stream)
          .diagramAt(Double.PositiveInfinity)
          .size
      )
    )

    case class Row(
      construction: String,
      engine: String,
      constructMs: Option[Double],
      reduceMs: Option[Double],
      totalMs: Option[Double],
      cells: Option[Int],
      bars: Option[Int],
      status: String
    )

    def median(xs: Seq[Double]): Double = xs.sorted.apply(xs.size / 2)
    def medianInt(xs: Seq[Int]): Int = xs.sorted.apply(xs.size / 2)

    // Materializes once (dimension-major, forcing full enumeration) and wraps the result as a fresh
    // Vector-backed StratifiedCellStream -- see the class doc's "Timing is split..." section for why.
    def materializeAndWrap(
      pts: Array[Array[Double]],
      maxDim: Int,
      construct: (Array[Array[Double]], Int) => StratifiedSimplexStream[Int, Double]
    ): (StratifiedCellStream[Simplex[Int], Double], Int) =
      val source = construct(pts, maxDim)
      val cellVec = source.iterator.toVector
      val byDim = cellVec.groupBy(_.dim)
      val wrapped = new StratifiedCellStream[Simplex[Int], Double]:
        def filtrationValue = source.filtrationValue
        def filtrationOrdering = source.filtrationOrdering
        val smallest = source.smallest
        val largest = source.largest
        def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
          case d if byDim.contains(d) => byDim(d).iterator
        }
      (wrapped, cellVec.size)

    def runDecomposable(
      clouds: Seq[Array[Array[Double]]],
      maxDim: Int,
      cname: String,
      construct: (Array[Array[Double]], Int) => StratifiedSimplexStream[Int, Double],
      ename: String,
      engine: (StratifiedCellStream[Simplex[Int], Double], Int) => Int
    ): Row =
      val outcomes: Seq[Either[String, (Double, Double, Int, Int)]] = clouds.map { pts =>
        withTimeout {
          val t0 = System.nanoTime()
          val (stream, cellCount) = materializeAndWrap(pts, maxDim, construct)
          val constructMs = (System.nanoTime() - t0) / 1e6
          val t1 = System.nanoTime()
          val barCount = engine(stream, maxDim)
          val reduceMs = (System.nanoTime() - t1) / 1e6
          (constructMs, reduceMs, cellCount, barCount)
        }
      }
      val oks = outcomes.collect { case Right(v) => v }
      if oks.isEmpty then
        Row(cname, ename, None, None, None, None, None, outcomes.collectFirst { case Left(e) => e }.getOrElse("?"))
      else
        val status = if oks.size == outcomes.size then "ok" else s"ok (${oks.size}/${outcomes.size})"
        Row(
          cname,
          ename,
          Some(median(oks.map(_._1))),
          Some(median(oks.map(_._2))),
          Some(median(oks.map(v => v._1 + v._2))),
          Some(medianInt(oks.map(_._3))),
          Some(medianInt(oks.map(_._4))),
          status
        )

    def runRipser(clouds: Seq[Array[Array[Double]]], maxDim: Int): Row =
      val outcomes: Seq[Either[String, (Double, Int)]] = clouds.map { pts =>
        withTimeout {
          val metricSpace = EuclideanMetricSpace(pts)
          val t0 = System.nanoTime()
          val bars = RipserCohomologyContext[Double](metricSpace, maxDim).persistentCohomology()
          ((System.nanoTime() - t0) / 1e6, bars.size)
        }
      }
      val oks = outcomes.collect { case Right(v) => v }
      if oks.isEmpty then
        Row(
          "VR (built-in)",
          "RipserCohomology",
          None,
          None,
          None,
          None,
          None,
          outcomes.collectFirst { case Left(e) => e }.getOrElse("?")
        )
      else
        val status = if oks.size == outcomes.size then "ok" else s"ok (${oks.size}/${outcomes.size})"
        Row(
          "VR (built-in)",
          "RipserCohomology",
          None,
          None,
          Some(median(oks.map(_._1))),
          None,
          Some(medianInt(oks.map(_._2))),
          status
        )

    println(
      f"${"n"}%-5s${"ambDim"}%-8s${"maxDim"}%-8s${"construction"}%-18s${"engine"}%-10s${"construct(ms)"}%-15s${"reduce(ms)"}%-13s${"total(ms)"}%-13s${"cells"}%-8s${"bars"}%-6s${"status"}%-16s"
    )

    for
      n <- minSize to maxSize by sizeStep
      ambientDim <- minAmbientDim to maxAmbientDim
      maxDim <- minMaxDim to maxMaxDim
    do
      val clouds =
        Seq.tabulate(trials)(i =>
          HomologyFixtures.randomCloud(
            n,
            ambientDim,
            Random(seed.toLong * 1_000_003L + n * 1009L + ambientDim * 97L + maxDim * 13L + i)
          )
        )

      val rows =
        (for
          (cname, construct) <- constructions
          (ename, engine) <- engines
        yield runDecomposable(clouds, maxDim, cname, construct, ename, engine))
          :+ runRipser(clouds, maxDim)

      rows.foreach { r =>
        def fmt(o: Option[Double]): String = o.map(v => f"$v%.2f").getOrElse("-")
        def fmtI(o: Option[Int]): String = o.map(_.toString).getOrElse("-")
        println(
          f"$n%-5d$ambientDim%-8d$maxDim%-8d${r.construction}%-18s${r.engine}%-10s${fmt(r.constructMs)}%-15s${fmt(r.reduceMs)}%-13s${fmt(r.totalMs)}%-13s${fmtI(r.cells)}%-8s${fmtI(r.bars)}%-6s${r.status}%-16s"
        )
      }

    success
  }
