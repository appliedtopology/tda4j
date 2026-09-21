# A pre-existing `CellularPersistenceInChunksContext` pairing bug, found while validating representatives (2026-09-20/21)

Found while root-causing the representative-tracking work (`WORKLOG-chunks-representatives.md`): validating the
new `vcolOf`-based `barcodeAt` against the naive engine on a tie-heavy clique surfaced a *pairing* disagreement,
not a representative one -- `diagramAt` itself reported the wrong bars, on unmodified `HEAD`, unrelated to any
representative-tracking code. The project lead was asked how to proceed (`AskUserQuestion`) and chose
"root-cause and fix the pairing bug first," then went to sleep with the instruction to keep going autonomously,
checking `advisor()` rather than stalling for input. This worklog is that root-cause and fix, written up as its
own finding per the standing worklog convention -- it's the more consequential discovery of the two, and
independent of representatives entirely (it would have mattered even if representatives were never in scope).

## The symptom

An 8-point, all-tied-at-zero clique at `maxDim=3`: `diagramAt` reported 56 dimension-3 bars against the naive
engine's 35. Minimized to **n=6, maxDim=3** (all other dimensions agree; only dimension 3 diverges). Confirmed
present on unmodified `HEAD` via `git stash` -- not introduced by anything in this session.

## First mechanism: a cross-dimension race in `advanceAll`'s local/global split

`advanceAll` runs two dimension-descending passes: Algorithm 2 (local, `processCell`, windowed via `stop`) for
every dimension from `internalMaxDim` down to 2, THEN `markActiveEntries`, THEN Algorithm 5 (global, `compress`/
`globalReduce`) for every dimension, again descending.

