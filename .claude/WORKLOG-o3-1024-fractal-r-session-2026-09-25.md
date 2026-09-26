# Session worklog: closing out the o3_1024 packed-engine anomaly, opening fractal-r

Date: 2026-09-25/26. Point-in-time record, not retroactively edited (see [[tda4j-worklog-convention]]).
Direct continuation of `.claude/WORKLOG-packed-ripser-engine.md`/`WORKLOG-ripser-profiling.md`/
`WORKLOG-ripser-comparison.md` — this session picks up from a fresh same-machine compute-server table
(real `ripser.cpp` live-built, not the hardcoded snapshot) that showed two anomalies against the established
15-45x packed-vs-SortedSet pattern: `o3_1024`'s S/pack ratio collapsed to 1.73x, and `fractal-r` timed out
for the packed engine while `RipserCohomologyContext` (SortedSet) itself finished in ~89 minutes.

## Tooling added this session

- `RipserPaperBenchmarkSpec.scala`: prints `totalSimplexCount`/packed `substitutionCount` per case now.
- `SingleEngineProfileDriver.scala`: gained distance-matrix input + threshold support (previously
  point-cloud-only, no threshold — couldn't reach `fractal-r`/`o3_1024` at all), plus `substitutionCount`
  printed alongside timing.
- `.claude/scripts/run-single-engine-jfr.sh`: wraps the driver with JFR, no sbt/timeout machinery, for a
  clean, no-time-budget profiling pass on one case.
- `.claude/scripts/jfr-summarize.sh`: reduces a JFR recording to a small pasteable text summary (top-N CPU
  leaf frames, allocation by call site and by class) piped directly from `jfr print`, never writing the raw
  event stream to disk — needed because the recordings themselves are far too large to transfer/paste and
  don't belong in git regardless of size.

## `o3_1024`: root-caused and fixed, three separate real bugs

Ambient dimension confirmed at 9 (not 3 like `sphere3`/`dragon`) — this is what made this case different.

| stage | packed | SortedSet | S/pack | commit |
|---|---:|---:|---:|---|
| original | 452,193.8ms | 731,192.8ms | 1.73x | (pre-session) |
| + `EuclideanMetricSpace` pairwise distance cache | 253,705.0ms | 601,825.6ms | 2.37x | `7b5ad07` |
| + apparent-pairs early-exit (`zeroPivotCofacet`/`zeroPivotFacet`) | 63,721.4ms | 353,798.6ms | 5.55x | `561776e` |
| + `SimplexIndexing.apply` encode-chain collapse (SortedSet-only) | ~52-64s (noise band, unaffected) | 237,879.6ms | ~3.7-4.5x | `9461134` |
| + `MaximumDistanceFiltrationValue` allocation-free rewrite (shared, 16 files) | not yet re-measured | not yet re-measured | — | `7b17442` |

Also landed: a trivial `pointSqDistance` loop-invariant hoist (`859d0ac`, found alongside the cache work,
zero design tradeoff).

**Fix 1 (`7b5ad07`): `EuclideanMetricSpace(cacheDistances: Boolean = true)`.** JFR found `pointSqDistance`/
`insertionDiameter` at 82% of packed's CPU, 43% of SortedSet's — ambient-dimension-9 geometry recomputed far
more often than there are distinct point pairs (`C(n,2)` vs. tens of millions of calls). Precomputes every
pairwise distance once, O(1) lookup after. Bounded by point count (not complex size), unlike
`memoizeFiltrationValue` (which stays `false` by design). Opt-in flag, defaults `true` per explicit
project-lead call (asked via `AskUserQuestion` — this is a real memory/speed tradeoff on shared, foundational
code, not something to decide unilaterally).

**Fix 2 (`561776e`): early-exit in `zeroPivotCofacet`/`zeroPivotFacet`, both engines.** Even after Fix 1,
`insertionDiameter` was STILL the dominant frame in both engines (68.59%/32.32%) — traced to
`zeroPivotCofacet`'s own full, unconditional sweep over every cofacet candidate of every simplex (2.48M
calls), tracking a running best instead of stopping at the first match. Verified BEFORE relying on it (new
`SimplexIndexingSpec` property test, 500 trials): `CofacetCursor.index` is strictly decreasing and
`FacetCursor.index` is strictly increasing across successive `advance()` calls, so the first candidate tied
at the target diameter is already the extremal one either method searches for. Matches real `ripser.cpp`'s
own `get_zero_pivot_cofacet`, which returns on first match for the same reason. Applied identically to
`RipserCohomologyContext` (`Homology.scala`) and `PackedRipserCohomologyContext`.

