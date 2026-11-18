# Which of equal-length edges files a simplex: `edgeTies` (2026-11-18)

Point-in-time snapshot, thread "Make the diametral-edge tie-break an option" (Mikael's relay of Ulrich Bauer's claim).
Reference code: `github.com/ubauer/ripser-dev`, branch `optimize/hybrid-cohomology`, commit 857c9d0 (`hybrid/pruned.cpp`,
`ripser.cpp`, `EDGE_LEXICOGRAPHIC.md`, `ORDER_CONVENTIONS.md`).

## The claim

Bauer: the tie-break for the diametral edge (of the simplex's longest edges, the one that decides its "block") is by
largest index in his hybrid and by smallest index in ours, and the choice makes apparent pairs easier and faster to find.

## What each implementation does (read from code, then checked by counts)

An edge is `{a, b}`, `a < b`, combinatorial index `C(b,2) + a`; a larger simplex index is older in every engine here.

| implementation | order of equal-length edges | the simplex's block edge is | order inside a block |
|---|---|---|---|
| `hybrid/pruned.cpp`, `--ties increasing` (its default) | increasing index (colexicographic) | the largest-index longest edge | smaller index older (the hybrid's own convention) |
| `hybrid/pruned.cpp` `--ties decreasing`, `ripser.cpp` `-DEDGE_LEXICOGRAPHIC` | decreasing index ("Ripser's edge order" in the hybrid's words) | the smallest-index longest edge | larger index older |
| `hybrid/pruned.cpp` `--ties lexicographic` / `random` | by smaller then larger vertex / hash of the index | last in that order | as above |
| tda4j `ParallelRipser` before this change | by smaller then larger end (lexicographic), fixed | the lexicographically largest longest edge | larger index older |
| tda4j `Engine.Ripser` (`PackedRipserCohomologyEngine`) before this change | none: diameter, then index | n/a (no block) | larger index older |
| tda4j cohomology engines on streams | the stream's own tie-break between simplices, colexicographic since #198 (`WORKLOG-colex-tie-break.md`) | n/a | n/a |

So Bauer's reading is right: our parallel engine's block edge was the lexicographically largest, and the sequential
engine had no edge order at all. Which edge a simplex is filed under is the only thing the edge order changes; the apparent
pair test from the block's edge is valid for any strict order of the edges (youngest same-block facet drops the largest
vertex outside the edge; oldest same-block cofacet adds the largest qualifying vertex above the largest residual vertex).

## What was built

`ParallelPackedCohomology.EdgeTies` (`Lexicographic` = default, `ReverseLexicographic`, `Colexicographic`,
`ReverseColexicographic`, `Shuffled`), threaded through `EdgeRanking`/`Space`, `ParallelRipser.run`, `Persistence`
(`edgeTies: Optional[EdgeTies]`, alias `Persistence.EdgeTies`), the MATLAB facade (`edgeTies`, in `recognizedKeys` only),
the CLI (`--edge-ties`), `npm/index.d.ts`. `Engine.Ripser` takes it through `EdgeRankedMetricSpace`: the sequential packed
engine run on edge RANKS in place of lengths (which is how ripser.cpp computes `EDGE_LEXICOGRAPHIC`), bars read back in
lengths. Unset: nothing changes (ParallelRipser `Lexicographic`, Ripser no edge order). Facades refuse the option for any
other complex or engine. Python needs nothing (options pass through as `edge_ties`).

**The residual order inside a block is not settable.** Both engines read a larger combinatorial index as older. The hybrid
reads it as younger. `Colexicographic` therefore has the hybrid's EDGE choice, not its direction inside a block, so it is
not a reproduction of the hybrid's default. `ReverseColexicographic` IS a reproduction of `ripser.cpp`'s edge-lex (below).

## Cross-checks

- `ParallelEdgeTiesSpec` pins the work counts of a ripser.cpp build with `-DEDGE_LEXICOGRAPHIC -DCOUNT_WORK` on the
  12 x 12 lattice (`--dim 2 --threshold 10`, squared distances): ripser.cpp prints 1734 apparent in degree 1 and 11447 in
  degree 2; `ReverseColexicographic` has 1734 apparent columns and 9713 in degree 2, 1734 + 121 of the 11568 triangles
  cleared, none left to reduce. 11447 = 9713 columns with an apparent cofacet + the 1734 triangles that are the apparent
  cofacet of an edge. Upstream Ripser's order (diameter, then index) has 10876 simplices in apparent pairs there, 571
  columns in degree 2.
- Accounting differs and must be converted: ripser.cpp's `COUNT_WORK` counts a simplex in an apparent pair as apparent
  whether it is the column or the row (first attempt: 11447 vs 9713, the whole discrepancy); `ParallelRipser` counts columns
  only; its `cleared` counts every pivot of the degree below. Its additions are counted per added column, the sequential
  engine's `substitutionCount` per rebuilt apparent column: not comparable (the assertion was dropped).
- The sequential engine on `EdgeRankedMetricSpace` gives the same bars (in lengths) and, order for order, an
  `apparentPairCount` equal to `ParallelRipser`'s `apparent` summed over degrees 1 and up.
- The sequential engine on lengths on the 32 x 32 lattice, H3, reproduces upstream Ripser's diam-lex count of column
  additions (282,825).
- Bars are identical across all five orders in the specs (`ParallelEdgeTiesSpec`, `ParallelRipserEngineSpec`,
  `EdgeTiesFacadeSpec`: F17, F2 and F3, cycles and cocycles, one and several threads, one-word and two-word keys, dense
  and sparse ranks) and in the driver runs below (the digests of the bars agree on the 32 x 32 and 10 x 10 x 10 lattices
  and the equilateral points).

## Measurements (one machine, three cores, noise high; not A/B-grade for time)

`ParallelPackedCohomologyDriver ... ties=<order>`, one thread, H<=2 unless said:

| input | order | columns left to reduce (deg 1 / 2 / 3) | additions |
|---|---|---|---|
| 32 x 32 lattice, H<=2 | lexicographic | 961 / 900 | 961 |
| | reverse-lexicographic | 961 / 0 | 0 |
| | colexicographic | 961 / 0 | 961 |
| | reverse-colexicographic | 961 / 0 | 0 |
| | shuffled | 961 / 150 | 2,028 |
| 32 x 32 lattice, H<=3 | lexicographic | 961 / 900 / 900 | 961 |
| | reverse-colexicographic | 961 / 0 / 0 | 0 |
| 10 x 10 x 10 lattice, H<=3 | lexicographic | 1701 / 1458 / 729 | 3,888 |
| | reverse-lexicographic | 1701 / 0 / 729 | 0 |
| | colexicographic | 1701 / 729 / 729 | 3,159 |
| | reverse-colexicographic | 1701 / 0 / 729 | 0 |
| | shuffled | 1701 / 1083 / 729 | 11,235 |
| 60 equilateral points, H<=3 | the four structured orders | 0 / 0 / 0 | 0 |
| | shuffled | 147 / 921 / 5,694 | 31,039 |

Degree 1 leaves 961 (32 x 32) or 1701 (10 x 10 x 10) columns to reduce in every order.

Wall time, `ParallelRipser`, one thread, 32 x 32 lattice H<=2: lexicographic 756 ms, reverse-lexicographic 747,
colexicographic 799, reverse-colexicographic 912 (min 705), shuffled 835: noise exceeds the effect. H<=3, one trial each:
7,512 ms against 7,344 ms. 10 x 10 x 10 lattice and equilateral: the same, within noise, except `shuffled` on 60 equilateral
points, 5 to 10 times slower (1,706 ms against 255 to 332 ms). Random points (no ties): the orders do not differ.

The SEQUENTIAL engine moves: 32 x 32 lattice, H<=3, three trials each: on lengths 10.5 to 10.9 s median with 282,825
substitutions and 5,381,544 apparent pairs; on reverse-colexicographic ranks 8.3 to 9.2 s with 0 substitutions and
5,651,974 apparent pairs, 16 to 21 % faster. H<=2: 962 ms against 810 ms. On `lexicographic` ranks, H<=2: 944 ms, no gain.

## Verdict on the claim

True where distances tie, and then only for work that is actually avoided: the reverse orders leave no column additions on
the lattices, and the default `Lexicographic` is the worst of the structured orders there (it pairs worst with the
larger-index-older direction inside a block). It is nil on generic points. In `ParallelRipser`'s wall time it is not
visible at these sizes, because the reduction left over is a few hundred columns out of millions; the sequential engine
gained because it did substitutions it no longer needs. Nothing here shows the effect at larger sizes or on a
many-core server.

## Not checked

- Scala.js: the facade spec is shared and was run there (see the PR); `ParallelEdgeTiesSpec` is JVM only.
- MiMa: a fresh 0.7.0 series, no baseline, so not run.
- No timing on the compute server; the numbers above are from a three-core cloud container.
- The hybrid's own engine (pruned.cpp) was read, not run.

## Follow-ups

- #348: whether `ParallelRipser`'s default should move from `Lexicographic` to `ReverseColexicographic`, and whether
  `Engine.Ripser` should move onto edge-rank blocks (the question of #223); both need server timings.
- #349: a settable direction inside a block, to reproduce the hybrid's default (`--ties increasing`) exactly.
- Benchmark rows for the orders on the server: the forked-build rows of PR #328 already carry ripser.cpp edge-lex and
  `ripser-forest`; tda4j rows with `edgeTies` are not added in this change (`workers/tda4j_js_worker.mjs` and `PaperBenchmarkDriver` do not pass the option).
