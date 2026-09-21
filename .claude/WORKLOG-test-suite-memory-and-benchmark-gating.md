# Test suite OOM root cause + flag-gated benchmark specs (2026-09-21)

Reported by the project lead: `sbt test` kept hanging or hitting OOM. Asked for two things: an easy way to bump
the test JVM's heap to ~2G, and/or defaulting benchmark-shaped specs to not run.

## Root cause of the OOM: not a code bug, a launcher default

`sbt` (via `sdkman`, launcher script version 1.9.7) hardcodes `sbt_default_mem=1024` and only skips adding
`-Xmx1024m` if `-Xmx` already appears in `java_args`/`JAVA_TOOL_OPTIONS`/`sbt_options` — none of which this repo
set. `Test / fork` is `false` (the default, unchanged), so the test JVM *is* sbt's own JVM. Confirmed directly,
not inferred from reading the script: `sbt -batch "eval java.lang.Runtime.getRuntime.maxMemory/1048576"` returned
`1024` on unmodified `HEAD`. This matches the project's own prior documented OOM (`CLAUDE.md`'s "Cross-engine
benchmark" section: `OutOfMemoryError` after ~3 minutes, up to 515% GC time, on a 1GB heap) — the suite has grown
substantially since then (io module, CLI, Cech, simplicial sets, chunks representatives, several property specs
with `minTestsOk` in the thousands), so 1GB was always going to become insufficient again, not a one-off fluke.

Fixed with a repo-root `.jvmopts` (`-Xmx2G`) — read automatically by the sbt launcher (`loadConfigFile .jvmopts`
at line 780 of the launcher script), applies to local runs and CI (GitHub Actions' `sbt/setup-sbt` action uses
the same launcher convention) with no `build.sbt` changes. Re-verified after adding the file: `maxMemory` reports
`2048`. Deliberately just `-Xmx2G`, no `-Xms`/`-XX:MaxMetaspaceSize` — advisor flagged both as scope creep (a warm
floor isn't needed; a metaspace cap only adds a new OOM mode without a demonstrated need).

## Benchmark specs: flag-gated, not hardcoded `skipAll`

Separately, 5 of the suite's "prints a timing table, not a correctness check" specs ran unconditionally under
plain `sbt test` (`ApparentPairsBenchmarkSpec`, `CubicalBenchmarkSpec`, `SparseRipsBenchmarkSpec`,
`DimensionCeilingBenchmarkSpec`, `ProfilingSpec`); 2 more were already excluded but via a hardcoded `skipAll`
that had to be hand-edited (and reverted) to actually run (`EngineComparisonBenchmarkSpec`,
`RipserPaperBenchmarkSpec`) — the latter's own companion script,
`.claude/scripts/run-ripser-paper-benchmark.sh`, existed specifically to automate that edit-and-revert via `sed`
+ an `EXIT` trap.

Per advisor's caution against gating on inference (`[[tda4j_measure_dont_infer]]`): measured before deciding,
rather than trusting each spec's own "kept small so this is safe under plain `sbt test`" doc comments. A full
`sbt test` completed successfully at 2G heap (no OOM) — the benchmark specs' own defaults were, in fact, not what
was causing the OOM (their total added wall-clock was real but modest); the two slowest contributors in that run
were ordinary correctness specs (`APISpec`, `RipserCohomologySpec`), not the benchmarks. So the two asks are
separate fixes for separate problems, not one fix wearing two hats: the heap bump addresses the OOM; the
benchmark gating addresses wall-clock and (per the project lead's own mid-session request) lets a real benchmark
run be toggled with a flag instead of an edit-and-revert.

All 7 specs now share one gate, evaluated once at spec construction:

```scala
if !args.commandLine.boolOr("runBenchmarks", false) then skipAll
```

`sbt test` skips all seven by default; `sbt -DrunBenchmarks=true test` runs them (scope with `testOnly` for a
single one — `EngineComparisonBenchmarkSpec` alone can take 15+ minutes and holds sbt's project-wide lock the
whole time). Each spec's own existing `-DminSize=`/`-Dtrials=`-style args are unchanged — this is an on/off
switch layered on top, not a replacement for them. Verified both directions with real `testOnly` runs, not just
read the code: `SparseRipsBenchmarkSpec` with no flag finished in 45ms reporting `SKIPPED`; with
`-DrunBenchmarks=true` it printed its real sparse-vs-dense table. `RipserPaperBenchmarkSpec` under the flag (no
`-DdataDir`) correctly fell through to its own second gate and printed its existing "pass -DdataDir=..." skip
message — confirming the two independent gates (shared flag, then the spec's own data-availability check)
compose the way they're supposed to.

`HomologySpec`'s `BarcodeRegressionSpec` was deliberately NOT folded into this flag — it's `skipAll`'d
unconditionally because it's a correctness spec that currently stalls/OOMs on its own generator range (a known
open bug tracked separately, not something anyone would want to opt into via a "run the benchmarks" flag).

**A real consequence found while updating the companion script**: `run-ripser-paper-benchmark.sh`'s Step 3 used
to `sed`-toggle a literal `  skipAll` line (restored via an `EXIT` trap) — that line no longer exists in
`RipserPaperBenchmarkSpec.scala`, so the sed would have silently no-matched (no error under `set -euo pipefail`,
since `sed -i` doesn't fail on zero matches) and the benchmark would have just stayed skipped with no indication
anything was wrong. Fixed by deleting the whole toggle-and-trap mechanism and adding `-DrunBenchmarks=true`
directly to the script's `sbt` invocation — simpler than before, not just updated to match, since the entire
reason that mechanism existed (avoiding a manual source edit) is now the flag's own job.

## Verified

Full `sbt clean test` at 2G heap, all edits on disk (no race against an in-flight compile, unlike a first,
messier run mid-session where edits landed while zinc was still reading files — discarded, not reported as a
real result): 67s wall-clock (was ~102s in the messy mixed-gate run, and unmeasured/OOM-prone before the heap
fix), 325 examples, **0 failures, 0 errors**, 10 skipped (up from 5 — the 5 newly-gated-by-default specs), 1
pending. All 7 gated specs finish in single-digit-to-low-triple-digit milliseconds under plain `sbt test`
(9ms–368ms) instead of seconds-to-minutes. `CLAUDE.md`'s "Commands" section documents the new
`-DrunBenchmarks=true` lever.
