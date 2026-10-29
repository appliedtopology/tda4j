---
layout: main
title: Persistence engines: what to trust, and why
---

The engines live in `homology/` (`Homology.scala`, `PackedRipserCohomology.scala`, `Cohomology.scala`,
`FastCubicalHomology.scala`, `FastAlphaHomology.scala`, `PackedCubicalCohomology.scala`): five algorithms in several
concrete classes. Engines 3 and 4 are one algorithm in two representations, and the two fast engines share one
algorithm (a dual-graph union-find by Alexander duality) on two cell types. They share the `Chain` reduction primitives
from [Architecture](architecture.md) but are not variants of one engine: a fix or bug in one says nothing about the
others. Read this page before choosing which to build on.

`PackedCubicalCohomologyEngine` is engine 4's algorithm (clearing, apparent pairs, a packed involution) on a cubical
grid's own cell indices, with no `Cube` or `Chain` inside the reduction. It must equal engine 5 on any
`CubicalGridStream`, bar for bar and term for term. `Engine.Cohomology` and engine 6's middle degrees run it on a grid.
A reduced column is stored as its cells' keys and coefficients (the cycle of a reported bar is that column), an apparent
pair's column is not stored at all, and the tables indexed by cell are dense `Int` arrays.

`CellularHomologyEngine`, `CellularPersistenceInChunksEngine` and `CellularCohomologyEngine` also implement the
`PersistenceEngine[CellT, C]` trait (`def barcode(stream): List[PersistenceBar[Double, Chain[CellT, C]]]`) through the
`PersistenceEngine.naive`/`.chunks`/`.cohomology` factories: an adapter for callers, such as the MATLAB and CLI facade,
that want a finished barcode. Each engine's incremental API (`advanceTo`/`diagramAt`/`barcodeAt`) stays available on
the concrete class. The Ripser engines take a `FiniteMetricSpace[Int]` rather than a stream and do not implement it.

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

`persistentHomology(stream, maxDimension)` caps the cursor at a homological degree. Cells above dimension
`maxDimension + 1` are neither sorted nor reduced. A cell of dimension `maxDimension + 1` can only close a class of
degree `maxDimension`, so it is reduced only while such a class is open, and it leaves nothing but the pairing it makes
(no V-column, no class of degree `maxDimension + 1`). The bars of degrees `0 .. maxDimension` and their representatives
are those of the cursor on the whole complex. The one-argument form reads a stream that has no `homologyDegreeLimit`
whole, and a stream cut off at degree `k` (`VietorisRips(points, maxDimension = k)`) up to `k`, which is also what a
larger explicit `maxDimension` comes to: the cells above `k` close nothing, so their unpaired ones are not homology.
`Persistence` uses the capped cursor, and so does `PersistenceEngine.naive`.

## 2. `CellularPersistenceInChunksEngine` / `PersistenceInChunksEngine` — chunked, generic

`CellularPersistenceInChunksEngine[CellT: OrderedCell, CoefficientT: Field](maxDim: Int = 5)` implements the
parallelizable "clear-and-compress" algorithm: local reduction per chunk, active-entry marking, then global
column compression/reduction. `PersistenceInChunksEngine[VertexT, CoefficientT]` is a one-line
`Simplex`-specialized subclass, mirroring `SimplicialHomologyEngine`'s relationship to engine 1 — the class
has no `Simplex`-specific behavior anywhere in its body, so any `OrderedCell` slots in directly.
`CubicalPersistenceInChunksEngine[CoefficientT]` is the same one-line subclass for `Cube`s.

Dimensions 0 and 1 go through a dedicated union-find fast path (`unionFindDim01`) instead of the general
`Chain`-based reduction, which gives the same result on a fixed total order and is much faster when a large point cloud's
dimension-0/1 structure dominates the complex. `barcodeAt(f)` returns a real representative for **every** bar, at every dimension, including
essential classes — reconstructed incrementally from this class's own already-computed
`boundaries`/`cleared`/`paired`/`killer` state (`vcolOf`, memoized) rather than by delegating to a second
engine run. `advanceAll()` runs the whole pipeline in one shot; there is no incremental querying the way
engine 1 has.

