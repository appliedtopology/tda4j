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
- Block 2: user-guide fences/prose, TDAlab `streams` exports, architecture.md note moved to the public objects (3.8.4 doc build
  clean). Developer-guide prose still names the implementation classes on purpose (they describe internals).
- Block 3: classes `private[tda4j]` (compiles, docs fences prove nothing public names them); `Truncated` generalized (own
  wrapper class) so its signature mentions no hidden type; `Truncated.ofCofaces` internal.
- Block 4: `StratifiedSimplexStream` -> `LevelwiseSimplexStream` (word-boundary perl over src/_docs/CLAUDE.md; worklogs untouched).
