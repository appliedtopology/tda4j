# Worklog: toroidal coordinates (lattice-reduced circular coordinates)

2026-09-26, cloud session, requested directly by the project lead overnight (not from
`WORKLOG-mainstream-feature-gap-analysis.md`'s own execution order): Edelsbrunner's own point from the Q&A at the
first circular-coordinates presentation -- given `k` independent persistent H¹ generators, any unimodular integer
combination of them is an equally valid choice of generators for the same rank-`k` sublattice, so "the" `k`
circular coordinates a cohomology computation hands back are arbitrary, not canonical, and can in principle be an
arbitrary skewed mix of a data set's "obviously" independent cycles. The project lead pointed at the Dreimac
project (Scoccola, Perea et al.) having solved exactly this via lattice reduction (LLL), calling the result
"toroidal coordinates," and asked for the same capability here. Session transcript:
`https://claude.ai/code/session_01EAhuR1Rpe1U7e84yp7YuEV`.

## What was built

- **`homology.LatticeReduction`** (`LatticeReduction.scala`): a from-scratch, textbook LLL implementation.
  `reduce(gram: Array[Array[Double]], delta: Double = 0.75): Result` factors the `k x k` Gram matrix via a
  hand-rolled Cholesky (own reasons below, not `commons-math3`'s `CholeskyDecomposition`), runs LLL on the
  Cholesky factor's rows (a concrete `R^k` realization of the abstract generators, so LLL has actual vectors to
  work with without ever touching the much-larger edge-indexed space the real harmonic cochains live in), and
  returns `Result(basisChange: Array[Array[Int]], reducedGram: Array[Array[Double]])` -- a unimodular integer
  change of basis and its own Gram matrix `U^T G U`. `isReduced(gram, delta, tol): Boolean` independently
  recomputes the same Cholesky/Gram-Schmidt machinery as a read-only diagnostic (is a Gram matrix, from anywhere,
  already LLL-reduced) -- used by tests as an oracle that doesn't just re-run `reduce` and check for the identity.
- **`homology.CircularCoordinates.computeToroidal`**: generalizes `compute` from one persistent H¹ class to `k`
  simultaneously-alive ones. Computes the full-filtration H¹ bars and `K_r`'s own persistent cohomology ONCE
  regardless of `k` (a real efficiency requirement, not just tidiness -- see below), then per chosen class:
  birth-match against `K_r`'s essential classes (exactly `compute`'s own logic), integer lift + exact verification,
  and harmonic smoothing -- the last two shared with `compute` via a new `harmonicSmoothOnComponent` helper
  factored out of `compute`'s own body (a pure relocation, no behavior change -- `compute`'s own 8-example test
  suite is the regression check for that). Builds the `k x k` Gram matrix of the resulting harmonic cochains
  (the paper's own dSMV inner product: plain unweighted sum-over-edges dot product -- already exactly what
  `compute`'s own harmonic-smoothing objective uses, no new geometric computation), reduces it via
  `LatticeReduction.reduce`, and applies the resulting `U` directly to the already-computed per-class `theta`s
  (linearity of harmonic smoothing means this is exactly equivalent to combining the cocycles first and
  re-smoothing once -- no second linear solve). Requires every chosen class's `[birth, death)` to have a common
  intersection containing `r`, no duplicate indices, and -- a real, checked requirement -- every chosen class
  supported on the SAME connected component of `K_r`'s 1-skeleton (two classes native to different components of
  a disconnected `K_r` have no joint domain to be coordinatized on at all; this is possible in principle even
  though it never happens for a single connected point cloud, since `K_r`'s own connectivity is independent of
  which cocycle happens to be chosen).
- **MATLAB facade**: `matlab.ToroidalCoordinatesResult` (new class, `theta(c)`/`hasCoordinate(i)`/
  `cocycleIndices()`/`basisChange()`/`originalGram()`/`reducedGram()`/`r()`/`prime()`) and
  `matlab.TDA4j.toroidalCoordinates` (3-arg default-`prime`/`reduce` overload + full 5-arg form), mirroring
  `circularCoordinates`'s own facade precedent exactly. Deliberately a NEW class rather than generalizing
  `CircularCoordinatesResult` -- `mimaReportBinaryIssues` runs in CI, and the result shape is genuinely different
  (`k` theta arrays plus the basis-change/Gram-matrix diagnostics, not one array).
