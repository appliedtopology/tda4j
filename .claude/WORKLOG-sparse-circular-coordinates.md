# Sparse circular coordinates (Perea), 2026-11-01

Mikael picked option A on "Should landmark circular coordinates scale past a few hundred points?" (Perea's sparse
coordinates), then: "We might as well extend the entire circular/toroidal interface to allow the user to choose Perea's
sparse coordinates." Follows `WORKLOG-dual-witness-complex.md` (the dense route: Dowker.lift of cocycles + harmonic
smoothing on the points' complex, 14 s at 400 points capped at r, out of memory uncapped).

## The construction, checked against the paper

Perea, "Sparse Circular Coordinates via Principal Z-Bundles" (arXiv:1809.09269v2; text read from the PDF):
- Theorem 3.2 / formula (11): for b in U_j, h(b) = exp 2 pi i (tau_j + sum_k phi_k(b) theta_jk), with
  theta = iota#(eta) + delta0 tau, theta_jk on the oriented edge [j, k], theta_jk = -theta_kj.
- Lemma 3.1: well defined because eta_jk = eta_jl + eta_lk on triples whose sets meet at b.
- (13): phi_l(b) = |alpha - d(l, b)|_+ / sum_l' |alpha - d(l', b)|_+, open balls B_alpha(l) = {d < alpha}.
- Section 4 computes on Rips(L) at 2 alpha and transfers to the nerve N(B_alpha) because nerves of ambient balls are
  expensive. We do not need that: the Dowker complex of landmark-to-point distances is the nerve of the balls
  restricted to the data, which is all the well-definedness argument uses (b ranges over data points).
- Harmonic smoothing: weighted least squares (17)-(18) with weights omega; he says the weights are not systematically
  investigated. We use the existing unweighted smoothing (`harmonicSmooth`), same as the dense route.
- Integer lift (14): centered residues, as `centeredResidues` does; he notes it can fail when the computation divides
  by 2 (the exact check here reports it).

Orientation: `harmonicSmooth` returns h = z - d0 g with d0 g([a, b]) = g(b) - g(a) and theta(v) = frac(g(v)). So
Perea's tau is -g and his theta is our h; in our orientation the point gets g(j) - sum_k phi_k(x) h(j -> k). Check:
for x in U_j and U_k, the two values differ by (g_j - g_k) - h(j -> k) (sum of phi is 1, h a cocycle on the triangles
x witnesses), and g_k - g_j = z - h = -h(j -> k) mod 1, so the difference is 0 mod 1.

## What was built

- `CircularCoordinates.sparse(diagram, relation, bar, r)`, `sparseToroidal(diagram, relation, bars, r, reduce)`: the
  diagram is `Dowker(relation, ...)`'s (rows = cover sets, columns = points); smoothing via the shared `smoothClasses`
  with a private `Persistence.Input` that builds `Dowker(relation, maxDimension, maxFiltrationValue)` (so K_r is the
  nerve at r), then `extendToPoints` per class.
- `numLandmarks: Int = 0` on `h1Bars(metricSpace, ...)`, `compute`, `computeToroidal`: maxmin landmarks,
  `WitnessGeometry.D`, `Persistence(Dowker(D, 1, cap), characteristic = prime, Cocycles)` with cap = the cone value
  min_x max_l D(l, x) (after it the nerve is a cone; bars unchanged). `h1Bars` uses prime 47 to match `compute`'s default.
- `combineClasses` now reads the points from `classData.head.theta.keys` (same set as before for the dense route, the
  covered points for sparse).
- MATLAB overloads `h1Bars(points, numLandmarks)`, `circularCoordinates(points, r, idx, prime, numLandmarks)`,
  `toroidalCoordinates(points, r, idxs, prime, reduce, numLandmarks)`; JS option `numLandmarks` on `h1Bars` (new options
  argument), `circularCoordinates`, `toroidalCoordinates`; Python `num_landmarks` on the three. No CLI command exists.
- Parameter named `numLandmarks`, not `landmarks`: elsewhere `landmarks` is an index list (`WitnessGeometry.landmarks`).

## Tests (`SparseCircularCoordinatesSpec`, facade specs)

- Circle, 300 points, 12 landmarks: every point covered, max step between neighbours < 0.02 (uniform 1/300), winds once.
- Flat torus 20 x 20 in R^4, 36 landmarks: degrees of the two coordinates along both circles, the same on every circle,
  determinant +-1.
- `sparse` on a hand-built Dowker diagram = `compute(numLandmarks = 10)` up to rotation.
- Errors: more landmarks than points; r below the birth.
- Mutations: flipping the sign of the partition-of-unity term fails the circle and torus examples; dropping the term
  (theta of the nearest landmark only, piecewise constant) fails the circle example.
- MATLAB spec: overloads match the Scala results; JS: winds once with `numLandmarks`; Python: covered, continuous.

## Timings (JVM, one run each, noisy circle sigma 0.05 / flat torus grid, unit radii)

| input | landmarks | h1Bars | compute / computeToroidal | covered |
|---|---|---|---|---|
| circle 400 | 12 | 0.34 s (cold) | 0.19 s | 400 |
| circle 2,000 | 30 | 0.25 s | 0.30 s | 2,000 |
| circle 20,000 | 50 | 3.4 s | 4.2 s | 20,000 |
| torus 10,000 (100 x 100) | 100 | 2.8 s | 3.3 s (two loops) | 10,000 |
| docs example, circle 5,000 | 30 | 1.4 s together | | 5,000 |

The dense route for comparison: 400 points 14 s capped at r, out of memory uncapped. Both landmark-side computations
include the cohomology twice (h1Bars, then compute); a caller holding the diagram can use `sparse` once.

## Open / not done

- Perea's edge weights for the smoothing (his (15)-(18)) are not offered; unweighted as in the dense route.
- `apply(diagram, input, ...)` with a non-Dowker diagram has no sparse form: sparse needs a cover, so it takes the
  relation. A witness-complex diagram could be used via the inclusion Dowker <= witness, unchecked.
