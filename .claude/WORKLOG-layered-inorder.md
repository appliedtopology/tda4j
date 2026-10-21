# WORKLOG: layered in-order Vietoris-Rips generation (2026-10-21)

Project lead's research idea: generate the VR complex layer by layer already in filtration order (Bauer, Matuszewski,
Vejdemo-Johansson, "Generating cofaces in Vietoris-Rips filtration order", arXiv:2411.05495), keeping just enough
metadata per d-simplex (sorted neighbour lists and pointers into them, keyed by the first longest edge) to step up to
d+1. Asked: feasibility, speed, memory, an honest verdict, and other uses of the three-case theorem.

## The paper, as far as this work uses it

- Theorem 3: the lexicographically first full-diameter facet of `τ = [v_d > ... > v_0]` is `τ - v_d` if that keeps the
  diameter; else `τ - v_{d-1}` if every full-length edge contains `v_d` and that facet keeps it; else (only `v_d v_{d-1}`
  is full length) `τ - v_{d-2}`. Read as generation (Remark 6): `σ` adds `v > v_d` (all distances `<= α`), or, if every
  full-length edge contains `v_d`, `v_d > v > v_{d-1}` (`<= α` to `v_d`, `< α` to the rest), or, if `v_d v_{d-1}` is
  the only full-length edge, `v_{d-1} > v > v_{d-2}` (all `< α`).
- Proposition 7: track the lexicographically first full-length edge `[s, t]`; `s = v_d` decides case 2, `s = v_d, t =
  v_{d-1}` case 3, and every generated cofacet inherits `[s, t]`.
- Section 2.4 is the depth-first (stack) stream, Section 2.5 coboundaries in filtration order from distance-sorted
  neighbour lists (Dory). Their Ripser experiment: in-order 7–50% slower, 7–65% less memory.

## What the library already had

