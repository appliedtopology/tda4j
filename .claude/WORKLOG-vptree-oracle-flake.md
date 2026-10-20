# VantagePointTreeOracleSpec failed on one build in sixteen: the oracle was wrong, ours was not

2026-10-20. Point-in-time record; later results go in an addendum.

## Trigger

CI failed `VantagePointTreeOracleSpec` ("Lattice points, with many exactly equal distances"):

```
query 39 radius 1.4142135623730951: jvptree List(31, 38, 39, 46, 47), ours List(30, 31, 38, 39, 46, 47)
```

Query 39 is the lattice point (4,7), point 30 is (3,6): their computed distance is `sqrt(2.0)`, the radius, bit for bit.
So the point is inside the inclusive radius and OURS was right; jvptree 0.3.0 lost it.

## Cause (verified)

jvptree builds with `new java.util.Random()` per node (`VPTreeNode.<init>`, no seed can be passed: the vantage point
is not a strategy), so every build of the same points is a different tree. `VPTreeNode.collectAllWithinDistance`
visits the nearer child iff `d(q, v) <= threshold + radius` and the farther iff `d(q, v) + radius > threshold`: no room
for rounding (bytecode, `javap -c`). On the lattice the points (0,3), (3,6), (4,7) are collinear, and in floating point
`sqrt(32) = 5.656854249492381 > sqrt(18) + sqrt(2) = 5.65685424949238`, by exactly one ulp. When (0,3) is the vantage
point and (3,6) the farthest of its nearer half (threshold `sqrt(18)`), the query at (4,7) with radius `sqrt(2)` skips
the half that holds (3,6).

Checked inside the trees (reflection on `rootNode`, `closer`, `farther`, `vantagePoint`, `threshold`): in **66 of 66**
builds that lost point 30 for query 39, the node pruning the child that holds it had vantage point 3 = (0,3), threshold
4.242640687119285, and `d(q,v)` exceeded `threshold + radius` by 1.0 ulp.

## Measurements (JVM, jvptree 0.3.0, the spec's 8 x 8 lattice: queries every third point, radii `sqrt(0..20)`)

- **Radius queries**, 2,000 builds: **121 builds (6.05%)** lose at least one point; **0** return a point outside the
  radius; in all 121 the lost point's computed distance equals the radius exactly. Six distinct (query, radius, point)
  triples, so the failing build depends on which vantage points the random draw picks. Query 39 / point 30 alone: 34 of
  2,000 builds.
- **Nearest neighbours**, same lattice, k in {1, 5, 20, 64}, 2,000 builds: short answers 1,451 of 176,000 queries (in
  1,154 builds, already known); **full-length answers with the wrong points: 43** (in 43 builds), all at k = 20, e.g.
  query 12, the 19th and 20th distances `3.0, 3.162` where the scan has `2.828, 2.828`; query 9, `5.0, 5.0` against
  `3.162, 3.162`. So the same spec had a second, independent flake of about 2% per run.
- **Where k-NN is reliable**: sweep of random clouds (n in {20, 40, 64, 100, 150, 300, 600}, dimension 1, 2, 3, 6, 40
  builds each, 8 random queries per build) plus the 8 x 8 and 4 x 4 x 4 lattices (800 builds each), k in {1, 2, 3, 5, n/20,
  n/12, n/10, n/8, n/5, n/3, n/2, n}: 261,120 queries, 4,101 short or wrong; none with k <= 5 (128,000 queries), none with
  k <= 16 (184,320), the smallest failing k was 20 (n = 40; also 21 at n = 64, 25 at n = 300). 16 is jvptree's default leaf capacity, which fits
  a result collector that prunes before it is full; that is a reading of the bytecode (`collectNearestNeighbors` prunes on
  the distance to `getFarthestPoint()` with no fullness test), not a patched build.
- **Example 1 of the spec** (random clouds, `Random(31)`), 500 builds, replicated outside the spec: 0 radius
  mismatches, 0 full-length k-NN mismatches. It was not a flake source.
- Our tree (deterministic: fixed xorshift seed) equals the linear scan on every query of the spec's examples, run after
  the change (the Java measurements above do not run it). With the pruning slack set to 0 the oracle spec STILL passed,
  so it never could have caught a regression in the slack; `VantagePointTreeSpec` failed only its synthetic
  noisy-distance example.

## What changed

- `VantagePointTreeOracleSpec` is judged by a linear scan. Ours must equal it on every radius and nearest-neighbour query
  (before, ours was only compared with jvptree, and the 6% and 2% disagreements were jvptree's). jvptree is held to the
  scan where it is right: no point outside the radius, no loss except points whose computed distance equals the radius,
  nearest-neighbour distances for k <= 5. Its defects (loss on the boundary, short and wrong k-NN answers for k > 5) are
  counted and printed by the last two examples, not asserted. Nothing about our tree is asserted less.
- `VantagePointTreeSpec` gets the discriminating example the oracle spec could not be: the four points (0,3), (1,3), (3,6),
  (4,7) in all 24 orders (so each is the vantage point of some tree, `leafSize = 3`), query (4,7), radius `sqrt(2)`, against
  the scan; it also asserts the ulp inequality. Mutation check: with `RelativeSlack = 0.0` it fails (six orders lose the
  point (3,6)); with `1e-12` it passes. Runs on both platforms (`Math.sqrt` is correctly rounded on Scala.js).
- `BUGS-IN-REFERENCES.md`: the radius defect and the wrong full-length k-NN answers.

## Left open

Not reported upstream. The cause of the wrong k-NN answers inside jvptree is a reading of the bytecode, not a patched build
(`BUGS-IN-REFERENCES.md`).
