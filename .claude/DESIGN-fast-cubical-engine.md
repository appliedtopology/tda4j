# Design note: a union-find/duality-based fast cubical engine

2026-09-20. Follow-up to `.claude/WORKLOG-cubical-capacity-sweep.md` ("how large can we comfortably go") —
"if we lean on union-find and discrete Morse theory, what can we do about cubical performance?" This is a
design analysis, not an implementation log: no code has been written yet.

## First, a correction to CLAUDE.md's own citation

CLAUDE.md's cubical section names "the Wagner-Chen-Vuçini 'Efficient Computation of Persistent Homology for
Cubical Data' approach (union-find for dimension 0, discrete-Morse-style reduction for higher dimensions)" as
an unattempted future direction. Having now actually read that paper (Wagner, Chen, Vuçini, TopoInVis 2011/2012):
**it does neither of those things.** Its actual contribution is *CubeMap*, a compact array-based cubical
representation exploiting coordinate parity for O(1)-amortized boundary/coface lookup (Θ(2^d n) memory,
Θ(3^d n + d²2^d n) time) feeding the *standard* matrix-reduction algorithm — no union-find, no discrete Morse
theory anywhere in it. Union-find appears in that paper only once, cited as *someone else's* prior technique for
dimension-0 persistence in the related-work section. This looks like a citation mix-up from whichever earlier
session wrote that CLAUDE.md sentence, not a real description of the paper. Worth fixing in CLAUDE.md directly
in a future pass — not done here since this file is analysis, not the shipped-state doc.

Usefully, though: CubeMap's core idea (implicit coordinate-parity boundary lookup, no stored pointers) is
something tda4j's own `Cube` opaque type *already does* — `Cubical.scala`'s doubled-coordinate encoding and its
boundary formula work the same way. So this paper doesn't hand tda4j a new lever; it confirms the representation
choice already made here was the right one.

## What the literature actually says, with real numbers

Three genuinely different, real techniques, only one of them "discrete Morse theory":

1. **CubicalRipser** (Kaji, Sudo, Ahara 2020) — reproduces Ripser's clearing + apparent-pairs optimizations for
   cubical complexes: a large fraction of persistence pairs are "trivial" (paired with a face/coface at the same
   filtration value) and can be identified locally without ever building or reducing their column. This is
   CLAUDE.md's *other* named direction, and it's real and shipping (a maintained tool).

2. **Flash Cubical** (Le Breton, Szustakowski, Piraud, arXiv:2606.04801, June 2026 — found during this session's
   research, not previously known to this codebase) — the most directly relevant paper found. Three ideas,
   composed:
   - **Dimension 0 via union-find**: the standard, already-familiar technique (and already implemented and
     validated in tda4j, just for `Simplex`, not `Cube` — see below).
   - **Dual union-find for the TOP dimension too**: the paper's actual novel contribution. If every
     top-dimension-minus-one facet has exactly 1 or 2 top-dimensional cofacets (always true for a cubical grid),
     build a *dual graph* (top cells → dual vertices, shared facets → dual edges, boundary facets → one shared
     "infinity" dual vertex) and run union-find on it in *reverse* filtration order, in a cohomological sense
     (dual positive cells are negative in the ordinary-homology sense, and vice versa — a real sign/orientation
     inversion to get right, the same class of care this codebase's own `RipserCohomologyContext` derivation
     needed). Consequence: **in 2D, this covers H0 AND H1 completely by union-find — no matrix reduction at
     all.** In 3D, H0 and H2 are covered by (dual) union-find, leaving only H1 to real reduction, and only on
     the cells NOT already paired off by the other two.
   - **Local pruning + lookup tables**: a purely constant-factor layer (identify trivially-classified
     "zero-persistence" cells before running union-find at all, via a precomputed table of the ~16k distinct
     local vertex-neighborhood configurations in 3D rather than the naive 2²⁶ — cubical local regularity makes
     this table small and reusable across the whole grid).
   - **Reported numbers** (Intel i7-12650H, 16GB RAM, single-threaded, F2 coefficients only): on a 128³ real
     volumetric dataset (Bonsai, ~2.1M cells) — Flash Cubical 0.53s/194MB vs. CubicalRipser 2.94s/440MB vs.
     GUDHI 7.28s/1384MB. **For comparison, tda4j's OWN chunks engine on the same cell count (n=64 in
     `.claude/WORKLOG-cubical-capacity-sweep.md`) took 90.2s and 10.26GB** — not an apples-to-apples comparison
     (different hardware, JIT vs. native, and tda4j is generic over arbitrary coefficient fields/cell types where
     Flash Cubical is F2-only cubical-only), but the gap is wide enough that "there is real headroom here" isn't
     in question, even allowing generously for those confounds.

