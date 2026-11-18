---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/homology/Harmonic*.scala"
  - "src/test/scala/org/appliedtopology/tda4j/homology/Harmonic*.scala"
---

# Harmonic chains: `HarmonicChainBarcode`, `HarmonicRepresentatives`

Derivation, probes and decisions: `WORKLOG-harmonic-chains.md`. Developer's Guide: `persistence-engines.md`.

- **Characteristic 0 only** (reals with a tolerance, or `Coefficients.Rationals`): the dot product over `Z/p` is not
  positive definite (a chain can be orthogonal to itself), so Gram-Schmidt divides by zero and `Z ∩ B⊥` can exceed the
  Betti number. Refused in `zeroCharacteristic`. A large prime was a modular shortcut in probes (matched Q, loud failures);
  offering it is the project lead's decision (issue).
- **`HarmonicChainBarcode`** (Hou-Parsa-Wang Algorithm 1) follows the harmonic chains along ONE insertion order (value,
  dimension, stream index): bars alive = Betti number, births and deaths = the ordinary barcode's, only re-paired,
  every chain a cycle orthogonal to all boundaries over its bar (Definition 11). It depends on the order of tied cells; on
  Vietoris-Rips it cuts a long bar at each value where a triangle enters with its longest edge. **For VR use
  `HarmonicRepresentatives`** (Basu-Cox: the ordinary bars, the harmonic cycle at the birth, independent of ties; no
  zero-length bars, they are not classes of the module).
- **Nothing that changes the complex carries over**: harmonic chains depend on the inner product of the cells, so Morse
  reduction and edge collapse change the barcode (probe: the Morse-reduced harmonic barcode was the ordinary one, not the
  full harmonic one). Clearing and apparent pairs give each cell's sign only, not the pairing the harmonic rule picks.
- **Oracles** (no shared code): the zigzag of harmonic chain groups by `dim ∩ V_k` over exact Q; Definition 11 per bar; the
  paper's Figure 1 by hand; Basu-Cox Definition 3.10 in chain coordinates over Q. The Gram matrix in `complement` is
  load-bearing with tied values (the tie-heavy spec kills the identity-Gram mutant).
- **Facade pairing**: `fromBars` reads a chain as `chain.terms` (cell order). `cycleVertices(i)` and `cycleCoefficients(i)`
  are two provider calls: `collapseAll` + `rawEntries` twice gave a heap order that differed between the calls and put a
  coefficient on another cell's vertices (`RepresentativePairingSpec`).
- **Cocycles** (`HarmonicRepresentatives(.., representatives = Representatives.Cocycles)`, #307): cochains and chains share the
  orthonormal coordinates, so the harmonic cochains are the harmonic chains; the module maps of cohomology are the orthogonal
  projections to an EARLIER level, so a bar `[s, t)` is born at the last value before `t` and Basu-Cox runs with the levels
  reversed. The diagram's marker records the kind. The harmonic chain barcode stays cycles only. A zero-length bar is
  refused (`ofDiagram`): it is not a class of the module.
- **`Orthogonalization`** (#305): `GramSchmidt` (default) keeps an orthogonal basis of all the cycles of a degree;
  `LaplacianSolve` keeps the boundaries and solves `(d d^T) y = -d e_sigma` for `z = e_sigma + d^T y` by conjugate gradient.
  Over `Q` the two are equal term for term (`HarmonicOrthogonalizationSpec`); over `R` every decision is `isEqual(x, 0)` at an
  absolute `1e-9`, so on large near-tie clouds the two can PAIR a few bars differently (on the one 70-point cloud with an exact reference: 42 listing lines off for Gram-Schmidt, 5 for the solve; `WORKLOG-harmonic-solve-cocycles.md`, #353). The solve's
  residual has to be zero at 2^-10 of that tolerance, else the next test answers differently; it stops with an error that
  names the alternatives. Measurements, and the exact reference that arbitrates: `WORKLOG-harmonic-solve-cocycles.md`.
  Facade key `orthogonalization` (`gram-schmidt`, `laplacian-solve`), CLI `--orthogonalization`.
- **Limits**: cost is cubic in the cells in the worst case; the memory is the orthogonal cycle basis of all cycles of a degree
  (`GramSchmidt`) or the boundaries of the degree (`LaplacianSolve`); the harmonic chain barcode is cycles only; a finite
  field is refused. The persistent Laplacian of a simplicial map is `PersistentLaplacian.ofMap` (`rules/module-maps.md`); the
  Gülen-Mémoli-Wan poset-filtration Laplacian is not built.
