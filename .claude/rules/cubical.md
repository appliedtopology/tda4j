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
with a message (they made faces enter after cofaces). Images built by `CubicalImage.fromFlatArray` are
`FlatCubicalGridStream`s; engines read `topCellValues`, never `topCellValue` per pixel. Quote benchmarks WITH
representatives (project lead): `reps=none` is a cost breakdown, not a mode.

**At ambient dim `>= 3`**, the cohomology engine handles residual middle dimensions `1..d-2` (no duality shortcut)
via `CellularCohomologyEngine.persistentHomology` on a `LimitedCubicalGridStream` hiding real top cells, keeping
degrees `<= d-2` (chunks there made the 3-D hybrid 3-7x SLOWER than plain cohomology; with cohomology it is faster at
every measured size -- `WORKLOG-fast-cubical-representatives.md`). The truncating views declare
`homologyDegreeLimit = maxDim - 1`, so the involution skips the artificial top degree. Cross-validated at
d=3 + one d=4 smoke test; not validated d≥5, win shrinks with d by design.
`.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`.