**Fix 3 (`9461134`): `SimplexIndexing.apply`'s five-stage encode chain collapsed into a `while` loop.**
Post-fix-2 profile showed `apply`'s `toSeq.sorted.reverse.zipWithIndex.map(...).sum` (plus its own uncached
`BinomialCoefficient`/`gcd` calls) at ~60% of SortedSet's remaining CPU — SortedSet-only, since packed's
`DiameterIndex` never needs re-encoding. This was previously named and explicitly DECLINED in
`WORKLOG-ripser-profiling.md`'s cursor-redesign session, on "SortedSet's job is legibility, not speed" —
revisited and reversed for this specific fix given SortedSet's absolute timing was now actively part of the
same investigation (asked via `AskUserQuestion`, answered yes).

**Fix 4 (`7b17442`): `FiniteMetricSpace.MaximumDistanceFiltrationValue.apply` rewritten allocation-free.**
Post-fix-3 profile showed the OLD `spx.flatMap(v => spx.toSeq.filter(_ > v).map(w => distance(v,
w))).max` chain at ~30% of remaining CPU — but the majority of ITS call volume traced to
`cohomologyOrdering.compare` (consulted on every `Chain.reduceBy` comparison, by documented design, not a
bug), not the smaller number of once-per-simplex lookups the earlier worklog's own scoped fix
(`zeroPivotFacet`-only) would have addressed. This method is shared by 16 files (every stream construction in
the codebase, not just the two Ripser engines) — a materially bigger blast radius than fixes 1-3, so asked
explicitly before touching it (`AskUserQuestion`, answered yes, full-suite-validate). Rewritten as one
`.toIndexedSeq` snapshot + a plain `i<j` double `while` loop — `.toArray` wasn't usable without adding a
`ClassTag[VertexT]` bound this class's signature doesn't carry (fully generic `VertexT`, unlike the
`Simplex[Int]`-specialized methods elsewhere). Full `sbt test` (600 examples, 589/0/0/11, unchanged) after
every one of these four fixes, not just at the end.

