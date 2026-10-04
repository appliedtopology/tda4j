---
paths:
  - "src/**/alpha/**"
  - "src/**/*Alpha*.scala"
---

# Alpha complexes: DQP versus Helix, and the fast alpha engine

Loads when you work in `alpha/` or on an alpha file. Project-wide rules are in `.claude/CLAUDE.md`.

## Alpha complex: DQP vs Helix

`WORKLOG-alpha-complex.md`, `HANDOFF-alpha-complex.md`. `AlphaShapes(points, dispatch)`: `"default"` → `"helix"`
(`HelixDelaunay`); `"DQP"` must be explicit. Alpha and VR/Ripser are separate sections with minimal interaction
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

**One root mechanism (near-cospherical clusters, order-dependent facet-pivot choices) produces two DIFFERENT
outcomes — don't conflate, a naive set-diff can't tell them apart**:
1. Order-dependent disagreement with DQP, **WONTFIX** (project lead) — the discarded side is reachable some
   other way too, still a complete triangulation. Helix is not reliable ground truth for dim≥4 fuzzing;
   `AlphaCrossValidationSpec` comparisons stay diagnostic (`unsafeCompare`/`unsafeFuzzCompare`).
2. Genuine incomplete triangulation (real topological hole), **fixed**. Self-consistency (not diff-vs-DQP) is
   the discriminator. Two compounding bugs: (a) `handleCosphericalPoints`'s facet-queue excluded the originating
   facet (fixed — iterate every vertex of the new simplex); (b) its greedy point-pull has no empty-circumsphere
   check (not fixed, out-of-scope symbolic-perturbation redesign). Worked around at the repair layer:
   `requireValidTriangulation`'s jitter-and-recompute also triggers on a genuine void on the RAW output.

**`FastAlphaHomologyEngine`** — `FastCubicalHomologyEngine`'s dual union-find ported to `HelixDelaunay`'s top
simplices; valid any ambient dim≥2, Helix only (DQP builds no adjacency structure). The "every facet ≤2 cofaces"
precondition is NOT guaranteed by construction (more likely violated at higher dim/more points) — validated
explicitly, throws `FastAlphaTriangulationException` rather than a silently-wrong dual graph. Facet dual-edge
value from `HelixDelaunay.filtrationValue` directly, never recomputed as min over top simplices. At dim≥3, same
chunks-hybrid as cubical on `LimitedAlphaShapesStream`; cross-validated at d=3. Wired as
`engine="fast-alpha"` (alpha+helix only, project lead signed off on the measured exception rate).
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
Default)`, `AlphaBackend.Default | Helix | DQP` (Default resolves to Helix); the facade's `alphaBackend` string goes
through `AlphaBackend.parse`. `AlphaShapes` is also a `PointCloudComplex` (`Persistence(points, complex = AlphaShapes)`),
which refuses `maxFiltrationValue` with a message pointing at `diagram.at(f)`.
