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