- `InorderCofaceSimplexStream` (`SimplexStream.scala`): Section 2.2 mirrored (smallest vertex first), driven by the
  sorted previous dimension; scans all `n` vertices per simplex. Two defects, found here (#182): ties come out in
  generation order, not `filtrationOrdering.reverse` (`respectsOrderingContract` false even on a random cloud), and
  on tied distances case 2 accepts a new vertex at distance exactly `α` from a vertex other than the first, so a
  simplex is also generated from another facet: 3×3×2 grid, 240 triangles listed (208 distinct), 348 tetrahedra (276).
  `CofaceSimplexStreamSpec` compares it by size on random clouds only.
- `RecursiveStackVietorisRipsSimplexStream` + `TopCofacetEnumerator` (`VietorisRips.scala`): Section 2.4's stack,
  re-sorted per dimension anyway; not offered by the dispatcher (times out past toy sizes).

## Design

**Mirror the paper** (vertices ascending, the smallest plays the paper's largest): then the first full-length edge is
`a < b` with the largest `a`, then the largest `b`; case 1 adds `w < u0`, case 2 (`a = u0`) `u0 < w < u1`, case 3
(`a = u0, b = u1`) `u1 < w < u2` (any `w > u1` for an edge).

**Why mirror: ties for free.** In general position (distinct distances) a simplex's only full-length edge is its root
`e`, so the simplices of diameter `|e|` are exactly `e ∪ W`, `W` a clique of the lens graph, and the generating facet of
`e ∪ W` is `e ∪ (W - min W)` in every case. Emitting candidates largest first, dimension `d + 1` comes out ordered by
(parent's position, new vertex descending) = lexicographic on `W` sorted descending = colexicographic descending, which
is exactly `EnumeratingCofaceSimplexStream`'s tie order (and Ripser's). So **no sort at all above the edges** in general
position. With tied lengths, runs of equal diameter can come out of order (blocks of different edges interleave, and a
block with several full-length edges); each run is checked in one pass and sorted only if needed (`tieRunsSorted`).

**What per-simplex metadata is needed: only the root edge.** Diameter = root's length; case flags = root's endpoints vs
`u0, u1`; inherited unchanged. The per-vertex pointers into distance-sorted lists are NOT per-simplex: the ball of `u`
at `α` depends on `(u, α)` only, and `α` is non-decreasing along a sorted dimension, so one pointer per vertex, moved
forward by a sweep, serves the whole dimension. A layer is `(k + 1)` vertex ints + one root int per simplex.

**Two candidate searches** (`LayeredInorderSimplexStream.Candidates`):
- `Bitsets`: a closed (`<= α`) and an open (`< α`) bitset row per vertex, filled by the sweep over the sorted edges;
  candidates = word-wise AND over the simplex's rows in the case's index range, read from the top bit down. `n²/4`
  bytes; no distance lookups during generation.
- `NeighbourLists` (the idea as stated): distance-sorted lists, forward-only ball pointers, scan the smallest ball among
  the simplex's vertices, check the other distances in a dense matrix, sort the few hits descending.

## Correctness

`LayeredInorderSimplexStreamSpec`: cell for cell, value for value and in order against `EnumeratingCofaceSimplexStream`
up to dimension 4, both candidate searches, thresholds (enclosing radius, ∞, 0.6 × radius), on random clouds, the
paper's octagon, a 3×3×2 grid, a three-valued metric and random integer metrics; the ordering contract; zero
`tieRunsSorted` in general position; the carried edge is the first full-length edge and its length the diameter.
Mutations: case 2 non-strict for `j > 0` (5 examples fail, incl. all tie-heavy ones); tie-run sort removed (5 fail, the
general-position ones pass, as they should). `LayeredInorderBenchmarkDriver ... check`: generator identical (vertices
and diameters) to the generate-and-sort baseline up to 9.3M tetrahedra.

## Measurements (sandbox, 4 cores, 15 GB; one method per JVM, -Xms8g ParallelGC, median of 7)

`sort` = Ripser's generation (add `w` above the top vertex, threshold bitsets, diameter computed) then a primitive merge
sort by (diameter, colex desc); `countsort` = counting sort on the diameter's rank among edge lengths, then colex within
runs. Same arrays and bitsets as `bitsets`, so the difference is sort versus no sort. Times in ms; "(sort)" is the part
of the baseline spent sorting.

| case | dim | simplices | bitsets | lists | sort (sort) | countsort (sort) |
|---|---|---|---|---|---|---|
| 1000 pts R³, t 0.25 | 3 | 1,254,198 | 142 | 318 | 406 (281) | 310 (210) |
| | 4 | 4,535,792 | 487 | 1130 | 1547 (1193) | 1236 (687) |
| 2000 pts R³, t 0.2 | 2 | 532,597 | 54 | 152 | 281 (154) | 159 (105) |
| | 3 | 2,966,969 | 330 | 857 | 1189 (781) | 997 (598) |
| 150 pts R⁵, t = enclosing radius | 3 | 3,221,154 | 208 | 539 | 1131 (735) | 738 (588) |

An earlier `sort` run of the first case gave 1257 ms for dim 4: run-to-run spread is ~20%, far below the effects here.
Memory, dim 4 of the first case: kept 108.9 MB layered (24 B/simplex) vs 127.0 MB sorted (28 B: vertices + a double);
allocated 444 MB (bitsets), 525 MB (lists), 802–983 MB (sort), 1293 MB (countsort). Peak heap not measured; the sort
needs a permutation, keys and a second copy of the layer on top of it.

Library streams, producing `Simplex` values (n = 100, R³, t 0.4, dim 3, 11,357 tetrahedra, median of 3): layered 10.7
ms, `InorderCofaceSimplexStream` 182, `RipserCofaceSimplexStream` 679, `EnumeratingCofaceSimplexStream` (the
`VietorisRips` default) 10,890 (#184). The layered stream at 1000 points, dim 4: 1.78 s and 4.0 GB allocated, ~880 B
per boxed `Simplex` (a `SortedSet`): the stream API, not generation, is the cost there.

## Verdict

- Feasible and correct, including on tie-heavy metrics, where the paper's strict/non-strict inequalities matter.
- It pays off for producing a sorted dimension: 2.2–5.6x faster than generate-and-sort and 2.0–3.5x faster than a
  counting sort, like for like. The win is the sort: generation alone costs about the same either way.
- The metadata collapses: the first longest edge (4 bytes) per simplex is all; the neighbour-list pointers are one
  global sweep. Distance-sorted neighbour lists as the candidate search only tie with sorting; bitset balls win. Bitsets
  cost `n²/4` bytes, fine to ~8k points (16 MB), not for 50k-point sparse clouds (not measured: index-sorted lists).
- No memory win against Ripser's layered approach: the whole dimension is kept, as Ripser keeps it (the paper's memory
  saving is the depth-first stack, which recomputes lower dimensions instead). The saving is the sort's scratch.
- Where it would matter: the packed Ripser engine's per-dimension column sort (#183, unmeasured share of its time).

## Other uses of the theorem (#185)

The theorem's real content is the decomposition into per-edge blocks `e * Cl(G_e)` (lens graph `G_e`: vertices closer
than `α` to both ends, adjacent when closer than `α`). Then `H_j(X_α, X_α⁻) ≅ H̃_{j-2}(Cl(G_e))`, which gives exact local
bar counts in general position: `#H_1 bars = #(empty-lens edges) − (n − #components)` and `#H_1 deaths + #H_2 bars =
Σ (components(G_e) − 1)`. Checked against the Ripser engine (`LayeredInorderBenchmarkDriver <n> <dim> 2 <t> lens 1
<seed>`): 8 of 8 exact (n 40–80, R²–R⁴, thresholds ∞, enclosing radius, 0.3). Also untested ideas: parallel/windowed
block generation with no sort, edge domination (Boissonnat–Pritam) tested on the fly, any flag filtration, Euler
characteristic curves from per-edge clique counts.

## Not done

- The coboundary-in-filtration-order half of the paper (Section 2.5).
- Peak heap measurement; larger clouds; a sparse candidate search.
- Deleting or fixing `InorderCofaceSimplexStream` (#182; the dispatcher no longer builds it).
- Scala.js: the new code is pure Scala (own bitsets, `java.util.Arrays` only); the JS test job was not run here.

## Follow-up: reducing by edge block in parallel (same day)

Project lead asked whether one reduction per edge block, all at once, gives sensible results. The ideas of #185 are
now one issue each (#187–#191); this one is #192.

**Sound for the pairs inside a block.** Order cells by value, then block (first longest edge ascending: with ties, a
face of diameter `α` not containing `e` has a first edge before `e`, so it is earlier), then dimension. A block's
columns have rows in the block or earlier blocks, and only the block's columns have pivots in its rows, so reducing a
block alone while the pivot stays inside is exactly what the sequential reduction does there. Columns whose pivot
leaves the block are deferred. Same for the coboundary matrix in reverse order.

`LayeredInorderBenchmarkDriver <n> <dim> 3 <t> blocks 1 7` (F₂, no clearing, no apparent pairs, simplices to dim 3):

| cloud | cells | pairs (in one block) | cohomology additions: sequential / phase 1 / phase 2 (adding a phase-1 pair) / sequential remainder |
|---|---|---|---|
| 30, R³, ∞ | 31,930 | 4,089 (4,055) | 38,945 / 3,654 / 35,291 (35,220) / 2,222 |
| 50, R³, enclosing radius | 70,425 | 9,091 (9,032) | 123,561 / 8,191 / 115,370 (115,225) / 28,920 |
| 60, R², enclosing radius | 136,744 | 15,219 (15,149) | 189,390 / 13,944 / 175,446 (175,303) / 10,091 |
| 60, R⁴, enclosing radius | 200,892 | 19,664 (19,576) | 347,705 / 18,228 / 329,477 (329,047) / 100,978 |
| 100, R³, 0.35 | 5,986 | 2,044 (1,919) | 12,053 / 1,360 / 10,693 (10,352) / 2,471 |
| 200, R³, 0.3 | 14,602 | 4,909 (4,633) | 34,040 / 3,353 / 30,687 (29,705) / 10,724 |

Pairs identical to one sequential reduction in every case, homology and cohomology. The in-block pairs (94–99.6% of
pairs) are cheap; the work is in the deferred columns, but almost all of it adds columns paired in phase 1, which are
final then, so each deferred column can be reduced against them independently. "Sequential remainder" counts the
additions from the first time a deferred column needs another deferred column: 5–32% of all additions (Amdahl 3–19x).
Homology on the truncated complex defers every top-dimensional cycle and leaves 78–88% of additions in phase 2.
Additions are not time; nothing here has clearing, apparent pairs or F_p. Prior art to compare: chunks (BKR),
Ripser++, Morozov–Nigmetov lock-free reduction.

## Follow-up: the threaded experiment and benchmarks (same day)

Project lead asked for the experiment itself and benchmarks to compare. `src/test/scalajvm/.../homology/
BlockParallelReduction.scala` (driver and code) and `BlockParallelReductionSpec`; C++ Ripser through ripser.py with
`.claude/scripts/ripser-cpp-timing.py` on the driver's `dump` of the same points.

**What it does.** Explicit coboundary matrices over F_p from the layered generator's cells, ordered by block (the
first longest edge by length, then smaller end, then larger end), dimension, layer order. Cohomology degree by degree
with clearing, degree 0 by union-find, as Ripser. `seq`: one reduction. `blocks-<t>`: phase 1 on `t` threads, each
block's columns by that block's columns while the pivot stays inside (rows of a block are written only by its own
columns, so the tasks share nothing); 2a, deferred columns against phase-1 columns in parallel (read-only); 2b, the
rest in order. `chunks-<t>-<c>`: blocks merged into units of at least `c` columns (Bauer–Kerber–Reininghaus chunks
with boundaries on block boundaries). Soundness needs only that a unit is contiguous in a filtration order: that is
BKR's argument, not something the three cases add; what blocks add is that in general position every zero-length pair
lies inside one block, and that with ties a value splits into blocks by edge.

**Correctness.** Spec: pairs identical to the sequential reduction in every degree for `Blocks(1)`, `Blocks(3)`,
`Blocks(3, 5)`; bars identical to `PackedRipserCohomologyEngine` (F₁₇ and F₂; random clouds, octagon, grid, random
integer metrics); `δV_j = R_j` for every column over F₁₇ and F₃; in general position phase 1 makes exactly the
zero-length pairs. Mutations caught: no deferral in phase 1 (4 examples fail), blocks ordered by (length, larger end,
smaller end) (the 2 tie examples fail: the lexicographic order is needed), V added with the wrong sign (cocycles
fail). `check` mode on cube3 n=400 t=0.25, torus n=400 t=0.5, cube3 n=300 at the enclosing radius (98M tetrahedra):
all agree. Bar counts agree with C++ Ripser on every benchmark cloud.

**Benchmarks** (sandbox, 4 cores; n=1000 in R³, degrees 0–2, F₁₇; one method per JVM, `-Xms12g -XX:+UseParallelGC`,
medians of 7–11; repeated runs of the same work vary by 20–30% here):

| cloud | edges / triangles / tetrahedra | C++ Ripser (H1 only) | packed engine | explicit build: generate + coboundaries | reduce `seq` | reduce `blocks-4` |
|---|---|---|---|---|---|---|
| torus, t=0.6 | 44,106 / 825,500 / 10,026,575 | 873 (107) ms | 2,524 ms | 1,242 + 3,665 ms | 380 ms | 404 ms |
| sphere, t=0.6 | 44,540 / 786,644 / 8,261,559 | 862 (85) ms | 2,669 ms | 830 + 2,335 ms | 220 ms | 388 ms |
| cube, t=0.3 | 40,956 / 646,170 / 5,848,167 | 777 (67) ms | 2,388 ms | 806 + 1,921 ms | 128 ms | 124 ms |

Where the reduction's work is (`blocks-4`; phase-1 times for 1 / 2 / 4 threads):

| cloud | degree 1: deferred of columns; additions phase 1 + 2a + 2b (2b adding a phase-1 column); 2b critical path | degree 2: phase 1 ms; deferred |
|---|---|---|
| torus | 294 of 44,106; 0 + 591 + 5,574 (5,449); 2,683 | 42 / 21 / 13; 3 |
| sphere | 283 of 44,540; 0 + 591 + 1,866 (1,725); 546 | 44 / 20 / 12; 8 (the essential H2 class: 2a 90–170 ms) |
| cube | 484 of 40,956; 0 + 834 + 1,218 (972); 212 | 31 / 20 / 11; 120 |

The critical path is the longest chain of 2b additions in which a column waits for the deferred columns it adds:
no schedule doing these additions shortens 2b by more than 2.1x (torus), 3.4x (sphere), 5.7x (cube). Chunks of 5,000
columns cut the deferred columns to 106 / 83 / 146 and change nothing in the totals.

**Verdict.**
1. Threads do not speed up the reduction of these matrices (1.0x at 4 threads). Phase 1 scales (3–3.6x) but holds
   little work once the coboundary exists: with clearing, 98.8–100% of the columns left pair inside their block,
   with few additions. The work is in a few hundred deferred degree-1 columns (the real H1 bars), sequential in 2b because they
   wait for each other, and then mostly adding phase-1 columns.
2. Explicit matrices are a dead end for speed: building them costs 3.5–5.6x C++ Ripser's whole run and more
   than the packed engine, because they hold the 6–10M tetrahedra Ripser never builds.
3. Where blocks could pay: C++ Ripser spends 88–91% of its time in degree 2 (H1 only: 67–107 ms of 777–873 ms), and
   degree 2 is almost all phase-1 work here (3–120 deferred columns of 650–825k). In an implicit engine that per-column
   work (enumerating cofacets, apparent and emergent pairs) is what a block-parallel phase 1 would split. Amdahl on
   that split: about 2.5–3x on 4 threads, less where an essential class needs a long reduction (the sphere).
   Unverified: it assumes Ripser's degree-2 time is per-column work; next step on #192 is a parallel phase 1 inside
   the packed engine, measured.

**CI out of memory (same day).** The JVM `test` job on `9754103` died with `OutOfMemoryError` in
`LayeredInorderSimplexStreamSpec` and `MatlabTabsSpec`. Cause: `LayeredInorderSimplexStreamSpec` and
`BlockParallelReductionSpec` compared whole listings and arrays with `must beEqualTo` inside `Result.foreach`, which
keeps every rendered message: run alone, the first OOMed at 256 MB and the second at 128 MB, and together they pushed
the shared 2 GB suite JVM over. Both now compare with `==` and report only the first difference (or the first three
failures): each passes in 128 MB (the block spec in 0.25 s instead of 8 s), and the no-deferral and V-sign mutations
are still caught. Rule in `rules/testing.md`.

## Addendum: high degrees and alpha complexes (same day)

Project lead asked: why explicit matrices (the library's `Chain`s would do); what happens in high degrees (H4 and
up); does it pair with alpha shapes; 40 points in a 10-cube up to H10? Driver: `cube<d>` clouds, `alpha:<cloud>` (the
alpha complex via `fromStream`: any filtered complex, one block per filtration value, which on points in general
position is one interval of the radius function), `verb` (alpha: `AlphaShapes`, then `Persistence`).

**Explicit matrices are not needed by the argument**: it uses only columns in reverse order and a pivot table, so it
works on implicit coboundaries (Ripser's way) as well. The arrays were the quickest way to test the reduction; boxed
`Chain`s would mostly measure boxing (the library's `Chain`-based engine on the 10-d alpha complex below: 243 s).

**Alpha.** `check` on `alpha:cube3` n=300 (H2) and `alpha:cube4` n=100 (H3): same pairs as sequential for every
method, same bars as `Persistence` on the alpha complex. 40 points in R¹⁰ to H9 (one run each, 12 GB heap):

| | cells (dims 0..10) | build | reduction | total | bars |
|---|---|---|---|---|---|
| explicit, `seq` | 40 777 9,126 65,814 293,823 828,515 1,497,213 1,727,810 1,230,764 493,019 84,970 | 85.0 s (library alpha complex, then `fromStream`) + 4.2 s coboundaries | 0.13 s, 362 additions in all degrees | 89 s | 7,277 |
| library | same complex | 22.3 s `AlphaShapes` | `Persistence` 242.8 s (includes the stream's own sorted faces) | 265 s | 7,277 |

**Vietoris-Rips, 40 points in [0,1]¹⁰ to H10** (distances: median 1.27, 90% below 1.55):

| threshold | cells (dims 0..11) | C++ Ripser | packed engine | explicit: generate + coboundaries + reduction | additions |
|---|---|---|---|---|---|
| 1.3 | 778k in all (166,621 at dim 7) | 945 ms | 1,678 ms | 223 + 426 + 39 ms = 0.69 s | 434 |
| 1.45 | 56.6M in all (15.1M at dim 11) | 52.4 s | 131.7 s | 36.2 + 75.6 + 0.5 s = 112 s | 434 |

All three agree on 81 bars. In every case here the reduction is essentially free: 41M columns at t=1.45 need 434
column additions in all degrees together (clearing removes 30–50% of each degree's columns and almost every other
column pairs with its first cofacet inside its block). High degrees do differentiate, but not for the reduction: the
whole cost is enumerating cells and their first cofacets, which is independent per block. That is the parallel work
#188 (generation per block) and a block-parallel phase 1 inside an implicit engine would split; unmeasured. For alpha
in high dimension the reduction is likewise free and the library's verb loses to plain primitive arrays with clearing
(the 10-d case: about 3x end to end).
