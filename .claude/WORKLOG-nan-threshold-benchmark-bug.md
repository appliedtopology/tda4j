# A stale `Some(possibly-NaN)` call site broke two benchmark specs' no-threshold rows (2026-09-21)

Reported by the project lead: ran `RipserPaperBenchmarkSpec` (with `-DripserBin=` pointing at a live-built
`ripser.cpp`) on a compute server at commit `ed58f4136e662c1f6909fa2f6e332f9bfe0ae6ca` and got a table with
obviously-wrong-looking numbers -- bar counts like `d1:0vs14`, `d1:0vs22`, `d1:0vs576` across nearly every case,
and timings that didn't grow monotonically with `n` (`sphere3_48` SortedSet 163.7ms, `sphere3_96` 54.9ms,
`sphere3_192` 72.9ms) where they obviously should. Asked whether this was a concurrency issue.

## Root cause: not concurrency

`RipserCohomologyContext`/`PackedRipserCohomologyContext`'s `maxFiltrationValue` parameter is `Option[Double] =
None`, resolved internally via `.getOrElse(metricSpace.minimumEnclosingRadius)` (`.claude/CLAUDE.md`'s
"maxFiltrationValue Option refactor" entry, 2026-09-19) -- this used to be a raw `Double` parameter with
`Double.NaN` as its own "no threshold, use the default" sentinel, before that refactor.

`RipserPaperBenchmarkSpec`'s `DataCase.threshold` field kept the OLD convention (`Double.NaN` meaning "use the
engine's own default") -- reasonably, since it also feeds the real `ripser.cpp` subprocess invocation, which
still takes a raw threshold and needs the `isNaN` check to decide whether to pass `--threshold` at all. But the
spec's two Scala-engine constructor calls wrapped it as `maxFiltrationValue = Some(c.threshold)`
UNCONDITIONALLY -- for every case with no explicit threshold (`sphere3_48/96/192`, `dragon`, `fractal-r`,
`random16`), this passes `Some(Double.NaN)`, not `None`. Since `Option[Double]`'s own default only fires on
`None`, `.getOrElse` is never consulted, and the engine uses `NaN` as the LITERAL threshold. Every candidate
simplex's inclusion check (`sparseCofacets`, `Homology.scala`) is `diameter <= maxFiltrationValue` --
comparisons against `NaN` are always `false` -- so every simplex above dimension 0 is silently excluded.
Vertices themselves are seeded directly (never routed through `sparseCofacets`), so dimension 0 is unaffected;
dimension >= 1 collapses to empty. Exactly the observed `d1:0vs...` pattern, and exactly why `o3_1024` (the one
case with a REAL explicit threshold, 1.8, not NaN) was the only row reporting `yes`.

The erratic-looking timings are a downstream symptom, not a separate bug: with dimension >= 1 empty, the
"computation" for every no-threshold case is trivial (O(n), just processing vertices) regardless of `n` --
what's left to measure is JIT/GC/scheduling noise around near-nothing, which is why it doesn't grow
monotonically with `n` the way a real VR computation would. `o3_1024`'s own numbers (868s SortedSet / 395s
packed against 2.97s real ripser, ~292x/~133x) are genuine and within the range this codebase's own prior
sessions already measured for other cases (`51-302x`, per [[tda4j_packed_ripser_engine]]) -- not a symptom of
this bug, since its threshold was never NaN.

This spec is `skipAll`'d by default (a documented, deliberate choice -- see the class's own doc and
`EngineComparisonBenchmarkSpec`'s), so the `maxFiltrationValue` refactor's own verification pass never actually
exercised it -- the refactor's own worklog states its verification was against the normal `sbt test` suite,
which never runs this spec. Nobody would have noticed until someone explicitly ran it, which is exactly what
happened here.

## A second, independent instance of the identical bug, found by grepping for the same pattern

`DimensionCeilingBenchmarkSpec` (NOT `skipAll`'d -- it IS part of routine `sbt test`) has its own `thresholdFor`
helper with a `"default"` regime returning `Double.NaN`, and its own two call sites (`ripserAttempt`,
`vrEnumNaiveAttempt`) wrapped it in `Some(threshold)` the same way. Since this spec asserts nothing about its
own printed numbers (a profiling script, per its class doc, not a correctness check), it never failed despite
every `"(default)"` row being wrong the whole time -- confirmed directly in THIS session's own earlier full-`sbt
test` output, which showed `cells=n, bars=n` for every `(default)` row (e.g. `n=15 cells=15 bars=15`) --
vertices only, exactly the same collapse.

Checked every other `Some(...)`-wrapped `maxFiltrationValue`/threshold call site in the codebase
(`grep -rn "maxFiltrationValue = Some("` across `src/`) and every file using `Double.NaN` at all
(`grep -rln "Double.NaN"`) to rule out further instances -- `SparseRipsBenchmarkSpec` and
`VRLowDimProfileDriver` both compute their threshold as `thresholdScale / sqrt(n)` (always a real finite
number) or pass `Double.PositiveInfinity` directly, neither ever NaN. `AlphaComplexDQP.scala`'s own `Double.NaN`
use is unrelated (the QP solver, not `maxFiltrationValue`). These two files were the only ones affected.

## Fix

Both files gained a small `effectiveThreshold` helper (`if threshold.isNaN then None else Some(threshold)`,
adapted to each file's own existing structure) and their engine-construction call sites now route through it
instead of `Some(...)` directly. `runRealRipser`'s own subprocess-argument construction in
`RipserPaperBenchmarkSpec` already had the correct `isNaN` check and was left untouched.

## Verification

`DimensionCeilingBenchmarkSpec` re-run directly (it needs no external data, unlike the paper spec): every
`"(default)"` row now shows real, `n`-dependent growth (e.g. Ripser H=3(default): `n=15 cells=560 bars=352` up
to `n=53 cells=57067 bars=48615`, vs. the previous `cells=n, bars=n` at every `n`) -- confirms the fix, not just
the diagnosis. `RipserPaperBenchmarkSpec` itself could not be re-run here (needs the paper's own downloaded data
sets, not present in this environment) -- the project lead's own compute-server run is what should be re-run to
confirm the fixed table, once this fix is picked up there. Full `sbt test` (unaffected files) stays clean.
