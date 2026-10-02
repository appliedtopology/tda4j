---
layout: main
title: Persistence engines: what to trust, and why
---

`homology/Homology.scala`, `homology/PackedRipserCohomology.scala`, `homology/Cohomology.scala`,
`homology/FastCubicalHomology.scala`, and `homology/FastAlphaHomology.scala` contain **five
independently-implemented algorithms across seven concrete classes** (the last two classes share one
algorithm — a dual-graph union-find via Alexander duality — applied to two different cell types, the same
"one algorithm, several concrete classes" relationship engines 3/4 already have). They share the `Chain`
reduction primitives from [Architecture](architecture.md), but they are not variants of one shared engine — a
fix or bug found in one does not imply anything about the others. Read this page before choosing which engine
to build on.

Three of the five (`CellularHomologyEngine`, `CellularPersistenceInChunksEngine`, `CellularCohomologyEngine`)
additionally implement the common `homology.PersistenceEngine[CellT, C]` trait (`def barcode(stream): List[
PersistenceBar[Double, Chain[CellT, C]]]`) via `PersistenceEngine.naive`/`.chunks`/`.cohomology` factories — a
thin, opt-in adapter over each engine's own incremental API, for a caller (the MATLAB/CLI facade) that just
wants a finished barcode without hand-writing each engine's own construct/advance/read dance. It does not
change any engine's own contract; the incremental API (`advanceTo`/`diagramAt`/`barcodeAt`) stays available on
the concrete class. `PackedRipserCohomologyEngine`/`RipserCohomologyEngine` don't implement it — they consume
a `FiniteMetricSpace[Int]` directly, not a stream (see engine 4 below).

## 1. `CellularHomologyEngine` / `SimplicialHomologyEngine` — reference-grade, generic

`CellularHomologyEngine[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]` is the naive
single-pivot-table boundary-reduction algorithm: process cells in filtration order, reduce each cell's
boundary against pivots recorded so far via `Chain.reduceBy`, and the cell either opens a class (reduced
boundary is zero) or closes one. No clearing, no chunking, no cohomology/twist optimization. Generic over
*any* `CellT: OrderedCell` — this is what makes it the engine `Cube` and `FiniteSimplicialSet` slot into
with no new engine code (`CubicalHomologyEngine`, `SimplicialHomologyEngine` are one-line specializations
of it). It's the **oracle every other engine here gets cross-validated against**.

It's also the only engine with genuine incremental querying: `HomologyState.advanceOne()`/`advanceTo(f)`/
`advanceAll()`, and `diagramAt(f)`/`barcodeAt(f)` can be called mid-stream to get the diagram *as of*
filtration value `f`. `barcodeAt` annotates every bar with a real representative cycle, tracked via a
parallel V-column alongside the ordinary reduction.

## 2. `CellularPersistenceInChunksEngine` / `PersistenceInChunksEngine` — chunked, generic

`CellularPersistenceInChunksEngine[CellT: OrderedCell, CoefficientT: Field](maxDim: Int = 5)` implements the
parallelizable "clear-and-compress" algorithm: local reduction per chunk, active-entry marking, then global
column compression/reduction. `PersistenceInChunksEngine[VertexT, CoefficientT]` is a one-line
`Simplex`-specialized subclass, mirroring `SimplicialHomologyEngine`'s relationship to engine 1 — the class
has no `Simplex`-specific behavior anywhere in its body, so any `OrderedCell` slots in directly.

Dimensions 0 and 1 go through a dedicated union-find fast path (`unionFindDim01`) instead of the general
`Chain`-based reduction, since both dimensions are mathematically forced to agree with the general machinery
on a fixed total order — a real, measured win when a large point cloud's dimension-0/1 structure dominates
the complex. `barcodeAt(f)` returns a real representative for **every** bar, at every dimension, including
essential classes — reconstructed incrementally from this class's own already-computed
`boundaries`/`cleared`/`paired`/`killer` state (`vcolOf`, memoized) rather than by delegating to a second
engine run. `advanceAll()` runs the whole pipeline in one shot; there is no incremental querying the way
engine 1 has.

