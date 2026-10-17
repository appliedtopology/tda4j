# Scala.js: our own vantage-point tree, minimum enclosing ball and binomial (2026-10-17)

Point-in-time record of replacing three JVM-only Java libraries in shared code (`src/main/scala`) so that the Scala.js
row can compile it: `com.eatthepath:jvptree` (VP-tree), `com.dreizak:miniball` (minimum enclosing ball) and
`org.apache.commons:commons-numbers-combinatorics` (`BinomialCoefficient`). The libraries stay JVM compile dependencies
in `build.sbt` for now; the JVM-only oracle specs under `src/test/scalajvm` use them and go with them.
`grep -rn "com.eatthepath\|com.dreizak\|org.apache.commons.numbers" src/main` finds nothing.

## What was built

### `VantagePointTree[V]` (`streams/VantagePointTree.scala`)

`VantagePointTree(elements, distance, leafSize = 16)`; `withinDistance`/`indicesWithinDistance(query, radius)`,
`nearest`/`nearestIndices(query, k)`, `size`.

- Layout: struct-of-arrays, preorder nodes, an inner node's vantage point is the element in its first slot, the rest
  split by distance to it at the median (`select`, Wirth's quickselect, median-of-three pivot) into a nearer half
  (`ceil(rest / 2)` elements) and a farther half. `threshold` = the largest distance in the nearer half; **both halves can
  hold elements at exactly that distance**, so a query visits both when it ties. The split is by rank, never by value:
  the tree is balanced (depth `log2(n / leafSize)`) for any data, coincident points and all-equal distances included,
  with no `PartitionException` and no deep recursion (50,000 identical points and 50,000 collinear points are specs).
- Deterministic: vantage points come from a xorshift64 generator with a fixed seed, advanced in construction order.
  Same elements, same tree, on every platform.
- `withinDistance` is `distance(query, e) <= radius`, **inclusive**. jvptree's leaf test is the same (`dcmpg; ifgt` in
  `VPTreeNode.collectAllWithinDistance`, checked in the bytecode and by `VantagePointTreeOracleSpec`: a radius equal to
  the computed distance to a data point includes it). Result order: ascending index in `elements`. Negative or `NaN`
  radius: empty; infinite: everything.
- `nearest(query, k)`: **ties are broken by the order of `elements`** -- the result is exactly the first `k` of the
  stable sort of `elements` by distance, which is what `BruteForce` computes. A bounded max-heap on `(distance, index)`,
  subtrees pruned only on a STRICT inequality, so an element tied with the current `k`-th with a smaller index is found.
  `k > size` returns everything; `k < 0` throws `IllegalArgumentException`.
- Pruning compares with a relative slack of `1e-12` (`RelativeSlack * (d + radius + threshold)`) and is written
  `!(bound > ...)` so that a `NaN` or infinite bound visits the child. The slack matters for computed metrics: Euclidean
  distances break the triangle inequality in the last bits, and a point exactly on the boundary could be skipped. The
  slack is pinned by a spec (a distance with 1e-14 relative noise): removing it fails that spec, and nothing else.
- Needs a symmetric, non-negative metric; throws `IllegalArgumentException` if `distance` returns `NaN`. A dissimilarity
  that breaks the triangle inequality by more than the slack can make a query miss elements (use `BruteForce`; the
  comment in `VantagePointQuery` is kept).

`JVPTree` is now `VantagePointQuery[VertexT](metricSpace)` (a `SpatialQuery`, field `vpTree: VantagePointTree[VertexT]`).
No compatibility shim. `EuclideanMetricSpace.vpdf` is a Scala function `(Array[Double], Array[Double]) => Double`, `vpt` a
`VantagePointTree[Array[Double]]`, and `neighbors(qp, eps)` returns the indices (ascending) of every point within `eps`;
the old version mapped each found array back with `pts.indexOf`, i.e. the first reference-equal index, so a point
object listed twice came back once. Nothing in the repository called `vpt`/`vpdf`/`neighbors(qp, eps)`.