3. **Discrete Morse theory** (Robins–Wood–Sheppard 2011 for constructing a discrete Morse function directly on a
   grayscale cubical image; Mischaikow–Nanda 2013, `MorseReduce`, for the general filtered-complex version) — a
   genuinely different, more general lever: greedily build an *acyclic partial matching* pairing most cells with
   an adjacent face/coface, leaving a much smaller set of "critical cells." The resulting *Morse chain complex*
   on just the critical cells is proven chain-homotopy-equivalent to the original, so it has the *same*
   persistent homology — but its own boundary maps have to be reconstructed from the matching via V-path
   counting, which is real additional algorithmic machinery, not just "fewer cells for free." Crucially: **this
   is complementary to, not a substitute for, technique 2** — you could Morse-reduce first, then run the
   union-find/duality pipeline on the (much smaller) Morse complex. Neither Flash Cubical nor CubicalRipser use
   discrete Morse theory at all, and both already report large wins without it, so its marginal value stacked on
   top of technique 2 is real in principle but *unmeasured* by anything I found — this is the highest-effort,
   least-validated-in-combination piece of the three.

## What tda4j already has vs. what's genuinely new

**Dimension-0 union-find is not new science for this codebase.** `SimplicialHomologyByDimensionContext`
(`Homology.scala`) already does exactly this — validated, cross-checked, five bugs found and fixed
(`.claude/WORKLOG-mst-and-perf.md`) — for `Simplex[VertexT]`. Its current, fixed implementation goes through
the generic `Chain.reduceBy`/`OrderedCell.boundary` interface for dimension-1 cells (not raw simplex-vertex
extraction), which is exactly the shape that made `PersistenceInChunksContext` a clean, low-risk genericization
into `CellularPersistenceInChunksContext` in an earlier session (see CLAUDE.md's "Simplicial sets" section,
"genericized in a later session"). **The first, lowest-risk step here is checking whether
`SimplicialHomologyByDimensionContext` has the same property (zero actual `Simplex`-specific behavior in its
body) and, if so, genericizing it the same proven way** — not a new algorithm, a proven refactor pattern applied
a third time.

**Dual union-find for the top dimension is genuinely new** — nothing in this codebase does anything like it.
But it's tractable specifically *because* `CubicalGridStream` is a regular grid: the facet→{1,2 top cells}
adjacency Flash Cubical's dual graph needs is directly computable from cube coordinates (this is exactly the
`containingTopCells`/coface machinery `CubicalGridStream` already has, just consumed in the opposite direction),
not a general "coboundary of a coboundary" computation over an arbitrary `OrderedCell`.

**Honest tempering of "dimension-0 alone" as a first cut**: in a 3D T-construction grid, dimension-0 cells are
only about 1/8 of the total cell count (roughly evenly spread across 0/1/2/3 dimensions) — so union-find on
dimension 0 alone, without the dual top-dimension trick, leaves ~7/8 of cells still going through the general
reduction path. **The dual union-find piece is where the actual memory/time win from the capacity sweep lives**,
not the dimension-0 piece by itself.

## Proposed phasing

A new, dedicated class (tentatively `FastCubicalHomologyContext` or similar) — NOT a modification of
`CellularHomologyContext`/`CubicalHomologyContext`, matching this codebase's own established pattern
(`RipserCohomologyContext`/`PackedRipserCohomologyContext` are separate engines cross-validated against the
generic reference oracle, never edits to it).

1. **Phase 1 (low risk, likely modest win alone)**: investigate whether `SimplicialHomologyByDimensionContext`
   generalizes the same way `PersistenceInChunksContext` did; if so, genericize it and wire up
   `CubicalHomologyByDimensionContext` as a one-line wrapper, giving dimension-0 via the already-validated
   elder-rule union-find. Cross-validate against `CubicalHomologyContext` on existing tie-heavy fixtures plus new
   property tests. Expected impact: modest on its own (~1/8 of cells in 3D), but validates the "port to `Cube`"
   mechanics cheaply, on a real precedent rather than a first-of-its-kind change.

