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

/** Answers a specific question, not a general profiling sweep: is the "everything is slow, point clouds have to stay
  * tiny" experience a product of (a) building homology past the dimension anyone actually cares about and (b) leaving
  * the filtration unbounded (the complete flag complex, every pairwise distance included) -- or is it slow even when
  * restricted to a realistic low homological dimension (2-4) and a bounded filtration range?
  *
  * '''What "restricted to homological dimension H" actually requires building''': per CLAUDE.md's MATLAB-API section,
  * computing H_k correctly (not as a truncation artifact where every top-dimension class looks essential because no
  * (k+1)-simplex exists to possibly kill it) needs (k+1)-dimensional simplices present. `RipserCohomologyContext`'s
  * `maxDimension` constructor argument now means the highest HOMOLOGICAL DEGREE reported, fixed at its own source
  * (`.claude/WORKLOG-maxdim-semantics-fix.md`) -- it resolves the needed `(H+1)`-simplices internally, so
  * `ripserAttempt` below passes `homDim` directly. `VR-Enum+Naive` (`SimplicialHomologyContext` fed a
  * `LimitedCofaceSimplexStream`) has NOT been fixed this way -- its dimension cap lives entirely in the stream it's
  * handed, so `vrEnumNaiveAttempt` still manually builds `buildDim = homDim + 1` simplices and lets
  * `SimplicialHomologyContext` report every dimension it finds (the top one, `homDim + 1`, is then itself a truncation
  * artifact, but this spec never reads that row's own top-dimension bars individually -- only total `cells`/`bars`
  * counts, for ceiling-finding purposes, not correctness assertions).
  *
  * '''Three threshold regimes, not two''', because "bounded" is not one thing:
  *   - `unbounded`: `maxFiltrationValue = Double.PositiveInfinity` -- the complete flag complex, every pairwise
  *     distance included regardless of size. The old default before this session's earlier work (see
  *     WORKLOG-mst-and-perf.md Part 4).
  *   - `default`: `maxFiltrationValue` left as `None`, resolving internally to `metricSpace.minimumEnclosingRadius` --
  *     the CURRENT default across this codebase's VR constructions. In a unit hypercube this stays roughly constant
  *     (~0.7 in 2D) as `n` grows -- it's a correctness cap (excludes only the genuinely cone-redundant long tail), not
  *     a scaling lever, so it should NOT be expected to keep complex size bounded as `n` grows.
  *   - `sparse`: `maxFiltrationValue = thresholdScale / sqrt(n)` (same convention as `SparseRipsBenchmarkSpec`) -- a
  *     threshold that genuinely SHRINKS with `n`, keeping the expected local neighborhood size roughly constant. This
  *     is the regime a user restricting attention to local/short-range topology would actually want, and is the one
  *     that isolates whether "longest distance" is a real, independent lever from "highest dimension."
  *
  * '''Two engine rows, not one''' -- per WORKLOG-mst-and-perf.md Part 2/Part 4, bounding `maxFiltrationValue` is NOT
  * uniformly effective: `RipserCohomologyContext` has genuine incremental sparse-aware enumeration
  * (`sparseCofacets`/`insertionDiameter`) that only enumerates candidates actually within threshold, while
  * `EnumeratingCofaceSimplexStream`-family streams filter AFTER `simplexIndexing` already constructed each candidate
  * (`keptByThresholdAndCriterion`, a post-hoc filter) -- so bounding distance cuts their reduction cost but not their
  * O(C(n,d+1)) enumeration cost. `VR-Enumerating` x `SimplicialHomologyContext` (Naive) is included specifically to
  * show whether that documented asymmetry actually shows up as a ceiling difference, not assumed from the worklog
  * alone. `RecursiveStackVietorisRipsSimplexStream` is excluded -- already documented as not speed-competitive, would
  * just print "timeout" at every cell and waste budget.
  *
  * '''Methodology: grow `n` until a per-attempt timeout fires, rather than time a fixed guessed grid''' -- the
  * point-cloud-size ceiling IS the answer to "how big can my point cloud be," so it's measured directly: for each
  * (engine, homDim, thresholdRegime) cell, `n` starts at `nStart` and grows geometrically (factor `growthFactor`) until
  * an attempt exceeds `ceilingTimeoutMs` (no cooperative cancellation exists in these engines -- same daemon- executor
  * pattern as `EngineComparisonBenchmarkSpec`, so a timed-out attempt's thread keeps running in the background) or
  * `nCap`/`maxSteps` is hit. Reports the largest `n` that completed within `fastBudgetMs` AND the largest `n` that
  * completed within `ceilingTimeoutMs`, side by side with `totalSimplexCount`/bar count, so a reader can separate "the
  * complex got smaller" from "the same-size complex got faster."
  *
  * '''Single trial per step, not median-of-several''' -- this is a ceiling-finding exploratory sweep, not a precise
  * timing comparison (that's what `SparseRipsBenchmarkSpec`/`ApparentPairsBenchmarkSpec` are for at a fixed `n`).
  * Expect noise of a step or two in the reported ceiling `n`, not a load-bearing number to two significant figures.
  *
  * '''A global wall-clock deadline bounds the whole run''', independent of every other parameter -- a single
  * misbehaving cell (e.g. an engine whose ceiling sits just past `nCap` and keeps retrying near the edge) cannot make
  * this spec repeat `EngineComparisonBenchmarkSpec`'s 15+ minute sbt-lock incident (see CLAUDE.md's cross-engine
  * benchmark section). Cells not reached before the deadline print "skipped (deadline)" rather than silently vanishing
  * from the table.
  *
  * Run standalone (NOT as part of plain `sbt test` -- see CLAUDE.md/WORKLOG-mst-and-perf.md Part 1 for the prior OOM
  * that cascaded into a spurious unrelated-spec failure in the same `sbt test` run), e.g.:
  *
  * {{{
  * sbt -J-Xmx4g "testOnly org.appliedtopology.tda4j.DimensionCeilingBenchmarkSpec"
  * }}}
  *
  * '''A previously-unknown correctness bug in `SimplicialHomologyContext`, found while building this spec, is now
  * FIXED''' (`CellularHomologyContext.HomologyState` in `Homology.scala`, plus two structurally-related fixes found in
  * the same pass -- see WORKLOG-dimension-ceiling.md for the original discovery and WORKLOG-reference-engine-fix.md for
  * the full derivation and fix). It used to throw `IllegalStateException: reduction pivot ... was not a recorded open
  * class` once simplices of dimension >= 4 (5+ vertices) were built, i.e. `homDim >= 3` here. Root cause: the
  * persistence matching partitions every cell into POSITIVE (creator) or NEGATIVE (destroyer, matched immediately,
  * recorded only under its own pivot's key) -- when a later, higher-dimension column's reduction cascaded onto an
  * already-matched NEGATIVE cell, `boundaries.get` correctly found nothing, but that did not mean reduction was
  * finished, only that the existing lookup couldn't see a negative cell's own substitute. Fixed by recording each
  * negative cell's own V-column (already computed, unused past its own branch) in a new `negativeVCols` map and wiring
  * it through `Chain.reduceBy`'s existing `fallback` parameter -- the same mechanism `RipserCohomologyContext` already
  * uses for its own apparent-pairs substitution. Verified against `RipserCohomologyContext`'s independently- derived
  * bar count (exact agreement, not just "no crash") and a 64-trial cross-validation sweep, 0 mismatches. This spec's
  * own previously-crashing cells (VR-Enum+Naive at H=3/H=4) now hit ordinary timeout ceilings instead.
  */
