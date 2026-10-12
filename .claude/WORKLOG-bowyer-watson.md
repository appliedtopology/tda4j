# WORKLOG: a low-dimensional alpha engine (Bowyer–Watson with exact predicates)

2026-10-04. Branch `ccr-66e09665-8g203t` (restarted on `scala` after PR #40 merged). Goal (project lead): "a fast alpha
for 2d, 3d"; how high can it go?

## Scope decision

- Generic Bowyer–Watson, any dimension in principle, with the predicates as the limiting factor: determinants
  expanded over permutations are `n!` terms (in-sphere in 4-D is 5x5, 120 terms; 5-D would be 720 per evaluation and
  the perturbed tie-break 7! per cofactor). Capped at 4-D; above it Helix (any dimension) and DQP (with a radius).
- An earlier claim of mine that Helix would be better at 5-D+ was unmeasured; it is moot under the cap (BW refuses
  5-D), not confirmed.
- Exact predicates (project lead: "distinctly out of scope for the library in general, but happy to look at
  efforts"): float filter with a conservative bound, then `BigDecimal` (a double is a binary fraction, so products
  and sums are exact there). No Shewchuk adaptive expansion port: the float filter decides almost everything on
  ordinary input and the fallback only has to be right, not fast.

## Design (as built)

- `DelaunayPredicates` (commit d259d6f): orientation and translated in-sphere determinants; error bound
  `2 (n·entryError + n + N) u · Σ|terms|` (generous; `DelaunayPredicatesSpec` fails when the bound is set to 0).
  "Inside" sign calibrated once on the standard simplex + centroid (lazy, helper instance uses only uncalibrated
  methods: an eager calibration recursed). Exact ties: symbolic perturbation of the lifted coordinate, rows in
  descending point index, first non-zero cofactor decides. Checked: permuting a cell flips orientation and in-sphere
  together (ties included); of four cocircular points exactly one diagonal is Delaunay.
- `BowyerWatsonTriangulation`: flat arrays, symbolic infinite vertex (index n; replacing it by a point beyond the hull
  facet gives positive orientation), conflict test for infinite cells = orientation > 0, or 0 and the finite
  neighbour's perturbed in-sphere positive. BRIO order, visibility walk from the last cell (with a step guard that
  throws rather than loops). Ridge matching packs the ridge into a Long.
- Repeated points: deduped BEFORE insertion, keeping the smallest index (first version detected them during the
  walk, which made the kept index depend on the insertion order; and its "duplicate" sentinel doubled as the walk's
  loop condition, so the walk never ended).
- `DelaunayAlphaShapes` (commit 2b86649) is the shared base for Helix and BW.

## Numerics found on the way

- The base's circumsphere solved the Gram system `DᵀD`, squaring the condition number: on a 4-D sliver (r = 3240) the
  radius was off by 5e-5 against a 60-digit reference. QR of `D` itself: 1e-12. With that, BW and Helix agree to 5e-10
  on random clouds (before: apparent "disagreements" that were the base's error, not either triangulation).

## Speed (uniform clouds in the unit cube, one sbt JVM, median of 3)

Triangulation alone: 1000 2-D 0.02 s, 10000 2-D 0.09 s, 5000 3-D 0.2 s.

The base's alpha-value stage then dominated (5000 3-D: faces 0.5 s, values 1.0 s, sort 0.8 s, all on `Set`-keyed
maps and commons-math QR per simplex). Rewritten on flat arrays (face table per dimension with open addressing,
cofaces linked while enumerating, hand-rolled Householder, sort on (value, simplex) pairs that only falls through to the
full ordering on equal values): whole alpha complex 5000 3-D ~2.4 s -> ~0.6 s. Occasional 1.5 s runs in the same JVM
look like GC (not investigated).

Whole alpha complex (`simplicesSortedMap` forced), BW vs Helix, both on the new base:

| d | n=1000 | n=3000 | n=10000 |
|---|---|---|---|
| 2 | 31 vs 249 ms | 74 vs 1125 | 445 vs 10427 |
| 3 | 99 vs 579 | 364 vs 3990 | 1718 vs (not run) |
| 4 | 1116 vs 6803 | 4095 vs 33954 | 16695 vs (not run) |

Per point: BW ≈ `b_d (n/1000)^0.2` ms, b = 0.03, 0.1, 1.1 (d = 2, 3, 4) -- the fit used by `prefersDQP` now. The 4-D
jump (10x over 3-D) is mostly simplex count (a 4-D Delaunay has several times more cells and faces per point).
GUDHI does 1000 3-D in ~0.07 s (earlier harness run); BW at 0.1 s is in the same range, not compared head to head in
this session.

## Dispatch

`Default`: BW when the points are at most 4 coordinates wide (rank-deficient wider data goes to Helix, which projects
too), Helix above; with a radius, DQP when `prefersDQP` says so against the BW cost (in 2-D that is k ≲ 3.5 neighbours
within 2r, i.e. almost never). `fast-alpha` takes either triangulation; BW guarantees its facet precondition.

## Tests

`BowyerWatsonSpec` (invariants after every insertion; = Helix in general position within 1e-9; identical
triangulations across insertion seeds on 2-D/3-D/4-D grids and small-integer clouds; brute-force empty spheres;
repeats; collinear input in its 1-D span; the 4-D cap), `AlphaDispatchSpec` (three-way agreement with a radius; the
default choice), `FastAlphaHomologySpec` (fast-alpha on BW = naive, grids included).

## Before the PR: small inputs and a last-bit tie

- `Default` routes tiny inputs to BW now, so probed (empty, 1 point, 5 copies of one point, 2 and 3 points in 3-D):
  BW threw on 1 point (`None.get`, the affine projection) and gave 5 infinite H0 bars for 5 copies (the base only
  emitted dimensions up to the affine rank 0, so the value-0 duplicate edges were dropped). Both fixed; Helix throws
  on 0/1/5-copies inputs too (unchanged).
- The CLI's square fixture (4 cocircular points + 2): Helix 10 bars, BW 9. Not a triangulation error: the two pick
  different diagonals, and Helix's diagonal had a Thales-tied triangle, its own radius and the triangle's radius
  differing by 2e-16, a bar of that length. Rule now in the base: a Gabriel value within relative 1e-12 of the
  smallest coface value IS that value (same sphere). Both backends give the same 9 bars.
