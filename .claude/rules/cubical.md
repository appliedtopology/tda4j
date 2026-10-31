---
paths:
  - "src/**/*Cubical*.scala"
  - "src/**/CubicalImage*.scala"
---

# Cubical complexes and the fast cubical engine

Loads when you work on a cubical file. Project-wide rules are in `.claude/CLAUDE.md`.

## Cubical complexes

`WORKLOG-cubical.md`. `Cube` = `opaque type Cube = Vector[Int]` in KMM doubled-coordinate encoding (`2a`
degenerate, `2a+1` = `[a,a+1]`). `Vector`, not an array — structural `equals`/`hashCode` required. Boundary sign
alternates by the axis's **rank among non-degenerate axes**, not raw position (invisible over F2;
`CubicalSpec`'s dd=0 runs over F3).

`CubicalGridStream`: dense T-construction (GUDHI/DIPHA/Perseus convention); lower cubes take the min over
containing top cells (monotonicity). `ExplicitCubicalStream` for sparse/hand-built complexes (same
`FiltrationOrdering.canonical` combinator). Sublevel/superlevel handled only in `CubicalImage.scala`'s loaders.
`CubicalImage.fromFlatArray` row-major, last axis fastest; H0 oracle uses Moore (8/26-connected) adjacency.

Both naive and chunks engines consume cubes. A grid-exploiting **3D** engine (CubicalRipser, Wagner-Chen-Vuçini)
remains a valid future direction (`DESIGN-fast-cubical-engine.md`); every dimension `>= 2` has a faster
option below.

**`FastCubicalHomologyEngine`** (`engine="fast-cubical"`) — Flash Cubical (Le Breton-Szustakowski-Piraud,
arXiv:2606.04801), original derivation, valid any ambient dim `>= 2`. Top cells → dual graph vertices, codim-1
cells → dual edges (`∞` sentinel for the outer boundary); primal `H_{d-1}` of the sublevel filtration = ordinary
`H_0` of the dual's own SUPERLEVEL filtration (Alexander duality) via `unionFindDim01` run descending with
endpoints swapped; combined with a primal `H_0` union-find, covers a 2D grid completely with no `Chain`
reduction. **`∞` must be checked explicitly as unconditional elder of any merge, not inferred from `birthOf(∞)`
being largest** — a real top cell can tie against it (see worklog before touching `computeDualTopDimension`).
Representatives: each top cell's sign relative to its dual component's root lives in `UnitSignedUnionFind` (signs as
bytes: every flip is a product of boundary coefficients ±1, so integer signs mapped to the field per output term are
exact; path compression composes signs; merges never touch members); a reported bar's cycle is the boundary of the
dying region, read off the frozen merge-forest subtree. (The alpha engine keeps the generic `SignedUnionFind[C]`.)
Never go back to per-component coefficient maps: copying them on every merge
was quadratic on a bright object on a dark background (80 s at 200²). Any change must keep `FastRepresentativesSpec`
green: equality with `EagerFastCubicalReference`/`EagerFastAlphaReference`, term for term, AND its validity examples
(every top-degree representative non-zero, closed, born with its bar) -- equality alone once passed a shared bug that
read the merge flip from the facet's own boundary (always 0), zeroing every representative of a merge away from `∞`.
The flip's coefficients are coboundary entries: `facet` looked up in the TOP cell's boundary.
**Equal births: the smaller component dies** (`UnitSignedUnionFind.sizeOf`; two masked regions likewise), else a tiling's equal
maxima kill the large region again and again (Truchet 1026²: 87 s, now 4 s). Any bars come out the same, only the cycle differs, so
`EagerFastCubicalReference` carries the same rule and `FastCubicalTieBreakSpec` pins the cost (`representativeCellsWalked`, a
`private[tda4j]` measurement) on a comb of equal peaks. Not fixed: a large region that is the YOUNGER by value still dies whole
(`FastCubicalProfileDriver ... spikes`: 125M cells at 512²), #293; `FastAlphaHomologyEngine` keeps the old `<=` rule, #294.
`WORKLOG-fast-cubical-engine.md`, `WORKLOG-fast-cubical-representatives.md`, `WORKLOG-fast-cubical-ties.md`.

**Order in rank space, compare on values** (`WORKLOG-cubical-performance.md`): both union-finds order cells by pixel
RANK (`GridRanks`: dense ranks under `java.lang.Double.compare`; a cell's rank is the minimum over its containing
pixels) with stable counting sorts fed in tie-break order (H₀ edges generated encoding-descending, sorted rank
ascending; dual facets encoding-ascending, rank descending), but every elder-rule and zero-length test compares the
VALUES `distinct(rank)` with IEEE `<=`/`!=` (-0.0 == 0.0), as the cube-based code did: comparing ranks changes outputs
(mutation-checked; `FastRepresentativesSpec`'s `signedZeroMerge` is the case random images miss). **`NaN` is the mask, and it stays NaN** (project lead, 2026-10-12, #52): NaN has no sign (a superlevel negation keeps
it) and compares to nothing, so a masked pixel is NOT IN THE COMPLEX -- never "enters at +Infinity" (the rejected first
attempt: masked cells would then show `[∞, ∞)` bars and an all-NaN image a bar). The complex is the closure of the
unmasked pixels: `cellValues` takes a NaN-skipping minimum, `filtrationValue`/`iterateDimension` leave NaN cubes out.
Grid engines: `GridRanks.masked = distinct.length` (one past every real rank) is a masked pixel's rank, so rank minima
skip masks and a cell of rank `masked` is absent; every enumeration must skip it (forgetting reads `distinct(masked)`:
out of bounds, mutation-checked). Fast dual: a masked component is elder to all but `∞` (`seniority`), facets of rank
`masked` merge first with no bar, a dying masked component is an essential bar born at the facet, never zero-length.
`±Infinity` are ordinary values (a `+Infinity` cell enters at `+Infinity`). Perseus `-1` reads as NaN, the writer
refuses infinities. A NaN from an upstream bug silently masks (known cost), unless masks are OFF: `nanMask = false`
(last parameter everywhere: `CubicalGridStream`, `Image`, `CubicalImage` array constructors, Perseus/DIPHA readers,
MATLAB `nanMask`, CLI `--nan-mask`) makes a NaN an error naming the pixel (`CubicalGridStream.requireNoNaN`: eagerly
for a flat array, at first read for a function-built grid). Masked images are in the random
generators of `FastRepresentativesSpec`/`PackedCubicalCohomologySpec`, so every engine-equality check covers them
(`WORKLOG-nan-mask.md`). Images built by `CubicalImage.fromFlatArray` are
`FlatCubicalGridStream`s; engines read `topCellValues`, never `topCellValue` per pixel. Quote benchmarks WITH
representatives (project lead): `reps=none` is a cost breakdown, not a mode.