## 3. `RipserCohomologyEngine` — test/reference oracle for engine 4

Persistent *co*homology via Ulrich Bauer's Ripser algorithm (arXiv:1908.02518), specialized to
`Simplex[Int]` Vietoris-Rips/clique complexes via `SimplexIndexing`'s combinatorial number system — a
deliberate narrowing from the generic `CellT: OrderedCell` engines above. One-shot only
(`persistentCohomology()`, no incremental querying).

**This class is not what production code calls.** `PackedRipserCohomologyEngine` (engine 4) is a faithful
re-keying of the same algorithm onto a packed representation, and is what `matlab.TDA4j`'s
`engine="ripser"` actually uses. This class's remaining value is narrower than "an independent check on the
Ripser algorithm": both classes share `SimplexIndexing`, so a bug there passes both silently (engine 1 is the
actually-independent oracle for the algorithm itself). What this class *does* catch is anything specific to
engine 4's own packed representation — its `DiameterIndex` carrier's index-only `equals`/`hashCode`, its
index-keyed lookup maps — that no other spec would flag. Its `Simplex[Int]`-keyed chains are also more
directly legible for hand-debugging. Kept fully maintained; don't add new production call sites against it.

Structural points worth knowing:

- **Clearing is required for correctness, not merely an optimization**: a simplex
  already used as a pivot in the previous dimension must be excluded from the
  next dimension's essential-index count.
- **Apparent pairs are partially implemented**: mutual apparent pairs can bypass
  reduction, but the implementation still constructs the full coboundary.

## 4. `PackedRipserCohomologyEngine` — the production Ripser engine

Same algorithm as engine 3, method for method, keyed on a packed `(Double, Long)` diameter/combinatorial-
index pair (`DiameterIndex`) instead of a materialized `Simplex[Int]`. This is what `matlab.TDA4j`'s
`engine="ripser"` calls, and the fastest, most memory-efficient engine in the library — a deliberate
representation choice on top of an already-validated algorithm, not a new algorithm. Kept in its own file,
separate from the three generic-`OrderedCell` algorithms in `Homology.scala`, since it's specific to
Vietoris-Rips/`SimplexIndexing` rather than a general engine.

`DiameterIndex` overrides `equals`/`hashCode` to consider only the combinatorial index, not the diameter —
so "same simplex" is true by construction regardless of which floating-point path computed its diameter,
sidestepping a real footgun (two carriers for the same simplex comparing unequal on floating-point noise).

## 5. `CellularCohomologyEngine` — generic cohomology, for every cell type

Persistent *co*homology, generic over `CellT: OrderedCell` — the cohomology counterpart to engine 1, filling
in what used to be a real asymmetry: cohomology in this codebase meant engines 3/4 only, both hardcoded to
`Simplex[Int]`. `Cube`, `FiniteSimplicialSet` generators, and `Simplex[Int]` complexes that aren't
Vietoris-Rips (Cech, Alpha, the general witness complex) had no cohomology option at all before this class —
not even Cech/Alpha, despite sharing `Simplex[Int]` as a cell type, since engines 3/4's speed optimizations
(`insertionDiameter`, apparent pairs) are proven specifically for the max-pairwise-distance functional, not
Cech's circumradius, Alpha's own filtration, or the general witness complex's per-dimension threshold. (The
*lazy* witness complex is the one exception among these: it genuinely IS a max-pairwise-distance flag
complex under its own `WitnessMetricSpace`, so engines 3/4 both work for it directly — see
`architecture.md`'s "Witness complexes" section.)