2. **Phase 2 (the real win, higher effort, genuinely new)**: dual union-find for the top dimension, specialized
   to `CubicalGridStream`'s regular grid (facet-to-top-cell adjacency computed directly from coordinates, not a
   generic coboundary walk). Covers 2D completely; in 3D, leaves only H1 — computed on the residual cells not
   already paired by phases 1-2 — to the existing, already-correct `Chain.reduceBy` machinery. This is where the
   actual memory reduction should come from: replacing several parallel `Map[Cube, Chain[Cube, CoefficientT]]`
   structures (the current `HomologyState`'s design, general-purpose by necessity) with plain integer-indexed
   union-find arrays for the large majority of cells.

3. **Phase 3 (optional, lower priority, pure constant factor)**: local pruning + lookup tables. Not needed for
   correctness — matches this codebase's own repeated pattern (see `.claude/WORKLOG-ripser-profiling.md`'s
   multiple follow-up sessions) of shipping correctness first, then chasing the remaining constant factor once
   real profiling data exists on the ACTUAL implementation, not a priori.

4. **Phase 4 (separate, bigger, not recommended to bundle with 1-3)**: discrete Morse reduction as an additional
   preprocessing layer ahead of phases 1-3. Real, valuable in principle, but its own correctness burden
   (V-path boundary reconstruction on the Morse complex) is comparable in size to phases 1-3 combined, and its
   marginal benefit stacked on top of a working union-find/duality pipeline is unmeasured in anything found
   during this research pass. Treat as a later, separately-scoped investigation once 1-3 are shipped and
   measured, not a first-cut requirement.

## Validation plan (per this codebase's own established discipline)