**At ambient dim `>= 3`**, cohomology handles residual middle dimensions `1..d-2` (no duality shortcut): what
`CellularCohomologyEngine.persistentHomology` gives on a `LimitedCubicalGridStream` hiding real top cells, degrees
`<= d-2` (chunks there made the 3-D hybrid 3-7x SLOWER than plain cohomology -- `WORKLOG-fast-cubical-representatives.md`).
The truncating views declare `homologyDegreeLimit = maxDim - 1`, so the involution skips the artificial top degree.
Cross-validated at d=3 + one d=4 smoke test; not validated d≥5, win shrinks with d by design.
`.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`.

**Representatives are packed** (`PackedChain`, `WORKLOG-compact-representatives.md`): both grid engines return
chains whose cells are doubled-grid keys, DISTINCT and SORTED under the chain's own `Ordering` (fast engine:
`cubeOrdering`, which is the row-major key order; packed engine: `GridCellOrder`, rank then key, reversed for cycles),
coefficients nonzero, decoded by `GridCubes`. Nothing checks the order at runtime, and equality is order-blind (formal
sums), so `FastRepresentativesSpec`/`PackedCubicalCohomologySpec` check every representative's terms ascend under its
own ordering (mutation-checked) and assert `isPacked` (else a silent fallback passes everything). A representative
keeps its decoder and its `Ordering`: neither may capture an engine (the inner-class `olderFirst` once pinned the whole
packed engine). Grids whose doubled grid exceeds `Int` fall back to heap chains (`GridCubes.fits`).

**`PackedCubicalCohomologyEngine`** (public since `WORKLOG-openness-audit.md`; `WORKLOG-cubical-performance.md`) computes that cohomology, and
`Engine.Cohomology` on any `CubicalGridStream` (cocycles native, cycles by its own packed involution): Ripser's
reduction on doubled-grid indices packed as `(rank << 32) | index` (the generic engine's order: value, then encoding
ascending), clearing, apparent pairs rebuilt on demand. It must equal `CellularCohomologyEngine` on the same cells --
whole grid or `LimitedCubicalGridStream` -- bar for bar, in list order, cocycles and cycles term for term, apparent
pairs on or off (`PackedCubicalCohomologySpec`; `FastRepresentativesSpec`'s 3-D/4-D cases compare it with the
generic engine through the eager reference): re-run both after any change. Stored columns are COMBINED on drain
(each cell once): leaving repeats in made 64³ noise take 40 s instead of 4. Each reduction step must move the pivot
on (`orderBug` throws otherwise): a column/order mismatch would loop forever, not fail.

**Memory of the grid engines** (`WORKLOG-cubical-memory.md`, #56): peak memory is the stored columns and the per-cell
tables, so they stay primitive. A reduced `Column` is `Int` keys and coefficient references, and the cycles' chain of a
reported bar IS that column's arrays (a heap pops in the chain's order, youngest first): never mutate a column or a
chain's arrays. An apparent pair's column is never stored nor drained into a `Column`: its cells are distinct with
coefficients ±1, so it is pushed straight from the cell's coboundary or boundary. Tables by cell (`rowOfCell`,
`seedOfDeath`) are dense `Int` arrays (a cell is the birth or the death of one row, never both), `Rows` is allocated at
its upper bound, reduction logs are primitive arrays (`Log`). In a hot loop: no tuple, no `Array.fill(n)(v)` or
`for x <- arrayOfLongs` (both box), no `Option` from a map (`getOrNull`); a JFR allocation profile
(`jdk.ObjectAllocationSample`) finds each. **A JVM's RSS under a pinned heap (`--jvm-fixed-heap`) is allocation until the first
collection, not need** (the sweep's warm run is several computations in one JVM): the bench's default is the compact
policy (serial collector, heap following the live data), the one to compare memory with; quote the heap a run needs
(`-Xmx` where it still runs at its speed), not RSS alone.

**Relative to the boundary** (`WORKLOG-truchet-tiles.md`): `Relative.ofBoundary(grid)` divides out `grid.onBoundary` (cubes with a point
coordinate at the first or last lattice position; closed under faces) and runs the general cohomology engine (18.6 s at 256², #291).
`FastCubical` takes it as a plain image: bars of degrees 0 and 1 of the pair = bars of the image in a frame one pixel wide below every
value, minus the bars born at the frame's value; the pair's top class is born where the frame's loop dies (`RelativeSpec`, tie-heavy
and masked images). The fast path's representatives cost the sum of the dying regions' sizes, kept small on a tiling by the equal-births rule above.
