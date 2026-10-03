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

## Update (2026-09-26): fractal-r SortedSet finished, corrected substitutionCount reading, a second fix to
## `MaximumDistanceFiltrationValue`, and a serious open question about packed on this case

**`substitutionCount` correction**: it only counts the LAZY FALLBACK firing (a later column's reduction hitting
an apparent pair's `tau` as a missing pivot), not how many apparent pairs were found overall. A low count is the
EXPECTED good case (most apparent pairs are never looked up again), not evidence of a low apparent-pairs hit
rate — an earlier reading in this file's live discussion got this backwards; corrected here, not silently fixed
in place (worklog convention).

**SortedSet on `fractal-r` finished**: `totalSimplexCount=19,224,829` (bars: `0->512, 1->122324, 2->18979158` —
the dimension-2 count alone is close to `C(512,3)≈22.5M`, i.e. this is a near-complete-on-triangles complex at
its default `minimumEnclosingRadius` threshold, genuinely ~7.7x bigger than `o3_1024`'s complex). Timing:
`1,020,414.1ms` (17.0 minutes) — a real **5.2x improvement** over the historical 88.8-minute number from the
very first compute-server table, confirming this session's shared fixes (`7b17442` in particular, since it's not
Euclidean-specific and applies to every `cohomologyOrdering` comparison regardless of metric space) helped this
case too, even though `fractal-r` never touches `EuclideanMetricSpace` at all.

**Fix 5 (`0657029`): `MaximumDistanceFiltrationValue.apply`, second pass.** A JFR profile of the SortedSet run
above (taken right after fix 4 landed) showed `apply` itself still ~14% of CPU, and `VectorBuilder.<init>`/
`Vector$.from`/`.result` at ~43% of ALL allocation bytes — fix 4's `.toIndexedSeq` snapshot (chosen to avoid
needing a `ClassTag[VertexT]`) builds a full immutable `Vector`, real overhead for what's almost always a 2-4
element collection (a simplex has only `dim+1` vertices). Replaced with a nested `SortedSet.iteratorFrom` walk
(outer iterator, inner `iteratorFrom(v)` skipping `v` itself) — same O(d²) comparison count, no backing
collection at all, still no `ClassTag` needed. Full `sbt test` clean both before and after the merge described
below.

**A genuine non-fast-forward mid-session**: while fix 5 was being validated, the project lead pushed directly to
this same branch (`b7c4984`: toroidal coordinates/lattice reduction, `BUGS-IN-REFERENCES.md`, a condensed
`CLAUDE.md`, `install-sbt.sh`). Resolved with a plain merge (`b91b2f9`, no conflicts — disjoint files), full
suite re-verified after merging (622 examples, up from 600, the new toroidal-coordinates/lattice-reduction
tests). Worth remembering for whoever else touches this branch: it is no longer exclusively this session's own
disposable history.

**The open, serious question: packed on `fractal-r` is at LEAST ~92x slower than SortedSet on this exact case,
unexplained.** At the time of SortedSet's 17-minute finish, packed's own run (same case, launched much earlier)
had been going for 94,625s (26.3 hours) with a CPU profile that scaled EXACTLY linearly with elapsed time across
two snapshots taken 66,000 seconds apart (every frame's percentage within 0.1% of the earlier one) — consistent
with steady, uninterrupted progress through a very large amount of real work, not a hang, but with no end in
sight. Both engines' profiles are dominated by the SAME shared `Chain.reduceLoop`/`RedBlackTree` machinery
(`Chain.scala`, used unchanged by packed), and packed's own `DiameterIndex` ordering (`compareDiamThenIndex`,
pure primitive comparison) should if anything be CHEAPER per comparison than SortedSet's `Simplex[Int]`
ordering (which needs `SimplexIndexing.apply` encode calls) — so there is no known mechanism explaining a 90x+
inversion of the pattern this entire investigation otherwise found (packed faster, often dramatically,
everywhere else). Recommended (not yet done, requires the compute server): kill the still-running packed job
(two stable profile snapshots are unlikely to be followed by a third that looks qualitatively different) and
either get packed's own `totalSimplexCount`/`substitutionCount` via a fresh, shorter attempt, or better,
construct a smaller synthetic reproduction (similar structure -- a distance-matrix-based, near-complete-on-
triangles complex -- but small enough to finish in seconds) to iterate on directly rather than spending another
multi-day run per attempt.

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

## Update 2026-10-03: `fractal-r` root-caused — a genuine infinite loop, not a slow reduction

The "~92x slower, unexplained" framing above was wrong in kind: packed wasn't doing more work than SortedSet,
it was stuck in a literal non-terminating loop. Reproduced locally (this session's sandbox, not the compute
server) on a 100-point submatrix of the real `fractal_9_5_2` distance matrix — small enough to iterate on in
seconds per attempt, confirming the "construct a smaller synthetic reproduction" suggestion above, except the
reproduction needed to be the REAL data, not synthetic (see why below).

