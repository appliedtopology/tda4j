# Replacing the delegate-based chunks representatives with an incremental design (2026-09-20/21)

Follow-up to `.claude/WORKLOG-chunks-representatives.md`, which shipped `CellularPersistenceInChunksContext.
barcodeAt` for dimension >= 1 by delegating the whole representative computation to a fresh, independent
`CellularHomologyContext` run over a dimension-capped copy of the same stream. That design is what this session
replaces -- not because it was incorrect (it was, and passed its own cross-validation), but because the project
lead rejected it directly: "Asking for a full CellularHomologyContext run *as well as* the original run is
nowhere near a reasonable request." The delegate design paid for a full independent sequential reduction over
every cell up to `internalMaxDim` on every `barcodeAt` call, throwing away all of chunks' own already-computed
`boundaries`/`cleared`/`paired`/`killer` state. Per this codebase's own worklog convention (see CLAUDE.md's
"Session practices"), that prior worklog is left as-is -- a point-in-time record of what was tried and why it
looked reasonable at the time -- rather than edited to erase the history. This worklog documents what replaced
it.

The project lead pointed to `PackedRipserCohomologyContext` as existence proof that clearing + optimizations CAN
coexist with inline representative tracking (its `basis`/`generators` maps are threaded directly through the
same reduction that finds pairs, no second pass). The ask: make chunks do the same -- reuse its own state
incrementally, not run a second engine.

A second, load-bearing instruction from the same review: the `Chain` payload had been dropped from
`HomologyState.barcode`'s tuple type in an earlier draft (to make room for the delegate design's own separate
bookkeeping). Restored -- `barcode: mutable.Map[Int, immutable.Queue[(Double, Double, Chain[CellT,
CoefficientT])]]` -- since `recordPair`/`unionFindDim01` already have the right chain in hand at the moment
each bar is recorded, and discarding it there just to reconstruct something equivalent later (or worse, via a
delegate) throws away free information.

## Design, by dimension

**Dimension 0** (unchanged from the prior session): trivial, `Chain(dyingVertex)`/`Chain(rootVertex)` --
verified to match the naive engine's own representatives exactly, no redesign needed here.

**Dimension 1, essential (cycle-forming) edges**: a genuine spanning-forest-path construction, built inside
`unionFindDim01` itself (which already computes the union-find forest for dimension 0/1 -- see
`.claude/WORKLOG-unionfind-in-chunks.md`). `treeEdges` (collected separately from the path-compressed union-find
`parent` array, which has already lost the historical tree shape by the time path compression runs) feed an
adjacency list; one BFS per component computes `pathFromRoot(v)` incrementally, using the fact that every
dimension-1 boundary in this codebase has additive-inverse coefficients on its two endpoints (`cu = -cv`), so
`pathFromRoot(v) = pathFromRoot(u) + (1/cv) ⊠ Chain(edge)` needs no correction term at all. For a cycle-forming
edge `e` with endpoints `(v0, c0)`, `(v1, c1)`: `essentialRepresentatives(e) = Chain(e) - c0⊠pathFromRoot(v0) -
c1⊠pathFromRoot(v1)` -- algebraically verified (`∂(rep) = 0`) and empirically confirmed via
`RepCycleAuditSpec`'s dd=0 checks over a signed field.

**Dimension 1, finite bars**: reuse the SAME tree-path representative `unionFindDim01` already built and stored
in `essentialRepresentatives` before `recordPair` promoted the edge out of `essentialSimplices` -- that map
entry is never removed, so it's still there to read.

**Dimension >= 2, both finite and essential**: `vcolOf`, the actual incremental mechanism this session built.

## `vcolOf`: reconstructing a V-column from chunks' own state, not a second engine

