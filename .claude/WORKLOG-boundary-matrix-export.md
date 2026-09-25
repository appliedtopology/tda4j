# Worklog: boundary-matrix export on PersistenceResult

2026-09-24, cloud session, continuing `.claude/WORKLOG-mainstream-feature-gap-analysis.md`. Executes that
worklog's item 2 (recommended execution order item 2 -- "unblocks MATLAB delivery for anything downstream").
Session transcript: `https://claude.ai/code/session_014m9j2FdkTDmh1PL2MA9rX6`.

## What was built

`matlab.PersistenceResult` gains `numCells()`/`boundaryRows()`/`boundaryCols()`/`boundaryValues()`/
`columnDimension(j)`/`columnVertices(j)`/`columnFiltrationValue(j)`: the boundary matrix of the *full complex*
a result was computed from (not just the cells inside bar representatives), lazy and memoized
(`BoundaryMatrixData`, `PersistenceResult`'s new `boundaryMatrixProvider: () => BoundaryMatrixData`
constructor parameter). Matches the design `WORKLOG-matlab-api.md` already committed to: "a second closure
alongside the existing cycle-provider closure, not a redesign."

**Key design decision, not in the originating worklog's own sketch**: the boundary matrix is built from the
same stream/metric-space construction `engine=naive` already consumes for that `complex`, **regardless of
which engine actually computed the result's own bars** -- `TDA4j.buildBoundaryMatrix` is called once per
`complex` branch (not once per `complex` x `engine` combination) in `computeGeneric`/
`computeWitnessFromLandmarks`/`computeCubicalGeneric`, and the resulting `() => BoundaryMatrixData` thunk is
threaded through every `fromBars` call in that branch. Reasoning: the boundary matrix is a property of the
*complex*, not of which reduction algorithm ran over it -- exporting an engine-specific artifact (e.g. Ripser's
own packed `DiameterIndex` representation) would need a different matrix per engine for no benefit, and would
make "boundary matrix" mean something different depending on an option (`engine`) that has nothing to do with
what a caller doing their own linear algebra actually wants. Confirmed empirically, not just designed that
way: a property test computes the SAME input across `naive`/`ripser`/`chunks`/`cohomology` and checks all four
report byte-identical `(rows, cols, values, columnDims, columnVertices, columnFiltrationValues)`.

Touches 26 `fromBars` call sites across 9 complex branches (VR, Alpha, Cech, DtmRips, SheehyRips, DtmAlpha,
Witness-lazy, Witness-general, Cubical) -- mechanical but deliberately NOT centralized into one function that
re-derives "how do you build the stream for complex X" a second time: CLAUDE.md's own "Streams: the ordering
contract" section documents five historical bugs from exactly that kind of duplicated stream-construction
logic drifting out of sync, so each complex branch's boundary-matrix thunk reuses the SAME already-in-scope
`stream`/`metricSpace` local the barcode computation itself uses (in several branches, this let an
already-redundant per-engine local `stream` val be deleted in favor of one shared one, a small simplification
that fell out of the boundary-matrix work rather than being a separate cleanup pass).

Also exports `columnFiltrationValue` (not in the originating worklog's item-1 sketch, which only mentioned
rows/cols/values) -- added because the worklog's own stated purpose ("unblocks circular coordinates, optimal
cycles") needs it: circular coordinates' own plan (that worklog's item 2) is "pick `r`, truncate to `K_r`,"
which needs each cell's own filtration value to know what's inside `K_r`. Cheap to add alongside the rest
(reuses each stream's own `filtrationValue: PartialFunction[CellT, FiltrationT]`, already used pervasively by
the reduction engines internally) and avoids a second round-trip through this same 26-call-site threading
exercise once item 3 actually needs it.

## Verification

The real oracle (not just eyeballing hand-picked entries): a standard Z/2 persistence-algorithm reduction,
implemented independently in the test file (`BoundaryMatrixSpec.reduceZ2`), run directly on the exported
matrix, checked against `PersistenceResult.toArray()`'s own bars -- on a hand-sized triangle, on random small
point clouds across all four engines (also checking all four engines agree with each other), on a cubical
complex, and on an alpha complex. Plus structural invariants (row < col always; a column's own dimension is
exactly one more than any row it references; `columnVertices(j).length == columnDimension(j) + 1`) and a
direct laziness/memoization check (a custom `boundaryMatrixProvider` that counts its own calls, constructed
directly since the test file shares `PersistenceResult`'s `private[matlab]` scope).

**Two real, subtle test-design points surfaced while building the reduction oracle** (both resolved in the
test, not bugs in the export itself):
1. The exported matrix intentionally includes the same "one cell-dimension higher" scaffolding
   `computeGeneric` already builds internally (H_k needs (k+1)-chains) and that `fromBars` drops from the
   *reported bars* (CLAUDE.md's own "drop `dim == k + 1` bars" rule) -- so reducing the *whole* exported
   matrix surfaces that scaffolding dimension as its own (not generally meaningful) bars, and an oracle
   comparing against the reported bars has to apply the identical filter before comparing, not assume the two
   are the same set by construction.
2. `FiniteField.Fp.toInt` (what `toDouble` for the `Z` field composes with) is the raw, possibly-negative
   Scala `%` residue, not canonicalized to `[0, p)` the way the class's own separate `toUInt` is (confirmed by
   reading `FiniteField.scala` directly, not assumed) -- so a boundary coefficient over the default field can
   legitimately report `-1.0` where `1.0` might be expected (both are the same element of F2); a test asserting
   exact equality to `1.0` is wrong, `abs(value) == 1.0` is the correct invariant. `cycleCoefficients` elsewhere
   in this same facade already has the identical property.

**Unrelated pre-existing bug found and left for a future session** (confirmed reproducing with zero
boundary-matrix code involved, i.e. plain `TDA4j.computeFromPoints(points, Array("complex","alpha"))`): a
highly symmetric 5-point configuration (a unit square plus its own center) makes `engine=naive` throw
`IllegalStateException("reduction pivot ... was not a recorded open class")` from
`CellularHomologyContext.advanceOne` -- the exact failure signature CLAUDE.md's "Streams: the ordering
contract" section already documents five prior instances of (a real ordering/tie-handling bug, not a
boundary-matrix-export issue). `BoundaryMatrixSpec`'s own alpha-complex test uses a deliberately
non-symmetric point set instead of chasing this down, since it's out of scope for this arc; worth a dedicated
session (tie-heavy alpha-complex fixtures, matching this codebase's own established debugging pattern for this
bug class).

`sbt test`: 507 examples (497 passed, 10 skipped benchmarks), 0 failures -- clean before and after this item's
work, and the new `BoundaryMatrixSpec` (including its ScalaCheck property) run clean 5x in a row. `sbt
scalafmtCheck`/`sbt laikaSite`: clean for every file this item touched (pre-existing drift files noted in the
prior worklog are the only ones still flagged, confirmed unrelated the same way as before). Fixed two
scaladoc-link warnings this item's own new doc comments introduced (`[[Vectorization]]`/`[[BarcodeDistance]]`
cross-package `[[...]]` links don't resolve in this docs toolchain the way same-package ones do -- several
pre-existing files already have the identical, apparently-accepted warning pattern; fixed only this item's own
new instances, left the pre-existing ones alone).

## Status

Item 2 of the recommended execution order is complete. Next: item 3, circular coordinates -- now unblocked
(the boundary matrix, including per-cell filtration values, is exactly the plumbing that item needs for its
"truncate to `K_r`" step).
