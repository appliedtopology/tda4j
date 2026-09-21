# Raw union-find for dimension 0/1 inside the clearing engine (2026-09-20)

Follow-up to the cubical capacity sweep (`WORKLOG-cubical-capacity-sweep.md`) and the resulting question: "If
we lean on union find and discrete Morse theory, what can we do about cubical performance?" The project lead's
own refinement of that question is what actually got built: "We should be able to introduce union find
acceleration to the clearing engine, right? With dual union find kept as an option for when it really
matters?" Full design derivation is in `.claude/DESIGN-unionfind-in-chunks.md`, written before any code, which
this worklog assumes as read.

## Why this is different from the raw-UnionFind attempt CLAUDE.md already scoped out

CLAUDE.md's `SimplicialHomologyByDimensionContext` section records an earlier session's live design attempt at
a raw `UnionFind` port that "needs delicate, not-yet-fully-converged elder-rule/V-column coefficient
bookkeeping to stay correct for a LATER dimension-2+ cell's reduction" -- scoped out as real risk for a small
gain. That target was `CellularHomologyContext` (the naive engine), which reconstructs representative cycles
via V-columns for `barcodeAt`. Re-reading `CellularPersistenceInChunksContext.HomologyState` in full confirmed
it carries no such state at all (`boundaries`, `cleared`, `paired`, `essentialSimplices`, `killer`, `R`,
`activeRows`, `barcode` -- nothing V-column-shaped), matching the MATLAB facade's own documented
`UnsupportedOperationException` for `cycleVertices` under `engine=chunks`. This is the fact that makes the same
idea safe here even though it wasn't safe there.

Also worth being precise about, since it's easy to conflate: `SimplicialHomologyByDimensionContext` itself is
NOT a raw union-find engine, and was never claimed to be a performance win -- its own CLAUDE.md entry says it
was "validated here as provably correct but not obviously faster, since it pays the same `filtrationOrdering`
comparator cost as general reduction." It computes dimension-0/1 pairs by routing every edge through the
general `Chain.reduceBy` primitive, one dimension early, sequentially -- correct, and useful as an oracle, but
not the fast path itself. What's built in this session IS the fast path: raw array-based union-find with path
compression, zero `Chain`/`TreeMap`/`PriorityQueue` involvement for dimensions 0/1.

## What was traced before writing code

Advisor flagged three things to check before trusting the design (see `DESIGN-unionfind-in-chunks.md`'s own
section for the full derivation of each):

1. Whether the chunked algorithm's deferred, chunk-local `stop`-gated pairing could disagree with a single
   global union-find pass. Resolved by tracing `Chain.reduceByUntil`'s `reduceLoop` (`Chain.scala`): it always
   operates on a `mutable.TreeMap` ordered by the SAME fixed total order (`Ordering[CellT]`, i.e.
   `stream.filtrationOrdering`), so the reduced boundary matrix in any two dimensions is canonical -- provably
   independent of processing order, not just empirically likely to agree. A union-find pass over the identical
   cells, in the stream's own filtration order, computes exactly this same canonical answer for dimensions 0/1.
2. Whether `markActiveEntries`/`eliminationFallback` need an `R` entry for a union-find-resolved cell. Traced
   every read site: `markColumn(k)` only iterates `R.keys` (a cell with no `R` entry is simply never visited as
   `k`), and when a higher cell's own `R_k` chain contains a lower cell `i`, `killer.get(i)` is only consulted
   when `i ∈ cleared` -- never for a cell that's merely `paired` or merely `essentialSimplices`. Confirmed: no
   `R` entry is needed for a cycle-forming ("survivor") edge.
3. Which dimension the main `advanceAll` loops should resume from. Resolved: dimension 0 (vertices) was
   ALREADY free in the existing code (a vertex's boundary is always empty, so `processCell` on one is a
   trivial no-op branch) -- the real cost is dimension 1 (edges), most of which are cycle-forming and get
   reduced all the way to zero through the general machinery today. So both loops resume at delta=2, not
   delta=1: nothing needs edges to pass through `processCell` again after the union-find pre-pass.

A fourth, separate check not on advisor's list but needed for correctness: `diagramAt` calls `advanceAll()`
unconditionally on every invocation, and every other mutation in the existing class is behind a per-cell
idempotency guard (`processCell` no-ops on an already-resolved cell; `compress`/`globalReduce` only touch cells
not yet resolved) specifically so repeated `advanceAll` calls on the same state are safe. A naive union-find
pre-pass has no such per-cell guard of its own (it always re-derives the same pairs from scratch), so a second
`diagramAt` call would silently append a duplicate bar to `barcode(0)` per pair, every time. Fixed with a
one-shot `dim01Resolved: Boolean` flag on `HomologyState`.

## What was built

