# Worklog: does restricting to homological dimension 2-4 fix the "everything is slow, point clouds stay tiny" complaint?

Session date: 2026-09-16. Direct follow-up to WORKLOG-mst-and-perf.md's Part 2 ("100 points in 3D is slow") and
Part 4 (`maxFiltrationValue` defaulting to `metricSpace.minimumEnclosingRadius`). The project lead asked
specifically: is the slowness a product of trying to go all the way to the highest dimension and longest distance
everywhere, and would restricting to a realistic homological dimension (2-4) change the picture?

## Method

New spec: `DimensionCeilingBenchmarkSpec.scala`. Kept deliberately small/cheap under plain `sbt test` (matching
`SparseRipsBenchmarkSpec`/`ApparentPairsBenchmarkSpec`'s convention, not `EngineComparisonBenchmarkSpec`'s
permanent `skipAll`), with a global wall-clock deadline so a misbehaving cell can't repeat the 15-minute sbt-lock
incident CLAUDE.md already documents. A real sweep is run via explicit `-D` overrides through `testOnly`.

For each of two engines (`RipserCohomologyContext` -- the production engine with genuine incremental sparse
enumeration -- and `EnumeratingCofaceSimplexStream` x `SimplicialHomologyContext`, i.e. "VR-Enum+Naive" -- the raw
stream + reference reduction algorithm, included specifically because WORKLOG-mst-and-perf.md Part 2/Part 4
documented that bounding distance does NOT reduce its O(C(n,d+1)) enumeration cost, only its reduction cost), for
each homological dimension of interest `H in {2,3,4}` (built as `maxDimension = H+1` simplices -- see
`DimensionCeilingBenchmarkSpec`'s own class doc for why: computing H_k correctly, not as a truncation artifact,
needs (k+1)-simplices present, confirmed by reading `RipserCohomologyContext.sparseCofacets`/`coboundaryOf`
directly, not inferred), and for three threshold regimes:

- `unbounded`: `maxFiltrationValue = +Infinity` -- the complete flag complex.
- `default`: `maxFiltrationValue` left at the codebase's current default, `metricSpace.minimumEnclosingRadius` --
  roughly CONSTANT (~0.7 in a unit cube) as `n` grows, a correctness cap (excludes only genuinely cone-redundant
  long edges), not a scaling lever.
- `sparse`: `maxFiltrationValue = 2.5/sqrt(n)` (same convention as `SparseRipsBenchmarkSpec`) -- genuinely SHRINKS
  with `n`, keeping expected local neighborhood size roughly constant.

...point cloud size `n` was grown geometrically (factor 1.5, starting at 20) until an attempt exceeded a 30-second
per-attempt timeout, crashed, or hit a 1200-point cap, reporting the largest `n` completed within 10s and within
30s alongside `totalSimplexCount`/bar count. Single trial per step (exploratory ceiling-finding, not a precise
timing comparison) -- ambient dimension fixed at 3, matching the point cloud shape (100 points, 3D) in the
original complaint.

Run: `sbt -J-Xmx4g -DnStart=20 -DnCap=1200 -DgrowthFactor=1.5 -DmaxSteps=14 -DambientDim=3 -DfastBudgetMs=10000
-DceilingTimeoutMs=30000 -DdeadlineSeconds=600 "testOnly ...DimensionCeilingBenchmarkSpec"` -- standalone, never
under plain `sbt test`, per the prior OOM-cascade caution. Completed in 9m45s, within the 600s deadline (all 18
cells reached; none skipped).

## Results: Ripser (the production engine)

