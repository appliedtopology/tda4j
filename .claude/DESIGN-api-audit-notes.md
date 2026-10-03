# API audit: running notes (raw material for DESIGN-api-audit.md)

Collected while doing the package flatten (2026-10-03). Unsorted observations; the audit document ranks them.

- `EuclideanMetricSpace(Seq(Array(...)))` is rejected: only `Array[Array[Double]]` and `Seq[Seq[Double]]` overloads.
- `VietorisRips(ms, maxDimension, maxFiltrationValue: Option[Double])`: users must write `Some(1.5)`; positional
  order is dimension-then-radius.
- Engines need explicit type arguments: `SimplicialHomologyEngine[Int, Double, Double]()`,
  `CellularHomologyEngine[G, F, Double]()`. Then `.persistentHomology(stream)` then `.barcodeAt(f)`/`.diagramAt(f)`:
  three nouns before the first answer.
- Simplicial sets: persistent homology needs `given (G is OrderedCell) = x.cellInstance` in scope BEFORE the engine is
  constructed (or `import x.given`).
- `PositiveInfinity()` with no expected type needs an explicit type argument (`PositiveInfinity[Double]()`).
- The single-import guarantee of `TDAlab` (re-exports) holds for TYPES only: top-level defs (`simplexIsOrderedCell`,
  `simplexOrdering`, `cubeIsOrderedCell`, `cubeOrdering`, `asSimplex`, `asCube`) need the package import (re-exported
  defs would be ambiguous for users who import both). Fix direction: move them into companions
  (`Simplex.isOrderedCell(...)`, `Simplex.ordering[V]`), which then ride along with the exported objects.
- Project lead: TDAlab may be opinionated, and several labs could exist for different exploration settings (the
  current one is simplicial with Int vertices and a `Simplex -> Chain` conversion; a cubical lab wants different
  conveniences). The generated re-export block is lab-agnostic, so a `trait Lab` carrying it plus per-lab
  conveniences is cheap.
- Default coefficient field: deliberately NOT added. A lone `Double is Field` in `object Field` (a typeclass
  companion = an implicit-scope anchor of every `?C is Field`) would silently decide `CoefficientT = Double` whenever a
  user forgot their `F_p` import -- real coefficients instead of F_p, wrong torsion answers, no compile error. Decision
  for the project lead; the rule adopted: defaults go in the companion of the DATA type, never of the typeclass.
- In a block, `given Double is Field = ...` AFTER statements that do implicit search fails: 'given instance given_is_Double_Field needs result type because its right-hand side attempts implicit search' (forward reference). Must come first, or be named with an explicit type.
