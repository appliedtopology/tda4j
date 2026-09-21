# Closing the chunks-engine representative gap (2026-09-20)

Follow-up to `.claude/WORKLOG-unionfind-in-chunks.md`'s "Follow-up: representatives" section, which shipped
dimension-0 representatives for `CellularPersistenceInChunksContext.barcodeAt` and left dimension >= 1 (finite
and essential alike) reporting `annotation = None`, flagging the real blocker: "the chunked algorithm's local
pass can defer a pair's resolution into a later global pass, so presence of a substituted cell's `coboundaries`
entry can't be assumed the way the naive engine's single sequential sweep guarantees." This session closes that
gap for every dimension `<= maxDim`, not just dimension 0.

## Starting state

Before this session started, the working tree already had (uncommitted) the union-find dim-0/1 fast path and
dimension-0 representative tracking from the prior session -- confirmed against `.claude/CLAUDE.md`'s own
narrative of that work, which matched the diff exactly. This session built on top of that uncommitted state
rather than re-deriving it; the project lead commits their own work, so nothing was committed here either (see
`.claude/CLAUDE.md`'s "Collaboration preferences" -- and the standing memory note on this).

## The plan `advisor()` blessed, and the correction it made before any code was written

Original idea (matching the blocker's own framing): port `CellularHomologyContext`'s V-column bookkeeping
directly into `processCell`/`compress`/`globalReduce`'s own state. Before writing any of that, `advisor()` was
consulted with the concrete design. Verdict: a *separate, sequential* representative pass (run only when
`barcodeAt` -- not `diagramAt` -- is called) sidesteps the deferred-pair-resolution hazard entirely, since it
never touches the chunked algorithm's own local/global bookkeeping. Four corrections came back:

1. **Validate the source formula first -- it had never been audited.** The plan's first draft intended to reuse
   `SimplicialHomologyByDimensionContext`'s `cycles`/`coboundaries` mechanism (CLAUDE.md already flagged its
   birth/death *values* as cross-validated, but its representative *chains* as never audited). Checked before
   committing to it, not after: `SimplicialHomologyByDimensionContext` and `CellularHomologyContext` were run
   side by side on `elderRuleCells`/`triangleCells`/`tetrahedronCells` and their per-bar representatives
   compared directly, including a `boundary(rep) == 0` check.

   **Result: the `coboundaries` formula is genuinely broken for dimension >= 1.** Every dimension >= 1
   representative it produced had a *nonzero* boundary -- not a differently-signed or differently-normalized
   cycle, an outright non-cycle. Example, `triangleCells`' dimension-1 bar: naive engine's representative
   `(2,3) - (1,3) + (1,2)` has boundary `0` (a genuine cycle); `SimplicialHomologyByDimensionContext`'s
   representative for the identical bar, `-(2,3) - (1,2) - (1,3)`, has boundary `2*v1 - 2*v3` -- nonzero. Larger
   fixtures (`tetrahedronCells`) showed the same failure plus visibly uncollapsed duplicate terms (the same cell
   appearing multiple times with the same sign, never cancelling), consistent with a formula that was never
   actually a valid V-column/coboundary construction for this case, just happened to still report the correct
   birth/death dimension and value (`leadingCell`/pivot identity survives even when the rest of the chain is
   wrong).

   This is a **real, previously unknown bug** in `SimplicialHomologyByDimensionContext`, not something this
   session introduced or needed to fix -- that class exposes no public method that returns its `cycles`/
   `coboundaries` chains at all, so nothing had ever exercised this path. Flagged here and to the project lead
   directly; **not fixed in this session** (out of scope: the class remains, per existing CLAUDE.md guidance, an
   independent birth/death-value oracle only, and this doesn't change that role -- it changes what should NOT be
   trusted from it, which was already "nothing, since nothing reads it").

   Given the audited formula failed, the plan pivoted to `CellularHomologyContext`'s own V-column formula (the
   one actually used in production via `engine="naive"`), per advisor's own fallback suggestion.

2. **Don't promise exact match in general -- representatives aren't unique.** Noted for the docs, though it
   turned out to be moot for the specific implementation chosen (see below): a genuinely independent computation
   would only be guaranteed to agree up to a nonzero scalar, not bit-for-bit.