**Status**: `o3_1024` reached diminishing returns for this session. Packed's own profile after fix 2 showed
no further mechanism to chase (confirmed by a THIRD near-identical profile showing no new inefficiency, just
run-to-run noise on the exact timing — 63.7s vs. 52.4s, same shape both times). SortedSet's remaining cost
(`RedBlackTree`/`TreeSet` iteration) is the inherent, by-design cost of that engine's representation, not a
bug — not something to fix without effectively rebuilding it as packed. Fix 4 hasn't been re-measured on
`o3_1024` yet (landed right as attention moved to `fractal-r`'s finished JFR) — worth a fresh pair of runs
whenever convenient, expected direction: further SortedSet improvement, S/pack ratio moving back up somewhat.

**A genuine, explicitly-flagged methodology gap**: every number above is a SINGLE trial
(`SingleEngineProfileDriver`'s own `trials` argument was left at 1 throughout). Two consecutive packed
readings (63,721.4ms then 52,368.8ms) differ by ~18% with NO corresponding change in profile shape or
mechanism — read as noise, not a real effect, but not confirmed via repeated trials. A future session wanting
a precise final number should use `trials=3` or more.

## `fractal-r`: a categorically different bottleneck, investigation still open

`ExplicitMetricSpace` (distance matrix, not Euclidean — none of fixes 1/4 above apply, since `distance` is
already an O(1) array lookup and this stream never goes through `EuclideanMetricSpace`). `n=512`, `maxDim=2`.
Packed engine launched under `run-single-engine-jfr.sh`, no time budget; a mid-stream `jcmd JFR.dump` was
taken via `jcmd` after ~28,618s (~7.95 hours) with the run STILL IN PROGRESS — not a completed result, no
`totalSimplexCount`/`bars` confirmed yet for this run.

**The profile is dominated by `Chain.reduceLoop`'s mutable `TreeMap` accumulator itself** — summing every
`RedBlackTree$`/`TreeMap`/`reduceLoop` frame (insert, get, delete, `minNodeNonNull`, `fixAfterInsert`,
`fixAfterDelete`, `subtractOne`, the `reduceLoop$$anonfun$2` closure) accounts for roughly 75-80%+ of CPU
samples, plus a separate 21.28% in raw `Integer.valueOf` autoboxing (almost certainly the generic
`Field[CoefficientT]` abstraction — this driver runs `Fp` under F2). Nothing about geometry, apparent-pairs,
or encoding appears at all — this case is spending its time doing genuinely large amounts of real
matrix-reduction elimination work, not the overhead fixes 1-4 targeted. Consistent with none of those fixes
touching this code path at all.

**This is not a new finding — it's the confirmation of something already investigated and explicitly
declined**, twice, before this session started: `WORKLOG-ripser-profiling.md`'s "What's still open" section
(first pass) and its sixth follow-up session's "Target 3" (investigated directly: `TreeMap` has no
single-descent upsert-or-delete primitive; `MapOps`'s default is the same two-traversal shape already
hand-rolled; "no viable further win found" without either a hand-rolled tree or a fundamentally different
accumulator shape — concluded to be "a materially bigger, riskier redesign" of shared, reference-oracle-
adjacent machinery every homology engine in this codebase depends on, including packed, which also goes
through `Chain.reduceBy`/`reduceByUntil` unchanged).

**Deliberately not acted on this session**: (1) the run hasn't finished — no confirmed correctness/final
timing for this case yet; (2) unlike fixes 1-4, there is no already-scoped, low-risk implementation waiting —
the prior investigation concluded the opposite; (3) this is squarely the kind of decision needing its own
dedicated, carefully-validated session per that worklog's own framing, not something to fold into this
session's momentum reactively.

## Next steps for whoever picks this up

1. **Wait for `fractal-r`'s run to actually finish** (or `jcmd JFR.dump` again later for an updated
   mid-stream snapshot) — get `totalSimplexCount`/`substitutionCount`/`bars` and a final timing, then decide
   whether the `Chain.reduceLoop` redesign is worth scoping as its own effort. If `substitutionCount` turns
   out very low relative to `totalSimplexCount` for this case, that would explain why so much falls through to
   real reduction (few apparent-pairs shortcuts firing) — worth checking once the run completes.
2. **Re-run `o3_1024` (both engines) at `7b17442`** for an updated table entry — fix 4 hasn't been measured on
   this case yet.
3. **If pursuing the `Chain.reduceLoop` redesign**: re-read `WORKLOG-ripser-profiling.md`'s sixth follow-up
   "Target 3" section first — it already ruled out the obvious approach. A real fix needs either a hand-rolled
   red-black tree with a genuine single-descent upsert-or-delete primitive, or a different accumulator shape
   (e.g. mutable `HashMap` for O(1) get/update/remove plus a separate lazy-deletion heap for `z.head`'s sorted
   access) — both bigger, riskier jobs than anything in this worklog, touching `CellularHomologyContext`
   (the reference oracle every other engine cross-validates against) and `PersistenceInChunksContext` too, not
   just the Ripser engines. Get explicit sign-off before starting, matching this session's own pattern of
   asking before any change with reach beyond the two Ripser engines.
4. **The generic `Field`/boxing cost (~21% of `fractal-r`'s CPU)** is a separate lever from the tree
   structure — flagged since the very first packed-engine session, never investigated. A real fix likely means
   specializing away from `Field[CoefficientT]` genericity for at least one concrete coefficient type, which
   cuts against the project's own "everything generic over Field" design principle (CLAUDE.md's own
   foundational architecture note) — a tradeoff to raise with the project lead, not decide unilaterally.