**Root cause.** `ExplicitMetricSpace.distance(x, y) = dist(x)(y)` (`FiniteMetricSpace.scala`) was a raw,
unsymmetrized matrix lookup. Checked `fractal_9_5_2`'s own distance-matrix text file directly: 168 of its
100x100 submatrix's 10,000 entries have `dist(i)(j) != dist(j)(i)`, differing by ~1e-5/1e-6 (rounding in
whatever produced the roadmap benchmark's file, not something tda4j did). `PackedRipserCohomologyContext.
insertionDiameter` computes a cofacet's diameter incrementally via `distance(existingVertex, insertedVertex)`
— and which vertex plays which role depends on which facet a simplex was reached from. So the SAME
combinatorial simplex (same `SimplexIndexing` index) could get stamped with two slightly different `diameter`
values depending on path: confirmed directly by instrumenting `Chain.reduceLoop` and `PackedRipserCohomology
Context`'s own `basisFallback` — one specific tetrahedron (index 2795451) was seen stamped `0.045074` as a
`z.head` entry and `0.045073` as a `basis` entry.

That one-ULP-ish difference was fatal because of a second, independent inconsistency: `basis` is a
`mutable.HashMap[DiameterIndex, Chain[...]]`, keyed by `DiameterIndex.equals` (index-only, by design — see the
class's own doc on why). `z` is a `mutable.TreeMap[DiameterIndex, CoefficientT]`, keyed by `compareDiamThenIndex`
(diameter THEN index). `basis.get(sigma)` found the stale entry (index matches), but eliminating it against `z`
via `updateMap` touched the TreeMap key `(0.045073, 2795451)` — a DIFFERENT key from `z`'s own
`(0.045074, 2795451)` entry under that Ordering. The intended cancellation silently never happened. `z.head`
was unchanged on every iteration; `Chain.reduceLoop` cycled through the identical `(zSize=19141,
lastSigma=DiameterIndex(0.045074,2795451))` state for 4,000,000+ iterations straight (confirmed by a temporary
iteration counter, since reverted) with no sign of ever stopping on its own.

`RipserCohomologyContext` (SortedSet) was never at risk from this mechanism, structurally: its `Simplex[Int]`
IS the `Chain` cell type directly (no separate diameter-carrying wrapper), and `filtrationValue`/
`cohomologyOrdering` recompute the diameter fresh from the vertex set every time, via one fixed canonical
iteration order (ascending vertex pairs) — so the same simplex always gets the same diameter regardless of
which code path asked for it. `o3_1024`'s own anomaly (fixed earlier in this file, 5 commits) is unrelated:
it's a point cloud (`EuclideanMetricSpace`), whose `distance` is exactly symmetric by formula, so it can't
trigger this at all.

**Fix (two parts, per project-lead sign-off — asked first since `ExplicitMetricSpace` has ~20 referencing
files):**
1. `ExplicitMetricSpace.distance(x, y)` now reads `dist(min(x,y))(max(x,y))` — always the same matrix cell
   regardless of which argument order a caller uses, so it's self-consistent by construction for every
   consumer (`matlab.TDA4j`, the IO loaders, DTM/witness/Dowker streams), not just the two Ripser engines.
   Picks one already-present value rather than averaging/fabricating a new one.
2. `PackedRipserCohomologyContext.compareDiamThenIndex` now returns 0 unconditionally when `x.index == y.index`,
   before ever consulting `diameter` — brings the Ordering back into agreement with `.equals`/`.hashCode` (both
   already index-only), so a future `FiniteMetricSpace` implementation that isn't perfectly symmetric can't
   reintroduce the same `basis`-vs-`z` key mismatch.

**Verification.** Full suite (622 examples) green after both fixes + `scalafmtAll`. On the 100-point real-data
submatrix: packed now finishes in ~530ms (median of 3, was a confirmed-infinite loop before) with
`bars`/`apparentPairCount`/`substitutionCount`/`totalSimplexCount` all EXACTLY matching SortedSet's own numbers
(`bars=Map(0->100, 1->4219, 2->112527)`, `apparentPairCount=115822`, `substitutionCount=940`,
`totalSimplexCount=121164`) — packed is ~8.7x faster than SortedSet here, consistent with every other case in
this file's table. Full-scale (n=512) `fractal-r` re-run with the fix is in progress as of this update; see
whoever picks this up next for that result if it isn't recorded below yet.

All temporary debug instrumentation (the `packedVerbose`-gated iteration counters in `Chain.reduceLoop` and
`PackedRipserCohomologyContext`'s `basisFallback`, used only to pin this down) was reverted before committing
the real fix — matching this file's own established practice of not shipping throwaway diagnostic code.