Like every engine that reads a stream, it reports no degree above the stream's `homologyDegreeLimit`, whatever `maxDim`
says: the default `maxDim = 5` on `VietorisRips(points, maxDimension = 2)` gives degrees `0..2`.

**What the union-find takes.** `unionFindDim01` sorts each edge by its
boundary over the engine's field (`edgeShape`):
- **zero** (no entries, a loop's `(v, c), (v, -c)`, or coefficients that
  vanish in the field): the edge closes a cycle by itself and is its own
  representative;
- **`c·(v - u)`** with `u ≠ v` and `c` a unit: the edge joins two components;
- **anything else** (one vertex with a nonzero coefficient, three vertices,
  coefficients that are not negatives of each other): union-find stops and
  clears what it recorded, and the general reduction runs from dimension 0.

Simplices, cubes and simplicial sets only produce the first two shapes. An
algebraic chain complex, such as a Morse complex over `F_p`, may produce the
third.

**Degree-1 representatives are built on request.** A cycle-closing edge's
representative is the edge minus the spanning-forest paths to its ends. It is
built when `barcodeAt` first asks for it, then memoized; `diagramAt` never
builds one. Keep the edge pass free of chains: a path chain per cycle-closing
edge would cost more than the union-find itself on a Vietoris-Rips complex.

**Top cells.** At `maxDim = 1`, once the union-find has taken degrees 0 and 1, the triangles are reduced in filtration
order against the number of degree-1 classes still open (the positive edges born at or before the triangle, minus the
triangles that have already closed one). A triangle that enters while none is open cannot close a class, so its boundary
reduces to zero and the engine does not reduce it. From `maxDim = 2` every top cell is reduced. Cells are compared by
rank: the stream's cells are sorted once by `filtrationOrdering`, which is cheaper than asking the stream's ordering at
every comparison. A stream in which some cell has no filtration value keeps its own ordering.

## 3. `RipserCohomologyEngine` — test/reference oracle for engine 4

Persistent *co*homology via Ulrich Bauer's Ripser algorithm (arXiv:1908.02518), specialized to
`Simplex[Int]` Vietoris-Rips/clique complexes via `SimplexIndexing`'s combinatorial number system — a
deliberate narrowing from the generic `CellT: OrderedCell` engines above. One-shot only
(`persistentCohomology()`, no incremental querying).

