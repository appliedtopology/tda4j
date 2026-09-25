# Persistence engines: what to trust, and why

`homology/Homology.scala`, `homology/PackedRipserCohomology.scala`, `homology/Cohomology.scala`,
`homology/FastCubicalHomology.scala`, and `homology/FastAlphaHomology.scala` contain **five
independently-implemented algorithms across seven concrete classes** (the last two classes share one
algorithm — a dual-graph union-find via Alexander duality — applied to two different cell types, the same
"one algorithm, several concrete classes" relationship engines 3/4 already have). They share the `Chain`
reduction primitives from [Architecture](architecture.md), but they are not variants of one shared engine — a
fix or bug found in one does not imply anything about the others. Read this page before choosing which engine
to build on.

Three of the five (`CellularHomologyContext`, `CellularPersistenceInChunksContext`, `CellularCohomologyContext`)
additionally implement the common `homology.PersistenceEngine[CellT, C]` trait (`def barcode(stream): List[
PersistenceBar[Double, Chain[CellT, C]]]`) via `PersistenceEngine.naive`/`.chunks`/`.cohomology` factories — a
thin, opt-in adapter over each engine's own incremental API, for a caller (the MATLAB/CLI facade) that just
wants a finished barcode without hand-writing each engine's own construct/advance/read dance. It does not
change any engine's own contract; the incremental API (`advanceTo`/`diagramAt`/`barcodeAt`) stays available on
the concrete class. `PackedRipserCohomologyContext`/`RipserCohomologyContext` don't implement it — they consume
a `FiniteMetricSpace[Int]` directly, not a stream (see engine 4 below).

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

## 3. `RipserCohomologyContext` — test/reference oracle for engine 4

Persistent *co*homology via Ulrich Bauer's Ripser algorithm (arXiv:1908.02518), specialized to
`Simplex[Int]` Vietoris-Rips/clique complexes via `SimplexIndexing`'s combinatorial number system — a
deliberate narrowing from the generic `CellT: OrderedCell` engines above. One-shot only
(`persistentCohomology()`, no incremental querying).

**This class is not what production code calls.** `PackedRipserCohomologyContext` (engine 4) is a faithful
re-keying of the same algorithm onto a packed representation, and is what `matlab.TDA4j`'s
`engine="ripser"` actually uses. This class's remaining value is narrower than "an independent check on the
Ripser algorithm": both classes share `SimplexIndexing`, so a bug there passes both silently (engine 1 is the
actually-independent oracle for the algorithm itself). What this class *does* catch is anything specific to
engine 4's own packed representation — its `DiameterIndex` carrier's index-only `equals`/`hashCode`, its
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

## 4. `PackedRipserCohomologyContext` — the production Ripser engine

Same algorithm as engine 3, method for method, keyed on a packed `(Double, Long)` diameter/combinatorial-
index pair (`DiameterIndex`) instead of a materialized `Simplex[Int]`. This is what `matlab.TDA4j`'s
`engine="ripser"` calls, and the fastest, most memory-efficient engine in the library — a deliberate
representation choice on top of an already-validated algorithm, not a new algorithm. Kept in its own file,
separate from the three generic-`OrderedCell` algorithms in `Homology.scala`, since it's specific to
Vietoris-Rips/`SimplexIndexing` rather than a general engine.

`DiameterIndex` overrides `equals`/`hashCode` to consider only the combinatorial index, not the diameter —
so "same simplex" is true by construction regardless of which floating-point path computed its diameter,
sidestepping a real footgun (two carriers for the same simplex comparing unequal on floating-point noise).

## 5. `CellularCohomologyContext` — generic cohomology, for every cell type

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

