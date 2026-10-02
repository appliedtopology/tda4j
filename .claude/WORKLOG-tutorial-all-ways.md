# WORKLOG: tutorial `all-ways-to-call.md` (started 2026-10-02)

Task: draft the MATLAB section, fix the two Scala snippets. Then follow-ups (stream renames, Sage sSets, persistent
group cohomology). Keep going, don't stop.

- Synced: reset branch `ccr-66e09665-8g203t` to `origin/scala` (PR #32 was merged; fresh start per branch rules).
- Docs build (3.8.4) at start: REPL snippet compiles; "by extension" snippet fails: duplicate `val homologyComputation`/
  `barcode` inside one object (tasks 1 and 2 reuse names).
- Substantive bug in task 1: the stream is a hollow TETRAHEDRON (4 vertices), not an octahedron.
- Findings while making the tutorial run (not just compile):
  1. Octahedron: old snippet was a hollow tetrahedron (user confirmed). Now `ExplicitStreamBuilder.fromFacets(8 triangles)`.
  2. **Library footgun fixed**: `ExplicitStreamBuilder`'s `using Option[Filterable]` defaulted to `None` even for `Double`
     (the Option was never summoned), so the sentinels were the stream's own min/max VALUES: `barcodeAt` reported a class
     dying at the last filtration value as essential, and births at the first value as -infinity (octahedron showed 7
     essential H1 bars). Fix: `Filterable.optionalFilterable` given. Full suite unchanged (668 pass).
  3. `IncrementalVietorisRipsSimplexStream(maxDimension = 1)` with the naive engine has no triangles, so every loop stays
     open (133 "significant" H1 bars). The naive engine needs `maxDimension = 2` for H1 (ripser/facade engines add the +1
     themselves). Tutorial now uses 2.
  4. By-extension snippet failed on duplicate `val` names; renamed.
- Added `ExplicitStreamBuilder.fromFacets` / `fromFilteredFacets` (user request): closure under faces; an unlisted face
  takes the min value of the cells containing it (monotone).
- MATLAB section drafted: octahedron via `computeFromDistanceMatrix` (distance 1 for edges, 2 for antipodal; the
  octahedron is a flag complex), points via `csvread`, `h1Bars`, `circularCoordinates`. NOT run in MATLAB (none here);
  every call is exercised through the same Java facade in `tutorial/AllWaysToCallSpec`. Unverified: cell-array ->
  `String[]` conversion and `javaaddpath` paths.

## Follow-up 1 (stream names) — done as proposal + additive dispatcher
- `streams.VietorisRips` (see `DESIGN-stream-naming.md`); `ExplicitStreamBuilder.fromFilteredFacets` got `UnlistedValues`
  strategies (`EarliestCoface` default per project lead: overlapping facets with different values give the overlap the
  min; `Constant(v)`), listed values never overridden, non-monotone result rejected.
- Mistake worth recording: I overwrote the existing `streams/VietorisRipsSpec.scala` with a new spec of the same name
  (it held the shared `matrixGen`); caught by the compile errors in unrelated specs, restored from git. `matrixGen` now
  lives in `streams/Generators.scala` (user request).
- Full suite 674 pass; scalafmt (main/test/sbt) clean; 3.8.4 doc build clean.
