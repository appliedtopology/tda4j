---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/package.scala"
  - ".claude/scripts/tdalab-exports.py"
  - "src/test/**/*Lab*.scala"
  - "src/test/scala/tda4juser/**"
---

# Labs (`TDAlab`, `CubicalLab`) and their re-exports

Loads when you work on `package.scala`, the re-export script or the lab specs. Project-wide rules are in `.claude/CLAUDE.md`.

`abstract class Lab(characteristic, precision = 1e-9)` (root `package.scala`) carries what every lab shares
(coefficients via `Coefficients`, `Fp`, the re-exports, `.show`); `TDAlab` (simplicial) and `CubicalLab` extend it,
with prebuilt objects `TDAlab.F2`/`F3`/`F17`/`Reals` (likewise `CubicalLab`): `import TDAlab.F17.{*, given}` must be the
ONLY library import a lab user needs. It brings `CoefficientT`, `Fp(...)`, the
field's given, chain arithmetic (`⊠`, `+`, `-`) on `Chain[Simplex[Int], CoefficientT]`, a `Simplex -> Chain` conversion,
Cats `.show` syntax, and flat re-exports of every public top-level class/trait/object/type/enum of the core and the
`sset` add-on, plus the `∆` val. The re-export block is GENERATED (`.claude/scripts/tdalab-exports.py`, between `BEGIN/END
generated re-exports` markers) and guarded by `TDAlabExportsSpec` -- rerun the script after adding a public type. Only
types and val aliases are re-exported (a re-exported def is ambiguous for users who import both). Hence `∆`
is `val ∆ : Simplex.type = Simplex`, and top-level defs have companion spellings that ride along with the re-exported
objects (`Simplex.fromSortedSet`/`ordering`/`isOrderedCell`, `Cube.fromVector`/`ordering`/`isOrderedCell`). No namespace objects (`tdalab.streams.X` is gone) and no given re-exports (defaults
come from companions). `characteristic = 0` means `Double`, a prime `p` `Z/p`. `TDAlab` fixes `Int` vertices (opinionated by design). A lab is never consulted by an engine (no
`TDAContext`-style context classes). Cats (`cats-core`,
`kittens`) is a dependency for `Show`; `Chain` is declared `into class` (needs `-preview`; `// format: off` around it
because scalafmt can't parse `into`) and implicit conversions are enabled in-source, not by a flag.