3. **Off-by-one: cap at `internalMaxDim`, not `maxDim`.** A dimension-`maxDim` finite bar's representative is
   extracted at the moment its `(maxDim+1)`-dimensional killer is processed; capping the representative
   computation's own cell set at `maxDim` (not `maxDim + 1 = internalMaxDim`) would silently leave every
   dimension-`maxDim` finite bar with `None` -- the same off-by-one the class's own top-of-file doc already
   documents for `allCells`/`advanceAll`.

4. **`barcodeAt` gives up the union-find fast path entirely, and that needs to be said plainly, not buried.**
   Whatever mechanism computes representatives for dimension >= 2 needs dimension-1 bookkeeping too (to seed
   cycle/coboundary state for the first dimension above 1), which re-does exactly the work `unionFindDim01`
   exists to let `diagramAt` skip -- and edges are where that fast path's win is largest. Acceptable, since
   `diagramAt` itself is untouched and stays fast; representatives are opt-in but not incrementally so.

## What shipped: full delegation, not a hand-copied mechanism

Rather than transcribing `CellularHomologyContext`'s V-column formula a second time into new fields on
`CellularPersistenceInChunksContext.HomologyState` (real risk of a second, independent transcription bug -- see
finding #1 above for what that risk looks like when it isn't caught), `barcodeAt` now **delegates the entire
representative computation** to a fresh, independent `CellularHomologyContext[CellT, CoefficientT, Double]`,
run over a new private `dimCapped(internalMaxDim)` stream wrapper -- a minimal generic `StratifiedCellStream`
truncation, mirroring `LimitedCofaceSimplexStream`'s exact contract (`SimplexStream.scala`) but generalized from
`CofaceSimplexStream[Int, Double]` (`Simplex`-only) to any `CellT: OrderedCell`, since that class can't be reused
directly here.

```scala
def barcodeAt(f: Double): List[PersistenceBar[Double, Chain[CellT, CoefficientT]]] =
  CellularHomologyContext[CellT, CoefficientT, Double]()
    .persistentHomology(dimCapped(internalMaxDim))
    .barcodeAt(f)
    .filter(_.dim <= maxDim)
```

This is correct, not just convenient, by the same canonical-reduced-matrix argument `unionFindDim01` already
relies on: a fixed total order (`stream.filtrationOrdering`) over a fixed cell set determines a *unique* reduced
boundary matrix, independent of which algorithm computes it. `dimCapped(internalMaxDim)` walks exactly the same
cells `advanceAll`'s own `allCells` does, over the identical ordering -- so `CellularHomologyContext`'s
sequential single-pivot-table reduction and the chunked local/global algorithm are provably computing the same
matrix, just via different traversals. This was **confirmed empirically, not just argued**: a new `HomologySpec`
test cross-checks `barcodeAt`'s own `(dim, lower, upper)` triples (annotation stripped) against `diagramAt`'s
output on the same state -- two genuinely different code paths inside the same class, so agreement here is real
cross-validation, not a tautology.

Consequence for advisor's point #2: since this delegates to the exact same class and formula the naive-engine
oracle everywhere else in this codebase already uses, representatives match **exactly**, not merely up to
scalar, whenever the capped and uncapped streams agree on the relevant cells (true for every finite complex
tested here, where the requested `maxDim` already exceeds the fixture's own top dimension). The weaker
"up to scalar" caveat would only bite a caller who compares against some *third*, independently-derived
representative source -- not relevant to what `HomologySpec`/`CubicalStreamSpec`/
`FilteredSimplicialSetStreamSpec` actually check.

## Cleanup that fell out of this change

`HomologyState.essentialRepresentatives` (added by the prior, dimension-0-only session) became dead once
`barcodeAt` stopped reading chunks' own `barcode`/`essentialSimplices` state for representatives at all --
removed, rather than left as an unused field. The same reasoning applied one level deeper: `barcode`'s stored
tuples used to carry a `Chain[CellT, CoefficientT]` third element (`recordPair`'s `dsigmaReduced`,
`unionFindDim01`'s `Chain(dyingVertex)`), read only by the *old* `barcodeAt`. With `barcodeAt` no longer reading
it, `diagramAt` was already discarding it (`case (lower, upper, _) => ...`), so nothing read it at all --
`barcode`'s tuple type was narrowed back to `(Double, Double)`, removing a Chain allocation from the hot fast
path (`unionFindDim01`, `recordPair`) that existed only to feed a reader that no longer exists.

## A second real finding, incidental to writing the tests: `Chain` has no matching `hashCode`

`Chain.equals` is overridden (collapses both sides and compares sorted entries), but `Chain` has no
corresponding `hashCode` override -- it falls back to identity hash. This is a real violation of the
`equals`/`hashCode` contract: two `Chain`s that are `==`-equal can and do land in different buckets of a
`scala.collection.Set`/`Map`. Caught directly while writing this session's own cross-validation test: an
initial `chunksReps.toSet == naiveReps.toSet` comparison (tuples containing `Chain` values) failed even though
manual inspection showed the two chains were the same cycle with terms in a different insertion order --
`.toSet` was silently doing an identity-hash-bucketed comparison, not the semantic one `Chain.equals` implements.
Worked around in the test (a bipartite multiset match using `Chain`'s own `==` directly, not `Set`/`Map`
membership) rather than fixed in `Chain` itself -- flagged here as a real, live footgun for any future caller
who puts a `Chain` in a `Set`/`Map` key position (test code or production), not attempted as a fix in this
session (adding a `hashCode` consistent with the existing `equals` is a small, mostly self-contained change, but
touches a widely-shared class with no scoped reason to touch it here).

## Validation

- `HomologySpec`: the former "dimension-0 representatives match the naive engine's exactly" test is replaced
  with a broader one covering every dimension, checking (1) no bar with `dim <= maxDim` has `annotation = None`,
  (2) exact representative match against the naive engine (multiset, via `Chain.equals` directly -- see the
  `hashCode` finding above), (3) `barcodeAt`'s own bars agree with `diagramAt`'s, ignoring annotation, (4) every
  representative is a genuine cycle (`boundary(rep) == 0`), (5) a representative's own leading (youngest) cell
  is exactly its bar's birth cell, and every term in it existed (by filtration value) no later than the bar's
  own birth -- across `elderRuleCells`/`triangleCells`/`tetrahedronCells`.
- `CubicalStreamSpec`: the same no-`None`/genuine-cycle/exact-match-against-naive checks, on `Cube` fixtures
  (the tie-heavy 2x2/3x3/1D fixtures already used elsewhere in that file) -- real coverage of the generic
  `dimCapped`/`CellularHomologyContext[CellT, ...]` instantiation for a non-`Simplex` cell type, not a formality.
- `FilteredSimplicialSetStreamSpec`: the same no-`None`/genuine-cycle checks on the torus fixture and
  `realProjectiveSpace(2)` (RP2) over **F3**, this codebase's established sign-discriminating fixture (`H_1=H_2=
  F2` over F2, both `0` over F3 -- an alternating-sum sign error is invisible over F2). `FiniteSimplicialSet`
  generators are the only `OrderedCell` instance whose boundary formula involves the degeneracy machinery at
  all, so this is genuinely different coverage from `Cube`/`Simplex`, not a rerun of the same check.
- `Tda4jSpec`: the old split test ("readable for dimension-0 bars" / "still explicitly refuses above dimension
  0") is merged into one confirming every bar, at every dimension, is now readable through the MATLAB facade's
  `engine="chunks"` path.
- Full `sbt -J-Xmx4g clean test`: 261 examples, 256 passed, 0 failed, 0 errors, 5 skipped, 1 pending (unchanged
  baseline -- `HomologySpec.scala`'s `BarcodeRegressionSpec` stays `skipAll`'d, see CLAUDE.md's own account of
  why). `scalafmtCheck`/`scalafmtSbtCheck` clean after `scalafmtAll`.

## What's still open

- `SimplicialHomologyByDimensionContext`'s `cycles`/`coboundaries` representative bug (finding #1 above) is
  real and unfixed -- flagged for whoever next has a reason to expose that class's chains publicly, which
  nothing currently does.
- `Chain`'s missing `hashCode` (finding #2 above) is real and unfixed -- flagged for whoever next needs to put
  `Chain` values in a `Set`/`Map` key position.
- `barcodeAt`'s representative computation is a full, independent sequential pass with no caching across calls
  -- calling it twice in a row pays twice. Not addressed here; matches `CellularHomologyContext` itself, which
  is also freshly constructed on every `barcodeAt` call from this delegate.
- No attempt was made to measure `barcodeAt`'s new wall-clock cost against the old (dimension-0-only) version,
  or against a hypothetical "port the mechanism into the chunked passes directly" alternative -- out of scope
  for a correctness-closing session, and the honest expectation (stated above and in the source doc) is that
  this is exactly as expensive as running `CellularHomologyContext` from scratch, which was never going to be
  cheaper than what porting the mechanism directly would have cost either, given dimension 0/1 must be
  re-derived either way.
