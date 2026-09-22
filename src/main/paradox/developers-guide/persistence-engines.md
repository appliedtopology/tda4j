# Persistence engines: what to trust, and why

`homology/Homology.scala` and `homology/PackedRipserCohomology.scala` contain **four independently-implemented
algorithms across five concrete classes**. They share the `Chain` reduction primitives from
@ref:[Architecture](architecture.md), but they are not variants of one shared engine — a fix or bug found in
one does not imply anything about the others. Read this page before choosing which engine to build on.

## 1. `CellularHomologyContext` / `SimplicialHomologyContext` — reference-grade, generic

`CellularHomologyContext[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]` is the naive
single-pivot-table boundary-reduction algorithm: process cells in filtration order, reduce each cell's
boundary against pivots recorded so far via `Chain.reduceBy`, and the cell either opens a class (reduced
boundary is zero) or closes one. No clearing, no chunking, no cohomology/twist optimization. Generic over
*any* `CellT: OrderedCell` — this is what makes it the engine `Cube` and `FiniteSimplicialSet` slot into
with no new engine code (`CubicalHomologyContext`, `SimplicialHomologyContext` are one-line specializations
of it). It's the **oracle every other engine here gets cross-validated against**.

It's also the only engine with genuine incremental querying: `HomologyState.advanceOne()`/`advanceTo(f)`/
`advanceAll()`, and `diagramAt(f)`/`barcodeAt(f)` can be called mid-stream to get the diagram *as of*
filtration value `f`. `barcodeAt` annotates every bar with a real representative cycle, tracked via a
parallel V-column alongside the ordinary reduction. `TDAContext` (root `package.scala`) extends this class.

## 2. `CellularPersistenceInChunksContext` / `PersistenceInChunksContext` — chunked, generic

`CellularPersistenceInChunksContext[CellT: OrderedCell, CoefficientT: Field](maxDim: Int = 5)` implements the
parallelizable "clear-and-compress" algorithm: local reduction per chunk, active-entry marking, then global
column compression/reduction. `PersistenceInChunksContext[VertexT, CoefficientT]` is a one-line
`Simplex`-specialized subclass, mirroring `SimplicialHomologyContext`'s relationship to engine 1 — the class
has no `Simplex`-specific behavior anywhere in its body, so any `OrderedCell` slots in directly.

Dimensions 0 and 1 go through a dedicated union-find fast path (`unionFindDim01`) instead of the general
`Chain`-based reduction, since both dimensions are mathematically forced to agree with the general machinery
on a fixed total order — a real, measured win when a large point cloud's dimension-0/1 structure dominates
the complex. `barcodeAt(f)` returns a real representative for **every** bar, at every dimension, including
essential classes — reconstructed incrementally from this class's own already-computed
`boundaries`/`cleared`/`paired`/`killer` state (`vcolOf`, memoized) rather than by delegating to a second
engine run. `advanceAll()` runs the whole pipeline in one shot; there is no incremental querying the way
engine 1 has.

## 3. `SimplicialHomologyByDimensionContext` — a genuinely independent oracle, not a production choice

Dimension 0 and the births of dimension-1 classes via union-find/Kruskal's algorithm (the elder rule);
higher dimensions via the same boundary-reduction approach as engine 1, processed strictly dimension by
dimension. This is a real, independently-implemented algorithm (not a variant of the reduction engines
above) — cross-validated against `SimplicialHomologyContext` on birth/death values across hundreds of random
Vietoris-Rips point clouds, agreeing exactly.

**Two caveats before reaching for it**: its own representative *chains* (`cycles`/`coboundaries`) have been
checked directly and found to be non-cycles (`boundary(rep) != 0`) for every dimension-≥1 bar tested — don't
expose or trust its representatives without fixing that first; birth/death *values* are unaffected. And it
is not wired into either production engine as a performance fast path — a dedicated benchmark found the
dominant cost elsewhere (an uncached filtration value, since fixed at the source), so a raw union-find port
was judged not worth the correctness risk to engine 2's own representative-tracking state. Its role is as an
independent cross-check when you specifically want a second algorithm's agreement, not as something to route
real work through.

## 4. `RipserCohomologyContext` — test/reference oracle for engine 5

Persistent *co*homology via Ulrich Bauer's Ripser algorithm (arXiv:1908.02518), specialized to
`Simplex[Int]` Vietoris-Rips/clique complexes via `SimplexIndexing`'s combinatorial number system — a
deliberate narrowing from the generic `CellT: OrderedCell` engines above. One-shot only
(`persistentCohomology()`, no incremental querying).