- Docs: `src/docs/developers-guide/architecture.md` (new "`homology.LatticeReduction` and
  `CircularCoordinates.computeToroidal`" section), `class-diagrams.md` (extended the existing
  `CircularCoordinates` diagram rather than a new one), `src/docs/user-guide/README.md` (new "Toroidal
  coordinates" subsection, same place as "Circular coordinates"). No CLI mirror, same reasoning as
  `circularCoordinates`'s own precedent (per-point array output, and `cocycleIndices` is itself a small array,
  awkward for the CLI's single-value-flag conventions) -- decided and documented, not merely omitted.
- **`.claude/BUGS-IN-REFERENCES.md`** (new file, cross-referenced from CLAUDE.md's Session practices): a
  standing, flat (never condensed away) log of defects found in external papers/reference implementations while
  validating tda4j features against them -- requested directly by the project lead mid-session ("document this
  bug, as well as other bugs we've found in established results, so we can find them all later"). Seeded with
  two entries: the Sheehy-Rips `CJS 2015` Algorithm-3-vs-Section-5.3 gap (already in CLAUDE.md/its own worklog,
  just cross-referenced here) and this session's own DREiMac finding below.

## The actual math (why this is the right construction, not just "port DREiMac")

`H^1(K_r; Z)`'s free part embeds as a lattice in `H^1(K_r; R)` via harmonic representatives, and
`H^1(K_r; R)` carries a natural inner product (the discrete Dirichlet form -- literally the same
`||z - d0 g||^2` objective `compute`'s own harmonic smoothing already minimizes, restricted to the harmonic
subspace). This is the exact combinatorial analogue of the period-matrix/Jacobian-variety construction for a
Riemann surface: a choice of generators for the lattice is a choice of basis, any two related by a unimodular
integer change, and the "reduced" (short, near-orthogonal) basis under the ambient inner product is not
canonical mathematics-wise but is the well-defined, principled choice LLL computes for any lattice. That the
paper's own name for this is "toroidal coordinates" reflects that the joint map lands on the flat torus
`H^1(K_r;R)/H^1(K_r;Z)` (rank `k`), and reducing the lattice basis is exactly what decorrelates the induced
torus coordinates.

**Cholesky-factor realization, not raw edge-vectors**: the harmonic cochains live in `R^(#edges of K_r)`, which
can be large; LLL only ever needs INNER PRODUCTS between the `k` generators (`k` is the number of simultaneously-
combined classes, always small in practice), so factoring the `k x k` Gram matrix `G = C C^T` (Cholesky) and
running LLL on `C`'s own rows gives a concrete, tiny (`k`-dimensional) stand-in whose pairwise dot products
reproduce `G` exactly -- this is both the paper's own Algorithm 4 (confirmed by reading it directly, not
assumed: "Compute the Cholesky decomposition `G=CC*`... `b_1,...,b_k := LLL(C_1,...,C_k)`, with `C_j` the j-th
row of `C`") and DREiMac's own implementation choice.

**Hand-rolled Cholesky, not `commons-math3`**: mirrors `alpha.CholeskyWorkspace`'s own precedent of not reaching
for `commons-math3`'s `CholeskyDecomposition` -- a linearly-dependent input (e.g. the same cohomology class
chosen twice) needs to fail with a message pinned to the failing pivot, not whatever `commons-math3`'s own
symmetry/PD thresholds do with a matrix assembled from many small floating-point-summed terms. The Gram matrix
itself is built to be symmetric BY CONSTRUCTION in `computeToroidalGeneric` (each off-diagonal pair's dot product
computed once and mirrored into both `(i,j)` and `(j,i)`, never two independently-summed calls that could differ
by floating-point noise), and `LatticeReduction.reduce` symmetrizes again internally (by averaging, after a loose
tolerance check) as defense in depth for any OTHER caller of the standalone module.

## A real bug found in the reference implementation, not ported

`scikit-tda/DREiMac`'s own `toroidalcoords.py` (the reference implementation of the same paper) has a genuine
Gram-Schmidt bug: `_gram_schmidt`'s inner loop projects each new vector onto the ORIGINAL input basis vectors
(`Aj = B[:, j]`) rather than the running orthogonalized ones (should be `Aj = A[:, j]`) -- confirmed by direct
numerical repro (feeding `(1,0,0),(1,1,0),(1,1,1)` produces a third output vector NOT orthogonal to the first;
see `.claude/BUGS-IN-REFERENCES.md` for the runnable repro), not just by reading the code. This is invisible for
exactly `k=2` (this feature's own headline "two circles -> one torus" case: with only two vectors there is only
ever a single projection step, and `A[:,0] == B[:,0]` identically, so the "wrong" and "right" formulas coincide)
but produces a provably non-orthogonal intermediate result for `k>=3` (which DREiMac's own docstring explicitly
anticipates: "it may be of interest to see other dimensions (e.g. for a torus)"). **Checked, and did NOT find,
whether this actually degrades `_lll`'s own final output** (a separate, harder question than whether the
isolated subroutine is wrong): ported `_lll`'s own main loop to pure Python and ran it on the specific 3x3
skewed-diagonal fixture below plus 300 random 3x3 integer bases -- every case, buggy-GS-guided and correct-GS-
guided runs land on the identical, genuinely-LLL-reduced (checked against a correct Gram-Schmidt of the output,
independent of whichever GS `_lll` used to get there) final basis. See `.claude/BUGS-IN-REFERENCES.md` for the
full repro and a plausible (not proven) mechanism. **This entry is therefore precise: a real, proven bug in an
isolated subroutine, not a demonstrated end-to-end correctness bug** -- don't restate it as "DREiMac gives wrong
answers for 3+ classes" without a repro that actually shows that. `LatticeReduction` is a from-scratch textbook
implementation instead (correct Gram-Schmidt, cross-checked against Wikipedia's own independently-stated
algorithm and worked example), not a port of DREiMac's code -- the only thing carried over from reading DREiMac
directly is the overall SHAPE of the computation (Cholesky factor -> LLL -> apply `U` to the original per-class
quantities), which matches the paper's own Algorithm 4/8 independently of DREiMac's specific (buggy)
implementation of the Gram-Schmidt step.

## Verification

`LatticeReductionSpec`: property-based checks on 5 hand-built Gram matrices (2x2 through 4x4, including the same
3x3 skewed-diagonal case used above to check DREiMac's own `_lll` end-to-end) -- unimodularity (`|det U| = 1`),
`reducedGram == U^T gram U` (checked with independent matrix-multiply code, not `LatticeReduction`'s own
`congruence` helper), `isReduced(reducedGram)`, and the covolume-squared (`det(Gram)`) invariant under any
unimodular change of basis. Plus: the "Edelsbrunner scenario" directly -- two orthogonal generators of norm 2
and 3, skewed by a known unimodular matrix, recovered (verified algebraically by hand before running, then
confirmed by the passing test) back to exactly the original diagonal Gram matrix; the same skew-and-recover
check at `k=3`; and the Wikipedia LLL article's own worked example (a basis for `Z^3`), checked by applying the
resulting `basisChange` back to the concrete original vectors and confirming the resulting Gram matrix matches
`reducedGram` exactly (ties the abstract Gram-matrix-only algorithm back to real vectors) rather than hardcoding
Wikipedia's stated output vector-for-vector (a transcription/tie-breaking-convention risk not worth taking when
the robust property checks already prove correctness independently). Error paths: non-square, non-symmetric
beyond floating-point tolerance, positive-semidefinite (linearly dependent generators), and `delta` outside
`(1/4, 1]`, each a distinct `IllegalArgumentException`.

`ToroidalCoordinatesSpec`: a hand-constructed wedge of two circles of different radii (1.0 and 0.6, deliberately
unequal to avoid a birth/death tie between the two loops), touching near a single point by construction (no
randomness anywhere -- each circle's own sample point 0 is placed to land almost exactly on the other's, and
every other cross-circle pair is far apart by comparison, so exactly one bridging edge appears over a wide
range of `r`, never a second one that would create a spurious extra cycle) -- topologically a wedge of two
circles, H^1 rank 2, one connected component. Confirms: `h1Bars` finds (at least) two simultaneously-alive
classes; `reduce=false` reproduces `compute`'s own per-class `theta` exactly, point for point (the shared-
computation refactor changed nothing observable); `reduce=true` produces a unimodular `basisChange` onto a
genuinely-`isReduced` Gram matrix preserving the covolume-squared invariant; and the parameter-validation/
component-mismatch error paths (empty/duplicate/out-of-range `cocycleIndices`, `r` outside the shared alive
range, and two classes on genuinely disconnected components of `K_r` -- reusing the existing "two disjoint
circles far apart" idea from `CircularCoordinatesSpec`, here with two IDENTICAL translated circles so their
`(birth,death)` ranges coincide exactly by construction, isolating the component check from any birth-range
coincidence).

