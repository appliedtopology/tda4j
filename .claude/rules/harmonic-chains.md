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
- **Limits**: memory is the orthogonal cycle basis of all cycles of a degree (a down-Laplacian solve is an untested
  alternative), cost cubic in the cells in the worst case; cycles only (no cocycle variant); the Gülen-Mémoli-Wan poset
  construction is not built.