| H | regime | ceiling <=10s | ceiling <=30s | cells at ceiling |
|---|---|---|---|---|
| 2 | unbounded | n=45 (4.4s) | n=68 (27.9s) | 866,847 |
| 2 | default | n=45 (9.0s) | n=68 (27.1s) | 188,569 |
| 2 | sparse | n=153 (2.1s) | n=345 (19.5s) | 1,297 |
| 3 | unbounded | n=20 (4.0s) | n=30 (27.3s) | 174,436 |
| 3 | default | n=30 (5.6s) | n=30 (5.6s) | 28,129 |
| 3 | sparse | n=153 (2.0s) | n=153* | 801 |
| 4 | unbounded | n=20 (9.1s) | n=20 (9.1s) | 60,459 |
| 4 | default | n=30 (6.8s) | n=30 (6.8s) | 25,704 |
| 4 | sparse | n=153 (0.6s) | n=153* | 959 |

`*` = growth stopped by a crash at the next step (n~230), not a timeout -- see "New bugs found" below; the true
30s ceiling for H=3/H=4 sparse is unmeasured, not "153."

## Results: VR-Enum+Naive (raw stream + reference reduction)

| H | regime | ceiling <=30s |
|---|---|---|
| 2 | unbounded | n=30 (18.9s) |
| 2 | default | n=30 (3.0s) |
| 2 | sparse | n=68 (6.8s) |
| 3, 4 | all three regimes | **0** -- crashes on the very first attempt (n=20) |

## Answer to the actual question

**Yes, dimension is a huge lever, and distance bounding is an even bigger one -- but only if the bound genuinely
shrinks with `n`.** Going from H=2 to H=4 fully unbounded collapses Ripser's 30s ceiling from n=68 down to n=20
(and n=20 already costs 9.1s there) -- confirms "don't build to the highest dimension unboundedly." But the
`default` (enclosing-radius) threshold, despite being this codebase's current default everywhere, is NOT the fix
for scale: it reduces cell count by a real, measured ~4.6x at H=2 (866,847 -> 188,569 cells) but both regimes
landed on the *same* n=68 growth step, both within a hair of the 30s cap (27.9s vs 27.1s) -- the growth grid here
(factor 1.5) is too coarse to say whether the achievable ceiling itself moved; only the cost-at-a-given-n moved,
by a confirmed multiplicative constant. This matches CLAUDE.md's own framing of the enclosing-radius default as "a
correctness cap, not a scaling lever," not a new finding, but now with a measured cell-count number attached.

The `sparse` threshold (shrinks with `n`) is categorically different: at H=2, it reaches n=345 within 30s -- 5x
the point-cloud size of `unbounded`/`default` at the same H -- with cell count staying nearly FLAT (450 to 1,297
cells across n=20 to 345), because a shrinking radius keeps the neighborhood local by construction. The pattern
holds, noisily, across dimension too: at H=3 and H=4 sparse, cell counts stay in the same few-hundred-to-low-
thousands range across the whole successfully-measured n range (20-153), regardless of build dimension -- a tight
local neighborhood rarely contains enough co-located points to form high-dimensional cliques at all, so once
distance is genuinely bounded, dimension stops being the dominant cost driver (exact runtimes at matched `n`
across H aren't directly comparable here since each cell drew an independent random cloud -- a seeding choice in
the benchmark, not fixed for this run -- but the *magnitude* pattern, roughly-constant complex size regardless of
H once locality is enforced, is real and is the most useful single fact from this sweep).

**Practical takeaway for the project lead**: restricting `maxDim`/homological dimension of interest alone gives
real but modest headroom (roughly 2-3x point-cloud size per dimension dropped, fully unbounded). Restricting to a
threshold that shrinks with `n` -- not the current `minimumEnclosingRadius` default, which stays roughly constant
-- is the lever that actually changes the scaling regime, and it compounds with a lower dimension cap. If the
domain genuinely needs long-range topology (the enclosing-radius default's whole justification), there isn't a
free lunch: dimension 3-4 on more than a few dozen unbounded points is inherently combinatorially expensive
(`C(n, d+1)` growth), independent of any implementation bug.

## New bugs found (neither chased to a fix this session -- both confirmed real, both localized)

### 1. `SimplicialHomologyContext` crashes unconditionally past build-dimension 3 -- CONFIRMED, root cause not
   isolated further than "engine-specific, not general combinatorics"

On `EnumeratingCofaceSimplexStream` input, `SimplicialHomologyContext.HomologyState.advanceOne` throws
`IllegalStateException: reduction pivot ... was not a recorded open class -- reduction invariant violated`
(`Homology.scala:116-118`) as soon as any dimension-4 simplex (5 vertices) is built -- i.e. `homDim >= 3` in this
sweep's terms. Reproduced independently of this benchmark: 0 of 18*, all three threshold regimes (unbounded,
default, sparse alike), and via a standalone diagnostic on a fixed point cloud (n=15, ambientDim=3) confirming:

