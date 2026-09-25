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

## Explicitly NOT done in the first (streams-only) session -- since completed

- **`matlab.TDA4j`/`cli` wiring** -- DONE in a follow-up session (see below).
- `src/docs/developers-guide/persistence-engines.md`'s streams x engines table -- DONE.
- `src/docs/developers-guide/architecture.md`/`class-diagrams.md` and `src/docs/user-guide/README.md` -- DONE.
- CLAUDE.md's own "Dowker complexes" paragraph -- DONE (updated to drop the "not yet wired in" caveat).

## Follow-up session: matlab.TDA4j/cli/docs wiring (2026-09-25, same day)

Resolved the input-shape question flagged above: a relation is neither a point cloud nor a square/symmetric
distance matrix, so it gets its own dedicated one-shot entry point, `computeFromRelation` (MATLAB/Java) /
`--input-format csv-relation` (CLI) -- NOT a new `complex=` value on `computeFromPoints`/`computeFromDistanceMatrix`
(mirroring the shape the two-step witness recipe's own separate entry points already established: a strict,
smaller, own option allowlist via `parseOptionsWithKeys`, not the shared `recognizedKeys`/`dispatch`/
`computeGeneric` machinery those two build around).

- `matlab.TDA4j.computeFromRelation(relation, options)`: recognizes `"engine"` (`naive` default, refuses
  `ripser`/`chunks` -- not a flag complex, same reasoning as `witness`/`witnessVariant=general`),
  `"maxDimension"` (default 2, needs the same "+1 build, drop via fromBars" dance as Cech/general-witness since
  the top dimension isn't naturally bounded), `"maxFiltrationValue"` (default `+Infinity`, no
  `minimumEnclosingRadius`-style truncation available for an arbitrary relation), `"dual"` (new: computes the
  `W`-side complex directly via `DowkerGeometry.dual`, so a caller never has to transpose the relation by hand
  to get the OTHER side's representatives), `"field"`/`"prime"`/`"epsilon"`. Implementation mirrors
  `computeWitnessFromLandmarks`'s `WitnessVariantKind.General` branch almost exactly (same
  `LimitedCofaceSimplexStream`/`buildBoundaryMatrix`/`fromBars`/`PersistenceEngine.naive`/`.cohomology` shape).
- `cli`: new `--input-format csv-relation` (reuses `CSV.readPointCloud` outright -- a Dowker relation is
  exactly that reader's own "arbitrary rows x columns, no squareness" shape, just routed to a different TDA4j
  entry point, not a new reader) and a new `ResolvedInput.Relation` case; `--dual` mirrors the new
  `"dual"` option. `--complex`/`--select-landmarks`/`--landmarks-file` are all rejected for
  `--input-format csv-relation`, mirroring the existing `CubicalGrid` guards exactly.
- Tests: 7 new `TDA4jSpec` examples (conversion-layer only, per that file's own stated purpose -- cross-checks
  the facade's dispatch against driving `DowkerCofaceSimplexStream` directly, including a `dual=true` check
  exercising the functorial duality theorem through the facade) and 6 new `CLISpec` examples (end-to-end via a
  real file on disk, plus the guard rejections). Full suite after this session: 599 examples, 0 failures, 11
  skipped; `laikaSite` builds clean (pre-existing, unrelated scaladoc-link warnings only).
- Docs: `persistence-engines.md` gained a `Dowker relation (computeFromRelation, no complex key)` table row
  (grouped under "Not a flag complex" in the reasons list, alongside `cech`/`witness`-general/`sheehy-rips`) and
  `class-diagrams.md` a `DowkerCofaceSimplexStream` node; `architecture.md` gained a full "Dowker complexes"
  section (mirroring the "Witness complexes" section's own depth) between Witness and Sheehy; `user-guide/
  README.md` gained a "Dowker complexes" walkthrough section (including `DowkerGeometry.fromBoolean` for the
  classical case) between Witness and Cubical, plus entries in the entry-points paragraph, the CLI paragraph,
  and the "Which persistence engine?" table.

CLAUDE.md's own instructions were also updated this session (unrelated to Dowker itself, but landed in the same
pass): a cloud session working on its own disposable `claude/...` branch may now commit and push at will,
without asking first -- the earlier blanket "the project lead commits their own work" rule was scoped down to
local/interactive sessions working directly on a shared branch.
