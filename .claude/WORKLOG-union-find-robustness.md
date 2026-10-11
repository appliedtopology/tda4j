# WORKLOG: the chunks engine's union-find on edges that are not c(v - u) (2026-10-11)

The project lead asked how hard, and how expensive, it would be to make `unionFindDim01` handle edges with a zero
boundary properly, since zero boundaries in minimal or near-minimal resolutions are a feature. The instruction was to
"try both robustness improvements", to accept "this doesn't get us anything" as a valid outcome, and to measure.

## Premise check

The earlier fix (56a9a30, then d41c728) was already in the union-find, not in the cells. An edge whose boundary listed no
entries closed a cycle by itself. Simplicial sets list a loop's boundary as (v, +1), (v, -1); the original code already
read that as a cycle, because both entries have the same root. No extra cells were ever created.

What was still wrong: the union-find read only entries 0 and 1 of each edge's boundary, with no merging of repeated
vertices and no zero check.

## The change (2e137d5)

`edgeShape(endpoints)` sorts each edge into one of three shapes:
- **ZeroBoundary:** no entries, a loop's (v, c), (v, -c), or coefficients that vanish in the field. The edge closes a
  cycle.
- **JoinsTwo:** c·(v - u) with u ≠ v and c a unit. It joins the two components, as before.
- **Irregular:** anything else, such as one vertex with a nonzero coefficient, three or more vertices, or coefficients
  that are not negatives of each other. An algebraic chain complex may contain these.

There is no allocation on the two-entry path; a listing longer than two is merged with `groupMapReduce`.

On the first Irregular edge, the union-find stops. Because it is the first thing `advanceAll` runs, it can clear what it
recorded (`cleared`, `paired`, `killer`, `killerOf`, `essentialSimplices`, `barcode`). The general reduction's loops then
run down to dimension 0 instead of 2, which is the pre-union-find algorithm. Finite degree-1 bars take the union-find
tree-path representative when there is one, otherwise the V-column (`vcolOf`).

## Correctness

The original union-find, per case of the new `PersistenceInChunksSpec` examples:

| case | original | robust |
|---|---|---|
| CW RP², loop listing no boundary | threw (fixed by 56a9a30) | correct |
| 2(v - u) over F_2 | threw (division by zero building a tree path through a zero-coefficient edge) | correct, matches naive |
| 2(v - u) over F_3 | correct | correct |
| 2(v - u) + disc over F_2 | threw | correct, matches naive |
| a + b + c over F_3 | wrong barcode, silently: merged a and b instead of killing c | correct, matches naive |
| 2a over F_3 | threw (index out of bounds) | correct |
| 2a over F_2 | threw | correct |

Each case asserts the hand value, agreement with the naive engine, and that the representatives are cycles present at
birth.

## Speed (A/B)

Method:
- Each run is one JVM process. The variants were compiled in a separate git worktree, so the main checkout and its sbt
  server stayed idle.
- The driver is `UnionFindEdgeDriver`. Its workload is materialized first, then run with `maxDim = 0`, so the general
  reduction has nothing to do.
- Each JVM does 2 warm-up runs and 5 timed runs, and reports the median. There are 3 JVMs per cell.
- The three variants:
  - **original**: 56a9a30~1;
  - **current**: 39c68e5 (an emptiness check);
  - **robust**: 2e137d5.

(results below)
