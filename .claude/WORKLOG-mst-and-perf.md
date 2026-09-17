# Worklog: MST-based dimension 0/1 persistence, and the "100 points is slow" investigation

Session date: 2026-09-16. Triggered by two related complaints from the project lead: (1) `sbt test` reporting
`SimplicialHomologyByDimensionContext`-related failures suggesting `object Kruskal cannot be found` might mean
Kruskal's algorithm had been removed from the codebase, and (2) concern that 100 points in 3 dimensions "should
not be a problem" for persistent homology, unlike the actual measured behavior.

## Part 1: "object Kruskal cannot be found" — not reproduced

`Kruskal`/`UnionFind` (`UnionFind.scala`) were present, compiled cleanly, and `UnionFindSpec` passed on a fresh
`sbt -batch "Test/compile"` / `testOnly` run at the start of this session. The class was never actually removed.
The most likely explanation, not independently confirmed: CLAUDE.md's cross-engine-benchmark section already
records a same-session `OutOfMemoryError` (515% GC time on a 1GB heap) that "left the JVM degraded enough to
cascade into an unrelated spec's failure later in the same `sbt test` run" — a stale/degraded JVM from a prior
OOM is a classic way to get a spurious class-resolution error that has nothing to do with the class actually
being gone. Worth keeping in mind if it recurs: check for a preceding OOM in the same run before assuming code
was deleted.

## Part 2: why 100 points in 3D is slow — two separate, both real causes

Measured directly (`EngineComparisonBenchmarkSpec` at `n=100, ambientDim=3, maxDim=2`, both with the spec's
default 1GB heap/25s timeout and with `-J-Xmx6g`/30s):

- **Every raw VR construction (`VR-Enumerating`, `VR-RipserCoface`, `VR-Inorder`, `VR-RecStack`, `VR-NewVR`) times
  out, on BOTH the Naive and Chunks engines, and — critically — so does the built-in `RipserCohomologyContext`
  row**, the one engine with sparse-Rips support. Only the two alpha-complex rows finish (Alpha-DQP/Alpha-Helix,
  ~1790-1794 cells, ~17-24s total).
