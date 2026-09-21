# Union-find acceleration inside the clearing engine (2026-09-20)

Design note, written before any code, in response to: "We should be able to introduce union find
acceleration to the clearing engine, right? With dual union find kept as an option for when it really
matters?" This supersedes `.claude/DESIGN-fast-cubical-engine.md`'s Phase 1/2 plan (a wholly separate new
engine class, genericizing `SimplicialHomologyByDimensionContext` as a stepping stone) — that plan was the
right shape when the target was a standalone cubical engine; it's the wrong shape once the target is the
existing production `CellularPersistenceInChunksContext` itself. `DESIGN-fast-cubical-engine.md`'s
survey of the literature (CubicalRipser, Flash Cubical arXiv:2606.04801, discrete Morse theory) and its
correction to CLAUDE.md's Wagner-Chen-Vuçini citation both stand; only the "what do we build" section changes.

## Why this is lower risk than the earlier abandoned attempt

CLAUDE.md already records a raw-`UnionFind` port being scoped out once: "a live design attempt found needs
delicate, not-yet-fully-converged elder-rule/V-column coefficient bookkeeping to stay correct for a LATER
dimension-2+ cell's reduction." That attempt targeted `CellularHomologyContext` (the naive engine), which
reconstructs representative cycles via V-columns for `barcodeAt` — a union-find fast path there has to keep a
V-column consistent for every dimension-0/1 cell it resolves without going through the general reduction, which
is exactly the bookkeeping that didn't converge.

