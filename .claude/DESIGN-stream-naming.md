# DESIGN: naming the simplex streams (proposal, 2026-10-02)

Status: **proposal + one additive step done** (`streams.VietorisRips` dispatcher). No existing class was renamed:
renames touch ~66 files and public API, 0.5.0 carries no compat aliases (CLAUDE.md), and the project lead should pick
the names. Everything below is a recommendation with reasons.

## What is wrong now

1. Names encode implementation trivia, not what the user gets: `EnumeratingCofaceSimplexStream`,
   `RipserCofaceSimplexStream`, `InorderCofaceSimplexStream`, `RecursiveStackVietorisRipsSimplexStream`,
   `IncrementalVietorisRipsSimplexStream` are ALL "the Vietoris-Rips filtration". A newcomer cannot tell which to pick;
   the tutorial itself picked the one CLAUDE.md calls "a cross-validation baseline, not a fast engine".
2. "Coface" and "Simplex" and "Stream" are noise on most names (`DtmRipsSimplexStream`, `CechCofaceSimplexStream`).
3. `maxDimension` means three different things: top HOMOLOGICAL degree (facade, engines, CLAUDE.md says "everywhere"),
   top SIMPLEX dimension (`IncrementalVietorisRipsSimplexStream`, `RecursiveStackSimplexEnumerator.targetDimension`), and
   absent (coface streams are unbounded; wrap in `LimitedCofaceSimplexStream(stream, k+1)`). The tutorial hit this: the
   user wrote `maxDimension = 1`, wanting H1, and got a stream with no triangles -> 133 spurious open H1 bars. CLAUDE.md's
   "everywhere" was wrong; corrected there.
4. `maxFiltrationValue` default differs by class (enclosing radius vs `+Infinity`).

## Done (additive)

`streams.VietorisRips(metricSpace, maxDimension = 2, maxFiltrationValue = None, implementation = Enumerating)`:
homological-degree `maxDimension` (builds k+1 internally), enclosing-radius default, `Implementation` enum for
Enumerating / RipserCoface / Inorder / Incremental. Default = what `matlab.TDA4j` builds for naive/chunks. Not offered:
`RecursiveStack` (cannot truncate; times out past toy sizes, `WORKLOG-mst-and-perf.md`). Exported from `TDAlab.streams`.
Tested in `VietorisRipsDispatcherSpec` (agreement across implementations, ULP-rounded).

## Proposed names (one object per complex, constructions as implementations)

| Today | Proposed | Note |
|---|---|---|
| `Enumerating/RipserCoface/Inorder/Incremental...Stream`, `RecursiveStack...` | `VietorisRips` + `VietorisRips.Implementation` (done); the classes become `private[streams]` or `VietorisRips.Enumerating` etc. | rename classes last, after the dispatcher has been the documented entry for a release |
| `LimitedCofaceSimplexStream(s, k+1)` | `s.truncatedTo(homologicalDegree = k)` (extension) or `Truncated(s, k)` | removes the manual "+1" |
| `CofaceSimplexStream` / `StratifiedSimplexStream` | `LevelwiseSimplexStream` | what it is: dimension-by-dimension iteration; "coface" is how it is computed |
| `CechCofaceSimplexStream` | `Cech(metricSpace, maxDimension, ...)` | same dispatcher shape |
| `LazyWitnessSimplexStream` / `WitnessCofaceSimplexStream` | `Witness.lazy(...)` / `Witness.general(...)` | matches the `witnessVariant` option |
| `DowkerCofaceSimplexStream`, `DowkerGeometry` | `Dowker(relation)` | |
| `DtmRipsSimplexStream` | `DtmRips(...)` | |
| `SheehyRipsSimplexStream` | `SparseRips(...)` (doc: Cavanna-Jahanseir-Sheehy) | named by what it is for |
| `EdgeCollapsedMetricSpace` | `VietorisRips(..., edgeCollapse = true)` or keep as a metric space | it is a drop-in metric space |
| `ExplicitStream`/`ExplicitStreamBuilder` | keep; `ExplicitStreamBuilder.fromFacets` added | |
| `CubicalGridStream`, `ExplicitCubicalStream`, `Limited...` | `Cubical.grid(image)`, `Cubical.explicit(...)` | |
| `AlphaComplexDQPStream`, `LimitedAlphaShapesStream` | `Alpha(points, backend = Helix \| DQP)` | AlphaShapes already dispatches |

Rule for the rest: the name says WHICH COMPLEX; options say how it is built; `maxDimension` is always a homological
degree and the object does the +1.

## Suggested order

1. (done) `VietorisRips`; use it in docs/tutorial.
2. Add `Cech`, `Witness`, `Dowker`, `DtmRips`, `SparseRips` dispatcher objects the same way (additive, no breakage).
3. Make the old classes `private[streams]` in one commit with the doc/test search-replace (66 files) once the lead agrees
   on names.
