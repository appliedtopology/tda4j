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
never the Gram system, it lost 5e-5 on a 4-D sliver).

`WORKLOG-alpha-complex.md`, `HANDOFF-alpha-complex.md`. `AlphaShapes(points, backend)`: `Default` → BowyerWatson
(width <= 4) or Helix; DQP only with a radius via `prefersDQP`, or explicitly. Alpha and VR/Ripser are separate sections with minimal interaction
(project lead's standing call). Never resurrect the ripped-out Miniball-Delaunay backend.

`AlphaComplexDQP` implements Carlsson & Carlsson (Sci. Rep. 14:19824, 2024), DAQP-style dual active-set QP;
`CholeskyWorkspace` hand-rolled incremental Cholesky. Filtration values are squared radii internally; `radiusOf`
takes sqrt. `AlphaShapeDQP` always untruncated; `AlphaComplexDQP.euclidean(points, maxRadius, ...)` for truncation.

**Settled `DualQP.solve` numerics — don't retune**: `rankTolerance=1e-6` (safe `[1e-7,1e-5]`); ratio-test ties
broken by global constraint index (Bland's rule); small-Schur-complement-no-swappable-inequality → infeasible
(accepted limitation, "commit anyway" tried and reverted). Vertex filtration value is `-space.weight(x)` only
when `x` is inside its own restricted power cell `V_x`, else the min over `x`'s own incident already-solved
edges (dim 1 before 0), and a vertex with no incident edges is dropped entirely
(`AlphaComplexDQPVertexAttachmentSpec`, `WORKLOG-dtm-filtrations.md`). Regressions in
`AlphaComplexDQPRegressionSpec`/`AlphaValidationSpec` (`minTestsOk=2000`).

**HelixDelaunay's bootstrap crash is fixed** (`.claude/WORKLOG-helix-bootstrap-fix.md`) via three additive fixes
routed through one shared `rankAtEpsilon` helper: retry every affinely-independent hyperplane candidate
(smallest-span first); reject affinely-degenerate candidates outright; project a globally-coplanar cloud onto
its true affine span first.

**Helix alpha values are computed top-down** (`alphaValues`): top cells take the smallest containing circumradius;
below that a simplex takes its own smallest circumradius (centre in its affine hull, `smallestCircumsphere`, never
`Hypersphere.apply`) if Gabriel w.r.t. its cofaces' opposite vertices, else the min over its immediate cofaces. The
Gabriel rule once covered edges only, so 3-D lost most `H_2` (`WORKLOG-helix-alpha-values.md`). **In general position
Helix must equal DQP bar for bar** (`HelixDqpAgreementSpec`, a CI gate in 2-D/3-D/4-D); only degenerate inputs stay
diagnostic.

**Helix walk** (`WORKLOG-helix-construction-speed.md`): hull facet by gift wrapping (deterministic, no RNG); across
each frontier facet the cofacet is the light point with the smallest centre parameter `t` (sphere centre `c0 + t n`),
ties to the smallest index, its sphere taken from `t` (never re-solved from the vertices: ill-conditioned on slivers),
then checked empty; the old candidate scan is only the fallback (`sphereScanFallbacks`, 0 on random clouds,
`HelixWalkSpec`). Cospherical = within `1e-10 r`, NOT `epsilon` (at the default `1e-5` near-ties got tiled as clusters
on a few thousand random points and tore the triangulation). EVERY result gets `looksValid` (each point a vertex, no
facet in 3 tops, every boundary facet on the hull -- Euler characteristic is NOT enough: a partial contractible walk
passes it); a failure goes to `repairByJitterRetriangulation` (jitter `1e-4` of the spacing, all points when the
structural check fails; on the PROJECTED points). Exact grids are valid by default at the default epsilon; at a far
smaller epsilon (`1e-9`) a 3-D grid can still fail to repair (near-coplanar float predicates) and the raw walk is
returned. Agreement with GUDHI at scale (bench harness): bottleneck <= 1e-8 at 1000/5000 3-D, 6e-7 at 10000 2-D.
**Dispatch with a radius** (`AlphaShapes(points, maxRadius = r)`, the verb's and facade's `maxFiltrationValue`):
`Default` = the triangulation without a radius, else `prefersDQP` (mean of `k^1.6`, k = neighbours within `2r`, 64
samples, vs a fitted cost model: DQP `0.004·2.35^(d-2)·k^1.6`, BowyerWatson `b_d (n/1000)^0.2`, b = 0.03/0.1/1.1 for
d = 2..4, Helix 5-D `150 (n/1000)^0.4`, d >= 6 always DQP). Triangulations are filtered (`RadiusLimitedAlphaShapes`), DQP truncated (`AlphaComplexDQPStream`, top dim `maxDimension + 1`); the
results must be identical in general position (`AlphaDispatchSpec`; cospherical: DQP keeps the spanned simplex, Helix
triangulates). The facade passes no `Epsilon`: alpha uses `AlphaShapes`' default `1e-5` (the `epsilon` option is `field=R`). Untruncated DQP is ~100x slower than Helix. fast-alpha refuses a
radius and takes either triangulation. Measurements: `WORKLOG-helix-construction-speed.md`.

**One root mechanism (near-cospherical clusters, order-dependent facet-pivot choices) produces two DIFFERENT
outcomes — don't conflate, a naive set-diff can't tell them apart**:
1. Order-dependent disagreement with DQP, **WONTFIX** (project lead) — the discarded side is reachable some
   other way too, still a complete triangulation. Helix is not reliable ground truth for dim≥4 fuzzing on
   degenerate input; `AlphaCrossValidationSpec`'s degenerate comparisons stay diagnostic (`unsafeCompare`/
   `unsafeFuzzCompare`).
2. Genuine incomplete triangulation (real topological hole), **fixed**. Self-consistency (not diff-vs-DQP) is
   the discriminator. Two compounding bugs: (a) `handleCosphericalPoints`'s facet-queue excluded the originating
   facet (fixed — iterate every vertex of the new simplex); (b) its greedy point-pull has no empty-circumsphere
   check (not fixed, out-of-scope symbolic-perturbation redesign). Worked around at the repair layer:
   `requireValidTriangulation`'s jitter-and-recompute also triggers on a genuine void on the RAW output.

**`FastAlphaHomologyEngine`** — `FastCubicalHomologyEngine`'s dual union-find ported to a `DelaunayAlphaShapes`'
top simplices (BowyerWatson or Helix); valid any ambient dim≥2, never DQP (no adjacency structure). The "every facet ≤2
cofaces" precondition is guaranteed by BowyerWatson, NOT by Helix (more likely violated at higher dim/more points) — validated
explicitly, throws `FastAlphaTriangulationException` rather than a silently-wrong dual graph. Facet dual-edge
value from `HelixDelaunay.filtrationValue` directly, never recomputed as min over top simplices. At dim≥3, same
cohomology hybrid as cubical on `LimitedAlphaShapesStream`; cross-validated at d=3. Wired as
`engine="fast-alpha"` (alpha with a triangulating backend, project lead signed off on the measured exception rate). Representatives
share the cubical engine's `SignedUnionFind` bookkeeping (`WORKLOG-fast-cubical-representatives.md`).
`WORKLOG-alpha-dual-unionfind.md`, `DESIGN-alpha-dual-unionfind.md`.

**`HelixDelaunay(pts, seed, requireValidTriangulation = true)` repairs facet-multiplicity violations** (off by
default) — nudges near-tied vertices, re-runs the same builder, recomputes circumspheres from ORIGINAL
coordinates ("simulation of simplicity"). Two earlier designs (coning from an apex; pruning to
smallest-circumradius claimants) were rejected (wrong boundary cycle / can punch a real hole). **Its own first
version had the identical failure mode as the rejected pruning design** (~10.5% barcode disagreement at d=3) —
facet-count self-check alone insufficient; fixed with a second check, `HelixDelaunay.interiorVoidVertices` (a
genuine Delaunay hull is convex hence contractible, so `H_{d-1}` of the full unfiltered complex must be trivial),
also triggered on RAW unrepaired input. Not attempted d≥4. `.claude/DESIGN-helix-triangulation-repair.md`.

**Degeneracy hazard**: cospherical `k` sites give a `(k-1)`-simplex (unit grid in R² → 3-simplices) — correct,
not a bug; truncating at ambient dimension gives the wrong homotopy type. Honest framing: paper's benchmarks
mixed vs Ripser/qhull; value is high ambient dimension + exact homology + small complexes, not raw speed.

**Backend choice is typed** (`WORKLOG-cursor-and-verb.md`): `AlphaShapes(points: PointCloud, backend: AlphaBackend =
Default)`, `AlphaBackend.Default | Helix | DQP | BowyerWatson` (parse: `bowyer-watson`/`bowyerwatson`/`bw`); the facade's `alphaBackend` string goes
through `AlphaBackend.parse`. `AlphaShapes` is also a `PointCloudComplex` (`Persistence(points, complex = AlphaShapes)`),
which refuses `maxFiltrationValue` with a message pointing at `diagram.at(f)`.
