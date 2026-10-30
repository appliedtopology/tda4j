# DQP alpha backend drops valid ill-conditioned simplices (2026-10-28/29)

Point-in-time snapshot. Trigger: Mikael's `--path top` sweep on `97939b1` (after PR #247): interior n = 40, d = 10, H_9:
Helix 82.6 s, `Auto` 78.3 s, GUDHI 79.8 s, fastalpha-top 84.6 s, DQP 156 s (7.3 GB). The question was why DQP is 2x Helix.

## Finding 1: DQP's barcode is wrong in high degrees
Local run (4 cores, same data regenerated: 40 uniform points in R^10, `/tmp` scratch only): Helix 46 s, DQP 80 s, DQP heap
9.4 GB against 4.6 GB. Compared with GUDHI's full barcode (`homology_coeff_field=17`, radii):
- Helix equals GUDHI to 1e-15 in every degree (bars longer than 1e-4: 40/360/1017/1209/654/182/36/6/0/0).
- DQP equals it in degrees 0 to 4 and 6 and has extra bars in the others: 1 in degree 5, 3 in degree 7, 1 in degree 8 and 15 in
  degree 9, which never die.
The earlier sweeps compared degrees up to 2 only, so none of them could see it.

Cause: 6.67 M cells at d = 10; DQP lacked 10 824 of them (d = 7, n = 60: 585 of 464 624). Every missing cell has all its
cofaces missing, and 519 of the d = 10 misses had all facets present (roots). The smallest missing value at d = 10 is
1.094 (the enclosing radius is 1.428), in dimension 5; at d = 7 all missing cells are above 6.6 (enclosing radius 1.21).
No cell is extra. Mechanism: the documented accepted limitation in `DualQP.solve`: a pivot with `s <= rankTolerance * beta`
and no blocking inequality, or after dropping one, makes the candidate infeasible. Valid simplices of large circumradius
have exactly such pivots, and there are more of them as the dimension grows.

## Why not just lower `rankTolerance`
Tried on the same data (cell counts against Helix within a radius). Within radius 1.6 at d = 10, 1e-8 and 1e-10 give
Helix's counts exactly in every dimension, 1e-12 adds cells. At d = 7, 1e-8 to 1e-10 are exact up to radius 200 and add or
lose a few cells beyond. But `WORKLOG-alpha-complex.md` item 3 found a poisoned factor at a pivot ratio of 1.03e-8 (R^4,
11 points): a lower tolerance alone is not safe.

## Change (`alpha/AlphaComplexDQP.scala`, `DualQP.solve`)
`solve` is now `attempt` at `rankTolerance`; when it returns infeasible only because of a small pivot (three exits are
flagged) and `rescueRankTolerance` (new setting, default `1e-11`, 0 = off) is smaller, `attempt` runs again at that
tolerance and the result is kept only if `certified`: multipliers of inequality constraints nonnegative, every active
constraint has no slack and no constraint is violated, all computed from the Gram matrix and U directly, not from the
factor, within `100 * equalityTolerance * scale`. Since the dual is concave these are sufficient for optimality, so a
poisoned factor cannot pass.

## Measurements
| | rescue off | rescue on |
|---|---|---|
| d = 7, n = 60, radius 10 / 200 | 26 and 227 cells short | exact |
| d = 7, n = 60, unbounded | 585 short | 1, 3, 3, 1 short (dims 4 to 7), none extra |
| d = 9, n = 30, radius 20 (3 seeds) | 106, 35, 48 short | 0, 0, 0; none extra |
| d = 10, n = 40, unbounded, bars (>1e-4) born <= 5 | extras from degree 5 | equal GUDHI in every degree |
| d = 10, n = 40, driver row at the enclosing radius, bars > 1e-9 | | equal GUDHI in every degree; DQP 37.0 s, Helix 45.3 s |
No slowdown (the second attempt is rare). Beyond a circumradius of about a hundred times the point spacing the values are
still approximate (relative error 1e-6 gives bars of 1e-4 at radius 100): d = 10 unbounded keeps extra bars in degrees 4 to
8 (1, 1, 5, 3, 1), all born above radius 5, which nothing built up to the enclosing radius reaches.

## Other findings
- Benchmark rows `backend=helix|dqp|bowyer-watson` were untruncated; the driver now stops them at the enclosing radius like
  the default row (`AlphaShapes.upToEnclosingRadius`). DQP bounded by 1.6 at d = 10 builds all dimensions in 26 s.
- `fastalpha-top` equals the default at d = 10 (84.6 s against 78.3 s): the triangulation walk is the floor, GUDHI is at
  the same 80 s, so the dual pass saves nothing there. Reading for `--path top`.
- JFR of unbounded DQP at d = 10 (after PR #247): 39% in `solveAtVertex` (QP 25%), 14% in `radiusOf` and about 20% in
  tree `Simplex` hashing and conversion in the cohomology engine's ordering.

## Open
- The remaining unbounded-DQP approximation (huge circumradius).
- Whether `Auto` should change: it already stays on the triangulation without a radius, and with a radius picks DQP
  from `prefersDQP`; with the enclosing radius DQP at d = 10 is faster than Helix's 46 s. Re-time on the server.
