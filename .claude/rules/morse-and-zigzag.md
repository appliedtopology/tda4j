---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/homology/DiscreteMorse.scala"
  - "src/main/scala/org/appliedtopology/tda4j/cells/MorseCell.scala"
  - "src/test/scala/org/appliedtopology/tda4j/homology/DiscreteMorseSpec.scala"
  - "src/main/scala/org/appliedtopology/tda4j/homology/ZigzagPersistence.scala"
  - "src/test/scala/org/appliedtopology/tda4j/homology/ZigzagPersistenceSpec.scala"
---

# Discrete Morse theory (`homology/DiscreteMorse.scala`, `cells/MorseCell.scala`; `WORKLOG-more-inputs.md`, "Discrete Morse theory")

- **The matching** is per filtration value (a pair enters at one value), `Strategy.Reduction | Coreduction`, acyclic by construction. The
  gradient flow in `MorseComplex.flow` gives the Morse boundary and the lift together: never write the lift as `sum (-V∂)^n`, which loops
  on a paired face. A pair's incidence must be +-1 (a unit in every field).
- **Oracles.** The matching validator in the spec shares no code with the pairing; `(∂^M)^2 = 0`; `∂ lift = lift ∂^M` (the identity that
  catches a wrong lift); persistence of `morse.stream` = persistence of the original over `Z/2, Z/3, Z/5` and two engines, compared as bars
  of positive length (the Morse complex has dropped the zero-length pairs). VR fixtures are a `def` (cached layers, concurrent examples).
- **`MorseCell`** is `(cell)(complex)`: equality on the cell, ordering/boundary/dimension from the complex. A Morse stream can have a dimension
  with no cell under a dimension with cells; the cohomology engine allows that when the cells above have no boundary.
- Limits: no cocycle lift; the user's own matching is not checked for acyclicity.

# Zigzag persistence (`homology/ZigzagPersistence.scala`; `WORKLOG-more-inputs.md`, "Zigzag persistence")

- **The generator order is the algorithm.** The live generators are kept in an order in which a later one absorbs an earlier one: a
  class born by a forward arrow is appended, a class born in the kernel of a backward arrow is PREPENDED. Ordering by birth alone passes
  the Betti numbers and fails the mirror and rank oracles. A forward arrow reduces image coordinates in list order (the dependent ones
  die); a backward arrow echelonizes in the larger complex's generators with the latest in the list as the leading one, the non-pivot
  generators die.
- **Oracles** (`ZigzagOracle`, a separate `private object`: specs2 implicits break plain `Seq` arithmetic inside the Specification): Betti
  number at every position (Z/2, Z/3, Z/5); the generalized rank invariant (bars containing `[i, j]` = rank of limit -> colimit through ONE
  component, inclusion-exclusion gives the multiplicities; mapping a limit element to the colimit by the SUM of components is zero over Z/2
  and gave negative multiplicities); all-forward = `Persistence` on an `ExplicitStreamBuilder` with value = index; the mirror under
  reversal; `ofComplexes` = `apply`; a representative is a cycle at the birth, over Q and Z/101.
- A bar is `[b, d)` over positions `0..n`, death at infinity when alive at the end; only the dimensions `d`, `d-1` of the cells an arrow
  changes are recomputed; cells above `maxDimension + 1` are ignored; complexes must be face-closed (checked). The representative is at
  the birth, so `representativesDieWithTheirBars` is false.
- Limits: dense-ish sparse elimination per arrow (a few thousand cells in the changing dimensions); no cocycle representatives; no CLI.
