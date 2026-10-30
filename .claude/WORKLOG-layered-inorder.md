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

## Addendum: the library-edit issue, and parallel blocks in a packed engine (same day)

Project lead chose: write up the stream-engine edit as an issue (#193), then run "parallel blocks in the packed
engine".

**#193 measurements.** `AlphaProfileDriver` (unsampled unless noted) on the Helix alpha complex, `Persistence` timed on
the already-sorted stream: 10,000 points in [0,1]³ to H2 (286,139 cells) 3.9 s against 0.09 s coboundaries + 0.07 s
reduction on flat arrays (`BlockParallelReduction`, no representatives); 40 points in [0,1]¹⁰ to H9 207 s under the
stack sampler. Its top tda4j frames: the coboundary blocks built from `Simplex.boundary` and `HashMap[CellT, Int]`
(`Cohomology.scala:159`) 20.6%, the involution's boundaries (`:56`) 11.8%, `SimplexOps.toArray` 11.0%; leading self frame
`RedBlackTree$TreeIterator.<init>` 32.9% (`Simplex` is a `TreeSet`). Side note, not pursued: the Helix triangulation of
the 10,000 points took 54 s; `AlphaShapes(pts)` with the default backend gave a slightly different complex in 1.3 s.

**The experiment.** `ParallelPackedCohomology` (test scope, JVM): Vietoris-Rips cohomology over F_p on implicit
coboundaries, as the packed engine computes it (clearing, apparent pairs, heap working column, cofacets on the fly,
H0 by the same reduction). A simplex is one `Long`: block (1 + rank of its first longest edge) above `mask - index`.
Each degree's sorted columns are cut into units of whole blocks (8 per thread), reduced in parallel youngest first
against the unit's own owners; a column whose pivot leaves its unit is deferred, goes on in parallel while its pivot
belongs to a phase-1 owner or an apparent pair (2a), then one by one in the sequential order (2b). Canonical cofacets
are assembled in parallel chunks and sorted with `Arrays.parallelSort`. Apparent pairs come from the block's edge: the
youngest same-block facet drops the largest vertex off the edge, the oldest same-block cofacet adds the first qualifying
vertex above it (O(k) instead of Ripser's O(k³) facet scan); `ripserApparent` does it Ripser's way for comparison.

`ParallelPackedCohomologySpec`: same pairs and same reduction log per column as the sequential run (3 threads, 1/4/1000
units per thread), same pairs Ripser's way, the packed engine's bars, on general position (plus a threshold), the
octagon and the grid over F_17 and F_2, random integer metrics; δV = R over F_17 and F_3. Mutations caught: no deferral
(integer metrics), all-plus signs (bars). Bars also equal the packed engine's on the 10-d case at t = 1.45.

**Times** (one JVM per row, 12 GB heap, ParallelGC, common-pool parallelism = threads; whole run from points to bars,
reduction logs kept, no cocycles expanded; C++ is ripser.py 0.6.15's bundled Ripser, and the packed engine is
`pairedCohomology(cocycles = false)`, both from the earlier rows). n=1000 to H2: median / min of 11, ms. 10-d: of 3, s.

| case | C++ | packed engine | sequential | sequential, apparent Ripser's way | 2 threads | 4 threads |
|---|---|---|---|---|---|---|
| torus t0.6 | 873 | 2,524 | 1,252 / 715 | 1,390 / 721 | 895 / 475 | 483 / 321 |
| sphere t0.6 | 862 | 2,669 | 1,366 / 695 | 1,245 / 686 | 643 / 434 | 410 / 312 |
| cube3 t0.3 | 777 | 2,388 | 714 / 572 | 798 / 715 | 464 / 396 | 295 / 266 |
| 40 pts in [0,1]¹⁰, H10, t1.45 | 52.4 | 131.7 | 10.4 / 9.96 | 12.1 / 12.0 | 6.8 / 4.8 | 4.27 / 3.35 |

Sequential medians at n=1000 swing 1.2-2x above the minimum within one JVM (not explained; JIT or GC); 4 threads give
2.2x by minimum, 2.4-3.3x by median. Where it goes (last trial of each): at n=1000 degree 2 is assembly+sort (358 ->
89 ms on torus) and phase 1 (489 -> 113 ms, nearly all apparent pairs), both scaling; the serial rest is H0 by
reduction (70-125 ms; Ripser's union-find would remove it) and degree 1's phase 2b (66-130 ms: ~200 deferred columns
and ~5,000 additions, as in the explicit experiment). In 10 dimensions 41M columns need ~400 additions and no column is
deferred from degree 4 up; sequentially 6.0 s is assembly+sort and 3.9 s phase 1 (apparent tests), both of which split
by block. The edge-based apparent test is worth 14% there (10.4 vs 12.1 s) and nothing measurable at n=1000.

**Why it is 13x the packed engine sequentially** (JFR of the packed engine on the 10-d case, its own frames only): about
half its samples decode combinatorial indices (`SimplexIndexing.searchRow`, `decodeToArray`: a binary search over
lazily filled binomial rows, several times per column), about a sixth to a quarter sort (`SortIndices.sort` over
(diameter, index) with a closure), a tenth test apparent pairs, 3% box `Int`s. The experiment decodes once per column
from a precomputed table and sorts a primitive `Long`. Against C++ Ripser the 5x is not attributed (C++ not profiled);
the apparent test explains 14% of it at most.

**Verdict.** Parallel blocks work in an implicit engine and are exact: 2.2-3.3x on 4 cores at n=1000 and 2.4-3.0x in
10 dimensions, bounded by H0 and degree 1's sequential phase 2b at small degree and by memory bandwidth in assembly.
The larger, cheaper win is sequential: one decode per column, a primitive sort key, and the edge-based apparent test
make the same algorithm 2-13x faster than the library's packed engine; against ripser.py's C++ it is on par at
n = 1000 (0.7-1.2x) and 5x faster in the 10-dimensional case. Limits: one `Long` key needs bits(C(n, maxDim + 2)) + bits(edges) <= 63 (n = 1000 to H2: 55;
n = 10,000 to H2: 75, too many, so a two-word key); no sparse neighbour lists; cocycles recorded as logs, not
expanded. Library edits filed: the packed engine #194, the stream engine #193.

## Addendum: the stream engine on flat arrays (#193), and the experiments into main (same day, later)

Project lead: "implement the universal fast engine too", then (review on #186) move the experiments into main and wire
them into the benchmark harness, in identifiable commits.

**Where it went.** Not a new engine: `CellularCohomologyEngine` takes the packed path itself when its cell instance
carries the private marker `StandardSimplexBoundary` (only `simplexIsOrderedCell` makes such instances, and they are
anonymous, so nobody overrides their `boundary`) and every vertex is an `Int`, strictly ascending. Every caller (verb,
`PersistenceEngine`, MATLAB, CLI, `FastAlphaHomology`'s middle degrees, `CircularCoordinates`) gets it unchanged;
`packSimplices = false` is the old reduction, `reducesPacked(stream)` reports the choice. Deviation from #193's text,
which proposed a separate engine and a dispatch: one class, no caller to switch, nothing new to document for users.

**Exactness by construction.** Each dimension: cells in the stream's listing, values computed once; the engine's order
(value ascending, then the stream's `filtrationOrdering`) is the listing with each equal-value run reversed, checked
strictly, else a stable sort (the same stable sort the old path ran, so ties and even pathological equal cells come out
the same). Cell numbers are positions; faces found by an open-addressing hash of vertex tuples (MurmurHash3). Coboundary
blocks as CSR of `2 * row + signBit`, plus each cell's youngest facet. A column with a free first row is paired at once;
any other goes through `Chain.reduceBy` with `fallback` building untouched owners' columns exactly as the old path
stored them (`plus(zero, ±1)`), and the V-column arithmetic is the old code verbatim. The involution mirrors
`Involution.cycles`: a death whose youngest facet is its birth needs nothing; otherwise `reduceBy` with a fallback for
processed trivial pairs (the `processed` check only matters for inconsistent pairings: mutating it away changes nothing
on valid input). Representatives are mapped back to cells only for bars returned.

**Tests.** `PackedSimplexCohomologySpec`: packed against `packSimplices = false` on alpha in R²-R⁴, a truncated alpha,
grid VR/Čech/witness (ties), RP² (fixed and filtered), shuffled listings; F₂, F₃, F₁₇ (3 seeds) and the reals; pairs,
order, cocycles and cycles with and without zero-length bars; RP² H₁ over F₂ not F₃; cycles with zero boundary and
essential cocycles with zero coboundary; fallbacks (`String` vertices, reversed vertex order, a user's instance).
Mutations caught: facet 1's sign flipped (error), tie runs not reversed (failures), involution shortcut always taken
(error), wrong V-column generator for an untouched pivot (errors). `CohomologyNumberingSpec` (engine against the
cell-keyed reference) passes on the packed path. Full JVM suite: 1,561 tests, 0 failures.

**Measurements** (`StreamCohomologyBenchmark`, test scope: the alpha stream built and listed before the clock, one JVM
per row, F₁₇, 12 GB heap; median / min):

| complex | cells | packed, cycles | cells, cycles | packed, cocycles | cells, cocycles |
|---|---|---|---|---|---|
| alpha, 10,000 uniform in [0,1]³, to H2 | 286,143 | 1.39 / 0.99 s (5) | 8.86 / 7.27 s (5) | 1.05 / 0.51 s (5) | 2.15 / 1.48 s (5) |
| alpha, 40 uniform in [0,1]¹⁰, to H9 | 6,231,871 | 23.3 / 18.0 s (3) | 232 s (1) | | |

So 6x (R³) and 10x (R¹⁰) for cycles, 2x for cocycles in R³. Far from the flat-array floor measured earlier (0.16 s and
4.3 s, no representatives); not profiled yet. Suspects: one pass over `Simplex` objects (tree iterators) and one
`filtrationValue` lookup per cell, the tie checks through `filtrationOrdering` (four lookups per comparison), and the
`Chain`s of returned representatives under an ordering that looks values up per comparison.

**Review on #186.** `BlockParallelReduction` and `ParallelPackedCohomology` moved to `src/main/scalajvm` (threads; a
pure rename commit, then the main-code rules: `final` dropped, scaladoc without the test-scope wording; also
`LayeredInorderSimplexStream`'s `Layer`, `IntBuffer`, `DoubleBuffer` lost their unexplained `final`).
`PaperBenchmarkDriver` takes `engine=blocks|parallel threads=N` for `task=vr` (bars only; threshold defaults to the
enclosing radius as the verb's does; the edge table and complex inside the timed region); `bench.py` has
`tda4j-blocks-mt`, `tda4j-parallel-1t`, `tda4j-parallel-mt` (the parallel ones set the common fork-join pool's
parallelism). Smoke run: identical barcodes to `engine=auto` on `bench/smoke-points.txt` and torus n=1000 t=0.6 over
F₂ (verb 3.7 s, blocks 6.1 s, parallel 0.90 s, one cold trial each, 1 thread). Two harness tests were stale after
the scala merge (Čech cases now in the main run; the new tasks countable by TDA4j's stream) and were updated.

## Addendum: a thread-scaling sweep for the multithreaded tools (2026-10-21, late)

The project lead asked how the thread count is set and for a sweep on a heavy example or two, to show how the `-mt`
tools scale on the 32-core compute server. `bench/sweep_threads.py` (`bench/run.sh sweep-threads`) runs every
`multithreaded` Vietoris-Rips row of `bench.py` (`tda4j-parallel-mt`, `tda4j-blocks-mt`, `giotto-ph-mt`, `dipha-mt`
with ranks) at 1, 2, 4, ... threads up to `os.cpu_count()`, ripser.cpp once per case for scale, each run through
`bench.run_one` (cold, then warm under `--warm-cap`). Design points:

- **Pinning** (default when `taskset` exists): a run at t threads gets CPUs 0..t-1 (`bench.pinned`, via
  `args.pin_cpus`), so a 1-thread JVM does not collect garbage on 32 cores; Open MPI gets `--bind-to none` so its ranks
  stay in the set. Not tested here (no MPI in the sandbox).
- **Order and stopping:** most threads first; a series stops at the first failure other than `oom` (fewer threads
  only take longer; an error does not depend on the count; fewer threads may need less memory).
- **The invariant checked:** the barcode does not depend on the thread count (exact multiset equality against the
  fewest threads that finished), plus the bottleneck distance to the baseline's when GUDHI is installed.
- **Explicit cap:** `tda4j-blocks-mt` is tagged `explicit` and skipped above `--explicit-max-cells` like PHAT, in the
  main run too (`bench.explicit_too_large`); it would otherwise spend its timeout or heap on fractal-r, dragon2000,
  o3_4096, clifford50000. The driver now says why when the experiments' n x n tables cannot be allocated.

**Which cases the parallel experiment can run.** Its key needs bits(max C(n, q), q <= dim + 2) + bits(edges + 1) <= 63:
o3_4096 needs 54 + 18 = 72 (does not fit; two-word keys are #194), cyclooctane exactly 63, fractal-r 49, o3_1024 60,
dragon2000 52, random16 42. clifford50000 fails earlier, on the n x n tables. Default cases: fractal-r and a generated
`cube10_40` (40 points uniform in [0,1]^10, numpy `default_rng(20261021)`, to H10). The generated draw is much lighter
than the earlier Scala-drawn cloud at the same threshold (ripser.cpp 1.7 s at t = 1.45 against ripser.py's 52 s there):
the high-dimensional cell count lives in the tail of the distances. Calibrated by ripser.cpp: 9.0 s at 1.5, 22.8 s at 1.55
(27.8M cells), 144 s at 1.6 (234.7M cells, `workers/count_cliques.cpp`); t = 1.6 chosen, and the count recorded in the
script so the explicit tools skip it.

**Smoke measurements** (4-core sandbox, pinned, one JVM per run, F₂; warm medians unless noted; not for the paper):

| case | ripser.cpp | tda4j-parallel-mt, 1 / 2 / 4 threads | tda4j-blocks-mt, 1 / 2 / 4 |
|---|---|---|---|
| random16 (15.8M cells, to H7) | 5.7 s | 2.78 / 1.39 / 0.753 s (3.7x) | 19.3 / 15.9 / 15.1 s (1.3x) |
| fractal-r (2.2B cells, to H2) | 7.49 s | 17.2 / – / 6.81 s (2.5x) | skipped (explicit) |
| cube10_40, t = 1.55 (27.8M) | 22.8 s | 7.92 / – / 2.75 s (2.9x), one trial | |
| cube10_40, t = 1.6 (234.7M) | 144 s, one run | 57.9 / – / 24.8 s (2.3x), one trial, heap peak 6.1 GB | skipped (explicit) |

Barcodes identical at every thread count on all four (cube10_40: 81 bars, as ripser.cpp's). Sequentially the experiment
is 2.3x slower than ripser.cpp on fractal-r and 2-2.5x faster on random16 and cube10_40. Not verified: anything above 4
threads, `giotto-ph-mt` and `dipha-mt` in the sweep (not installed here), the GUDHI distance column.

## Addendum 2026-10-28: the lens-graph identities in the library (#187, #191)

Point-in-time snapshot (thread "Work through issues #187 to #206").

**#187, the oracle.** `LensGraphOracleSpec` (shared by JVM and JS) checks the two identities of #187 against `Engine.Chunks`,
`Naive`, `Cohomology`, `Ripser` and `ParallelRipser` over F_2, F_3 and F_17 on random clouds (n = 6..12 in R^2..R^4, three
thresholds), against `Ripser` and `ParallelRipser` on eight clouds of n = 40..60 (two on JS), and on two hand-counted
clouds (a perturbed square, a perturbed octahedron). The oracle (`LensGraphOracleSpec.counts`) is O(edges * lens^2) with
a union-find per lens and never computes persistence. It requires all distances distinct (`distinctDistances`); a grid is
asserted to fail that check, because with ties an edge's lens graph misses the simplices its tied edges share.
All engines agree with the oracle today. Sanity mutation: `includeZeroLength = true` made the engines throw rather than
disagree, so that run says nothing about detection; the discriminating power is the two hand counts plus the 64 expectations
that held on every engine.

**#191, the curve.** `EulerCharacteristicCurve` (`streams/`): a simplex belongs to its last edge in the sorted edge list
(any fixed order refining length), and the simplices of an edge `ab` are `ab` joined with the cliques of the graph on
the points joined to both `a` and `b` by EARLIER edges, joined to each other when their edge is earlier. Using the
sorted-edge index instead of the length for "earlier" makes the identity exact on tied distances too: tied edges are just
taken in the sorted order and their jumps add at the shared scale. So the curve steps at length(ab) by
`-1 + c_1 - c_2 + ...` with `c_j` the `j`-vertex cliques of that graph; the sweep holds one bit row per vertex
(`n^2/8` bytes per chunk). Chunks of the sorted edges are independent given the edges before them (each replays its
prefix with bit sets, cheap next to clique counting) and run through `Parallel.tasks`, last chunk first because the lens
graphs grow along the sweep.
- Checked cell for cell against `ofStream(LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream))` for simplex
  dimension 0..4 on random clouds, the octagon, a grid, the three-value metric and random integer metrics, at threshold
  None, infinity and 0.6 of the enclosing radius, and against the alternating sum of the Betti numbers of the full
  7-point complex (`Persistence`, no simplex count). Sign mutation fails 6 of 8 examples.
- Steps where the value does not change are not steps (the right triangle's edge of length 5 and its triangle cancel).
- Timing, one run each, this container (4 cores), not an A/B: n = 1,000 in R^3, threshold 0.3, simplices to dimension 3:
  45 ms (36,172 steps); n = 300 to dimension 2: 7 ms against 300 ms for counting the Ripser stream; n = 100 in R^8, every
  edge, simplices to dimension 6: 14.6 s on one thread, 7.1 s on four (about 2x; why not more is not looked into).
- Not done: MATLAB, CLI and JavaScript wrappers (follow-up issue), a curve from a persistence diagram (the Betti-number
  alternating sum of a diagram is the curve of the complex only when the diagram is not truncated).
