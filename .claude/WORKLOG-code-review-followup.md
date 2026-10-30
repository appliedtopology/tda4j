# WORKLOG: code-review fixes landed on current `scala` (2026-10-30)

Rebuilt PR #284 (issues #275-#281) on the tip of `scala` after #274 and #283, and reviewed it as I merged. The first
four code-review worklogs (`WORKLOG-code-review-*-2026-10-30.md`) describe what #284's branch did; this one says what
changed on the way in.

## Merge

#284's branch was cut at #270 and conflicted with #283 in `ParallelPackedCohomology.scala`, its spec and `engines.md`.
Taking either side whole silently loses #283: its degree-0 skip when partitioning work units (`partitionLevel` runs only
for `d > 0`) and its `degree.components` path at the top of `cocycle` (without it a degree-0 cocycle would be a bare
vertex, since degree 0 has no logs). Both stay, and a spec pins the `components` path. The `BarcodeDistance` paragraph of
`engines.md` was moved to `barcode.md` on `scala` and stays moved.

## Changes on top of #284

- `PointCloud.check` is the one place ragged and non-finite points are rejected; `PointCloud` and `EuclideanMetricSpace`
  both call it, so the messages agree (they were two copies of the same text).
- `EuclideanMetricSpace.point(index)` returns one point's copy. `pts` copies all `n` rows per call, so nothing in the
  library reads it (grep: src, docs, python, npm, demo, bench); without `point` a user loop `space.pts(i)` would be
  quadratic and the only cheap reader, `pointAt`, is `private[tda4j]` by design (the space's caches depend on its arrays).
- The snapshot spec of #284 read a distance before changing the arrays, which fills the lazy cache first: the cached half
  passed with an aliasing constructor. The spec now changes everything before the first read and also checks the minimum
  enclosing radius and `neighbors` (the lazy vantage-point tree). Mutation check: with the constructor aliasing its
  input it fails.
- The deep-log cocycle spec uses factor 0 (a single term per column, so 30,000 levels stay cheap); it pins the call stack,
  not values. Added a hand-derived diamond over F_17 (a column reached by two paths, 5 + 16 * 3 = 2) and a degree-0
  `components` spec. Mutation checks: ignoring the factors fails the diamond; dropping the `components` branch fails the
  components spec. (Ignoring the factors in the deep-chain spec makes it build quadratically large maps and not finish.)
- `BootstrapBand` reduces each result to bar endpoints. #284 left the bars lazy; a lazy `LazyChain` holds its engine run
  until it is read, and `original` lives for every round.
- Rule files back under ~12 KB (`engines.md` 11,992, `streams.md` 11,951 bytes); changelog fragment lists the behavior
  changes #284 left out: non-finite rejection on direct construction, `pts` copying, `point`.

## BootstrapBand measurement (#281)

Noisy circle in the plane, degree 1, `Auto` (so ParallelRipser), 4 cores, one JVM per size, median of 7 runs after 2
warm-ups, eager = before #284, lazy = #284, lazy + endpoints = this change.

| points | eager | lazy | lazy + endpoints |
|---|---|---|---|
| 500 | 586 ms | 542 ms | 561 ms |
| 1,000 | 7,975 ms | 7,874 ms | 7,391 ms |

The last two columns do the same work and differ by up to 6%, so the differences are noise: no speed effect is
confirmed. Retained heap after a `System.gc()` loop was too noisy to read (eager -812 MB to +30 MB, lazy +4 and +121 MB,
endpoints -13 and 0 MB). The retention mechanism is by reading `LazyChain`/`Persistence`, not by a clean measurement.
The case for #281 is that the representatives are never read, not a measured speed-up.

## Left open

#282: `partitionLevel`, `workUnitOf` and `assembleNextLevel` are extracted (checked: `level` and `levelBlocks` are not
reassigned between the loop start and the assembly call, so passing the live variables equals the old snapshot); the
phase reductions and pairing/bar collection stay in `persistence` (#282). No A/B measurement of the extraction; it is
outside the inner loops.

`cocycle`'s memo keeps every intermediate cocycle, so a deep chain with non-zero factors costs memory quadratic in the
depth; the iterative version fixes the stack, not that. Neither the depth of real logs nor that memory was measured (#287).

The extra copy of the coordinates that `EuclideanMetricSpace` now holds (and the lazy one in `vpt`) was not measured against
the memory of a run at 10^4 to 10^6 points (#288).