### `MinimumEnclosingBall` (`streams/MinimumEnclosingBall.scala`)

`MinimumEnclosingBall(points: PointCloud)` (an `into` type: `Array[Array[Double]]`, `Seq[Seq[Double]]` ... all convert)
gives `center`, `squaredRadius`, `radius`. Rejects an empty cloud, ragged rows and non-finite coordinates.

**Algorithm: Wolfe's active-set method on the dual problem, not Welzl.** The ball is the maximiser of
`f(l) = sum l_i |p_i|^2 - |sum l_i p_i|^2` over convex weights `l` (the minimum-norm-point problem of the lifted points).
State: a set `S` of affinely independent boundary points with weights `l > 0` and centre `c = sum l_i p_i`.
Round: take the point `p` farthest from `c`; if its squared distance is at most `r^2 (1 + 1e-13)` stop; else append `p` to `S`
and run the minor cycle:

1. Factor (Cholesky) the Gram matrix of `p_j - p_0` for the points of `S`. If the new point is independent, solve
   `2 G x = diag G` for the circumcentre inside `aff(S)` and its barycentric coordinates `mu`. All `mu >= -1e-12`: accept
   (`l = max(mu, 0)`, `c` = circumcentre).
2. Some `mu_i < 0`: move the weights from `l` toward `mu` until the first one reaches zero (ratio test), drop that
   point (and any other at weight `<= 1e-14`, never the new one), repeat step 1.
3. The new point is (numerically) in the affine hull of `S` (pivot below `1e-13` of its squared length; always the case
   with `d + 2` points): no circumsphere exists. Move weight along the affine dependency (which leaves `c` where it is and
   raises `f` at the rate `|p - c|^2 - r^2 > 0`) until one weight reaches zero, drop it, repeat step 1. If the rate is not
   positive, or the iteration cap (`50 (n + d) + 200` rounds, `4 cap + 8` minor steps) is hit, stop.

`f` never decreases, a minor cycle removes a point each pass, so each round terminates; a round costs one `O(nd)` scan
and `O(m^3)` for `m <= d + 1` boundary points. The result is **translated by the first point and rescaled by nothing**: no
absolute constant appears, every tolerance is relative, so rescaling by a power of two rescales `center` and
`squaredRadius` bit-exactly (a spec).

**The reported radius is the largest computed distance from the returned centre to a point** (in the original
coordinates), not the Gram-system radius. So the ball encloses its points in the arithmetic a caller would use to check
it, even when the iteration stops early on a degenerate input; the radius is the true minimum plus the rounding of the
centre (a spec pins `worst <= radius` exactly; the Gram radius, which agrees to ~1e-13, fails it).

