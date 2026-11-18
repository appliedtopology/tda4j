---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/homology/ParallelPackedCohomology.scala"
  - "src/main/scala/org/appliedtopology/tda4j/homology/ParallelRipser.scala"
  - "src/test/scalajvm/org/appliedtopology/tda4j/homology/ParallelEdgeTiesSpec.scala"
  - "src/test/scala/org/appliedtopology/tda4j/matlab/EdgeTiesFacadeSpec.scala"
---

# `edgeTies`: the order of equal-length edges in the Ripser engines

Derivation, the cross-check against ripser.cpp and Bauer's code, and the measurements: `WORKLOG-edge-ties.md`. Bauer's
claim, that the choice of the diametral edge among equal ones makes apparent pairs easier to find, holds in column counts
on tie-heavy input, is nil on generic input, and is within noise for `ParallelRipser`'s wall time.

- **What it is.** `ParallelPackedCohomology.EdgeTies` (`Persistence.EdgeTies`; the facades' `edgeTies`, the CLI's
  `--edge-ties`) orders the edges of one length, and a simplex is filed under the LAST of its longest edges in that order
  (its block). Every order gives the same bars; they differ in the pairs that are apparent and the zero-length pairs that form.
- **Who has an order.** `ParallelRipser` always ranks its edges (`EdgeRanking`; default `Lexicographic`, which is Bauer's
  `--ties lexicographic`). `Engine.Ripser` has none: asked, it runs `PackedRipserCohomologyEngine` on an
  `EdgeRankedMetricSpace` (rank + 1 as the distance, `limit` as `maxFiltrationValue`), reads its bars back in lengths with
  `lengthOf`, and drops zero-length bars made by ranks of one length unless `includeZeroLength`. Facades refuse the option
  unless `complex=vr` and the engine is `ripser`/`parallel-ripser`.
- **The residual order inside a block is the engine's own** (a larger combinatorial index is older) and cannot be set.
  `ReverseColexicographic` is therefore ripser.cpp's `EDGE_LEXICOGRAPHIC` exactly (pinned counts in `ParallelEdgeTiesSpec`);
  `Colexicographic` has the edge choice of Bauer's hybrid (largest index, `--ties increasing`) but not its direction
  inside a block. `Lexicographic` is the default and pairs worst with that direction on lattices. Moving the default is
  an open question (#348), not a rule; a settable direction inside a block is #349.
- **Counting.** ripser.cpp's `COUNT_WORK` counts a simplex in an apparent pair as apparent whether it is a column or a
  row; `ParallelRipser` counts columns only, and `cleared` is every pivot of the degree below. Compare after converting.
  The sequential engine's `apparentPairCount` is the sum of `ParallelRipser`'s `apparent` over degrees 1 and up, order for
  order (`ParallelEdgeTiesSpec`). Time claims need the A/B discipline of `CLAUDE.md`: `ParallelRipser`'s wall time was flat
  across orders; only the sequential engine moved (16-21% on a 32 x 32 lattice).