- **Genuinely dimension-triggered, not a seed/threshold artifact**: `buildDim=2` and `buildDim=3` on the identical
  cloud succeed (470 and 1471 bars respectively); `buildDim=4` on the SAME cloud fails with the same pivot,
  `TreeSet(5, 8, 9, 11)`, every time.
- **Not a general combinatorics bug**: `RipserCohomologyContext`, an independently implemented and separately
  cross-validated engine, computes the identical complex at the identical dimension successfully (see the H=4
  unbounded row above -- n=20 ran fine on Ripser). So the fault is in `SimplicialHomologyContext`'s reduction
  bookkeeping or its interaction with `EnumeratingCofaceSimplexStream`, not in `Simplex.boundary`/`SimplexIndexing`
  machinery shared by both (`Simplex.scala`'s `boundary` extension was read directly and is dimension-general, no
  hardcoded bound).
- **Not specific to the `LimitedCofaceSimplexStream` dimension-cap wrapper**: swapped for a hand-rolled wrapper
  matching `EngineComparisonBenchmarkSpec`'s own `bounded` helper exactly -- identical crash, identical pivot.

**Practical impact**: this codebase's reference/oracle engine (the one every other engine gets cross-validated
against, per its own class doc) cannot compute homology in dimension 3 or above on a Vietoris-Rips stream AT ALL
right now, for any input, regardless of size or threshold. This was never caught before because
`EngineComparisonBenchmarkSpec`'s own defaults (`maxMaxDim=2`) never built a 4-dimensional simplex, and no other
existing spec happens to push `SimplicialHomologyContext` + `EnumeratingCofaceSimplexStream` that high either.

**Not investigated further this session**: the actual reduction-algorithm root cause (why a valid pivot goes
missing specifically once 5-term boundaries exist) needs its own dedicated pass, not a same-session extension of a
benchmarking sweep -- flagged here, left for the project lead to prioritize.

### 2. `RipserCohomologyContext` crashes in the `sparse` threshold regime at high build dimension -- FIXED, see
   WORKLOG-simplexindexing-overflow.md

**Update, later session**: root-caused and fixed. `SimplexIndexing`'s combinatorial indices were `Int`-typed and
`binomial`'s `Int`-returning implementation silently truncated (via `BigInt.intValue`) once `C(n,k)` exceeded
`Int.MaxValue` -- confirmed at exactly `n=230` (this repro), `k>=5`. Fixed by migrating combinatorial indices to
`Long` throughout `SimplexIndexing`/`RipserCohomologyContext` (matching `ripser.cpp`'s own `int64_t` convention),
plus a second, related bug the fix exposed: `SimplexIndexing`'s `binomialTable` was eagerly computing a full
`(vertexCount+1) x (vertexCount+1)` grid regardless of which entries were ever needed, now lazily memoized. Full
account, including why `zeroPivotCofacet`'s own reasoning wasn't the actual bug, in
WORKLOG-simplexindexing-overflow.md. The section below is kept as originally written (the localization, before the
fix), not retroactively rewritten.

At H=3/H=4 with the `sparse` threshold, growth stopped at n~230 with `ArrayIndexOutOfBoundsException`. Chased per
advisor's specific instruction (don't attribute without a stack trace -- the sparse threshold at n=230 is
`2.5/sqrt(230) ~ 0.165` in a unit cube at ambient dimension 3, a near-degenerate near-empty-complex regime, so the
failure needed to be distinguished from "this is just what happens when almost nobody has neighbors" before
blaming the engine). Isolated with a standalone diagnostic, same cloud/threshold, both build dimensions:

