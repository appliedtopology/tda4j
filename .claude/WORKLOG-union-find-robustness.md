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

Each cell lists the per-JVM medians in ms, then the median of those three:

| workload | original | current | robust |
|---|---|---|---|
| tree, 200k edges, no cycles | 2532, 2508, 2482 → 2508 | 2546, 2686, 2707 → 2686 | 2576, 2561, 2494 → 2561 |
| VR, 500 points, 93,642 edges | 4760, 4716, 4738 → 4738 | 5040, 4813, 4904 → 4904 | 4989, 4856, 4810 → 4856 |

**No measurable effect.** The spread within each cell (±100–250 ms) covers the differences between cells. The ordering
is not even monotone: `current`, which only adds an emptiness check, came out slowest on both workloads. A first,
interrupted run of the original variant in the main checkout gave 2470–2604 ms (tree) and 4832 ms (VR), the same
range. The edge-shape check is a few field operations per edge, far below everything else this path does.

**Conclusion on robustness: it improves things and costs nothing measurable.** Six input shapes that threw or silently
gave a wrong barcode now give the naive engine's answer, and speed is unchanged within ±5%. Keep it.

## Finding: representative bookkeeping, not union-find, dominates this path

Even the cycle-free tree takes about 2.5 s for 200k edges, while union-find itself is milliseconds at that size.
800 VR points (228k edges) took 15 s per run. The union-find pass builds representatives eagerly:
- a root-to-vertex path chain for every vertex (`pathFromRoot`);
- a cycle chain for every cycle-closing edge (about 227k of them at 800 points).

`diagramAt` never needs these, and with `maxDim = 0` they are never reported at all. A probe (below) measures their
share.

**The probe.** A copy of the robust variant with the eager degree-1 representative construction cut out: no
`pathFromRoot`, no cycle-edge chains. This is a timing probe only, not a valid implementation: on the union-find path
`vcolOf` cannot rebuild these cycles, because union-find never fills `boundaries`. Same driver, 3 JVMs:

| workload | robust (eager representatives) | probe (none) |
|---|---|---|
| tree, 200k edges | 2561 ms | 1351, 1581, 1232 → 1351 ms |
| VR, 500 points | 4856 ms | 134, 145, 181 → 145 ms |

On VR, 97% of the edge pass is spent building representatives that `diagramAt` never reads; the cycle-edge chains alone
make it about 33× slower. On the tree, half of the time is `pathFromRoot`. The rest is barcode bookkeeping for 200k bars.

**Next step:** build these representatives lazily, only when `barcodeAt` asks for a degree-1 representative, memoized and
with the same construction. Measure `diagramAt`, `barcodeAt`, and a `maxDim = 1` run end to end before deciding to keep
it.

## Addendum (same day, later): lazy degree-1 representatives (8ef85c6), measured

**The change.** `unionFindDim01` now keeps the spanning forest: `forestVertices`, `forestParent`, and `treeEdges`, which
moved from locals to fields. `pathFromRoot` is a `lazy val` with the same BFS as before. `degreeOneRepresentative(edge)`
memoizes into `essentialRepresentatives`:
- `treePathCycle(edge)`, the edge minus the tree paths to its ends (or the edge itself when its boundary is zero);
- `vcolOf(edge)` after union-find's fallback.

`barcodeAt` uses it for finite degree-1 pivots and for essential degree-1 cells. Union-find itself now records only
degree-0 representatives. The construction is unchanged, so the representatives are the same chains. 170 engine and
sset specs pass, including the irregular-edge examples and the involution and query-contract specs.

**The A/B.**
- The two jar sets are robust (2e137d5) and lazy (8ef85c6). They were built in the worktree and copied apart, so the JVM
  runs could be interleaved, robust then lazy, workload by workload.
- The driver is `UnionFindEdgeDriver`, extended with two arguments: `query` (`diagram` or `barcode`) and `maxDim`.
- Each JVM does 2 warm-ups and 5 timed runs and reports the median; there are 3 JVMs per cell.

