---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/homology/DiscreteMorse.scala"
  - "src/main/scala/org/appliedtopology/tda4j/cells/MorseCell.scala"
  - "src/test/scala/org/appliedtopology/tda4j/homology/DiscreteMorseSpec.scala"
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
