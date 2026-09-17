# Session worklog — naive VR stream, the coface-stream iterator bug, the cross-engine benchmark, and two bugs it found

This session started from the 2026-09-16 sessions' own next-step ("a plain Vietoris-Rips simplex stream
generator", see `tda4j-ripser-status` memory) and grew through four distinct, connected pieces of work.
Written because the scope grew well past the original ask (a benchmark) into two confirmed, fixed
correctness bugs — worth a durable record rather than only living in CLAUDE.md's running prose.

## 1. `IncrementalVietorisRipsSimplexStream` — a reference-grade naive VR stream

Requested: "a good naive-ish Vietoris-Rips implementation... a straight up implementation of [Antonio]
Rieser's extension of Zomorodian's algorithm" (arXiv:2301.07191v3, *A New Construction of the
Vietoris-Rips Complex*). Note the deliberate disambiguation: "Rieser" the paper's author, not "Ripser"
the software this codebase also implements — easy to conflate given the codebase's own naming, and
worth keeping straight in any future reference to this class.

Implemented in `SimplexStream.scala` as `IncrementalVietorisRipsSimplexStream`, extending
`EnumeratingCofaceSimplexStream`: `maxDimension`, `maxFiltrationValue` (default `+Infinity`),
`keepCriterion`, `useLargestNeighborBound` (default `true`, a pure optimization — pinned as such by a
regression test that disables it and checks nothing changes). Deliberately not speed-competitive; it's a
solid cross-validation baseline for the other, more experimental VR streams, not a replacement for them.
Tested in `IncrementalVietorisRipsSpec.scala` (10 cases): brute-force cross-validation, agreement with
`EnumeratingCofaceSimplexStream` at `+Infinity`, sortedness/no-repeat invariants, naive-engine barcode
agreement (untruncated and thresholded), `maxDimension = 0`/`1` edge cases, a single-point space,
coincident points.

## 2. The `StratifiedCellStream.iterator` bug, and `flattenToCellStream`'s removal

The existence of `HomologyFixtures.flattenToCellStream` (a test-only workaround) was itself the signal
something was wrong in the production interface, not just the tests — the actual ask was "find the bug
that leads to `.iterator` hanging indefinitely, and fix it so `flattenToCellStream` becomes redundant."

**Root cause, two compounding mechanisms**: `StratifiedCellStream`'s default `.iterator` was
`Iterator.from(0).filter(iterateDimension.isDefinedAt).fold(...)`-shaped — `.filter` on an *infinite*
`Iterator.from(0)` spins forever once past the last defined dimension (nothing downstream ever calls
`.hasNext` far enough to notice there's no more real data, since `.filter` itself has to exhaust the
source looking for the next match that never comes), and `.fold` requires full source exhaustion before
producing anything. Separately, `Int` silently wraps around after ~2^31 iterations (no exception on the
JVM), so a naive `d <= someBound` guard could spuriously become true again for a very negative `d`,
eventually converting a hang into a crash instead.

**Fixed at the source**: replaced with `Iterator.from(0).takeWhile(iterateDimension.isDefinedAt).flatMap(iterateDimension)`
— `takeWhile` correctly *stops* at the first undefined dimension instead of endlessly filtering, and this
depends on a **contiguous-domain contract** now documented directly on `iterateDimension`'s doc comment:
no d-simplex may exist without its (d-1)-dimensional faces, so `isDefinedAt` must be true on a prefix
`[0, n)`, never with gaps. Every previously-unbounded stream's `iterateDimension` catch-all needed an
explicit upper bound to satisfy this (a real bound in each case: a d-simplex needs d+1 distinct vertices,
so bounding at `metricSpace.size` is not arbitrary) — `EnumeratingCofaceSimplexStream`,
`RipserCofaceSimplexStream`, `InorderCofaceSimplexStream`, `LimitedCofaceSimplexStream`,
`RecursiveStackVietorisRipsSimplexStream`. `AlphaShapeDQP`'s own unbounded domain was initially left
alone (private `cellsByDim`, existing specs looping to ambient dimension, a perceived degeneracy-hazard
risk) but fixed in the same session once the benchmark (section 3) became a real caller of `.iterator()`
on it — verified safe because the public `sizeByDimension.length` mirrors `cellsOfDimension`'s own
existing internal bound exactly, confirmed against all 6 existing alpha specs plus a scratch termination
test.

`flattenToCellStream` and its call sites were deleted entirely (`HomologyFixtures.scala`,
`HomologySpec.scala`, `RipserCohomologySpec.scala`) — no longer needed once `.iterator` itself works.
Full mechanism is also in CLAUDE.md directly (`SimplexStream.scala`'s bullet and the dedicated section
after it).

