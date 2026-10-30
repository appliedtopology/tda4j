# Merging the edge blocks in a binary tree (2026-10-30)

Point-in-time record; the resulting rule is one sentence in `rules/engines.md`. Mikael's question: in the block-parallel
Ripser engine (`ParallelPackedCohomology`, behind `Engine.ParallelRipser`), once every unit has done its own work, would
merging the units pairwise in a binary tree beat finishing everything in series? Method: read the engine, argue validity,
build the tree as an option (`Merge.Tree`), compare with the serial merge on the same inputs. 4 cores (the container),
no subagents.

## How the merge works today

Each degree's columns are cut into `threads * 8` units of whole edge blocks. Phase 1 reduces each unit youngest column
first against that unit's own pairs, in parallel; a column whose pivot leaves its unit is deferred. Then
**phase 2a** reduces the deferred columns in parallel against the units' pairs (final, only read) for as long as the
pivot is owned by one, and **phase 2b** finishes what is left one column at a time, youngest first, against every pair so
far. So it is not "everything in series": the part of the deferred work that does not wait on another deferred column is
already parallel. What 2b serializes is columns that wait on a deferred column, and also columns that do not (two
deferred columns from distant units, with nothing in common, still go one after the other).

## Is a tree valid? Yes, for any tree shape

A column of unit `i` has entries only in blocks of units `>= i`, so its pivot only moves toward later units, and a pair
whose pivot lies in unit `k` is owned by a column of a unit `<= k`. For a contiguous run `N` of units, call "the state of
`N`" the reduction of `N`'s columns alone, each stopped when its pivot leaves `N`'s rows. A column outside `N` and later
than it cannot have an entry in `N`'s rows, so this agrees with the global reduction up to the exit: the state of `N` is a
function of `N` alone, not of how it was assembled. The merge of adjacent `L` and `R` takes `L`'s deferred columns, youngest
first, and reduces them against `R`'s pairs; `R`'s deferred columns pass through. Hence the merge is associative in the
only sense that matters: any bracketing gives the state of the union, and the pairs and every column's log are those of
one sequential reduction. This is the chunk algorithm of Bauer, Kerber and Reininghaus applied recursively (one level of
chunks, then a global pass, becomes `log2(units)` levels); the spectral-sequence algorithm of Edelsbrunner and Harer, and
Lipsky, Skraba and Vejdemo-Johansson's parallel version, are other multi-level orders (from memory, not re-read). The
dependence runs one way: `L` consumes `R`'s pairs, `R` never reads `L`. That is why the order inside a merge is fixed
(youngest first), and why a tree is possible at all.

Checked, not just argued: `ParallelPackedCohomologySpec` now runs `Merge.Tree` next to `Merge.Serial` (3 threads; 1, 4 and
1000 units per thread) and demands the same pairs and the same reduction log of every column, and valid cocycles; the
mutation "reduce the left half's deferred columns oldest first" fails the integer-distance example. Bars equal the packed
engine's on the torus n=1000 (`check`). A first run hung: a degree with no columns has no units, and `solve(0, 0)`
recursed forever; fixed (an empty merge), and the property tests cover it.

## Measurements (4 cores, medians of 15 trials, `-Xms6g -Xmx10g -XX:+UseParallelGC`, one JVM per row)

`ParallelPackedCohomologyDriver <cloud> <n> 2 <threshold> 4 <units/thread> <trials> 42 17 [tree]`; harness
`ab.sh` (scratchpad, alternating serial and tree JVMs). Run-to-run noise here is 20-30% on totals (median over min), so
read the by-phase medians and the by-depth columns, not the totals. "Merge" is phases 2a + 2b, or the tree plus the pass
for pivots beyond every unit (degree 0 only).

Merge time per degree, ms (serial / tree), and what fraction of the run the serial merge is:

