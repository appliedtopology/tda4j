---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/homology/CircularCoordinates.scala"
  - "src/main/scala/org/appliedtopology/tda4j/homology/LatticeReduction.scala"
  - "src/test/scala/org/appliedtopology/tda4j/homology/CircularCoordinates*"
  - "src/test/scala/org/appliedtopology/tda4j/homology/ToroidalCoordinatesSpec.scala"
  - "src/test/scala/org/appliedtopology/tda4j/homology/LatticeReductionSpec.scala"
---

# Circular and toroidal coordinates

Loads when you work on `CircularCoordinates` or `LatticeReduction`. Project-wide rules are in `.claude/CLAUDE.md`; the
engines they sit on are in `rules/engines.md`.

**`CircularCoordinates`** (de Silva-Morozov-Vejdemo-Johansson 2011): `h1Bars` lists persistent H¹ `(birth,death)` by
persistence descending (pick `r` from this first); `compute(metricSpace, r, cocycleIndex, prime=47,...)` computes
cohomology of the *static* truncated complex `K_r` (essential there, matched to the full-filtration bar by birth value).
Odd prime field only (p=2 can hide torsion); the integer lift is checked **exactly** per triangle
(`NoIntegerCocycleException` otherwise). Harmonic smoothing by `ConjugateGradient` on the cocycle's connected
component, anchored at `g=0`; `theta(v)=frac(g(v))`. MATLAB mirrors it; no CLI. `WORKLOG-circular-coordinates.md`.

**From a diagram** (#236): `CircularCoordinates(diagram, input, bar, r, complex)`, `toroidal(diagram, input, bars, ...)`,
`h1Bars(diagram)` take any `PersistenceDiagram[Simplex[Int]]` plus what `Persistence` was given (a diagram does not
remember its complex); the field is the diagram's (odd prime only). `diagram.representatives == Some(Cocycles)` (set by
`Persistence`, `None` for hand-built and snapshot diagrams) supplies each bar's cocycle, restricted to `K_r` and checked
mod p then over Z; any other diagram has the class recovered by `CellularCohomologyEngine` on `K_r`, matched by birth.
`compute(metricSpace, ...)` keeps its own VR front half. **A bar's class is defined only modulo the shorter bars nested
in it**: with such bars alive at `r` the engines' coordinates differ (all valid), so cross-route tests need `r` where
exactly the asked-for loops are alive. `WORKLOG-circular-from-diagram.md`. A Dowker diagram of cocycles lifted to the
points (`Dowker.lift`) with the points' Dowker complex as `input` gives every point a coordinate; that complex is dense
(14 s at 400 points capped at `r`, out of memory uncapped), `WORKLOG-dual-witness-complex.md`.

**Sparse coordinates** (Perea 2020, arXiv:1809.09269; `sparse(diagram, relation, ...)`, `sparseToroidal`, `numLandmarks = n`
on `h1Bars`/`compute`/`computeToroidal` and the MATLAB/Python/JS mirrors): class and harmonic smoothing on the Dowker
complex of the relation (the cover's nerve restricted to the data, no Rips-at-2α transfer), then `extendToPoints`: point
`x` gets `g(j) - sum_k phi_k(x) h(j -> k)`, `j` its nearest landmark, `phi_k` proportional to `max(r - R(k)(x), 0)`.
Orientation: this file's `h = z - d0 g`, so `g(b) - g(a) = -h(a -> b)` mod 1 (Perea's tau is `-g`); the sign is what the
continuity spec (`SparseCircularCoordinatesSpec`, steps < 0.02 on 300 points) catches. `numLandmarks` caps the nerve
where one point sees every landmark (a cone: no loops after). Unweighted smoothing (Perea's edge weights not used).
Scale: 20,000 points / 50 landmarks ~4 s. `WORKLOG-sparse-circular-coordinates.md`.

**Toroidal coordinates** (`computeToroidal`, Scoccola et al. 2022, arXiv:2212.07201): combines `k` *simultaneously*-alive
H¹ classes (common `r`, same connected component of `K_r`, checked) into one torus-valued map: `LatticeReduction` (LLL,
`delta=3/4`) of the classes' harmonic-cochain Gram matrix's Cholesky factor, the unimodular `U` applied to the per-class
`theta`s. Not a port of DREiMac's `toroidalcoords.py` (its `_gram_schmidt` bug: `.claude/BUGS-IN-REFERENCES.md`). MATLAB: `toroidalCoordinates`/`ToroidalCoordinatesResult`; no CLI.
`WORKLOG-toroidal-coordinates.md`.
