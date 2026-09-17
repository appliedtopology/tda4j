# Worklog: chasing and fixing the `SimplicialHomologyContext` dimension >= 4 crash

Session date: 2026-09-16. Direct follow-up to WORKLOG-dimension-ceiling.md's bug 1 (`SimplicialHomologyContext`
throws `IllegalStateException: reduction pivot ... was not a recorded open class` once any dimension-4 simplex
exists). The project lead asked to fix the reference engine's crash first, and separately floated switching the
codebase's "wide default" VR stream from `EnumeratingCofaceSimplexStream` to `IncrementalVietorisRipsSimplexStream`
(NewVR).

## NewVR would not have fixed this -- checked before touching anything

`IncrementalVietorisRipsSimplexStream extends EnumeratingCofaceSimplexStream` and only overrides `iterateDimension`
(confirmed by reading `SimplexStream.scala` directly) -- it inherits the exact same `.iterator()` default every
other stream here does. Reproduced the crash on NewVR directly, on the same point cloud: identical exception,
identical pivot (`TreeSet(5, 8, 9, 11)`). This rules out the stream implementation entirely and confirms the bug
lives in `CellularHomologyContext`/`SimplicialHomologyContext` itself, not in whichever stream feeds it. The
"switch the default stream" question is a separate, valid decision on its own merits (VR construction strategy),
but it does not bear on this crash and was not pursued further this session.

## Fix 1 (real, kept): `CellularHomologyContext` consumed cells in dimension-major order, not true filtration order

`HomologyState.CellIterator` was `stream.iterator.buffered`. For any `StratifiedCellStream` (every stream in this
codebase besides a hand-built `.iterator` override), that default is dimension-major: all of dimension d, then all
of dimension d+1, ... -- correct only *within* one dimension's own bucket (which every stream is careful to sort by
`filtrationOrdering.reverse`). But `CellularHomologyContext`'s single shared pivot table (`boundaries`/`positives`,
populated across ALL dimensions together) is the classical Algorithm 1, which requires cells consumed in one
combined, true filtration order across every dimension -- unlike `RipserCohomologyContext`, whose `basis` map
resets per dimension and so never needs this.

Confirmed empirically, not just reasoned through: on the 15-point, ambientDim=3 repro cloud built to dimension 4,
4 dimension-4 cells have a strictly smaller (older) filtration value than the dimension-3 cell dimension-major
order processes first -- a genuine ordering inversion, not a hypothetical one.

**First attempt at the fix was wrong and is worth recording.** Re-sorting via `stream.filtrationOrdering.reverse`
wholesale changed the crash's pivot (from `TreeSet(5,8,9,11)` to `TreeSet(0,9)`) rather than fixing it:
`filtrationOrdering`'s tie-break is `(dimension, then colex)`, written UNREVERSED even though the primary key
(filtration value) IS negated in the comparator -- so `.reverse` on the whole ordering flips the dimension
tie-break too, making a tied triangle sort *before* its own longest edge (the opposite of Algorithm 1's actual
precondition: every cell's faces must precede it). This never surfaced at any existing `.sorted(using
filtrationOrdering.reverse)` call site because those all sort within one dimension, where the tie-break is a
no-op. Caught by an advisor consult, not independently.

**Correct fix**: build the processing order explicitly --

```scala
val processingOrder: Ordering[CellT] =
  Ordering
    .by[CellT, FiltrationT](c => stream.filtrationValue.applyOrElse(c, (_: CellT) => stream.smallest))
    .orElse(Ordering.by[CellT, Int](_.dim))
    .orElse(stream.filtrationOrdering.reverse)
