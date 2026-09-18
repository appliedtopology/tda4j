# Packed `(Double, Long)` Ripser engine: design, cross-validation, and measured performance

Phase 2 of the plan that started with [[tda4j-maxdim-semantics-fix]] (Phase 1, `maxDim`/`maxDimension`
semantics) and [[tda4j-ripser-paper-benchmark]] (which found the ~20µs/simplex flat constant-factor tax
against real `ripser.cpp` and named the `Simplex[Int]`/`SortedSet[Int]` reduction-time carrier as the leading
suspect). The project lead's explicit direction: try a paired `(Double, Long)` cell type through the existing
`Chain.reduceBy` machinery first, and only consider a hand-rolled reduction if that doesn't work.

## Design

`PackedRipserCohomology.scala`, a new class `PackedRipserCohomologyContext[CoefficientT: Field]` living
alongside `RipserCohomologyContext`, not replacing it. Same algorithm end to end — clearing, apparent pairs
(mutual + on-the-fly substitution), sparse-Rips threshold, `maxDim`-means-top-reported-degree semantics
inherited correctly from Phase 1 — re-keyed onto a packed cell:

```scala
final case class DiameterIndex(diameter: Double, index: Long):
  override def equals(other: Any): Boolean = other match
    case that: DiameterIndex => this.index == that.index
    case _                   => false
  override def hashCode(): Int = index.hashCode()

given packedOrdering: Ordering[DiameterIndex] = compareDiamThenIndex(_, _)
```