**Production code does not call this class.** `PackedRipserCohomologyEngine` (engine 4) is the same algorithm on a
packed representation, and is what `engine="ripser"` uses. Both share `SimplexIndexing`, so a bug there passes both;
engine 1 is the independent oracle for the algorithm. This class catches what is specific to engine 4's packed
representation (its `DiameterIndex` carrier's index-only `equals`/`hashCode`, its index-keyed lookup maps), and its
`Simplex[Int]`-keyed chains are easier to debug by hand. It is maintained; do not add production call sites.

Structural points worth knowing:

- **Clearing is required for correctness, not merely an optimization**: a simplex
  already used as a pivot in the previous dimension must be excluded from the
  next dimension's essential-index count.
- **Apparent pairs are partially implemented**: mutual apparent pairs can bypass
  reduction, but the implementation still constructs the full coboundary.

## 4. `PackedRipserCohomologyEngine` — the production Ripser engine

Same algorithm as engine 3, method for method, keyed on a packed diameter/combinatorial-index pair
(`DiameterIndex`) instead of a materialized `Simplex[Int]`. This is what `matlab.TDA4j`'s
`engine="ripser"` calls, and the fastest, most memory-efficient engine in the library — a deliberate
representation choice on top of an already-validated algorithm, not a new algorithm. Kept in its own file,
separate from the three generic-`OrderedCell` algorithms in `Homology.scala`, since it's specific to
Vietoris-Rips/`SimplexIndexing` rather than a general engine.

`DiameterIndex` overrides `equals`/`hashCode` to consider only the combinatorial index (both words), not the diameter —
so "same simplex" is true by construction regardless of which floating-point path computed its diameter,
so two carriers for the same simplex never compare unequal on floating-point noise.

**Index width.** The index of a simplex is one `Long` while C(n + d + 2, d + 2) fits in 63 bits for `maxDimension = d`
(16,000 points to degree 3, 122,000 to degree 2). Beyond that the engine numbers the simplices with two words
(`WideSimplexIndexing`, up to 126 bits; its cursors find the same cofacets and facets in the same order). The choice is
made once, in the constructor (`wideIndices` forces either, `usesWideIndices` reports it), and a run that fits in one
`Long` runs the one-word code. The second word exists only where it is used: a cell past 63 bits is a
`WideDiameterIndex` (a plain `DiameterIndex` has `high = 0` and no field for it), and the working column, the levels, the
pairing and a stored column keep high-word arrays only in a two-word run. The maps keyed by index (pivots, the cleared
set) are keyed by a dense number of the two-word index (`WideKeys`). Decode a returned cell with
`engine.vertices(cell, size)`, which reads either width.

**Reduction internals.** The column being reduced is Ripser's working column: a binary heap of
(diameter, index, coefficient) entries in primitive arrays, to which adding a column only pushes entries; equal
cells are combined when they reach the top. Coboundaries and boundaries are pushed straight from the
`SimplexIndexing` cursors, never built as `Chain`s. Under a threshold that keeps few of the pairs, a simplex's
cofacets are found by intersecting its vertices' lists of neighbours within the threshold (built once, with each
distance stored) rather than by trying every vertex; the lists give the same cofacets in the same order. Per-simplex
bookkeeping is kept in primitive arrays (each degree's simplices, the pairing, the cleared indices), and a
`PersistenceBar` is built only for a bar that is returned; in the involution, an apparent pair's boundary column is
rebuilt from its death simplex when needed rather than stored. A stored reduced column keeps its pivot first and the
remaining terms uncombined. An emergent pair (Bauer's Definition 3.11) is a column whose oldest cofacet of the same
diameter is the pivot of no earlier column (no stored column, no emergent pair, and not the apparent partner of that
cofacet's youngest facet): its coboundary is reduced as it stands, so the engine pairs it at once without putting its
cofacets on the working column, and keeps only the simplex, from which the column is rebuilt in the rare case that a
later column needs it. `useEmergentPairs = false` turns the shortcut off; no output changes. Cocycles (the V-columns) are recorded as a reduction log per column and expanded only
for the bars that are reported, so `persistentCohomology()` builds none for zero-length bars and
`persistentHomology()` builds none at all. Cycles come from the engine's own specialization of `Involution.cycles`
on the same heap, which returns exactly what the generic involution returns. The heap relies on a simplex having
one diameter: a metric that is not symmetric by construction (anything but `EuclideanMetricSpace` and
`ExplicitMetricSpace`, which reads its lower triangle) is read as `d(max(i, j), min(i, j))`, the same triangle.

**Counters.** Engines 3 and 4 report what their last run did:
- `apparentPairCount`, the simplices paired as apparent pairs with no reduction
  of their coboundary;
- `emergentPairCount` (engine 4 only), the simplices paired as emergent pairs;
- `substitutionCount`, how often another column needed an apparent pair's
  column as a pivot and had it rebuilt;
- `totalSimplexCount`, the simplices the run assembled over all dimensions.

They explain a speed difference between two inputs of the same size: a high
apparent-pair rate means little reduction work. They are diagnostics, not
part of any result.

## 5. `CellularCohomologyEngine` — generic cohomology, for every cell type

Persistent *co*homology, generic over `CellT: OrderedCell`: the cohomology counterpart to engine 1. It serves every
cell type: `Cube`, `FiniteSimplicialSet` generators, and the `Simplex[Int]` complexes that are not Vietoris-Rips
(Cech, Alpha, the general witness complex). Engines 3 and 4 cannot: their speed optimizations (`insertionDiameter`,
apparent pairs) are proven only for the max-pairwise-distance functional, not for Cech's circumradius, Alpha's own
filtration, or the general witness complex's per-dimension threshold. The lazy witness complex is the exception: it is
a max-pairwise-distance flag complex under its own `WitnessMetricSpace`, so engines 3 and 4 work for it (see
"Witness complexes" in `architecture.md`).

**The key idea**: the coboundary matrix persistent cohomology reduces is the transpose of the ordinary
boundary matrix, same coefficients. Every stream this class targets already gets fully materialized before
persistence runs (unlike Vietoris-Rips at the scale engines 3/4 target), so this class builds the coboundary
relation directly by inverting each materialized cell's own `boundary[CoefficientT]` call, one dimension band
at a time (built, used, and discarded before moving to the next dimension, bounding peak memory to the
largest single band) — no `SimplexIndexing`-style combinatorial machinery, and no per-cell-type coboundary
formula.

**No `maxDim` parameter and no apparent pairs.** Callers truncate the input stream when needed, and the coboundary
relation is already materialized, so apparent-pair optimizations have nothing to save. A stream built for degrees
`0..k` says so in its `homologyDegreeLimit`; the engine then reports no bar above `k`, cocycles and cycles alike, because
the cells of degree `k + 1` that close no class are not the complex's homology and would each cost a bar and a
representative (`PackedCubicalCohomologyEngine` does the same on a truncated grid).

**Representatives**: Every bar carries a V-column. Essential bars yield genuine cocycles over the
full complex; finite bars carry representatives valid over their persistence
interval.

**Simplices on flat arrays.** When the cells are simplices with `Int` vertices and the cell instance is the library's
own (`simplexIsOrderedCell`, which every complex the library builds uses), the engine works on arrays instead of
`Simplex` objects: each dimension's vertex tuples in the engine's order, a hash table from a tuple to its cell number,
and each coboundary block as compressed columns of signs. A column whose first row is free is paired without building
anything. A column that needs an addition is reduced by `Chain.reduceBy` as before, with the columns it adds built on
demand, so the bars and representatives are exactly those of reducing the cells. Cycles take the same route: a death
cell whose youngest face is its birth cell needs no reduction, and only the representatives returned are turned back
into `Simplex` objects. `CellularCohomologyEngine(packSimplices = false)` reduces the cells themselves, and
`reducesPacked(stream)` says which a stream gets. Other vertex types, a vertex order other than `Int`'s and a cell
instance of your own fall back to the cells.

**Experiments: edge blocks on threads.** Two JVM-only objects try reducing Vietoris-Rips cohomology in parallel, with
each degree cut into units of whole edge blocks (a simplex's block is its first longest edge):
`BlockParallelReduction` on explicit coboundary matrices, `ParallelPackedCohomology` on implicit ones, as engine 4
computes them (its key is one `Long` where the block and the index fit, else the block beside the index; the index
itself is still one `Long`). Both give exactly the pairs of a sequential reduction.
`BlockParallelReduction` builds its matrices in full, returns bars only and `Persistence` does not call it.
`ParallelPackedCohomology` with `cocycles = true` keeps a `Log` per reduced column (the columns added to it and their
factors; an apparent pair has none), from which `cocycle(degree, p, column)` builds a bar's cocycle, and with `keepPairs`
the pairing. `Persistence.Engine.ParallelRipser` runs it: `Persistence.Input.parallelRipser` calls the seam
`ParallelRipser.run` (shared code: 8 units per thread of `Parallel.threads`, run by `Parallel.tasks`, which pulls them
from a counter on the JVM and runs them in order on Scala.js; `Auto` only asks with several threads, which JavaScript never has) and gets a `ParallelRipserRun`, the engine's keys and logs without its types, which is
all the shared `Persistence` code sees. Each bar's chain is a `LazyChain`: a cocycle is built from the logs when read, a
cycle comes out of one pass of `Involution.cycles` over the keys (`ParallelRipserCycles`, with the shortcut of
`CellularCohomologyEngine`: a death simplex whose youngest face is its birth needs no reduction; the death simplices
that do need one are found by a parallel scan of `faces` over every death, which is most of the pass, and their
reductions then run in order, one death dimension to a thread). Keys of one dimension
ascend with age, which is the order `Involution` is given, reversed. Cycles need one-word keys; two-word keys give
cocycles only. `computeAllRepresentatives` forces the chains before returning (cocycles on all threads).
`Engine.Auto` chooses the engine when `Parallel.threads > 1`, the input is a Vietoris-Rips metric space, there is no
`includeZeroLength`, and `ParallelRipser.supports` (a prime up to 46,000, indices that fit 63
bits). Edge ranks are a dense `n x n` table up to `DenseRankLimit` (4,096) points and sorted neighbour lists above it, so memory
follows the edges within the threshold; the one `n`-squared cost left is the distance pass that builds the edge list. `Persistence`'s `threads` option runs the call in `Parallel.withThreads(n)` (a fork-join pool of that size on the
JVM, none in JavaScript), and the lazy chains keep the count they were made under (`Parallel.limit`, `Parallel.limited`).
The MATLAB facade and the CLI do the same through `threads`, and send an unset `engine` for the Vietoris-Rips complex over
`Z/p` (no `includeZeroLength`, no `edgeCollapse`) to this engine when `Parallel.threads > 1` (`parallelVR`); `engine=ripser`
stays `PackedRipserCohomologyEngine`. The benchmark harness runs the engine directly as `engine=parallel` (`tda4j-parallel-1t/-mt`, bars only, with `threads`
and `wide`; it tabulates the distances, so it takes up to 60,000 points) and through `Persistence` as `engine=parallelverb`
(`tda4j-parallelverb-1t/-mt`, with representatives).

## 6. `FastCubicalHomologyEngine` — dual-graph union-find

`FastCubicalHomologyEngine` is specialized to `CubicalGridStream`.

It computes `H_0` by primal union-find and `H_{d-1}` by applying Alexander
duality to a dual graph and running union-find in reverse filtration order.
For ambient dimension `d >= 3`, intermediate dimensions
`1 <= k <= d-2` go to cohomology on the grid without its top cells
(`PackedCubicalCohomologyEngine`, cycles by the involution).

The engine is valid for ambient dimension `>= 2` and avoids general `Chain` reduction in the two shortcut dimensions, `H_0` and `H_{d-1}`.

`persistentHomology(grid, includeZeroLength, maxDimension)` stops at degree `maxDimension`: the dual graph is walked only
when the top degree `d - 1` is asked for, and the middle degrees are reduced on the grid cut to dimension
`maxDimension + 1`, the same shape as `FastAlphaHomologyEngine`.

A masked (NaN) pixel is not in the complex. In the dual graph it is present
from the start, like the outside of the grid, and a masked region that dies
gives an essential bar.

Representatives are reconstructed from the active dual components so the
result remains compatible with the rest of the persistence API.

Its pieces are public:

- `SignedUnionFind[C](size)` is a union-find whose elements carry an orientation in the field `C`. `union(young, old,
  flip)` hangs one root under another and multiplies the young component's orientations by `flip`; `find` composes
  orientations along the path; `members(root)` lists a frozen region with its orientations relative to the root, which
  is how a representative is read off. `UnitSignedUnionFind(size)` is the same with orientations `±1` kept in bytes,
  exact whenever every flip is `±1` as in the cubical dual.
- `DoubledGrid` walks the doubled grid (coordinate `2a` is the point `a`, `2a + 1` the interval `[a, a + 1]`) one
  prefix at a time, so a caller can run a tight loop along the last axis.
- `GridCellOrder` is the order `PackedCubicalCohomologyEngine` puts on the cubes of one grid: the rank of a cell's
  value, then its doubled-grid index.
- `LimitedCubicalGridStream(stream, maxDim)` is a grid stream cut off above a cell dimension, with the same values and
  order; the engine uses it for the degrees between `H_0` and `H_{d-1}`.

## 7. `FastAlphaHomologyEngine` — dual union-find for alpha complexes

`FastAlphaHomologyEngine` applies the same dual-graph union-find strategy as
`FastCubicalHomologyEngine` to a Delaunay triangulation (`BowyerWatsonDelaunay`
or `HelixDelaunay`).

It requires a full triangulation and is not compatible with the DQP alpha
backends. As with the cubical engine, `H_0` and `H_{d-1}` use union-find and
intermediate dimensions go to `CellularCohomologyEngine` (cycles by the
involution). `maxDimension` caps the degrees: the cohomology engine then sees the
stream only up to dimension `maxDimension + 1`, and the dual graph is walked only
when the cap reaches `d - 1`. `persistentHomologyTopDegree` is that dual pass alone.

The dual construction assumes each facet has at most two cofaces.
`BowyerWatsonDelaunay`'s exact predicates guarantee this. `HelixDelaunay` can
violate it in rare degenerate configurations, so the engine validates the
triangulation and throws `FastAlphaTriangulationException` rather than
returning an invalid result.

See [Alpha complex](alpha-complex.md) for backend limitations and triangulation
repair.


## Querying a run: the cursor

Engine 1's `HomologyState` is a cursor over the stream:
- `advanceOne()`, `advanceTo(f)` and `advanceAll()` move it;
- `advanceFor(budget)` runs it for a time-boxed slice;
- `processedCells` and `totalCells` report progress.

It exists so that a long run can be inspected while it runs, and keeps its
output if it dies.

**Queries do not depend on where the cursor is.** `diagramAt(f)`,
`barcodeAt(f)` and `diagramWithGeneratorsAt(f)` give the diagram truncated at
`f`:
- bars born at or before `f`, with their deaths capped at `f`;
- a class alive at `f` is reported as dying at `f`, or as essential when no
  cell enters after `f`.

A query past the cursor advances it as far as it needs. A query below the
cursor gives what a fresh cursor would. `snapshotAt(f)` copies the answer into
an immutable `PersistenceDiagram`.

Engine 2 answers `diagramAt(f)` and `barcodeAt(f)` with the same contract, but
it runs the whole reduction on its first query and has no cursor.

## Cycles and cocycles from one pairing: `Involution`

Every engine but the fast ones and the Ripser oracle (engine 3, cocycles
only) gives both kinds of representative. It computes one kind natively and
derives the other from its own pairing
(`homology/Involution.scala`, after Čufar and Virk, arXiv:2105.03629).

**Cycles from a cohomology pairing** (`Involution.cycles`). Only the boundary
columns of death cells are reduced, youngest cell first:
- a finite bar `(σ, τ)` gets `R_τ`, the reduced boundary of `τ`, a cycle whose
  youngest cell is `σ`;
- an essential bar gets the cycle the reduction of `∂σ` to zero produces.

Engines 4, 5 and the packed cubical class expose this as `persistentHomology`.
Engines 4 and 5 also return the bars with their birth and death cells through
`pairedCohomology`.

**Cocycles from a homology pairing** (`Involution.cocycles`, `cocycleBars`).
Only the coboundary columns of birth cells are reduced, built from the stream
by `coboundariesOf`; the representative is the V-column `V_σ`. Engines 1 and 2
expose a `pairing` for this.

The pairing must come from the same total order the involution reduces under,
and every pivot is checked against the paired cell. A mismatch throws; it is a
bug in the pairing or the order, so never fall back. Zero-length pairs must be
present: their columns are pivots for the others.

The engines give different, equally valid representatives. Never compare
representatives cell for cell across engines; check that each one is a cycle
(or cocycle) present at the bar's birth. The fast engines have no derived kind:
their union-finds break ties by value rather than by the stream's order. Image
cocycles go through `Engine.Cohomology`.

## Circular coordinates from a diagram

`CircularCoordinates(diagram, input, ...)` turns a degree-1 bar of any `PersistenceDiagram[Simplex[Int]]` into a map to
the circle. A finite bar's cocycle `V_σ` has `σ` as its oldest cell and a coboundary that starts at the death cell, so
restricted to the complex `K_r` at a scale `r` inside the bar it is a cocycle, and it is non-zero there. The diagram
records which kind it carries (`PersistenceDiagram.representatives`); a diagram of cocycles supplies the class directly, and
a diagram of cycles has it recovered by the cohomology of `K_r` over the diagram's field, matched to the bar by its
birth. Both lift to integers (checked exactly on every triangle) and are smoothed to the harmonic representative.

A bar's class is only defined up to adding the classes of shorter bars nested inside it. When such bars are alive at `r`,
the engines' representatives, and so their coordinates, differ; every one is a valid circle map. To compare two routes,
choose `r` where only the loops asked for are alive.

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
| `cech`                        | `cohomology`         | **no** — not a max-pairwise-distance functional (radius, not diameter) | yes | yes | yes          | **no**       | **no**       |
| `witness`, `witnessVariant=lazy`    | `ripser`  | yes — a flag complex under its own reified `WitnessMetricSpace` | yes | yes | yes | **no** | **no** |
| `witness`, `witnessVariant=general` | `cohomology`   | **no** — not a flag complex (dimension-specific threshold) | yes | **no** | yes | **no** | **no** |
| `dtm-rips`                    | `cohomology`         | **no** — vertices aren't born at 0, and the weighted-Rips functional isn't `insertionDiameter`-compatible | yes | yes | yes | **no** | **no** |
| `dtm-alpha`                   | `cohomology`         | **no** — no notion of a Vietoris-Rips complex at all   | yes     | **no** — shares `AlphaComplexDQP`'s known stall/OOM risk (see `alpha-complex.md`) | yes | **no** | **no** — always uses `AlphaComplexDQP`, never a triangulation |
| `alpha`                       | `cohomology`         | **no** — no notion of a Vietoris-Rips complex at all   | yes     | **no** — known stall/OOM risk | yes | **no** | **yes** — only a triangulating `alphaBackend` (`default`, `bowyer-watson`, `helix`), any ambient dimension `>= 2` |
| `sheehy-rips`                 | `cohomology`         | **no** — a simplex's value is not the maximum ambient pairwise distance among its vertices (some pairs sparsified away, others excluded outright) | yes | yes | yes | **no** | **no** |
| cubical (`computeFromCubicalImage`/`computeFromImage`, no `complex` key) | `fast-cubical` (2-D and up) | **no** — `PackedRipserCohomologyEngine` is specialized to `Simplex[Int]` | yes | yes | yes | **yes** — any ambient dimension `>= 2` | **no** |
| Dowker relation (`computeFromRelation`, no `complex` key) | `cohomology` | **no** — not a flag complex (a witness for a whole simplex need not witness any of its edges) | yes | **no** — same conservative refusal `witness`/general has (use `naive`/`cohomology`) | yes | **no** | **no** |
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

`engine="cohomology"` (`CellularCohomologyEngine`, engine 5 above) is the one column with no "no" cells because it
is generic over `CellT: OrderedCell` and assumes nothing about the construction; the price is that it lacks engines
3 and 4's Vietoris-Rips-specific optimizations.

**Representatives through the facades.** `matlab.TDA4j` takes the option
`representativeType`, `cycles` (the default) or `cocycles`, and the CLI takes
`--representative-type` with the same values. `fast-cubical` and `fast-alpha`
give cycles only and refuse `cocycles` with a message that names `cohomology`.
Every other engine gives both kinds, the second through `Involution`.

`PersistenceResult.cycleVertices(i)` and `cycleCoefficients(i)` return bar
`i`'s representative of either kind:
- a simplex as its sorted vertices;
- a cube as its doubled coordinates;
- a coefficient of `Z/p` as an integer representative.

The CLI prints representatives with `--representatives` (text output only).

## Choosing an engine

Most callers should not choose: `Persistence(...)` with `Engine.Auto` picks
packed Ripser for Vietoris-Rips on points, fast cubical for images (when cycles
are asked for) and cohomology for everything else. See
[The public API](public-api.md). The table is for building on an engine
directly.

| Need | Engine |
|---|---|
| Exploration, intermediate-filtration queries, representative cycles, any `OrderedCell` type | **`CellularHomologyEngine`** |
| Large complex, chunked/parallelizable, representatives for every bar including essential ones | **`CellularPersistenceInChunksEngine`** |
| Fast, memory-efficient cohomology on a Vietoris-Rips/clique complex over integer vertex labels | **`PackedRipserCohomologyEngine`** (what `engine="ripser"` uses) |
| A `Simplex[Int]`-keyed reference implementation for hand-debugging engine 4 | `RipserCohomologyEngine` (test oracle, not a production choice) |
| Cohomology (real cocycle representatives) on `Cube`/`FiniteSimplicialSet`/Cech/Alpha/general witness complex, or any `OrderedCell` type engines 3/4 can't serve | **`CellularCohomologyEngine`** (what `engine="cohomology"` uses) |
| Fastest option for a cubical grid of any ambient dimension `>= 2` (no `Chain` reduction at all for `H_0`/`H_{d-1}`; a cohomology hybrid for any residual middle dimensions at `d >= 3`) | **`FastCubicalHomologyEngine`** (what `engine="fast-cubical"` uses) |
| Fastest option for an alpha complex via a triangulation (`BowyerWatsonDelaunay` up to 4-D, `HelixDelaunay` any ambient dimension `>= 2`; same hybrid shape as `FastCubicalHomologyEngine`; on Helix noticeably more likely to throw `FastAlphaTriangulationException` at higher ambient dimension/point count) | **`FastAlphaHomologyEngine`** (what `engine="fast-alpha"` uses) |