| cloud | degree 0 | degree 1 | degree 2 | total (serial / tree) | serial merge, all degrees |
|---|---|---|---|---|---|
| torus 1000, t 0.6 (2 rounds) | 60-88 / 55-56 | 74-82 / 98-99 | 2.2-2.4 / 3.0-3.2 | 451-453 / 464-465 | 30-38% |
| sphere 1000, t 0.6 | 86 / 57 | 67 / 94 | 20 / 24 | 483 / 458 | 36% |
| cube3 1000, t 0.3 | 55 / 51 | 56 / 68 | 14 / 16 | 398 / 410 | 31% |
| torus 2000, t 0.4 | 126 / 97 | 156 / 204 | 0.9 / 1.1 | 1,013 / 1,086 | 28% |
| scales 480, t 8.2 | 14 / 13 | 12-14 / 10-11 | 0.1 / 0.3 | 92-101 / 92-99 | 29% |

Degree 0's two numbers are the same code (one unit; all vertices are block 0; 999 additions in 2b), so their gap is the
noise level. Tree on the torus by depth, degree 1 (busy / longest ms, depth 0 the top merge): 37 / 37, 27 / 27, 21 / 21,
7.7 / 7.3, 4.6 / 2.8. Busy equals longest at every depth: no two merges ran at the same time. Units per thread on the torus,
degree 1 merge (serial / tree): 2: 72 / 89; 8: 74 / 98; 32: 74 / 109 (more leaves, more levels, more reloading of the
working column into the heap).

Where the deferred columns are, torus n=1000 degree 1, per unit oldest first (33 units): `20 59 57 43 12 5 4 0 0 ... 0`.
Cube3: `36 152 76 38 13 4 4 0 1 3 1 1 0 ...`. All of them are in the first quarter of the units: a cycle is born at a short
edge, so the columns that leave their unit are the early edges, and everything to the right only supplies pairs. In a
balanced tree the merges with work form the left spine (`[0,2)`, then `[0,4)`, then `[0,8)`, ...), each needing the one
before it. The top merge alone holds the two longest chains: 2 columns, 3,638 of the 5,994 additions, 37 ms (one run).

## Verdict

1. The tree is correct and gives the same pairs and logs, but on these clouds it is slower than the serial merge by
   20-40% in degree 1 and gains nothing elsewhere: the merge work sits on one spine, and each merge reloads and drains its
   columns once more. The serial path's 2a already parallelizes the part a tree would, with no barrier.
2. A tree could only help where deferred columns are spread over the units and wait on each other locally. `scales` is
   built for that (11 deferred columns over 33 units): the degree-1 merge falls from 12-14 ms to 10-11 ms (the nodes of
   depth 1 and 2 do overlap), 2-3 ms of a 92-101 ms run, inside the noise. There 2b is empty anyway: all 970 additions are
   in 2a.
3. The bound for any schedule of the dependent additions is their critical path: 2.1x (torus), 3.4x (sphere), 5.7x (cube)
   on phase 2b (`WORKLOG-layered-inorder.md`, explicit-matrix experiment), 2b being 60-70 ms of a 450 ms run: at most
   about 8% of the run on the torus, and the two long columns (about 1,800 additions each, one cofacet enumeration per
   addition) are a chain no schedule shortens.
4. **The serial part of the run is not what the tree addresses.** At n = 1000 on 4 threads the serial merge is about 30%
   of the run, and about half of that is degree 0 (as large as degree 1 in four of the five rows): `H0` is reduced as columns of the coboundary, 999
   additions in one unit, 55-88 ms (13-19% of the run at n = 1000; 12% at n = 2000). Ripser uses union-find for it
   (#194, item 5). That is the first thing to take out; it answers the "what is the serial part" question of #264 for this
   range of sizes (the 32-CPU server's fractal-r and cube10_40 are not measured here).

## What to do next, if anything

- H0 by union-find, with its cocycles still built from the components on demand (the library's representatives principle).
  Not done here.
- A dependency-driven 2b (each deferred column runs as soon as the deferred owners it needs are final) is the schedule that
  reaches the critical-path bound; worth at most ~8% on the torus. Not built.
- `Merge.Tree` stays as an experiment on the thread branch only if wanted; otherwise `git revert` the experiment commit.
  Binary-compatibility: `persistence` gained a defaulted parameter, a MiMa filter if it ships.