**This class is not what production code calls.** `PackedRipserCohomologyContext` (engine 5) is a faithful
re-keying of the same algorithm onto a packed representation, and is what `matlab.TDA4j`'s
`engine="ripser"` actually uses. This class's remaining value is narrower than "an independent check on the
Ripser algorithm": both classes share `SimplexIndexing`, so a bug there passes both silently (engine 1 is the
actually-independent oracle for the algorithm itself). What this class *does* catch is anything specific to
engine 5's own packed representation — its `DiameterIndex` carrier's index-only `equals`/`hashCode`, its
index-keyed lookup maps — that no other spec would flag. Its `Simplex[Int]`-keyed chains are also more
directly legible for hand-debugging. Kept fully maintained; don't add new production call sites against it.

Structural points worth knowing:

- **Clearing is required for correctness, not an optional speedup**: a simplex already claimed as a pivot
  one dimension down must be excluded from the next dimension's essential-index count entirely, not merely
  checked for "does its own column reduce to zero" — Definition 3.2/Proposition 3.1 of the paper.
- **Apparent pairs (Definition 3.2/Proposition 3.9) are partially implemented**: a genuine mutual
  apparent-pair check identifies the pivot directly and skips `Chain.reduceBy`'s reduction pass for it (a
  measured 1.35x-1.8x win, growing with complex size). What is *not* implemented is Ripser's further
  optimization of never building an apparent simplex's coboundary at all — `coboundaryOf(sigma)` is still
  called in full on the shortcut path, since `basis(tau)` needs the complete reduced column, not a truncated
  stand-in. Getting the fully lazy version would also require restructuring how each dimension's candidate
  simplices are enumerated in the first place (currently eager, unlike Ripser's own incrementally-assembled
  `columns_to_reduce`) — a larger, separate project.

Don't confuse this with `RipserStreamBase`'s `zeroApparentCofacet`/`zeroApparentFacet` (see
@ref:[Architecture](architecture.md)) — those are a *stream-generation-time* filter built on a restricted
cofacet iterator, a different mechanism serving a different purpose (skip generating a simplex at all, not
skip a reduction step).

## 5. `PackedRipserCohomologyContext` — the production Ripser engine

Same algorithm as engine 4, method for method, keyed on a packed `(Double, Long)` diameter/combinatorial-
index pair (`DiameterIndex`) instead of a materialized `Simplex[Int]`. This is what `matlab.TDA4j`'s
`engine="ripser"` calls, and the fastest, most memory-efficient engine in the library — a deliberate
representation choice on top of an already-validated algorithm, not a new algorithm. Kept in its own file,
separate from the four generic-`OrderedCell` algorithms in `Homology.scala`, since it's specific to
Vietoris-Rips/`SimplexIndexing` rather than a general engine.

`DiameterIndex` overrides `equals`/`hashCode` to consider only the combinatorial index, not the diameter —
so "same simplex" is true by construction regardless of which floating-point path computed its diameter,
sidestepping a real footgun (two carriers for the same simplex comparing unequal on floating-point noise).

## Choosing an engine

| Need | Engine |
|---|---|
| Exploration, intermediate-filtration queries, representative cycles, any `OrderedCell` type | **`CellularHomologyContext`**/`TDAContext` |
| Large complex, chunked/parallelizable, representatives for every bar including essential ones | **`CellularPersistenceInChunksContext`** |
| A second, independently-implemented algorithm to cross-check birth/death values against | **`SimplicialHomologyByDimensionContext`** (values only — see its caveats above) |
| Fast, memory-efficient cohomology on a Vietoris-Rips/clique complex over integer vertex labels | **`PackedRipserCohomologyContext`** (what `engine="ripser"` uses) |
| A `Simplex[Int]`-keyed reference implementation for hand-debugging engine 5 | `RipserCohomologyContext` (test oracle, not a production choice) |

## Dead/experimental code kept intentionally

Root test sources' `SimplicialSetSpec.scala` is entirely commented out — an earlier, from-scratch sketch of
a simplicial-set representation, predating and unrelated to the real `FiniteSimplicialSet` now in `cells`
(see @ref:[Architecture](architecture.md)). Kept as historical record, not wired into anything; don't delete
without checking with the maintainer first.
