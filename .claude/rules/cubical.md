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
Representatives: each top cell's sign relative to its dual component's root lives in `SignedUnionFind` (path
compression composes signs; merges never touch members); a reported bar's cycle is the boundary of the dying region,
read off the frozen merge-forest subtree. Never go back to per-component coefficient maps: copying them on every merge
was quadratic on a bright object on a dark background (80 s at 200²). Any change must keep `FastRepresentativesSpec`
(equality with `EagerFastCubicalReference`/`EagerFastAlphaReference`, term for term) green.
`WORKLOG-fast-cubical-engine.md`, `WORKLOG-fast-cubical-representatives.md`.

**At ambient dim `>= 3`**, `chunks` handles residual middle dimensions `1..d-2` (no duality shortcut) via
`CellularPersistenceInChunksEngine` on a `LimitedCubicalGridStream` hiding real top cells (`chunks`'s own
`maxDim=d-2` already discards the incomplete bars this would otherwise wrongly leave open). Cross-validated at
d=3 + one d=4 smoke test; not validated d≥5, win shrinks with d by design.
`.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`.
