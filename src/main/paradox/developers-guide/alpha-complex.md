# Alpha complex: DQP vs Helix

Two independent backends compute alpha complexes; `Alpha(points, dispatch)` (`AlphaShapes.scala`) chooses
between them. This page is the developer-facing view — what to know before touching either backend's code.
For the user-facing framing (which one to pick, and what the honest tradeoffs are), see the
@ref:[User's Guide](../user-guide/index.md).

## Dispatch

```scala 3
def Alpha(pts: Seq[Array[Double]], dispatch: String = "default")(using epsilon: Epsilon): AlphaShapes
```

`dispatch = "default"` **always resolves to `"helix"` regardless of point-cloud shape**, as of this
writing — `"DQP"` must be requested explicitly. The name "default" invites the opposite assumption; don't
assume it picks whichever backend is "better" for a given input, because right now it doesn't pick at all.
An earlier Miniball-based Delaunay backend was ripped out entirely as broken (see git history) — don't
resurrect it without checking why first.

Both backends extend the common `AlphaShapes` abstract class (`extends
StratifiedSimplexStream[Int, Double]() with DoubleFiltration[Simplex[Int]]()`), so they're
dispatch-interchangeable as far as any code consuming the resulting stream is concerned —
`AlphaComplexSpec` runs identical property checks against both to enforce this.

## `HelixDelaunay` — an actual Delaunay triangulation

Based on the frontier-walking approach in the paper cited in its class doc (`AlphaShapes.scala`). Builds
an actual Delaunay triangulation incrementally: finds a bootstrap simplex, then walks the frontier of
facets, testing candidate points against each facet's supporting hyperplane and circumsphere.
`filtrationValue` returns the *unsquared* circumradius (matching DQP's own units after `radiusOf` — see
below).

**Two independent robustness bugs were found while using this as DQP's cross-validation ground truth**
(full repro/root-cause detail in `.claude/WORKLOG-alpha-complex.md`, Part 3):

1. **Fixed and verified.** The initial-simplex bootstrap's `assert(validated.nonEmpty)` used to fail on
   ordinary random input at roughly a 1-in-600 rate: when more than `ambientDimension` points lay on the
   discovered hull-supporting hyperplane (common for grid-like or otherwise partly-degenerate clouds), the
   code collapsed `startingSimplex` down to just 2 points regardless of `ambientDimension`, starving the
   circumsphere bootstrap of a well-posed starting point. Fixed by greedily growing an affinely-independent
   subset of exactly `ambientDimension` points (rank-checked via `SingularValueDecomposition`) instead of
   an arbitrary 2-point slice. 20,000-trial fuzz re-run: zero failures.
2. **Partially fixed; the residual behavior is a known, accepted limitation, not an oversight.**
   `addFrontierCase`'s facet-deduplication check originally compared a `d`-vertex facet against a
   `(d+1)`-vertex full simplex (always `false`, dead code) — fixed, but this alone barely moved the failure
   rate. The dominant cause is order-dependence in the frontier walk's greedy first-empty-candidate search
   on point clouds with a near-cospherical local cluster (circumradii of competing candidate simplices
   agreeing to 4-5 significant figures — closer than `handleCosphericalPoints`' own tiling logic detects,
   since that logic only checks near-ties against *one already-chosen* candidate's circumsphere, not
   near-ties *across* competing candidates). Quantified: **zero failures across 20,000-trial fuzz sweeps at
   ambient dimension 2 and 5**, but **~1-in-170 at ambient dimension 4 with 20-30 points** — ordinary-
   looking inputs, not contrived minimal counterexamples. A real fix needs joint near-tie detection across
   all competing candidates before committing to one, with a tie-break convention consistent with DQP's own
   Bland's-rule anti-cycling approach (see below) — a genuine algorithm change, not a bounded bug fix.

**Practical consequence**: Helix is not a fully reliable ground truth for automated cross-validation
fuzzing on point clouds with ambient dimension ≥ 4 and no assurance against near-cospherical local
structure. `AlphaCrossValidationSpec`'s broad `forAll`-based DQP-vs-Helix comparisons are deliberately kept
*out* of `sbt test` (as `unsafeCompare`/`unsafeFuzzCompare` diagnostic methods instead) — a real Helix
failure would otherwise masquerade as a DQP regression or vice versa. `specs2`'s `pendingUntilFixed` was
tried and rejected for this: it's for a deterministically-known-failing example, and it flags an unexpected
*pass* as itself a failure — the wrong semantics for a bug that only triggers probabilistically.

## `AlphaComplexDQP` — dual active-set QP, never builds Delaunay at all

Implements Erik Carlsson & John Carlsson, *Computing the alpha complex using dual active set quadratic
programming*, Scientific Reports 14:19824 (2024), <https://doi.org/10.1038/s41598-024-63971-3>. The core
idea: instead of building the Delaunay complex and reading off which simplices survive, answer a per-
simplex *feasibility* query directly — "is this Voronoi/power face nonempty, and does it meet the ball of
radius `r`?" — posed as the convex QP (13) in the paper and answered in the Lagrangian dual (10). Two
consequences the class-doc header in `AlphaComplexDQP.scala` spells out: any dual-feasible point gives a
lower bound on the primal optimum (weak duality lets many candidates be discarded without ever solving the
QP, and `λ = 0` is always dual-feasible so there's no phase-1 cost), and the dual depends on the data only
through the Gram matrix `B = A Aᵀ`, so ambient dimension enters only when `B` is assembled, not in the
search itself — which is what lets this scale to the paper's 2352-dimensional example, where Delaunay is
entirely infeasible.

The QP solver follows DAQP (Arnström, Bemporad & Axehill, IEEE TAC 67(8):4362-4369, 2022,
<https://github.com/darnstrom/daqp>, reference [27] of the paper). The paper's problem (9) is already in
DAQP's canonical inner form (`H = I`, a least-distance problem), so DAQP's general `H`-factorisation is
unnecessary and its recursive LDL^T updates collapse to Cholesky update/downdate of `B_W`
(`CholeskyWorkspace` in the source) — a hand-rolled incremental Cholesky, deliberately not Apache Commons
Math's `CholeskyDecomposition`, which has no update/downdate API and would force an O(k³) full
refactorisation per active-set step instead of O(k²).

### Math cheat sheet

Base vertex `x`, neighbours `x_i`, power weights `p`. **Filtration values are squared radii (powers)** per
the paper's Definition 10 — `AlphaComplexDQP.radiusOf` takes the square root, and
`AlphaShapeDQP.filtrationValue` goes through `radiusOf` (not the raw squared value) specifically so it
matches `HelixDelaunay.filtrationValue`'s units under the shared `AlphaShapes` contract. The dual objective
only needs *squared distances*, not the dot products the paper frames it with: `B_ij = (d²(i,x) + d²(j,x) -
d²(i,j))/2`. This is why `PowerDistance` sits on squared distance rather than extending `FiniteMetricSpace`
directly (which is unsquared) — it bridges via `PowerDistance.toMetricSpace` only where interop is actually
needed (e.g. `cechNeighbours()`'s VP-tree spatial index). **Caveat**: `B` is PSD only for
Euclidean-embeddable metrics — feeding in an arbitrary (non-Euclidean) metric, as Vietoris-Rips happily
allows elsewhere in this library, is not supported here, and "alpha complex" isn't even a well-defined
notion for such a metric.

`AlphaShapeDQP` (what `Alpha(pts, "DQP")` actually constructs) always computes the complete, **untruncated**
alpha complex (`maxRadius = Double.PositiveInfinity`), specifically to match `HelixDelaunay`'s
always-untruncated behavior — `metricSpace.minimumEnclosingRadius` was tried as the default bound first and
rejected, because degenerate configurations produce simplices with arbitrarily large circumradius (see
@ref:[Degeneracies](degeneracies.md)) that a finite bound would silently exclude. Callers who want an actually
radius-truncated alpha complex should call `AlphaComplexDQP.euclidean(points, maxRadius, maxDimension,
settings)` directly instead of going through `Alpha(pts, "DQP")`.

### Numerical-robustness decisions — settled, not casually retunable

Extensive robustness work has gone into `DualQP.solve` (full derivation of each with concrete
counterexample point clouds in `WORKLOG.md` at the repo root):

- **`rankTolerance` default is `1e-6`, not the more "obvious" `1e-12`.** A Schur-complement ratio as large
  as `~1e-8` has been observed to poison the Cholesky factor (multipliers blowing up to `~1e14`) and cause
  genuine non-terminating active-set cycling (confirmed non-terminating at 100,000 iterations, not just
  slow). `1e-8` (the textbook sqrt-of-machine-epsilon rule of thumb) is *still* not always enough margin —
  a case was found where the poisoning commit's ratio was `1.03e-8`, clearing that bar by a hair. `[1e-7,
  1e-5]` is the empirically-verified safe range; `1e-4` starts rejecting genuinely non-degenerate directions
  and silently gives a wrong answer instead.
- **Ratio-test ties break by the constraint's global index**, not its position in the working set (Bland's
  rule anti-cycling) — working-set position isn't a stable ordering, so breaking ties by it would let the
  same pair of global indices swap forever without progress.
- **Known, accepted limitation — do not "fix" this without reading `WORKLOG.md` first**: when the entering
  variable's Schur complement is small and no active inequality can be swapped out to compensate,
  `DualQP.solve` conservatively treats the candidate as infeasible rather than committing the small pivot
  directly. This *can* wrongly exclude a genuinely-Delaunay simplex near certain near-degenerate
  configurations. A mathematically "more correct" fix (commit anyway when `s > 0`, since the dual objective
  has a genuine finite maximum at `t* = grad_j / s`) was implemented and reverted: two counterexamples with
  near-identical Schur-complement ratios (`~3.4e-7` legitimate, `~3.0e-7` catastrophic) required *opposite*
  handling, proving no fixed numerical threshold can safely distinguish "safe to commit" from "will poison
  the factor" — it depends on the rest of the working set's conditioning, not that one ratio in isolation.
  `solveAtVertex` has a defense-in-depth per-candidate catch so a not-yet-characterized non-convergence case
  excludes just that one candidate (logged to stderr) rather than aborting the whole complex.
- **Vertex filtration values must go through `space.weight(x)`, not a bare `0.0` default.** The `weights`/
  `witnesses` maps backing `AlphaComplexDQP.filtrationValue`/`.witness` are only populated for `k >= 1`
  candidates inside `compute()`'s main loop; vertices (`byDim(0)`) are added separately and need their own
  entries set explicitly (`weights(f) = -space.weight(x)`, per Definition 10 evaluated at a vertex, where
  the unconstrained minimizer is trivially `y* = x`). Defaulting to `0.0` is only correct in the unweighted
  case — with nonzero weights it silently breaks the monotonicity invariant that a vertex's filtration value
  must not exceed that of an edge through it. Caught by `AlphaComplexDQPWeightedSpec`, currently the only
  place weighted complexes get exercised end-to-end (`HelixDelaunay` is plain-Euclidean-only, so it can't
  serve as ground truth for the weighted case — that spec checks structural invariants only, same as
  `AlphaComplexSpec` does for the unweighted case, not a full correctness proof).

`AlphaComplexDQPRegressionSpec` (in `AlphaComplexSpec.scala`) pins two hand-verified adversarial point
clouds (a facet-closure counterexample and the cycling counterexample above) as permanent regression tests,
alongside the original 3x3-grid repro in `AlphaValidationSpec` and a broader `AlphaComplexSpec` property
suite (`minTestsOk = 2000`, not scalacheck's default of 100 — the bugs above had verified failure rates as
low as 1-in-12000, so 100 samples gives weak protection).
`AlphaComplexDQPSpatialIndexSpec` separately cross-checks `cechNeighbours()`'s VP-tree-based implementation
against a preserved brute-force reimplementation — correctness never depends on the spatial index, only
performance does, and the win is real but regime-dependent (scales with N in sparse/local neighborhoods,
the normal alpha-complex case; no benefit, even a small regression, in dense near-complete-graph
neighborhoods).

**Scala-specific gotcha writing specs against this file**: give any `Seq[Simplex[_]]` an explicit type
ascription (`val xs: IndexedSeq[Simplex[Int]] = ...`) before calling `.forall`/similar on it. In a specs2
spec mixing the `ScalaCheck` trait, an un-ascribed `.forall` can resolve to a specs2 `ValueCheck`-based
extension instead of the standard-library one, breaking type inference inside the lambda with confusing
"value X is not a member of ValueCheck[Simplex[Int]]" errors. Not fully root-caused; the explicit
ascription reliably fixes it.

## Honest framing worth keeping in mind while working on either backend

The paper's own benchmarks are mixed against Ripser (loses on 2 of 4 persistence examples) and against
qhull-based Delaunay on some inputs. The genuine value proposition for DQP is high ambient dimension (where
Delaunay is infeasible), exact homology rather than persistence diagrams, and much smaller complexes than
Vietoris-Rips when data sits near a low-dimensional subspace — not raw speed. See the
@ref:[User's Guide](../user-guide/index.md) for how this should shape what you tell an end user.