Traced with cell-specific `println` instrumentation (temporary, stripped once the fix landed) on the n=6
fixture: tetrahedron `{1,2,4,5}`'s own local `processCell` found a nonzero reduced boundary (pivot among its
four triangle faces) but deferred `recordPair` because `stop` fired -- the pivot fell outside that round's local
window. Later, during the delta=4 GLOBAL pass, `globalReduce({0,1,2,3,4})` reduced against `boundaries` and,
mid-cascade, hit `{1,2,4,5}` as a substituted term. `eliminationFallback`'s catch-all (`cleared=false,
paired=false -> None`) treated this as "no content, safe to claim as final pivot" -- correct for a genuinely
essential cell (one whose `R` was actively removed after reducing to zero), wrong for a cell merely *pending*
its own resolution. `{1,2,4,5}` got wrongly `cleared` by `{0,1,2,3,4}`, stealing it from its rightful role as
killer of its own triangle face `{1,2,4}` -- which was left spuriously essential as a result. Same shape as the
21-spurious-essential-triangles finding on the original 8-point repro.

Root cause: `processCell`'s local phase can leave a cell "in limbo" (nonzero `R`, neither `cleared` nor
`paired`) when its pivot falls outside every round's window. The global phase, which ALSO runs dimension-
descending, can reach that in-limbo cell as a substituted term in a HIGHER dimension's reduction before the
in-limbo cell's own dimension ever gets its turn in the global phase -- and `eliminationFallback`'s catch-all
can't distinguish "genuinely essential" from "pending."

**Fix, part 1**: a reconciliation step in `advanceAll`, between the local phase and `markActiveEntries`, that
resolves every in-limbo cell (`R.contains(sigma) && !cleared.contains(sigma) && !paired.contains(sigma)`) by
re-reducing its raw boundary with no window, in `allCells` order (dimension-ascending, so a lower dimension's
own in-limbo cells are resolved -- and contribute their own new `boundaries` entries -- before a higher
dimension's in-limbo cells are reduced against them). This guarantees every cell's `cleared`/`paired`
classification is final BEFORE `markActiveEntries`/the global phase ever run, making `eliminationFallback`'s
catch-all honest again: by the time it's consulted, "neither cleared nor paired" really does mean "genuinely
essential."

## Second mechanism: `eliminationFallback`'s paired-cell substitute was never a real V-column

Part 1 alone fixed `{1,2,4,5}` but broke a DIFFERENT cell (`{0,2,3,5}`) on the SAME fixture, the same way: the
reconciliation step's own reduction (needed no local window, so it can cascade through an already-cleared
pivot's stored boundary) pulled in an already-`paired` cell as a term, and had no way to eliminate it --
`processCell`'s reduction only consults `boundaries` (cleared pivots), never `eliminationFallback`. A quick
audit of WHY the ordinary local phase never hits this (it's strictly dimension-descending, so a dimension-`d`
cell's local reduction can never encounter an already-`paired`, necessarily-lower-dimension term) confirmed this
is new territory the reconciliation step itself opened up, not a latent gap in `processCell`'s existing design.

Switching the reconciliation step to use `eliminationFallback` (matching `compress`/`globalReduce`) surfaced the
REAL, deeper bug: `eliminationFallback`'s existing "paired" branch used `Some(Chain(l))` (trivial self-cancel)
for an inactive paired row, and `None` (become-the-final-pivot) for an active one. Neither is a real
substitute. Confirmed by direct comparison against the naive engine's own mechanism: `CellularHomologyContext.
advanceOne` uses `negativeVCols.get` as its `Chain.reduceBy` fallback -- a REAL, possibly multi-term V-column
(leading term = the cell itself), not a placeholder, consulted UNCONDITIONALLY whenever a paired cell is hit as
a term (no active/inactive distinction at all in the naive engine).

Extracted naive's actual, ground-truth reduced boundary for `{0,1,2,3,4}` directly (recomputing
`Chain.reduceBy(dsigma, naiveState.boundaries, Chain.empty, fallback = naiveState.negativeVCols.get)` against
naive's own final, complete state): `Chain()` -- genuinely zero, i.e. `{0,1,2,3,4}` is the essential H4 class of
the 4-skeleton of a 5-simplex (S^4, by Euler characteristic/exactness: 6 top cells, 5 pair off as killers of the
5 dimension-3 positive classes, the 6th is essential). The reduction LOG along the way contained the exact same
`±2.0⊠{0,2,3,5}`/`{1,2,3,5}`/`{1,2,4,5}`/`{2,3,4,5}`/`{0,3,4,5}` terms chunks' WRONG result stopped on --
confirming chunks was truncating a cascade that, if continued via a real V-column substitute, fully cancels.

**Fix, part 2**: `vcolOf` (built earlier in this same class for `barcodeAt`'s own representative tracking --
see `WORKLOG-chunks-representatives.md`) computes EXACTLY this same quantity for a paired cell: its own
"stop at my own pivot" branch, and its fold-over-reduction-log logic (skip correction for paired-eliminated
terms, apply killer's-vcol correction for cleared-eliminated terms), structurally mirror `negativeVCols`'
construction in the naive engine term for term. `eliminationFallback`'s paired branch now reads
`Some(vcolOf(l))`, unconditionally -- dropping the active/inactive gate entirely for this branch, matching
`negativeVCols.get`'s own unconditional use in the naive engine. The reconciliation step (part 1) was updated
to route through `eliminationFallback` too, instead of a bare `boundaries`-only reduction.

**Why calling `vcolOf` mid-`advanceAll` (not just post-hoc from `barcodeAt`) is safe**: it depends on every
paired cell's `cleared`/`paired` classification being permanently fixed by the time it's consulted, which is
exactly what the reconciliation step (part 1) now guarantees before the global phase (and therefore
`eliminationFallback`, and therefore `vcolOf`) ever runs -- `compress`/`globalReduce`'s own guards
(`!cleared.contains && !paired.contains`) mean neither ever revisits an already-classified cell, so nothing
downstream can invalidate a `vcolOf` result computed mid-algorithm and cached in `vcolCache`.

## Verification

- The original minimal n=6/maxDim=3 repro: `cleared ∩ paired` at dimension 3 is now empty (was `{0,2,3,5}`);
  `cleared`/`paired` dimension-3 counts (5/10) exactly match naive's `positive-then-killed`/`negative` sets,
  cell for cell, not just by count.
- `PersistenceInChunksSpec`'s full suite (7 examples, including the pinned `tetrahedronBoundaryDegenerateCells`
  fixture -- the regression a PRIOR session's `compress`/`globalReduce` fix was built against) passes unchanged.
- A new sweep (`RepCycleAuditSpec`, n in [5,8] x maxDim in [2,3], all-tied-at-zero cliques -- the worst case for
  tie-heavy cascading resolution) confirms `diagramAt`'s bar-count-by-dimension exactly matches the naive
  engine's own (naive's output restricted to `dim <= maxDim`, since its own stream has no maxDim concept -- a
  dimension-`d` cell can only ever affect a dimension `d-1` pivot, so nothing above `maxDim+1` can change a
  `dim <= maxDim` bar, matching chunks' own `internalMaxDim = maxDim + 1` convention). All three properties
  (bar counts, `barcodeAt` representatives are genuine cycles at dim >= 1, `barcodeAt`/`diagramAt` agree with
  each other) pass across the full sweep.
- Full `sbt test` run pending as of this writing -- see this worklog's own follow-up note once it completes, or
  CLAUDE.md's own updated entry for the final confirmed state.

## What's NOT touched

`eliminationFallback`'s "cleared" branch (active ? substitute via killer's `R` : self-cancel) is unchanged --
this bug's evidence never implicated it, and it's `globalReduce`'s own primary substitution path there
(`basis=boundaries` is tried first, so this branch is only ever reached via `compress`, where `basis=empty`
forces everything through the fallback). Revisiting it, if ever needed, is a separate investigation with its
own evidence, not an extension of this one.