`vcolOf(sigma)` computes, for ANY cell (paired or essential), a chain with leading term `sigma` itself such that
`∂(vcolOf(sigma))` equals sigma's true reduced boundary -- exactly the same quantity `CellularHomologyContext.
advanceOne`'s own `vcol`/`negativeVCols` mechanism computes, but derived by walking chunks' OWN already-resolved
`boundaries`/`cleared`/`paired`/`killer` maps, memoized (`vcolCache`) with cycle detection (`vcolInProgress`) in
case the acyclic-elimination invariant it depends on is ever violated.

Getting this right required re-reading `CellularHomologyContext.advanceOne`'s audited V-column formula VERBATIM
from source twice, after two wrong guesses from memory:

1. **First wrong guess**: `vcolOf`'s reduction used only `boundaries` (cleared pivots) as substitution source,
   no fallback for already-paired terms. This left a dimension-3 pivot's reduction stuck on the first paired
   triangle term it hit (no substitution available), producing `vcol = Chain(pivot)` -- wrong, nonzero boundary.
   Root-caused via a small 8-point-clique diagnostic dumping raw representatives and their boundaries directly.
   Fixed by adding `fallback = (c => if paired.contains(c) then Some(vcolOf(c)) else None)`, mirroring
   `CellularHomologyContext`'s own `negativeVCols` mechanism -- found by reading the real source, not memory.

2. **Second wrong guess**: the fold over the reduction log initially subtracted `coeff ⊠ vcolOf(eliminated)` for
   EVERY eliminated term, including paired-eliminated ones. Wrong per the naive engine's actual code (`if
   negativeVCols.contains(pivot) then acc` -- literally no correction for a paired-eliminated term, since
   substituting through an already-paired cell's own V-column is a pure same-dimension basis rewrite that
   doesn't change the underlying value being tracked, only its expression). Corrected to match, after
   re-reading `advanceOne` line-by-line rather than trusting a half-remembered shape of it.

`pivotOf`/`killerOf`: a paired cell's own pivot is needed (to know where `vcolOf`'s internal reduction should
`stop`, avoiding unnecessary work past the point already known). Initially read via `R.get(sigma).
flatMap(_.leadingCell)` -- wrong for union-find-derived pairs (tree edges), since `unionFindDim01` never
populates `R` at all (see that method's own doc for why it doesn't need to). Fixed by adding `killerOf: mutable.
Map[CellT, CellT]` (the reverse of the existing `killer` map), maintained everywhere `killer` is set, in both
`unionFindDim01` and `recordPair`.

## `barcodeAt`'s assembly

Finite bars: the chain already stored in `barcode` by `recordPair`/`unionFindDim01` -- trustworthy as-is at
dimension 0, but for dimension >= 1 that stored `dsigmaReduced` can be a compressed/self-cancelled value that is
NOT a genuine cycle on its own (`compress`'s "inactive row, self-cancel" shortcut breaks the `R_σ = ∂(V_σ)`
inductive invariant that holds for the uncompressed reduction -- confirmed by `advisor()`, then empirically).
`barcodeAt` therefore extracts each finite bar's PIVOT (the stored chain's `leadingCell`) and calls `vcolOf` on
it (dimension 1: reads `essentialRepresentatives` directly instead, per the note above; dimension 0: the stored
chain is already correct as-is). Essential bars: `essentialRepresentatives.getOrElseUpdate(sigma, vcolOf(sigma))`
-- dimension 0/1 already populated eagerly by `unionFindDim01`, dimension >= 2 computed lazily on first request
and cached.

## The pairing bug this surfaced, and its own fix

Validating `vcolOf` against the naive engine on a tie-heavy clique surfaced a genuine, pre-existing PAIRING bug
in `CellularPersistenceInChunksContext` -- confirmed present on unmodified `HEAD`, unrelated to any
representative-tracking code, and a bigger issue than the original ask. The project lead was asked how to
proceed (chose "root-cause and fix the pairing bug first") and then handed off for autonomous continuation. Full
derivation, root cause (two distinct mechanisms), and fix are in `.claude/WORKLOG-chunks-pairing-bug.md` -- kept
as its own worklog rather than folded into this one, since it's independent of representatives entirely (it
would have mattered even if representatives were never in scope) and is the more consequential of the two
findings.

One detail worth recording here specifically: fixing the pairing bug's second mechanism (`eliminationFallback`'s
paired-cell substitute) reused `vcolOf` itself, called mid-`advanceAll` (inside `compress`/`globalReduce`'s
shared `eliminationFallback`), not just post-hoc from `barcodeAt` as originally designed. This is safe
specifically because the pairing bug's own fix (a reconciliation step before `markActiveEntries`) guarantees
every cell's `cleared`/`paired` classification -- and hence every dependency `vcolOf` might recurse into -- is
permanently fixed before the global phase, and therefore `eliminationFallback`, and therefore `vcolOf`, are ever
consulted. The representative-tracking mechanism and the pairing-bug fix ended up structurally intertwined, not
coincidentally adjacent.

## Verification

- `RepCycleAuditSpec` (throwaway, `.claude/`-adjacent test sources, to be folded into permanent specs and
  deleted): n in [5,8] x maxDim in [2,3], all-tied-at-zero cliques (the worst case for tie-heavy cascading
  resolution) -- `diagramAt` bar counts match naive's exactly, `barcodeAt`'s representatives are genuine cycles
  (`∂(rep) = 0`) at every dimension >= 1, and `barcodeAt`'s own `(dim, lower, upper)` bars match `diagramAt`'s.
- `PersistenceInChunksSpec`'s full existing suite (7 examples, including the pinned degenerate-S^2 fixture) is
  unaffected.
- Full `sbt test`: 264 examples, 0 failures, 0 errors (259 passed, 5 skipped, 1 pending -- matching the
  established deliberate-skip baseline), clean after both the pairing-bug fix and this representative redesign.

## Follow-up, same session: everything above closed out

Temporary `DEBUG`/`println` instrumentation stripped. `RepCycleAuditSpec`'s content folded into
`PersistenceInChunksSpec` as a permanent regression test (bar counts vs naive, cleared/paired non-overlap,
genuine-cycle representatives, on the same n in [5,8] x maxDim in [2,3] tie-heavy sweep) and the throwaway file
deleted. `CubicalStreamSpec`/`FilteredSimplicialSetStreamSpec`/`Tda4jSpec`'s existing representative assertions
re-run directly and confirmed still passing (their own doc comments, which referenced the delegate design and
the old worklog filename, updated to match). `Tda4j.scala`/`PersistenceResult.scala`'s doc comments updated to
describe the incremental mechanism. CLAUDE.md's two chunks-representatives sections rewritten to describe the
final state (delegate design mentioned only as superseded history) and the pairing bug. Full `sbt test`: 264
examples, 0 failures/errors, unchanged from the pre-redesign baseline.

One correction along the way, worth recording since it was written into docs before being checked: an early
draft of this session's own comments claimed chunks' `advanceAll()` was unsafe to call more than once on the
same state. Empirically false -- `unionFindDim01` already carries a `dim01Resolved` guard predating this
session, and every other pass (`processCell`/`compress`/`globalReduce`) is a natural per-cell no-op once a cell
is already resolved; `HomologySpec`'s own existing (passing) test already called `barcodeAt()` then `diagramAt()`
on one state, which does exactly this. Caught by writing a throwaway scratch spec and comparing output directly,
not by re-reading the code more carefully -- the lesson generalizes: check the existing test suite for
precedent before writing an inferred mechanism into a comment or worklog.

Not attempted, out of this session's scope: Scala 3.9.0 LTS upgrade (queued separately by the project lead,
tracked as its own task, not part of the representatives/pairing-bug arc).
