# WORKLOG: Parallelization survey (2026-09-22)

Point-in-time record of a code survey requested by the project lead: where parallelization would be
impactful, and what platform (parallel collections / Future / Akka / cats-effect / ZIO / other) should carry
future parallelization work. No code was changed this session — this is a research/recommendation pass, not
an implementation arc. Findings are grounded in reading the actual source (not inferred from CLAUDE.md's
summaries alone), with `advisor()` consulted once after initial orientation.

## Starting point: what's already in the dependency graph

`build.sbt` already depends on `org.scala-lang.modules %% scala-parallel-collections % 1.0.4`. Actual usage is
almost nonexistent: one real call site (`SymmetryGroup.scala:497`, `generators.par.forall(...)`) and one dead
import (`RipserStream.scala:13`, `CollectionConverters.*` imported, never used). `scala.concurrent.Future` +
`ExecutionContext` (stdlib) is used in exactly one place in `main`, none — it's confined to the four benchmark
specs (`EngineComparisonBenchmarkSpec`, `RipserPaperBenchmarkSpec`, `CubicalBenchmarkSpec`,
`DimensionCeilingBenchmarkSpec`), all via the same pattern: a daemon-thread `Executors.newCachedThreadPool` +
`Await.result(Future(body), timeout)`, used purely for a per-cell timeout, not for concurrency — each cell is
still launched and awaited one at a time. No Akka, cats-effect, ZIO, or Scalaz dependency anywhere. No
`java.util.concurrent.StructuredTaskScope`/virtual-thread usage.

## Framing: measured cost, not apparent loop shape

The existing profiling worklogs (`WORKLOG-ripser-profiling.md` and its three follow-up sessions) already show
where wall-clock time actually goes in the two production reduction engines (naive `CellularHomologyContext`,
`PackedRipserCohomologyContext`): `Chain.reduceLoop`'s `TreeMap` churn, `insertionDiameter`'s distance-math
loop, `SimplexIndexing`'s encode/decode, `zeroPivotFacet`'s `maxPairwiseDistance`. All of that lives *inside*
the pivot-reduction algorithms themselves — sequential by the algorithm's own data dependency (a column's
reduction can consume an earlier column's pivot), not by an accident of implementation. That's the honest
headline: parallelism's real leverage in this codebase is in the **construction / filtration-value phases**
that feed the reduction engines, not in the reduction engines' own hot loops. Every genuinely-promising target
below is a construction-phase target.

## Findings, ranked by leverage-to-risk

### 1. `AlphaComplexDQP.compute()` — verified safe, cheapest real win — SHIPPED this session

`AlphaDQPSettings.parallel: Boolean = false` (`AlphaComplexDQP.scala:245`) is a **documented, dead flag** —
its own doc comment says "run the per-vertex loop on the common ForkJoinPool. Output is deterministic," but
`grep` for `settings.parallel` in the file returns nothing. It is never read anywhere.

The loop it's meant to guard (`compute()`, lines 910-926) is *already* shaped as parallel-map-then-sequential-
merge:

```scala
val perVertex: IndexedSeq[mutable.IndexedBuffer[Found]] = candidates.collect {
  case (x, cs) if cs.nonEmpty => solveAtVertex(x, cs, nbrs(x), wsCap).to(mutable.IndexedBuffer)
}.toIndexedSeq

// Merge in vertex order so the output does not depend on scheduling.
perVertex.foreach { founds => founds.foreach { f => byDim(k) += f.cell; weights(f.cell) = f.weight; ... } }
```