Same sequence CLAUDE.md's own "Cubical complexes" validation section already used for `CubicalGridStream`
itself, since that ordering (dd=0, monotonicity, structural invariant, hand-derived tie-heavy fixtures with an
exact bar-count via the spanning-tree/Euler-characteristic argument, independent union-find-over-present-pixels
H0 oracle) is exactly built to catch the tie-heavy/degenerate cases a grid-specific algorithm is most likely to
get wrong — NOT leading with cross-validation against `CubicalHomologyContext` on random large grids (an
unvalidated new engine against an already-trusted one can make a bug in either look like a bug in the other, the
same trap this file's own Helix/DQP history and the cubical validation section both already document). Once the
hand-derived fixtures pass, cross-validate against `CubicalHomologyContext` on 100+ random small grids, THEN
re-run `.claude/WORKLOG-cubical-capacity-sweep.md`'s exact sweep methodology to measure the real speedup/memory
reduction on this codebase specifically — not cite Flash Cubical's own numbers as if they transferred directly.

## Recommendation

Start with Phase 1 (genericize `SimplicialHomologyByDimensionContext`, cheap and low-risk on a proven pattern),
then Phase 2 (the dual union-find piece, where the real capacity-sweep-relevant win lives) — both before
touching Phase 3 or considering Phase 4 at all. Not started in this session; this file is the design record for
whichever session picks it up next.

## 2026-09-25 update: Phase 1 status, the concrete Phase 2 algorithm, and representatives

Picked up by the session executing `.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 6.
`.claude/WORKLOG-fast-cubical-engine.md` has the full narrative; this section records only the durable design
content, per this file's own role as a design record rather than a session log.

### Phase 1: already done, just needed a name

`SimplicialHomologyByDimensionContext` (this file's own name from the 2026-09-20 pass) does not exist under
that name in the current source — searched exhaustively, including every worklog that mentions it, and found
no evidence it was ever a real, distinct class rather than a misremembered reference to `CellularPersistenceInChunksContext`'s
own `unionFindDim01`. That method IS already fully generic over `CellT: OrderedCell` (confirmed: `streams.UnionFind`,
the reusable class, is used nowhere in `Homology.scala` at all — `unionFindDim01` is its own self-contained,
already-generic array-based union-find), and `CellularPersistenceInChunksContext[Cube, ...]` was ALREADY
cross-validated against `CubicalHomologyContext` directly in a later, undocumented-in-this-file session
(`CubicalStreamSpec`'s "matches the naive engine" and "union-find fast path agrees with the naive engine on
random tie-heavy images" sections, plus `CubicalBenchmarkSpec`/`CubicalProfileDriver`). The only genuinely
missing piece was the ergonomic one-line wrapper this file's own Phase 1 description asked for
(`CubicalPersistenceInChunksContext`, mirroring `PersistenceInChunksContext`'s relationship to `Simplex`) — added,
zero new behavior, nothing else needed.

### Phase 2: the exact algorithm, derived from Alexander duality (no paper or reference implementation was
### reachable this session — arXiv, Dagstuhl-style mirrors, and every non-arXiv summary site tried all returned
### `EGRESS_BLOCKED` from this session's own network policy, and no GitHub implementation of Flash Cubical
### exists to `add_repo` the way GUDHI's edge-collapse module did for a different item this session — this is
### an independent derivation, not a port, and should be read with that in mind)

**Setup.** Top cells (dimension `d` = ambient dimension) are dual VERTICES; codimension-1 cells (dimension
`d-1`, "facets") are dual EDGES, each connecting the 1 or 2 top cells containing it as a face (always exactly 1
or 2, for a cubical grid — `CubicalGridStream.containingTopCells` on a codimension-1 cube gives exactly this).
A facet touching only ONE top cell (a boundary facet of the whole grid) connects that one top cell to a single
shared auxiliary dual vertex, `∞`. Assign `∞` the value `+Infinity` (see below for why).

**Claim**: primal `H_{d-1}` of the sublevel filtration equals ordinary `H_0` of the SUPERLEVEL filtration on
this dual graph (dual-vertex/edge value = the corresponding primal cell's own T-construction filtration value,
`∞` fixed at `+Infinity` so it is a member of every superlevel set), computed by processing dual
vertices/edges together in a SINGLE descending-value pass with the SAME elder-rule array-based union-find
`unionFindDim01` above already uses, with two translations: (1) every bar's `(birth, death)` pair is REPORTED
SWAPPED — a merge event at dual-superlevel value `s` becomes a primal bar endpoint at primal value `s`, but
whichever of {the merging component's own birth value, `s`} was the *raw* union-find birth becomes the primal
*death*, and vice versa; (2) the component containing `∞` is discarded entirely — it is Alexander duality's own
unbounded/reduced-homology reference component, not a real primal class.

**Why**: primal sublevel sets GROW as the parameter increases; by Alexander duality (`H_{d-1}(X) ≅ H^0(S^d
\ X)`, treating the grid as sitting inside `S^d` via one-point compactification, `∞` being that one point),
the complement SHRINKS as the primal parameter increases, so tracking the complement's OWN connectivity means
processing it in the OPPOSITE (descending) direction — a component of the complement "splits" (in real,
increasing time) exactly when, read backward (decreasing time, i.e. the complement's own natural GROWING
direction), two pieces of complement FIRST become connected — an ordinary union-find MERGE event in the
backward reading. This is why birth and death swap: a merge that happens "late" in the backward/superlevel
reading (small `s`) is an event that happens "early" in real forward time, and a class that dies in a merge at
raw value `s` in the backward reading is a class that is BORN there in real time, `s` also being exactly when
its two dual pieces most recently became distinguishable going forward — the standard cohomological
birth/death inversion (consistent with `CLAUDE.md`'s own "over a field, cohomology and homology barcodes
coincide" once the swap is applied — this construction computes something intrinsically cohomological in
flavor, which is why the swap is needed to read it back as an ordinary homology bar).

**Verified against a concrete, hand-computable example before writing any code** (not trusted from the
abstract argument alone — this codebase's own repeated lesson about ordering/duality claims): a 3x3 pixel grid,
the 8 border pixels at value 0 and the center pixel at value 1 (`.claude/WORKLOG-fast-cubical-engine.md` has
the full arithmetic). Ground truth, independently reasoned: at `t=0` every cell of the full 3x3 grid is present
EXCEPT the single center 2-cell (every edge/vertex touching the center pixel is ALSO a face of an adjacent
0-valued border pixel, hence already present) — a disk with one open top cell removed, homotopy equivalent to
a circle, so `H_1 = Z`, born at 0; filling the center cell at `t=1` kills it. Expected bar: `(0, 1)`. Dual
graph: 9 pixel-vertices + `∞` (24 total facets/dual-edges, all at value 0 in this fixture since every one
touches a 0-valued pixel). Superlevel/descending processing: at `s=1`, only the center pixel and `∞` are
present, as two separate singleton components (no edges yet, all facet values are `0 < 1`); at `s=0`, all 8
border pixels and all 24 edges enter at once, merging everything (the pixel grid graph is connected, and
boundary pixels link to `∞`) into ONE component. Elder rule: `∞`'s component (born at `s=+Infinity`, older)
survives; the center-pixel component (born at `s=1`) dies at `s=0`. Raw dual bar: birth=1, death=0. Swapped:
primal `(0, 1)` — exactly the hand-derived ground truth. (A real, easy-to-make mistake caught in the same
pass: `∞` must be `+Infinity`, not `-Infinity` — the natural-seeming "always active" framing "-Infinity" gives
the WRONG superlevel-set membership, since `{value >= s}` requires `+Infinity`, not `-Infinity`, to always
qualify.) Also confirms a real worry dissolves on inspection: of the 24 dual edges, only 9 are needed for a
spanning tree of the 10 dual vertices — the other 15 are simply skipped by ordinary union-find (already
same-component when reached), exactly the same "redundant edge, no event" case ordinary H_0 union-find already
handles for any graph denser than a tree; no special handling needed.

**Scope decision, made explicit rather than silently assumed**: shipping the ambient-dimension-2 case only
this pass (`H_0` via the existing, already-validated primal union-find, `H_1` via the dual union-find above,
covering 2D completely with zero general `Chain.reduceBy` reduction needed at all — exactly this file's own
original "in 2D, this covers H0 AND H1 completely" framing). Ambient dimension 3 needs an additional, genuinely
harder piece this pass does NOT attempt: `H_1` still needs general reduction on whichever cells are NOT already
resolved by the two union-finds (`H_0`'s own primal one, `H_2`'s new dual one), which means correctly
identifying and removing already-paired cells from what the general machinery sees — a real extension, not
just "run the same thing one dimension higher," deferred to a follow-up rather than attempted half-validated.

### Representatives

The design gap this file itself flagged (Flash Cubical is F2-only and produces none) is resolved by construction
of the dual algorithm above, not needing new machinery: a dual UNION-FIND is, cell-for-cell, the SAME shape as
`unionFindDim01`'s own primal one, so it can carry a REPRESENTATIVE alongside each union exactly the way
`unionFindDim01` already does for dimension-0 bars (`Chain(dyingVertex)`, growing a tree-path sum as unions
happen) — except here the representative lives one dimension down from the dual vertices being unioned:
merging two dual components (top cells) across a shared facet is exactly the elementary move "these two top
cells are now known to be on either side of a facet already accounted for," and the accumulated GENERATOR for
the eventual `H_{d-1}` class is `boundary(coherently-oriented sum of top cells in the younger [dying] dual
component)` — the shared internal facets between top cells of the SAME component cancel in that sum by
construction (each internal facet is a boundary term of both of its two top cells, with OPPOSITE sign once
"coherently oriented" is applied consistently), leaving exactly the facets on that component's own dual
boundary (which, by construction, is the (d-1)-cycle bounding the class). "Coherently oriented": track a
`Field`-valued orientation SIGN per top cell relative to an arbitrarily chosen root of its own dual component
(sign flips by `-1` each time a facet's own two boundary terms toward its two top cells have the SAME sign
rather than opposite — i.e., propagate the relative sign across each dual edge as it is unioned, the same way
a spanning-tree walk propagates a consistent orientation across a graph), maintained incrementally as unions
happen (no separate walk needed after the fact). Test sign correctness over `Fp(3)`, using
`CubicalOrderedCell.scala`'s existing rank-among-non-degenerate-axes sign rule for the actual per-facet
coefficient in each `boundary` term — F2 cannot distinguish a correct alternating orientation from a constant
one, the same reason every other signed-field test in this codebase insists on `Fp(3)` or `Double`.

## Sources

- [Wagner, Chen, Vuçini — Efficient Computation of Persistent Homology for Cubical Data (TopoInVis 2011/2012)](https://chaochen.github.io/publications/chen_topoinvis_2011.pdf)
- [Le Breton, Szustakowski, Piraud — Fast Cubical Persistent Homology on 2D and 3D Images via Union-Find, Pruning, and Lookup Tables (arXiv:2606.04801)](https://arxiv.org/pdf/2606.04801)
- [CubicalRipser (Kaji et al. 2020)](https://github.com/shizuo-kaji/CubicalRipser)
- [Robins, Wood, Sheppard — Theory and Algorithms for Constructing Discrete Morse Complexes from Grayscale Digital Images (IEEE TPAMI 2011)](https://pubmed.ncbi.nlm.nih.gov/21576736/)
- [Mischaikow, Nanda — Morse Theory for Filtrations and Efficient Computation of Persistent Homology (Discrete & Computational Geometry, 2013)](https://people.maths.ox.ac.uk/nanda/source/DMPers-Final.pdf)
