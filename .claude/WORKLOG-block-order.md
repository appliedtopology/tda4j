# The direction inside a block: `blockOrder`, and counting the work in the benchmark (2026-11-18)

Point-in-time snapshot, same thread and PR as `WORKLOG-edge-ties.md` (which stays as written: its "Not reproduced" is what this
closes). Reference code: `github.com/ubauer/ripser-dev` at 857c9d0 (`ORDER_CONVENTIONS.md`, `hybrid/pruned.cpp`,
`ripser-forest/ripser-forest.cpp`, `ripser.cpp`, `hybrid/profile.py`). Issue #349 (the direction), #348 (the default).

## What a block is, and what the option changes

Simplices order by diameter, then dimension, then combinatorial index (CNS). `ParallelPackedCohomology` files a simplex under
its last longest edge in the chosen edge order (its block) and keys it by block, then an index part; a smaller key is older,
and the pivot of a column is its smallest-key cofacet. Edge ranks are unique, so a block holds one edge (dimension 1) or
simplices of dimension 2 and up (vertices, block 0, also share). Within a block the key's index part is `mask - index`
(`LargerIndexFirst`: larger index older, Ripser's convention) or `index` (`SmallerIndexFirst`: Bauer's hybrid, per
`ORDER_CONVENTIONS.md`). In code: `Space.key(block, index) = (block << bits) | (index ^ flip)`, `flip = mask` or `0`; the wide
layout carries the block beside the key with the same `^ flip`.

