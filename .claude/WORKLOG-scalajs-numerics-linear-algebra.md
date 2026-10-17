# Scala.js numerics: the linear algebra and erf that commons-math3 gave us (2026-10-17)

Part of the Scala.js cross-build (`DESIGN-scalajs-crossbuild.md`): commons-math3 has no Scala.js build and Scala.js
cannot compile Java sources, so every use of it in shared code (`src/main/scala`) was replaced by pure Scala. This is
the linear-algebra and erf part (the VP-tree, minimum enclosing ball and binomial part is in a different worktree).
commons-math3 stays a JVM compile dependency for now (`build.sbt`, the lead's), and is the JVM-only test oracle.

## What was built (`src/main/scala/.../algebra/`)

- `DenseVector`: immutable `Array[Double]` value; equality and `hashCode` on the entries (`0.0 == -0.0`, `NaN`
  equals itself), because it sits inside the case classes `Hyperplane`/`Hypersphere`, which are keys of hash sets
  (`DelaunaySimplex`). `final`, with the `//` reason (value type). `+ - unary_- * dot norm distance`, `fromArray` copies.
  `norm`/`dot`/`distance` sum in the same order as commons' `ArrayRealVector`.
- `DenseMatrix`: immutable row-major value (`apply`, `row`, `column`, `transpose`, `*` by matrix and vector, `-`,
  `frobeniusNorm`, `toArray`), `svd` and `lu(singularityThreshold = 1e-11)`.
  - `LuDecomposition`: Crout arrangement, partial pivoting, singular iff a pivot is below the threshold (or exactly 0);
    `isNonSingular`, `solve`. Same operations in the same order as commons' `LUDecomposition`.
  - `SingularValueDecomposition`: compact (`p = min(m, n)`, descending, `u` m x p, `v` n x p) by one-sided Jacobi
    (Hestenes); a wide matrix is decomposed through its transpose and `u`/`v` swap. A zero (or below-cutoff) singular value
    gets a column completed to an orthonormal set from the standard basis (two Gram-Schmidt passes). `rank`, `tolerance =
    max(m, n) s_1 2^-52` and `solve` = `V S^+ U^T b` with that cutoff, as commons. Stops when every column pair has
    `|w_p . w_q| <= sqrt(m) 2^-52 |w_p| |w_q|`, at most 60 sweeps.
- `ConjugateGradient.solve(operator, b, maxIterations = 1000, tolerance = 1e-10, check = true, initial)`: the textbook
  recurrence in commons' operation order. Stops at `|r| <= tolerance |b|`; `NotConvergedException` after
  `maxIterations`; with `check`, `NotSymmetricPositiveDefiniteException` for `<p, A p> <= 0` or a failed symmetry probe.
- `Erf.erf`, `Erf.erfc`: W. J. Cody's rational Chebyshev approximations (Math. Comp. 23, 1969), the coefficients
  re-typed from the paper and validated first in a Python port against `math.erf`/`math.erfc` (the C library).
  `erf` odd exactly, `+-1` from `|x| = 6`; `erfc` 0 from 26.543 (Cody's XBIG).

New public top-level names `DenseVector`, `DenseMatrix`, `ConjugateGradient`, `Erf` (re-exported by `TDAlab`, block
regenerated; scalafmt moves the `∆` back to the front of the block as before). The decompositions are nested in
`DenseMatrix` (rule 3 of the openness principle: `SingularValueDecomposition`/`LuDecomposition` are not top-level names).
`DenseVector`/`DenseMatrix` themselves are generic words: a user who has their own `DenseVector` in a different file of
their package (or imports breeze's) is bitten by the flat-namespace hazard; the names are the ones the brief asked for.

## Call sites switched

- `AlphaShapes`: `type Point = DenseVector`; `Hyperplane`, `Hypersphere`, `facetCentre` (LU, threshold 1e-14),
  `rankAtEpsilon`, `projectToAffineRank` (`svd.v`), the pick of affinely independent points. The frontier queue is a
  `mutable.ArrayDeque` instead of a `LinkedBlockingDeque` (single-threaded; `rules/scalajs.md` forbids the blocking
  queues in shared code).
- `BowyerWatson`: the rank test of the initial simplex.
- `CircularCoordinates`: the harmonic smoothing solves with `ConjugateGradient.solve(..., check = true)`.
- `Barcode` (the persistence-module algebra: `isMap`, `imageMatrix`, `image`, `kernel`, `cokernelMatrix`, `cokernel`,
  `reduceMatrix`, `pivotsOf`) takes and returns `DenseMatrix`. `BarcodeSpec` builds its matrices with `DenseMatrix`.
- `Vectorization.normalCdf`: `Erf.erf`.
- `grep -rn "org.apache.commons.math3" src/main` is empty.

## Measurements (all on this machine; JVM 21, fixed seeds)

Against commons-math3 (`DenseLinearAlgebraOracleSpec`, JVM only):

| what | result |
|---|---|
| singular values, 50 Gaussian matrices per shape, 42 shapes up to 30x30 and 50x3 | max `|ds| / s_1` = 5.7e-15; max relative difference of any single value 5.8e-13 (the small ones: commons' are the less accurate) |
| our SVD alone | `|A - U S V^T| / max(1, |A|)` <= 6.4e-15; `max |U^T U - I|`, `max |V^T V - I|` <= 1.1e-14 |
| `svd.solve` against commons' solver, 700 least-squares systems | max relative difference 4.7e-14 |
| `lu.solve`, 1200 systems of order 1..12, threshold 1e-14 | bit for bit equal, and the same `isNonSingular` |
| `ConjugateGradient`, 350 SPD systems of order 2..40 | bit for bit equal (same recurrence, same order of operations) |
| `Erf.erf` against commons', 2,000,000 points in [-7, 7] | max 1.0e-15 absolute |
| `Erf.erf` against the C library (Python port of the same algorithm, 2,000,000 points in [-6.5, 6.5]) | max 3.3e-16 absolute, worst 4 ulp, at 0.47 where erf is 0.49 (it is computed as `1 - erfc` there) |
| `Erf.erfc` against the C library (Python port, 1,000,000 points in [-6, 26]) | max relative 1.0e-15 |
| commons' `erf`/`erfc` against the C library's table values | `erf` 2.2e-16 absolute; `erfc` 2.1e-14 RELATIVE in the tail (so differences to commons' `erfc` up to 6.4e-14 are commons') |

Singular vectors agree with commons up to sign for separated singular values (`< 1e-9`); for equal or zero singular values
they are not unique and the two implementations pick different orthonormal bases.

Primitive-level microbenchmark (2000 decompositions, median of 15, warmed; noisy, a 40 ms commons outlier at d = 4
dropped from the reading): SVD (values only) of a d x d matrix, ours / commons in ms: d=2 0.88/1.27, d=3 1.89/2.46,
d=6 8.5/13.1; with the `u` column: d=2 0.89/1.50, d=3 1.95/2.49, d=4 5.5/4.0, d=6 13.9/8.6 (ours builds `u` and `v`
eagerly); LU: 0.08/0.27, 0.12/0.24, 0.19/0.35, 0.52/0.65. Unconfirmed at engine level: the listed alpha specs run in
the same time within the noise of a parallel test run, and no isolated A/B of `HelixDelaunay` was done.

## The sign hazard of `Hyperplane.from`

`Hyperplane.from` takes its normal from the null column of `U` of the SVD of the facet's difference vectors; the sign
of a singular vector is arbitrary and differs between implementations. Reading the callers: `FrontierCase.apply`,
`handleCosphericalPoints` and the `inward` choice in `compute()` all orient the plane themselves (`reverse` when the
complement vertex is on the light side, or when a point lies below), `looksValid.onHull` and the `vs` filter use `|dist|`
or both sides symmetrically, `byMinimalCentre`/`bySphereScan` use the already oriented plane. So for a facet with a proper
cofacet the sign cannot matter. The remaining places are those where the orientation test itself is a tie: a cospherical
cluster tiled by `handleCosphericalPoints` pulls in points that are coplanar with a facet (`dist` of rounding size), and
`inward` for a candidate that is not a hull facet.

Experiment (a temporary switch, removed): flip the sign of every `Hyperplane.from` normal and triangulate with
`HelixDelaunay` (with and without `requireValidTriangulation`): 45 random Gaussian clouds (2-D 60 points, 3-D 50, 4-D 30,
15 each), a 4x4 grid, a 3x3x3 grid and the cube corners give identical simplices and radii (1e-9); the 3^4 grid in 4-D
(81 points, all cospherical clusters) gives a different but valid tiling (both `looksValid`, 1327 top simplices each) with
the identical barcode (433 bars, equal to the last bit). That is the known order-dependent tie-breaking of cospherical
clusters (`rules/alpha.md`, WONTFIX class 1), with the sign as one more input to it.

Decision: `Hyperplane.from` returns a canonically signed normal (the first coordinate of absolute value at least
`0.5/sqrt(d)` is positive; a unit vector always has one), so the tie-break no longer depends on which SVD
implementation produced the plane, nor on the order of the points; callers that need a side `reverse` it as before.
`HyperplaneSpec` pins: unit normal orthogonal to the facet, same plane for every order of the points, the canonical sign,
the lines/planes with known normals. What the old sign was on the grid is not known (commons is no longer called there):
the existing alpha specs pass with the canonical one.

## Differences from commons-math3 at the call sites

1. `Hyperplane.from`: canonical sign (above).
2. `Barcode.reduceMatrix` no longer changes its argument (commons' version mutated it and returned it); `image`,
   `kernel`, `cokernel` always passed it a fresh matrix. All `Barcode` methods take and return `DenseMatrix`; public
   `AlphaShapes.Point` is `DenseVector`: source-incompatible, binary-incompatible, deliberate for 0.5.x.
3. `ConjugateGradient`: `maxIterations` is the number of iterations allowed (commons' iteration manager counted the
   initialization as one, so it allowed one fewer); no operator application on the zero start (commons applied it to the
   zero vector); `check = true` adds a symmetry probe (two extra applications) and throws our exceptions
   (`NotConvergedException`, `NotSymmetricPositiveDefiniteException`) where commons threw `MaxCountExceededException`
   and `NonPositiveDefiniteOperatorException`. The positive-curvature check is commons'; `r . z` positivity is vacuous
   without a preconditioner and is not tested.
4. A matrix with no rows: commons' `createRealMatrix` threw `NoDataException` (reachable in `HelixDelaunayBuilder`
   with ambient dimension 1 and several coincident points on the supporting hyperplane); `DenseMatrix` is 0x0 with rank 0.
5. LU with `singularityThreshold <= 0`: an exactly zero pivot counts as singular (commons divides by it).
6. `Vectorization.persistenceImage`: `Erf.erf` is within 1e-15 of commons' (their `erf` is the less accurate one), so a
   pixel can change in its last digits; `VectorizationSpec` is unchanged and green.
7. `toString` of `Hyperplane`/`Hypersphere` prints `DenseVector(1.0, 2.0)` instead of commons' `{1; 2}`.
8. Singular vectors: see above (up to sign, and arbitrary within equal singular values).

## Scala.js

- `sbt tda4jJS/compile` still fails; its errors are in `Viewer.scala`, `CechStream.scala`, `VietorisRips.scala`,
  `SimplexStream.scala`, `SimplexIndexing.scala`, `FiniteMetricSpace.scala`, `EdgeCollapseStream.scala`, `Cofacets.scala`.
  None is in a file changed here. (Compiling does not catch a missing `java.*` class; that shows when linking, which the
  other errors still block. The only `java.*` in the new code is `Arrays.copyOfRange`, `System.arraycopy`,
  `Double.hashCode`; the one JVM-only class left in a file touched here is `LinkedBlockingDeque`, now removed.
  `AlphaShapes` still uses `java.util.IdentityHashMap` and `Collections.newSetFromMap`: unverified on JS.)
- The five new shared specs (`DenseVectorSpec`, `DenseMatrixSpec`, `ConjugateGradientSpec`, `ErfSpec`, with
  `DenseFixtures`) were run on Scala.js 1.22 / Node 22.22 in a scratch project outside the repo (the four sources plus
  `Optional.scala`, same compiler flags): 76 examples, all pass, `ErfSpec` within its JVM tolerances (V8's `exp`). The
  SVD/LU/CG arithmetic is only `+ - * / sqrt`, so results are bit for bit the JVM's.
- A finding from that run: `must throwAn[IllegalArgumentException]("singular")` passed on the JVM and failed on JS (specs2
  matches the message differently on Scala.js); the spec now checks `e.getMessage.contains("singular")` in a `like`.
- `HyperplaneSpec`, `BarcodeSpec` and the alpha specs were not run on JS (they need the whole `tda4jJS` build to link).

## Tests

- New, shared: `DenseVectorSpec`, `DenseMatrixSpec` (LU, SVD, pseudo-inverse), `ConjugateGradientSpec`, `ErfSpec`,
  `DenseFixtures`; `alpha/HyperplaneSpec`; three `reduceMatrix`/`pivotsOf` examples in `BarcodeSpec`.
- JVM-only: `src/test/scalajvm/.../algebra/DenseLinearAlgebraOracleSpec` (the table above).
- The spec-writing discipline: each SVD property is one the plausible wrong answer fails (no sort, no U/V swap for wide
  matrices, no completion of null columns, a missing cutoff).
- JVM runs: `sbt "tda4j/testOnly *Alpha* *Bowyer* *Helix* *Delaunay* *Barcode* *Circular* *Toroidal* *Vectoriz*
  *Distance*`: 136 examples green before (3 min 15 s) and, with the new specs matched by the same globs plus `*Dense*
  *ConjugateGradient* *ErfSpec* *Hyperplane* *TDAlabExports* *UserImports* *APISpec* *Show*`, 276 green after. The whole JVM suite
  (`sbt tda4j/testFull`, at the last code commit): 1462 examples, 0 failed, 8 skipped (the benchmark specs), 4 min 10 s.

## Open

- `DenseVector`/`DenseMatrix` as top-level names (above).
- `LatticeReduction` still hand-rolls its Cholesky (it never used commons' version); its scaladoc mentions
  commons-math3 as the road not taken.
- The commons-math3 line in `build.sbt` can go to the JVM test scope when the lead merges (the only remaining users are
  `src/test/scalajvm`).
