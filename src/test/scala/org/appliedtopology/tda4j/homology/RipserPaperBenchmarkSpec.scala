package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}
import org.appliedtopology.tda4j.io.{given, *}

import org.specs2.mutable
import org.specs2.main.Arguments

import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.sys.process.*

/** Compares `RipserCohomologyContext` (this codebase's own reproduction of Bauer's Ripser algorithm, `Homology.scala`)
  * against real `ripser.cpp`, on the actual data sets used in Table 1 of the Ripser paper (arXiv:1908.02518, "Ripser:
  * efficient computation of Vietoris-Rips persistence barcodes") -- not the paper's own 2019-hardware numbers, which
  * aren't comparable to anything run today, but a same-machine, same-day re-measurement of real `ripser.cpp` (built
  * from `github.com/Ripser/ripser`, plain `make`, no `USE_COEFFICIENTS` -- its default build already computes over Z/2,
  * matching this class's `IntMod2.Fp` below) done alongside this spec. Like
  * `ProfilingSpec`/`ApparentPairsBenchmarkSpec`/`SparseRipsBenchmarkSpec`, this is a profiling script, not a
  * correctness check.
  *
  * '''Getting the data.''' The paper's `sphere3`/`o3` data sets are `ripser`'s own bundled examples; `random16`/
  * `dragon`/`fractal-r` come from the Otter et al. "roadmap" benchmark the paper cites. Exact URLs (confirmed against
  * the actual Dockerfile the paper's own `ripser-benchmark` repo uses to produce Table 1, not reconstructed from
  * memory):
  * {{{
  * BASE=https://raw.githubusercontent.com/Ripser/ripser-benchmark/master
  * curl -sO $BASE/sphere_3_192_points.dat
  * curl -sO $BASE/o3_1024.txt
  * curl -sO $BASE/o3_4096.txt
  * ROADMAP=https://github.com/n-otter/PH-roadmap/raw/master/data_sets
  * curl -sLo random_point_cloud_50_16_.txt $ROADMAP/roadmap_datasets_point_cloud/random_point_cloud_50_16_.txt
  * curl -sLo fractal_9_5_2_distmat.txt \
  *   $ROADMAP/roadmap_datasets_distmat/fractal_9_5_2_random_edge_list.txt_0.19795_distmat.txt
  * curl -sLo dragon_vrip_2000.txt $ROADMAP/roadmap_datasets_point_cloud/dragon_vrip.ply.txt_2000_.txt
  * }}}
  * `sphere3_48.dat`/`sphere3_96.dat` (the size-growth ladder below) are just `head -48`/`head -96` of
  * `sphere_3_192_points.dat`. These are third-party benchmark files (not this project's own data), so they are
  * deliberately NOT checked into the repo -- point `-DdataDir=` at wherever you downloaded them.
  *
  * '''`RipserCohomologyContext`'s `maxDimension` means "top homological degree reported," not "top simplex dimension
  * built" -- fixed at its own source since this spec first caught it (`Homology.scala`, see
  * `.claude/WORKLOG-maxdim-semantics-fix.md` for the fix itself and `.claude/WORKLOG-ripser-comparison.md` for how it
  * was originally found).''' A first version of this spec passed `c.maxDim` straight through before the fix landed,
  * which made every class at the requested top dimension spuriously essential (`coboundaryOf` was empty by construction
  * at `sigma.dim == maxDimension`) -- a real, well-known truncation artifact (H_k needs (k+1)-chains to resolve which
  * k-cycles actually die), not this engine's bug specifically. Real `ripser --dim p` never had this problem because it
  * always builds the `(p+1)`-skeleton internally to resolve dimension-`p` pairs (the paper's own Section 3.2:
  * "computing persistent homology in dimensions `0 <= d <= p` still requires reduction of the full boundary matrix
  * `d_{p+1}`") -- `RipserCohomologyContext` now does the same internally, so this spec passes `c.maxDim` directly with
  * no `+1`-and-filter workaround. That first version's un-worked-around run reported a 192-point sphere at `--dim 2`
  * producing over one million "bars" -- Table 2's entire non-zero-pair count for that same data set is 18 145 -- which
  * is what caught the bug in the first place; see `.claude/WORKLOG-ripser-comparison.md`.
  *
  * '''Zero-persistence bars''': `persistentCohomology()`'s own bar list includes zero-persistence bars deliberately
  * (`Homology.scala`'s "zero-length bars must not be silently dropped" -- required for the bars-account-for-cells
  * structural invariant elsewhere), but `ripser.cpp`'s own printed text output does not print persistence-0 intervals.
  * So the reference counts embedded below (`refBars`) are non-zero-persistence counts parsed from real `ripser`'s own
  * output, and this spec filters tda4j's own bars the same way (`lower != upper`) before comparing -- otherwise this
  * would be comparing two different things and calling the mismatch a bug.
  *
  * '''`torus4` (50000 points) is deliberately excluded''', not merely deferred: real `ripser.cpp` itself needs ~8GB for
  * it (Table 1), and this engine's `Simplex[Int]`/`SortedSet[Int]` per-simplex carrier is roughly two orders of
  * magnitude heavier than Ripser's packed 64-bit `diameter_index_t` (`DiameterSimplex`'s own doc, `RipserStream.scala`,
  * flags this as a deliberate deferred choice, not an oversight) -- extrapolating that ratio puts torus4 over what any
  * single machine reasonably has, so running it would test the JVM's OOM killer, not this engine. State the reason;
  * don't spend wall-clock time proving it.
  *
  * '''Coefficients''': ripser's default (no `USE_COEFFICIENTS`) build computes over Z/2, not the
  * `Field.DoubleApproximated` this codebase's other specs default to -- so this spec uses `FiniteField(2)` explicitly,
  * to make the two sides comparable rather than comparing different problems.
  *
  * '''Thresholds''': `o3_1024`/`o3_4096` pass `maxFiltrationValue` explicitly (1.8 / 1.4, matching the paper's own
  * `--threshold` flags) rather than relying on `minimumEnclosingRadius` -- the paper's own benchmark harness passes
  * these explicitly too, they are not this engine's computed enclosing radius. The no-threshold rows
  * (`sphere3`/`random16`/`dragon`/`fractal-r`) rely on this engine's `minimumEnclosingRadius` default, which is the
  * same `min_i max_j d(i,j)` quantity `ripser.cpp`'s own no-`--threshold` default (`enclosing_radius`) computes --
  * confirmed by reading both definitions directly, and empirically: this spec's own reference `ripser.cpp` run on
  * `sphere3` reported "using threshold at enclosing radius 1.97444" with no `--threshold` flag passed.
  *
  * '''Size-growth ladder''': `sphere3` is also run at `n = 48, 96, 192` (first rows of the same file, same `--dim 2`,
  * same no-threshold default), each against its own freshly-measured `ripser.cpp` reference -- this is what
  * distinguishes a roughly-constant "JVM/representation tax" from a slowdown ratio that grows with `n` (an algorithmic
  * problem), which a single-`n`-per-data-set table can't, since every row here already varies `n`, `maxDim`, ambient
  * dimension, and threshold simultaneously.
  *
  * '''Three-way comparison, not two''': every case now also runs `PackedRipserCohomologyContext`
  * (`PackedRipserCohomology.scala`) -- the parallel packed-`(Double, Long)` engine built to test whether eliminating
  * `Simplex[Int]`/`SortedSet[Int]` as the reduction-time carrier actually closes some of the ~20µs/simplex
  * constant-factor tax this same spec first measured (`.claude/WORKLOG-ripser-comparison.md`). See
  * `.claude/WORKLOG-packed-ripser-engine.md` for the measurement this table's own numbers feed into -- this class doc
  * states the methodology, that worklog states the result.
  *
  * Run with, e.g.:
  * {{{
  * sbt -J-Xmx16G -DdataDir=/path/to/downloaded/data -DtimeoutSeconds=300 \
  *   "testOnly org.appliedtopology.tda4j.RipserPaperBenchmarkSpec"
  * }}}
  *
  * '''`-DripserBin=<path>`, added in `.claude/WORKLOG-ripser-profiling.md`'s follow-up session''': when set, this spec
  * shells out to a REAL `ripser` binary at that path for every case (`--dim`/`--threshold`/`--format` built from each
  * `DataCase`'s own fields, matching the exact invocation this class's doc above already documents by hand) and times
  * it directly with `System.nanoTime()` around the subprocess call, instead of trusting the hardcoded
  * `DataCase.ripserMs`/`refBars` snapshot from 2026-09-17. This is what "orchestrate the real-ripser comparison from
  * within the benchmark test case code" means here: no separate script has to build the timing table by hand, run this
  * spec with the flag set and it reflects whatever machine it's actually running on, TODAY. Falls back to the hardcoded
  * `ripserMs`/`refBars` when the flag isn't given (the original, unchanged default behavior) -- this is additive, not a
  * replacement of the existing snapshot-comparison mode. Bar counts are parsed fresh from the real binary's own stdout
  * (`persistence intervals in dim K:` sections, counting lines until the next such header or EOF) rather than trusted
  * from `refBars` too, when this flag is set -- a genuine cross-check, not just a timing number. A companion shell
  * script, `.claude/scripts/run-ripser-paper-benchmark.sh`, builds a fresh vanilla `ripser.cpp` (from
  * `github.com/Ripser/ripser`, matching this class's own documented build), downloads the data sets from the URLs
  * already in this doc comment if missing, and invokes `sbt` with both `-DdataDir` and `-DripserBin` set -- meant to be
  * copied to and run directly on a machine (e.g. a compute server) that doesn't already have either.
  */