`HomologyState.unionFindDim01()` (`Homology.scala`, `CellularPersistenceInChunksContext`): builds a plain
`Array[Int]` parent array over the stream's dimension-0 cells (indexed via a local `vertexIndex` map, not the
class's own `cellIndex`, to avoid sizing the array to the full cell count when only vertices need slots -- a
deliberate memory-hygiene choice given the capacity sweep found memory, not time, is the actual ceiling at
scale), with path-compressing `find`. For each dimension-1 cell, in the stream's own filtration order: find
both boundary vertices' roots; if they agree, the edge is cycle-forming (`essentialSimplices += edge`,
matching exactly what `processCell`'s own `isZero` branch would have left it as); if they differ, the smaller
(younger) root under `Ordering[CellT]` dies (`cleared`, `paired`, `killer`, `barcode(0)` all populated
directly, matching `recordPair`'s output shape). After all edges are processed, any vertex still its own root
is marked essential (an H0 class that survives to infinity).

Two things this deliberately does NOT populate, both confirmed dead by the trace above: `boundaries` at a
vertex key (only edges ever appear in a higher cell's own `.boundary`, never vertices -- a killed vertex's
entry would only ever be read by another dimension-1 cell's own reduction, which the pre-pass replaces
entirely) and `R` for a survivor edge. `barcode(0)`'s stored chain is `Chain.empty` rather than the actual
2-term reduced chain `recordPair` would have stored -- checked first that nothing reads it: `diagramAt`
discards the third tuple element entirely, and grepping every `.barcode` read site in the test tree found only
`SimplicialHomologyByDimensionSpec` reading a DIFFERENT class's state, also discarding the chain component.

Generic over `CellT: OrderedCell` by construction -- the only per-cell operation is `edge.boundary[CoefficientT]`,
already the generic interface every engine in this file goes through. A dimension-1 cell's boundary always has
exactly two terms for every concrete `OrderedCell` in this codebase (two distinct vertices for `Simplex`/`Cube`;
for `FiniteSimplicialSet`, also always two terms, since a dimension-0 element can never be degenerate -- there's
no dimension below 0 to degenerate from -- though the two terms can reference the SAME vertex, e.g. a self-loop
edge like `minimalSphere(1)`'s single edge). Comparing roots after `find`, not raw endpoints, handles the
self-loop case with no special branch: both endpoints resolve to the same root immediately, correctly read as
cycle-forming.

`advanceAll()` now calls `unionFindDim01()` up front and both of its own loops (local `processCell`, global
`compress`/`globalReduce`) start their `delta` range at 2 instead of 0 -- `internalMaxDim.to(2, -1)` degrades
to an empty range when `internalMaxDim < 2` (e.g. `maxDim=0`), matching Scala's own `Range` semantics for a
backwards-impossible range, so no special case was needed for small `maxDim`.

## Validation

Ran the direct consumers first, not the full suite blind: `PersistenceInChunksSpec` (all 7 hand-derived
fixtures, including the pinned `tetrahedronBoundaryDegenerateCells` degenerate-S² fixture and the
elder-rule-discriminator fixture -- exactly the tie-heavy cases most likely to expose an order-dependent bug),
`HomologySpec` (naive-vs-chunks cross-validation), `CubicalStreamSpec` (`CellularPersistenceInChunksContext[Cube,
...]` against the naive engine and its own structural invariant on tie-heavy fixtures),
`FilteredSimplicialSetStreamSpec` (the torus's non-dimension-aligned filtration -- the fixture specifically
built to discriminate a reversed `filtrationOrdering`), `SimplicialSetConstructionsSpec`, and
`SimplicialHomologyByDimensionSpec` (kept as the independent oracle, unchanged, per advisor's explicit caution
not to delete it in the same change). All 43 examples across these six specs passed with zero changes to
expected output.

Full `sbt test` run separately; see the end of this worklog for the final tally once it completes.

## Follow-up: representatives (2026-09-20, same day)

Prompted by the project lead stating a standing design principle (now in `.claude/CLAUDE.md`, right before
"### Algebraic core": every homology engine should support flexible coefficients AND return representatives;
every public interface should expose them). Auditing the codebase against it surfaced that chunks had zero
representative-cycle API at all -- `diagramAt` discarded every bar's chain data outright -- a pre-existing gap,
not something the union-find work above introduced, but the union-find pass's own `Chain.empty` placeholder for
dimension-0 bars made it slightly more entrenched (the general machinery it replaced at least stored a chain,
even though nothing read it).

**Advisor's staged plan, followed in order**: (1) dimension 0/1 via union-find -- trivial, no new machinery,
since `Chain(dyingVertex)`/`Chain(rootVertex)` is exactly what `CellularHomologyContext`'s own `cycles` map
holds at dimension 0. (2) finite bars dim >= 2 (needs `reduceByUntil`'s reduction-log captured at three call
sites plus a `coboundaries`-shaped map). (3) essential bars dim >= 2 (needs the `cycles` map itself). (4) MATLAB
facade (mechanical once 1-3 land). Explicitly NOT doing 2 and 3 in one pass with 1.

**What shipped this session: step 1 and step 4, scoped honestly to match**. `unionFindDim01`'s finite-bar tuple
now stores `Chain(dyingVertex)` (was `Chain.empty`); a new `essentialRepresentatives: mutable.Map[CellT,
Chain[CellT, CoefficientT]]` field, populated only for surviving root vertices, gives essential dimension-0
classes a representative too. A new `barcodeAt(f: Double): List[PersistenceBar[Double, Chain[CellT,
CoefficientT]]]` mirrors `CellularHomologyContext.barcodeAt`'s exact output shape, but is honest about scope:
`dim == 0` is used as a proxy for "populated by `unionFindDim01`, hence trustworthy" (safe today since that
method is the *only* populator of `barcode(0)`); every other bar (dim >= 1 finite -- still routed through the
general machinery, which stores a chain proven to be *a* valid cycle by the boundary-of-a-boundary argument but
NOT verified to match what `CellularHomologyContext`'s own `cycles`/`coboundaries` mechanism would report for
the same bar -- and every essential bar above dimension 0, which has no tracking mechanism at all) reports
`annotation = None`, matching `PersistenceBar`'s existing `Option`-based design rather than fabricating a value.

**Verified, not just asserted**: a new `HomologySpec` test cross-checks chunks' dimension-0 representatives
against `CellularHomologyContext`'s own `barcodeAt` for EXACT equality (not homology-equivalence) on the
elder-rule fixture (the tie-heavy, sign-discriminating one already used elsewhere) plus triangle/tetrahedron --
this is the check advisor specifically flagged as necessary, since `dsigmaReduced`-shaped chains and the naive
engine's `cycles`-map chains are homologous but not generally equal, and a homology-only check would have missed
a real divergence.

**MATLAB facade wiring (step 4) was mechanical, as predicted**: `fromBars` already handled `Option[Chain]`
gracefully (`Some` -> decode, `None` -> a per-bar `UnsupportedOperationException`, not a blanket one) from its
existing `engine="ripser"` apparent-pairs-shortcut case, so `engine="chunks"` just needed to call `barcodeAt`
instead of `diagramAt` and reuse the exact same `fromBars` path `"ripser"`/`"naive"` already use -- no new
plumbing. `fromDiagram` (the old chunks-only, always-throws path) is now dead and was deleted, not left as an
unused fallback. Updated `Tda4jSpec`'s old "explicitly refuse for engine=chunks" test (which hardcoded bar index
0 as "always throws," now false) into two tests: one confirming dimension-0 bars are readable, one confirming
dimension >= 1 bars still explicitly refuse (found via `dimension(i)`, not a hardcoded index, since bar order
isn't guaranteed by dimension).

**What's still open, explicitly** (steps 2/3 from advisor's plan, not started): finite bars at dimension >= 1
and every essential bar above dimension 0 still report `None`. Closing this needs the same `reductionLog` ->
`coboundaries` -> `cycles` incremental mechanism `CellularHomologyContext`/`SimplicialHomologyByDimensionContext`
already use, ported into the chunked local/global algorithm -- advisor flagged a real, not-yet-investigated
blocker before attempting this: the chunked algorithm's local pass can defer a pair's resolution past the local
`stop` window into a later global pass, so a substituted cell's `coboundaries` entry may not exist yet at
substitution time, unlike the naive engine's single sequential sweep where presence is guaranteed. Whether
representative assembly needs to happen only during global passes, or be deferred to a dedicated post-`advanceAll`
pass over the recorded pairs, is an open question for that future session, not decided here.

## What's deliberately not done in this pass

- `SimplicialHomologyByDimensionContext` is NOT deleted yet, on purpose -- kept as the independent
  cross-validation oracle per advisor's caution (doubly so now: it's also the only worked example of the
  `coboundaries`/`cycles` mechanism steps 2/3 will need), to be removed in its own commit once broader fuzz
  validation (100+ random Vietoris-Rips clouds, matching that class's own existing coverage) has run against the
  union-find path.
- Dual union-find for the top dimension (cubical-specific, opt-in) is not started -- scoped as a separate
  phase in `DESIGN-unionfind-in-chunks.md`, after this phase is measured.
- No re-run of the capacity sweep yet to measure the real wall-clock/memory improvement -- the sweep's own
  methodology (`WORKLOG-cubical-capacity-sweep.md`) is the natural next step once broader correctness
  validation is done, not before.
- No lookup-table/local-pruning constant-factor optimizations from Flash Cubical -- real, but layered on top
  of, not required for, this change.
- Full `sbt test` was run twice this session with the default 1GB sbt heap and hung/OOM'd both times on
  `DimensionCeilingBenchmarkSpec`'s "VR-Enum+Naive H=4(sparse)" case -- a pre-existing environmental issue (that
  spec is deliberately not `skipAll`'d, per its own comment, and the accumulated suite has grown past what a 1GB
  heap comfortably covers), not something this session's changes caused. `sbt -J-Xmx4g test` runs clean. Worth
  fixing the default heap or that spec's own memory footprint in a future session, flagged here rather than
  silently worked around every time.