- **Cause 1, combinatorial, not a bug**: none of `EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream`/
  `InorderCofaceSimplexStream`/`RecursiveStackVietorisRipsSimplexStream` (4 of the benchmark's 5 VR constructions)
  expose ANY distance-threshold parameter — only `IncrementalVietorisRipsSimplexStream.maxFiltrationValue` and
  `RipserCohomologyContext.maxFiltrationValue` do, and the benchmark (like typical direct usage) doesn't set
  either. `EuclideanMetricSpace` has no threshold either. So a "100-point, maxDim=2" run means the COMPLETE flag
  complex: C(100,1)+C(100,2)+C(100,3) = 166,750 simplices, growing to ~4.09M at maxDim=3. This is a missing
  capability for 4 of the 7 constructions, not a usage mistake the benchmark could have avoided — flagged as the
  concrete next piece of work.
- **Cause 2, a real measured inefficiency, found via `jstack` sampling `EngineComparisonBenchmarkSpec`'s worker
  thread mid-run** (5 samples, all in the construction phase): 4 of 5 landed inside
  `EnumeratingCofaceSimplexStream.filtrationOrdering.compare`, called from `TimSort` during
  `RipserCofaceSimplexStream.iterateDimension`'s bucket sort — specifically inside
  `MaximumDistanceFiltrationValue.apply` (O(d²) pairwise distance scan with `SortedSet`/`List` allocation per
  call) and `SimplexIndexing.apply` (sorts a list per call). Every one of the ~7 `.sorted(using
  filtrationOrdering.reverse)` call sites across `SimplexStream.scala`/`VietorisRips.scala` recomputed both from
  scratch on EVERY comparator invocation during `TimSort` — O(m log m) expensive recomputations per dimension
  bucket instead of O(m). This compounds the combinatorial cost from Cause 1 rather than being independent of
  it. Confirmed as a real, separate cost (not just "more cells is more work") because the profiler landed
  overwhelmingly in the comparator's own internals, not in `Chain`/reduction code, during the *construction*
  phase specifically.

**Fix applied for Cause 2**: `sortedByFiltration` (`SimplexStream.scala`, on `EnumeratingCofaceSimplexStream`,
inherited by `RipserCofaceSimplexStream`/`InorderCofaceSimplexStream`/`IncrementalVietorisRipsSimplexStream`) —
a decorate-sort-undecorate helper that memoizes `filtrationValue`/`simplexIndexing` per cell for the duration of
one sort call, scoped locally (not stored on the stream instance, so it stays bounded to one dimension's bucket
and never grows across the stream's lifetime — deliberately NOT a repeat of
`RipserCohomologyContext.memoizeFiltrationValue`'s stream-lifetime cache, which the project lead declined to
default on for memory-frugality reasons that apply here too). Delegates to the exact same `compare` logic
(now `lazy val tieBreak` there too, a one-line independent fix), so it cannot silently diverge from
`filtrationOrdering`'s own semantics — same total order, just computed once per element instead of once per
comparison. Replaced every `.sorted(using filtrationOrdering.reverse)` call site in
`EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream`/`InorderCofaceSimplexStream`/
`IncrementalVietorisRipsSimplexStream`. Deliberately did NOT touch `RecursiveStackVietorisRipsSimplexStream`
(`VietorisRips.scala`, explicitly documented as "not a speed-competitive engine") or `AlphaShapes.scala`'s
analogous sort — left as follow-up, not silently skipped.

**What this does NOT fix**: Cause 1. `filtrationOrdering.compare` still needs to run at least once per
comparison-worthy pair, and a genuinely untruncated 100-point/maxDim=2 flag complex is still 166,750 simplices
regardless of comparator speed. Full re-benchmark after this fix was not re-run to completion this session
(would need the same multi-minute timeout budget as Part 2's own measurement runs) — the honest claim is "this
removes a measured, real multiplier on top of Cause 1," not "this makes 100-point/maxDim=2 fast."

## Part 3: the MST/Kruskal approach — `SimplicialHomologyByDimensionContext` had FIVE bugs, not the two CLAUDE.md documented

CLAUDE.md going into this session already documented two known, isolated bugs in this class (never fixed,
"evidently never run end-to-end"): an unguarded `barcode(0)`/`barcode(currentDim)` map read (`NoSuchElementException`
on any complex with an MST edge), and a missing `given Ordering[Simplex[VertexT]] = stream.filtrationOrdering`.
Both are real and are the first two fixes below. Fixing them was NOT sufficient to make the class produce correct
output — three more bugs surfaced only once the class could actually run to completion, each caught by a
regression suite it never had before (`SimplicialHomologyByDimensionSpec`, new this session):

1. **(Previously documented) Unguarded `barcode` map reads** — `barcode(0) = barcode(0).appended(...)` and
   `barcode(currentDim) = barcode(currentDim).appended(...)` threw immediately since `barcode` starts as
   `mutable.Map.empty`. Fixed with the same `.getOrElse(dim, immutable.Queue.empty)` guard
   `PersistenceInChunksContext.recordPair` already uses.
2. **(Previously documented) Missing filtration-ordering given** — `chainRM` was summoned before any
   `Ordering[Simplex[VertexT]]` was in scope, silently falling back to the generic lexicographic
   `Simplex is OrderedCell` ordering instead of filtration order (the same bug class `CellularHomologyContext`'s
   own class doc describes at length). Fixed by adding `given Ordering[Simplex[VertexT]] =
   stream.filtrationOrdering` before `chainRM` is summoned.
3. **(New) `mstIterator`'s naive dying-vertex selection, found immediately after fixing (1)/(2)** —
   `kruskal.mstIterator.foreach` took `Chain.from(edge.boundary).leadingCell.get` (the edge's own two raw
   endpoints) as "the vertex that dies," without ever reducing against `boundaries`. This is wrong whenever a
   cascade happens: if vertex `v` was already killed by an earlier tree edge in the same pass, and a later tree
   edge's two endpoints happen to include `v` again, the code tried to read `cycles(v)` — already removed —
   throwing `NoSuchElementException`. Root cause: `Kruskal`'s own `mstIterator`/`cyclesIterator` compute the
   ENTIRE union-find result eagerly at construction time (in `lrList`'s `partitionMap`), which discards which
   vertex was the open root AT THE TIME each specific edge was applied — exactly the information the elder rule
   needs, and un-recoverable after the fact once every edge has already been unioned. Fixed by abandoning
   `Kruskal`/`UnionFind` for this integration entirely (per the advisor's steer, see below) and replacing both
   the `mstIterator` and `cyclesIterator` loops with ONE pass over `stream.iterateDimension(1)` (already in
   filtration order, per the stream contract — no separate sort needed), reducing every edge through the exact
   same `Chain.reduceBy` primitive `advanceOne` uses for every other dimension. This makes the dimension-0/1 fast
   path provably the same computation as the general algorithm applied one dimension early, not a hand-rolled
   shortcut that could silently diverge from it — and also fixed a second, smaller inconsistency this
   unification exposed: the old `mstIterator` branch recorded raw `dEdge` into `coboundaries`, while the
   `cyclesIterator` branch recorded the properly-folded `coboundary` — now both branches use the folded value
   uniformly.
4. **(New) `advanceTo`'s loop condition never actually entered for the very first call** — `currentIterator`
   starts as `Iterator.empty.buffered` (see `persistentHomology`'s factory args), and `advanceTo`'s `while`
   condition gated on `currentIterator.hasNext` — false from the start, so the loop body (which calls
   `advanceOne`, whose ELSE branch is what actually advances to the next dimension and refills
   `currentIterator`) never ran at all. Compounded by a second issue: `current` was explicitly reset to
   `Double.PositiveInfinity` right after the dimension-0/1 setup block, and `advanceTo`'s default `f` parameter
   is ALSO `Double.PositiveInfinity` — `f > current` was false from the very first call too, for any caller
   using the default. Net effect: no dimension ≥ 2 cell was EVER processed by this class before this session,
   for any input, regardless of the other four bugs. Fixed by dropping the `currentIterator.hasNext` conjunct
   from the loop condition (`currentDim <= dim` alone is the correct, still-terminating bound) and initializing
   `current = stream.smallest` (matching the constructor's own pre-setup default) instead of `+Infinity`.
5. **(New) Bars above dimension 0 were recorded one dimension too high** — `advanceOne`'s finite-bar recording
   used `barcode(currentDim) = ...`, i.e. the dimension of `sigma` (the cell doing the killing), not the
   dimension of the class actually being closed (`cycleBasis.leadingCell`'s own dimension, one lower — exactly
   what `CellularHomologyContext` records via `pivot.dim`, not `sigma.dim`). A 1-cycle killed by a triangle was
   filed under dimension 2. Fixed by computing `barDim = cycleBasis.leadingCell.map(_.dim).getOrElse(currentDim
   - 1)` and keying `barcode` by that instead.

**A sixth, false alarm, worth recording because it looked identical to a real bug**: the first cross-validation
run against the fully-degenerate `HomologyFixtures.tetrahedronBoundaryDegenerateCells` fixture (every cell tied
at filtration value 0.0) disagreed with the hand-derived expected barcode on exactly 2 of 8 bars (`(1,
-Infinity, 0.0)` instead of `(1, 0.0, 0.0)`), even after all five fixes above. Root cause turned out to be in the
NEW TEST HELPER (`SimplicialHomologyByDimensionSpec.stratifiedStream`), not the engine: it built each
dimension's `iterateDimension` bucket from the fixture's own listed order (via `groupBy`) rather than explicitly
sorting by `filtrationOrdering.reverse`. For every OTHER fixture (all with genuinely distinct filtration values)
the fixture author's natural "list cells in increasing fv order" happens to already coincide with
`filtrationOrdering.reverse`'s order, masking the gap — the fully-tied fixture is the only one where iteration
order is driven entirely by tie-break direction, and the fixture's ascending-lex listing order doesn't match
`FilteredSimplexOrdering.reverse`'s tie-break direction. This is the exact bug class CLAUDE.md's "Bug found while
cross-validating" section already documents for several production streams (iteration order and
`filtrationOrdering` must be the SAME total order) — it just hadn't been applied to this one-off test stream
yet. Fixed by sorting each bucket with `.sorted(using filtrationOrdering.reverse)` in the helper. After this fix,
all three specs in `SimplicialHomologyByDimensionSpec` pass clean: the 5 hand-verified fixtures (including the
degenerate one), the bars-account-for-cells structural invariant, and 100 ScalaCheck trials cross-validating
against `SimplicialHomologyContext` on random Vietoris-Rips point clouds.

### Advisor guidance that shaped this (two calls, one reconciliation)

First call (before starting the fix): rejected an initial plan to fix `SimplicialHomologyByDimensionContext` in
isolation as "not what the user asked for" (they asked for MST added TO the engines they run, not a fourth,
unused engine) — but this framing turned out to be based on incomplete information. Second call, after tracing
the exact Kruskal/elder-rule correspondence (tree edge = `reduced != 0`, cycle edge = reduces to zero, younger
root dies) and finding the logic was already debugged once before (commit `841bc83`, "Fixed bugs with the
generation of representative cochains for degree 1 homology from Kruskal's algorithm"): reconciled to "do both,
in order" — fix the isolated class first (cheap, zero risk to the reference oracle `CellularHomologyContext`),
cross-validate it against `SimplicialHomologyContext` as an executable proof the logic is right, and ONLY THEN
consider porting it into `CellularHomologyContext` as an actual engine fast path, with an explicit stop
condition: "if step 2 shows disagreement, stop before step 3 and report — don't port logic you've just shown to
be wrong."

### Decision: fixed and validated, but NOT ported into `CellularHomologyContext` this session

Step 2 (cross-validation) succeeded cleanly — see above. Step 3 (porting into the reference oracle) was
deliberately NOT done this session, on a cost/benefit read made explicit here rather than silently dropped:

- **Benefit is small for the complaint that motivated this work.** Dimension-0/1 cells are a small fraction of a
  VR complex once `maxDim >= 2` — for the 100-point/maxDim=2 case in Part 2, that's ~4,950 dimension-1 cells out
  of ~166,750 total. Even a large constant-factor speedup on dimension-0/1 processing specifically would not
  move the needle on that complaint, which is dominated by dimension-2 volume (Cause 1) and comparator overhead
  across ALL dimensions (Cause 2, already fixed above, and NOT specific to dimension 0/1).
- **Risk is real and not symmetric.** `CellularHomologyContext` is the reference oracle every other engine in
  this codebase is cross-validated against (see its own class doc). This session found FIVE distinct bugs while
  getting the MST logic right in an isolated class with no other consumers — a mistake ported into the shared
  oracle would be far more costly to detect and could masquerade as a regression in something else entirely.
- **The actual case where this WOULD pay off — large point clouds at low `maxDim` (0 or 1), where dimension-0/1
  IS most or all of the complex — is a different scenario from what was measured this session**, and deserves
  its own targeted benchmark before deciding it's worth the oracle risk.

`SimplicialHomologyByDimensionContext` is left as a correct, validated, but not (yet) benchmarked or
production-wired fourth engine. Porting its now-executable logic into `CellularHomologyContext` as a genuine
speedup (using raw `UnionFind` rather than `Chain.reduceBy`, to actually avoid the `filtrationOrdering`
comparator overhead for dimension 0/1 — the `Chain.reduceBy`-based version validated here is provably correct
but not obviously faster than general reduction, since it still pays the same comparator cost) is the natural
next step, but needs its own dedicated validation pass, not a same-session extension of this one.

## Regression coverage (Parts 1-3)

New: `SimplicialHomologyByDimensionSpec.scala` (this class's first-ever test coverage). Re-ran after all changes
in this worklog: `HomologySpec`, `PersistenceInChunksSpec`, `RipserCohomologySpec`, `SimplexStreamSpec`,
`SimplexIndexingSpec`, `RipserStreamSpec`, `VietorisRipsSpec`, `CofaceSimplexStreamSpec`,
`IncrementalVietorisRipsSpec`, `UnionFindSpec` — the same regression set CLAUDE.md's own ordering-bug fixes used,
since this session touched the same shared `filtrationOrdering`/bucket-sort surface.

## Part 4 (same session, later): `maxFiltrationValue` defaults to `metricSpace.minimumEnclosingRadius`

Direct follow-up to Part 2's finding that 4 of 7 VR constructions had no distance-threshold mechanism at all. The
project lead explicitly requested this: past the enclosing radius, every vertex is within range of some common
apex, so the complex is a cone and contributes no further homology (Ripser paper, p. 412) — `ripser.cpp` already
uses this exact quantity (`enclosing_radius = min_i max_j dist(i,j)`, identical to this codebase's
`FiniteMetricSpace.minimumEnclosingRadius`) as its own default threshold.

**Implementation obstacle, not anticipated**: the natural expression --
`class Foo(metricSpace: FiniteMetricSpace[Int], maxFiltrationValue: Double = metricSpace.minimumEnclosingRadius)`
-- does not compile under this project's `-source:future` setting. Confirmed directly with a minimal isolated
case (`class ScratchA(val x: Int, val y: Int = x + 1)` fails with "Not found: x"): Scala 3 only allows a default
value to reference an *earlier parameter list*, not an earlier parameter within the same list. Splitting into a
curried list (`(metricSpace: ...)(maxFiltrationValue: Double = metricSpace.minimumEnclosingRadius)`) does compile,
but breaks every existing call site, not just ones setting the threshold: Scala requires an explicit trailing `()`
at the call site once a second parameter list exists, even one where every parameter has a default (confirmed:
`RipserCohomologyContext[Double](metricSpace, maxDim)` alone fails to compile against a curried signaure with
"missing argument list"). Settled on a `Double.NaN` sentinel instead, resolved internally
(`if maxFiltrationValue.isNaN then metricSpace.minimumEnclosingRadius else maxFiltrationValue`) -- preserves every
existing call site's syntax untouched, both positional and named.

**A first implementation attempt was flagged by the assistant itself as possibly unsafe, and the concern turned
out to be wrong -- worth recording since it nearly caused a revert.** The specific worry: at a caller-chosen
bounded `maxDim` (the normal case -- nobody computes full-dimensional homology on a 100-point cloud), the
"nothing changes past the enclosing radius" argument implicitly assumes enough dimensions exist for the cone's
own filling simplices to be present; a concrete counterexample was found immediately in the existing test suite
(`threePointLine`: 3 colinear points at distances 1, 2, 3, so `minimumEnclosingRadius = 2.0` from the middle
point, less than the longest edge at 3.0 -- at `maxDim = 1`, thresholding at 2.0 drops that edge and with it the
test's expected essential H¹ bar entirely). An advisor consult corrected this: `ripser.cpp` runs in exactly this
combination routinely (`enclosing_radius` as the default threshold, `dim_max` routinely 1 or 2), and the dropped
bar is a fact about the *truncated graph's own combinatorics* at that bounded `maxDim`, not a topological
correctness failure -- in the full complex that same cycle is filled by the triangle at the same value 3.0, a
zero-length bar, exactly consistent with the cone theorem. The existing tests that broke were pinning the *old*
default (unthresholded), not asserting something that had to remain true; each needed either
`maxFiltrationValue = Double.PositiveInfinity` added explicitly (to keep testing what it originally tested) or
its "untruncated" comparison baseline updated to match (`EnumeratingCofaceSimplexStream`, used as an oracle
throughout the test suite, is thresholded now too, by the same default).

**The load-bearing check, run before treating this as done, not after**: is the default-thresholded barcode
*exactly* the untruncated barcode restricted to `[0, minimumEnclosingRadius]` -- i.e. does the new default behave
like any other explicit threshold value, subject to the same `restrictToThreshold` oracle every other threshold
already has to satisfy? Added as a new property test in both `RipserCohomologySpec` and
`IncrementalVietorisRipsSpec` (200 random Vietoris-Rips point clouds each) -- passes cleanly. This is what
actually justifies calling the default safe, not the theorem argument on its own.

**Scope of the change**: `EnumeratingCofaceSimplexStream` (previously had no threshold mechanism at all -- new
`maxFiltrationValue` parameter, folded into a combined `keptByThresholdAndCriterion` predicate alongside the
existing `keepCriterion` rather than a second independent filter, per advisor's specific steer), its two direct
subclasses `RipserCofaceSimplexStream`/`InorderCofaceSimplexStream` (threaded the same parameter through, applied
at every place each class's own bespoke coface-generation logic produces a candidate), `RipserCohomologyContext`
and `IncrementalVietorisRipsSimplexStream` (already had the parameter for genuine sparse-Rips truncation -- only
the default changed). Deliberately NOT touched: `RecursiveStackVietorisRipsSimplexStream` (`VietorisRips.scala`
-- independent coface logic, explicitly documented as a cross-validation baseline rather than a speed-competitive
engine, needs its own pass) and anything alpha-complex-related (a standing, explicit instruction that alpha and
Ripser reproduction are different sections of the library with minimal interaction -- also, `minimumEnclosingRadius`
in Euclidean-distance terms isn't obviously the right quantity for a circumradius-based filtration anyway).

**Test fallout, all fixed, not silently absorbed**: roughly 10 existing tests across `RipserCohomologySpec`,
`IncrementalVietorisRipsSpec`, `PersistenceInChunksSpec`, and `SimplexStreamSpec` had pinned specific barcodes or
simplex counts computed at the old always-unbounded default -- each was either given `maxFiltrationValue =
Double.PositiveInfinity` explicitly (where the test's actual purpose needed genuinely untruncated input, e.g. the
clearing-correctness regression tests) or updated to compare two consistently-thresholded sides instead of one
thresholded and one not. Full regression run after all fixes: 75 examples across `HomologySpec`,
`PersistenceInChunksSpec`, `RipserCohomologySpec`, `SimplexStreamSpec`, `SimplexIndexingSpec`, `RipserStreamSpec`,
`VietorisRipsSpec`, `CofaceSimplexStreamSpec`, `IncrementalVietorisRipsSpec`, `SimplicialHomologyByDimensionSpec`,
`UnionFindSpec`, `APISpec` -- 0 failures, 1 pending (the same pre-existing, unrelated pending case from Parts
1-3). `scalafmtCheck`/`scalafmtSbtCheck` clean.

**Measured effect on the exact "100 points, maxDim=2" scenario from Part 2** (`EngineComparisonBenchmarkSpec`,
`n=100, ambientDim=3, maxDim=2`, `-J-Xmx6g -DtimeoutSeconds=30`, same command as Part 2's own measurement):

| construction | engine | before | after |
|---|---|---|---|
| VR-Enumerating | Naive | timeout | 18.4s (54,492 cells, was 166,750) |
| VR-Enumerating | Chunks | timeout | 14.0s |
| VR-RipserCoface | Naive/Chunks | timeout | 17.4s / 15.0s |
| VR-Inorder | Naive/Chunks | timeout | 14.9s / 11.5s |
| VR-NewVR (`IncrementalVietorisRipsSimplexStream`) | Naive/Chunks | timeout | 27.3s / 17.0s |
| VR-RecStack (`RecursiveStackVietorisRipsSimplexStream`) | both | timeout | **still timeout** -- expected, this class was deliberately not touched (see above) |
| VR (built-in) `RipserCohomologyContext` | -- | timeout | **1.34s** |

Complex size dropped from 166,750 to 54,492 cells (a real ~3x reduction, not the whole story -- see below).
`RipserCohomologyContext` is the standout: from timing out at 30s+ to 1.34s, because it already had genuine
incremental sparse-aware enumeration (`sparseCofacets`/`insertionDiameter`, Part 4 of a much earlier session) that
simply had nothing to bite on before this session (default threshold was always `+Infinity`) -- now that the
default threshold is finite, that machinery is doing real, effective work for the first time in normal usage.
The `Naive`/`Chunks` engines on the `EnumeratingCofaceSimplexStream` family improved from "never finishes" to
"11-27 seconds," a genuine and worthwhile win, but nowhere near as dramatic -- consistent with Part 2's earlier
finding that post-hoc filtering (`keptByThresholdAndCriterion`, applied AFTER `simplexIndexing(ix, d+1)` already
constructed the candidate) reduces what reaches the (dominant-cost) reduction step but not the O(C(n,d+1))
enumeration cost itself. `VR-RecStack` timing out exactly as before is the predicted, not surprising, outcome of
leaving `RecursiveStackVietorisRipsSimplexStream` out of this pass.