val CellIterator = stream.iterator.toVector.sorted(using processingOrder).iterator.buffered
```

ascending filtration value (unreversed, oldest first -- the correct direction), then ascending dimension (faces
before cofaces on a tie), falling back to `stream.filtrationOrdering.reverse` only to inherit the established
within-dimension colex tie-break bit-for-bit (safe to reuse there because two same-dimension cells never reach the
dimension key above, so `.reverse`'s flipped dimension order is never consulted for them).

**Verified against the actual Algorithm 1 precondition, not just "no longer the same crash"**: wrote a standalone
check asserting every cell's own faces appear strictly before it in the sorted sequence (`cell.boundary` compared
against each face's position) -- zero violations, on the same repro. `given Ordering[CellT] = stream.filtrationOrdering`
(used for `Chain`'s pivot selection, i.e. `leadingCell`) did NOT need changing: every `Chain[CellT, CoefficientT]`
this algorithm builds is dimension-homogeneous (a cell's boundary, and every `boundaries`/`generators` entry
derived from it, only ever contains cells of one fixed dimension), so the dimension tie-break is structurally
never exercised during pivot selection -- confirmed by re-reading `advanceOne`'s construction of `dsigma`/`reduced`/
`vcol`, not assumed.

**Cost, noted rather than left silent**: `stream.iterator.toVector` now materializes every dimension simultaneously
where the old dimension-major path could in principle release each bucket after use (though in practice each
`iterateDimension(d)` call already fully materializes its own bucket via `sortedByFiltration` before yielding
anything, so the actual new cost is one O(N log N) global sort over the combined cell count, not a complexity-class
change). Acceptable for the reference/oracle engine, whose purpose is correctness over speed -- not applied to any
other engine.

**Full regression suite run after this fix alone (before Fix 2 below existed)**: not isolated separately: both
fixes were verified together in one final `sbt clean test` run (below) rather than one full run per fix, since
Fix 2 was found while still investigating the crash Fix 1 didn't resolve.

## Fix 2 (real, independently justified, kept -- but NOT the cause of the crash that motivated this session)

While instrumenting the crash, noticed `Chain.scala` had **four** separate zero-tests using plain Scala `==`/`!=`
against `fr.zero`, bypassing the coefficient field's own `isEqual` entirely:

- `Chain.collapseHead`: `if acc == fr.zero then ...`
- `Chain.collapseAll`: `.filter((c, x) => x != fr.zero)`
- `Chain.isZero()`: `entries.head._2 == summon[CoefficientT is Field].zero`
- `Chain.scala`'s private `updateMap` (used by `toSortedMap`/every `reduceBy`/`reduceByUntil` call): `if newCoeff
  == fr.zero then m.removed(cell) ...`

`Field.DoubleApproximated(epsilon)` exists specifically so equality (and hence zero-testing) respects a tolerance
(`isEqual(x,y) = abs(x-y) < epsilon`) -- all four sites silently ignored that and used exact bit equality instead,
for every `Double`-coefficient computation in this codebase, everywhere `Chain` is used. Fixed by routing all four
through `fr.isEqual(x, fr.zero)`. This is a real, independently-valuable correctness fix (any Double-coefficient
computation that legitimately cancels to a tiny nonzero residual was previously left as a phantom nonzero term
instead of being collapsed away) -- kept regardless of the outcome below.

**This was NOT what caused the dimension >= 4 crash, confirmed rather than assumed.** Initial reasoning (and an
advisor's initial hypothesis) was that a phantom near-zero coefficient, left behind by exactly this bug, was
being returned as an invalid "pivot." Directly refuted: instrumented the actual crash and printed the reduced
chain's contents -- the leading (crashing) coefficient is exactly `-2.0` (not `1e-16`-scale noise), and every
other term in the same chain is exactly `±1.0`. Floating point was never involved in this specific repro; all
values are small clean integers. The `Fp(2)`/`Fp(3)`/`Fp(17)` discriminator (below) independently confirms this.

## The actual crash: chased to this point, then resolved -- see "Fix 3" below

**Update, same session, after this section was first written**: resolved. See "Fix 3" at the end of this
worklog. The account below is kept exactly as it was written mid-investigation (including the parts that turned
out to be red herrings) because it's the actual record of how the fix was found, not because it's still accurate
about the crash being open.

Traced to a specific, well-understood mechanism, but not yet root-caused to a fix:

1. `TreeSet(5, 8, 9, 11)` (dimension 3) is processed at position 148 in the (now-correct) processing order. Its
   own boundary reduces to something *nonzero*, so it does not open a class -- it "kills" pivot `TreeSet(5, 8, 9)`
   (dimension 2) instead. Being a destroyer at its own level, it is written into neither `positives` nor
   `boundaries` as a *key* (only as the value stored under `boundaries(TreeSet(5,8,9))`'s pivot) -- it becomes
   invisible to both lookups from that point on.
2. At position 162, `TreeSet(3, 5, 7, 8, 9)` (dimension 4) reduces, cascading through eliminations against three
   earlier dimension-4 cells' own recorded boundaries. Two of those three independently contain `TreeSet(5,8,9,11)`
   with coefficient `+1`, so eliminating both legitimately leaves `-1 + -1 = -2` at that cell -- confirmed by a
   step-by-step trace of the actual `reduceLoop` substitutions, not inferred. Reduction stops there (no
   `boundaries` entry for it), and `advanceOne`'s invariant check throws because it's also not in `positives`.

Three plausible causes were checked and **ruled out** with direct evidence, not by assumption:

- **Field-dependent (torsion-like) behavior, not a bug**: ruled out. The crash reproduces identically (same
  pivot) over `Fp(3)` and `Fp(17)` -- fields where `-2` is nonzero (unlike `Fp(2)`, where `-2 ≡ 0` and the naive
  engine's earlier apparent "success" over `Fp(2)` was coincidental, not evidence of anything). A field-dependent
  difference would not reproduce identically across three fields with different characteristics.
- **Floating-point phantom-zero (the Fix 2 bug)**: ruled out directly, see above -- exact integer coefficients
  throughout, no residual noise anywhere near this chain.
- **Duplicate chain-entry merging** (a `basisChain` folded into the working map twice, or containing an
  un-collapsed duplicate key): ruled out by a full step trace of `reduceLoop`'s substitutions for the crashing
  column -- every `basisChain` consulted has exactly as many distinct cells as raw items, and the `-2.0` comes
  from two *different*, individually legitimate basis chains each correctly contributing `-1`, not from any chain
  being applied twice or containing a duplicate key.

What remains unresolved is whether the underlying textbook invariant -- "the final pivot of any nonzero reduced
column, under a correctly-executed single-pivot-table reduction, is always a currently-open creator, regardless of
dimension mixing" -- is being violated by a genuine bug somewhere upstream of position 148 (i.e. `TreeSet(5,8,9,11)`
was wrongly classified negative, or something feeding its own reduction at that point is wrong), or whether there
is a subtlety in this specific mixed-dimension formulation this session's reasoning hasn't correctly accounted
for. Advisor consults during this session leaned confidently toward "the invariant is real and is being violated,"
but that could not be independently nailed down to a specific defect within this session's effort -- both cheap
discriminators available (duplicate-entry check, field-independence check) came back clean, which is itself
useful information (it narrows where the bug can be) but does not identify it.

The above was where this session's own reasoning got stuck. Handed to the project lead rather than continuing to
guess -- their answer is what actually resolved it, below.

## Fix 3 (the real fix, from the project lead, not derived independently this session)

The project lead supplied the missing piece directly: the positive/negative tagging **is a matching** (a cell is
never both; unmatched cells are always positive), which corresponds to a direct-sum decomposition of the chain
module -- a basis for the boundaries and a basis for the non-boundary cycles, together spanning the cycles. A
leading coefficient of `-2` is unremarkable on its own (matches Fix 2's independent finding: not a bug signal by
itself). The actual missing piece: **"having been matched as the boundary of something means the reduction isn't
done when it gets picked out by something in the next dimension up."** I.e. `boundaries.get(sigma) == None`
during `reduceLoop` does NOT mean reduction has terminated at a valid new pivot -- it can also mean the current
leading cell is a NEGATIVE cell (already matched downward, as a destroyer), which is invisible to `boundaries`
(keyed only by positive/matched pivots) precisely because it's negative, not because it's unprocessed. Reduction
needs to be able to substitute past that case too, not just past matched-positive pivots.

**The fix**: a negative cell's own `vcol` (already computed in `advanceOne`'s negative branch, before this
change went unused past that branch) is exactly the right substitute -- a chain living in the negative cell's OWN
dimension whose leading term is the cell itself (same construction as any positive cell's `generators` entry).
Recorded it in a new map, `negativeVCols: mutable.Map[CellT, Chain[CellT, CoefficientT]]`, keyed by the negative
cell itself, written alongside `boundaries`/`generators` in `advanceOne`'s negative branch. Threaded it through
`Chain.reduceBy`'s existing `fallback` parameter (`Chain.reduceBy(dsigma, boundaries, Chain.empty, fallback =
negativeVCols.get)`) -- the exact same mechanism `RipserCohomologyContext` already uses for its own apparent-pairs
on-the-fly substitution (`zeroApparentFacet`), not a new pattern introduced for this fix.

**A second-order bug surfaced immediately after wiring the fallback in, fixed in the same pass**: `vcol`'s own
construction (`log.items.foldLeft(Chain(sigma)) { ... generators.getOrElse(pivot, throw ...) }`) assumed every
entry `reduceLoop`'s `log` could name was a `boundaries`/`generators`-keyed positive pivot -- true before this
fix, false after, since `log` now also records eliminations that went through the new fallback. Worked out from
first principles why this needed different handling, not by trial and error: a positive-pivot substitution
corresponds to a genuine dimension-shifted relationship (`boundaries(pivot) = ∂(generators(pivot))`), so `vcol`
must apply a matching correction to preserve the `∂(vcol) = reduced` invariant every later `generators` lookup
depends on. A negative-cell fallback substitution is a *pure basis change within one dimension* (re-expressing one
raw cell via earlier same-dimension cells) with no dimension shift and no relation to `sigma`'s identity at all --
the underlying value of the reduction is unchanged by it, so `∂(vcol) = reduced` already holds without any `vcol`
correction for those steps, and applying one would be wrong, not merely redundant. Fixed by skipping (not
correcting) any `log` entry whose key is in `negativeVCols` -- safe and unambiguous since `generators` and
`negativeVCols` have disjoint key sets by the matching property itself (a cell is never both positive-matched and
negative).

**Verified, not just "stopped crashing"**: both original repro streams (`EnumeratingCofaceSimplexStream` and
NewVR) now succeed and agree *exactly* with `RipserCohomologyContext`'s independently-derived bar count (3473) on
the same complex. A broader stress sweep (64 trials: varying seed/n/ambientDim/build-dimension 3-4, cross-
validating `SimplicialHomologyContext` against `RipserCohomologyContext` on each) found 0 mismatches and 0
crashes. Re-ran `DimensionCeilingBenchmarkSpec`'s previously-crashing cells (VR-Enum+Naive at H=3/H=4, all three
threshold regimes) directly -- every one now hits a normal timeout ceiling instead of an exception.

## What's kept

Full `sbt clean test` after all three fixes: 164 total, 160 passed, 0 failed, 0 errors, 4 skipped, 1 pending --
identical shape to the pre-existing baseline, no regressions. All three fixes (processing order, epsilon-aware
zero testing, and the negative-cell fallback) are independently justified and are kept together.

Not committed -- per standing project convention, the project lead commits their own work.
