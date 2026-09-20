# Autonomous multi-task session (2026-09-19, evening)

Given a standing mandate: work through three deferred items in order (#1 → #2 → #3, moving on only once out of
things to do on the current one), use `advisor()` check-ins liberally instead of stalling for user feedback,
commit after each numbered task, keep this worklog updated as I go (not just at the end).

1. Root-cause the 3D cubical naive-engine scaling problem (per-cell cost grows with `n` in 3D, flat in 2D,
   documented but never explained; sharpened by the same-day finding that the chunks engine does NOT have this
   problem on the same inputs).
2. Build a real, validated raw-UnionFind fast path for dimension 0/1 and wire it into the actual production
   engines (`CellularHomologyContext`/`PersistenceInChunksContext`), not just the correctness-only
   `SimplicialHomologyByDimensionContext`.
3. Simplicial set quotients/attaching maps.

This file is updated incrementally as each task progresses, not just at the end.

---

## Task #1: 3D cubical scaling root cause

### Hypothesis and methodology

The prior session's benchmark found 3D naive-engine per-cell cost growing with `n` (190 -> 340 -> 850 us/cell,
n=8 to 32) while the chunks engine on the SAME `CubicalGridStream` stayed flat (123 -> 90 -> 114 us/cell) —
meaning whatever the cause was, it was invisible to chunks but not to naive, which rules out a bug in the
stream's own cell enumeration or a fixed per-cell cost shared identically by both consumers.

Built `CubicalProfileDriver.scala` (`src/test/scala/.../homology/`, a new, kept driver mirroring
`SingleEngineProfileDriver`'s single-JVM-process convention, avoiding the sbt-hosted benchmark harness's own
documented timeout/daemon-thread contamination risk) to time three phases separately, each on a FRESH stream
instance:

