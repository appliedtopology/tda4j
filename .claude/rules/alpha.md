---
paths:
  - "src/**/alpha/**"
  - "src/**/*Alpha*.scala"
---

# Alpha complexes: Bowyer-Watson, Helix and DQP, and the fast alpha engine

Loads when you work in `alpha/` or on an alpha file. Project-wide rules are in `.claude/CLAUDE.md`.

## Alpha complex: Bowyer-Watson, Helix, DQP

**`BowyerWatsonDelaunay`** (`alpha/BowyerWatson.scala`, `WORKLOG-bowyer-watson.md`) is the default for points at most
4 coordinates wide. Exact predicates (`DelaunayPredicates`: permutation-expansion determinants, float filter then
`BigDecimal`; in-sphere ties broken by perturbing the lifted coordinate in descending point index), so the
triangulation is valid on grids and the SAME for every insertion order (`BowyerWatsonSpec` pins both; don't weaken it to
Betti numbers). Exactly repeated points: smallest index kept, others joined by a value-0 edge. Refuses an affine span
above 4-D (n! expansion); never raise the cap without a different determinant. Equal to Helix within 1e-9 in general
position. Both triangulators extend `DelaunayAlphaShapes` (faces/values/sort on flat arrays, Householder circumspheres:
never the Gram system, it lost 5e-5 on a 4-D sliver). A Gabriel value within relative `1e-12` of its smallest coface value is set
to it (a vertex on the sphere = the same sphere; otherwise a ~1e-16 bar that depends on the diagonal chosen).