class RipserPaperBenchmarkSpec(args: Arguments) extends mutable.Specification:
  // Re-enable deliberately (uncomment) when actually running the benchmark -- see EngineComparisonBenchmarkSpec's
  // own doc for why these stay skipped by default (holds sbt's project-wide lock; no pass/fail signal either way
  // since this asserts nothing, just prints a table).
  skipAll
  "RipserCohomologyContext vs real ripser.cpp, on the paper's own data sets" >> {
    val dataDir: Option[String] = sys.props.get("dataDir").filter(_.nonEmpty)
    val timeoutSeconds: Int = sys.props.get("timeoutSeconds").map(_.toInt).getOrElse(180)

    dataDir match
      case None =>
        skipped(
          "pass -DdataDir=<path to downloaded ripser paper data sets> -- see this class's own doc for exact URLs"
        )
      case Some(dir) =>
        val IntMod2 = new FiniteField(2)
        import IntMod2.Fp
        import IntMod2.given

        // Routed through the `io` module (added in a later session -- see `.claude/WORKLOG-io-module.md`) instead
        // of this spec's own ad hoc parsing, which is exactly what motivated building `io.CSV` in the first
        // place: `CSV.readPointCloud`/`CSV.readFullDistanceMatrix` implement the identical
        // trim-filter-split-on-`[\s,]+` logic this spec used to hand-roll.
        def loadPointCloud(path: String): Array[Array[Double]] = CSV.readPointCloud(path)

        def loadDistanceMatrix(path: String): IndexedSeq[IndexedSeq[Double]] =
          CSV.readFullDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq

        // No cooperative cancellation exists in this engine -- see EngineComparisonBenchmarkSpec's own doc for
        // why a timeout only stops waiting, not the underlying computation. Daemon threads keep that from
        // blocking JVM/sbt exit.
        // lower/upper are different BarcodeEndpoint wrapper types (ClosedEndpoint at birth vs.
        // OpenEndpoint/PositiveInfinity at death) even for a genuine zero-persistence bar, so `lower != upper`
        // would always be true regardless of the actual values -- extract the underlying Double first.
        def endpointValue(e: barcode.BarcodeEndpoint[Double]): Double = e match
          case barcode.NegativeInfinity() => Double.NegativeInfinity
          case barcode.PositiveInfinity() => Double.PositiveInfinity
          case barcode.ClosedEndpoint(v)  => v
          case barcode.OpenEndpoint(v)    => v

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
            case e: OutOfMemoryError => Left(s"OutOfMemoryError: ${Option(e.getMessage).getOrElse("")}")
            case e: Throwable        => Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}".trim)

        case class DataCase(
          name: String,
          metricSpace: () => FiniteMetricSpace[Int],
          maxDim: Int,
          threshold: Double, // Double.NaN => this engine's own minimumEnclosingRadius default
          // Reference ripser.cpp numbers, measured on THIS machine on 2026-09-17 (Apple M1 Pro, 32GB RAM,
          // real ripser.cpp built from a fresh `git clone` + plain `make`), not the 2019 paper's numbers --
          // see the class doc and WORKLOG-ripser-comparison.md for the full re-measurement. `refBars` is
          // non-zero-persistence bars per dimension, parsed from ripser's own printed output (see class doc).
          // Both are used only as a FALLBACK when `-DripserBin` isn't set -- see that flag's own doc above.
          ripserMs: Double,
          refBars: Map[Int, Int],
          // The same underlying data file/format `metricSpace` above already loads, exposed separately so
          // `-DripserBin` mode can hand them to the real `ripser` binary directly rather than re-deriving them.
          dataFile: String,
          ripserFormat: String // "point-cloud" or "distance" -- real ripser's own `--format` flag values
        )

        // Ordered smallest-complex-first (Table 2's total-pairs column, not raw n -- random16's n=50 is
        // misleading once its maxDim=7 is accounted for), so a run that starts failing partway through still
        // reports every smaller case it completed.
        val cases = Seq(
          DataCase(
            "sphere3_48",
            () => EuclideanMetricSpace(loadPointCloud(s"$dir/sphere3_48.dat")),
            2,
            Double.NaN,
            10,
            Map(0 -> 48, 1 -> 14, 2 -> 1),
            s"$dir/sphere3_48.dat",
            "point-cloud"
          ),
          DataCase(
            "sphere3_96",
            () => EuclideanMetricSpace(loadPointCloud(s"$dir/sphere3_96.dat")),
            2,
            Double.NaN,
            50,
            Map(0 -> 96, 1 -> 22, 2 -> 1),
            s"$dir/sphere3_96.dat",
            "point-cloud"
          ),
          DataCase(
            "sphere3_192",
            () => EuclideanMetricSpace(loadPointCloud(s"$dir/sphere_3_192_points.dat")),
            2,
            Double.NaN,
            660,
            Map(0 -> 192, 1 -> 53, 2 -> 1),
            s"$dir/sphere_3_192_points.dat",
            "point-cloud"
          ),
          DataCase(
            "dragon",
            () => EuclideanMetricSpace(loadPointCloud(s"$dir/dragon_vrip_2000.txt")),
            1,
            Double.NaN,
            1150,
            Map(0 -> 2000, 1 -> 576),
            s"$dir/dragon_vrip_2000.txt",
            "point-cloud"
          ),
          DataCase(
            "o3_1024",
            () => EuclideanMetricSpace(loadPointCloud(s"$dir/o3_1024.txt")),
            3,
            1.8,
            1570,
            Map(0 -> 1024, 1 -> 576, 2 -> 180, 3 -> 7),
            s"$dir/o3_1024.txt",
            "point-cloud"
          ),
          DataCase(
            "fractal-r",
            () => ExplicitMetricSpace(loadDistanceMatrix(s"$dir/fractal_9_5_2_distmat.txt")),
            2,
            Double.NaN,
            3030,
            Map(0 -> 512, 1 -> 438, 2 -> 659),
            s"$dir/fractal_9_5_2_distmat.txt",
            "distance"
          ),
          DataCase(
            "random16",
            () => EuclideanMetricSpace(loadPointCloud(s"$dir/random_point_cloud_50_16_.txt")),
            7,
            Double.NaN,
            3440,
            Map(0 -> 50, 1 -> 39, 2 -> 16, 3 -> 5, 4 -> 3, 5 -> 0, 6 -> 0, 7 -> 0),
            s"$dir/random_point_cloud_50_16_.txt",
            "point-cloud"
          ),
          DataCase(
            "o3_4096",
            () => EuclideanMetricSpace(loadPointCloud(s"$dir/o3_4096.txt")),
            3,
            1.4,
            30790,
            Map(0 -> 4096, 1 -> 2466, 2 -> 811, 3 -> 33),
            s"$dir/o3_4096.txt",
            "point-cloud"
          )
        )

        // -DcaseNames=fractal-r,o3_4096 restricts the run to specific cases by name -- added to give a targeted
        // re-run (e.g. a longer per-case -DtimeoutSeconds budget) to just the cases that didn't finish in an
        // earlier pass, without re-running every smaller case's already-known-good numbers ahead of them.
        val caseNames: Option[Set[String]] =
          sys.props.get("caseNames").filter(_.nonEmpty).map(_.split(",").map(_.trim).toSet)
        val selectedCases = caseNames match
          case None        => cases
          case Some(names) => cases.filter(c => names.contains(c.name))

        // c.threshold uses Double.NaN as its own "no explicit threshold, fall back to this engine's own
        // minimumEnclosingRadius default" sentinel (matching real ripser's --threshold-omitted behavior, see
        // runRealRipser's own identical `if c.threshold.isNaN then Seq.empty else ...` check below) -- a
        // holdover from when RipserCohomologyContext/PackedRipserCohomologyContext's own maxFiltrationValue
        // parameter was itself a raw NaN-sentineled Double, before it became Option[Double] (see CLAUDE.md's
        // "maxFiltrationValue Option refactor" entry). `Some(c.threshold)` unconditionally, as both engine
        // constructor calls below used to do, wraps that NaN sentinel in a Some instead of passing None --
        // since Option[Double] resolves its own default via `.getOrElse`, only actually consulted when the
        // caller passes None, `Some(Double.NaN)` bypasses minimumEnclosingRadius entirely and uses NaN as the
        // literal threshold. Every simplex-inclusion check compares against it as `diameter <= threshold`,
        // which is false for any diameter since any comparison against NaN is false -- so every simplex above
        // dimension 0 (vertices aren't threshold-checked) gets silently excluded, collapsing every no-threshold
        // case's dimension >= 1 output to empty. This spec is skipAll'd by default (see the class's own note
        // above), so the Option refactor's own verification never exercised it -- found only when someone
        // explicitly ran this benchmark and noticed dimension >= 1 bar counts were all zero.
        def effectiveThreshold(c: DataCase): Option[Double] = if c.threshold.isNaN then None else Some(c.threshold)

        def barsMatchStr(byDim: Map[Int, Int], refBars: Map[Int, Int]): String =
          val allDims = (byDim.keySet ++ refBars.keySet).toSeq.sorted
          val mismatches = allDims.filter(d => byDim.getOrElse(d, 0) != refBars.getOrElse(d, 0))
          if mismatches.isEmpty then "yes"
          else
            mismatches.map(d => s"d$d:${byDim.getOrElse(d, 0)}vs${refBars.getOrElse(d, 0)}").mkString("NO(", ",", ")")

        // -DripserBin=<path> -- see this class's own doc comment above for the full rationale. Shells out to a
        // REAL ripser binary, built and invoked exactly the way this class's doc already documents by hand
        // (`--dim`/`--threshold`/`--format`), and times it directly with `System.nanoTime()` around the
        // subprocess call -- the same "wall clock around the call" approach `withTimeout` already uses for the
        // Scala engines, so all three numbers in one table row are measured the same way. Inherits the same
        // no-cooperative-cancellation caveat `withTimeout`'s own doc states: a `-DtimeoutSeconds` that elapses
        // stops WAITING on the subprocess, not the subprocess itself.
        val ripserBinProp: Option[String] = sys.props.get("ripserBin").filter(_.nonEmpty)

        // A single subprocess invocation of `ripser` on a small case (tens of milliseconds) is dominated by
        // process-launch noise, not the actual VR computation -- already flagged as a caveat in
        // `WORKLOG-ripser-comparison.md` and confirmed directly this session: a single run of `sphere3_96`
        // varied between ~42ms (5-trial median via a standalone script) and ~107ms (one-off), a >2x spread on
        // the SAME binary/data/machine. `ripserTrials` (default 5) re-runs the SAME subprocess this many times
        // and takes the MEDIAN elapsed time -- cheap for small/fast cases (where the noise matters most) and
        // still bounded for slow ones (a 30s case costs at most `ripserTrials`x its own runtime, not
        // `ripserTrials` times some larger multiple). Bar counts are parsed from the LAST trial's stdout only
        // (they don't vary between runs, only timing does, so re-parsing every trial would be wasted work).
        val ripserTrials: Int = sys.props.get("ripserTrials").map(_.toInt).getOrElse(5)

        def runRealRipser(ripserBin: String, c: DataCase): Either[String, (Double, Map[Int, Int])] =
          withTimeout {
            val args = Seq(ripserBin, "--dim", c.maxDim.toString, "--format", c.ripserFormat) ++
              (if c.threshold.isNaN then Seq.empty else Seq("--threshold", c.threshold.toString)) :+
              c.dataFile
            var lastOutput: String = ""
            val times = (1 to ripserTrials).map { _ =>
              val t0 = System.nanoTime()
              lastOutput = args.!!
              (System.nanoTime() - t0) / 1e6
            }.sorted
            val medianMs = times(times.size / 2)
            // Every line ripser prints under a "persistence intervals in dim K:" header is already a
            // non-zero-persistence bar -- its own text output omits persistence-0 intervals (this class's doc
            // comment above, confirmed against real ripser's source) -- so a raw per-section line count is
            // directly comparable to `refBars`/this spec's own `byDim` computation, no further filtering needed.
            val dimHeader = """persistence intervals in dim (\d+):""".r
            var currentDim: Option[Int] = None
            val byDim = scala.collection.mutable.Map.empty[Int, Int]
            for line <- lastOutput.linesIterator do
              dimHeader.findFirstMatchIn(line) match
                case Some(m) => currentDim = Some(m.group(1).toInt)
                case None    =>
                  if line.trim.startsWith("[") then currentDim.foreach(d => byDim(d) = byDim.getOrElse(d, 0) + 1)
            (medianMs, byDim.toMap)
          }

        println(
          if ripserBinProp.isDefined then
            s"Real ripser.cpp binary: ${ripserBinProp.get} (live-measured on this run, not the hardcoded 2026-09-17 snapshot)"
          else "No -DripserBin given -- using the hardcoded 2026-09-17 M1 Pro reference snapshot (see class doc)"
        )
        println(
          f"${"case"}%-13s${"ripser(ms)"}%-11s${"SortedSet(ms)"}%-14s${"x"}%-8s${"packed(ms)"}%-11s${"x"}%-8s" +
            f"${"S/pack"}%-8s${"bars ok"}%-9s${"status"}%-20s"
        )

        // -DpackedOnly=true skips RipserCohomologyContext entirely and times only the packed engine. Added
        // after this table's default dual-engine mode was found to give misleading numbers on the paper's
        // larger cases: `withTimeout`'s `Future` has no cooperative cancellation (per its own doc above), so
        // once RipserCohomologyContext times out on a case, that computation keeps running on its daemon
        // thread, competing for CPU/heap with the packed engine's own timed run on the SAME case and every
        // case after it -- observed directly as growing JVM resident size and a cascade of spurious "packed:
        // timeout" results once RipserCohomologyContext started timing out (`WORKLOG-packed-ripser-engine.md`).
        // Only safe to trust the dual-engine table's ratio columns for cases where BOTH engines actually
        // finished; anything after the first timeout should be re-measured with this flag instead.
        val packedOnly = sys.props.get("packedOnly").contains("true")
        for c <- selectedCases do
          // Live-measure real ripser.cpp first (before either Scala engine), same run order rationale as
          // SortedSet-then-packed below -- a consistent, documented bias rather than a counterbalanced median,
          // matching this spec's existing single-trial convention. Falls back to the hardcoded snapshot when
          // `-DripserBin` isn't set, or reports its own failure reason (e.g. a bad path, or the binary itself
          // erroring on this data set) rather than silently reusing the snapshot on a live-mode failure.
          val liveRipserOutcome: Option[Either[String, (Double, Map[Int, Int])]] =
            ripserBinProp.map(bin => runRealRipser(bin, c))
          val effectiveRipserMs: Double = liveRipserOutcome match
            case Some(Right((ms, _))) => ms
            case _                    => c.ripserMs
          val effectiveRefBars: Map[Int, Int] = liveRipserOutcome match
            case Some(Right((_, bars))) => bars
            case _                      => c.refBars
          val liveRipserFailure: Option[String] = liveRipserOutcome.flatMap(_.left.toOption)
          // Both engines timed in the SAME run, same JVM warmup state, so the SortedSet-vs-packed ratio isn't
          // biased by one of them going first every time the way separate spec runs would be -- run order here
          // is SortedSet then packed for every case, a consistent (if not counterbalanced) bias, noted rather
          // than eliminated (median-of-several trials would fix this properly; out of scope for a single-run
          // profiling script -- see class doc's own "single trial" precedent in the sibling benchmark specs).
          val sortedSetOutcome =
            if packedOnly then Left("skipped")
            else
              withTimeout {
                val ms = c.metricSpace()
                val t0 = System.nanoTime()
                // RipserCohomologyContext's own maxDimension now means "top homological degree reported" (fixed at
                // the source -- see .claude/WORKLOG-maxdim-semantics-fix.md), so c.maxDim is passed directly; no
                // manual +1-and-filter workaround needed anymore (a first version of this spec had one, which is
                // exactly what caught the semantics bug in the first place -- see the class doc above).
                val ctx = RipserCohomologyContext[Fp](ms, c.maxDim, maxFiltrationValue = effectiveThreshold(c))
                val allBars = ctx.persistentCohomology()
                val elapsedMs = (System.nanoTime() - t0) / 1e6
                val nonZero = allBars.filter(b => endpointValue(b.lower) != endpointValue(b.upper))
                val byDim = nonZero.groupBy(_.dim).view.mapValues(_.size).toMap
                (elapsedMs, byDim, ctx.totalSimplexCount)
              }
          val packedOutcome = withTimeout {
            val ms = c.metricSpace()
            val t0 = System.nanoTime()
            val ctx = PackedRipserCohomologyContext[Fp](ms, c.maxDim, maxFiltrationValue = effectiveThreshold(c))
            val allBars = ctx.persistentCohomology()
            val elapsedMs = (System.nanoTime() - t0) / 1e6
            val nonZero = allBars.filter(b => endpointValue(b.lower) != endpointValue(b.upper))
            val byDim = nonZero.groupBy(_.dim).view.mapValues(_.size).toMap
            (elapsedMs, byDim, ctx.totalSimplexCount)
          }

          val sortedSetMs = sortedSetOutcome.toOption.map(_._1)
          val packedMs = packedOutcome.toOption.map(_._1)
          // `packedOnly` deliberately leaves `sortedSetOutcome` as a fixed `Left("skipped")` placeholder (see
          // this flag's own doc comment above) -- both `barsOk` and `status` need to look past that placeholder
          // to whatever `packedOutcome` actually did, or a real packed timeout/OOM silently prints as the
          // uninformative "SortedSet: skipped" (found the hard way: a run that DID distinguish a packed timeout
          // from a packed OutOfMemoryError would have been directly useful, and this bug threw that information
          // away -- see WORKLOG-packed-ripser-engine.md's later update).
          val barsOk =
            if packedOnly then
              packedOutcome.toOption.map((_, pkBars, _) => barsMatchStr(pkBars, effectiveRefBars)).getOrElse("-")
            else
              (sortedSetOutcome, packedOutcome) match
                case (Right((_, ssBars, _)), Right((_, pkBars, _))) =>
                  val ssVsRef = barsMatchStr(ssBars, effectiveRefBars)
                  val pkVsRef = barsMatchStr(pkBars, effectiveRefBars)
                  if ssVsRef == "yes" && pkVsRef == "yes" then "yes" else s"SS:$ssVsRef,PK:$pkVsRef"
                case _ => "-"
          val status =
            liveRipserFailure match
              case Some(e) => s"ripser: $e"
              case None    =>
                if packedOnly then
                  packedOutcome match
                    case Left(e)  => s"packed: $e"
                    case Right(_) => "ok (packed only)"
                else
                  (sortedSetOutcome, packedOutcome) match
                    case (Left(e), _) => s"SortedSet: $e"
                    case (_, Left(e)) => s"packed: $e"
                    case _            => "ok"

          def fmtMs(o: Option[Double]): String = o.map(v => f"$v%.1f").getOrElse("-")
          def fmtRatio(o: Option[Double]): String = o.map(v => f"${v}%.2fx").getOrElse("-")

          println(
            f"${c.name}%-13s${effectiveRipserMs}%-11.1f${fmtMs(sortedSetMs)}%-14s" +
              f"${fmtRatio(sortedSetMs.map(_ / effectiveRipserMs))}%-8s${fmtMs(packedMs)}%-11s" +
              f"${fmtRatio(packedMs.map(_ / effectiveRipserMs))}%-8s" +
              f"${fmtRatio(for s <- sortedSetMs; p <- packedMs yield s / p)}%-8s$barsOk%-9s$status%-20s"
          )

    success
  }