1. **phase1**: `stream.iterator.size` — `CubicalGridStream.iterateDimension`'s own per-dimension sort
   (dominated by `containingTopCells` during each bucket's own sort).
2. **phase2**: `persistentHomology(stream2)` — `HomologyState`'s construction, which triggers its SECOND,
   GLOBAL sort (`processingOrder`, across all dimensions combined — see `Homology.scala`'s own comment on why
   this exists, a separate concern from phase1's per-dimension sort).
3. **phase3**: `state.diagramAt(Double.PositiveInfinity)` — the actual `advanceAll` reduction loop.

### What the measurement found

Before any fix, dims=3:

| n  | cells   | phase1 us/cell | phase2 us/cell | phase3 us/cell | total us/cell |
|----|---------|----------------|-----------------|-----------------|----------------|
| 8  | 4913    | 64.169         | 36.283          | 231.366         | 331.818        |
| 16 | 35937   | 18.630         | 16.070          | 438.595         | 473.295        |
| 24 | 117649  | 16.178         | 18.344          | 678.497         | 713.019        |

phase1/phase2 are FLAT or shrinking with `n` — ruling out both of the stream's own up-front sorting passes.
ALL of the previously-documented growth lives in phase3 (the actual reduction) alone: a real ~3x factor over
this range.

An initial JFR allocation/execution-sample pass (before phase separation) was mildly misleading on its own:
leaf frames prominently featured `CubicalGridStream.cartesianProduct`/`containingTopCells`-adjacent costs, which
are real but are a small, ambient-dimension-bound CONSTANT per call (max `2^3 = 8` for 3D), not something that
scales with `n` by itself. The phase-separated timing is what actually isolated WHERE the n-dependent growth
lived (phase3 specifically), not the profile alone.

### Root cause

`CubicalGridStream.filtrationValue` (`CubicalStream.scala`) was a completely UNCACHED `PartialFunction`.
`CellularHomologyContext.HomologyState` (and, checked separately, `CellularPersistenceInChunksContext.
HomologyState` too) bakes `stream.filtrationOrdering` directly into `Chain`'s `SortedMap`/`PriorityQueue`
pivot-selection machinery — every chain-arithmetic comparison during REDUCTION consults this ordering, which
calls `filtrationValue`, not just once per cell during the stream's own up-front sorts. Every single one of
those comparisons recomputed `containingTopCells(c)` (O(2^(ambientDim - dim(c))) per call) completely from
scratch.

In 2D, that per-call constant is small enough (max `2^2 = 4`) to stay hidden in ordinary noise. In 3D (max
`2^3 = 8`) it was large enough that, multiplied by however many comparisons a reduction of growing size
actually performs, it looked exactly like `n`-dependent growth in the earlier session's timing table — even
though `containingTopCells` itself has no dependence on `n` at all.

This explains the earlier "chunks doesn't have this problem" finding too, but not in the way originally
sharpened: it isn't that chunks' algorithm is structurally immune to this cost. Chunks pays the SAME uncached
`filtrationValue` cost per comparison — it's just that chunks' own baseline per-cell cost (multiple chunked
passes, `compress`/`globalReduce`) was apparently large enough already that the uncached cost's growth was
harder to see as a distinct trend in the previous session's noise floor, not that it was absent. (Confirmed
below: the fix speeds up BOTH engines substantially, not just naive.)

### Fix

A per-instance `mutable.HashMap[Cube, Double]` memoization cache on `filtrationValue`:

```scala
private val filtrationValueCache = mutable.HashMap.empty[Cube, Double]

override val filtrationValue: PartialFunction[Cube, Double] = new PartialFunction[Cube, Double]:
  def isDefinedAt(c: Cube): Boolean = inGrid(c)
  def apply(c: Cube): Double =
    filtrationValueCache.getOrElseUpdate(c, containingTopCells(c).map(topCellValue).min)
```

**Memory-frugality check, done explicitly rather than assumed** (this codebase has an established precedent,
`RipserCohomologyContext`'s deliberately opt-in `memoizeFiltrationValue`, defaulting to `false` specifically
because a global cache runs against Ripser's own memory-frugality design goal on potentially-huge VR
complexes): that precedent does NOT apply here, on two independently-checked grounds.

1. `CellularHomologyContext.HomologyState.CellIterator` is `stream.iterator.toVector.sorted(using
   processingOrder).iterator.buffered` — the ENTIRE stream is already eagerly materialized into one in-memory
   `Vector` before any reduction starts. A cache bounded by that same already-resident cell count adds nothing
   new.
2. Checked directly in `Homology.scala` (not assumed by analogy) that `CellularPersistenceInChunksContext.
   HomologyState.allCells` does the exact same thing: `0.to(internalMaxDim).iterator.flatMap(d => stream.
   iterateDimension.applyOrElse(d, ...)).toVector` — also a full eager materialization up front. So the
   "no new memory-frugality concern" argument holds for the chunks engine too, not just naive — this was flagged
   as a specific gap to check by `advisor()` (see below) and confirmed rather than left as an unverified
   extrapolation from the naive-engine case.

Separately checked: no caller in this codebase (`CubicalImage.scala`'s two `CubicalGridStream(...)` call sites,
every test) holds a stream across multiple `persistentHomology`/`diagramAt` runs, or constructs one without ever
consuming it. This wouldn't matter even if one did, though — the cache's ceiling is the stream's own fixed,
finite `totalCellCount`, independent of caller behavior, unlike VR's genuinely unbounded-in-practice complex
sizes.

### Measured after the fix

Phase-separated (3D, same driver):

| n  | cells   | phase1 us/cell | phase2 us/cell | phase3 us/cell | total us/cell |
|----|---------|----------------|-----------------|-----------------|----------------|
| 8  | 4913    | 18.763         | 20.627          | 60.988          | 100.379        |
| 16 | 35937   | 9.099          | 5.341           | 67.980          | 82.420         |
| 24 | 117649  | 5.392          | 4.525           | 91.408          | 101.325        |

Phase3's own per-cell cost is now 61 -> 68 -> 91 us/cell — much flatter than before. The previous ~3x factor
over this range is now ~1.5x, which is consistent with ordinary `O(log N)` reduction-accumulator behavior
(`Chain.reduceLoop`'s own `TreeMap`, growing chain sizes as reduction proceeds), not eliminated to perfectly
flat, but no longer a distinct pathology on top of that. Total time at n=24 dropped from 83886.0ms to 11920.8ms
(~7x faster) on the synthetic driver.

Confirmed on the REAL, established benchmark (`CubicalBenchmarkSpec.scala`, not just the custom driver), since
both engines share the same fixed `CubicalGridStream`:

- **2D** (`-DminN=8 -DmaxN=256 -DstepMultiplier=2`): naive dropped from ~75-150us/cell to a roughly flat
  21-45us/cell; chunks dropped from ~42-66us/cell to ~12-23us/cell. Full sweep time dropped from ~70s to 16.7s.
- **3D** (`-Ddims=3 -DminN=8 -DmaxN=32 -DstepMultiplier=2 -DtimeoutSeconds=180`): naive dropped from the
  previously-documented 284/349/891 us/cell (n=8/16/32) to **83.953/47.837/109.137 us/cell** — an 8x wall-clock
  improvement at n=32 (244777.9ms -> 29971.6ms), and the old clear growth trend is gone (aside from ordinary
  run-to-run noise); chunks dropped from 123/90/114 to **25.269/18.113/23.299 us/cell**.

Full `sbt test`: 249 examples, 244 passed, 0 failed, 5 skipped, 1 pending — bit-for-bit identical to the
pre-fix baseline, confirming this is a pure performance change with no behavior difference.

### Advisor check-in

Consulted before closing out the task. Verdict: fix is sound, measurement is clean, commit it. Two concrete
items came back and were both acted on before committing:

1. **Verify the memory-frugality argument holds for the chunks engine too, not just naive** (the doc comment
   as first written only argued it for `CellularHomologyContext`). Checked directly (see above) —
   `CellularPersistenceInChunksContext.HomologyState.allCells` also eagerly materializes everything up front,
   so the argument holds for both; the doc comment references both classes now.
2. **State the residual phase3 growth honestly** — "much flatter, with a residual ~1.5x over the same range
   that the previous measurement's 3x was hiding," not "the growth is gone." Applied verbatim in both this
   worklog and the `CLAUDE.md` update.

Also flagged: this fix changes the earlier same-day "naive vs. chunks is a *structural*, not constant-factor,
3D difference" finding (`eef2dc1`) — that framing is now wrong, since the growth was a shared stream-level bug,
not an algorithmic property of either engine. `CLAUDE.md`'s cubical section was revised in place (not merely
appended to) to reflect this, per the advisor's explicit instruction not to leave two contradictory accounts of
the same measurement in the file.

### Decisions

- **`CubicalProfileDriver.scala` kept permanently**, alongside `CubicalBenchmarkSpec.scala`, mirroring
  `SingleEngineProfileDriver`'s established precedent (a real, reusable single-JVM profiling tool, not
  throwaway scratch) — it may be useful again if cubical performance work continues (e.g. task #2's UnionFind
  fast path, or a future CubicalRipser-style engine).
