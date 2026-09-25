# DESIGN: extending the fast dual-union-find engines past d=2 via a hybrid with `chunks`

Status: design note written and cubical (d>=3) implementation started 2026-09-25, same session as
`WORKLOG-alpha-dual-unionfind-matlab-wiring.md`. Alpha's own port is explicitly sequenced AFTER cubical is
validated (mirrors how item 7 followed item 6 originally) -- see "Sequencing" below.

## The request, and a correction to how I'd been framing it

Both `FastCubicalHomologyContext` and `FastAlphaHomologyContext` currently `require(ambientDim == 2, ...)`.
My own framing of that limit, going into this note, was "the union-find trick isn't viable above d=2" --
the project lead corrected this directly: it's still worth having at d>=3 even though the win shrinks, for
both engines, and the correction is right. The mechanism itself (Alexander duality reducing `H_0`/`H_{d-1}`
to ordinary graph connectivity) does not degrade with dimension at all -- `H_0` is a union-find on the primal
1-skeleton regardless of `d`, and `H_{d-1}` is a union-find on the dual graph regardless of `d`, and neither
computation cares how large `d` is. What actually shrinks with `d` is the FRACTION of the total homology
those two computations account for: at `d=2` there are zero "middle" dimensions (`1 <= k <= d-2` is the empty
range `1 <= k <= 0`), so the two union-finds account for literally everything. At `d=3` there is exactly one
middle dimension (`H_1`); at general `d` there are `d-2` of them, and each one needs genuine boundary-matrix
reduction, not a duality trick -- there is no "H^0 of some derived graph" reformulation of an interior
Betti number. So the right shape for this work is a HYBRID: keep both union-finds unconditionally (they cost
almost nothing and are already-correct at any `d`), and hand the residual middle dimensions to the general
engine this codebase already has -- not "gate the fast engine off above d=2," which was my own wrong framing.

## Key finding: the two union-finds are ALREADY dimension-generic in the existing code

Before designing anything new, I re-read both engines' actual code rather than assuming from the doc
comments. `FastCubicalHomologyContext.computeDualTopDimension` and `FastAlphaHomologyContext`'s own
`computeDualTopDimension` are BOTH already written in terms of `stream.ambientDim`/`helix.ambientDimension`
symbolically -- `bars += new PersistenceBar(ambientDim - 1, ...)`, the facet-enumeration loop
(`(0 until ambientDim).flatMap { degenAxis => ... }` for cubical), the vertex/facet event construction -- none
of it hardcodes `2` anywhere. `computeH0` is dimension-agnostic by construction (it only ever looks at
dimension-0/1 cells, a primal 1-skeleton union-find, regardless of what `d` is). **The ONLY thing gating
either engine to `d=2` is the single `require` check at the top of `persistentHomology`.** This is a
significant simplification for scope: this design does not need to touch either union-find implementation at
all. The entire job is (a) relaxing the `require`, and (b) adding a third piece for the middle dimensions,
then combining all three.

## Why the dual union-find's correctness doesn't depend on what happens in the middle dimensions

