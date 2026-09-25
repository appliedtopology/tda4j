# Worklog: Dowker complexes (streams layer)

Session: 2026-09-25. First-ever exercise of a Dowker complex anywhere in this codebase.

## Design decisions (agreed with project lead before implementation)

- Generalizes De Silva-Carlsson witness complexes rather than sitting fully alongside them: the witness complex's
  `nu=0` case (`WitnessGeometry.witnessValue(sigma, m = _ => 0.0)`) is exactly the Dowker filtration value with
  `R = D` (the landmark-to-witness distance matrix). Not implemented by delegating to `WitnessGeometry` -- that
  class's shape (ambient metric space + landmark subset) doesn't fit an arbitrary relation with no shared ambient
  space -- but the formula is the same, independently re-derived in `DowkerGeometry.filtrationValue`.
- `R: L x W -> [0, Infinity]` is a fully general relation, not derived from any metric -- this is deliberately more
  general than witness (supports e.g. Chowdhury-Mémoli-style persistent homology of asymmetric directed networks,
  `R(x,y)` = edge weight from x to y on the SAME vertex set, no symmetrization needed).
- `f(sigma) = min_w max_{x in sigma} R(x,w)` is automatically monotone under face removal (proved directly from the
  formula: `max` over a subset is `<=` `max` over the superset, for every fixed `w`, so `min_w` preserves the
  inequality) -- unlike `WitnessCofaceSimplexStream`'s per-dimension `m_k` threshold, no recursive "max with
  facets" clamp is needed here. Built on `RipserCofaceSimplexStream`'s generic coface loop (same as
  Cech/general-witness) since Dowker is NOT a flag complex in general (a witness for a whole subset need not
  witness any of its edges).

## Bugs found and fixed during this session

1. **`keptByThresholdAndCriterion`'s `<=` admits `+Infinity <= +Infinity`.** `DowkerGeometry.fromBoolean` encodes
   "never witnessed" as a literal `Double.PositiveInfinity` (not a missing-value sentinel some other stream would
   filter out first) -- every other stream in this codebase's default `maxFiltrationValue = +Infinity` is safe
   because none of them ever actually COMPUTE a genuinely infinite filtration value (Witness's own formula, e.g.,
   is always finite for finite inputs). With Dowker's boolean encoding, the untruncated default let every
   candidate through regardless of whether any witness covered it, collapsing the whole construction to the
   complete simplex on `numLeft` vertices. Confirmed empirically: the pentagon fixture (below) produced K5's 10
   edges instead of the intended 5-cycle before the fix. Fixed by overriding `keptByThresholdAndCriterion` in
   `DowkerCofaceSimplexStream` to additionally require `filtrationValue(spx).isFinite` -- a genuinely infinite
   filtration value is now unconditionally excluded, regardless of what `maxFiltrationValue` the caller passes.

2. **Raw barcode duality only holds after dropping zero-persistence bars.** The functorial Dowker duality theorem
   (Chowdhury & Mémoli 2018) gives a natural isomorphism of the X-side and Y-side persistence MODULES, so their
   interval decompositions must be literally identical multisets -- but a simplicial filtration records exactly
   one `H_0` birth event per vertex, unconditionally, so when `numLeft != numWitnesses` the two sides' RAW
   barcodes cannot possibly match bar-for-bar even in principle (different vertex counts, different birth-event
   counts). Confirmed empirically with a hand-worked 3x4 rectangular relation via a scratch `runMain` (not
   committed): X-side barcode `[(0,0,1),(0,0,1),(0,0,Inf),(1,1,1)]`, Y-side
   `[(0,0,1),(0,0,1),(0,0,Inf),(0,1,1),(1,1,1),(1,1,1),(1,2,2),(2,2,2)]` -- every bar present on one side but not
   the other has `birth == death` (a "born already merged" artifact of several cells sharing one real filtration
   value, the same tie-break-artifact status these bars already have elsewhere in this codebase, e.g.
   `WitnessStreamSpec`). After dropping those, both sides reduce to the identical
   `[(0,0,1),(0,0,1),(0,0,Inf)]`. `DowkerStreamSpec.dropZeroPersistence` documents and applies this; the class
   doc on `DowkerGeometry` records the same fact for future readers.

## What shipped

- `streams/DowkerStream.scala`: `DowkerGeometry` (relation matrix, `filtrationValue`, `.dual`, `.fromBoolean` for
  the classical unfiltered/boolean case), `DowkerFiltration` (memoized `PartialFunction`, no `dim<=0 => 0.0`
  special case since Dowker vertices have real, meaningful, generally-nonzero values), `DowkerPlaceholderMetricSpace`
  (private, satisfies `RipserCofaceSimplexStream`'s constructor only -- `distance` never read, same status
  `WitnessMetricSpace` has for the general witness variant), `DowkerCofaceSimplexStream` (the `L`-side complex,
  `.dual` gives the `W`-side complex via `geometry.dual`).
- `streams/DowkerStreamSpec.scala`: hand-derived pentagon fixture (5 arcs covering a circle -- the classical nerve
  example, chosen specifically for nontrivial `H_1`, unlike a tree/chain example which would validate duality only
  vacuously), independent brute-force cross-check of `filtrationValue` + monotonicity property test, the duality
  cross-check itself (property test across random relations, X-side vs. Y-side barcodes after
  `dropZeroPersistence`), the bars-account-for-cells structural invariant, and `CellularCohomologyContext` vs. the
  naive engine cross-check (the relevant cross-engine check here, since this is not a flag complex --
  `PackedRipserCohomologyContext`/`chunks` are not expected to apply, same status Cech/general-witness have).
- Full suite: 586 examples, 0 failures, 11 skipped (pre-existing benchmark `skipAll`s), after `scalafmtAll`
  (no reformatting needed).

## Explicitly NOT done this session (follow-up)

- **`matlab.TDA4j`/`cli` wiring** (`complex=dowker` or similar, `engine=naive`/`cohomology` refusing
  `ripser`/`chunks` the same way general-witness/Cech do -- needs its own `resolveDowkerEngine`-style dispatch
  and a decision on the input shape: a relation matrix is not obviously "points" or "a distance matrix", so the
  existing `computeFromPoints`/`computeFromDistanceMatrix` entry points may not fit cleanly and a new
  `computeFromRelation`-style entry point may be needed).
- `src/docs/developers-guide/persistence-engines.md`'s streams x engines table (a `dowker` row, mirroring the
  `witness`/general row's reasoning).
- `src/docs/developers-guide/architecture.md`/`class-diagrams.md` and `src/docs/user-guide/README.md`.
- CLAUDE.md's own package-layout/architecture summary (a short "Dowker complexes" paragraph, once the above
  surfaces exist to summarize).

Per CLAUDE.md's own "finalizing a user-visible capability" checklist, this session lands the streams-layer chunk
only (with genuine, cross-validated correctness) -- the four other surfaces are real follow-up work, not
forgotten.