Why not Welzl (the brief's first suggestion): the number of recursive calls of Welzl's algorithm on `m` boundary points
that are all needed (a regular simplex, or any near-spherical cloud in high dimension) is of the order of `2^m` ball
computations, so a regular simplex in `R^20` is out of reach. That is from the recursion `T(i, r) = sum_{j<i} T(j, r+1)`,
NOT measured here; no Welzl code was written. The active-set method solves the 21-vertex regular simplex (40 calls) in
the `MinimumEnclosingBallSpec` time budget of ten seconds with room to spare (the whole spec takes under two seconds).

`CechFiltration` keeps its structure: cached, clamped to the facets' maximum, the "reuse the facet's radius when a vertex
is strictly inside" shortcut (`distanceToCenter < raw * (1 - 1e-9)`) unchanged; `raw` is `ball.radius`.

### `SimplexIndexing.binomial` (`streams/SimplexIndexing.scala`)

Exact `Long`, no floating point and no `BigInt`: the running value after step `i` is `C(n - k + i, i)` with
`k := min(k, n - k)`; each step divides the common factor `g = gcd(result, i)` out before multiplying,
`result = (result / g) * ((n - k + i) / (i / g))`, and checks `result / g > Long.MaxValue / factor` first. The sequence
only grows, so it overflows exactly when `C(n, k)` does. `C(66, 33) = 7219428434016265740` fits, `C(67, 33)` throws
`IllegalArgumentException("binomial(67, 33) overflows Long -- this complex is too large to index")` (the old message; the
`ArithmeticException` cause is gone). `n < 0`, `k < 0`, `k > n` give 0 as before.
`SimplexIndexing.binomialInt(n, k)` (new, public) is `binomial` as an `Int` for `0 until C(n, k)`; the three call sites in
`SimplexStream.scala` used `BinomialCoefficient.value(n, d).toInt`, which wrapped silently past `Int.MaxValue`; it now
throws `IllegalArgumentException`.

## Tests

Shared (`src/test/scala/.../streams`, will run on JS): `VantagePointTreeSpec` (16 examples), `MinimumEnclosingBallSpec`
(20), `BinomialSpec` (10); `MetricSpaceSpec` now compares `VantagePointQuery` with `BruteForce` on the neighbours
themselves (not only their distances). JVM only (`src/test/scalajvm/.../streams`): `VantagePointTreeOracleSpec`,
`MinimumEnclosingBallOracleSpec` (balls and Cech radii), `BinomialOracleSpec`. Written before the implementations; the
first compile of the specs was red.

Mutation check (each mutation applied to the source, the shared specs run, the source restored): leaf test `<=` -> `<`
(11 failures), tie-break reversed (9), pruning on equality (9), no pruning slack (**0 at first**, then 1 after adding the
noisy-metric example), no affine exchange (4), termination tolerance 1e-6 (1), dependence tolerance 1e-3 (1), Gram radius
instead of the largest distance (**0 at first**, then 1 after making the containment check exact), binomial without the
gcd step (7). The two survivors were fixed in the specs, not in the code.

### Measured agreement with the libraries (`println`ed by the oracle specs)

- Ball vs miniball, 1,300 clouds (1-30 points, dimension 1-20; uniform, 2-8 points in dimension 2-5, integer lattice with
  repeats): worst relative radius difference **4.0e-15**, worst centre distance / radius **5.8e-15**. Asserted: 1e-12 and 1e-11
  (the brief allowed 1e-10 for the radius), about 250 and 2,000 times the measured maximum.
- Cech filtration values, `CechFiltration` vs the old miniball code with the same clamp and shortcut, every simplex of
  36 random clouds (n = 7-10 points, dimension 2-8, a third on an integer lattice), **17,976 simplices**: largest relative
  difference **4.9e-15** (asserted 1e-12). No value moved beyond rounding.
- Neighbour sets for radius queries: identical to jvptree on random clouds (1-600 points, dimension 1-6), on an integer lattice
  at radii that are lattice distances, and at radii equal to a computed distance. k-NN distances identical wherever jvptree
  returned `k` points.
- Binomial: identical value, or overflow on identical inputs, for every `n <= 140`, `k` in `[-2, n + 2]`, and 20,000 random
  `(n, k)` with `n` up to `Int.MaxValue`.

### Differences from the libraries

- **jvptree's `getNearestNeighbors(q, k)` can return fewer than `k` points** (its vantage points are random: 17 of 100
  builds for the points `0..63` on a line, query 21, `k = 64`). On random clouds of 5-300 points it did so for 67 of 5,736
  queries with `k < n/2` (smallest `k/n` seen 0.247), 135/2,880 with `n/2 <= k < 3n/4`, 292/2,880 below `n-1`, 468/2,880
  at each of `k = n-1` and `k = n`; never for `k = 1`; `k` small against a large `n` was not tested.
  `DistanceToMeasure(.., JVPTree(..), k)` and `AlphaComplexDQP.dtm` (built on `JVPTree`) were affected for such `k`;
  `BruteForce`, the default of `DistanceToMeasure`, was not. Ours returns `k`. Entry in `BUGS-IN-REFERENCES.md`.