An apparent pair (column `s`, cofacet `t`): `t` is the oldest cofacet of `s` in `s`'s own block, and `s` is the youngest facet of
`t` in that block. For v < v', `s ∪ {v}` has the smaller index than `s ∪ {v'}`, and the facet that drops the smaller vertex has
the larger index. So the mirror image is exact, vertex for vertex:

| | `LargerIndexFirst` | `SmallerIndexFirst` |
|---|---|---|
| oldest same-block cofacet adds | the largest qualifying vertex | the smallest |
| youngest same-block facet drops | the largest vertex outside the block edge | the smallest |
| the column is apparent iff | the added vertex > the column's largest outside vertex | the added vertex < its smallest |
| H0 elder rule (union-find) | the smaller root survives | the larger survives |

Implemented as `apparentCofacetLarger`/`apparentFacetLarger` (the old bodies) and `apparentCofacetSmaller`/`apparentFacetSmaller`
(ascending cursors `startUp`/`rankUp` in `EdgeRanking`, `iB`/`iA` bookkeeping ascending), dispatched on `space.smallerFirst`;
`youngestFacetByPairs` and `oldestCofacetVertexByScan` scan up or down the same way. Threaded through `ParallelRipser.run`,
`Persistence` (`blockOrder: Optional[BlockOrder]`, alias `Persistence.BlockOrder`; `Auto` takes `ParallelRipser` for
`SmallerIndexFirst` whatever the thread count, `Engine.Ripser` refuses), the facade (`blockOrder`), the CLI (`--block-order`),
`npm/index.d.ts`. `Engine.Ripser` has no block order: it is `PackedRipserCohomologyEngine`, whose order is the engine's.

## Checks

- `ParallelBlockOrderSpec` (JVM, 11 examples): a hand-derived order of five simplices, vertex keys, key round trip, bars equal
  to the packed Ripser engine's, every apparent pair against the definition by brute force (all simplices, all orders), the
  plain coboundary reduction (no apparent shortcut) giving the same pairs as the apparent-pair run, parallel/sequential/
  `ripserApparent`/two-word keys/sparse ranks equal, cocycles valid, the two directions pairing differently on a lattice.
- Pinned against builds of ripser-dev at 857c9d0 (`-DCOUNT_WORK`): ripser-forest's `columns` per `--ties` on the 12 x 12 lattice
  (`--dim 2 --threshold 10`, squared distances: increasing 121, decreasing 221, lexicographic 121) and the 4 x 4 x 4 lattice
  (`--dim 3 --threshold 2`: 108, 162, 108) equal TDA4j's columns left to reduce for `Colexicographic`,
  `ReverseColexicographic`, `Lexicographic` with `SmallerIndexFirst`. Bauer's identity (Lexicographic + SmallerIndexFirst equals
  ReverseColexicographic + LargerIndexFirst on reversed labels) holds in the pairing. `Shuffled` is the hybrid's `--ties random`
  (splitmix64 of the CNS index, from its source); ripser-forest's `random` hashes `hi << 16 | lo` instead and is not pinned.
- Mutation check (`mut/run.py`, a flip per rule in `ParallelPackedCohomology.scala`): the first spec let one mutant live (the
  column's largest instead of smallest outside vertex, which only matters in degree 3 and up on non-cleared columns); inputs
  were added (equilateral 8 degree 3, equilateral 7 degree 4, an integer metric 9 points degree 3, a 4 x 4 lattice degree 3) and
  every mutant dies.
- `BlockOrderFacadeSpec` (7 examples, both platforms), `ParallelRipserEngineSpec` and `CLISpec` additions.
- Not run: MATLAB marshalling, MiMa (a fresh 0.7.0 series has no baseline), the hybrid's own engine (read, not run).

## Work counts: what compares to what

`bench/count_work.py` (`run.sh count-work`) records, per tool, per degree from 1, and in total: `simplices` (the columns of the
degree), `reduced` (through the reduction), `skipped` (`simplices - reduced`), `additions`. Definitions per tool:

- ripser.cpp `COUNT_WORK` (stderr `work in dim d: ...`): `reduced = columns`, `skipped = simplices - columns` (its `simplices`
  includes the H0 spanning-tree edges in degree 1), `additions = additions + apparent_additions`. Its `apparent` counts a simplex
  in an apparent pair as apparent whether it is the column or the row, and its `cleared` only the pivots that are not apparent:
  so `apparent`/`cleared` are not TDA4j's and are kept raw only.
- TDA4j `Stats` (`PaperBenchmarkDriver work=true`): `reduced = columns - cleared - apparent`, `skipped = cleared + apparent`,
  `additions = adds1 + adds2a + adds2b`; `cleared` includes the H0 pivots in degree 1.
- ripser-forest (`WORK {json}`, whole run): `columns` is `reduced`; its other counters (`lower_skipped`, `streams`, ...) are cofacet
  scans and the like, not columns. RedZeD hybrid (`hybrid/profile.py`'s counters): `cells`, `lower_pairs`, `cleared_cells`,
  `xor_calls`, ... are another kind of work; raw only.
- Check: on the 12 x 12 lattice ripser.cpp edge-lex and TDA4j `ReverseColexicographic`/`LargerIndexFirst` agree degree by degree
  (degree 1: 1998 simplices, 121 reduced, 0 additions; degree 2: 11568, 0, 0); ripser.cpp's upstream order has 571 reduced and
  671 additions in degree 2, which no TDA4j order here reproduces (`Engine.Ripser` has no counters of this kind). Pinned in
  `bench/test_bench.py` (`WorkCountTests`), with the three counting-build parsers on fake binaries.
- Bars by degree are recorded per row, and `work.md` says when a case's rows disagree: a counting build that finds other bars did
  not count the same computation.

Counting builds (`run.sh setup`): the same compile as the timed build plus `-D COUNT_WORK` (`<name>-count`, `-coeff-count`), only
where the source has the counters; the hybrid's source is made by `_COUNT_PREP` (`python3 -I hybrid/profile.py`) and named by
`_COUNT_SRC`. Exercised on a local clone of ripser-dev: `forest`, `edge` (both builds) and `hybrid` built and ran; `blocks` has no
counters in its source and says so. The tie-heavy inputs are two new `experiment` cases (left out of runs and of `count-cells`
unless named): `lattice32` (32 x 32 integer lattice, degree 3, threshold 5.1, Bauer's `grid32 H3 t5.1`) and `equilateral60`.

## Measurements (this container, one thread, counts only: no timing)

`count_work.py` on `lattice32` (6,263,334 simplices through degree 3; every row finds the bars 1024 / 961). 961 is the number of
H1 classes: the fewest columns any order can leave to reduce is 961.

| edge order | larger index older: reduced / additions | smaller index older: reduced / additions |
|---|---|---|
| lexicographic (the default) | 2,761 / 961 | 961 / 0 |
| reverse-lexicographic | 961 / 0 | 961 / 961 |
| colexicographic | 961 / 961 | 961 / 0 |
| reverse-colexicographic | 961 / 0 | 2,761 / 961 |
| shuffled | 1,332 / 2,028 | 1,387 / 2,039 |

Other builds, same input: ripser.cpp edge-lexicographic 961 / 0 (identical to TDA4j's reverse-colexicographic with the larger index
older), ripser.cpp in its upstream order 271,391 / 282,825, ripser-forest 961 reduced, RedZeD hybrid `xor_calls` 961 and
`lower_pairs` 36,924 (raw). `equilateral60` (523,625 simplices): every structured order and both directions leave 0 columns and
0 additions; shuffled leaves 6,762 / 29,892 (larger first) and 7,565 / 26,219 (smaller first).

Reading: on the lattice the default (lexicographic, larger index older) leaves 2,761 columns against the floor of 961, as does its
mirror (reverse-colexicographic, smaller index older); the other structured pairs reach the floor, five of them with fewer
additions than the default, and which pairs do depends on the combination, not on either option alone (lexicographic is worst with the
larger index older and best with the smaller). That answers the claim; it does not
settle the default (#348), which needs server timings on cases with and without ties: the counts above are work, not time, and the
earlier finding stands that `ParallelRipser`'s wall time was flat across edge orders at the sizes tried.

## Open

- #348: the default of `edgeTies` and `blockOrder` for `ParallelRipser` and the question of edge-rank blocks for `Engine.Ripser`.
- #351: the sequential engine has no block order (it is `LargerIndexFirst`; `Persistence` refuses the other there).
- #352: the hybrid's counters are raw only (no column-reduction reading), and `count-work` has not been run on the compute server.
- No timing of any order on a server: the counts above are work, and the container's wall times were flat across orders.
