# Degree 0 by union-find in every engine that reduced it (2026-10-30, #194 item 5)

Point-in-time record; the resulting rule is the "Degree 0" paragraph of `rules/engines.md`. Mikael's question, after the
binary-tree result (`WORKLOG-tree-merge.md`: H0 was half of the serial part of `ParallelRipser`): is there any context
where union-find for H0 is a bad idea? If not, put it in all the engines. The tree-merge prototype was dropped (reverted)
in the same stretch.

## Is there a context where it is bad? One, and it is a guard, not a reason to skip

Union-find is the elder rule on the graph of the edges. It is the reduction exactly when every 1-cell's boundary is
`c·(v - u)` (`u ≠ v`, `c` a unit) or vanishes (a loop). A cell complex from `sset`, a Morse complex or a hand-built chain
complex can have an edge with three ends, one end, or `c·u + d·v` with `c + d ≠ 0`; there degree 0 is a matrix rank over
the field, not connectivity. `CellularCohomologyEngine` therefore checks every edge (`EdgeBoundary.shape`, the check the
chunks engine already made, moved to one place) and reduces degree 0 by columns when one fails, or when an edge has a face
outside the stream. Nothing else was found: not ties (the edges' order is the pivots' order), not field (the argument is
the same over F_2, F_p and the reals), not memory (it is far smaller than the columns it replaces).

## Why the pairs and the cocycles are the reduction's

Columns are the vertices' coboundaries, processed youngest first; a column pivots on its oldest edge. Read the edges oldest
first with a union-find whose roots are the oldest vertex of each component: an edge that joins two roots kills the
younger one, and that pair is the reduction's. For the cocycle: by induction a stored reduced column is `δ(1_C)` for the
component `C` that died, and the working column of a vertex that meets that pivot is `δ(1_S)` for the union `S` merged so
far. At the edge `e` the two columns have `S` and `C` at opposite ends of `e`, so their coefficients at `e` are `±c` with
opposite signs: the reduction coefficient is -1 whatever the scalar `c` of the edge, and `1_S + 1_C` is the next V-column.
So a bar's V-column is the indicator of the component that dies, as it stands just before the edge (the final component
for a vertex that never dies). A scalar on a row commutes with column operations, which is why `c` drops out.
An emergent or apparent pair of degree 0 (a zero-length edge at a vertex) is the case where the component is the vertex.

## What was changed