**No `maxDim` parameter, and no apparent pairs — both deliberate.** This class computes cohomology up to
whatever top dimension the materialized stream actually contains; a caller wanting only `H_0..H_k` truncates
the *input stream* first (`LimitedCofaceSimplexStream(stream, k + 1)`, the same mechanism engine 3's own test
suite and `engine="naive"` already use) and drops `dim == k + 1` bars from the result afterward — deleting a
whole footgun class (engines 2, 3, and 4 each had to fix a "`maxDim` means top built vs. top reported degree"
bug once) rather than reimplementing it a fifth time. Apparent pairs' entire point is avoiding coboundary
*enumeration* — this class has none to avoid, since it must materialize the coboundary relation for every
cell up front just to have "coboundary" exist at all; porting the mutual-pair check would save a `basis` write
and one already-cheap `Chain.reduceBy` call, noise-level and not worth the machinery. See
`.claude/DESIGN-generic-cohomology.md` for the full derivation of both calls.

**Representatives**: every bar carries a V-column, the same way engines 3/4 already track one. Only an
*essential* bar's V-column is a genuine cocycle (`d(vcol) = 0`) by construction — a finite bar's V-column has
coboundary equal to its own nonzero reduced pivot chain instead (still a valid representative on the bar's own
living interval, just not a cocycle over the whole complex). `coboundaryOfChain` exists specifically to check
this for essential bars. This is also the class's actual point, not an afterthought: over a field the
cohomology barcode is identical to the homology barcode, so a bars-only version would be entirely redundant
with engine 1, which already covers every cell type this class does.

There is no `Cocell`/`OrderedCocell` typeclass backing this (an earlier, unimplemented, dual-to-`Cell` trait
pair was removed outright while building this class) — coboundary is *extrinsic* to a cell (it depends on
which higher-dimensional cells exist in the ambient complex), not intrinsic the way `boundary` is, so a
per-cell `coboundary` method with no complex to consult was never the right shape.

## 6. `FastCubicalHomologyContext` — dual-graph union-find, any ambient dimension >= 2

Flash Cubical (Le Breton-Szustakowski-Piraud, arXiv:2606.04801): a genuinely different algorithm from engines
1/2 above, not a faster re-keying the way engine 4 is for engine 3. Specialized to `CubicalGridStream`
directly (like engines 3/4 are specialized to `Simplex[Int]` Vietoris-Rips) rather than generic over
`CellT: OrderedCell` — it reads the grid's own `shape`/`ambientDim`/`topCellValue` directly, so it does not
implement `PersistenceEngine[CellT, C]` either, for the same "honest asymmetry" reason that trait's own doc
comment already gives for engines 3/4.

**Valid at any ambient dimension `>= 2`** (a `require`d precondition `matlab.TDA4j`/`cli` both check before
ever calling it, with a clear message rather than a generic exception). At `d=2`, `H_0` (an ordinary primal
union-find, ascending filtration order, elder rule) plus `H_1` (via the dual construction below) together
account for every nontrivial cell dimension a 2D grid has — `H_2` is identically zero for any subcomplex of a
2D grid (a bounded planar region has no 2-dimensional voids to detect), so nothing is being skipped. At `d >=
3` there are `d-2` "middle" dimensions (`1 <= k <= d-2`) with no duality shortcut; these are handed to
`CellularPersistenceInChunksContext` run on a `LimitedCubicalGridStream` view that hides the real
top-dimensional cells entirely, so the (often largest) top dimension never touches general `Chain` reduction —
still a real, if shrinking-with-`d`, win, and no new hardcoded dimension ceiling (`chunks` is already fully
general over `d`). See `.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md` for the full derivation,
including why the dual union-find's own correctness doesn't depend on how the middle dimensions get resolved;
cross-validated against the naive engine at `d=3` (hand fixtures, Fp(3) sign-genericity, a random property
test) plus one `d=4` smoke test, not validated at `d >= 5`.