Each cell lists the per-JVM medians in ms, then the median of those three:

| workload | robust (eager) | lazy |
|---|---|---|
| tree, 200k edges, `diagramAt`, maxDim 0 | 2572, 2565, 2635 → 2572 | 1531, 1630, 1430 → 1531 |
| VR, 500 points, `diagramAt`, maxDim 0 | 4702, 4838, 4789 → 4789 | 153, 177, 157 → 157 |
| VR, 500 points, `barcodeAt`, maxDim 0 | 4737, 4956, 4769 → 4769 | 153, 151, 145 → 151 |
| VR, 50 points, `barcodeAt`, maxDim 1 (13,728 cells, 60 bars) | 1823, 1627, 1628 → 1628 | 1614, 1663, 1785 → 1663 |

**Memory.** VR on 800 points (228,028 edges), `diagramAt`, maxDim 0, one cold run per heap size:

| heap | robust | lazy |
|---|---|---|
| 256 MB | `OutOfMemoryError` | 675 ms |
| 512 MB | `OutOfMemoryError` | 1014 ms (an sbt lint check was running at the same time) |
| 1 GB | 16,355 ms | 667 ms |

At 800 points the eager variant needs more than 512 MB just for representatives that `diagramAt` never reads. At 1 GB
it is 25× slower than lazy, against 30× at 500 points. These are single cold runs, so the times are rough; the
out-of-memory rows are the discriminating result.

**Where it does not help.** On Vietoris-Rips with `maxDim = 1`, the general reduction of the triangles dominates:
- 50 points take about 1.6 s and 70 points about 13 s, for `diagramAt` and `barcodeAt` alike.
- The eager edge pass on 1–2k edges is milliseconds of that.

When every degree-1 bar is reported (with `includeZeroLength`), lazy does the same work as eager plus one memo lookup per
bar. There is no measured regression in any case.

**Conclusion: keep it.** It helps exactly where union-find is the whole job: `maxDim = 0` (single linkage), `diagramAt`,
and large edge counts with few reported degree-1 bars. There, it takes off about 30× on VR, 1.7× on a tree, and the
memory that made 800 points fail at 512 MB. Everywhere else it is neutral. The representatives are the same chains.

**Noticed, not pursued:** with `maxDim = 1`, chunks takes 13 s on 70 VR points (40,517 cells), which is superlinear from
50 points (13,728 cells, 1.6 s). CLAUDE.md already records that Chunks and Naive reduce every top cell. `Auto` never
picks chunks for VR (it picks Ripser), so this matters only to callers who choose chunks.

The worktree used for the A/B was removed afterwards.

**Spec for the lazy path** (advisor review: no existing spec built many tree-path representatives over a signed field):
- `PersistenceInChunksSpec` now has "Degree-1 representatives built on demand from union-find's spanning forest are
  cycles present at birth". It runs seeded VR clouds of 6, 12 and 20 points over F_17, at `maxDim = 1`, with zero-length
  bars included, so every cycle-closing edge's representative is built.
- It asserts more than 100 degree-1 bars in total. Each one's representative must be nonzero, have zero boundary, and
  consist of cells present at birth. The full diagram must equal the naive engine's.
- **Mutation:** flipping the sign of the second tree path in `treePathCycle` (`- c1·path(v1)` to `+`) fails this example
  and none of the other 15 in the spec. The source was then restored.

A grep found nothing else reading `essentialRepresentatives`: it is a constructor parameter of `HomologyState`, read only
by `barcodeAt` and `degreeOneRepresentative`. `vcolOf` never reads it. The claim that the representatives are the same
chains as before follows from the unchanged construction; it was not compared cell for cell.

`sbt clean testFull`: 1052 tests, 0 failed, 11 skipped (the gated benchmarks); `plot` 18 tests, 0 failed.
