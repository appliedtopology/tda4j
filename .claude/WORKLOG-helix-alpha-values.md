# Helix alpha values: the Gabriel rule in every dimension (2026-10-04)

Point-in-time snapshot. Found by the cross-platform benchmark's agreement check (`bench/`) on its first smoke run.

## The finding

On 1000 uniform points in 3-D, `Persistence(points, complex = AlphaShapes)` disagreed with GUDHI's `AlphaComplex`:
- `H_2`: 149 bars against GUDHI's 1367;
- `H_1`: 2501 against 2496;
- bottleneck distance 0.017.

It was systematic, not a large-input effect: at 50 points it was 2 `H_2` bars against 34. The plane was fine.

The check that localized it compared three constructions with GUDHI at 50 points:
- the DQP backend: bottleneck distance 1.6e-15;
- the Čech complex: 1.6e-15 (the same barcode as alpha in general position);
- only Helix, the **default** backend, was wrong.

## The cause

`HelixDelaunay.computeFVal` applied the Gabriel rule (a simplex whose smallest circumsphere is empty enters at that
sphere's radius) **only to edges**. Every simplex of dimension ≥ 2 got the minimum circumradius of the top-dimensional
Delaunay simplices containing it.

In the plane, edges are the only middle dimension, so 2-D was right. In 3-D, Gabriel triangles entered at their
tetrahedra's radius instead of their own smaller one, so voids never opened, and most `H_2` classes (and a few `H_1`)
vanished.

The edge branch was also subtly wrong above 2-D. A non-Gabriel edge took the minimum over the top simplices, which can
exceed the value of a Gabriel triangle containing it.

`FastAlphaHomologyEngine` reads `helix.filtrationValue` for its dual edges, so it inherited the wrong values.

## The fix (`alpha/AlphaShapes.scala`)

Values are computed top-down by dimension, once, in `alphaValues`:
- **top dimension:** the smallest circumradius among the Delaunay cells containing the simplex (its own, unless a
  cospherical cluster was tiled as one larger cell);
- **dimension `k < d`:**
  - compute `σ`'s smallest circumsphere, with its centre **in `σ`'s affine hull**. The new `smallestCircumsphere`
    solves the `k×k` Gram system. It does not reuse `Hypersphere.apply`, whose SVD least-squares centre is the
    minimum-norm solution, off the affine hull below full dimension;
  - `σ` is Gabriel if no opposite vertex of an immediate coface lies strictly inside that sphere (the same `epsilon`
    convention as before). This is the test GUDHI uses, and for a Delaunay simplex it is equivalent to testing every
    point;
  - `α(σ) = min(r, min over cofaces)` if Gabriel, else the minimum over its immediate cofaces. For a Gabriel simplex
    `r` is already at most every coface's value; the `min` keeps monotonicity exact in floating point.
- **vertices:** 0, as before.
- **Speed:** the containing top cells come from a vertex index rather than a scan of every Delaunay cell per simplex,
  which was quadratic. The unused `edgeIsDelaunay` was removed.

## Gate

- **New `HelixDqpAgreementSpec`** (a normal CI spec, not a diagnostic): Helix equals DQP bar for bar within `1e-9`
  on random uniform clouds, ignoring bars shorter than `1e-7`.
  - The clouds: 20 in 2-D and 20 in 3-D, of 8–40 points; 8 in 4-D, of 8–16 points.
  - DQP shares no construction code with Helix.
- Before the fix, `AlphaCrossValidationSpec`'s Helix-vs-DQP comparisons were diagnostics only, which is how this
  survived.
- Against GUDHI on random 3-D clouds, bottleneck distance:

  | points | distance | bar counts |
  |---|---|---|
  | 50 | 1.3e-15 | identical |
  | 100 | 2.1e-15 | identical |
  | 200 | 2.4e-14 | identical |
  | 400 | 1.2e-9 | `H_1` 918 against 916, `H_2` 452 against 450 |

  The extra bars at 400 points have persistence ~1e-9: floating-point near-ties that GUDHI's exact predicates
  resolve as zero-length.
- All alpha specs, `FastAlphaHomologySpec` and `FastRepresentativesSpec` pass. `testFull`: 922 tests, 0 failures.
- **Harness re-run** (`bench/`, 4-core sandbox, warm median):
  - `unif3d_1000` now agrees with GUDHI: bottleneck distance 1.6e-8, and `H_2` 1370 bars against GUDHI's 1367 (the
    extra 3 are near-ties).
  - Time went from 54.9 s to 12.1 s; the vertex index replaced the scan over every Delaunay cell.
  - `unif2d_1000` went from 3.7 s to 2.1 s, still agreeing.
  - Both remain about 200x behind GUDHI (CGAL); Helix's construction dominates.

## Not done

- Helix's construction speed is unchanged, apart from the vertex index; it dominates alpha timings (`bench/`).
- The degenerate (cospherical) cases keep their documented WONTFIX behaviour. Agreement is asserted for general
  position only.
