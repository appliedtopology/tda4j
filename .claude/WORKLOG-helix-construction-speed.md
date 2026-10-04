# WORKLOG: Helix construction speed (lever 2), timeboxed (2026-10-04)

Lever 2 of the paper-benchmark plan. Timeboxed per the advisor: measure DQP first, then report rather than rewrite.

## DQP vs Helix (`PaperBenchmarkDriver task=alpha`, `-Xms8g -Xmx8g`, ParallelGC)

| input | Helix | DQP |
|---|---|---|
| unif2d_1000 (degrees 0-1) | 2.5 s (warm) | 252 s |
| unif3d_1000 (degrees 0-2) | 13.6 s (warm) | not finished; stopped, 2-D already settles it |

DQP is ~100x slower in 2-D, so Helix stays the default and is the thing to speed up. (2-D: Helix 921 H₁ bars, DQP
920; `HelixDqpAgreementSpec` ignores bars shorter than 1e-7, so this is presumably a near-zero bar. Not chased.)

## Where Helix's time goes (unif3d_1000)

Throwaway phase timer: triangulation 8-10 s; alpha values 0.01 s; homology (cohomology engine, 27,531 simplices) ~0.4 s.
So the frontier walk (`HelixDelaunayBuilder.compute`) is everything. JFR sampled badly in this sandbox (281-614
samples per 10-17 s run), but its leaves were `RedBlackTree` equality iterators (`SortedSet ==`), `TimSort`, and
immutable `HashSetBuilder`.

## Changes (output identical)

1. `validated.exists(ds => new.simplex == ds.simplex)` scanned every accepted simplex with a `SortedSet` comparison,
   per frontier case: replaced by a `HashSet[Simplex[Int]]` kept beside `validated` (`accept`).
2. `addFrontierCase` ran `frontierCases.removeIf(facet ==)` over the whole queue: replaced by a facet index
   (`queuedByFacet`) and lazy cancellation (cancelled cases marked by identity, skipped when taken). Same semantics:
   a facet already queued is cancelled instead of re-queued, the order of the others is unchanged. The cospherical
   branch's subset `removeIf` goes through the same index. The main loop takes the next case only AFTER processing
   the current one (processing can cancel queued cases; prefetching was a bug caught in review of my own diff before
   running).

Identity check: a throwaway dump of every simplex and its alpha value, HEAD vs new, on a 6x6 grid, a 4x4x4 grid, 12
cospherical points plus centre, and 20 random clouds (2-D and 3-D), seeds 0 and 3 each: byte-identical (21,802
lines). Alpha/Helix specs and `FastRepresentativesSpec` pass.

Same-session A/B, 1 warm-up + 5 trials, medians: unif3d_1000 14.13 → 9.95 s (1.4x, trials do not overlap);
unif2d_1000 2.34 → 2.05 s (1.14x).

## Tried, no effect, reverted

Allocation-free `isLight`/`contains`/distance on cached coordinate arrays (bit-identical arithmetic): triangulation
8.0 → 7.85 s, noise. The per-point vector allocations are not the cost.

## Not done (outside the timebox)

- Each frontier case builds `points.indices.toSet -- facet` (an immutable HashSet of n Ints) and fully sorts the
  light points by distance, then tests candidates in that order, each with an O(n) empty-sphere scan. A lazy
  selection (heap) plus a spatial index for the empty-sphere test is the real fix, but the HashSet's iteration
  order is the tie-break among equidistant candidates: changing it can change which valid tiling Helix produces on
  cospherical input (grids). That is a behaviour change to decide deliberately, not a speedup to slip in.
- The walk is inherently O(frontier × n); against GUDHI/CGAL (milliseconds for 1000 points) Helix stays orders of
  magnitude slower. For the paper, alpha speed is not a claim to make; exactness and high ambient dimension are.