| engine | how |
|---|---|
| `ParallelPackedCohomology` | one pass over the edges in key order (`MergeForest`: path-halving finds, the merge history kept, children as CSR on `seal`); degree 0 is not cut into units; cocycles are read off `members(v, diedAt(v))` when asked for, through `Degree.components`. The deaths are the `late` pairs of degree 1, so degree 1's phases are unchanged. |
| `PackedRipserCohomologyEngine` | the edges (degree 1's level) are generated and sorted once and serve degree 0 (read backwards: oldest first) and degree 1; `pairVertices` fills the rows in vertex order, as before, and the death edges are degree 1's cleared columns. `useUnionFind = false` keeps the old loop. The apparent and emergent counters now cover degrees 1 and up. |
| `CellularCohomologyEngine` | the cell path (`reduction`) and the flat-array path (`PackedReduction`, which gained `facesAbove`/`coboundaries` split so degree 0 does not build a coboundary block it does not use); `useUnionFind` parameter. |
| `PackedCubicalCohomologyEngine` | union-find over vertices numbered by their coordinates in a mixed radix (the key order), age compared on `packed(key)` at a merge only (no vertex-to-position table: that would be a second array the size of the doubled grid); `useUnionFind` parameter. |

Already by union-find, unchanged: `CellularPersistenceInChunksEngine`, `FastCubicalHomologyEngine`,
`FastAlphaHomologyEngine`, `BlockParallelReduction`. Left as column reductions on purpose, because they are the oracles:
the naive `CellularHomologyEngine` (`rules/engines.md`: a raw-UnionFind fast path was rejected), `RipserCohomologyEngine`,
`ChainPackedRipserReference`, `CellKeyedCohomologyReference`.

## How it was checked

- `ParallelPackedCohomologySpec`: degree 0 against a plain mod-p reduction written in the spec (general position, tie-heavy
  and integer metrics, F_17 and F_2) and against `RipserCohomologyEngine` where there are no ties. The old per-call
  "a log was recorded" assertion went (degree 0 records none); a separate example checks that degrees 1 and 2 still log.
- `EmergentPairsSpec`: `useUnionFind` on and off, term for term (bars, pairs, cocycles, cycles, with and without zero-length
  bars; F_2, F_3, F_17; neighbour lists and two-word indices on and off; tie grids with coincident points). The apparent-pair
  count is compared with the reference engine's minus the reference's degree-0 count.
- `CohomologyUnionFindSpec` (new): random hand-built chain complexes with every edge shape (plain, scaled `c(v - u)`, loops
  listed as nothing or as `(v, 1), (v, -1)`, three ends, one end, parallel edges) and triangles, against
  `CellKeyedCohomologyReference`, over F_2, F_3, F_5, F_17, plus on/off for cycles. `CohomologyNumberingSpec` gained discrete
  Morse complexes (all of its edges are regular, so they take the union-find path).
- `PackedCubicalCohomologySpec`, `PackedSimplexCohomologySpec`: the generic or unpacked side now reduces every column
  (`useUnionFind = false`), so each of them compares union-find with the reduction instead of with itself.
- Mutations, each caught: elder rule reversed (parallel, packed Ripser, cell path, flat-array path, cubical: five places);
  a cocycle that is the vertex alone (packed Ripser, cubical); an irregular edge treated as a loop (cell path).
- Full JVM suite: 2,194 specs, 0 failures, 8 skipped (before the Scala.js fix below; rerun after it, see the end).
- `MergeForestSpec` (new, both platforms): `members` against a naive closure over the merge list, for a star, a chain and
  random merges of 5, 40 and 150 vertices, each vertex once.

## Measurements (4 cores, one JVM per row, `SingleEngineProfileDriver` and `ParallelPackedCohomologyDriver`)

Identical bar and cocycle digests on and off in every row.

| case | before | after |
|---|---|---|
| packed Ripser, H0 only, 2,000 random points in R^3 | 1,840 ms | 521 ms |
| same, 4,000 points | 12,300 ms; out of memory at -Xmx800m | 2,750 ms; runs at -Xmx400m |
| same, H0-H1, 2,000 points | 6,850 ms | 5,130 ms |
| same, cycles (`packed-cycles`) H0-H1, 1,000 points | 1,480 ms | 1,170 ms |
| same, H0-H2, 300 points | 2,157 ms | 2,179 ms (H0 is not the cost there) |
| `ParallelPackedCohomology`, torus 1,000, threshold 0.6, 4 threads | degree 0 55-88 ms; total 451 ms | degree 0 0.4 ms; total 307 ms |
| same, sphere 1,000 | degree 0 86 ms; total 483 ms | degree 0 0.6 ms; total 338 ms |

Machine noise here is 20-30% on totals; the degree-0 phase numbers and the H0-only rows are far outside it.

## Left

- The cycles of degree 0 still come from `Involution` (the reduced boundary of each death edge); H0-only cycles cost about what
  the cocycles do (477 ms against 516 ms at 2,000 points), so it is not a bottleneck.
- With degree 0 gone, the serial rest of the parallel engine at n = 1,000 is degree 1's deferred columns (phase 2b, 58-67 ms of
  300-340): the candidate is the dependency-driven 2b of `WORKLOG-tree-merge.md`, worth at most about 8% on the torus.
- On a stream with a face missing at the vertices or an irregular edge, `CellularCohomologyEngine` still reduces degree 0 by
  columns; no engine reports which path it took (the specs know by construction).

## Scala.js: `java.util.ArrayDeque` as a stack repeats elements (found by the first Scala.js run)

`MergeForest.members` first walked the tree with a `java.util.ArrayDeque[Int]` as its stack. On the JVM that is right; on
Scala.js the deque repeats elements once it grows (80 pushes and pops through `push`/`pop`, 218 of 300 random trees
visited some element twice; a plain reproduction, no library code). The cocycle of the packed cubical engine's essential
class then had 66 terms for 34 distinct vertices on the 7x6 grid of `PackedCubicalCohomologySpec` (seed 0), which is how it
showed: five examples of that spec and one of `CohomologyNumberingSpec` failed on Scala.js and none on the JVM. The first
`MergeForestSpec` run here was the mutation check: with the deque back, it fails on Scala.js ((0,0,40), (0,0,150), (0,1,40))
and passes on the JVM.
`members` is now breadth first in one growing `Int` array (no boxing either); no other `java.util.ArrayDeque` is in the
sources (the `ArrayDeque`s elsewhere are `scala.collection.mutable.ArrayDeque`, which are Scala code). The rule is in
`rules/scalajs.md`. This is a Scala.js library defect in the version of the build (linker 1.22.0); not reported upstream.