class DimensionCeilingBenchmarkSpec(args: Arguments) extends mutable.Specification:
  // Not skipAll, unlike EngineComparisonBenchmarkSpec -- defaults below are kept deliberately small/cheap (a global
  // deadline included) so this stays safe under plain `sbt test`, the same convention SparseRipsBenchmarkSpec/
  // ApparentPairsBenchmarkSpec use. Pass much larger -D overrides via an explicit `testOnly` invocation for a real
  // sweep -- see the class doc's example command.
  "Dimension/threshold ceiling sweep" >> {
    val nStart: Int = args.commandLine.intOr("nStart", 15)
    val nCap: Int = args.commandLine.intOr("nCap", 60)
    val growthFactor: Double = args.commandLine.doubleOr("growthFactor", 1.5)
    val maxSteps: Int = args.commandLine.intOr("maxSteps", 6)
    val ambientDim: Int = args.commandLine.intOr("ambientDim", 3)
    // Fixed, not command-line-driven: this spec exists to answer one specific question (does restricting to
    // homological dimension 2-4 fix the ceiling?), not to be a general-purpose sweep -- see class doc.
    val homDims: Seq[Int] = Seq(2, 3, 4)
    val thresholdScale: Double = args.commandLine.doubleOr("thresholdScale", 2.5)
    val seed: Int = args.commandLine.intOr("seed", 42)
    val fastBudgetMs: Double = args.commandLine.doubleOr("fastBudgetMs", 2000)
    val ceilingTimeoutMs: Double = args.commandLine.doubleOr("ceilingTimeoutMs", 5000)
    val deadlineSeconds: Long = args.commandLine.intOr("deadlineSeconds", 90).toLong

    given Double is Field = Field.DoubleApproximated(1e-9)

    val daemonExecutor = Executors.newCachedThreadPool(new ThreadFactory:
      def newThread(r: Runnable): Thread =
        val t = new Thread(r)
        t.setDaemon(true)
        t)
    given ExecutionContext = ExecutionContext.fromExecutor(daemonExecutor)

    def withTimeout[A](millis: Double)(body: => A): Either[String, A] =
      try Right(Await.result(Future(body), millis.millis))
      catch
        case _: TimeoutException => Left("timeout")
        case e: Throwable        => Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}".trim)

    def randomCloud(n: Int, rng: Random): Array[Array[Double]] =
      Array.fill(n)(Array.fill(ambientDim)(rng.nextDouble()))

    def rngFor(tag: String, n: Int): Random =
      Random(seed.toLong * 1_000_003L + tag.hashCode.toLong * 97L + n.toLong)

    def thresholdFor(regime: String, n: Int): Double = regime match
      case "unbounded" => Double.PositiveInfinity
      case "default"   => Double.NaN
      case "sparse"    => thresholdScale / math.sqrt(n.toDouble)

    val startTime = System.nanoTime()
    def deadlineHit: Boolean = (System.nanoTime() - startTime) / 1e9 >= deadlineSeconds

    // Same hand-rolled dimension-cap wrapper as EngineComparisonBenchmarkSpec's own `bounded` (rather than
    // LimitedCofaceSimplexStream) -- confirmed equivalent by diagnostic swap while chasing the crash documented in
    // the class doc above (both wrappers reproduce the identical failure on the identical input), kept as this one
    // since it's the established convention elsewhere in this codebase's benchmarks.
    def bounded(
      stream: StratifiedSimplexStream[Int, Double],
      maxDim: Int
    ): StratifiedSimplexStream[Int, Double] =
      new StratifiedSimplexStream[Int, Double]:
        def filtrationValue = stream.filtrationValue
        def filtrationOrdering = stream.filtrationOrdering
        val smallest = stream.smallest
        val largest = stream.largest
        def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
          case d if d >= 0 && d <= maxDim => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty)
        }

    // Materializes dimension-major into a Vector-backed StratifiedCellStream, same pattern as
    // EngineComparisonBenchmarkSpec's materializeAndWrap -- avoids enumerating the source stream twice (once for a
    // cell count, once for reduction), which would double the enumeration cost this cell is specifically trying to
    // measure honestly.
    def materializeAndWrap(
      source: StratifiedCellStream[Simplex[Int], Double]
    ): (StratifiedCellStream[Simplex[Int], Double], Int) =
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

    // RipserCohomologyContext's own maxDimension now means "top homological degree reported," fixed at its
    // own source (see .claude/WORKLOG-maxdim-semantics-fix.md) -- homDim is passed directly, no +1 needed here
    // (unlike vrEnumNaiveAttempt below, which still builds one dimension higher manually).
    def ripserAttempt(n: Int, homDim: Int, regime: String): Either[String, (Double, Int, Int)] =
      withTimeout(ceilingTimeoutMs) {
        val pts = randomCloud(n, rngFor(s"ripser-$regime-$homDim", n))
        val metricSpace = EuclideanMetricSpace(pts)
        val threshold = thresholdFor(regime, n)
        val t0 = System.nanoTime()
        val ctx = RipserCohomologyContext[Double](metricSpace, homDim, maxFiltrationValue = Some(threshold))
        val bars = ctx.persistentCohomology()
        val ms = (System.nanoTime() - t0) / 1e6
        (ms, ctx.totalSimplexCount, bars.size)
      }

    def vrEnumNaiveAttempt(n: Int, buildDim: Int, regime: String): Either[String, (Double, Int, Int)] =
      withTimeout(ceilingTimeoutMs) {
        val pts = randomCloud(n, rngFor(s"vrenum-$regime-$buildDim", n))
        val metricSpace = EuclideanMetricSpace(pts)
        val threshold = thresholdFor(regime, n)
        val t0 = System.nanoTime()
        val stream =
          bounded(EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(threshold)), buildDim)
        val (wrapped, cellCount) = materializeAndWrap(stream)
        val barCount = SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(wrapped)
          .diagramAt(Double.PositiveInfinity)
          .size
        val ms = (System.nanoTime() - t0) / 1e6
        (ms, cellCount, barCount)
      }

    case class Step(n: Int, ms: Double, cells: Int, bars: Int)

    def findCeiling(label: String, attempt: Int => Either[String, (Double, Int, Int)]): Unit =
      var n = nStart
      var steps = 0
      var last10: Option[Step] = None
      var last30: Option[Step] = None
      var stopReason: String = "nCap/maxSteps reached"
      var stopped = false
      while !stopped && steps < maxSteps && n <= nCap && !deadlineHit do
        attempt(n) match
          case Right((ms, cells, bars)) =>
            val step = Step(n, ms, cells, bars)
            if ms <= fastBudgetMs then last10 = Some(step)
            last30 = Some(step)
            println(f"  [$label%-28s] n=$n%-6d ms=$ms%10.1f cells=$cells%-9d bars=$bars%-6d")
            n = math.max(n + 5, math.ceil(n * growthFactor).toInt)
            steps += 1
          case Left(err) =>
            stopReason = s"hit ceiling: $err"
            stopped = true
      if deadlineHit && !stopped then stopReason = "global deadline"
      def fmt(o: Option[Step]): String =
        o.map(s => f"n=${s.n}%-6d ms=${s.ms}%9.1f cells=${s.cells}%-8d bars=${s.bars}%-5d").getOrElse("(none reached)")
      println(f"$label%-28s <=${fastBudgetMs.toInt}%5dms: ${fmt(last10)}")
      println(f"${""}%-28s <=${ceilingTimeoutMs.toInt}%5dms: ${fmt(last30)}  [$stopReason]")
      println("")

    println(
      s"ambientDim=$ambientDim thresholdScale=$thresholdScale nStart=$nStart nCap=$nCap growthFactor=$growthFactor " +
        s"fastBudgetMs=$fastBudgetMs ceilingTimeoutMs=$ceilingTimeoutMs deadlineSeconds=$deadlineSeconds"
    )
    println(
      "homDim H is reported at the level a user cares about. Ripser now takes H directly (fixed at its own " +
        "source); VR-Enum+Naive still builds buildDim = H+1 simplices manually (see class doc)."
    )
    println("")

    val regimes = Seq("unbounded", "default", "sparse")

    for
      homDim <- homDims
      regime <- regimes
      if !deadlineHit
    do
      findCeiling(s"Ripser H=$homDim($regime)", n => ripserAttempt(n, homDim, regime))
      if !deadlineHit then
        val buildDim = homDim + 1
        findCeiling(s"VR-Enum+Naive H=$homDim($regime)", n => vrEnumNaiveAttempt(n, buildDim, regime))

    if deadlineHit then println("Global deadline reached -- remaining cells skipped (deadline).")

    success
  }