- **No further investigation of the residual ~1.5x phase3 growth** — it's consistent with ordinary
  `Chain.reduceLoop` accumulator behavior already characterized elsewhere in this codebase
  (`.claude/WORKLOG-ripser-profiling.md`'s `Chain.reduceLoop` sections), not a new, unexplained pathology
  worth a dedicated investigation of its own.
- Task #1 is DONE.

---

## Task #2: raw-UnionFind fast path for dimension 0/1 -- scoped out after measurement, replaced with a cheaper fix that answers the same question

### Starting point

`WORKLOG-mst-and-perf.md`'s own "Decision" section (an earlier session) explicitly deferred porting
`SimplicialHomologyByDimensionContext`'s validated elder-rule logic into `CellularHomologyContext` as a raw
`UnionFind`-based fast path, for two stated reasons: the benefit looked small for the `maxDim >= 2` case actually
measured back then (dimension-0/1 is a small fraction of a complex once triangles exist), and the risk of
introducing a new bug into the REFERENCE ORACLE every other engine is cross-validated against was real (that
session found five distinct bugs getting the `Chain.reduceBy`-based version right in an isolated class with no
other consumers). It explicitly named the scenario where the benefit WOULD be real -- "large point cloud, low
maxDim (0 or 1), where dimension-0/1 IS most or all of the complex" -- and said this needs its own targeted
benchmark before deciding, not an assumption either way.

### Measurement

Built `VRLowDimProfileDriver.scala` (kept, mirroring `CubicalProfileDriver`'s phase-separated convention) to
target exactly that scenario: `SimplicialHomologyContext` on a large (n=2000-20000), SPARSE (threshold scaled as
`2.5/sqrt(n)`, same convention as `SparseRipsBenchmarkSpec`) Vietoris-Rips complex capped at `maxDim=1`.
Phase-separated timing confirmed phase3 (the actual reduction) dominates (~85-90% of total wall-clock), with a
mildly-growing but not pathological per-cell cost (~106-130 us/cell across n=2000-20000).

Deep-stack JFR profiling (`jfr print --stack-depth 30`, the SAME depth-30 correction this codebase's own Ripser
profiling arc already learned it needed -- a shallower `--stack-depth 12` pass first mis-attributed a large
chunk of cost to "other," which turned out on the deeper pass to be `Chain.collapseAll`/`filtrationValue`
machinery hidden a few frames further up than 12 could reach) found: **~57% of execution samples (whole-process,
dominated by phase3) attributed to `filtrationValue`/`filtrationOrdering`**, ~22% to coface enumeration (mostly
phase1/phase2, not phase3), ~6.3% to `Chain.reduceLoop`/`reduceBy` machinery, ~3.6% to `Chain`/`PriorityQueue`
construction, ~0.3% to `Simplex.boundary` construction, ~11% genuinely unattributed (mostly `Chain.collapseAll`/
`HashMap` bookkeeping intrinsic to the general reduction algorithm itself).

### Advisor check-in and the pivot away from raw UnionFind

Two check-ins here. The first, before any code, sanity-checked the measurement plan itself and caught a real
methodology risk before it was spent: task #1's own fix had JUST changed the baseline this benchmark would be
measuring against (both the cubical fix and the earlier same-day Ripser-profiling arc had already removed
similar comparator costs elsewhere), so the measurement had to characterize the CURRENT cost, not infer from a
stale mental model. It also corrected an over-broad initial design instinct (a fully generic `CellT: OrderedCell`
fast path) with a concrete counterexample: `FiniteSimplicialSet`'s `torus`/`minimalSphere(1)` fixtures have
1-generators with BOTH faces equal (self-loops), whose boundary is algebraically ZERO over a field (`d(e) = v -
v = 0`), not two terms -- a fast path keyed on "the two endpoints" would silently mishandle exactly the fixture
family built to be adversarial. Scoped to `Simplex[VertexT]` only, per that correction.

The second check-in, after the measurement above came back, is the one that actually changed the plan: **while
attempting to design the raw UnionFind implementation, reconstructing exactly which coefficients/V-column
corrections a multi-hop `boundaries` substitution needs (to stay bit-for-bit algebraically valid for a LATER
dimension-2+ cell's reduction, which reads `boundaries`/`generators`/`negativeVCols` for dimension-1 pivots) took
many turns of careful derivation without ever fully settling the general-coefficient-field case** -- a
real, concrete instance of exactly the "five bugs last time, in an isolated class with no other consumers" risk
`WORKLOG-mst-and-perf.md` already flagged, except this time the target was the reference oracle directly. Before
writing any of that code, tried a MUCH cheaper alternative first: manually wrapping
`EnumeratingCofaceSimplexStream`'s default `filtrationValue` (`FiniteMetricSpace.MaximumDistanceFiltrationValue`)
in a `mutable.HashMap` cache, entirely in the profiling driver, no production code touched yet, to see how much
of the 57% that alone would close.

**Result: this stream's default `filtrationValue` was uncached, exactly the same bug class task #1 fixed for
`CubicalGridStream`, apparently never given the equivalent treatment.** Memoizing it alone (measured
before/after, `forceUncached` toggle in the driver):

| n     | cells   | phase3 us/cell (uncached) | phase3 us/cell (memoized) | reduction |
|-------|---------|---------------------------|-----------------------------|-----------|
| 5000  | 52431   | 123.029                   | 68.114                      | 44.6%     |
| 10000 | 105591  | 123.150                   | 77.061                      | 37.4%     |
| 20000 | 212839  | 129.175                   | 80.742                      | 37.5%     |

Brought to advisor as an explicit reconciliation ("I found X [uncached filtrationValue explains most of the
measured cost], you suggested Y [raw UnionFind], which constraint breaks the tie?"), per the standing instruction
to surface exactly this kind of conflict rather than silently picking a side. Verdict: **do the memoization fix,
do NOT build the raw UnionFind path.** The residual cost after memoization (`Chain.reduceLoop`'s accumulator,
`Chain`/`PriorityQueue` construction -- a combined ~10% of the ORIGINAL whole-process measurement, i.e. small
once the dominant cost is gone) is exactly the SAME cost `.claude/WORKLOG-ripser-profiling.md` already
characterized and deliberately deferred to its own dedicated redesign -- not a dimension-0/1-specific
opportunity at all, so a raw UnionFind port would buy little beyond what's already a known, separately-owned
future task, at real risk to the reference oracle for a derivation that hadn't converged cleanly. This is the
measured answer to item #2's actual question, not a failure to complete it.

### Fix shipped

`EnumeratingCofaceSimplexStream.filtrationValue` (`SimplexStream.scala`): the default `MaximumDistanceFiltrationValue`
fallback is now wrapped in a per-instance `mutable.HashMap[Simplex[Int], Double]` cache, ON by default -- scoped
to ONLY the default fallback, not a caller-supplied `filtrationValueOverride` (e.g. `CechFiltration` already
caches internally; double-wrapping would be pure waste). `RipserCofaceSimplexStream`/`InorderCofaceSimplexStream`
both extend `EnumeratingCofaceSimplexStream` and inherit this unchanged (confirmed neither overrides
`filtrationValue` itself). `RecursiveStackVietorisRipsSimplexStream` has its own independent `filtrationValue`
and was deliberately left alone, matching this codebase's existing precedent of excluding it from this stream
family's shared changes.

**Why this doesn't contradict `RipserCohomologyContext`'s own `memoizeFiltrationValue = false` default** (an
explicit project-lead call, documented in CLAUDE.md): that decision protects a stream `RipserCohomologyContext`/
`PackedRipserCohomologyContext` never fully materialize by design (genuinely large VR complexes), where
`insertionDiameter` gives an O(d) incremental alternative that makes NOT caching viable in the first place.
Neither condition holds for `EnumeratingCofaceSimplexStream`'s actual consumers: `CellularHomologyContext.
HomologyState.CellIterator` and `CellularPersistenceInChunksContext.HomologyState.allCells` BOTH already eagerly
materialize every cell into a `Vector` before any reduction starts (checked directly, same check task #1 did for
the cubical engines), and there's no incremental alternative to the general max-pairwise-distance functional this
stream computes by default.

**Verified**: full `sbt test` clean; a new ScalaCheck property in `SimplexStreamSpec.scala`
(`CofaceSimplexStreamSpec`, "Memoizing EnumeratingCofaceSimplexStream's default filtrationValue changes nothing
about the computed barcode") cross-checks the FULL bar list (dim, birth, death), not just counts, between the
now-default memoized stream and an explicitly-forced-uncached one, on random point clouds -- mirroring
`RipserCohomologySpec`'s own memoization-toggle property test.

### Decision

- Raw UnionFind for dimension 0/1 is NOT built. `SimplicialHomologyByDimensionContext` remains the only place
  that logic exists, unchanged, still not wired into the production engines -- this is unchanged from
  `WORKLOG-mst-and-perf.md`'s own prior state, now with an actual measurement behind why the port isn't worth its
  risk (not just an assumption).
- The residual `Chain.reduceLoop`/`PriorityQueue` cost (~10% of the original whole-process measurement) is a
  known, already-owned future task (`.claude/WORKLOG-ripser-profiling.md`'s own "what's still open" section),
  not something this session re-scoped or re-flagged as dimension-0/1-specific.
- Task #2 is DONE, closed via measurement + a cheaper fix that answers the same underlying question the raw
  UnionFind proposal was trying to answer (make dimension-0/1-heavy, large VR complexes faster on the naive
  engine), not via the originally-named mechanism.

---

## Task #3: simplicial set quotients/attaching maps

### Starting point

CLAUDE.md's "Simplicial sets" section named this as the deliberately-deferred next construction after
`product`/`coproduct`: "identifying generators, or generators across a coproduct, under a gluing relation
respecting face compatibility." Orientation pass before any design: read `SSetElement.scala` (the
`insertOuter`/`faceOf` operator algebra), `SimplicialSet.scala` (`FiniteSimplicialSet`, `validate()`),
`SimplicialSetConstructions.scala` (`product`/`coproduct`, for the established style/conventions), and
`SimplicialSetFixtures.scala` (existing hand-built fixtures, including the F2-vs-F3-discriminating
`realProjectiveSpace(2)`).

### Advisor check-in #1: the design, before any code

Proposed a two-layer split: a primitive `quotient[G](sset, quotientMap: G => G)` (generator-to-generator only,
reusing `validate()` unchanged for correctness-checking) plus an ergonomic `identify[G](sset, pairs: Seq[(G,G)])`
using a small union-find LOCAL to `cells` (not `streams.UnionFind` -- backwards package dependency). Proposed
validation fixtures: a bigon (two edges glued into a circle) and Hatcher's single-2-simplex RP² Δ-complex model,
cross-validated against the existing `realProjectiveSpace(2)` fixture.

**Two corrections came back, both load-bearing, before any code was written:**

1. **The `G => G` design cannot express Hatcher's own RP² model.** Working through it by hand (which the advisor
   asked for explicitly, rather than trusting `validate()` to catch a wrong-but-consistent construction): in
   Hatcher's model, a filled triangle's three edges do NOT all glue pairwise -- two of them (`d_0(F)`, `d_2(F)`)
   glue into one loop, but the THIRD (`d_1(F)`) has no peer and instead collapses entirely to a DEGENERATE point
   over the vertex (`d_1(E_2) = s_0(E_0)` in `realProjectiveSpace`'s own already-existing face data). A
   generator-to-generator map can only ever produce another non-degenerate generator as a face's target -- it has
   no way to express "this cell crushes down a dimension." `identify`'s pairs-of-generators interface is
   fundamentally the wrong shape for that third edge.
2. **`validate()` is a necessary precondition, not a sufficient correctness check.** It verifies the simplicial
   identities hold on the RESULT, not that the quotient computed is the INTENDED one -- an over-eager
   `quotientMap` can satisfy `d_i d_j = d_{j-1} d_i` perfectly while still describing the wrong space. The actual
   correctness evidence has to be an independent homology cross-check.

### Resolution: generalize `quotientMap` to `G => SSetElement[G]`, not restrict the fixture

Rather than the fallback the advisor offered (ship a restricted `quotient` and pick a fixture that avoids the
degenerate-collapse case), worked through Hatcher's RP² construction by hand against the more general signature
`quotientMap: G => SSetElement[G]` -- letting a generator collapse either to a genuine surviving representative
(`SSetElement(Nil, rep)`, a fixed point) or to an already-established degenerate element (`SSetElement(word,
rep)`). Composing a face's own (possibly already-degenerate) word with its remapped target's own word needed no
new algebra: `word.foldRight(mapped.word)(insertOuter)` is exactly `insertOuter` applied one step at a time,
right-to-left, which composes `s_word(s_mappedWord(rep))` correctly using machinery that already existed.

Derived the RP² gluing by hand BEFORE writing any code and checked it converges to `realProjectiveSpace(2)`'s
own already-existing face data structurally, not just its homology: with `V0,V1,V2 -> V0`, `E12,E01 -> E12`
(the loop), `E02 -> s_0(V0)` (the degenerate collapse), and `F -> F`, the derived `facesOf(F)` came out as
`[SSetElement(Nil,E12), SSetElement([0],V0), SSetElement(Nil,E12)]` -- literally the same shape as
`realProjectiveSpace`'s own `[outer, SSetElement(List(0),E(0)), outer]`, under the correspondence
`E12<->E(1)`, `V0<->E(0)`, `F<->E(2)`. This hand convergence, done before any code ran, is stronger evidence of
correctness than the green test that followed it.

### Implementation

`quotient[G: Ordering](sset, quotientMap: G => SSetElement[G])` and `identify[G: Ordering](sset, pairs:
Seq[(G,G)])` added to `SimplicialSetConstructions.scala`, after `coproduct`. `identify` computes its
generator-to-generator `quotientMap` via a small, local (not `streams.UnionFind`) union-find over the transitive
closure of `pairs`, `Ordering[G]`-minimum per component as the canonical representative, and delegates to
`quotient`.

New fixtures added to `SimplicialSetFixtures.scala`: `edge` (a single non-degenerate edge, two distinct
endpoints) and `triangle` (a plain filled 2-simplex, three vertices/edges/one face) as raw material, plus
`rp2QuotientMap`/`realProjectiveSpaceViaQuotient` (Hatcher's gluing applied to `triangle` via `quotient`) --
shared between `SimplicialSetConstructionsSpec` (structural checks) and `SimplicialSetHomologySpec` (the
cross-validation against the independently-hand-built `realProjectiveSpace(2)`), specifically so both specs
exercise literally the same gluing rather than two copies that could silently drift apart.

### Advisor check-in #2: after implementation, before declaring done

Verdict: ship it, the `G => SSetElement[G]` generalization was the right call (better than the suggested
restricted-fallback), and the hand-derivation converging to `realProjectiveSpace(2)`'s exact face data is
stronger evidence than the passing test alone. One real gap flagged: `quotient` never checked that
`quotientMap(g).generator` is ITSELF a fixed point -- a caller passing a chained map (`quotientMap(a) =
SSetElement(Nil,b)`, `quotientMap(b) = SSetElement(Nil,c)`, `b` never a fixed point) would silently produce a
quotient whose faces target `b`, which isn't in `generatorsByDim` -- and `validate()`'s own structural check only
inspects `faces(g)` for surviving `g`, so this would slip through silently whenever no surviving cell's face
happens to point at the broken link directly. `identify` is immune by construction (`find` always
path-compresses to a genuine root), so this was a `quotient`-only exposure. Fixed with an explicit `require` in
`quotient` checking `isFixedPoint(quotientMap(g).generator)` for every generator, before computing `facesOf`.
Also flagged and fixed: the RP² quotient map had been written out twice (once per spec file) -- moved to
`SimplicialSetFixtures.realProjectiveSpaceViaQuotient` so both specs share one definition.

### Validation

`SimplicialSetHomologySpec.scala` (homology cross-checks, via `CellularHomologyContext`):
- **Bigon** (`identify(coproduct(edge, edge), [(Left(V0),Right(V0)), (Left(V1),Right(V1))])`): `validate()`
  empty, generator counts `(2, 2)`, `H_0 = H_1 = F` -- hand-verifiable directly (both edges end up sharing the
  identical boundary `V1 - V0`, so the boundary map has rank 1, not 2).
- **RP² via quotient**: `validate()` empty, generator counts `(1, 1, 1)`, cross-validated against
  `realProjectiveSpace(2)` over BOTH F2 and F3 -- `H_1 = H_2 = F2` over F2, both `0` over F3 (the same
  sign-discriminating pair `realProjectiveSpace(2)` was originally built to catch), confirming the general
  `quotient` primitive is correct, not merely internally consistent.

`SimplicialSetConstructionsSpec.scala` (structural checks, mirroring how `product`/`coproduct` are covered
there): the same two fixtures' `validate()`/generator-count checks, plus two negative tests -- a
dimension-inconsistent `quotientMap` is caught by `validate()` as a structural error (isolated to one broken
generator, everything else left as identity, so the test isolates exactly the one failure mode), and `identify`
throws `IllegalArgumentException` on a pair of different-dimension generators.

No `forAll`/ScalaCheck properties were added for this task -- every fixture here is a small, fixed-size hand-built
complex (matching `product`/`coproduct`'s own precedent), so the oversized-property-test lesson from earlier in
this session (see the `sbt test` runtime incident, below) doesn't apply to this task's own additions.

Full `sbt test` after `scalafmtAll`: clean, no new failures (see this file's own final entry below for the exact
count).

### Decision

- Task #3 is DONE. `quotient`/`identify` are in `SimplicialSetConstructions.scala`; CLAUDE.md's "Deliberately
  deferred, still not attempted" sentence in the Simplicial sets section is updated to reflect the shipped state.
- The bar construction / classifying spaces (named in the same deferred sentence as leaning on `product` and
  quotients once quotients exist) remains NOT attempted -- out of scope for this task, a genuinely separate,
  larger construction.
- This closes the three-item mandate for this session (#1, #2, #3 all done, in order, each committed
  separately).

---

## Aside: an `sbt test` runtime incident during task #2, worth recording

Mid-task-#2, a full `sbt test` run that should finish in under 5 minutes (per this codebase's own established
baseline) instead ran past 17 minutes, with real `OutOfMemoryError`s inside sbt's own 1GB-heap JVM cascading into
unrelated, otherwise-passing specs (`RipserCohomologySpec`, `AlphaComplexDQPWeightedSpec`) later in the same run
-- the user asked directly why. Root cause, found and fixed immediately: a new ScalaCheck property just added to
`SimplexStreamSpec.scala` (the memoization-toggle test for task #2's own `filtrationValue` fix) built the
COMPLETE, untruncated Vietoris-Rips complex (`maxFiltrationValue = +Infinity`, no dimension cap) for point clouds
up to 15 points -- up to 2^15-1 simplices through the slow naive engine, TWICE per trial, across ~100 default
ScalaCheck trials. Fixed by capping `maxDim = 2` (via `LimitedCofaceSimplexStream`) and reducing the point-cloud
generator range to `Gen.chooseNum(6, 12)`, exactly matching `RipserCohomologySpec`'s own equivalent property's
established convention. Verified in isolation first, then via a full clean re-run (`RipserCohomologySpec`'s
previously-OOM'd cases came back fully green), confirming the OOM cascade was this session's own oversized test,
not a pre-existing or environmental problem. This is the direct precedent behind task #3's own "no `forAll` on
unbounded-size complexes" discipline above.