Worth spelling out explicitly, since it was the one place I initially talked myself into a phantom problem
before rejecting it. In the STANDARD single-pass reduction algorithm, a `(d-1)`-cell has two separate roles:
first, its OWN boundary is reduced via `d_{d-1}: C_{d-1} -> C_{d-2}` (against `(d-2)`-cell pivots), which
determines whether it's "negative" (paired to a `(d-2)`-cell, closed forever) or "positive" (open, and NOW a
candidate pivot for some `d`-cell's own later reduction). A cell already claimed as negative can never also
become a pivot (this codebase's own established invariant, e.g. `CLAUDE.md`'s chunks section: "a paired cell
must never become a pivot"). So naively, it looks like the dual-graph technique -- which treats every facet as
a candidate dual-graph edge with no reference to whether that facet is "already claimed" by a `(d-2)`-cell --
might double-use a cell the standard algorithm would have excluded.

It doesn't, because the dual-graph technique isn't a re-derivation of the standard algorithm's own bookkeeping
-- it's an independent computation of the same invariant (persistent `H_{d-1}`) via a different theorem
(Alexander duality: `H_{d-1}(X) ~= H~^0(S^d \ X)`, a statement about the topology of `X` at each filtration
value, full stop). Two valid algorithms for the same persistence problem can disagree completely about which
specific cell is "negative" vs "positive" internally (this codebase's own docs already note this for ties:
"which tied cell dies at a tied time is order-dependent") while still agreeing on the resulting BARCODE. The
existing, already-exhaustively-validated `d=2` case is not a lucky special case of this argument -- it's
already relying on it: `computeH0`'s own dimension-0 union-find and `computeDualTopDimension`'s own dimension-1
dual union-find already disagree, in general, with what a single monolithic reduction would internally call
"negative" for a given edge, and they've been cross-validated against the naive engine on thousands of random
and hand-derived fixtures with exact agreement regardless. The middle-dimension general reduction added by
this design doesn't need to coordinate with the dual union-find's own internal bookkeeping at all -- it only
needs to correctly compute `H_1..H_{d-2}` of the SAME underlying complex, which is a well-posed question
independent of how `H_0`/`H_{d-1}` happen to get computed alongside it.

## The construction: truncate, then reuse `chunks` unchanged

This codebase already has the exact truncation argument needed, established for the naive/cohomology engines'
own `maxDim` handling (`LimitedCofaceSimplexStream(stream, k+1)`, dropping the resulting `dim == k+1` bars):
removing a complex's top-dimensional cells cannot change any lower-dimensional boundary map at all (`d_1
.. d_{k}` only ever involve cells of dimension `<= k`), so persistent homology at dimensions `0..k-1` computed
on a complex truncated to `dim <= k` is EXACTLY correct, and only the reported dimension-`k` bars themselves
are wrong (artificially left open where a higher cell, now missing, would have closed them).

Applying this here: truncate the stream to dimensions `0..d-1` (hide the real top-dimensional `d`-cells
entirely), and run `CellularPersistenceInChunksContext[CellT, C](maxDim = d - 2)` on the truncated stream.
Two existing `chunks` behaviors line up with this perfectly, with no new logic needed on that side at all:

- `chunks`'s own documented internal contract is "walks `0..maxDim+1`, filters essential bars to `<= maxDim`"
  (`CLAUDE.md`, "maxDim/maxDimension means top homological degree reported"). With `maxDim = d - 2`, that's
  "walk `0..d-1`, report `<= d-2`" -- i.e. it wants EXACTLY the cells the truncated stream provides (`0..d-1`),
  and its OWN pre-existing filtering already discards the incomplete, truncation-induced `d-1` bars for free.
  No new "drop the top bar" step needs writing here; it's the same mechanism `naive`/`cohomology` already use
  for their own `maxDim`, just consumed via `chunks`'s own parameter instead of a second wrapper layer.
- `chunks` already resolves dimensions 0 (and, from its own `unionFindDim01`, the paired/unpaired status of
  dimension-1 cells) via raw union-find before any general `Chain` reduction runs at all (`CLAUDE.md`: "a fixed
  total order determines a unique reduced matrix; `advanceAll` starts at delta=2"). So invoking `chunks` for
  the middle dimensions gets `H_0` back as a side effect, at no extra cost over what `chunks` was going to do
  anyway -- there's no need for this design to ALSO keep the existing `computeH0` union-find in the `d >= 3`
  path; it can come from `chunks`'s own output instead, and `computeH0` stays exactly as-is, used only in the
  (unchanged) `d=2` path where invoking `chunks` at all would be pure overhead (an empty middle-dimension
  range).

So, concretely, `persistentHomology` becomes (shape, not final code):

```
if ambientDim == 2 then
  computeH0(stream) ++ computeDualTopDimension(stream)          // unchanged, zero new overhead
else
  val truncated = LimitedCubicalGridStream(stream, ambientDim - 1)         // hides the real top cells
  val middleBars = CellularPersistenceInChunksContext[Cube, C](ambientDim - 2)
    .persistentHomology(truncated)
    .barcodeAt(Double.PositiveInfinity)                                    // H_0 .. H_{d-2}, real reps
  middleBars ++ computeDualTopDimension(stream)                            // H_{d-1}, on the UNtruncated stream
```

`computeDualTopDimension` needs the real, untruncated stream (it reads actual top-dimensional cells directly)
-- only the middle-dimension call goes through the truncated view.

### The one new piece of code: a truncating stream wrapper

`LimitedCofaceSimplexStream` (`streams/SimplexStream.scala`) is hardcoded to `CofaceSimplexStream[Int,
Double]`, i.e. `Simplex[Int]` specifically -- it doesn't fit `Cube`. `StratifiedCellStream[CellT: OrderedCell,
FiltrationT: Filterable]` itself has exactly one abstract member (`iterateDimension`; `filtrationOrdering`/
`filtrationValue` come from the `CellStream`/`Filtration` parents it also requires), so a `Cube`-specific
mirror is a small, low-risk addition:

```scala
class LimitedCubicalGridStream(stream: CubicalGridStream, maxDim: Int)
    extends StratifiedCellStream[Cube, Double]
    with DoubleFiltration[Cube]():
  override def iterateDimension: PartialFunction[Int, Iterator[Cube]] = {
    case d: Int if d >= 0 && d <= maxDim && stream.iterateDimension.isDefinedAt(d) => stream.iterateDimension(d)
  }
  override def filtrationOrdering: Ordering[Cube] = stream.filtrationOrdering
  override def filtrationValue: PartialFunction[Cube, Double] = stream.filtrationValue
```

This preserves `StratifiedCellStream.iterator`'s contiguous-from-0 contract trivially (truncating a
contiguous `0..ambientDim` domain to `0..maxDim` is still contiguous from 0), and delegates ordering/value to
the wrapped stream unchanged -- the same shape `LimitedCofaceSimplexStream` already uses for the simplicial
case, just against the smaller `StratifiedCellStream` interface `Cube` streams actually implement (no
`CofaceSimplexStream`-specific members like `currentDimension`/`keepCriterion` to forward, since cubical
streams don't have those at all).

## What this does to the engines' own dimension ceiling

This construction has no inherent dimension cap of its own -- `chunks` is already fully general over `d`, and
the two union-finds don't degrade with `d` either (per the correction above). So the natural end state is
`require(ambientDim >= 2, ...)` -- not "extend the cap from 2 to 3," but remove the cap entirely, letting
performance (not correctness) be the only thing that changes as `d` grows. That matches the project lead's own
framing exactly ("still worth having it available even if the win reduces at higher dimensions"): there's no
principled place to draw a NEW hardcoded line, so this design doesn't invent one. Validation below still only
exercises `d=3`/`d=4` directly (nothing here can be validated at literally every `d`), so the doc/worklog
should say what was actually tested, not claim unbounded confidence.

## Risks and what still needs empirical validation (not just this argument)

- **Representative correctness through `chunks` on a truncated stream**: the barcode argument above
  (truncation can't affect lower boundary maps) is standard, but this codebase's own culture is "agreement
  between two engines isn't proof... which tied cell dies at a tied time is order-dependent" -- needs a real
  cross-validation run against `CellularHomologyContext`/`CubicalHomologyContext` (the naive engine) on 3D
  fixtures, both bars AND representative chains (`Chain.from(...).isZero()` cycle checks), not merely assumed
  from the argument being sound on paper.
- **Alpha's own risk is separate and likely WORSE at higher d, not the same rate**: `FastAlphaHomologyContext`
  additionally carries the facet-multiplicity precondition risk (`FastAlphaTriangulationException`, measured
  ~1-in-18700 at `d=2` specifically). `HelixDelaunay`'s OWN, separate, already-documented near-cospherical
  limitation is known to get MUCH worse with ambient dimension (`AlphaCrossValidationSpec`'s own doc: ~1-in-170
  at `d=4`, vs. negligible at `d=2`/`d=5` in that spec's own sweep -- note d=5 there was fine but d=4 wasn't,
  so this isn't even simply monotonic, which is exactly why it needs re-measuring, not extrapolating). The
  ~1-in-18700 figure must NOT be assumed to carry over to `d=3` -- it needs its own fresh, targeted measurement
  (the same throwaway-script methodology `WORKLOG-alpha-dual-unionfind.md` used originally) before `fast-alpha`
  is extended past `d=2`, and the exception message's own "measured at roughly 1-in-18700" text is specific to
  `d=2` and would need updating per-dimension or generalizing once real numbers exist for `d=3`.
- **Cost crossover point unmeasured**: this design predicts a shrinking-but-real win, matching the project
  lead's own expectation, but that's a prediction, not a measurement -- an A/B against plain `chunks` on the
  untruncated complex (this codebase's own standing performance-claim discipline: "isolated A/B measurement,
  median of trials, one engine per JVM") is needed once the implementation exists, not asserted from the
  design alone.
- **`totalBarsAccountForAllCells`-style invariants**: `HomologyFixtures`'s existing helper may need the same
  care the original cubical work already flagged (a known miscounting edge case around `+Infinity`-valued
  primal cells) -- re ‑check rather than assume it transfers cleanly to a 3-way-combined bar list.

## Sequencing

Cubical first, alpha second -- not concurrent -- mirroring exactly how item 7 (alpha) was originally scoped as
a deliberate follow-on to item 6 (cubical), not simultaneous work, in `WORKLOG-alpha-dual-unionfind.md`'s own
opening section. Cubical has no analogue of the facet-multiplicity/HelixDelaunay risk at all (a grid's "every
facet has 1 or 2 top-cell cofaces" holds unconditionally at any `d`), so it's the strictly lower-risk place to
validate the truncate-and-hand-to-chunks pattern itself before alpha's own, separate risk profile is layered
on top of it.