`matlab.ToroidalCoordinatesResultSpec`: the facade is a faithful, correctly-marshalled pass-through to
`homology.CircularCoordinates.computeToroidal`, mirroring `CircularCoordinatesResultSpec`'s own scope (not a
re-test of the underlying math).

Full suite: `sbt test` -- 620 examples (609 passed, 11 skipped benchmarks), 0 failures, 0 errors, clean before and
after this item's work (previous count was 519; the new examples here plus intervening unrelated work elsewhere
in the repo account for the rest). `sbt scalafmtCheck`: clean for every file this item touched; `scalafmtAll`
also reformatted 6 unrelated pre-existing-drift files it happened to walk over
(`homology/{Homology,PackedRipserCohomology}.scala`, `streams/{FiniteMetricSpace,SimplexIndexing}.scala`,
`homology/SingleEngineProfileDriver.scala`, `streams/SimplexIndexingSpec.scala`) -- reverted, confirmed via a
plain `scalafmtCheck` (no `scalafmtAll` first) that exactly 4 of those six (the two test/driver files apparently
don't get checked, or check clean on their own) are pre-existing drift flagged even with none of this item's own
changes present, same "note but don't fix" precedent the circular-coordinates worklog already established (that
worklog's own drifted-file list was three DIFFERENT files -- `DistanceToMeasure.scala`/`DtmRipsStream.scala`/
`SheehyRipsStream.scala` -- so this set has simply moved on with unrelated intervening commits, not something to
chase). `sbt mimaReportBinaryIssues`: passes trivially (`mimaPreviousArtifacts` is empty pre-1.0, same as every
other item). `sbt laikaSite`: builds clean, same 4 pre-existing scaladoc cross-reference warnings
(`Barcode.scala`/`Gudhi.scala`/`FiniteMetricSpace.scala`, none of them files this item touched) and no new ones.

## An environment note, not a tda4j finding

This session's container had no `sbt` installed and no pre-warmed dependency cache at all (unusual for this
project's sessions generally, per this file's own commands section assuming `sbt` just works) -- bootstrapped a
launcher from Maven Central (`sbt-launch-1.12.11.jar`) by hand. Maven Central (via this environment's proxy)
throttled the resulting cold-cache burst of parallel dependency fetches with `429`s aggressively enough that
plain `sbt compile` needed several paced, externally-retried attempts (a solo `curl` of an individual
repeatedly-`429`d artifact succeeded immediately, confirming this is burst/concurrency throttling, not a real
block) before the full dependency graph resolved. Not a tda4j code issue; noted here in case a future session
hits the same cold-start friction and wants the shortcut (retry with real pacing between attempts -- each
retry's successes are cached, so this converges -- rather than assuming the network is unreachable).

## Status

Complete: `homology.LatticeReduction` (Scala API), `homology.CircularCoordinates.computeToroidal` (Scala API,
plus the `harmonicSmoothOnComponent` extraction from `compute`'s own body), `matlab.TDA4j.toroidalCoordinates` +
`matlab.ToroidalCoordinatesResult` (MATLAB facade), CLI deliberately not mirrored (documented, same reasoning as
`circularCoordinates`), `src/docs/developers-guide/architecture.md`/`class-diagrams.md` and
`src/docs/user-guide/README.md` updated, `.claude/BUGS-IN-REFERENCES.md` created and cross-referenced from
CLAUDE.md, full suite green (620/620 non-skipped), scalafmt/mima/laikaSite clean. Committed and pushed to
`claude/compassionate-shannon-k78asq`.