`WORKLOG-alpha-complex.md`, `HANDOFF-alpha-complex.md`. `AlphaShapes(points, backend)`: `Default` → BowyerWatson
(width <= 4) or Helix; DQP only with a radius via `prefersDQP`, or explicitly. Alpha and VR/Ripser are separate sections with minimal interaction
(project lead's standing call). Never resurrect the ripped-out Miniball-Delaunay backend.

`AlphaComplexDQP` implements Carlsson & Carlsson (Sci. Rep. 14:19824, 2024), DAQP-style dual active-set QP;
`CholeskyWorkspace` hand-rolled incremental Cholesky. Filtration values are squared radii internally; `radiusOf`
takes sqrt. `AlphaShapeDQP` always untruncated; `AlphaComplexDQP.euclidean(points, maxRadius, ...)` for truncation.
It keeps a `FaceTable` per dimension with value/witness rows (no `Simplex`-keyed maps); `keepWitnesses = false`, which
the streams use, drops the witness arrays. From d = 10 Helix beats DQP on time and memory (`WORKLOG-dqp-table-backed-complex.md`).

**`DualQP.solve` numerics**: `rankTolerance=1e-6` (a lower one admits a poisoned factor); ratio-test ties broken
by global constraint index (Bland's rule). A pivot under it rules a candidate out, which in high dimension dropped
valid simplices of large circumradius and their cofaces: such a candidate is retried at `rescueRankTolerance=1e-11`
and kept only if its multipliers are certified against the data (`WORKLOG-dqp-ill-conditioned-cells.md`). Values past
about 100 times the point spacing stay approximate: truncate DQP. Vertex filtration value is `-space.weight(x)` only
when `x` is inside its own restricted power cell `V_x`, else the min over `x`'s own incident already-solved
edges (dim 1 before 0), and a vertex with no incident edges is dropped entirely
(`AlphaComplexDQPVertexAttachmentSpec`, `WORKLOG-dtm-filtrations.md`). Regressions in
`AlphaComplexDQPRegressionSpec`/`AlphaValidationSpec` (`minTestsOk=2000`).

**`Hyperplane.from` returns a canonically signed normal** (the first coordinate of size at least `0.5/sqrt(d)` is
positive); every use that needs a side orients it itself (`reverse`). The sign only breaks ties inside exactly cospherical
clusters (`WORKLOG-scalajs-numerics-linear-algebra.md`).

**HelixDelaunay's bootstrap** retries every affinely-independent hyperplane candidate (smallest span first), rejects
degenerate ones, and projects a globally coplanar cloud onto its affine span, all through `rankAtEpsilon`
(`.claude/WORKLOG-helix-bootstrap-fix.md`).

**Helix alpha values are computed top-down** (`built`, read by `valueOf`): top cells take the smallest containing circumradius;
below that a simplex takes its own smallest circumradius (centre in its affine hull, `smallestCircumsphere`, never
`Hypersphere.apply`) if Gabriel w.r.t. its cofaces' opposite vertices, else the min over its immediate cofaces. (`WORKLOG-helix-alpha-values.md`). **In general position
Helix must equal DQP bar for bar** (`HelixDqpAgreementSpec`, a CI gate in 2-D to 4-D, and cell for cell in 7-D within
a radius); only degenerate inputs stay diagnostic.

**`DelaunayAlphaShapes` keeps faces and values in flat tables, read per dimension** (`WORKLOG-helix-alpha-speed.md`): `built`
is a `FaceTable` per dimension plus value arrays; the `Simplex` objects, the sorted order of a dimension
(`simplicesOfDimension`, `sortedDimension`) are made when it is first asked for (the verb reads up to `maxDimension + 1`,
never the millions of faces above), and `filtrationValue` probes the table, not a `HashMap[Simplex, Double]`. Nothing in
here may be per-simplex hash sets or a scan of the cells per top simplex (`topValue` was quadratic in the number of cells
and was 75% of the time at 40 points in R^7: `HelixDelaunay.smallestCircumradii`); no `tabulate`/`map`/`sum` in
`circumsphere` (boxing, once per face). Any change here is checked by a before/after dump of every simplex, value (hex
doubles) and stream order (`AlphaProfileDriver dump=`), not by Betti numbers. DQP and Cech pay for the
3-skeleton only (at most C(n, <= 4)) when capped.

**Helix walk** (`WORKLOG-helix-construction-speed.md`): hull facet by gift wrapping (deterministic, no RNG); across
each frontier facet the cofacet is the light point with the smallest centre parameter `t` (sphere centre `c0 + t n`),
ties to the smallest index, its sphere taken from `t` (never re-solved from the vertices: ill-conditioned on slivers),
then checked empty; the old candidate scan is only the fallback (`sphereScanFallbacks`, 0 on random clouds,
`HelixWalkSpec`). Cospherical = within `1e-10 r`, NOT `epsilon` (at the default `1e-5` near-ties got tiled as clusters
on a few thousand random points and tore the triangulation). EVERY result gets `looksValid` (each point a vertex, no
facet in 3 tops, every boundary facet on the hull -- Euler characteristic is NOT enough: a partial contractible walk
passes it); a failure goes to `repairByJitterRetriangulation` (jitter `1e-4` of the spacing, all points when the
structural check fails; on the PROJECTED points). Exact grids are valid at the default epsilon; at `1e-9` a 3-D grid can fail to repair and the raw walk is returned.
**Dispatch with a radius** (`AlphaShapes(points, maxRadius = r)`, the verb's and facade's `maxFiltrationValue`):
`Default` = the triangulation without a radius, else `prefersDQP` (mean of `k^1.6`, k = neighbours within `2r`, 64
samples, vs a fitted cost model: DQP `0.004·2.35^(d-2)·k^1.6`, BowyerWatson `b_d (n/1000)^0.2`, b = 0.03/0.1/1.1 for
d = 2..4, Helix 5-D `150 (n/1000)^0.4`, d >= 6 always DQP). Triangulations are filtered (`RadiusLimitedAlphaShapes`), DQP truncated (`AlphaComplexDQPStream`, top dim `maxDimension + 1`); the
results must be identical in general position (`AlphaDispatchSpec`; cospherical: DQP keeps the spanned simplex, Helix
triangulates). The facade passes no `Epsilon`: alpha uses `AlphaShapes`' default `1e-5` (the `epsilon` option is `field=R`). Untruncated DQP is ~100x slower than Helix. fast-alpha refuses a
radius and takes either triangulation. Measurements: `WORKLOG-helix-construction-speed.md`.
**Without a radius** the verb (`AlphaShapes.fromPoints` -> `upToEnclosingRadius`) cuts the triangulation (BowyerWatson/Helix, NEVER
`Default`, which picks DQP for any radius at d >= 6) at `minimumEnclosingRadius`: the union of balls is contractible past it, so the barcode is
unchanged (`PersistenceVerbSpec`); the facade's `complex=alpha` does the same except `engine=fast-alpha` (tutorial count 313; `Infinity` gives the 325 of `AlphaShapes(points)`, `MatlabTabsSpec`). #215.
`WORKLOG-enclosing-radius.md`.

**One root mechanism (near-cospherical clusters, order-dependent facet-pivot choices) produces two DIFFERENT
outcomes — don't conflate, a naive set-diff can't tell them apart**:
1. Order-dependent disagreement with DQP, **WONTFIX** (project lead) — the discarded side is reachable some
   other way too, still a complete triangulation. Helix is not reliable ground truth for dim≥4 fuzzing on
   degenerate input; `AlphaCrossValidationSpec`'s degenerate comparisons stay diagnostic (`unsafeCompare`/
   `unsafeFuzzCompare`).
2. Genuine incomplete triangulation (real topological hole), **fixed** where it was a bug: `handleCosphericalPoints`'s
   facet-queue now iterates every vertex of the new simplex. Its greedy point-pull still has no empty-circumsphere check
   (out of scope: symbolic perturbation), so `requireValidTriangulation`'s jitter-and-recompute also triggers on a
   genuine void in the RAW output. Self-consistency, not diff-vs-DQP, is the discriminator.

**`FastAlphaHomologyEngine`**: `FastCubicalHomologyEngine`'s dual union-find on a `DelaunayAlphaShapes`' top simplices (BowyerWatson
or Helix); any ambient dim>=2, never DQP (no adjacency). The "every facet has <=2 cofaces" precondition holds for BowyerWatson,
NOT always for Helix: it is validated and throws `FastAlphaTriangulationException` rather than build a wrong dual graph. Facet
dual-edge value comes from `HelixDelaunay.filtrationValue`, never a min over top simplices. At dim>=3 the cubical cohomology
hybrid on `LimitedAlphaShapesStream`, capped by `maxDimension` (the dual graph only when the cap reaches `d - 1`;
`persistentHomologyTopDegree` is the dual pass alone; `WORKLOG-fastalpha-degree-cap.md`). Wired as `engine="fast-alpha"`
(project lead signed off on the measured exception rate). Representatives use `SignedUnionFind[C]`
(`WORKLOG-fast-cubical-representatives.md`, `WORKLOG-cubical-performance.md`). `WORKLOG-alpha-dual-unionfind.md`, `DESIGN-alpha-dual-unionfind.md`.

**`HelixDelaunay(pts, seed, requireValidTriangulation = true)` repairs facet-multiplicity violations** (off by
default): nudges near-tied vertices, re-runs the builder, recomputes circumspheres from the ORIGINAL coordinates. Coning
and pruning to the smallest-circumradius claimants were rejected (wrong boundary cycle, can punch a real hole); a facet-count
self-check alone was insufficient, so `HelixDelaunay.interiorVoidVertices` (the full complex of a Delaunay hull is
contractible: `H_{d-1}` must be trivial) also triggers it, on RAW input too. Not attempted d>=4. `.claude/DESIGN-helix-triangulation-repair.md`.

**Degeneracy hazard**: cospherical `k` sites give a `(k-1)`-simplex (unit grid in R² → 3-simplices) — correct,
not a bug; truncating at ambient dimension gives the wrong homotopy type.

**Backend choice is typed** (`WORKLOG-cursor-and-verb.md`): `AlphaShapes(points: PointCloud, backend: AlphaBackend =
Default)`, `AlphaBackend.Default | Helix | DQP | BowyerWatson` (parse: `bowyer-watson`/`bowyerwatson`/`bw`); the facade's `alphaBackend` string goes
through `AlphaBackend.parse`. `AlphaShapes` is also a `PointCloudComplex` (`Persistence(points, complex = AlphaShapes)`),
which refuses `maxFiltrationValue` with a message pointing at `diagram.at(f)`.

## Parallelism in the alpha pipeline (`WORKLOG-parallelism.md`)

Everything shared between threads gives the same simplices, values to the bit and stream order as one thread (`ParallelAlphaSpec`
compares each against `Parallel.sequentially`; check a change with `AlphaProfileDriver dump=` before and after, `sequential=true`
for the reference). The Helix walk evaluates the next queued frontier cases (`evaluate`, pure in the points) in batches and takes
them in queue order: never let `evaluate` read walk state (`validated`, `visitedFacets`, `queuedByFacet`), and keep a speculative
failure swallowed (the walk re-evaluates the case when it reaches it). `FaceTable.facesOf` numbers faces by first occurrence, as
the sequential pass does; a face table made by it has no `add`. `DelaunayAlphaShapes.flatLayers` orders ties lexicographically
(not colex like the VR layers) and is `None` with repeated points. BowyerWatson insertion stays sequential.
