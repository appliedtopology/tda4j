# WORKLOG: stream renaming (started 2026-10-02, after "I like the pitched names, go ahead")

Plan (advisor-reviewed): public objects with homological-degree `maxDimension` (`VietorisRips` done earlier, now `Truncated`,
`Cech`, `Witness(variant)`, `Dowker`, `DtmRips`, `SparseRips`), then docs/tutorial/TDAlab on the new names, then hide the
implementation classes `private[tda4j]`, then `StratifiedSimplexStream` -> `LevelwiseSimplexStream`. Commit per block.
- Deviations from the pitch: `Witness(..., variant = Lazy | General)` (`lazy` is a keyword); `private[tda4j]` not
  `private[streams]` (homology/matlab/tests use the classes); no `Cubical`/`Alpha` objects (`CubicalImage` and `AlphaShapes`
  already ARE the dispatching entry points); `CofaceSimplexStream` stays a hidden base (carries cache state), only the
  stratified trait gets the public name; MATLAB/CLI option strings (`sheehy-rips`, ...) untouched.
- Block 1 (additive): `streams/Complexes.scala` + `ComplexesSpec` (each object == the implementation class wrapped at k+1,
  cell-for-cell AND value-for-value). Passed first run.
