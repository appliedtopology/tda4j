---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/homology/ParallelPackedCohomology.scala"
  - "src/main/scala/org/appliedtopology/tda4j/homology/ParallelRipser.scala"
  - "src/main/scala/org/appliedtopology/tda4j/homology/ForestColumns.scala"
  - "src/main/scala/org/appliedtopology/tda4j/homology/CofacetStreams.scala"
  - "src/test/scalajvm/org/appliedtopology/tda4j/homology/ParallelForestColumnsSpec.scala"
  - "src/test/scalajvm/org/appliedtopology/tda4j/homology/ParallelCofacetStreamsSpec.scala"
  - "src/test/scalajvm/org/appliedtopology/tda4j/homology/ParallelEdgeTiesSpec.scala"
  - "src/test/scalajvm/org/appliedtopology/tda4j/homology/ParallelBlockOrderSpec.scala"
  - "src/test/scala/org/appliedtopology/tda4j/matlab/EdgeTiesFacadeSpec.scala"
  - "src/test/scala/org/appliedtopology/tda4j/matlab/BlockOrderFacadeSpec.scala"
  - "bench/count_work.py"
---

# `edgeTies` and `blockOrder`: the order of equal-length edges, and of a block, in the Ripser engines

Derivation, the cross-check against ripser.cpp and Bauer's code, and the measurements: `WORKLOG-edge-ties.md` (edges) and
`WORKLOG-block-order.md` (the direction inside a block, the work counts of the benchmark). Bauer's
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
- **`blockOrder` is the direction inside a block** (`ParallelPackedCohomology.BlockOrder`, `Persistence.BlockOrder`; the facades'
  `blockOrder`, the CLI's `--block-order`): `LargerIndexFirst` (default, Ripser's convention: the larger combinatorial index is
  older) or `SmallerIndexFirst` (Bauer's hybrid). Only `ParallelRipser` reads it; `Auto` goes there for `SmallerIndexFirst` on
  any thread count, `Engine.Ripser`/`engine=ripser` refuse it. The two apparent-pair rules are mirror images (smallest
  vertices instead of largest, the H0 elder rule flips); a change to one is mirrored in the other and the brute-force spec
  (`ParallelBlockOrderSpec`) is mutation-checked: it needs inputs of degree 3 and up to catch a wrong "outside vertex".
- **Which pair is which tool**: `ReverseColexicographic` + `LargerIndexFirst` = ripser.cpp `EDGE_LEXICOGRAPHIC` (pinned counts);
  `Colexicographic` / `ReverseColexicographic` / `Lexicographic` + `SmallerIndexFirst` = ripser-forest `--ties increasing` /
  `decreasing` / `lexicographic` (columns reduced pinned on two lattices); `Shuffled` = the hybrid's `--ties random`. The
  hybrid's engine was read, not run. `Lexicographic` + `LargerIndexFirst` is the default and, with its mirror, the worst
  pair on lattices (`WORKLOG-block-order.md`); moving it is #348, not a rule.
- **Counting.** ripser.cpp's `COUNT_WORK` counts a simplex in an apparent pair as apparent whether it is a column or a
  row; `ParallelRipser` counts columns only, and `cleared` is every pivot of the degree below. Compare after converting.
  The sequential engine's `apparentPairCount` is the sum of `ParallelRipser`'s `apparent` over degrees 1 and up, order for
  order (`ParallelEdgeTiesSpec`). Time claims need the A/B discipline of `CLAUDE.md`: `ParallelRipser`'s wall time was flat
  across orders; only the sequential engine moved (16-21% on a 32 x 32 lattice).
- **The benchmark counts work, apart from timing** (`bench/count_work.py`, `run.sh count-work`, `PaperBenchmarkDriver work=true`;
  `bench/README.md`, "Counting the work"): the fields compared across tools are `simplices`, `reduced`, `skipped` and `additions`,
  defined per tool in the worklog; ripser-forest's and the hybrid's own counters are raw (forest's `columns` is its `reduced`).
  The tie-heavy inputs are `experiment` cases (`lattice32`, `equilateral60`): the paper's cases have no equal distances to show
  anything. A change to `Stats` or to a counting build's output format updates the parsers and `WorkCountTests`.
- **Columns by the maximal-facet forest, working columns as cofacet streams** (`ForestColumns`, `CofacetStreams.scala`; the edge-block paper's
  forest and stream results, `WORKLOG-paper-optimizations.md`). `Space.forestColumns` finds the columns of a degree without listing
  its simplices: the leaves of the forest that are not minimal children, in one depth-first pass per step over an adjacency bit
  matrix that grows with the edges (vertex order: label order for `SmallerIndexFirst`, reversed for `LargerIndexFirst`). It applies
  with one-word keys, dense ranks and apparent pairs from the block's edge, and not when the run keeps its pairs (cycles need the
  zero-length apparent pairs as pivots). `Space.streams` keeps a working column as lazy `CofacetStream`s in a heap (the cofacets of
  a column in filtration order, by walking the neighbour lists sorted by edge rank), so a reduced column is stored as its `V`
  (`Owner.vKeys`, `lead`), never as `R`: read `R` as the coboundary of the cocycle (`coboundaryOf`), or from a `streams = false` run.
  The reduction is term for term the heap run's (pairs, logs, additions, cocycles: `ParallelCofacetStreamsSpec`, mutation-checked;
  two mutants survive and are equivalent: the bound-before-cofacet tie-break and `<` for `<=` in the `pp` walk). Both default on.
  The edge ranking sorts by length with a primitive stable merge after arranging the ties (`EdgeTies.sortByLength`).
