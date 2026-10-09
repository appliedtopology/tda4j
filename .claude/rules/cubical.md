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
`WORKLOG-fast-cubical-engine.md`, `WORKLOG-fast-cubical-representatives.md`.

**Order in rank space, compare on values** (`WORKLOG-cubical-performance.md`): both union-finds order cells by pixel
RANK (`GridRanks`: dense ranks under `java.lang.Double.compare`; a cell's rank is the minimum over its containing
pixels) with stable counting sorts fed in tie-break order (H₀ edges generated encoding-descending, sorted rank
ascending; dual facets encoding-ascending, rank descending), but every elder-rule and zero-length test compares the
VALUES `distinct(rank)` with IEEE `<=`/`!=` (-0.0 == 0.0), as the cube-based code did: comparing ranks changes outputs
(mutation-checked; `FastRepresentativesSpec`'s `signedZeroMerge` is the case random images miss). NaN pixels are refused
with a message naming the pixel (they made faces enter after cofaces), by EVERY engine: `CubicalImage.fromFlatArray`
checks at construction, `CubicalGridStream.topCellValues` at first read (a grid built from a function), `GridRanks`
again; one message, `GridRanks.nanPixel`. The mask (never enters) is `+Infinity` in grid values, so `-Infinity` in a
superlevel image's own units (`fromFlatArray` negates first): docs and messages in user units must say both. Images built by `CubicalImage.fromFlatArray` are
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

**`PackedCubicalCohomologyEngine`** (`private[tda4j]`, `WORKLOG-cubical-performance.md`) computes that cohomology, and
`Engine.Cohomology` on any `CubicalGridStream` (cocycles native, cycles by its own packed involution): Ripser's
reduction on doubled-grid indices packed as `(rank << 32) | index` (the generic engine's order: value, then encoding
ascending), clearing, apparent pairs rebuilt on demand. It must equal `CellularCohomologyEngine` on the same cells --
whole grid or `LimitedCubicalGridStream` -- bar for bar, in list order, cocycles and cycles term for term, apparent
pairs on or off (`PackedCubicalCohomologySpec`; `FastRepresentativesSpec`'s 3-D/4-D cases compare it with the
generic engine through the eager reference): re-run both after any change. Stored columns are COMBINED on drain
(each cell once): leaving repeats in made 64³ noise take 40 s instead of 4. Each reduction step must move the pivot
on (`orderBug` throws otherwise): a column/order mismatch would loop forever, not fail.