`CellularPersistenceInChunksContext.HomologyState` was re-read in full (`Homology.scala`) to check whether the
same hazard applies here. It doesn't: the class has no `generators`/V-column field anywhere. State is
`boundaries` (cleared pivots' reduced chains), `cleared`/`paired`/`essentialSimplices` (membership sets),
`killer` (pivot -> killer), `R` (per-cell partially-reduced chain, used only by `markActiveEntries`/
`compress`/`globalReduce`), `activeRows`, `barcode`. This matches the MATLAB facade's own documented behavior
(`engine=chunks` always throws `UnsupportedOperationException` for `cycleVertices`/`cycleCoefficients`) — there
is no representative-cycle machinery to keep consistent. This is the reason the same idea is safe to try again
here even though it wasn't safe there.

## What's actually being replaced, precisely

Traced `processCell`/`recordPair`/`markColumn`/`eliminationFallback`/`compress`/`globalReduce` end to end for
dimension 0 and 1 specifically (`Homology.scala`):

- **Dimension 0 (vertices) is already free.** A vertex's boundary is always empty (`Chain.from(Seq.empty)`), so
  `processCell` on any vertex always takes the `isZero` branch and marks it essential — trivial, O(1), no `R`
  entry is ever created for a vertex regardless of the fast path. The delta=0 iteration of both `advanceAll`
  loops is already a no-op beyond this. **The real cost is dimension 1 (edges)**, which go through the full
  `Chain.reduceByUntil` machinery (`processCell` locally per chunk, then `compress`/`globalReduce` globally) —
  and edges vastly outnumber the spanning-tree edges that actually pair with a vertex, so most of dimension 1's
  cost is spent reducing cycle-forming edges down to zero, the exact computation elder-rule union-find replaces
  directly.
- **The replacement is mathematically forced to agree, not just empirically likely to.** `Chain.reduceByUntil`'s
  `reduceLoop` (`Chain.scala`) repeatedly takes the *smallest remaining term* under `Ordering[CellT]` (a
  `mutable.TreeMap`, so this is independent of insertion order) and substitutes `basis`/`boundaries` for it if
  present — a standard Gaussian-elimination fixpoint. Given a fixed total order (`filtrationOrdering`, already
  guaranteed total by this codebase's own tie-break discipline — see CLAUDE.md's "Bug found while
  cross-validating" section), the reduced boundary matrix in any two dimensions is canonical: it does not depend
  on *what order* individual columns get reduced in, only on the final `basis` contents. This is *why* the
  chunked local/global two-phase algorithm is correct in the first place (Bauer/Kerber/Reininghaus-style
  clear-and-compress: local, deferred, then global passes all converge to the same canonical answer). A
  union-find pass over dimension-0/1 cells, processed strictly in `filtrationOrdering`, computes exactly this
  same canonical reduced matrix restricted to dimensions 0/1 — it is a different, cheaper *algorithm* for the
  identical *linear-algebra problem*, not an approximation of it. `SimplicialHomologyByDimensionContext`'s own
  cross-validation (100+ random VR clouds against `SimplicialHomologyContext`) is existing empirical evidence of
  exactly this equivalence, just for a different engine.
- **No `R` entry is needed for cycle-forming ("survivor") edges.** Traced every place `R`/`killer` get consulted
  for a lower cell: `markColumn(k)` only iterates `R.keys` (so a cell with no `R` entry is simply never visited
  as `k`), and when a higher cell's own `R_k` chain contains a lower cell `i` as a term, `markColumn`'s branches
  only inspect `killer.get(i)` when `i ∈ cleared` — never for `i` that is merely `paired` or merely
  `essentialSimplices`. Since vertices never appear in anything but a dimension-1 cell's own boundary (only
  edges reference vertices; 2-cells reference only edges, etc.), a killed vertex's `boundaries`/`killer` entry
  is *only ever consulted by the union-find pass itself*, never by dimension >= 2 processing. And a survivor
  edge (not cleared, not paired — elder-rule found no unvisited-component partner) needs only
  `essentialSimplices += edge`, exactly the terminal state `processCell` would leave it in when its own
  boundary reduces to zero — nothing downstream (`markColumn`, `eliminationFallback`, `compress`,
  `globalReduce`) ever looks up `R` for a cell that is merely essential-so-far.

**Conclusion**: dimension 0 and 1 can be resolved *entirely* by a union-find pre-pass — populating `cleared`,
`paired`, `killer`, `boundaries`, `barcode(0)`, `essentialSimplices` for every dimension-0/1 cell up front — and
`advanceAll`'s two loops (local `processCell`, then global `compress`/`globalReduce`) can then skip delta=0 and
delta=1 entirely, starting at delta=2, with no change needed to how they treat dimension >= 2. This is a fully
self-contained splice: nothing above dimension 1 is touched, and nothing above dimension 1 needs the pre-pass to
have populated anything beyond what's listed above.

## Scope, per your question

1. **Generic dimension-0/1 union-find, always on, inside `CellularPersistenceInChunksContext`** — works for any
   `CellT: OrderedCell` (`Simplex`, `Cube`, `FiniteSimplicialSet` generators alike), since elder-rule union-find
   over a graph's vertices/edges has no cubical-specific precondition. This makes
   `SimplicialHomologyByDimensionContext` redundant as a *standalone* class once it's cross-validated in place —
   its whole reason for existing (a validated elder-rule implementation) now lives inside the engine that's
   actually used everywhere. Per your steer, it gets deleted once the chunks path is cross-validated against it,
   as its own separate commit — not in the same change, so it stays available as an independent oracle during
   development (this was advisor's specific caution, and it's a cheap one to honor).
2. **Dual union-find for the top dimension, opt-in, cubical-only** — Flash Cubical's actual contribution
   (arXiv:2606.04801): valid only when every (d-1)-facet has exactly 1 or 2 d-dimensional cofacets, true for a
   cubical grid, not true in general (a VR triangle's edge can have arbitrarily many triangle cofacets). This
   cannot be a default on the generic class. Concretely: a constructor flag or a small `Cube`-specific subclass
   (parallel to how `PersistenceInChunksContext` is `Simplex`-specific) that additionally resolves the
   top-dimension-vs-second-top-dimension pairing via the dual graph, leaving every middle dimension to the
   generic machinery unchanged. Scoped as phase 2, after phase 1 is shipped and measured — the capacity sweep
   (`WORKLOG-cubical-capacity-sweep.md`) already shows dimension 1 (edges) is by far the largest cell count in a
   3D grid, so phase 1 alone is where most of the win should be.

## Validation plan

Same discipline as every other engine change in this codebase:

1. Hand-derived, tie-heavy fixtures first (reuse `PersistenceInChunksSpec`'s pinned
   `tetrahedronBoundaryDegenerateCells`, `elderRuleExpected`, the 3-cycle-graph/filled-triangle fixtures — these
   already exercise degenerate all-tied filtrations, exactly the regime an order-dependent bug would show up in
   first, per advisor).
2. Cross-validate against `CellularHomologyContext`/`SimplicialHomologyContext` (the untouched naive engine, a
   different algorithm with no shared code) on:
   - 100+ random Vietoris-Rips point clouds (`Simplex`, mirroring `SimplicialHomologyByDimensionSpec`'s own
     existing coverage),
   - the existing cubical tie-heavy fixtures (`Cube`),
   - the `torus`/RP²/RP³ `FiniteSimplicialSet` fixtures, including the non-dimension-aligned filtration
     `WORKLOG-simplicial-set-filtration.md` used to discriminate a reversed `filtrationOrdering` — the closest
     existing fixture to something that would expose a union-find-vs-chunked-order disagreement, if one exists.
3. Keep `SimplicialHomologyByDimensionContext` alive and green until (2) passes, then delete it in its own
   commit.
4. Re-run `CubicalProfileDriver`'s capacity sweep (`WORKLOG-cubical-capacity-sweep.md`'s methodology) to measure
   the real improvement, rather than citing Flash Cubical's own numbers.

## What's deliberately not being done in this pass

- No lookup-table/local-pruning constant-factor optimizations (Flash Cubical's other two contributions) — real,
  but layered on top of, not required for, the union-find replacement itself.
- No discrete Morse reduction — orthogonal technique, `DESIGN-fast-cubical-engine.md`'s Phase 4 note about not
  bundling it still applies.
- No change to dimension >= 2 handling at all in phase 1.
