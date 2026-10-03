---
paths:
  - "src/**/cells/**"
  - "src/**/groups/**"
  - "src/**/algebra/SSetElement*.scala"
  - "src/**/*SimplicialSet*.scala"
  - "src/**/homology/BettiNumbers*.scala"
---

# Simplicial sets (the Sage-parity layer) and group classifying spaces

Loads when you work in `cells/`, `groups/` or on a simplicial-set file. Project-wide rules are in `.claude/CLAUDE.md`.

## Simplicial sets

`WORKLOG-simplicial-sets.md`, `WORKLOG-simplicial-set-constructions.md`, `WORKLOG-simplicial-set-filtration.md`.

- Eilenberg–Zilber presentation: non-degenerate generators per dimension, plus per generator `faces: G =>
  IndexedSeq[SSetElement[G]]`. `SSetElement(word, target)`: degeneracy word in normal form is **strictly
  decreasing** (`s_0 s_0 = s_1 s_0` → `[1,0]`); `Nil` = bare generator. `insertOuter`/`faceOf` implement the
  simplicial identities on arbitrary elements; `validate()` checks `d_i d_j = d_{j-1} d_i` — necessary, not
  sufficient; verify intended topology via homology.
- `finiteSimplicialSetIsOrderedCell`: normalized chain complex boundary (bare faces only), depends on the set's
  own `faces` — thread explicitly, never an ambient global given.
- **`FiniteSimplicialSet[G]`'s `using Ordering[G]` clause comes AFTER its value parameters, not before**:
  `using`-first broke constructor call sites' type inference for `G` (silently unified with whatever `Ordering`
  was found first in scope) — safe only when `G` is already fixed some other way.
- `SimplicialSetStream`: constant-0 filtration, dimension-then-`Ordering[G]`. `FilteredSimplicialSetStream`: real
  `StratifiedCellStream[G, Double]`, same VR ordering convention. `fromStream` takes `CellStream[Simplex[VertexT],
  ?]` (VR coface streams aren't `SimplexStream`s).
- `product`: `(X×Y)_n = X_n × Y_n`, pair non-degenerate iff words' index sets are disjoint — **not** EZ shuffles;
  face maps strip the common degeneracy set and **relabel** survivors via rank, not delete. `coproduct`:
  `Left`/`Right` tags.
- `quotient(sset, quotientMap: G => SSetElement[G])` needs degenerate targets (RP² from a triangle collapses an
  edge to `s_0(v)`); must resolve in **one step** to fixed points (`require`d). `identify(pairs)` is the
  union-find ergonomic layer (own union-find in `cells`).
- **Sage-parity layer** (`DESIGN-sage-simplicial-sets-comparison.md`; pullbacks and Z-coefficients deliberately deferred):
  `trait SimplicialSet[G]` (lazy/infinite; `FiniteSimplicialSet` extends it; `.skeleton(n)` is correct only below degree n);
  `SimplicialSets` (`fromSimplicialComplex`, `simplex`, `point`, `empty`, `horn`, `kleinBottle`, `subcomplex`, `cone`, `suspension`,
  `wedge`, `fVector`, `isConnected`); `SSetMap` (validate/`andThen`/image/injective/surjective on SIMPLICES, `homologyRank`,
  `mappingCone`, projections); `FundamentalGroup.presentation` (spanning-tree `reduce` + relation `d_2·d_0 = d_1`; checked by
  Hurewicz against engine H_1); `CupProduct` (Alexander-Whitney, `cohomologyBasis`, `isCoboundary`); `algebra.LinearAlgebra`
  (dense, small complexes). Oracles must DISCRIMINATE: equal Betti numbers prove little (cup products told the torus from
  S^1∨S^1∨S^2 -- and caught that the old `torus` fixture WAS the latter: both triangles had faces (B,C,A); fixed, second is
  (A,C,B)); use F_2 AND F_3 (suspension of RP², Klein bottle (1,2,1)/(1,1,0)).
- **Second Sage batch** (`WORKLOG-sage-additions.md`): `SimplicialSets.presentationComplex` (inverse of `FundamentalGroup.presentation`;
  inverse edges + fan-triangulated relators), `smash` (product / wedge via `quotient`), `join` (`JoinGenerator` OfX/OfY/Both),
  `sphere(n)`, `complexProjectivePlane` (Sage's one-vertex model) and `complexProjectivePlaneKuhnel` (9 vertices), `hopfMap`
  (Sage's S³ model; checked by the mapping cone's cohomology RING having x² ≠ 0), and `Steenrod.sq` (F₂ only, Steenrod's
  cup-i formula, checked by Wu's formula on B(Z/2)). CP³/CP⁴ NOT available (Sage builds them from Kenzo data files). Sage sources
  were read through WebFetch (a small model summarises the page) — every transcribed data table is verified by `validate()`,
  Betti numbers AND cup products, never trusted.
- Fixtures (`SimplicialSetFixtures`): `minimalSphere(n)`, `realProjectiveSpace(2|3)` (sign discriminator F2 vs
  F3), `torus`, `triangle`/`realProjectiveSpaceViaQuotient`. No MATLAB/CLI entry (needs its own encoding design).