**The key idea**: the coboundary matrix persistent cohomology reduces is the transpose of the ordinary
boundary matrix, same coefficients. Every stream this class targets already gets fully materialized before
persistence runs (unlike Vietoris-Rips at the scale engines 3/4 target), so this class builds the coboundary
relation directly by inverting each materialized cell's own `boundary[CoefficientT]` call, one dimension band
at a time (built, used, and discarded before moving to the next dimension, bounding peak memory to the
largest single band) — no `SimplexIndexing`-style combinatorial machinery, and no per-cell-type coboundary
formula.

**No `maxDim` parameter, and no apparent pairs — both deliberate.** The engine has no `maxDim` parameter; callers truncate the input stream when
needed. Apparent-pair optimizations are not used because the coboundary
relation is already materialized explicitly.

**Representatives**: Every bar carries a V-column. Essential bars yield genuine cocycles over the
full complex; finite bars carry representatives valid over their persistence
interval.

## 6. `FastCubicalHomologyEngine` — dual-graph union-find

`FastCubicalHomologyEngine` is specialized to `CubicalGridStream`.

It computes `H_0` by primal union-find and `H_{d-1}` by applying Alexander
duality to a dual graph and running union-find in reverse filtration order.
For ambient dimension `d >= 3`, intermediate dimensions
`1 <= k <= d-2` fall back to `CellularPersistenceInChunksEngine`.

The engine is valid for ambient dimension `>= 2` and avoids general `Chain` reduction in the two shortcut dimensions, `H_0` and `H_{d-1}`.

Representatives are reconstructed from the active dual components so the
result remains compatible with the rest of the persistence API.

## 7. `FastAlphaHomologyEngine` — dual union-find for alpha complexes

`FastAlphaHomologyEngine` applies the same dual-graph union-find strategy as
`FastCubicalHomologyEngine` to the triangulation produced by `HelixDelaunay`.

It requires the full `HelixDelaunay` triangulation and is not compatible with
the DQP alpha backends. As with the cubical engine, `H_0` and `H_{d-1}` use
union-find and intermediate dimensions fall back to
`PersistenceInChunksEngine`.

The dual construction assumes each facet has at most two cofaces.
`HelixDelaunay` can violate this in rare degenerate configurations, so the
engine validates the triangulation and throws `FastAlphaTriangulationException`
rather than returning an invalid result.

See [Alpha complex](alpha-complex.md) for backend limitations and triangulation
repair.

## Streams × engines: what works with what

Not every engine supports every complex construction. `ripser` relies on
Vietoris-Rips-specific assumptions about the filtration and representation,
while `fast-cubical` and `fast-alpha` operate only on their corresponding
concrete constructions. The generic engines (`naive`, `chunks`, and
`cohomology`) support a broader set of streams, subject to the exceptions
listed below.

