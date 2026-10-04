# WORKLOG: Helix construction speed (lever 2), timeboxed (2026-10-04)

Lever 2 of the paper-benchmark plan. Timeboxed per the advisor: measure DQP first, then report rather than rewrite.

## DQP vs Helix (`PaperBenchmarkDriver task=alpha`, `-Xms8g -Xmx8g`, ParallelGC)

| input | Helix | DQP |
|---|---|---|
| unif2d_1000 (degrees 0-1) | 2.5 s (warm) | 252 s |
| unif3d_1000 (degrees 0-2) | 13.6 s (warm) | not finished; stopped, 2-D already settles it |

DQP is ~100x slower in 2-D, so Helix stays the default and is the thing to speed up. (2-D: Helix 921 H₁ bars, DQP
920; `HelixDqpAgreementSpec` ignores bars shorter than 1e-7, so this is presumably a near-zero bar. Not chased.)

## Where Helix's time goes (unif3d_1000)

Throwaway phase timer: triangulation 8-10 s; alpha values 0.01 s; homology (cohomology engine, 27,531 simplices) ~0.4 s.
So the frontier walk (`HelixDelaunayBuilder.compute`) is everything. JFR sampled badly in this sandbox (281-614
samples per 10-17 s run), but its leaves were `RedBlackTree` equality iterators (`SortedSet ==`), `TimSort`, and
immutable `HashSetBuilder`.

## Changes (output identical)

1. `validated.exists(ds => new.simplex == ds.simplex)` scanned every accepted simplex with a `SortedSet` comparison,
   per frontier case: replaced by a `HashSet[Simplex[Int]]` kept beside `validated` (`accept`).
2. `addFrontierCase` ran `frontierCases.removeIf(facet ==)` over the whole queue: replaced by a facet index
   (`queuedByFacet`) and lazy cancellation (cancelled cases marked by identity, skipped when taken). Same semantics:
   a facet already queued is cancelled instead of re-queued, the order of the others is unchanged. The cospherical
   branch's subset `removeIf` goes through the same index. The main loop takes the next case only AFTER processing
   the current one (processing can cancel queued cases; prefetching was a bug caught in review of my own diff before
   running).

Identity check: a throwaway dump of every simplex and its alpha value, HEAD vs new, on a 6x6 grid, a 4x4x4 grid, 12
cospherical points plus centre, and 20 random clouds (2-D and 3-D), seeds 0 and 3 each: byte-identical (21,802
lines). Alpha/Helix specs and `FastRepresentativesSpec` pass.

Same-session A/B, 1 warm-up + 5 trials, medians: unif3d_1000 14.13 → 9.95 s (1.4x, trials do not overlap);
unif2d_1000 2.34 → 2.05 s (1.14x).

## Tried, no effect, reverted

Allocation-free `isLight`/`contains`/distance on cached coordinate arrays (bit-identical arithmetic): triangulation
8.0 → 7.85 s, noise. The per-point vector allocations are not the cost.

## Not done (outside the timebox)

- Each frontier case builds `points.indices.toSet -- facet` (an immutable HashSet of n Ints) and fully sorts the
  light points by distance, then tests candidates in that order, each with an O(n) empty-sphere scan. A lazy
  selection (heap) plus a spatial index for the empty-sphere test is the real fix, but the HashSet's iteration
  order is the tie-break among equidistant candidates: changing it can change which valid tiling Helix produces on
  cospherical input (grids). That is a behaviour change to decide deliberately, not a speedup to slip in.
- The walk is inherently O(frontier × n); against GUDHI/CGAL (milliseconds for 1000 points) Helix stays orders of
  magnitude slower. For the paper, alpha speed is not a claim to make; exactness and high ambient dimension are.

## Option 1: the frontier walk rework (same day, project lead: tie-order changes on cospherical input are acceptable;
ideally deterministic run to run)

### What changed (`alpha/AlphaShapes.scala`)

1. **Cofacet by minimal centre parameter.** Every sphere through a frontier facet has its centre on `c0 + t n` (`c0`
   the facet's circumcentre in its own hyperplane, from a Gram system; `n` the normal towards the light side) and passes
   through light point `p` at `t(p) = (|p - c0|² - r0²) / (2 n·(p - c0))`. The Delaunay cofacet is the smallest `t`;
   one O(n d) primitive pass, no sorting, no per-candidate emptiness scans. Ties to the smallest index. The chosen
   simplex's sphere is `c0 + t n`, radius `sqrt(r0² + t²)`: re-solving it from the 4 vertices (`Hypersphere.apply`,
   least squares) was off by far more than epsilon on slivers (near-coplanar points). Checked empty; the old scan is the
   fallback (never taken on random clouds).