**Why a pair, not a bare `Long`** (the project lead's own correction, given before implementation started): a
combinatorial-number-system index alone carries no filtration-order information — real `ripser.cpp` handles
this with separate sort/compare code operating on its own `diameter_index_t` struct, not on a bare index. Here
that becomes a paired cell type plus one `Ordering` instance, so `Chain[DiameterIndex, CoefficientT]` reduces
correctly with zero changes to `Chain.reduceBy`/`reduceByUntil` — confirmed directly against `Chain.scala`
before writing any code: every reduction primitive `RipserCohomologyContext` actually uses is bounded on bare
`Ordering`, never on `OrderedCell`/`.boundary`. The paired approach worked on the first attempt; the fallback
(a hand-rolled, non-`Chain`-based reduction) was never needed and stays out of scope, per the original plan.

**`equals`/`hashCode` on `index` alone, not the pair.** `SimplexIndexing`'s combinatorial index is only unique
*within one fixed simplex dimension* — index 5 at dimension 1 and index 5 at dimension 2 are different
simplices — so `cleared`, unlike the current engine's self-describing `mutable.Set[Simplex[Int]]`, is
redesigned as a **rotating pair of per-dimension sets** (`activeCleared`/`nextCleared: mutable.Set[Long]`,
swapped at each dimension boundary) rather than one long-lived accumulating set. This was flagged as a real
correctness trap during planning, not discovered as a bug afterward — the property tests below were written
specifically to exercise it (random clouds up to `maxDim=3`).

**Cofacet enumeration exposing the inserted vertex for free**: `SimplexIndexing.cofacetIterator`
(`RipserStream.scala`) already threads the candidate vertex `j` as `Iterator.unfold` state and discarded it
before returning. Added `cofacetIteratorWithVertex(index, size, allCofacets): Iterator[(Int, Long)]` alongside
it (same unfold logic, `cofacetIterator` now defined in terms of it) so `coboundaryOf`'s sign computation and
`insertionDiameter` get the inserted vertex at no extra decode cost — it was already loop state.

**No separate `filtrationValue`/memoization layer**: the diameter is always carried inline in `DiameterIndex`,
so there's nothing to memoize and nothing that can go stale.

## Cross-validation (`PackedRipserCohomologySpec.scala`)

9 tests, all passing, cross-validated against `RipserCohomologyContext` (not against hand-derived barcodes —
that would just re-litigate `RipserCohomologySpec`'s own already-established correctness):

- Hand fixtures: `threePointLine` at `maxDim=1` and `maxDim=2`, and the apparent-pair collision cloud
  `RipserCohomologySpec` already pins as a regression (a genuine mutual apparent pair whose `sigma` also has a
  second, non-apparent-paired cofacet tied at the same value — the exact shape that caught a real bug in the
  reference engine's own apparent-pairs shortcut, worth reusing since the packed engine reimplements that
  shortcut independently).
- Substitution-firing tests: `substitutionCount > 0` on the collision cloud with apparent pairs enabled, `== 0`
  with them disabled — not just "the barcode is unchanged," which a fallback that never fires could also
  produce.
- Property tests (200 trials each): random Vietoris-Rips clouds unthresholded and at the default
  enclosing-radius threshold, `totalSimplexCount` agreement, and `maxDim=3` (confirming the Phase 1 semantics
  fix carried over correctly to the packed engine).

All 9 passed on first correct implementation — the paired-cell design didn't need any correctness rework once
the rotating-`cleared`-set and vertex-exposing changes above were in from the start.

## Measured performance

Two measurement passes were needed. The first (`RipserPaperBenchmarkSpec`'s default dual-engine mode — real
`ripser.cpp` / `RipserCohomologyContext` / `PackedRipserCohomologyContext`, one JVM, one run) produced a
methodology problem worth recording as its own finding before the actual numbers.

### A benchmark-harness bug found while measuring, not a result

`withTimeout` (`RipserPaperBenchmarkSpec.scala`) has no cooperative cancellation — its own doc comment already
says so, for the reason `EngineComparisonBenchmarkSpec` established earlier: neither engine here supports it.
What wasn't previously exercised is what happens when **two** engines are timed back-to-back in the same
process and the first one times out: `Await.result` gives up waiting, but the abandoned `Future` keeps running
on its daemon thread. Once `RipserCohomologyContext` (SortedSet) started timing out at `sphere3_192`, every
case after it ran with an accumulating pile of abandoned SortedSet computations still consuming CPU and heap in
the background — observed directly as JVM resident size climbing from ~1.8GB to ~5.6GB over four supposedly
independent cases, and confirmed by the packed engine's own reported times going to `"timeout"` for cases where
it should have been fast (`dragon`, `o3_1024`, `fractal-r` all reported `packed(ms) = "-"`, status
`"SortedSet: timeout"` masking whatever packed actually did). **This is a real gap in the benchmark harness,
not a finding about either engine** — fixed by adding a `-DpackedOnly=true` flag that skips
`RipserCohomologyContext` entirely, so the packed engine's own scaling could be measured in a clean process
(see the flag's own doc comment in `RipserPaperBenchmarkSpec.scala` for the mechanism). The dual-engine table's
ratio columns are only trustworthy for cases where **both** engines actually finished — anything after the
first timeout needs re-measuring with the flag instead, which is what was done here.

### The clean numbers

Only two cases ran to completion on both engines in the same (uncontaminated) process, both from the very
start of that run before any timeout had occurred:

| case | ripser.cpp | SortedSet (`RipserCohomologyContext`) | packed | S/pack |
|---|---|---|---|---|
| sphere3_48 (n=48) | 10.0ms | 1779.0ms (177.9x) | 377.0ms (37.7x) | **4.72x** |
| sphere3_96 (n=96) | 50.0ms | 33730.0ms (674.6x) | 3394.3ms (67.9x) | **9.94x** |

Using `totalSimplexCount` from `WORKLOG-ripser-comparison.md`'s own diagnostic on these same two clouds
(136,390 and 2,480,471 simplices respectively, at this `maxDim`): SortedSet costs ~13.0µs/simplex and
~13.6µs/simplex (consistent with the ~20µs/simplex figure from that session, same order of magnitude, not an
exact match — different JVM/run, and this run's packed engine ran second, inheriting some JIT warm-up of the
shared generic `Chain.reduceBy` machinery from SortedSet's immediately-preceding run on the same case, a real
if modest confound noted in the code's own comment). Packed costs ~2.76µs/simplex and ~1.37µs/simplex. **The
speedup is real and growing with size, not flat** — 4.72x at n=48, 9.94x at n=96 — consistent with removing a
genuine per-comparison cost (the `SimplexIndexing.apply` encode `RipserCohomologyContext`'s comparator pays
twice per comparison, replaced here by pure `Long`/`Double` comparison on an already-decoded pair) rather than
a one-time fixed overhead that would wash out at scale.

### Packed engine alone, at larger scale (clean, no contamination)

A second run (`-DpackedOnly=true`, 240s timeout, no SortedSet in the process at all) measured the packed
engine's own scaling further up the paper's data-set ladder:

| case | ripser.cpp | packed (clean) | x vs ripser |
|---|---|---|---|
| sphere3_48 | 10.0ms | 544.0ms | 54.4x |
| sphere3_96 | 50.0ms | 3704.1ms | 74.1x |
| sphere3_192 | 660.0ms | 52970.9ms | 80.3x |
| dragon | 1150.0ms | **timeout (>240s)** | — |
| o3_1024 | 1570.0ms | **timeout (>240s)** | — |
| fractal-r | 3030.0ms | **timeout (>240s)** | — |
| random16 | 3440.0ms | **timeout (>240s)** | — |
| o3_4096 | 30790.0ms | **timeout (>240s)** | — |

(sphere3_48/96's clean-process numbers here, 544ms/3704ms, are higher than the 377ms/3394ms measured
back-to-back with SortedSet above — this is the JIT-warm-up confound named above showing up directly: without
a preceding SortedSet run to pre-JIT the shared reduction path, the packed engine's own cold-start cost is
higher. Both measurements are honest; they answer slightly different questions, "packed after SortedSet has
warmed the JVM" vs. "packed alone, cold.")

sphere3_192 completing in 53s is itself informative: extrapolating `totalSimplexCount`'s own measured growth
pattern on this family (136,390 → 2,480,471 is an 18.2x jump for a 2x point-count increase — this family does
not scale like a simple `O(n^{d+1})` bound, so a similar ~18x jump 96→192 would put sphere3_192 around 45M
simplices) gives roughly 1.2µs/simplex at this scale — in the same range as, if not slightly better than, the
1.4-2.8µs/simplex measured at n=48/96. Per-simplex cost is not degrading as the complex grows into the tens of
millions of simplices, which is the honest positive reading of this one data point. This is an extrapolation
from a growth pattern observed on a different (smaller) pair of clouds, not a directly measured count for
sphere3_192 itself — `totalSimplexCount` is only available for outcomes that returned normally, and this
would need adding a print statement and re-running to confirm directly.

**dragon, o3_1024, fractal-r, random16, and o3_4096 did not complete within 240s even with the packed engine
running alone.** This is the honest, mixed part of the result: the representation change measurably helps
(4.72x-9.94x over the existing engine on the cases both could finish, growing with size, not flat), but it
does not close the gap to real `ripser.cpp` on the paper's harder cases, and several of those remain entirely
out of reach for either tda4j engine within a reasonable time budget. `dragon` in particular (n=2000,
`maxDim=1`, so only 2-simplices are ever transiently touched) timing out is notable — it's a much smaller
nominal request than `o3_4096`, so its complex size (driven by the default `minimumEnclosingRadius` threshold
on a real 3D scan's point density, not by `maxDim`) is evidently far larger than its point count alone
suggests. Not investigated further in this pass — that would be a new profiling arc, not a continuation of
"does the packed representation help."

### What this does and doesn't answer

The two elimination effects named in the original plan — no more `SortedSet[Int]` carried through
`Chain.reduceBy`'s `PriorityQueue`/`SortedMap` machinery, and no more `SimplexIndexing.apply` encode on every
single ordering comparison — together produce a real, measured, size-growing speedup on every case both
engines could complete. That is a genuine positive result for the representation hypothesis from
`WORKLOG-ripser-comparison.md`, not a wash. It is not, on its own, enough to make this codebase
competitive with real `ripser.cpp` on the paper's own harder benchmark cases within this session's time
budget — real ripser is still 40-80x faster even after the fix, and several cases remain unmeasurable in
either engine. The coefficient field used throughout (`IntMod2.Fp`, an opaque `Int`-backed type, boxing to
`java.lang.Integer` in every `Chain` term) is a possible confound on the *absolute* per-simplex numbers — it
affects both engines identically, so it cannot explain the SortedSet-vs-packed ratio, but it could be masking
some of the remaining gap to real `ripser.cpp`'s native, unboxed arithmetic. Not probed in this pass (the
S/pack headline result was clearly non-flat, so the specific condition that would have justified spending time
on a supplementary `Double`-coefficient run — a flat or ambiguous headline number — didn't hold).

## Status

Both engines live side by side in the codebase (`RipserCohomologyContext`, `Homology.scala`; the packed engine,
`PackedRipserCohomology.scala`), per the original plan's explicit "parallel, not a replacement" scope. The
packed engine is not wired into any facade (`matlab/Tda4j.scala`) or `EngineComparisonBenchmarkSpec` — that
was out of scope for this pass, and remains the project lead's own call, per [[tda4j-commit-workflow]] and the
plan's own scope note.

## Update: 30-minute-per-case packed-only run, three more cases resolved

The project lead asked for a follow-up run giving each case a full 30-minute (1800s) budget instead of the
240s used above ("it's fine if it takes all night"), specifically to see how much further up the paper's data-
set ladder the packed engine could get.

### An OS-level kill, not a code bug, on the first attempt

The first attempt (`-J-Xmx16G`, matching every earlier run in this arc) was killed outright by the OS partway
through (`dragon`, the first case past `sphere3_192`) for system-wide low memory — not an in-JVM
`OutOfMemoryError`, and not caught by `withTimeout`'s own exception handling at all. `vm_stat`/`sysctl
vm.swapusage` at the time showed only ~8GB of this machine's 32GB actually free (IntelliJ, WindowServer, two
Brave windows, and PyCharm already resident) and swap at 92% utilization (18.8GB/20.5GB) — requesting a 16GB
JVM heap on top of that left no real headroom, so once the heap actually grew toward that scale the OS reclaimed
memory by killing the process. **Not a finding about the packed engine or this codebase** — re-launched with
`-J-Xmx6G` instead, deliberately small enough to fit the machine's actual free memory at the time, so that any
further failure would be a real, informative in-JVM `OutOfMemoryError` rather than an opaque OS kill.

### Results at 30 minutes / 6GB heap

| case | ripser.cpp | packed (30min/6G) | x vs ripser |
|---|---|---|---|
| sphere3_48 | 10.0ms | 512.6ms | 51.3x |
| sphere3_96 | 50.0ms | 3568.8ms | 71.4x |
| sphere3_192 | 660.0ms | 50192.2ms | 76.1x |
| dragon | 1150.0ms | **347088.5ms (347s)** | **301.8x** |
| o3_1024 | 1570.0ms | **470306.5ms (470s)** | **299.6x** |
| fractal-r | 3030.0ms | timeout (1800s) | — |
| random16 | 3440.0ms | **339047.3ms (339s)** | **98.6x** |
| o3_4096 | 30790.0ms | timeout (1800s) | — |

Three more of the paper's cases (`dragon`, `o3_1024`, `random16`) now complete with real numbers — the extra
budget genuinely helped, not just extended a hang. `fractal-r` and `o3_4096` still did not finish; total wall
time for the run (4813s) minus the sum of every completed case's own time leaves `3602.3s` unaccounted for,
almost exactly `2 x 1800s` (`3600s`) — strong arithmetic evidence both failures were genuine 1800s timeouts, not
early `OutOfMemoryError`s, so a targeted re-run to disambiguate wasn't needed.

**The honest reading of the three new numbers**: the gap to real `ripser.cpp` *widens*, not narrows, as these
harder cases are added — 51-76x on the sphere3 ladder, but 99-302x on `dragon`/`o3_1024`/`random16`. This
doesn't contradict the earlier positive S/pack finding (that comparison was always packed-vs-SortedSet, not
packed-vs-ripser, and remains valid on the cases where both were measured) — it does mean the packed
representation's win over the old engine doesn't translate into closing the gap to `ripser.cpp` itself on these
harder, more irregular data sets. `dragon` in particular (n=2000, `maxDim=1`, so only transient 2-simplices)
taking longer than `o3_1024` (n=1024, `maxDim=3`, thresholded) despite its lower nominal dimension confirms the
earlier suspicion: its complex size, driven by `minimumEnclosingRadius` on a real 3D scan's point density, is
evidently far larger than point count or requested dimension alone would suggest — still not profiled.

### A second benchmark-harness bug, found and fixed this same session

`RipserPaperBenchmarkSpec`'s `status`/`barsOk` columns had their own masking bug in `-DpackedOnly=true` mode:
both computed a `match` on `(sortedSetOutcome, packedOutcome)` whose first case fired on `sortedSetOutcome`
being `Left(...)` unconditionally — but `packedOnly` mode always sets `sortedSetOutcome = Left("skipped")` by
construction, so that case fired every time regardless of what `packedOutcome` actually was, permanently
printing `"SortedSet: skipped"` and `"-"` even when the packed engine itself had failed with a real, specific
reason (timeout vs. `OutOfMemoryError` vs. something else) worth knowing. This is exactly why `fractal-r`'s and
`o3_4096`'s failures above had to be disambiguated by wall-clock arithmetic instead of just reading the status
column. Fixed at the source: both `status` and `barsOk` now branch on `packedOnly` explicitly and report
`packedOutcome`'s own result/reason directly when SortedSet was deliberately skipped, rather than falling
through the general two-engine `match`. This is a permanent fix, not a throwaway diagnostic — kept in the
committed spec.