| Complex (`complex=`)         | Default engine | `ripser`                                            | `naive` | `chunks`                                          | `cohomology` | `fast-cubical` | `fast-alpha` |
|-------------------------------|-----------------|------------------------------------------------------|---------|-----------------------------------------------------|--------------|----------------|--------------|
| `vr`                          | `ripser`        | yes                                                    | yes     | yes                                                   | yes          | **no**         | **no**       |
| `cech`                        | `naive`         | **no** — not a max-pairwise-distance functional (radius, not diameter) | yes | yes | yes          | **no**       | **no**       |
| `witness`, `witnessVariant=lazy`    | `ripser`  | yes — genuinely a flag complex under its own reified `WitnessMetricSpace` | yes | yes | yes | **no** | **no** |
| `witness`, `witnessVariant=general` | `naive`   | **no** — not a flag complex (dimension-specific threshold) | yes | **no** | yes | **no** | **no** |
| `dtm-rips`                    | `naive`         | **no** — vertices aren't born at 0, and the weighted-Rips functional isn't `insertionDiameter`-compatible | yes | yes | yes | **no** | **no** |
| `dtm-alpha`                   | `naive`         | **no** — no notion of a Vietoris-Rips complex at all   | yes     | **no** — shares `AlphaComplexDQP`'s known stall/OOM risk (see `alpha-complex.md`) | yes | **no** | **no** — always uses `AlphaComplexDQP`, never `HelixDelaunay` |
| `alpha`                       | `naive`         | **no** — no notion of a Vietoris-Rips complex at all   | yes     | **no** — known stall/OOM risk (`HomologySpec`'s `BarcodeRegressionSpec`) | yes | **no** | **yes** — only `alphaBackend=helix` (the default), any ambient dimension `>= 2` |
| `sheehy-rips`                 | `naive`         | **no** — a simplex's value is not the maximum ambient pairwise distance among its vertices (some pairs sparsified away, others excluded outright) | yes | yes | yes | **no** | **no** |
| cubical (`computeFromCubicalImage`/`computeFromImage`, no `complex` key) | `naive` | **no** — `PackedRipserCohomologyEngine` is specialized to `Simplex[Int]` | yes | yes | yes | **yes** — any ambient dimension `>= 2` | **no** |
| Dowker relation (`computeFromRelation`, no `complex` key) | `naive` | **no** — not a flag complex (a witness for a whole simplex need not witness any of its edges) | yes | **no** — same conservative refusal `witness`/general has (use `naive`/`cohomology`) | yes | **no** | **no** |
| simplicial sets               | *(no `matlab`/`cli` entry point at all — construct `SimplicialSetStream`/`FilteredSimplicialSetStream` and drive any generic engine directly)* | | | | | | |

Unsupported combinations fall into a few categories:

- **Non-flag filtrations** — Cech, general witness, Dowker, and Sheehy
  cannot use VR-specific packed Ripser assumptions.
- **Non-VR constructions** — alpha complexes do not have the metric-space /
  clique representation the Ripser engines require.
- **Weighted flag filtrations** — DTM-Rips is combinatorially a flag complex,
  but its filtration is incompatible with Ripser's diameter-specific
  optimizations.
- **Representation-specific engines** — `fast-cubical` and `fast-alpha`
  operate only on their corresponding concrete constructions.

`engine="cohomology"` (`CellularCohomologyEngine`, engine 5 above) is the one column with no "no" cells for a
reason: it's generic over `CellT: OrderedCell` with no per-construction speed assumptions baked in, at the cost
of none of engines 3/4's Vietoris-Rips-specific optimizations — see engine 5's own section above for what that
tradeoff actually buys and costs.

## Choosing an engine

| Need | Engine |
|---|---|
| Exploration, intermediate-filtration queries, representative cycles, any `OrderedCell` type | **`CellularHomologyEngine`** |
| Large complex, chunked/parallelizable, representatives for every bar including essential ones | **`CellularPersistenceInChunksEngine`** |
| Fast, memory-efficient cohomology on a Vietoris-Rips/clique complex over integer vertex labels | **`PackedRipserCohomologyEngine`** (what `engine="ripser"` uses) |
| A `Simplex[Int]`-keyed reference implementation for hand-debugging engine 4 | `RipserCohomologyEngine` (test oracle, not a production choice) |
| Cohomology (real cocycle representatives) on `Cube`/`FiniteSimplicialSet`/Cech/Alpha/general witness complex, or any `OrderedCell` type engines 3/4 can't serve | **`CellularCohomologyEngine`** (what `engine="cohomology"` uses) |
| Fastest option for a cubical grid of any ambient dimension `>= 2` (no `Chain` reduction at all for `H_0`/`H_{d-1}`; a `chunks` hybrid for any residual middle dimensions at `d >= 3`) | **`FastCubicalHomologyEngine`** (what `engine="fast-cubical"` uses) |
| Fastest option for an alpha complex via `HelixDelaunay`, any ambient dimension `>= 2` (same hybrid shape as `FastCubicalHomologyEngine`; noticeably more likely to throw `FastAlphaTriangulationException` at higher ambient dimension/point count) | **`FastAlphaHomologyEngine`** (what `engine="fast-alpha"` uses) |