2. **Seed simplex** from the hull facet by the same selection (was: every point x a full emptiness scan, O(n²) with an
   n-element `Set` per test).
3. **Hull facet by gift wrapping** (was: a random walk of re-picks among "light" points, each round rebuilding an
   n-element `Set`; at 5000 3-D points this was most of the time). Deterministic; `seed` now only seeds repair jitter.
4. **Cospherical = within `1e-10 r`**, not `epsilon`. At the default `epsilon = 1e-5`, 5000 uniform 3-D points had
   near-cospherical groups tiled as clusters; those tilings do not match their neighbours, every such run then failed the
   validity check and went through the (homology-based) full check and jitter retriangulations: 472 s -> 15 s.
5. **Every walk is checked** (`looksValid`: each point a vertex, no facet in three top simplices, every single-coface
   facet on the hull) and repaired if it fails, even unasked (unasked, a failed repair returns the raw walk). First
   version used Euler characteristic 1 instead of the hull test: a 13-tetrahedron partial walk of a 4x4x4 grid passed
   it. Repair: jitter `1e-4` of the spacing (was `1e-6`: a jittered row of grid points is collinear to 1e-6 and its
   facets' circumcentres are too ill-conditioned for the walk -- 19 over-claimed facets on a 1e-6-jittered 4x4x4 grid,
   still 6 at 2e-4 and none at 2e-3 with `epsilon = 1e-9`); all points jittered when the structural check fails (a
   torn grid has no local culprit: jittering only the bad facets' vertices re-tore the rest for 8 attempts); repair runs
   on the projected points (it used the unprojected ones: latent while repair was opt-in).
6. `badFacetsOf` counts distinct simplices (a cluster tiling can record a simplex the walk also found, with the
   cluster's sphere: that is one simplex, not a third claimant). The engine (`FastAlphaHomologyEngine`) already counted
   distinct simplices.

### Gates

- `HelixWalkSpec` (new): random 2-D/3-D/4-D clouds -- no fallback, no over-claimed facet, no cavity; exact 6x6 and
  4x4x4 grids valid by default (Euler characteristic 1, full volume, no over-claimed facet). Without the automatic check
  the 4x4x4 grid gave volume 4.2 of 27.
- HEAD vs new on random clouds and a 12-point cospherical circle: identical simplices and values (seeds 0 and 3), before
  the sphere-from-`t` change (which moves top values in the last bits). Grids: a different valid tiling.
- Exact grids at the default epsilon, two seeds each: 4², 6², 10², 3³, 4³, 5³, 3⁴ all valid. At `epsilon = 1e-9` the
  4x4x4 grid still does not repair (the near-coplanar float-predicate problem below); documented limitation.
- Pinned facet-multiplicity fixture (`FastAlphaHomologySpec`, 12 points, 2-D): old walk -- edge {8,10} in three distinct
  triangles (a real violation); new walk -- valid. The exception test now runs the engine's check on a hand-built
  three-coface map (`FastAlphaHomologyEngine.requireDualGraph`); `TDA4jSpec`'s facade test now checks the same input
  computes and agrees with the naive engine.
- Validity search: 0 invalid in 20000 2-D (12 points), 3000 3-D (20), 500 4-D (25) random clouds -- but the OLD walk
  also had 0 on those seeds, so no validity improvement is claimed from random clouds.
- Against GUDHI (bench harness, bottleneck): unif3d_1000 1.65e-8, unif3d_5000 8.05e-9, unif2d_10000 6.27e-7.

### Timings (this sandbox, cold single runs unless noted)

| input | before this arc | hash indices only | walk rework |
|---|---|---|---|
| unif3d_1000 triangulation | ~8-10 s | ~8 s | ~1.3-2.9 s |
| unif3d_1000 whole alpha task (driver, warm) | 14.1 s | 9.95 s | ~1.5 s |
| unif3d_5000 triangulation | not run (est. minutes) | -- | 15-17 s (472 s before the cosphericity fix) |
| unif2d_10000 triangulation | not run | -- | 16-26 s |

Harness against GUDHI (fast-alpha engine, warm): 2-D 10000 points 14 s vs 0.15 s (91x); 3-D 1000 3.0 s vs 0.07 s
(45x); 3-D 5000 16.6 s vs 0.44 s (38x). Still O(facets x n) per walk: a spatial index for the minimal-centre search
is the next lever (per facet only points near the facet's sphere can win). Not done.

### Open: near-coplanar robustness

Inconsistent decisions between neighbouring facets on near-coplanar groups (five grid points on a cube face coplanar
to 2e-3) remain possible with float predicates; exact/adaptive predicates (Shewchuk) or symbolic perturbation would
remove it. The repair's larger jitter sidesteps it for grids at the default epsilon.

## Option 3: radius-aware alpha dispatch (same day)

Question (project lead): DQP builds partial skeleta, Helix the whole Delaunay first -- can that inform the dispatch?
Yes. DQP grows the complex dimension by dimension (edges, then cofaces of accepted simplices), each candidate one QP,
restricted to Cech neighbours within `2 maxRadius` (VP-tree); `AlphaShapeDQP` (what `AlphaBackend.DQP` meant until now)
had no radius, so every pair was a neighbour: the 252 s for 1000 2-D points was its worst case.

### Crossover measurements (throwaway driver; DQP truncated at r with top dimension = ambient; Helix full; one JVM per
input, JIT warmed on 200 points; k = mean number of points within 2r over 200 samples)

| input | Helix full | DQP at k ≈ ... | crossover k |
|---|---|---|---|
| 2-D 1000 | 0.53 s | 14.6: 0.37 s, 28.6: 1.05, 104.7: 5.7, 190.8: 9.3 | ~20 |
| 2-D 10000 | 14.2 s | 6.1: 1.1 s, 17.9: 2.3, 35.3: 5.3, 75: 18.1, 144: 59.2 | ~60 |
| 3-D 1000 | 1.7 s | 25.8: 0.96 s, 52.3: 2.3, 89.4: 7.9, 156: 17.6 | ~40 |
| 3-D 5000 | 14.3 s | 13.6: 1.9 s, 31.2: 5.3, 70.8: 17.2, 129: 52.2 | ~60 |
| 4-D 1000 | 10.1 s | 6: 0.31 s, 27.1: 2.0, 72.6: 9.8, 148: 30.3, 256: 86.7 | ~73 |
| 5-D 500 | 57.0 s | 13.3: 0.47 s, 33.9: 2.5, 67.7: 10.2, 118: 25.4 | >150 |

Model: DQP per point ≈ `c_d k^1.6` ms with `c_d` ≈ 2.35x per dimension (`c_2` ≈ 0.0018 at 10000 points, ≈ 0.004-0.005
at 1000: the 1000-point constant is used, which errs towards Helix near the crossover -- past it DQP's cost climbs
steeply, Helix's is fixed); Helix per point ≈ `h_d (n/1000)^0.4` ms, h = 0.53, 1.7, 10, 150 for d = 2..5. Predicted
crossovers are 0.6-0.8x the measured ones (conservative). d >= 6: DQP whenever a radius is given (Helix's Delaunay
complex there is far beyond these sizes).

### Implementation

- `AlphaShapes.apply(points, backend, requireValidTriangulation, maxRadius, maxDimension)`; `Default` = Helix without a
  radius, else `prefersDQP`. Helix + radius = `RadiusLimitedAlphaShapes` (filter by value; a subcomplex since values do
  not decrease to cofaces). DQP + radius = `AlphaComplexDQPStream(AlphaComplexDQP.euclidean(points, r, top))`, top =
  `maxDimension + 1` capped at ambient.
- `AlphaShapes.fromPoints` (the verb) no longer refuses `maxFiltrationValue`; MATLAB/CLI `maxFiltrationValue` now
  reaches alpha (it was silently ignored), facade default `alphaBackend` is `default`; `fast-alpha` + radius refuses.
- Gate `AlphaDispatchSpec`: Helix-filtered == DQP-truncated (simplices and values to 1e-9) on random 2-D/3-D/4-D clouds
  at three radii; verb bars equal across backends and through `maxFiltrationValue`; the rule picks DQP at small r.

### Review follow-ups (same day)

- The facade passes no `Epsilon`: alpha gets `AlphaShapes`' default 1e-5 (the facade's `epsilon` option is the
  `field=R` tolerance only), so the small-epsilon grid limitation does not affect MATLAB/CLI defaults. New facade
  example: 4x4x4 grid, `fast-alpha` (needs a valid triangulation) agrees with `naive`.
- The unasked repair catches only the non-convergence `IllegalStateException`; any other exception propagates.
- `prefersDQP` averages `k^1.6` over the samples (DQP's cost is a sum of per-point `k^1.6`; the mean of `k` understates
  it on clustered data). Results never depend on the choice.
- Claims narrowed: backends agree in general position (on cospherical points DQP keeps the spanned simplex, Helix
  triangulates; same barcode up to zero-length bars); repair jitter (1e-4 of the spacing) can flip near-ties closer
  than that, not only exact ties.