**The dual construction**: top cells (pixels) become dual vertices, codimension-1 cells (facets) become dual
edges connecting the 1 or 2 top cells containing them (a shared `∞` sentinel vertex, fixed at `+Infinity`,
stands in for a facet's missing side on the grid's own outer boundary). Primal `H_{d-1}` of the sublevel
filtration equals ordinary `H_0` of this dual graph's own SUPERLEVEL filtration (Alexander duality,
`H_{d-1}(X) ≅ H^0(S^d \ X)`), computed by the same elder-rule array union-find engine 2's own `unionFindDim01`
uses, processing dual vertices/edges together in DESCENDING order of primal value, with every resulting bar's
endpoints swapped. `∞` must be the unconditional elder of any merge it takes part in — not just because
`birthOf(∞) = +Infinity` is *usually* the largest value, but enforced explicitly, since a real top cell can
also carry `topValue = +Infinity` (this codebase's own "permanently missing cell" convention, e.g. Perseus's
`-1`) and tie against it.

**Representatives**: each active dual component tracks its own running signed sum of top cells, oriented
coherently as merges happen so a dying component's boundary is exactly the `H_{d-1}` cycle bounding it — the
orientation flip needed at each merge is solved directly from the connecting facet's own boundary coefficients
(always `±1`, `cubeIsOrderedCell`'s alternating-sign rule) and each side's own already-established sign,
matching this codebase's design principle of representatives from every engine, not just this one's own
speed. This is this codebase's *own* extension: the source paper is F2-only and barcode-only.

No paper access (network-blocked) and no existing implementation to port (unlike engine 4's GUDHI-verified
edge-collapse precedent) meant this is an original derivation from Alexander duality, not a translation — see
the design note for the full derivation and a hand-verified worked example, checked before any code was
written.

## 7. `FastAlphaHomologyContext` — engine 6's own dual union-find, ported to `HelixDelaunay`

Same algorithm as engine 6, applied to `HelixDelaunay`'s top simplices instead of a cubical grid's top cells
(`.claude/DESIGN-alpha-dual-unionfind.md`, `alpha-complex.md`'s own `FastAlphaHomologyContext` section for the
full derivation and its own newly-measured risk). `HelixDelaunay` specifically, never `AlphaComplexDQP`/
`AlphaShapeDQP` — the dual graph needs the full, untruncated triangulation (`AlphaComplexDQP.euclidean`'s own
truncated mode is incompatible) and "every facet has <= 2 cofaces," which `AlphaShapeDQP`'s own documented
cospherical-degeneracy hazard can violate directly by emitting an oversized simplex. **Currently ambient
dimension 2 only, unlike engine 6** (which now also handles `d >= 3` via a hybrid with `chunks` — see engine
6's own section and `.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`): the same hybrid shape applies
in principle (this engine's own dual-graph code is already dimension-generic, exactly like engine 6's was
before its own extension), but alpha's `d >= 3` port is deliberately sequenced AFTER cubical's own hybrid was
validated, not concurrent with it, and additionally needs its own fresh measurement of the facet-multiplicity
precondition's failure rate at `d=3` (HelixDelaunay's own separate near-cospherical limitation is known to get
WORSE, not stay flat, at higher ambient dimension — see this file's own risk note just below and
`alpha-complex.md`) before it can ship. Not yet started.

**Unlike engine 6, this precondition is not guaranteed by construction** and was measured directly this
session: roughly 1-in-18700 on random points at ambient dimension 2 specifically (see `alpha-complex.md` for
the full measurement) — real, but rare, a genuine `HelixDelaunay` limitation, not a flaw in this construction.
Validates the precondition explicitly and throws the named `FastAlphaTriangulationException` on violation
rather than building a silently-wrong dual graph — its message is layered plain-language-first (for an
unsuspecting MATLAB/CLI caller: "NOT an error in your data," the concrete retry) with the facet-count detail
as a technical appendix, the same two-audience approach `NoIntegerCocycleException` already established for
`CircularCoordinates`.

**Wired into `matlab.TDA4j`/`cli` as `engine="fast-alpha"`/`--engine fast-alpha`**, like every other engine on
this page — valid only for `complex=alpha` with `alphaBackend=helix` (the default) and ambient dimension 2;
see `alpha-complex.md`'s own section for the full reasoning behind shipping the measured risk above.

## Streams × engines: what works with what

Every complex construction in this codebase produces a `CofaceSimplexStream`/`CellStream` that, in principle,
any of `matlab.TDA4j`'s four `engine` values could consume — but two of those engines
(`PackedRipserCohomologyContext`/`engine="ripser"` and `CellularPersistenceInChunksContext`/`engine="chunks"`)
were built against specific assumptions (a genuine flag complex, a max-pairwise-distance filtration functional,
vertices born at filtration 0) that not every construction satisfies, and refusing the combination outright
(a clear `IllegalArgumentException`, not a silently wrong barcode) is deliberate. This table is generated by
reading `matlab.TDA4j`'s own `dispatch`/`resolveWitnessEngine`/`dispatchCubical` — the single source of truth
for every refusal and default — not by inference from a construction's own doc; re-check that source if this
table and the code ever disagree.

| Complex (`complex=`)         | Default engine | `ripser`                                            | `naive` | `chunks`                                          | `cohomology` |
|-------------------------------|-----------------|------------------------------------------------------|---------|-----------------------------------------------------|--------------|
| `vr`                          | `ripser`        | yes                                                    | yes     | yes                                                   | yes          |
| `cech`                        | `naive`         | **no** — not a max-pairwise-distance functional (radius, not diameter) | yes | yes | yes          |
| `witness`, `witnessVariant=lazy`    | `ripser`  | yes — genuinely a flag complex under its own reified `WitnessMetricSpace` | yes | yes | yes |
| `witness`, `witnessVariant=general` | `naive`   | **no** — not a flag complex (dimension-specific threshold) | yes | **no** | yes |
| `dtm-rips`                    | `naive`         | **no** — vertices aren't born at 0, and the weighted-Rips functional isn't `insertionDiameter`-compatible | yes | yes | yes |
| `dtm-alpha`                   | `naive`         | **no** — no notion of a Vietoris-Rips complex at all   | yes     | **no** — shares `AlphaComplexDQP`'s known stall/OOM risk (see `alpha-complex.md`) | yes |
| `alpha`                       | `naive`         | **no** — no notion of a Vietoris-Rips complex at all   | yes     | **no** — known stall/OOM risk (`HomologySpec`'s `BarcodeRegressionSpec`) | yes |
| `sheehy-rips`                 | `naive`         | **no** — a simplex's value is not the maximum ambient pairwise distance among its vertices (some pairs sparsified away, others excluded outright) | yes | yes | yes |
| cubical (`computeFromCubicalImage`/`computeFromImage`, no `complex` key) | `naive` | **no** — `PackedRipserCohomologyContext` is specialized to `Simplex[Int]` | yes | yes | yes |
| simplicial sets               | *(no `matlab`/`cli` entry point at all — construct `SimplicialSetStream`/`FilteredSimplicialSetStream` and drive any generic engine directly)* | | | | |

A fifth engine value, `engine="fast-cubical"` (`FastCubicalHomologyContext`, engine 6 above), is not shown as
its own table column: it would be "no — not a cubical grid" for every row except cubical, which is the ONLY
row that offers it — valid at any ambient dimension `>= 2` there (a degenerate 1-axis image is refused with a
message naming the actual dimension, not a bare `IllegalArgumentException`).

A sixth engine value, `engine="fast-alpha"` (`FastAlphaHomologyContext`, engine 7 above), is symmetric: "no —
not `HelixDelaunay`" for every row except `alpha`, the ONLY row that offers it, and even there **only when
`alphaBackend=helix` (the default) and the point cloud's own ambient dimension is exactly 2** (`alphaBackend=
DQP`, any other `complex`, or a 3D point cloud are all refused with a message naming the actual mismatch, not a
bare `IllegalArgumentException`).

Reading the "no" cells as one-line reasons, grouped by root cause:

- **Not a flag complex** (`cech`, `witness`/general): a `k`-simplex's value isn't determined by its own edges'
  values alone, so `insertionDiameter`'s incremental recurrence has nothing valid to incrementally update.
  `sheehy-rips` belongs here too, not with `dtm-rips` below, despite looking pairwise-derived at a glance: its
  own `filtrationValueOverride` needs to see every vertex of a `k`-simplex at once (the `min`-over-vertices
  `vanish` exclusion check), not just its edges — proven by construction, not merely asserted: a hand-derived
  fixture (`SheehyRipsStreamSpec`) exhibits a triangle whose three edges are ALL individually present and
  finite, yet the triangle itself never appears — the one thing an actual flag complex can never do.
- **Not a Vietoris-Rips complex at all** (`alpha`, `dtm-alpha`): `PackedRipserCohomologyContext` consumes a
  `FiniteMetricSpace[Int]` directly and enumerates cliques via `SimplexIndexing` — there is no Delaunay/power-
  cell structure it could route through instead.
  These two also refuse `chunks`, but for a third, unrelated reason: a known stall/out-of-memory risk in
  `AlphaComplexDQP` at scale, not an algorithmic mismatch (see `alpha-complex.md`; `HomologySpec`'s
  `BarcodeRegressionSpec` stays `skipAll`'d for the same reason and is the regression pin, not a live check).
- **A genuine flag complex, but vertices aren't born at 0 and the edge functional isn't plain max-pairwise-
  distance** (`dtm-rips` only): `PackedRipserCohomologyContext`'s two production-critical optimizations
  (`insertionDiameter`, apparent pairs) are proven specifically for `MaximumDistanceFiltrationValue` on the
  metric space handed to it — a weighted filtration value invalidates both proofs even though the complex
  itself is, combinatorially, an ordinary flag/clique complex (unlike `sheehy-rips` above).
- **Representation-specific** (cubical): `PackedRipserCohomologyContext`/`RipserCohomologyContext` are
  hardcoded to `Simplex[Int]`'s combinatorial-number-system indexing (`SimplexIndexing`); `Cube` has no
  equivalent encoding built for it. Engine 6 (`fast-cubical`) is a dedicated fast engine in this spirit, but
  not a drop-in replacement for `ripser` here: it's a different algorithm (dual-graph union-find plus, at
  `d >= 3`, a hybrid with `chunks` for the residual middle dimensions — not `SimplexIndexing`-style
  enumeration). A grid-exploiting engine dedicated to 3D specifically (`CubicalRipser`, Wagner-Chen-Vuçini)
  remains a documented future direction, `DESIGN-fast-cubical-engine.md`.

`engine="cohomology"` (`CellularCohomologyContext`, engine 5 above) is the one column with no "no" cells for a
reason: it's generic over `CellT: OrderedCell` with no per-construction speed assumptions baked in, at the cost
of none of engines 3/4's Vietoris-Rips-specific optimizations — see engine 5's own section above for what that
tradeoff actually buys and costs.

## Choosing an engine

| Need | Engine |
|---|---|
| Exploration, intermediate-filtration queries, representative cycles, any `OrderedCell` type | **`CellularHomologyContext`**/`TDAContext` |
| Large complex, chunked/parallelizable, representatives for every bar including essential ones | **`CellularPersistenceInChunksContext`** |
| Fast, memory-efficient cohomology on a Vietoris-Rips/clique complex over integer vertex labels | **`PackedRipserCohomologyContext`** (what `engine="ripser"` uses) |
| A `Simplex[Int]`-keyed reference implementation for hand-debugging engine 4 | `RipserCohomologyContext` (test oracle, not a production choice) |
| Cohomology (real cocycle representatives) on `Cube`/`FiniteSimplicialSet`/Cech/Alpha/general witness complex, or any `OrderedCell` type engines 3/4 can't serve | **`CellularCohomologyContext`** (what `engine="cohomology"` uses) |
| Fastest option for a cubical grid of any ambient dimension `>= 2` (no `Chain` reduction at all for `H_0`/`H_{d-1}`; a `chunks` hybrid for any residual middle dimensions at `d >= 3`) | **`FastCubicalHomologyContext`** (what `engine="fast-cubical"` uses) |