Verified thread-safe, not just assumed: `solveAtVertex` allocates a fresh `DualQP` per call
(`AlphaComplexDQP.scala:1049`), and `DualQP`'s constructor allocates its own private `CholeskyWorkspace` plus
every other mutable array it touches (`ws`, `lam`, `lamStar`, `work`, `rvec`, `inW`, `frozen`, `grad`) — all
per-instance fields, not shared across vertices. `solveAtVertex` reads only `space` (read-only `PowerDistance`,
no internal cache), `candidates`/`nbrs` (read-only, distinct per-vertex buffers), and writes only to its own
local `found` buffer. No shared mutable state crosses the per-vertex boundary during the parallel-safe part of
the loop. (This was the one item flagged by `advisor()` as needing verification before trusting the "one-line
fix" framing — checked directly, holds.)

The determinism argument in the existing code comment ("merge in vertex order") is not actually what makes
this safe — `candidates` is a `mutable.Map`, and `.collect{...}.toIndexedSeq` doesn't sort by vertex, so
`perVertex`'s order is unspecified regardless of scheduling. The real safety property is (a) every simplex is
tested exactly once, bucketed by its own minimal vertex (`buildCandidates`'s own contract), so no two base
vertices ever write the same `weights`/`witnesses`/`byDim` key, and (b) the actual output order comes from the
explicit `sortInPlace()(using cellOrdering)` at line 949, which runs after the merge and is itself independent
of insertion order. Concurrent writes to `byDim`/`weights`/`witnesses` from parallel branches would still need
guarding (or restructuring to per-branch local buffers + one sequential merge, which is what the existing code
already does for the compute part) — the finding is that the *computation* (`solveAtVertex`) parallelizes for
free; the *merge* already runs sequentially and should stay that way.

This is a genuinely small, low-risk change: swap `candidates.collect{...}` for a parallel-collections
equivalent, gated behind the existing (currently-dead) `settings.parallel` flag, keep the merge loop exactly as
is.

**Shipped, same session, after the project lead confirmed CPU-bound reasoning and asked to proceed.**
Implementation: extracted the per-vertex work into `vertices: IndexedSeq[(Int, mutable.IndexedBuffer[Simplex[Int]])]`
+ a `solveEntry` helper, then `if settings.parallel then vertices.par.map(solveEntry).toIndexedSeq else
vertices.map(solveEntry)` — `scala.collection.parallel.CollectionConverters.*` imported (same convention as
`SymmetryGroup.scala`). The merge loop is untouched. New regression spec,
`AlphaComplexDQPParallelSpec` (`AlphaComplexSpec.scala`): builds the same random point cloud with
`AlphaDQPSettings(parallel = false)` and `AlphaDQPSettings(parallel = true)` via
`AlphaComplexDQP.euclidean(...)` directly (200 trials, `[2,4]` dimensions x `[6,14]` points — deliberately
bigger than `AlphaComplexSpec`'s own generator so most dimensions actually have multiple candidates per base
vertex, giving the parallel branch real concurrent work most runs) and asserts `cellsOfDimension`,
`filtrationValue`, and `witness` agree exactly, per dimension, per cell — not just that both sides don't crash.
All 200 trials agreed exactly on first run; no tolerance/approximate-equality needed since both branches
perform the identical `solveAtVertex` computation, just scheduled differently. Full `sbt test`: 326 total, 0
failed, 0 errors (316 passed, 10 skipped benchmark specs, 1 pending — unchanged from the pre-existing
baseline). `scalafmtAll` run before finishing.

### 2. `CellularPersistenceInChunksContext.advanceAll()` — real algorithm, not a loop annotation

`Homology.scala:862` has a standing comment on exactly this loop:

```scala
for b <- (r - 1).until(m) do // parallelizable!
```

This is the local-reduction phase of Bauer–Kerber–Reininghaus's clear-and-compress algorithm (the actual
published distributed/parallel persistent-homology algorithm this class implements) — the two-round doubling
windowing scheme exists specifically so same-round chunk blocks are mathematically independent. But realizing
the comment is **not** a matter of dropping in a concurrent collection. `processCell` (called per cell inside
this loop) reduces against the shared `boundaries` map via `Chain.reduceByUntil`, and a hit finalizes through
`recordPair`, which writes `boundaries`, `cleared`, `paired`, `killer`, `killerOf`, `essentialSimplices`, and
`barcode` — all shared `mutable.Map`/`mutable.Set` state. The dependency isn't just a race to guard with a
lock: block `b`'s reduction can, by design, consume a pivot that block `b'` (b' < b, same round) recorded
during this same phase. Making this safe means giving each chunk block its own **local** pivot map during the
local phase (what the paper actually specifies) and merging afterward — a real restructuring of `processCell`/
`recordPair`'s state threading, not a parallel-collections swap on the existing loop.

This is also the single most fragility-sensitive piece of code in the whole codebase by CLAUDE.md's own
account: two separate, subtle correctness bugs were found and fixed in this exact local/global chunk-pairing
mechanism in the last few sessions (`WORKLOG-chunks-pairing-bug.md`, `WORKLOG-benchmark-and-chunks-bug.md`) —
one from a stale-snapshot-vs-mutation hazard, one from an in-limbo-cell misclassification across the local/
global boundary. A parallelization attempt here needs the same treatment those got: hand-derived fixtures,
cross-validation against the naive engine cell-for-cell (not just birth/death agreement), and ideally
`advisor()` on the design before writing code — not something to fold into a quick pass. Highest leverage of
anything found (chunking exists *specifically* for this), but the highest risk too — a dedicated session.

### 3. `CechFiltration.computeRadius` — SHIPPED, real ~9-10% win, capped by surrounding sequential machinery

`CechStream.scala`'s Miniball-based radius computation is a `mutable.HashMap[Simplex[Int], Double]` cache
(non-thread-safe) where a simplex's radius computation reads its own facets' *already-cached* radii one
dimension down (never a fresh Miniball call on a facet — that's the whole point of the cache, per its own
doc). Within one dimension, once the dimension-below cache is fully populated, sibling same-dimension Miniball
calls are mutually independent — same "parallel-map, sequential-merge" shape as DQP, but layered by dimension
rather than flat, and currently implemented as a single mutable cache rather than a batch computed then
written. A real win, but needs restructuring into "compute all of dimension d in parallel against a frozen
dimension d-1 cache, then merge into the cache, then move to d+1" rather than a direct `.par` on the existing
recursive `computeRadius`.

**Shipped, differently from the original sketch above once the actual hook was traced.** The "batch-then-merge
into a frozen cache" restructuring wasn't needed after all: `CechFiltration`'s own `mutable.HashMap` cache was
swapped for `scala.collection.concurrent.TrieMap` (`CechStream.scala`) — a genuine drop-in (identical
`getOrElseUpdate` signature, so `apply`'s own logic needed zero changes), backed by a lock-free Ctrie, safe for
concurrent reads AND writes. `RipserCofaceSimplexStream`'s own default-path `filtrationValueCache` (used when
no override is supplied, i.e. plain VR) got the identical swap, for the same reason — see below for why this
touches that shared class at all. With the cache genuinely thread-safe, no separate "compute raw, merge later"
scaffolding was needed: a new `parallelFiltrationValue: Boolean = false` constructor parameter on
`RipserCofaceSimplexStream` (threaded through `CechCofaceSimplexStream`'s own constructor of the same name)
gates a pre-warm step inside `iterateDimension`'s existing dimension`>= 1` case — the candidate-generation `for`
comprehension was extracted once into a `rawCandidates: IndexedSeq[Simplex[Int]]` val (previously inlined with
its own filter), and when the flag is set, `rawCandidates.par.foreach(c => filtrationValue.applyOrElse(c, ...))`
warms the (now-safe) cache in parallel before the unchanged sequential filter+sort runs. The `parallel = false`
path is behaviorally identical to before (same candidates generated, same filter, same sort — verified, not
just reasoned: `sbt test` unchanged at 330/0/10/1 both before and after).

**Real design question resolved before writing code, not assumed**: the natural per-dimension hook for this
(mirroring cubical's own `iterateDimension`) lives in `RipserCofaceSimplexStream`, a class SHARED by plain VR
and Cech alike — `CechCofaceSimplexStream extends RipserCofaceSimplexStream`, supplying only a
`filtrationValueOverride`. Two options were weighed: duplicate the candidate-generation loop in a Cech-only
override (rejected — this codebase has repeated, explicitly-documented history of exactly this kind of
duplication drifting out of sync and corrupting a reduction, e.g. the three-times-repeated
`filtrationOrdering`-tie-break bug in CLAUDE.md's own "Bug found while cross-validating" section), or add the
flag generically to the shared class (chosen — architecturally consistent with how this class family already
generalizes, e.g. `filtrationValueOverride` itself was added there specifically FOR Cech). This means
`parallelFiltrationValue` is technically usable on plain VR via `RipserCofaceSimplexStream` too, not just Cech
— flagged honestly as a side effect of sharing the hook, since item 5 (VR's own filtration-value cost) was
declined this session as a dedicated target. **The project lead confirmed, when this was flagged, that VR using
the flag is welcome, not something to wall off** — closed out with a dedicated regression test
(`SimplexStreamSpec`'s `CofaceSimplexStreamSpec`, mirroring the existing memoization-toggle property's own size
cap of maxDim=2/points 6-12, for the same documented OOM-avoidance reason) proving `parallelFiltrationValue =
true`/`false` agree exactly on plain `RipserCofaceSimplexStream`, both per-simplex filtration values and the
full barcode — not left as an untested side effect once it was explicitly endorsed. Full suite: 332 examples
(up from 330), 0 failures.

**Verified correct**: two new tests in `CechStreamSpec` — a ScalaCheck property (reusing the file's own random
2D point-cloud generator) asserting `parallelFiltrationValue = true`/`false` compute IDENTICAL per-simplex
filtration values (not just an agreeing barcode) across every dimension, and a hand-derived-fixture test (the
unit equilateral triangle, this spec's own sign/value-discriminating fixture) pinning exact barcode agreement.
Full suite: 330 examples (up from 328), 0 failures.

**Measured (same driver methodology, `CechParallelProfileDriver`, both stream-only construction time and full
end-to-end persistent homology timed separately)**: full end-to-end timing turned out to be dominated by an
UNRELATED, pre-existing cost — `SimplicialHomologyContext`'s own naive reduction scales badly with cell count
on an untruncated Cech complex (n=20/maxDim=2: 1350 cells, 1.4s; n=25: 2625 cells, 36.0s — a ~27x time jump for
under 2x more cells, nothing to do with this change), so stream-only construction time (what this change
actually touches) is the honest metric. At maxDimCap=2 (triangles, <=3 points per Miniball call), n=60
(36,050 cells): 248.7ms sequential vs. 252.1ms parallel — no measurable difference, too little aggregate work
per call to be worth the scheduling overhead. At n=200 (1,333,500 cells): 18330.7ms vs. 16476.7ms — **~10.1%
faster**. At a different configuration entirely (n=20, ambient dim=6, maxDimCap=5 — larger simplices, up to 6
points per Miniball call, 60,459 cells): 1024.1ms vs. 932.4ms — **~9.0% faster**. Two very different regimes
(large-n/low-dimension vs. small-n/high-dimension) landing at the same ~9-10% is a real, stable result, not
noise — but it's a much smaller win than DQP's 2.4x-3.9x: the parallelized filtration-value computation is only
one phase among several sequential ones (candidate-list construction, the filter pass, the sort) that this
change deliberately left untouched to avoid duplicating `RipserCofaceSimplexStream`'s shared logic — the same
Amdahl's-law shape the cubical work already found, just with a larger parallelizable fraction (~10% vs. ~5-6%).

### 4. `CubicalGridStream.filtrationValueCache` — SHIPPED, but a real, capped, modest win — not a big one

**Shipped**: a new `parallelFiltrationValue: Boolean = false` constructor parameter on `CubicalGridStream`
(threaded through `CubicalImage.scala`'s five convenience constructors too, since that's how most callers
actually build one). `iterateDimension(d)`, when the flag is set, computes every dimension-`d` cube's raw
filtration value (`containingTopCells(c).map(topCellValue).min`) in a `scala-parallel-collections` `.par.map`
first -- into a plain `IndexedSeq`, no shared mutable state touched during the parallel phase -- then merges
into the existing `filtrationValueCache` sequentially via `getOrElseUpdate`, avoiding any concurrent write to
that (non-thread-safe) `mutable.HashMap`. `cubesOfDimension(d)` yields each cube exactly once, so the merge
never double-writes. Default `false`, zero behavior change for existing callers. Every built-in `topCellValue`
closure in `CubicalImage.scala` (flat-array reads, `BufferedImage.getRGB`) is safe to call concurrently
(reads only immutable captured data); documented as a precondition on the new parameter for a caller-supplied
`topCellValue`.

**Verified correct**, not just assumed: a new ScalaCheck property (`CubicalStreamSpec`, reusing the file's own
tie-heavy `genTestImage` generator) asserts `parallelFiltrationValue = true` and `= false` produce IDENTICAL
per-cube filtration values, not just an agreeing barcode -- a corrupted individual value that still happened
to sort into the same relative order would pass a barcode-only check. Plus a second test pinning exact barcode
agreement on the file's existing three hand-derived tie-heavy fixtures. Full suite: 328 examples (up from 326),
0 failures.

**Measured (same `CubicalParallelProfileDriver`/`CubicalProfileDriver` methodology as the DQP work) -- and the
honest result is a real but SMALL, occasionally negative win, not a DQP-scale one**: phase-separated timing
(reusing the existing, unmodified `CubicalProfileDriver`) shows the phase this change targets --
`iterateDimension`'s own per-dimension sort/cache-warm pass -- is only **~5-6% of total wall-clock time**, in
BOTH 2D and 3D (3D, n=24: 602ms of 11,672ms total; 2D, n=400: 2496ms of 38,825ms total) -- the other ~90%+ is
`advanceAll`'s own reduction (`Chain.reduceLoop`'s accumulator, already identified as the dominant cost in the
Ripser-profiling arc and never touched here). This is an Amdahl's-law ceiling, not a bug: even PERFECT,
zero-overhead parallelization of a 5%-of-total phase caps total speedup at ~5%. Measured directly: 3D n=24
(117,649 cells) sequential 9959.4ms vs. parallel 9484.8ms median -- **4.8% faster**, matching the ceiling
almost exactly. 3D n=16 (35,937 cells) sequential 1686.9ms vs. parallel 1797.3ms -- **6.5% SLOWER** --
parallel-collections' own scheduling overhead exceeds an already-small (proportionally capped) budget at this
size, the same regime DQP's own small-n noise floor lives in, just with a lower ceiling here so the crossover
happens at a larger absolute size.

**Honest framing, not oversold**: unlike DQP (a real 2.4x-3.9x win once the input is large enough), this
change's own maximum possible benefit is small by construction -- the underlying `filtrationValueCache`
memoization (added in an earlier session) already eliminated the expensive part (repeated recomputation during
reduction); what's left to parallelize is only the FIRST-touch computation, a small fraction of total time.
Kept anyway: it's correctness-verified, opt-in, zero risk to any existing caller, and a real (if modest, and
size-dependent in sign) win for callers who explicitly ask for it on large-enough grids. Not recommending it as
a default-on change.

(Original candidate-identification note, for context: `CubicalStream.scala:86`'s `filtrationValueCache` was
flagged as the simplest cache-based candidate because there's **no cross-cell dependency at all** —
`containingTopCells(c).map(topCellValue).min` for two different cubes never reads each other's cache entries,
unlike Cech's facet dependency below — which is exactly what made pre-populating it in one parallel pass, then
handing a finished cache to the reduction engines, a safe and simple change. That original reasoning held up;
the measurement above is what determined how much it was actually worth.)

### 5. `FiniteMetricSpace` / `cechNeighbours()` / VP-tree-adjacent computations — real but diffuse

Pairwise distance computation (`EuclideanMetricSpace.distance`, `minimumEnclosingRadius`,
`AlphaComplexDQP.cechNeighbours()`'s neighbor enumeration) is classically embarrassingly parallel — each pair
independent, no shared state. Smaller and more diffuse than 1-4 above (no single loop dominates), worth
revisiting once a parallelization platform is chosen, not a flagship item on its own.

### 6. Reduction engines themselves (naive, `RipserCohomologyContext`, `PackedRipserCohomologyContext`) — not a target here

Column-by-column pivot reduction has a genuine sequential data dependency (a later column's reduction can
require an earlier column's already-resolved pivot). Real parallel/distributed persistent homology is an open
research area with its own published algorithms (GPU-accelerated variants, distributed spectral-sequence
approaches) — item 2 above (chunks) *is* this codebase's own existing attempt at exactly that problem. Nothing
here is a "just add `.par`" opportunity; treat as a separate, much larger, algorithm-design undertaking if ever
pursued, not part of this survey's actionable set.

### 7. Benchmark specs — deliberately excluded, not just deprioritized

Initially looked promising (`EngineComparisonBenchmarkSpec`'s `Await.result` pattern launches one cell at a
time, sequentially, using `Future` purely for its timeout rather than for concurrency — so the whole sweep
could in principle run faster with real concurrency). **Rejected on `advisor()`'s correction**: these specs
*are* the project's own timing measurements, several of them consumed directly by decisions recorded in
CLAUDE.md (e.g., the ~20us/simplex constant-factor-tax finding, the sparse-Rips speedup numbers). Running
cells concurrently would have them contend for cores and corrupt exactly the wall-clock numbers they exist to
produce — directly against this project's own documented measurement discipline (interleaved A/B, checked
noise floors, "machine noise exceeds effect size" in more than one WORKLOG). The sequential `Await` there is a
feature, not an oversight. Not recommending any change to these specs.

## A recurring hazard, not specific to any one item above

This codebase has accumulated many per-instance `mutable.HashMap`/`mutable.Map` memoization caches over
successive profiling sessions (`filtrationValueCache` in both Cech and cubical streams, `binomialEntry`/
`binomialCache` in `SimplexIndexing`, `vcolCache` in chunks, `weights`/`witnesses`/`present` in DQP, the whole
`cleared`/`paired`/`killer`/`boundaries`/`barcode` state in chunks). None of them are concurrency-safe. Any
future parallelization work touching code with one of these caches in scope needs to either (a) confine
concurrent access to a read-only phase over an already-fully-populated cache (item 4's approach), (b)
restructure to parallel-compute-then-sequential-merge (items 1 and 3's approach), or (c) swap the specific
cache to a genuinely concurrent structure (`java.util.concurrent.ConcurrentHashMap` /
`scala.collection.concurrent.TrieMap`) where real concurrent read-modify-write is unavoidable — verified
per-case the way item 1 was here, not assumed safe by pattern-matching to a different, already-verified case.

## An existing anti-pattern found in passing, worth flagging rather than citing as precedent

`SymmetryGroup.scala:497`'s `generators.par.forall(...)` (the only pre-existing real use of
`scala-parallel-collections` in `main`) is very likely net-negative, not a model to follow: `generators` is a
`List[Int => Int]` of length `bitlength - 1` (a hypercube's bit-length, realistically small), and each
generator application is a few bit-shift/XOR operations — nanoseconds of work. `ForkJoinPool` task-submission
overhead for a `.par.forall` over a handful of nanosecond-cost closures is almost certainly larger than just
running it sequentially. Flagged, not fixed, since it wasn't part of this survey's scope — but don't point to
this call site as evidence parallel collections are already working well here; it's the opposite case, a
reminder that every `.par` needs a real size threshold, not blanket application.

## Platform recommendation

**`scala-parallel-collections` for the data-parallel construction-phase targets (items 1, 3, 4, 5).** Already
a project dependency (v1.0.4), so zero new dependency risk. Matches the shape the code is already in (or needs
to be restructured into regardless of platform choice): compute a collection of independent results in
parallel, merge sequentially into shared state. Needs, per site: (a) a size threshold — parallel-collections'
own task-scheduling overhead dominates on small inputs, confirmed as a live risk by the `SymmetryGroup`
anti-pattern above, not a hypothetical one; (b) awareness that the default parallel collections implementation
shares the common `ForkJoinPool`, which `sbt`'s own test parallelism also uses — library-internal parallelism
inside a test run can oversubscribe cores alongside sbt's own parallel test execution, worth checking
`Test / parallelExecution` interaction before shipping any change here.

**`scala.concurrent.Future` + stdlib `ExecutionContext` for orchestration-level, bounded/cancellable work.**
Already the established idiom in this exact codebase (the four benchmark specs' daemon-executor + timeout
pattern) — zero new dependency, and consistent with the direct, imperative style used throughout `main`. Right
tool if a future caller needs "run this with a deadline" rather than "map this collection in parallel"; not
the right tool for items 1/3/4/5's data-parallel shape.

**Reject Akka.** Actor/message-passing is the wrong abstraction for a batch of independent numeric
computations with no need for supervision, location transparency, or long-lived stateful actors — every
candidate above is a flat "map over N independent items, merge the results" shape, which actors add ceremony
around rather than simplify. Heavyweight dependency for what parallel collections already covers.

**Reject cats-effect / ZIO / Scalaz**, on a precedent already set *in this codebase*, not just a paradigm
argument: `.claude/WORKLOG-cli-executable.md` records Decline being rejected in favor of Scallop specifically
*because* Decline pulls in `cats-core`, described there as "a genuinely new dependency family for a codebase
with no typelevel surface anywhere else." `cats-effect` (and ZIO's own comparable effect-system paradigm)
would introduce that same dependency family for concurrency instead of CLI parsing — the project already made
this call once, deliberately, for a smaller ask than "wrap the numeric core in an effect monad." Adopting one
would also mean restructuring the plain, direct-style, mutation-heavy code this survey just walked through
(chunks' local/global state, DQP's per-vertex buffers, every memoization cache) into `IO`-wrapped style — a
much larger paradigm shift than the actual need (independent-item maps) justifies. Scalaz is additionally
legacy relative to cats-effect/ZIO in the current ecosystem; not recommended for new work regardless.

**Not recommending virtual threads or `java.util.concurrent.StructuredTaskScope`.** Virtual threads exist to
cheapen *blocking* (I/O-bound) concurrency; every target identified here is CPU-bound numeric work, so virtual
threads buy nothing. Structured concurrency may be worth a look for the orchestration-level use case
eventually, but check its preview/stable status on whichever JDK this project actually targets before relying
on it — a preview API would impose a `--enable-preview`-style build flag on every downstream consumer of this
library, a real distribution cost not worth taking on for a "nice to have."

## Item 1 (DQP), measured: real speedup at moderate-to-large sizes, noise floor swamps it at small sizes

Measured with a new kept driver, `alpha/AlphaDQPParallelProfileDriver.scala` (mirrors `homology.
SingleEngineProfileDriver`'s convention: a plain `object` with `main`, invoked directly via `java -cp
$(sbt "export Test/fullClasspath")` to skip sbt's own per-invocation startup cost, one JVM process per
(parallel, n, dim) configuration). Machine: 8 cores. Synthetic uniform-random point clouds, same seed shared
between the `parallel=true`/`parallel=false` run at a given (n, dim) so both sides solve the identical
workload. Median of 5-9 in-process trials per run (the first trial in every run is a visible JIT-warmup
outlier, 3-4x the steady-state time — expected and excluded by taking the median, not a sign of anything
wrong).

**dim=3, interleaved, two independent seeds:**

| n   | sequential (median ms) | parallel (median ms) | speedup |
|-----|------------------------|------------------------|---------|
| 60  | 75.7-97.2 (noisy)       | 61.0-67.8 (noisy)      | ~1.1-1.4x, within noise floor -- see below |
| 120 | 522.9                   | 217.2                  | 2.41x |
| 240 | 3156.5 / 2879.2 (seed 2)| 806.4 / 820.8 (seed 2) | 3.91x / 3.51x |
| 480 | 22404.1 / 20290.9 (seed 2) | 7081.2 / 5359.6 (seed 2) | 3.16x / 3.79x |

The n=120-480 results are real and reproducible, not noise: consistent ~3-4x speedup across two independent
point clouds at n=240 and n=480, growing (not shrinking) with problem size, and well-clustered within each
run's own 5 trials once the JIT-warmup trial is excluded (e.g. n=480/seed=2 parallel: 5359.6, 4785.5, 4549.0,
5457.4 -- a ~15% spread, not the 5x+ spread the small-n runs below show). Sub-linear against the 8-core ceiling
as expected: `buildCandidates`, the final `sortInPlace`, and the sequential merge loop are all still
single-threaded, an Amdahl's-law tax that doesn't shrink as `n` grows within one dimension.

**n=40-60 (small): genuinely inconclusive, not a small positive/negative effect -- a noise floor problem.**
Two independent 9-trial rounds at n=60/dim=3 (same seed, immediately repeated) gave medians of 80.0ms and
75.7ms sequential vs. 61.0ms and 66.5ms parallel -- looks like a mild, consistent parallel win. But the
*individual* trial times within a single run swing from ~35ms to ~300ms+ for the identical configuration (e.g.
parallel n=60 round A: `319.6,106.6,90.9,61.0,211.0,46.7,36.4,34.9,45.1`) -- a 9x range having nothing to do
with `settings.parallel`, purely JIT/scheduler noise at this short a runtime (tens to low-hundreds of ms
total). An earlier single 3-trial ad hoc check at the same n=60/dim=3 (done before this more careful sweep)
had shown the *opposite* direction entirely (parallel 203ms vs. sequential 97ms, i.e. parallel over 2x
*slower*) -- purely an unlucky draw from that same noise, not a real effect either way. dim=4/n=40 showed the
same pattern (round A: parallel faster by ~30%; round B, same seed, re-run: parallel slower by ~30%) -- two
directly contradictory results from the identical configuration. **Honest conclusion: at total runtimes under
roughly a few hundred milliseconds, this measurement setup cannot distinguish a real small effect from noise,
in either direction** -- consistent with the survey's own prediction that parallel-collections' scheduling
overhead is a real risk on small inputs, just not precisely quantifiable at this scale without a much larger
trial count or a JMH-style warmup-then-measure harness (not attempted here).

dim=4/n=80 (single round, not repeated a second time): 281.2ms sequential vs. 220.8ms parallel, 1.27x -- a
believable but not yet doubly-confirmed positive result, sitting between the "clear win" and "noise floor"
regimes above.

**Practical takeaway for `AlphaDQPSettings.parallel`**: worth enabling for point clouds large enough that
sequential `compute()` already takes on the order of half a second or more (this machine's n=120/dim=3 case,
~500ms sequential, already shows a clean 2.4x win) -- not for small/fast point clouds, where the flag is a
likely-harmless but unproven coin flip rather than a reliable win. No code change made in response to this
(the flag stays opt-in, as documented) -- this is a usage-guidance finding, not a correctness or default-value
finding.

## Item 2 (chunks), attempted: the plan was invalidated by measurement before any code was written

Picked up on request to actually do the chunks redesign. Before writing code, closed the round-1-independence
argument properly (a real gap `advisor()` flagged: the disjointness proof needs "round-1 chunks read only (a)
frozen higher-delta `boundaries`, and (b) their own chunk-local writes," not index arithmetic alone) and worked
out that round 2 is genuinely NOT chunk-independent (adjacent chunks' round-2 windows overlap, so a naive
parallelization of round 2 risks two chunks racing to claim the same pivot when neither has recorded it yet —
`Chain.reduceLoop` stops and treats a missing `basis` entry as "this is the final pivot," so a missing-because-
not-yet-written-by-a-sibling-chunk entry produces a genuinely wrong double-claim, not just a race on an
already-correct value). The plan that survived that analysis: parallelize round 1 only (provably safe, chunk-
local accumulate + sequential merge), leave round 2/reconciliation/global untouched.

**`advisor()`'s scope warning, then measured, before implementing anything**: no evidence existed that round 1
is where chunks' time actually goes. Added temporary phase-timing instrumentation directly to `advanceAll`
(reverted after use, see below -- `git diff` on `Homology.scala` is empty) and ran it via a throwaway driver
(`ChunksPhaseTimingDriver.scala`, deleted after use) against ordinary Vietoris-Rips complexes at the default
enclosing-radius threshold:

| n  | maxDim | cells  | round1 (ms, paired) | round2 (ms, paired) | reconcile (ms, paired) | markActive | global (ms, paired) | total |
|----|--------|--------|----------------------|----------------------|--------------------------|------------|------------------------|-------|
| 60 | 2      | 83,744 | 1171.4 (0)            | 1029.8 (0)            | **18786.0 (11134)**       | 36.0       | 739.4 (0)               | 23283.9 |
| 40 | 2      | 41,805 | 682.0 (0)              | 520.4 (0)              | **13176.7 (5863)**        | 22.1       | 255.9 (0)               | 15347.3 |
| 40 | 1      | 3,778  | 61.2 (0)               | 39.0 (0)               | **340.6 (457)**           | 5.1        | 20.1 (0)                | 716.4 |

**Round 1 and round 2 resolved exactly zero pairs, on all three configurations.** Reconciliation -- a single
flat sequential pass with no chunking, no windowing, added specifically as the safety net for cells the local
phase left "in limbo" -- did 81-94% of the wall-clock time and effectively all of the dimension >= 2 pairing.
The loop the standing `// parallelizable!` comment marks was, empirically, close to dead weight on every input
tried: real wall-clock cost (rounds 1+2 combined: 1.2-1.7s of the above), zero useful output.

**Confirmed the mechanism directly, not just inferred it from the numbers** (`advisor()`'s explicit instruction
before concluding anything): printed per-dimension cell counts and counted how many chunks contain cells of
more than one dimension. n=40/maxDim=2: dimension counts `(0->40, 1->643, 2->5863, 3->35259)`, `chunkSize=204`,
**205 total chunks, only 3 straddling a dimension boundary**. `chunks = allCells.grouped(chunkSize)` partitions
the dimension-*bucketed* `allCells` vector (all dim-0 cells, then all dim-1, ..., concatenated) into contiguous
slices of size `chunkSize = sqrt(allCells.size)`. Since a Vietoris-Rips complex's higher dimensions vastly
outnumber its lower ones (dimension 3 here is 35,259 cells against a chunk size of 204 -- one dimension alone
spans ~173 chunks), nearly every chunk sits *entirely* inside one dimension's own bucket. Round 1's window is
confined to a chunk's own index range; round 2's adds one adjacent chunk. Neither can find a dimension-`(delta-
1)` pivot for a dimension-`delta` cell unless the two happen to share a chunk -- which, for realistically-sized
complexes, is a 3-in-205 event, not the common case the algorithm's own design assumes.

**Conclusion, taken to the project lead rather than assumed**: this is not "the parallelizable loop needs a
`.par`" -- it's that the chunking scheme's local/global split is, as currently implemented, largely vestigial
for typical inputs. The real fix is chunking *per dimension* (so a chunk spans a `delta`/`(delta-1)` boundary
by construction, restoring the two-round windowed scheme the paper actually specifies) -- which is what would
make round-1 parallelism meaningful in the first place, but is a correctness-neutral, behavior-changing
redesign of a shipped, already-cross-validated class (two prior real bugs in this exact code, see the pairing-
bug and benchmark-bug sections above), not a smaller task than originally scoped. Parallelizing reconciliation
itself is not a viable alternative: it's a single sequential pass with the same ordering dependency the naive
engine has (`eliminationFallback` recurses into `vcolOf`, reading `killer`/`paired`/`boundaries` state built up
term-by-term as the pass runs) -- no windowing argument makes it chunk-safe.

**No code shipped from this item.** All instrumentation reverted (`git diff` on `Homology.scala` is clean) and
the throwaway driver deleted, per this project's own convention for diagnostic-only files. This is a real,
useful finding on its own -- a previously-undocumented property of an already-shipped, tested class -- even
though it produced no code change.

## What's still open

- Item 2 (chunks): per-dimension chunking is the real next step if this is worth pursuing further -- a bigger
  redesign than originally scoped, needing the full tie-heavy cross-validation battery this class has needed
  twice before. Not started. A decision for the project lead: worth the risk, or leave chunks as-is (it is
  still *correct*, just not gaining from its own local/global split on typical inputs).
  not attempted here.
- Items 3-4 (Cech, cubical) need the batch-then-merge cache restructuring designed, not attempted here.
- No benchmarking was done this session — this is a static-analysis survey, not a measured performance
  pass. Actual speedup from any of the above is not yet quantified; the profiling worklogs establish where
  time currently goes in the reduction engines but not how much of the *construction* phase's wall-clock time
  items 1/3/4/5 would actually shave off. That measurement should happen alongside whichever item is
  implemented first, per this project's own "measure, don't infer" norm.
