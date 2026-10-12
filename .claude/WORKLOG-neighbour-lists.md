# WORKLOG: neighbour lists in the packed Ripser engine (2026-10-06)

Project lead asked for ripser.cpp's sparse path after the first full harness run: on clifford50000 (`--dim 2
--threshold .15`) the engine, once it no longer crashed at startup (commit bd2c9f2: distance cache sized `n*n` in `Int`,
and the verb collected all pairwise distances), spent its time in `cofacetDiameter` under `zeroApparentCofacet`: every
cofacet enumeration tried all 50,000 vertices.

## What changed

- `NeighbourLists.within(n, threshold, maxEntries)(distance)` (`streams/SimplexIndexing.scala`): one pass over the pairs
  `i < j`, keeping those within the threshold with the distance the engine's own `distance(i, j)` returns; laid out as
  compressed rows, ascending per vertex. `None` past `maxEntries` directed pairs (capped at `Int.MaxValue`).
- `SimplexIndexing.SparseCofacetCursor`: intersects the simplex's vertices' lists from the top down, and computes each
  cofacet's index with exactly `CofacetCursor`'s `iB`/`iA` arithmetic. Same cofacets, same order (vertex and index
  strictly decreasing), same index; `maxDistance` from the stored distances, so diameters are bit-identical.
- `PackedRipserCohomologyEngine`: one inline `eachCofacet` that `forEachCofacet`, `zeroPivotCofacet` and
  `sparseCofacets` go through, on the lists when present. New constructor option `neighbourLists: Option[Boolean]`;
  default: build them when at most a quarter of ordered pairs (and at most 2^28 entries) are within the threshold.
- First version built the lists in two passes (count, then fill), each computing every distance: 46 s on clifford50000.
  Now one pass.

## Gate and mutation checks

`NeighbourListsSpec`: lists vs all-vertex scan, term for term (`pairedCohomology`, `persistentCohomology`,
`persistentHomology(true)`, apparent-pair count), 10 seeds × 4 spaces (random cloud, integer grid, a skewed metric not
symmetric by construction, an explicit matrix with unequal triangles) × thresholds 0.25 / 0.5 / enclosing radius ×
F_3 / F_17 × apparent pairs on/off. Mutations:
- dropping `iA` from the cofacet index: fails (the heap reduction never converges, OOM);
- every position off by one (`k + 1`): **passes**, and correctly so: it negates every coboundary uniformly, which cancels
  out of pivots, reduction ratios and representatives. Not a test gap;
- position always 0 (non-uniform sign error): fails.

## clifford50000 (sandbox, 4 cores, 15 GB)

Counts at threshold 0.15: 2,242,206 edges, 39,337,615 triangles, 412,979,074 tetrahedra. With the lists the run got
past the cofacet scan and ran 9 minutes before `OutOfMemoryError: GC overhead limit exceeded` at `-Xmx12g`. Not
measured further here. The remaining cost is storage, not the lists: each degree's simplices are a `Seq` of boxed
`DiameterIndex` (and a sorted copy), the cleared sets are `mutable.Set[Long]` (boxed). ripser.cpp keeps the same as
primitive (diameter, index) pairs. Whether a 32 GB+ heap is enough on the lead's machine is unverified.

## A/B: lists on vs off (forked JVM per run, -Xms8g -Xmx8g ParallelGC, F_2, cocycles reported, median of 3)

| case | density | auto | on | off |
|---|---|---|---|---|
| sphere3_192, enclosing radius, dim 2 | 0.974 | scan, 4.73 s | 4.41 s | 4.97 s |
| o3_1024, threshold 1.8, dim 3 | 0.063 | lists, 9.25 s | 8.75 s | 26.57 s |
| unif3d 2000, 0.29, dim 1 | 0.072 | | 0.88 s | 2.12 s |
| unif3d 2000, 0.36, dim 1 | 0.124 | | 1.46 s | 2.79 s |
| unif3d 2000, 0.42, dim 1 | 0.181 | | 2.09 s | 3.31 s |

The lists were never slower, even at 97% density, so the quarter cutoff is a memory guard (12 bytes per directed pair
against 8 per pair for the dense distance cache), not a speed tradeoff. o3_1024 at 1.8 (a Ripser-paper case) is about
3x faster; its earlier ~30 s (`WORKLOG-vr-working-column.md`) was the all-vertex scan. Sandbox numbers: not citable.

## Next (not done; ask first)

Each degree's simplices as parallel `Double`/`Long` arrays sorted in place, and a primitive long set for the cleared
indices, gated by `PackedWorkingColumnSpec` and `NeighbourListsSpec`. Building the lists is still O(n²) distance
evaluations (about 23 s at 50,000 points); a spatial grid for low-dimensional point clouds would avoid that.