- k-NN tie order: jvptree's is undefined; ours is the order of `elements` (so equal to `BruteForce`).
- Radius-query result order: ascending index (jvptree: tree order); `VantagePointQuery.neighbors` still returns a `Set`.
- The ball's radius is the largest distance to the returned centre (`>=` the true radius by rounding); miniball reported its
  own system's radius. Differences are below 5e-15 in every measurement.

## Performance (unconfirmed: one JVM, a machine shared with another agent's sbt, no A/B isolation)

50,000 random points, 5,000 queries, `leafSize` 16: k = 15 nearest neighbours in dimension 2: 17 ms vs jvptree 80-114 ms; dimension 3: 35 vs
75-88 ms; dimension 8: 1.2-1.9 s vs 2.5 s (all noisy); radius 0.05: 30-160 ms vs 33-200 ms. Build 10-13 ms vs 16-55 ms.
Leaf sizes 8-32 are within noise of each other; 16 is the default. Ball, 20,000 clouds: 3 points in R^2 27 ms vs miniball
11.5 ms, 4-5 points in R^3 20 ms vs 8-10 ms, 8 points in R^5 36 ms vs 22 ms, 21 points in R^20 343 ms vs 303 ms: about
1 microsecond per call on the small clouds that Cech simplices are, 1.3-2.5x slower than miniball there. Not measured: a
whole Cech persistence run.

## Where the old names still appear (not edited: the brief excludes `_docs`/`CLAUDE.md`, or they follow a released library)

- `_docs/developers-guide/architecture.md` lines 364, 367 and 463 (Miniball), `class-diagrams.md` lines 254 and 256 (`JVPTree`).
- `_assets/presentations/tutorial-2026-10-16/index.md` line 247 and `index.html` line 1093 (`JVPTree`).
- `demo/examples/patches/{patches.scala:23-24, README.md:21, experiments/pipeline50k.scala:5,8, experiments/rips_sub.scala:3,35}`
  (`JVPTree`): the demo is its own build against the last RELEASE of tda4j, so renaming there would break it until it
  moves to 0.7.0; rename then.
- `build.sbt` lines 144-147 and 182 (the three dependencies, the assembly comment), `.claude/scripts/install-sbt.sh:63`
  (a comment), `.claude/DESIGN-package-structure.md` lines 52 and 156, `.claude/PLAN-paper.md` (history).
- Test comments in `CechStreamSpec`/`CechParallelProfileDriver` that say "Miniball" (history), `rules/alpha.md:25`
  ("Miniball-Delaunay", an unrelated ripped-out backend).

## Build observations (the lead's)

- `sbt testFull` at the root compiles the 109 sources a second time into `tda4j-root` (the root `project in file(".")` has
  the default source directories) without the dependencies, and fails on `cats`. `sbt tda4j/testFull` is the working form.
- `sbt tda4jJS/compile` still has 79 errors (typer), none in a file changed here: `plot/Viewer`, `alpha/AlphaShapes`,
  `plot/Plot`, `barcode/*`, `matlab/*`, `io/Endpoints`, `alpha/BowyerWatson`, `homology/{Cohomology,CircularCoordinates}`,
  `sset/CollapsedNerve`. Because the typer stopped, Scala.js-specific later phases never ran over the new files. They use
  only `java.util.Arrays.copyOf`/`sort` on primitive arrays, `scala.math` and `scala.collection.immutable.ArraySeq`, all of
  which Scala.js provides; the shared specs use `System.nanoTime`, `Integer.bitCount`, `Integer.numberOfTrailingZeros`,
  `scala.util.Random` and `BigInt`, also provided. **Not run on JS.**
- Every pre-commit run wants `/root/.cache/tda4j/scalafmt-3.11.1/scalafmt <files>` before `git add`.

## Open

- Delete the three JVM dependencies and the three `*OracleSpec` files together (or keep the dependencies test-scoped).
- Put the batch of old-name references above right when `_docs` is next edited.