## 3. `EngineComparisonBenchmarkSpec` — and what it found on its first run

The actual ask this session: "a comparative benchmark that checks all combinations of homology engine,
simplex stream construction (both VR and alpha) against each other on a range of dimensions and point
counts." Built as a 2-D sweep (ambient point-cloud dimension × max homology dimension, per the project
lead's explicit choice over a 1-D sweep) across every (stream, engine) pairing this codebase actually
supports: 5 VR streams, 2 alpha backends (`helix`, `DQP`), `SimplicialHomologyContext` and
`PersistenceInChunksContext` as decomposable pairs, plus `RipserCohomologyContext` as its own bundled row
(takes a `FiniteMetricSpace[Int]` directly, not a stream, and can't touch alpha complexes at all). Each
cell runs under a per-cell timeout on a daemon-thread executor, per the project lead's explicit choice
("include with a per-cell timeout") over skipping the known-stall `PersistenceInChunksContext` × alpha
combination outright — no engine here supports cooperative cancellation, so a "timeout" cell leaves a
background thread running; the daemon flag only stops it from blocking JVM exit, it doesn't reclaim the
work. See the spec's own doc comment for the construction/reduction timing-split rationale and why
alpha/VR bar counts are never compared against each other (circumradius vs. diameter — different
quantities).

**First run immediately found a real, previously-unknown reduction bug** — this is the actual value the
benchmark delivered, beyond its stated purpose: at `maxDim >= 2`, `SimplicialHomologyContext` threw
`IllegalStateException: reduction pivot ... was not a recorded open class` for exactly three
constructions (`RecursiveStackVietorisRipsSimplexStream`, `HelixDelaunay`, `AlphaShapeDQP`), while every
other construction — and `PersistenceInChunksContext` on these same three — ran clean (no crash, but see
section 4: "no crash" turned out not to mean "correct").

## 4. The `filtrationOrdering` bug — two layers, both now fixed

**Layer 1, direction (fixed first, in an earlier pass this session)**: all three offending classes
defined `filtrationOrdering` as plain ascending (`Ordering.by(filtrationValue)`, or
`FilteredSimplexOrdering[Int,Double](this)` called without its own `filtrationOrdering` using-parameter,
silently defaulting to ascending `Ordering[Double]`) — never reversed. `CellularHomologyContext`
(`SimplicialHomologyContext`'s underlying machinery) bakes `stream.filtrationOrdering` directly into
`Chain`'s pivot-selection machinery, which requires "smaller under this ordering" to mean "younger," not
"older" — the exact convention `EnumeratingCofaceSimplexStream.filtrationOrdering` already documented
and required, from an earlier session's fix (see CLAUDE.md's "Bug found while cross-validating (4)
against (1)" section). Fixed by reversing the primary key only in all three:
`Ordering.by(filtrationValue).reverse.orElse(tiebreak)`. This alone made the crash go away.

**Layer 2, tie-break consistency (the deeper, previously-hidden bug this session found and fixed)**:
fixing layer 1 alone was necessary but not sufficient. Verification used the *right* oracle from the
start, on advisor's correction: don't compare `SimplicialHomologyContext` ("Naive") against
`PersistenceInChunksContext` ("Chunks") as ground truth for a stream-ordering question, since Chunks'
own correctness on these streams had never been established — use a structural invariant
(`HomologyFixtures.totalBarsAccountForAllCells`: every cell opens or closes exactly one bar) and, for VR
specifically, cross-validate against `EnumeratingCofaceSimplexStream` (already independently trusted),
same engine, same point cloud, only the stream varying.

That check immediately failed for all three streams even after layer 1's fix — `SimplicialHomologyContext`
was producing genuinely wrong barcodes, not just avoiding a crash. Root cause: each stream's own
`iterateDimension` bucket order didn't match `filtrationOrdering.reverse` on cells that tie exactly —
- `RecursiveStackVietorisRipsSimplexStream`'s dim-1 `edges` sorted ascending value + *ascending*
  `simplexOrdering` tie-break; its dim >= 2 case had no sort at all (order came straight from
  `TopCofacetEnumerator`'s `SortedSet[Int]` neighbor traversal — ascending vertex id, unrelated to
  filtration order).
- `HelixDelaunay`'s `simplicesSortedMap` used bare `sortBy(filtrationValue)` — no explicit tie-break at
  all (fell back to `simplicesMap`'s own insertion order among ties).
- `AlphaShapeDQP`'s `byDim(k).sortInPlaceBy` tie-broke on `c.show` — a **string**, not
  `simplexOrdering[Int]`.

This is the *same bug class* `EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream`/
`InorderCofaceSimplexStream` already got fixed for, in an earlier session (CLAUDE.md's "Bug found while
cross-validating" section) — "a stream's `iterateDimension` order and its `filtrationOrdering` must be
the *same* total order, one the consistent `.reverse` of the other" — just not yet applied to these
three. No crash resulted this time (the pivot-table `IllegalStateException` only fires on genuinely
unrecorded pivots, not merely on ties processed in a locally-inconsistent order), so nothing short of an
independent-oracle regression test could have caught it.

**Fixed the same way as the original fix**: every ad hoc/independently-built bucket sort replaced with
`.sorted(using filtrationOrdering.reverse)` — the *same* `Ordering` object, not a separately reconstructed
comparator. `AlphaShapeDQP`'s fix is the one exception in *mechanism*, not intent: `compute()`
(`AlphaComplexDQPBuilder`) runs before an `AlphaShapeDQP` instance — and hence its `filtrationOrdering`
— exists, so it builds an explicit `Ordering.by(weight).orElse(simplexOrdering[Int].reverse)` at that
point, written to match `filtrationOrdering.reverse` exactly rather than calling it directly.

**Verified, not just asserted**: `SimplicialHomologyContext` on the now-fixed
`RecursiveStackVietorisRipsSimplexStream` matches `SimplicialHomologyContext` on
`EnumeratingCofaceSimplexStream` *exactly*, cell-for-cell, on every property-test trial — the strongest
evidence available. No independent oracle stream exists for alpha complexes, so `HelixDelaunay`/
`AlphaShapeDQP` are verified via `totalBarsAccountForAllCells` holding on every trial instead — weaker,
but decisively better than before (that invariant used to fail constantly on these streams).

## 5. A second, unrelated `PersistenceInChunksContext` bug, found in the process — also now fixed

Even after layer 1 + layer 2 above were both fixed and `SimplicialHomologyContext` was fully
cross-validated as correct, `PersistenceInChunksContext` *itself* still disagreed with it — and this
reproduced even on `EnumeratingCofaceSimplexStream`, the well-established, independently-trusted stream,
proving it was never a stream-ordering problem at all. Root-caused by hand-tracing the minimal possible
repro: 4 coincident points bounded at `maxDim=2` — the full 2-skeleton of a tetrahedron, i.e. the
boundary of the 3-simplex, topologically S² — whose correct barcode (H₀ = ℤ, H₂ = ℤ, one essential class
each) is verifiable by hand, not just by trusting another engine.

**Two distinct defects found and fixed in `Homology.scala`'s `PersistenceInChunksContext.HomologyState`**:

1. `compress` (Algorithm 4, clear-and-compress paper) took a *static snapshot* of `Rk.items` before its
   elimination loop started, then mutated `Rk` inside the loop body. Any term newly introduced by a
   substitution — e.g. compressing away one cleared entry can pull in an unrelated *paired* cell that was
   never part of the original snapshot — was silently never itself eliminated. Since a paired cell must
   never become anyone's pivot (its role, killer of some other pivot, is already fixed), this let one
   survive as `Rk`'s final leading term; `recordPair` then added it to `cleared` while it was still in
   `paired`, corrupting the pivot/pairing invariant (confirmed directly: added an assertion that fires
   whenever `recordPair`'s pivot is already `paired` or its sigma is already `cleared` — it fired,
   repeatably, tracing exactly this mechanism).

   Fixed by rewriting `compress` on top of `Chain.reduceByUntil`'s existing fixpoint loop (the same
   primitive `processCell`/`globalReduce` already use) instead of a hand-rolled single pass — its
   `reduceLoop` operates on a `SortedMap` (a properly consolidated, one-entry-per-cell view) and keeps
   popping the current minimum term and substituting until nothing more is reducible, so a term
   introduced mid-reduction is automatically revisited on the next iteration.

2. Fixing (1) alone wasn't sufficient either: `globalReduce`'s *own* separate reduction pass (`Chain.
   reduceByUntil(rSigma, boundaries, ...)`, using only the `boundaries` map — cleared pivots only) could
   *also* expose a newly-introduced paired cell partway through (substituting a cleared cell's own
   recorded boundary can pull in one of *its* non-pivot terms, and that term can itself be a cell already
   used as someone else's killer) — and `globalReduce` had no rule at all for eliminating paired cells,
   only `compress` did, and by then `compress` had already finished running for this cell.

   Fixed by extracting the elimination rule into one shared `eliminationFallback` method (cleared+active
   → substitute killer; cleared+inactive *or* paired+inactive → self-cancel via `Chain(l)` substituted
   against itself, which `reduceLoop`'s own arithmetic cancels to zero with no separate zero-out code
   path needed; paired+active → `None`, "not reducible, stop," since there's no killer to substitute and
   it's legitimately allowed to remain as `Rk`'s final pivot) and passing it as `globalReduce`'s
   `fallback` too, not just `compress`'s.

   Also fixed in passing, defensibly if not proven load-bearing for the specific repro above:
   `markActiveEntries`'s row scan used `takeWhile(_ => !isActive)`, stopping at the *first* row that
   makes the column active and silently leaving every later row in the same chain unclassified in
   `activeRows` (which `compress`/`eliminationFallback` then look up per-row, independently, later).
   Changed to a full `.foreach` scan — every row needs its own classification, not just the column's
   aggregate active/inactive verdict.

**Verified**: the hand-derived S² repro now produces the same barcode via both engines
(`(0,0.0,Infinity)`, `(2,0.0,Infinity)`, three `(1,0.0,0.0)` zero-length bars — matches "connected,
simply connected until the last edge, one 2-dimensional void" by hand). Final full `sbt test` (after
section 6 below): 146 total, 143 passed, 0 failed, 3 skipped, 1 pending, 82s, including
both new regression tests (`VietorisRipsSpec`'s new test, `AlphaFiltrationOrderingRegressionSpec`'s two
sub-tests) and the entire existing suite (`PersistenceInChunksSpec`, `RipserCohomologySpec`,
`HomologySpec`, `AlphaComplexDQPWeightedSpec`, etc.) — this touched shared machinery (`Chain.reduceByUntil`'s
calling convention in two call sites; every stream's own bucket order) with real regression risk, so a
clean full run matters more here than for a narrowly-scoped change. (`HomologySpec.scala`'s
`BarcodeRegressionSpec` stays `skipAll`'d throughout this — see section 6 below for a mistake made and
caught while trying to un-skip it.)

## 6. Hardening after an advisor pass: pin the fixture, verify against ground truth, not just agreement

A review pass flagged two gaps before calling section 5 done, both addressed:

**The hand-derived S² fixture was only ever a throwaway scratch spec, deleted after use.** It was the
single most valuable artifact from root-causing the bug — hand-verifiable ground truth, and the exact case
that isolated the `compress` snapshot defect — but the permanent regression tests that replaced it
(`VietorisRipsSpec`, `AlphaFiltrationOrderingRegressionSpec`) only compare Naive against Chunks on *random*
point clouds, where the degenerate all-tied-values configuration this bug needed is reachable only by
scalacheck shrinking after some other failure. Pinned permanently: `HomologyFixtures
.tetrahedronBoundaryDegenerateCells`/`tetrahedronBoundaryDegenerateExpected` (the same 14-cell, all-values-0
fixture, with the full hand-derived barcode as the expected value, not just a shape check — every bar's
`(birth, death)` is trivially `(0.0, 0.0)` or `(0.0, Infinity)` at this degeneracy, so the exact multiset is
well-defined regardless of which specific cell ties with which), consumed directly by a new
`PersistenceInChunksSpec` case.

**Verification never upgraded from "Chunks agrees with Naive" to "Chunks is correct."** The regression tests
added in section 4/5 only ever check the two engines against each other — which is exactly the check this
bug defeated for a while (both were self-consistent and still wrong, or one was silently corrupting the
other's presumed-trustworthy answer). Added three more `PersistenceInChunksSpec` cases that check
`PersistenceInChunksContext` directly against a hand-derived barcode, no other engine involved: the S²
fixture above, plus two reused from `RipserCohomologySpec`'s already-hand-verified `threePointLine` fixture
(3-cycle graph → exactly one essential H¹, not three; filled triangle → zero essential H¹, one zero-length
bar instead) and `HomologyFixtures.elderRuleExpected` (the filtration-order-vs-lexicographic-order
discriminator). All pass. This matters specifically for this fix because `eliminationFallback`'s paired+
inactive branch now *deletes* content during `globalReduce` that previously survived unmodified into
`recordPair`/`boundaries` — a real behavioral extension of the paper's compression rule beyond where it
was previously applied (`compress` only), so "the two engines still agree" alone doesn't rule out both
being wrong in the same new way.

**A mistake made and caught**: `HomologySpec.scala`'s `BarcodeRegressionSpec` (`skipAll`'d, "currently
stalls out") was suspected stale — plausibly superseded by this session's earlier `StratifiedCellStream
.iterator` fix, given the timing. Un-skipped and run once via `sbt testOnly`: completed in under 20
seconds, all 3 examples passing. **Generalized from that single run to "fixed, keep it un-skipped" and
said so in CLAUDE.md — wrong**, and exactly the "measure, don't infer" trap this project's own memory
warns about (caught only because the project lead independently reran the same test in the IDE and
reported it looked stuck for minutes, prompting a second look). The spec's generator
(`matrixGen(Gen.double, Gen.chooseNum(2, 10), Gen.chooseNum(25, 150))`, no fixed seed) samples a *different*
random point cloud on every run; the first run happened to draw a small, low-dimensional one. A
subsequent `sbt test` run — full suite, different sample — hit `OutOfMemoryError` in this same spec after
2m46s with up to 515% GC time (multiple threads all in GC simultaneously) on the default 1GB heap, and left
that JVM degraded enough to cascade into an unrelated spec's failure later in the same run
(`CofaceSimplexStreamSpec`).

Re-skipped, and the *actual* question — is this a pre-existing scale limitation (as the original skip
already said) or a new compounding-complexity regression from this fix's `compress`/`globalReduce` rewrite —
was answered by measuring, not by re-asserting either way: instrumented `advanceAll` to print `R`'s total
and per-chain-maximum term count after each dimension's global step, on a 40-point, ambient-dimension-4
`AlphaShapeDQP` complex (well inside this spec's own generator range, chosen to be reproducible and fast to
inspect rather than hunting for the exact original failing sample). Per-chain maximum stayed at 8-10 terms
throughout — no pattern consistent with chains compounding across the sweep, which is what a regression in
this fix would look like (`eliminationFallback`'s cleared+active branch hands back `R(killer(l))`'s
*current* contents as a substitution source, and both `compress` and `globalReduce` write `R(k)` back in
place, so a genuine regression would show per-chain size ratcheting upward call over call). Instead: that
same 40-point/dimension-4 input alone produced **102,090 simplices** from `AlphaShapeDQP`'s
always-untruncated construction — the actual bottleneck is sheer complex size, present before this
session's fix and not changed by it. `BarcodeRegressionSpec`'s own generator range (dimension up to 10, up
to 150 points) can produce alpha complexes far larger still, so an OOM somewhere in that range isn't
surprising in hindsight. Left `skipAll`'d — this measurement rules out "this fix made it worse" but doesn't
establish "this is fully understood/bounded," and un-skipping again without first addressing the scale
problem itself (a separate, larger task, not attempted here) would repeat the same mistake.

**Two comments corrected to stop overclaiming a specific mechanism the evidence didn't actually establish**:
the `markActiveEntries` `takeWhile`→`.foreach` change's comment originally asserted it was *the* confirmed
fix for the observed bug; hand-tracing the actual repro showed `activeRows` was unchanged by this specific
change in that case. Reworded to state the real justification: marking more rows active is the
conservative direction to err in (an inactive row gets deleted outright by `eliminationFallback`; an active
row substitutes its full killer column instead), independent of whether it was load-bearing for the one
repro that was hand-traced.

One test-design bug found and fixed along the way, worth naming so it isn't mistaken for a third
production bug: the original `AlphaFiltrationOrderingRegressionSpec` constructed `Alpha(points, dispatch)`
*twice* — once per engine — and `HelixDelaunay`'s filtration-value computation touches a `mutable.Set`
whose iteration order (and hence floating-point summation order) isn't guaranteed identical between two
independent constructions of "the same" complex. This produced two mathematically-equal but bit-different
`Double`s, which `containTheSameElementsAs` (exact equality) then reported as spurious
missing/must-not-contain bars — a real, reproducible flake, but in the test's construction discipline, not
in production code. Fixed by constructing the stream once and sharing it between both engine calls, which
is also simply the more faithful test of the property the spec actually cares about (two engines agreeing
on *one* complex).

## Where things stand now

All three streams flagged by the benchmark (`RecursiveStackVietorisRipsSimplexStream`, `HelixDelaunay`,
`AlphaShapeDQP`) are fixed at both the direction and tie-break layers, and `PersistenceInChunksContext`
itself is fixed independent of any specific stream. CLAUDE.md's "Cross-engine benchmark, and a bug it
found on first run" section has been rewritten to match this final state (was stale — described only the
layer-1 fix as "not fixed as of this writing"). The `tda4j-ripser-status` memory file needs the same
update (still describes this as an open, single-layer, unfixed bug) — do that before starting further
work from a fresh session, not as part of this worklog.
