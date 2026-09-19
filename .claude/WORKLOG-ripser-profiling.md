# Profiling the Ripser engines: where the compute actually went

Started from a request to explain a compute-server timing table (below) and answer directly: is the current
performance "as good as we can hope for," or is there somewhere compute is being accidentally sunk? Session ran
autonomously overnight per explicit instruction ("prioritize keeping going... document all choices you make rather
than waiting for my call").

## The timing table that started this

```
case         ripser(ms) SortedSet(ms) x       packed(ms) x       S/pack  bars ok  status
sphere3_48   10.0       6561.2        656.12x 767.2      76.72x  8.55x   yes      ok
sphere3_96   50.0       119008.3      2380.17x9390.9     187.82x 12.67x  yes      ok
sphere3_192  660.0      1652693.8     2504.08x139923.9   212.01x 11.81x  yes      ok
dragon       1150.0     10445678.5    9083.20x1142956.4  993.88x 9.14x   yes      ok
o3_1024      1570.0     8508510.2     5419.43x1395726.7  889.00x 6.10x   yes      ok
```

## Finding #0 (the one that matters most): the `ripser(ms)` column is not a compute-server measurement

`RipserPaperBenchmarkSpec.scala`'s `DataCase` class has a `ripserMs: Double` field, documented directly on the case
class: "Reference ripser.cpp numbers, measured on THIS machine on 2026-09-17 (Apple M1 Pro, 32GB RAM...)" — see
`.claude/WORKLOG-ripser-comparison.md`, the session that produced them. Every value in the user's table
(10.0/50.0/660.0/1150.0/1570.0) matches these hardcoded `DataCase` literals exactly (`sphere3_48`→10, `sphere3_96`→50,
`sphere3_192`→660, `dragon`→1150, `o3_1024`→1570). The `SortedSet(ms)`/`packed(ms)` columns are genuine fresh
measurements from the compute-server run; the `ripser(ms)` column is not — it's a constant baked into the spec,
carried over from a completely different machine.

**This means every `x` ratio and the whole "gap to ripser.cpp" framing in that table compares tda4j-on-the-compute-
server against ripser.cpp-on-a-laptop, not a same-hardware comparison.** It also resolves what would otherwise be a
troubling internal inconsistency: `.claude/WORKLOG-packed-ripser-engine.md`'s own 30-minute-budget local run (on the
M1 Pro, `-Xmx6G`) measured `dragon` at 347.1s and `o3_1024` at 470.3s for the packed engine; the new compute-server
numbers show 1143.0s and 1395.7s for the same two cases — roughly 3x slower. A compute server that is ~3x slower
per core than an Apple M1 Pro for single-threaded JVM/Scala work is entirely ordinary (many "compute servers" are
built for core count/throughput, not single-core clock speed, and the M1 Pro's single-core performance is unusually
strong) — this is not a regression, and it is not evidence about whether tda4j's own engines got slower or faster.
**The `SortedSet(ms)`/`packed(ms)` numbers within that one table ARE comparable to each other** (both measured in
the same run, same machine) — the `S/pack` column (8.55x–12.67x) is real and in the same range as the M1 Pro's own
4.72x–9.94x from the earlier session, consistent with the packed representation's win being a real, portable effect,
not machine-specific. **Recommendation, not yet acted on**: re-run `RipserPaperBenchmarkSpec` with real `ripser.cpp`
actually built and timed on the compute server itself (the class already supports a real dual-engine run; a
`ripserMs` field populated from an actual `ripser.cpp` build on that machine, not the M1 Pro's hardcoded value)
before drawing any conclusion about how far tda4j is from ripser.cpp on that hardware.

## Methodology: local profiling, not the compute server

No access to the compute server itself in this session — all profiling and measurement here is local, on the same
machine `WORKLOG-packed-ripser-engine.md`/`WORKLOG-ripser-comparison.md` used (Apple M1 Pro, 32GB), which per
`uptime`/`vm.swapusage` was already under real memory/CPU pressure for the whole session (load average 16–34 on 8
cores throughout; swap 12.7–12.8GB/14.3GB used, roughly stable — a memory-safety check before starting, per this
codebase's own established lesson from the prior packed-engine session's OS-kill incident). Given that pressure,
all profiling used small-to-moderate synthetic random Euclidean point clouds (n=48–150, ambient dimension 3,
maxDim=2, `Double` coefficients) generated with a fixed seed via a scratch driver (`ProfileDriver.scala`, briefly
added under `src/main/scala/.../profiling/` to run `RipserCohomologyContext`/`PackedRipserCohomologyContext`
directly outside sbt/specs2 for JFR profiling, removed again at the end of the session — not part of the shipped
library). JDK: Temurin 21.0.1 (matching this project's documented target), invoked directly with
`-XX:StartFlightRecording=settings=profile` (JFR ships in the JDK, no extra tooling install needed/attempted, given
the machine's existing load). Both CPU (`jdk.ExecutionSample`) and allocation (`jdk.ObjectAllocationSample`, weighted
by allocated bytes, not just event count) profiles were captured, per this codebase's own standing lesson
(`[[tda4j-measure-dont-infer]]`; `WORKLOG-ripser-comparison.md`'s own explicit recommendation to profile allocation
next, not just re-sample CPU).

## Finding #1: `binomial(n, k)` was silently uncached on the entire cofacet/facet enumeration hot path

`SimplexIndexing`'s own class doc explains, at length, why `binomialEntry(d, s)` memoizes `binomial(d + s, s)` — "the
hottest path in the engine." But `SimplexIndexing.cofacetIteratorWithVertex`, `facetIterator`, and the encode
direction of `apply(simplex: Simplex[Int]): Long` all called the free-standing `binomial(n, k)` function **directly,
never through `binomialEntry`/`binomialCache`** — a real oversight, not a deliberate scope boundary (nothing in
`RipserStream.scala`'s history flags this as intentional). `cofacetIteratorWithVertex`'s `Iterator.unfold` loop
alone calls it twice per candidate vertex, for every `j` from `vertexCount - 1` down to `0` — `O(vertexCount)`
uncached calls per simplex whose coboundary is enumerated, not `O(1)`.

Each uncached call ran `binomialBigint`: allocate a `BigInt`, tail-recurse `k` multiply/divide steps, allocate the
result. JFR leaf-frame analysis (a 48-point random cloud, 3 warmup + 15/30 timed iterations per engine):

| engine | leaf samples in `binomialtail`/`binomialBigint` | as % of all CPU samples |
|---|---|---|
| `RipserCohomologyContext` (SortedSet) | 69 / 593 | 11.6% |
| `PackedRipserCohomologyContext` (packed) | 56 / 220 | 25.5% |

(A cruder "binomial anywhere in the call stack" count — 35.9%/44.1% — was computed first and is **not** the right
number to quote: it double-counts every caller on the stack waiting for the leaf to return. The leaf-frame numbers
above are what "time spent actually computing this" means. An earlier draft of this file's own inline doc comment
in `RipserStream.scala` used the inclusive number; corrected to the leaf number before this session ended.)

### A negative result worth keeping: naive memoization did not help

The first fix attempted: wrap `binomial` in a second `HashMap[(Int, Int), Long]` cache (mirroring
`binomialEntry`/`binomialCache`'s own pattern), leaving the `BigInt`-based `binomial` untouched underneath. This
compiled, passed every test, and **changed wall-clock time by nothing distinguishable from noise** — measured via a
controlled A/B (same code swapped in place via `Edit`, same fixed random seed per size, so both sides ran on
*identical* point-cloud sequences, not just similarly-sized ones):

| case | pre-fix avg | HashMap-cache avg | Δ |
|---|---|---|---|
| packed n=48 | 100.1ms | 101.6ms | ~0% |
| packed n=96 | 1209.3ms | 1239.4ms | ~0% (slightly worse) |
| packed n=150 | 7166.5ms | 7392.3ms | ~0% (slightly worse) |

Re-profiling confirmed the *mechanism* worked exactly as intended — `binomialtail`/`binomialBigint` disappeared
from the leaf-frame profile entirely — but the cost had simply **moved**, not disappeared: `HashMap$Node.findNode`
and `scala.runtime.BoxesRunTime.equals2` (tuple-key hashing/equality) took its place as new leaf hotspots. The
explanation, confirmed rather than assumed: the `k` argument at these call sites is always small (bounded by
simplex dimension, typically ≤ 4–5 in these workloads), so a `BigInt` tail-recursion of `k` steps is *already*
cheap enough that a boxed-`Tuple2` `HashMap` lookup costs about the same as just recomputing it. **Caching a
computation doesn't help when the computation is expensive because of its own implementation choice, not because
it's being redundantly repeated.** This is the general lesson worth keeping past this specific bug: measure the
fix, don't assume "add a cache" is automatically a win just because a profiler pointed at the un-cached call.

### The actual fix: stop using `BigInt`

`RipserStream.scala` already imports `org.apache.commons.numbers.combinatorics.BinomialCoefficient` for an existing
helper (`binomialApache`, itself unused elsewhere and truncating to `Int` — the exact truncation bug
`WORKLOG-simplexindexing-overflow.md` already fixed once for `binomial` itself, so not reusable as-is).
`BinomialCoefficient.value(n, k): Long` is a well-tested, non-`BigInt`, GCD-guarded-for-large-`n` algorithm — no
allocation for the common small-`n`-or-small-`k` case, `ArithmeticException` on genuine overflow. `binomial` now
delegates to it directly, with `n < 0 || k < 0 || n < k` special-cased to `0` before delegating (matching
`binomialBigint`'s old contract; Apache's own function throws `IllegalArgumentException` for that range instead,
which `cofacetIteratorWithVertex`'s own `iA`/`iB` bookkeeping genuinely does reach at the edges of its sweep —
confirmed by running the full suite, not merely reasoned through) and overflow re-wrapped into the same
`IllegalArgumentException`-with-message shape the old `require` produced.

`binomialEntry`/`binomialCache` was **also** rewritten in the same pass: allocation profiling (below) found its
`(Int, Int)`-tuple-keyed `HashMap` lookup was itself the single largest allocation source in *both* engines — a
`Tuple2$mcII$sp` gets boxed on every lookup, including cache hits, just to probe the map. Replaced with a
lazily-grown `Array[Array[Long]]` (rows indexed by `d`, which is always small and bounded by simplex size at every
real call site; columns sized `vertexCount + 1`, filled lazily with a `-1L` sentinel exactly as before) — zero
allocation per lookup once a row exists. This does **not** reintroduce the eagerly-computed-full-table bug
`WORKLOG-simplexindexing-overflow.md` fixed: rows are still only ever allocated for a `d` some real call actually
reaches, and each entry is still computed lazily on first access, not eagerly for the whole row.

## Finding #2: `Iterator.unfold` allocated a `Tuple5`/`Tuple4`/`Option`/closure on every step, not every result

Allocation profiling (`jdk.ObjectAllocationSample`, weighted by allocated bytes, filtered to the `main` thread —
excludes JFR's/JIT's own internal allocation noise) on the same 48-point cloud, **before** any fix:

| class | SortedSet | packed |
|---|---|---|
| `Tuple2$mcII$sp` (boxed `(Int,Int)` — `binomialEntry`'s cache key) | 16.3% | 19.1% |
| `SimplexIndexing$$Lambda...` (the `unfold`/`filter`/`map` step closures, allocated fresh per call) | 13.5% | 14.9% |
| `RedBlackTree$Tree`/`Tree[]`/`KeysIterator`/`TreeSet` combined | 28.3% | 20.9% |
| `BigInt` | 5.2% | 2.9% |
| `Tuple5` (the `unfold` state tuple in `cofacetIteratorWithVertex`) | — (not in top 20) | 5.7% |

`cofacetIteratorWithVertex` and `facetIterator` were rewritten from `Iterator.unfold(...).filter(...).map(...)` to
hand-rolled `Iterator` subclasses with plain mutable fields — same exact per-step arithmetic (verified line-for-line
against the original, including the easy-to-invert-by-mistake detail that `facetIterator` yields `iiB + iA` using
the *old* `iA`, not the freshly-updated `iiA`; kept byte-for-byte, not "corrected"), but allocating only the
`(Int, Long)`/`Long` actually returned by `next()` — no per-step state tuple, no `Option` wrapper, no closure
allocated per call (the closures were being allocated once per `cofacetIteratorWithVertex`/`facetIterator` *call*,
not once per step, but every simplex's coboundary triggers one such call, and there were roughly `vertexCount` steps
inside each).

## Finding #3: `(tau.underlying diff sigma.underlying).head` used a full tree-merge to find one element

The single most surprising find, uncovered by re-profiling allocation *after* fixes #1–#2: `Tuple4` jumped to 12.5%
of `RipserCohomologyContext`'s total allocation weight — traced via stack trace to
`scala.collection.immutable.RedBlackTree$.split`/`._difference`, called from `RipserCohomologyContext.coboundaryOf`
and `.zeroPivotCofacet` via `TreeSet.diff`. Both compute `(tau.underlying diff sigma.underlying).head` purely to
find the one vertex `tau` (a cofacet) has that `sigma` (its facet) doesn't — a question with exactly one right
answer by construction (`tau.underlying.size == sigma.underlying.size + 1`, always), answered here by invoking
`TreeSet`'s general persistent-tree set-difference algorithm (`split`/`_difference`, itself built out of `Tuple4`
and fresh tree nodes) — real overkill for the actual question being asked. This is the exact same expensive shape
`SimplexIndexing.cofacetIteratorWithVertex`'s own doc comment already flagged the *packed* engine as deliberately
avoiding (`PackedRipserCohomologyContext` never materializes this diff at all — it gets the inserted vertex
directly from `cofacetIteratorWithVertex`'s exposed `(Int, Long)` pair) — it just hadn't been fixed in the original,
`Simplex[Int]`-based engine `coboundaryOf` itself, which is why `PackedRipserCohomologyContext` (untouched by this
specific fix, since it never had the cost) shows no equivalent line item.

Fixed by replacing both call sites with `tau.underlying.find(v => !sigma.underlying.contains(v)).get` — a linear
scan over `tau`'s own (small, already-sorted) vertex set, `O(sigma.size)`, zero tree-merge machinery. `coboundaryOf`
is the main per-simplex reduction driver in `RipserCohomologyContext`, called once per non-cleared,
non-apparent-paired simplex, iterating essentially the full remaining vertex range internally — this was, by
allocation weight, the single largest fixable cost found in this engine.

## Measured result: real, reproducible, above the noise floor

Noise on this machine (under sustained external load for the whole session) is real and was measured directly, not
assumed: the same fixed code, same seeds, at `n=64`, gave `1232ms`/`1395ms`/`1341ms`/`1308ms` across four
back-to-back runs — a ~13% spread with **zero code change**. Any claimed improvement smaller than that is not
trustworthy on this machine; the numbers below are well clear of it.

Controlled before/after, git `HEAD`'s pristine `RipserStream.scala` (before any fix in this session) vs. the final
state (all four fixes), same fixed random seeds, 3 warmup + 10 timed iterations each:

| case | before | after | improvement |
|---|---|---|---|
| `PackedRipserCohomologyContext`, n=150, dim=3, maxDim=2 | 7487.7ms | 4774.5ms | **36.2%** |
| `RipserCohomologyContext`, n=64, dim=3, maxDim=2 | 1308.7ms | 829.9ms | **36.6%** |

(The packed engine's own improvement comes entirely from fixes #1–#2 — it never had finding #3's cost to begin
with, confirmed directly: its own timing barely moved between the fixes-#1–#2 checkpoint, 4802.9ms, and the final
state, 4774.5ms, both well inside the measured noise floor. `RipserCohomologyContext`'s improvement is spread across
all four fixes: fixes #1–#2 alone measured 29.4% at this size, #3 added the remaining ~7 points.)

Allocation weight (48-point cloud, main thread only, measured after fixes #1–#2 only — see Finding #3 above for
where the #3 number, 12.5% of the *remaining* total at that checkpoint, came from):

| engine | before | after fixes #1–#2 | reduction |
|---|---|---|---|
| SortedSet | 30,205 MB | 18,801 MB | 37.7% |
| packed | 9,247 MB | 5,068 MB | 45.2% |

The correctness suite (`sbt test`) was run after every single fix in this session, not just at the end: 235
examples, 0 failures, 0 errors, 230 passed, 5 skipped, 1 pending — identical to the pre-session baseline every
time. `SimplexIndexingSpec`'s exact-cofacet/exact-facet-set assertions, `RipserCohomologySpec`'s 17
examples/1609 expectations, and `PackedRipserCohomologySpec`'s cross-validation against the (now-fixed) reference
engine all cover the rewritten code paths directly, not just incidentally.

## What's still open, characterized but not fixed

**`Chain.reduceLoop`'s `SortedMap`-based accumulator** (`Chain.scala`) is, by the allocation profile taken *after*
all four fixes above, now the largest remaining identified cost in both engines: `RedBlackTree$Tree`/`Tree[]`/
`KeysIterator` together are ~19% (packed) to ~24% (SortedSet, alongside a large but likely-related `$colon$colon`/
`List` share) of remaining allocation weight, and this machinery is shared by *every* engine in `Homology.scala`
(`CellularHomologyContext`, `PersistenceInChunksContext`, both cohomology engines) via `Chain.reduceByUntil`'s
`toSortedMap`/`updateMap` — each elimination step during reduction currently allocates through an immutable,
persistent `SortedMap` (`scala.collection.immutable.TreeMap`) rather than mutating in place.

**Deliberately not attempted this session**, on the advisor's explicit recommendation and for reasons specific to
this codebase, not general caution: (1) `CellularHomologyContext` is the reference oracle every other engine in
this codebase is cross-validated against — a change here that's wrong in a subtle way could silently corrupt every
other engine's own correctness story; (2) the packed engine's own design (`WORKLOG-packed-ripser-engine.md`) was
built on an explicit standing instruction to go through `Chain.reduceBy` *unchanged*; (3) this session's own
detour into "naive memoization" (Finding #1's negative result) is a direct, recent example of a profiler-suggested
fix that didn't survive contact with a real measurement — the right next step for `Chain.reduceLoop` is the same
discipline, not a rewrite validated only by wall-clock time on an already-noisy, already-loaded machine.

**A concrete, bounded next step, if a future session takes this on**: keep `Chain.reduceByUntil`'s public
signature and return type identical; replace only `reduceLoop`'s internal `SortedMap`/`updateMap` accumulator with
`scala.collection.mutable.TreeMap` (still a red-black tree, still gives `headOption`/ordered iteration, but mutates
in place rather than allocating a new persistent tree per update); validate against `RipserCohomologySpec`,
`PackedRipserCohomologySpec`, and `HomologySpec` (which already cross-validate every engine against every other),
plus a fresh allocation profile to confirm the `RedBlackTree` share actually drops rather than just moving
(Finding #1's own lesson) before trusting any wall-clock number from it.

**Also not investigated, out of scope for this pass**: the `Double`/`Long` boxing inherent to `DiameterIndex`
(36.0% of the packed engine's own remaining allocation weight, post-fix) and to the generic `Field[CoefficientT]`
abstraction generally — this is the same cost `DiameterSimplex`'s own doc comment and
`.claude/WORKLOG-lazy-enumeration.md`'s "Session 2" section already flagged as a deferred, known option (Ripser's
own compact `diameter_index_t` representation vs. this codebase's boxed carrier), not a new finding. `Chain.scala`'s
`mutable.PriorityQueue`-backed representation (the OTHER half of `Chain`'s machinery, used for `collapseAll`/
`leadingTerm`, separate from `reduceLoop`'s `SortedMap`) was not profiled as its own line item and may have its own
story; not chased in this pass since it didn't surface as a top allocation source on the workloads tested here.

## Follow-up session (2026-09-19): a real vs-ripser number, a real server script, and one more fixed sink

Three follow-up asks: (1) check the "easier" paper examples locally to see how well the fixes above actually
took, (2) build a script to get real `ripser.cpp` timings on the compute server, ideally orchestrated from the
benchmark spec itself, (3) determine whether clear time sinks in the packed path are exhausted.

### Real, same-day, same-machine numbers on the actual paper data (not synthetic clouds)

Everything in this file above used synthetic random Euclidean clouds for speed and control. This session instead
built real `ripser.cpp` fresh (`git clone --depth 1 https://github.com/Ripser/ripser.git`, plain `make` — the
*vanilla* upstream repo, not `~/CLionProjects/ripser`, which turned out on inspection to be the project lead's own
personal fork, `git@github.com:michiexile/ripser.git`, with real independent modifications for a SoCG 2025
submission — using that instead would have silently changed what "ripser.cpp" means in this comparison, so a
fresh, separate, unmodified clone was built instead) and downloaded the paper's own `sphere3_48`/`sphere3_96`/
`sphere3_192` data (exact URLs already in `RipserPaperBenchmarkSpec.scala`'s class doc).

All five fixes in this file (the four above, plus `insertionDiameter`'s below) vs. the true pre-session git `HEAD`
baseline, on the SAME real data:

| case | pre-session packed | post-fix packed | improvement |
|---|---:|---:|---:|
| sphere3_48  | 512.6ms   | 202.3ms   | **60.5%** |
| sphere3_96  | 3568.8ms  | 1825.0ms  | **48.9%** |
| sphere3_192 | 50192.2ms | 27650.7ms | **44.9%** |

Larger than the ~36% measured on synthetic clouds earlier in this file — real point clouds apparently exercise the
fixed `O(vertexCount)`-per-simplex code paths harder than the synthetic ones did, which makes sense (all five fixes
scale with vertex count, and these real data sets are exactly the regime the original bug reports flagged: "the gap
widens on harder, larger cases").

Gap to real `ripser.cpp`, measured fresh THIS session on this machine (median of 5 trials per case, via the new
`-DripserBin`/`-DripserTrials` support below — NOT the hardcoded 2026-09-17 snapshot, and NOT directly comparable
to any number computed against that snapshot):

| case | ripser.cpp (median of 5) | SortedSet | packed | S/ripser | P/ripser |
|---|---:|---:|---:|---:|---:|
| sphere3_48  | 10.8ms  | 1151.9ms  | 202.3ms   | 107.1x | 18.8x |
| sphere3_96  | 47.3ms  | 25529.8ms | 1825.0ms  | 539.5x | 38.6x |
| sphere3_192 | 431.9ms | (not run — see below) | 27650.7ms | — | 64.0x |

`sphere3_192`'s `SortedSet` number isn't in this table on purpose: it did not finish inside a 120s timeout, and
because `withTimeout`'s `Future` has no cooperative cancellation, letting it run longer in the SAME process would
contaminate the packed engine's own measurement on that case (the exact zombie-thread issue
`WORKLOG-packed-ripser-engine.md` already documents) — confirmed directly: a first dual-engine attempt at this
case gave `packed = 44690.4ms`, and a clean `-DpackedOnly=true` re-run of the SAME case gave `27650.7ms` instead,
a 38% difference purely from that contamination. **Anyone reading this table should trust `-DpackedOnly=true`
numbers for any case where the other engine might time out, not the dual-engine table's number for that same
case** — this is not a new finding, just a fresh confirmation of an already-documented trap.

**Honest reading**: the packed engine is now 19x-64x slower than real `ripser.cpp` on these three cases, down
from an unmeasured-but-implied larger gap before this session's fixes (the pre-session table's own hardcoded
`ripserMs` values, 10/50/660ms, were themselves noisy single-sample process-launch timings — see the next
paragraph — so a precise "was Nx, now is Mx" ratio isn't defensible; the packed engine's own wall-clock time
dropping by 45-60% on identical data, independent of any ripser-side measurement at all, is the defensible claim).
The gap does not close with problem size here (18.8x -> 38.6x -> 64.0x, growing not shrinking) — consistent with
this file's own earlier characterization of `Chain.reduceLoop`'s `SortedMap` machinery and `DiameterIndex`/`Field`
boxing as the largest remaining, NOT-yet-fixed costs (see "What's still open" above and the packed-engine-specific
profiling below): those costs scale with total simplex count, same as `ripser.cpp`'s own work, so a fixed
per-operation tax on top of comparable big-O growth should indeed show up as a roughly stable-to-growing ratio,
not one that closes on its own as `n` grows.

**A genuine measurement-quality finding, not a performance one**: a single subprocess invocation of `ripser` on
these small cases is dominated by process-launch noise, not the VR computation itself -- confirmed directly, not
assumed: one single-trial run of `sphere3_96` gave `106.6ms`; a 5-trial-median run of the exact same binary/data/
machine gave `45.7ms`, a >2x spread. `RipserPaperBenchmarkSpec`'s new `-DripserBin` support (below) now runs the
real binary `ripserTrials` times (default 5) and takes the median for exactly this reason, rather than trusting
any single subprocess timing the way an ad hoc shell `time` invocation would.

### `RipserPaperBenchmarkSpec` now orchestrates the real-ripser comparison itself

Per the explicit ask ("if we can orchestrate it from within the benchmark test case code, even better"):
`RipserPaperBenchmarkSpec.scala` gained a `-DripserBin=<path>` system property. When set, the spec shells out to
that binary directly for every case (`--dim`/`--threshold`/`--format` built from each `DataCase`'s own new
`dataFile`/`ripserFormat` fields, matching the exact invocation this class's doc already documented by hand),
times it with `System.nanoTime()` around the subprocess call (median of `-DripserTrials`, default 5), and parses
bar counts fresh from its own stdout (`persistence intervals in dim K:` sections — every printed line under one is
already a non-zero-persistence bar, confirmed against the class's own existing doc on this point) instead of
trusting the hardcoded `ripserMs`/`refBars` snapshot. This is additive, not a replacement: omitting `-DripserBin`
reproduces the exact original hardcoded-snapshot behavior. `barsOk` now cross-checks both Scala engines against
whichever reference (live or hardcoded) is in effect for that run.

A companion script, `.claude/scripts/run-ripser-paper-benchmark.sh`, handles the setup a bare `-DripserBin` still
needs: clones+builds vanilla `ripser.cpp` (skipped if already built), downloads the paper's data sets (skipped
per-file if already present), derives `sphere3_48.dat`/`sphere3_96.dat`, temporarily un-skips
`RipserPaperBenchmarkSpec` (restored via an `EXIT` trap, so it's restored even on a failed/interrupted run), and
invokes `sbt` with `-DdataDir`/`-DripserBin`/`-DtimeoutSeconds`/`-DripserTrials`/`-DpackedOnly` all wired to
overridable env vars. Tested end-to-end this session (reusing an already-built binary/already-downloaded files to
exercise its idempotent-skip paths, plus a real `sphere3_48` run) — the `skipAll` toggle-and-restore round-tripped
cleanly (`git diff` on the spec file after a run shows zero residual change from the script itself). Meant to be
copied onto a machine that doesn't already have either half (e.g. the compute server) and run directly there;
see the script's own header comment for full usage, including recommended flags for the paper's larger/slower
cases (`PACKED_ONLY=true`, a reduced `RIPSER_TRIALS`, a larger `TIMEOUT_SECONDS`).

### Finding #5: `insertionDiameter`'s `.iterator.map(...).max` allocated a closure and boxed every intermediate `Double`

A dedicated follow-up profiling pass on `PackedRipserCohomologyContext` alone (JFR, real `sphere3_96` data, per the
third ask: "have we exhausted clear time sinks in the packed path?") found `insertionDiameter`'s own allocation
signature dominating what was left: a `PackedRipserCohomologyContext$$Lambda` closure at 7.5% of total allocation
weight, traced directly to

```scala
private def insertionDiameter(sigma: Simplex[Int], sigmaFv: Double, v: Int): Double =
  math.max(sigmaFv, sigma.underlying.iterator.map(u => metricSpace.distance(u, v)).max)
```

— the `.map(u => metricSpace.distance(u, v))` closure captures `v`/`this` and is allocated FRESH on every single
call, and `.max` over the resulting `Iterator[Double]` boxes every intermediate distance before comparing. This
method is called once per candidate cofacet vertex considered by `sparseCofacets`/`coboundaryOf`/
`zeroPivotCofacet`/`zeroApparentCofacet` — the same `O(vertexCount)`-per-simplex hot-path shape as every other fix
in this file. `RipserCohomologyContext.insertionDiameter` (`Homology.scala`) had a byte-for-byte identical
implementation (confirmed by direct comparison, not assumed from the shared name) — both fixed identically, with a
plain `while` loop over primitive `double`, no closure, no boxing:

```scala
private def insertionDiameter(sigma: Simplex[Int], sigmaFv: Double, v: Int): Double =
  var maxD = sigmaFv
  val it = sigma.underlying.iterator
  while it.hasNext do
    val d = metricSpace.distance(it.next(), v)
    if d > maxD then maxD = d
  maxD
```

**Measured effect, on real `sphere3_96` data specifically** (the packed engine, isolated from the SortedSet
engine, via a dedicated scratch driver — `PackedProfileDriver.scala`, same disposition as `ProfileDriver.scala`
above, deleted at the end of this session): `2430.9ms -> 1782.7ms` average, a further **26.7%** reduction on top
of the four fixes already in this file — the single largest individual improvement measured in either follow-up
session. Re-profiling confirmed the mechanism directly, not just the timing: the `$$Lambda` allocation category
disappeared from the top 15 entirely, and `java.lang.Double`'s own share of total allocation weight dropped from
21.7% to 1.2% in the same comparison. Full `sbt test` (235 examples) clean after this fix too.

### Have we exhausted clear time sinks in the packed path? Yes, for now.

The post-fix profile (real `sphere3_96` data, `PackedRipserCohomologyContext` alone) no longer shows any single
method consuming an outsized, obviously-wasteful share of CPU or allocation the way `binomialtail`/`TreeSet.diff`/
`insertionDiameter`'s closure did before their respective fixes. What's left splits cleanly into two categories,
neither of which is a "clear" (quick, safe, contained) fix:

1. **Legitimate, necessary computation** — `insertionDiameter`'s own distance-comparison loop (now the single
   largest leaf frame, 18% of CPU samples, but doing real, unavoidable work), `SimplexIndexing.searchRow`'s binary
   search (decode), `BinomialCoefficient.value`'s own arithmetic (including a real, if secondary, cost: for
   `vertexCount > 66`, Apache's implementation takes a GCD-guarded overflow-safe branch — `ArithmeticUtils.gcd`
   was 2.7%-2.9% of leaf samples in this profile — a genuine, likely-irreducible-without-hand-rolling-and-
   re-verifying-overflow-safety cost of correctness at this scale, not flagged as worth chasing further), and
   `compareDiamThenIndex`'s comparator (already minimal: two primitive comparisons, no allocation).
2. **Already-characterized structural costs, unchanged by this session's fixes** — `Chain.reduceLoop`'s
   `SortedMap`/`RedBlackTree` machinery is now, by a wide margin, the single largest allocation category on its
   own (48.4% of total allocation weight combining `RedBlackTree$Tree`/`$Tree[]`/`$KeysIterator`/`TreeSet` — UP
   from ~20% before this session's fixes removed the noise sitting on top of it, not because this cost itself
   grew), and generic boxing (`Long`/`DiameterIndex`/`Tuple2` combined, ~34%) is the other large share — both
   exactly the two items this file's own "What's still open" section already named and explicitly declined to
   fix this session, for the same reasons (shared reference-oracle machinery; a standing instruction to leave
   `Chain.reduceBy` unchanged; this session's own HashMap-caching dead end as a fresh reminder not to redesign on
   intuition). Nothing NEW was found in this category this session — it's the same, already-scoped, bigger job.

So: yes, the "quick win" tier is exhausted for now. Every further improvement identified requires the bounded
`Chain.reduceLoop` prototype (or the `DiameterSimplex`/packed-representation redesign) already scoped out above,
not another profiling pass at this same level.

## Answering the original question

Yes — real compute was being sunk, for reasons unrelated to the packed representation's own design (both engines
shared all three root causes, since both go through the same `SimplexIndexing`/`Chain.reduceBy` machinery): an
uncached `BigInt` computation on the enumeration hot path, an allocation-heavy `Iterator.unfold` idiom, and one
full tree-merge algorithm invoked to answer a single-element question. Fixed, measured (not inferred) at 36%
faster on both engines on the workloads this session could safely run on this machine, with the correctness suite
clean after every step. The remaining gap to real `ripser.cpp` cannot currently be characterized honestly for the
compute-server table specifically (Finding #0) — that needs a same-hardware `ripser.cpp` build and run, not
inference from a different machine's numbers. On this session's own machine, the already-established ~20µs/simplex
constant-factor tax from `WORKLOG-ripser-comparison.md` was not re-measured directly, but `Chain.reduceLoop`'s
`SortedMap` machinery (now the largest remaining identified cost) and `DiameterIndex`/`Field` boxing are the two
concrete, evidence-backed candidates for most of what remains of it — not vague "the JVM is just slower than C++"
hand-waving.

## Follow-up session (2026-09-19, later the same day): the reduceLoop redesign, and a misattribution caught first

Asked to do the `Chain.reduceLoop` redesign scoped above (`SortedMap` → a mutable accumulator). Before writing any
code, re-checked the earlier profile's exact allocation call sites (`jfr print --stack-depth 30`, not the shallow
depth used the first time) — advisor's suggestion, on the reasoning that "48% RedBlackTree" was suspiciously
large for one accumulator. It was: **the "48%" figure earlier in this worklog conflated three unrelated
allocation sources that only share a `RedBlackTree` class-name prefix.**

| source | share of total allocation | what it actually is |
|---|---|---|
| `insertionDiameter`'s `sigma.underlying.iterator` | **23.8%** | reading a `Simplex`'s own vertex set, once per candidate cofacet |
| `SimplexIndexing`/`SimplexOps` decode-time `TreeSet` building | ~12% | constructing a `Simplex[Int]` from a packed combinatorial index, one vertex at a time |
| `Chain.reduceLoop`'s actual `SortedMap.updated`/`.removed` churn | **~6.9%** (confirmed at `--stack-depth 30`, zero unattributed) | the accumulator this session was asked to redesign |

The first two were never `Chain.reduceLoop` at all. `insertionDiameter` — the `while`-loop rewrite from the
earlier pass in this same worklog — eliminated the closure/boxing cost of `.map(...).max` but still called
`sigma.underlying.iterator` on every single call, and `TreeSet.iterator()` itself allocates a `KeysIterator`
wrapping a `TreeIterator` (with its own `Tree[]` DFS-stack array) — a persistent-tree cost that has nothing to do
with tail recursion or `Chain`'s own map. This was a bigger, lower-risk fish than the originally-scoped redesign:
it doesn't touch the reference-oracle `CellularHomologyContext`/`Chain.reduceBy` machinery at all, just two
already-experimental/already-fixed-once methods.

**Fix #6 (insertionDiameter iterator elimination)**: `insertionDiameter` now takes an already-materialized
`Array[Int]` instead of a `Simplex[Int]`, indexed directly in the `while` loop. `sigma`/`decoded` is fixed across
every candidate cofacet vertex considered within one `coboundaryOf`/`sparseCofacets`/`zeroPivotCofacet` call, so
each caller (both engines, `PackedRipserCohomology.scala` and `Homology.scala`) hoists
`sigma.underlying.toArray` ONCE per call instead of `insertionDiameter` re-iterating `sigma.underlying` on every
candidate. The sibling cost — `coboundaryOf`'s sign computation, `sigma.underlying.count(_ < v)`, the same
"iterate a `SortedSet` for a question the already-hoisted array can answer" shape — was fixed identically, as a
plain scan over the (already-sorted) hoisted array. **Deliberately NOT touched**: `Homology.scala`'s
`tau.underlying.find(v => !sigma.underlying.contains(v)).get` (`coboundaryOf`/`zeroPivotCofacet`'s
inserted-vertex lookup) — a separate, already-fixed call site from the FIRST pass in this worklog (Finding #4),
operating on `tau`, which varies per candidate and can't be hoisted the same way; fixing it properly would mean
switching to `SimplexIndexing.cofacetIteratorWithVertex` (which the packed engine already uses specifically to
avoid needing this lookup at all) — a bigger structural change, left as a candidate for a future session, not
attempted here.

Verified the coefficient fields these engines' own cross-validation specs actually use before trusting the sign
fix against a green suite (a sign error here is invisible over F2, and this codebase already knows that trap from
`CubicalSpec`'s dd=0-over-F3 test): both `RipserCohomologySpec` and `PackedRipserCohomologySpec` default to
`Field.DoubleApproximated`, not F2 (`RipserCohomologySpec` also exercises `FiniteField(11)`) — neither hides a
sign flip, so the existing 235-example green suite is real evidence here, not a blind spot.

**Measured (controlled A/B, `git stash` isolating just the two changed files, same `sphere3_96` real paper data,
`-DpackedOnly=true`, median of 3 trials each)**: re-profiling confirmed the `KeysIterator`/`Tree[]` allocation
categories under `insertionDiameter` vanished entirely (zero occurrences in a fresh profile, not reduced —
checked by grepping for the method name in the extracted allocation trace) rather than reappearing under
`toArray`. Wall-clock: 2316.0ms → 2110.2ms, **~8.9% faster** — real, but well below the 23.8%-of-allocation
figure would suggest on its own (allocation reduction doesn't translate 1:1 to wall-clock time when the
computation itself, real floating-point distance math, already dominates).

**Fix #7 (the originally-scoped `Chain.reduceLoop` redesign, now correctly scoped)**: `Chain.scala`'s
`updateMap`/`toSortedMap`/`reduceLoop` rewritten from an immutable, persistent `SortedMap` (each `updated`/
`removed` allocates O(log n) fresh tree nodes to preserve structural sharing nothing in this recursion actually
needs — `z`/`reductionLog` are built fresh at the top of `reduceByUntil` and never observed at an intermediate,
pre-mutation state by anything else) to a `scala.collection.mutable.TreeMap`, mutated in place. `reduceLoop`
itself changed from `@tailrec` recursion threading a fresh map through each step to an explicit `while` loop
mutating the same map object — a mechanical consequence of the mutation, not a separate design choice.
`reduceByUntil`'s public signature and return type are byte-for-byte unchanged. One subtlety checked, not
assumed: `mutable.TreeMap` does NOT override `headOption` itself, and `IterableOnceOps`'s inherited default
allocates a full iterator (`if (it.hasNext) Some(it.next())`, built on `.iterator`) — exactly the class of cost
this whole session was chasing. Decompiled `TreeMap.class` to check rather than guess: `head` (unlike
`headOption`) IS separately overridden and compiles to one direct `RedBlackTree.min` call, zero iterator — so
`reduceLoop` uses `if z.isEmpty then ... else val (sigma, sigmaCoeff) = z.head` instead of `z.headOption`.

**Validation**: full `sbt test` (235 examples, 230 passed/0 failed/5 skipped/1 pending — unchanged baseline) after
the redesign, with particular attention to `PersistenceInChunksSpec` (7 examples, clean) — flagged by advisor as
the one place a previous bug (`compress`'s stale-snapshot-vs-mutation hazard, see the "Cross-engine benchmark"
section of `CLAUDE.md`) was *exactly* this class of hazard (introducing in-place mutation where value semantics
used to hold), and which carries a pinned regression fixture
(`HomologyFixtures.tetrahedronBoundaryDegenerateCells`) that already discriminates it. `RipserCohomologySpec`
(17 examples) and `PackedRipserCohomologySpec` (9 examples) — the two engines whose `coboundaryOf`/`insertionDiameter`
also changed this session — both clean.

**Measured (same A/B methodology, isolating just `Chain.scala` via `git stash` with the iterator fix already
applied to both files)**: re-profiling confirmed `Chain$.reduceLoop`/`.updateMap`/`.toMutableTreeMap`'s combined
allocation share dropped to ~8.4% of a much-smaller total (most of which is now legitimate: a mutable tree still
allocates a node for a genuinely NEW key, and `reduceByUntil`'s final `Chain.from(...)` conversion is unavoidable
either way — the eliminated cost was specifically the *rebuild-the-path-to-the-root* churn on every UPDATE of an
existing key, not all `TreeMap` allocation). Wall-clock: 2097.8ms → 1967.1ms, **~6.2% faster** — matching
advisor's corrected expectation ("low single digits from the accumulator swap alone") once the 48% misattribution
was corrected, not the originally-guessed 10-15%.

**Combined effect of both fixes this follow-up session, against the true pre-session baseline**: 2316.0ms →
1967.1ms on real `sphere3_96` data, packed engine, **~15.1% faster** — on top of the ~45-60% already measured
earlier the same day (see the first follow-up section above) and the ~36% from the original overnight session.

**General lesson, worth restating because it was worth two advisor round-trips to surface**: a profiler grouping
by bare class name (`RedBlackTree$Tree`, `RedBlackTree$KeysIterator`) can silently merge unrelated call sites that
happen to share an implementation class. The fix was cheap once applied (`jfr print --stack-depth 30` instead of
a shallow default, then classify each sample by its actual originating method) but nearly wasn't applied at all —
the plan to redesign `Chain.reduceLoop` was fully formed, advisor-reviewed once already, and about to be coded
before the deeper stack trace was pulled. The prompt for pulling it was advisor's own explicit "one more check,
because it may change what you build" — not a self-generated doubt. Worth remembering the *shape* of that prompt
for next time: when a single number drives an implementation plan, check what's actually IN that number before
building on top of it, especially when the number is suspiciously round or suspiciously large relative to
everything else measured in the same pass.

**Files changed this follow-up**: `PackedRipserCohomology.scala`/`Homology.scala` (`insertionDiameter` signature,
`coboundaryOf`'s sign computation, all call sites), `Chain.scala` (`updateMap`/`toSortedMap`→`toMutableTreeMap`/
`reduceLoop`). A third scratch driver, `PackedProfileDriver.scala`, was used twice (once per fix) and deleted
both times, same convention as the first two sessions.

## Third follow-up session, same day (2026-09-19): the boxing and decoding, three fixes

Asked to fix the two remaining items from the second follow-up's report: `.maxByOption`/`.minByOption`'s generic
`Long` boxing in `zeroPivotCofacet`/`zeroPivotFacet` (both engines), and the decode-time `Simplex[Int]`/`SortedSet`
construction (`si(index, size)`, immediately flattened to an array and discarded at three of the packed engine's
own call sites) that had become the single largest remaining allocation category once the second follow-up's fixes
cleared everything bigger away from around it.

**Fix #8: `SimplexIndexing.decodeToArray(n, size): Array[Int]`, a new method alongside `apply`, not a replacement
for it**. `apply`'s existing decode builds a `Simplex[Int]` via `size` separate `upperAccum + (id + d)` calls, each
a full persistent-tree insertion -- fine for callers that actually need a `Simplex[Int]` object, wasteful for
`PackedRipserCohomologyContext.sparseCofacets`/`coboundaryOf`/`zeroPivotCofacet`, which only ever did
`si(sigma.index, size).underlying.toArray`, discarding the `Simplex` immediately. `decodeToArray` performs the
IDENTICAL `searchRow`/`binomialEntry` arithmetic as `apply` (every branch transcribed line-for-line, not a
different algorithm) but writes each vertex into a pre-sized `Array[Int]` and sorts once at the end
(`java.util.Arrays.sort`, in-place, zero allocation) instead of maintaining sortedness via incremental tree
rebuilds -- correct regardless of which order the recursion happens to emit vertices in, so this doesn't depend on
separately re-deriving that order by hand. `apply` itself is completely unchanged. **Verified before use, not
assumed**: a new `SimplexIndexingSpec` ScalaCheck property (`minTestsOk = 500`) confirms `decodeToArray(n,
size).toSet == apply(n, size).underlying` across random valid `(vertexCount, size, index)` triples -- this also
incidentally confirmed decode-then-encode is the identity (`si(si(idx, size), size) == idx`), an invariant the next
fix relies on directly.

**Fix #9: eliminate `.maxByOption`/`.minByOption`'s generic boxing in `zeroPivotCofacet`/`zeroPivotFacet`, both
engines**. Scala's `maxByOption`/`minByOption` aren't specialized for `Long` keys, so every comparison boxed --
measured as the packed engine's own largest remaining allocation source after fix #8 alone (~13.5% of total
weight). Rewritten as hand-rolled `while` loops with a primitive `Long` accumulator and a `found: Boolean` flag
(instead of a nullable `Simplex[Int]` sentinel, which doesn't type-check -- opaque types don't admit `null` under
this project's null-safety settings). In `Homology.scala`'s versions specifically, a SECOND, distinct cost stacked
on top of the same boxing: `.maxByOption((tau, _) => si(tau))`/`.minByOption(sigma => si(sigma))` RE-ENCODED a
simplex that had JUST been decoded from an index the iterator already handed over (`si.cofacetIterator`/
`facetIterator` yield the exact index `tau`/`sigma` was decoded from) -- a fully redundant O(d log d) round trip
through `searchRow`/`binomialEntry` for a `Long` already in hand. Fixed by reusing that index directly instead of
re-encoding, backed by fix #8's round-trip property test rather than assumed safe.

**Fix #10, found while re-profiling after #8/#9, not part of the original ask but the same shape**:
`zeroPivotFacet`'s own candidate-facet decode (`si(facetIdx, size - 1)`, needed only to feed
`FiniteMetricSpace.MaximumDistanceFiltrationValue`) was the ONE remaining `Simplex[Int]`-returning decode call in
the packed engine's hot path -- `zeroPivotCofacet`'s doc had explicitly called removing-a-vertex's cost "a scope
boundary, not an oversight" in the second follow-up, but re-profiling after #8/#9 showed it had become the single
LARGEST remaining category (~34% of a much-smaller total) purely because everything bigger around it had shrunk.
Fixed with a new private `maxPairwiseDistance(vertices: Array[Int]): Double` (plain O(d^2) nested `while` loops,
mirroring `insertionDiameter`'s style) replacing `rawFiltrationValue`/`MaximumDistanceFiltrationValue` for this ONE
call site -- `zeroPivotFacet` now decodes via `decodeToArray` like every other hot-path call, needing no
`Simplex[Int]` at all. `MaximumDistanceFiltrationValue` itself is untouched; every other caller is unaffected.

**Validation**: full `sbt test` after each of the three fixes individually (236 examples now, 231 passed/0
failed/5 skipped/1 pending -- the +1 over the prior 235/230 baseline is the new `SimplexIndexingSpec` round-trip
property test), with particular attention to `PackedRipserCohomologySpec`'s apparent-pair regression cases (which
exercise `zeroPivotCofacet`/`zeroPivotFacet` directly) and `RipserCohomologySpec`'s equivalent -- both clean after
every step, not just at the end.

**Measured (controlled A/B, `git stash` isolating the fix files -- and, since the new property test references
`decodeToArray`, the test file had to be stashed alongside them for the baseline to even compile)**: real
`sphere3_96` data, packed engine, `-DpackedOnly=true`, median of 3 trials at each stage:

| stage | median wall-clock | vs. previous stage |
|---|---|---|
| before this round (end of 2nd follow-up) | 1959.3 ms | -- |
| after fix #8 + #9 (decode-array + boxing) | 1603.2 ms | 18.2% faster |
| after fix #10 (`zeroPivotFacet` decode) | 1399.1 ms | a further 12.7% faster |
| **combined, this round** | | **28.6% faster** |

Allocation, same three stages (isolated `PackedProfileDriver` runs, not sbt-test-suite-wide totals, so NOT directly
comparable to earlier sessions' percentages, but internally consistent across this table): 2215.23 MB -> 1582.41
MB -> 1050.88 MB, a 52.6% total reduction. Re-profiling after fix #8/#9 confirmed `SimplexOps.$plus`/`.toSeq` (the
decode-time TreeSet cost) dropped from being the single largest category to a residual ~34% dominated entirely by
`zeroPivotFacet`'s still-unfixed decode; after fix #10, that same category dropped to ~1.2% (only genuinely
necessary decodes remain), and `.maxByOption`/`.minByOption` no longer appear anywhere in the profile at all.

**What's now the dominant remaining cost, identified but explicitly NOT attempted this round**:
`SimplexIndexing$$anon$1.next()` (the hand-rolled `cofacetIteratorWithVertex` from the very first session's
Finding #3) boxing its `(Int, Long)` result pair on every step -- `Tuple2` + `Long` together are now **49.8%** of
total allocation weight, by a wide margin the largest single category, bigger than everything else in this
session combined. This was already named as an "identified, deferred" item in the second follow-up's report to
the project lead (see that section above): fixing it means replacing `Iterator[(Int, Long)]`'s return-a-pair-per-
step interface with a cursor (`hasNext`/`advance`/`vertex`/`index`), which changes the calling convention at every
call site in BOTH engines -- a real, interface-changing job, not a quick win, and deliberately left for the
project lead to decide whether to take on next rather than expanded into without asking.

**Files changed this follow-up**: `RipserStream.scala` (`decodeToArray`, new), `PackedRipserCohomology.scala`
(`decodeToArray` at three call sites, `zeroPivotCofacet`/`zeroPivotFacet` hand-rolled, `maxPairwiseDistance` new),
`Homology.scala` (`zeroPivotCofacet`/`zeroPivotFacet` hand-rolled + redundant-encode removal),
`SimplexIndexingSpec.scala` (new round-trip property test). A fourth scratch driver, reused three times (once per
fix) and deleted each time, same convention as every prior session.

## Fourth follow-up session, same day (2026-09-19): the cursor redesign

Explicitly authorized ("if it only hits the ripser engines it is worth the changes. Go for it") after confirming
via `grep` that `SimplexIndexing.cofacetIteratorWithVertex`/`cofacetIterator`/`facetIterator` are consumed only by
`RipserCohomologyContext` and `PackedRipserCohomologyContext`, plus dead/deprecated legacy code
(`RipserCliqueFinder`, `RipserStreamSparse`) and `SimplexIndexingSpec`'s own tests -- the previous session's flagged
~49.8%-of-allocation cost (the hand-rolled `Iterator[(Int, Long)]` boxing a fresh tuple on every candidate vertex
considered) was safe to fix as an interface change.

**`advisor()` was consulted before writing any code**, given this touches `RipserCohomologyContext` -- the
reference oracle every other engine (`PackedRipserCohomologyContext` via `PackedRipserCohomologySpec`,
`SimplicialHomologyContext` via `RipserCohomologySpec`) is cross-validated against. Two corrections came out of
that consult, both followed:

1. **`sparseCofacets` keeps `Iterator[DiameterIndex]`, not `Seq`.** Both call sites
   (`persistentCohomology`'s `simplicesAtD.iterator.flatMap(sparseCofacets(_, size)).toSeq`) immediately
   materialize the flattened result, so returning `Seq` would have been behavior-preserving for the *barcode* --
   but it would force every source simplex's own cofacet list to be fully built before flattening, where the
   `Iterator` keeps only one source simplex's cofacets live at a time. Since peak memory during exactly this
   workload is what the (separate, still-pending) memory-comparison deliverable measures, trading that streaming
   behavior away for a marginally simpler hand-rolled `Iterator` wasn't worth it -- `sparseCofacets` still returns
   `Iterator[DiameterIndex]`, now hand-rolled directly over `CofacetCursor` instead of
   `cofacetIteratorWithVertex.map(...)`.
2. **`FacetCursor`'s removed-vertex path needed its own correctness check before being trusted**, not just
   `CofacetCursor`'s (which mirrors `cofacetIteratorWithVertex`'s already-tested inserted-vertex exactly). Added to
   `SimplexIndexingSpec`: for random valid `(vertexCount, size, index)`, walking `facetCursor`/`cofacetCursor` and
   checking `decodeToArray(cur.index, size +/- 1).toSet == decodeToArray(startIndex, size).toSet` with `cur.vertex`
   inserted/removed, independently of the cursors' own internal `iB`/`iA` arithmetic. Both properties passed
   (500 trials each) *before* either cursor's `vertex` field was relied on for the incremental-insert/incremental-
   remove optimizations below.

**The redesign**: `SimplexIndexing` gains two new classes, `CofacetCursor` and `FacetCursor`, each a
`hasNext: Boolean` / `vertex: Int` / `index: Long` / `advance(): Unit` cursor (deliberately not
`scala.collection.Iterator` -- splitting what `next()` used to do atomically into a read-then-step lets a caller
read `vertex`/`index` as many times as it wants between one `advance()` and the next, at zero allocation either
way). Both decode their starting simplex via `decodeToArray` (binary-searched `Array[Int]`), not `apply`
(`Simplex[Int]`/`SortedSet[Int]`, `O(log d)` tree lookups) -- the same array-over-tree substitution `decodeToArray`
already motivated, folded in here since these cursors replace `cofacetIteratorWithVertex`/`facetIterator`'s bodies
outright. `cofacetIteratorWithVertex`/`cofacetIterator`/`facetIterator` themselves are kept as thin `Iterator`
wrappers over the new cursors -- unchanged public signature and behavior, for the legacy/dead-code callers and
`SimplexIndexingSpec`'s existing assertions.

**Call sites rewired, packed engine (`PackedRipserCohomology.scala`)**: `sparseCofacets`/`coboundaryOf`/
`zeroPivotCofacet` now use `CofacetCursor` directly -- no `(Int, Long)` tuple per candidate. `zeroPivotFacet` uses
`FacetCursor` directly, and (a natural extension once `FacetCursor.vertex` was available and verified) builds each
candidate's vertex ARRAY by removing one element from `tau`'s already-decoded `Array[Int]` instead of a fresh
`decodeToArray(facetIdx, size - 1)` call -- a plain O(d) array-copy-excluding-one-index versus `decodeToArray`'s own
`searchRow`/`binomialEntry` search plus a final sort.

**Call sites rewired, `RipserCohomologyContext` (`Homology.scala`, the reference oracle) -- a deeper rewrite than
the packed engine's, not just a cursor swap**: the old `coboundaryOf`/`zeroPivotCofacet` used the vertex-less
`si.cofacetIterator(sigma)` and had to fully decode each candidate back into a `Simplex[Int]`
(`si(cofacetIdx, sigma.size + 1)`) and then linearly scan it (`tau.underlying.find(v =>
!sigma.underlying.contains(v))`) just to recover the ONE vertex `CofacetCursor` now hands over directly as
`cur.vertex`. Both methods now build `tau` by inserting `cur.vertex` into `sigma`'s own vertex set
(`(sigma.underlying + v).asSimplex`, O(log d)) -- the same incremental-insertion convention `sparseCofacets`
(dimension assembly, untouched this round) already used, replacing an O(d log d) combinatorial decode.
`zeroPivotCofacet` additionally defers building `tau` at all until the SINGLE winning candidate is known (tracking
`bestVertex`/`bestIdx` as primitives through the loop, mirroring the packed engine's already-established pattern),
since `insertionDiameter` only needs `cur.vertex`, not a materialized `tau`. `zeroPivotFacet` builds each
candidate's `sigma` via `(tau.underlying - cur.vertex).asSimplex` (one incremental removal) instead of a full
decode (`si(idx, tau.size - 1)`) -- verified sound by the `FacetCursor` property test above before relying on it
here. Unlike the cofacet direction, `sigma` can't be deferred to just the winner: `filtrationValue(sigma)` needs
the full candidate vertex set on every candidate (no incremental shortcut exists for removing a vertex's diameter
contribution, the same scope boundary this class's other docs already note), so `sigma` is built once per
candidate either way.

**Validation**: compiles clean; `SimplexIndexingSpec` (6 examples now, including the two new `CofacetCursor`/
`FacetCursor` properties), `PackedRipserCohomologySpec` (9 examples, 805 expectations -- including cross-validation
against the reference engine on random clouds and the apparent-pair collision regressions) and
`RipserCohomologySpec` (17 examples, 1609 expectations -- including cross-validation against
`SimplicialHomologyContext`, the independently-verified oracle, and the essential-cocycle/coboundary check) all
green individually; full `sbt test` clean too (237 total, 232 passed/0 failed/5 skipped/1 pending -- the +1 over
the third follow-up's 236/231 baseline is the new cursor-correctness property test).

**Measured (controlled A/B, `git stash` isolating the four changed files against the prior commit -- confirmed
safe first by checking `git diff --stat HEAD` matched only this round's own edits, not stale uncommitted state from
an unrelated earlier session), real `sphere3_96` data, median of 3 trials, one engine per JVM process (a bare
`SingleEngineProfileDriver` invoked directly via `java`, not the sbt-hosted `RipserPaperBenchmarkSpec` -- avoids
that harness's own documented per-cell-timeout/daemon-thread contamination risk entirely, since each engine here
gets its own fresh process)**:

| engine | before | after | improvement |
|---|---|---|---|
| packed | 1006.4 ms | 941.0 ms | 6.5% faster |
| SortedSet (`RipserCohomologyContext`) | 25690.7 ms | 19755.0 ms | 23.1% faster |

The SortedSet engine's improvement is much larger than the packed engine's, and this is expected, not an
inconsistency to chase: the packed engine's fix removed ONLY the tuple-boxing allocation (`DiameterIndex`'s
identity/diameter were already computed from indices directly, same as before), where the SortedSet engine's fix
additionally eliminated a genuine O(d log d) combinatorial decode-then-linear-scan on every candidate -- a real
reduction in computation, not just allocation, consistent with this arc's own repeated finding that allocation
reduction and wall-clock reduction don't move 1:1.

**Re-profiled to confirm, not assumed**: `SimplexIndexing$$anon$1` (the old `cofacetIteratorWithVertex` tuple
allocator) no longer appears anywhere in either engine's post-fix allocation profile. The packed engine's new
largest category is `DiameterIndex` itself (15.12%, legitimate per-result construction, not per-candidate
iteration) with `Chain.reduceLoop`'s `RedBlackTree$Node` and `Chain.from`'s own buffer/`PriorityQueue` construction
close behind -- the "quick win" tier looks exhausted again, same pattern as after the second follow-up's fixes.

**A genuinely new cost surfaced in the SortedSet engine's post-fix profile, identified but explicitly NOT
attempted this round**: with the tuple-boxing/redundant-decode cost gone, `SimplexIndexing.apply(simplex:
Simplex[Int]): Long` (the ENCODE direction -- `simplex.toSeq.sorted.reverse.zipWithIndex.map(...).sum`, called
once per `coboundaryOf`/`zeroPivotCofacet`/`zeroPivotFacet` invocation each, to seed that call's own cursor -- a
PRE-EXISTING cost, unchanged by this round, just no longer hidden behind something bigger) and
`FiniteMetricSpace.MaximumDistanceFiltrationValue`'s per-candidate closure/`SortedSet` iteration (inside
`zeroPivotFacet`'s `filtrationValue(sigma)` check -- the SAME shape of cost `PackedRipserCohomologyContext.
zeroPivotFacet` already fixed via its own array-based `maxPairwiseDistance`, but never applied to this engine's
equivalent call) together now account for a substantial share of allocation. A future session wanting to chase
this further has two concrete, already-scoped options: (a) give `RipserCohomologyContext.zeroPivotFacet` the same
`maxPairwiseDistance(Array[Int])` treatment `PackedRipserCohomologyContext` already has, building the candidate's
array from `tau`'s own already-decoded vertices via `FacetCursor.vertex`-based removal rather than
`filtrationValue`'s generic `SortedSet` path; (b) look at whether `apply(simplex)`'s `toSeq.sorted.reverse.
zipWithIndex.map(...).sum` chain (five separate allocating stages for what's structurally a single reduction) can
be collapsed into a `while` loop the same way every hand-rolled hot-path method in this file already has been.
Neither attempted here -- flagged for the project lead to decide on, matching this arc's own established practice
of naming the next cost rather than chasing it unasked.

**Files changed this session**: `RipserStream.scala` (`CofacetCursor`/`FacetCursor` classes, `cofacetCursor`/
`facetCursor` factory methods, `cofacetIteratorWithVertex`/`facetIterator` rewritten as thin wrappers),
`PackedRipserCohomology.scala` (`sparseCofacets`/`coboundaryOf`/`zeroPivotCofacet`/`zeroPivotFacet` rewired),
`Homology.scala` (`coboundaryOf`/`zeroPivotCofacet`/`zeroPivotFacet` rewired, including the incremental-insertion/
incremental-removal restructuring), `SimplexIndexingSpec.scala` (two new cursor-correctness properties). A fifth
scratch driver this arc, `SingleEngineProfileDriver.scala` (single engine, single JVM process, no sbt/timeout
machinery) -- kept past this session rather than deleted, since the same shape is needed for the still-pending
time-and-memory-vs-real-`ripser.cpp` comparison this session's own request also asked for; see that deliverable's
own section below once it exists.