```
threshold=0.16484511834894675, minimumEnclosingRadius=0.797..., neighbor counts: min=0 max=11 mean=3.50
buildDim=2: OK, cells=881, bars=494
buildDim=4: CRASH java.lang.ArrayIndexOutOfBoundsException: Index 231 out of bounds for length 230
    at EuclideanMetricSpace.distance(FiniteMetricSpace.scala:131)
    at RipserCohomologyContext.insertionDiameter$$anonfun$1(Homology.scala:753)
    ... (via Iterator.max)
    at RipserCohomologyContext.insertionDiameter(Homology.scala:753)
    at RipserCohomologyContext.zeroPivotCofacet$$anonfun$1(Homology.scala:840)
```

**Confirmed NOT the degenerate-threshold regime alone**: `buildDim=2` on the exact same 230-point cloud and the
exact same threshold succeeds cleanly (881 cells, 494 bars) -- only `buildDim=4` fails, so this is genuinely
dimension-triggered, the same pattern as bug 1 above, just in a different engine and a different failure mode.

**Localized, not fully root-caused**: the crash is inside `zeroPivotCofacet` (`Homology.scala:832-844`), which by
its own doc comment deliberately enumerates cofacets via `si.cofacetIterator`'s "full (unrestricted)
enumeration" -- explicitly NOT threshold-guarded there, on the documented reasoning that any tied cofacet found is
automatically within the filtration threshold too (a value argument). That reasoning says nothing about whether
`si.cofacetIterator`'s combinatorial-index decode always produces a vertex id within `[0, metricSpace.size)` --
and here it produced 231 for a 230-point space (`SimplexIndexing(metricSpace.size)` was built with size=230), a
plain off-by-something in the combinatorial enumeration itself, not a threshold/value bug. Given `buildDim=2`
never triggers it, this presumably needs BOTH a high enough combinatorial index (more candidates to enumerate,
which scales with `C(n, d+1)`, i.e. needs both largish `n` and higher `d`) to reach whatever boundary case in
`SimplexIndexing`'s decode is broken -- not investigated further than this localization.

**Practical impact**: the one lever this worklog identifies as actually changing the scaling regime (a threshold
that shrinks with `n`) is currently unusable at build dimension >= 4 once `n` grows past roughly 150-230 in this
setup -- it fails outright rather than degrading gracefully. Below that combination (lower `n`, or `default`/
`unbounded` regimes, or build dimension <= 3) it's unaffected -- all measurements in the results tables above other
than the two `sparse`-marked `*` cells are unaffected by this bug.

## What was NOT done this session

- Neither bug was fixed. Both are real, reproducible, and now localized to a specific method/file; deciding
  whether and when to fix them is the project lead's call, not folded into this benchmarking session.
- The `sparse` regime's true 30s ceiling at H=3/H=4 is unmeasured (growth was cut short by the crash, not a
  timeout) -- would need bug 2 fixed, or the sweep re-run with a floor under the threshold to avoid the failing
  region, to actually measure it.
- `RecursiveStackVietorisRipsSimplexStream` and alpha-complex constructions were excluded from this sweep (already
  documented elsewhere as not speed-competitive / a different section of the library, per CLAUDE.md).
- No fixed-cloud-across-dimension re-run was done for the main sweep itself (the `rngFor` tag includes `buildDim`,
  so each (engine, H, regime) cell draws an independent random cloud) -- flagged by the advisor as a real, not
  fixed, confound; exact cross-H timing comparisons at "the same n" should be read as noisy, though the
  cell-count-stays-flat pattern in the sparse regime is a large enough effect to survive that noise.

Not committed -- per standing project convention, the project lead commits their own work.
