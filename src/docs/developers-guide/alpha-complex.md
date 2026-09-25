# Alpha complex: DQP vs Helix

Two independent backends compute alpha complexes; `AlphaShapes(points, dispatch)` (`alpha/AlphaShapes.scala`)
chooses between them. This page is the developer-facing view. For the user-facing framing (which one to
pick, and the honest tradeoffs), see the [User's Guide](../user-guide/README.md).

## Dispatch

```scala 3
object AlphaShapes:
  def apply(pts: Seq[Array[Double]], dispatch: String = "default")(using epsilon: Epsilon): AlphaShapes
```

`dispatch = "default"` **always resolves to `"helix"` regardless of point-cloud shape** — `"DQP"` must be
requested explicitly. Both backends extend the common `AlphaShapes` abstract class, so they're
dispatch-interchangeable as far as any code consuming the resulting stream is concerned —
`AlphaComplexSpec` runs identical property checks against both to enforce this.

## `HelixDelaunay` — an actual Delaunay triangulation

Builds an actual Delaunay triangulation incrementally: finds a bootstrap simplex, then walks the frontier of
facets, testing candidate points against each facet's supporting hyperplane and circumsphere.
`filtrationValue` returns the unsquared circumradius, matching DQP's own units after `radiusOf`.

**Known, quantified limitation**: on point clouds with a near-cospherical local cluster (competing
candidate simplices' circumradii agreeing to 4-5 significant figures — closer than the tiling logic's own
near-tie detection catches, since that logic only checks ties against one already-chosen candidate, not
across competing candidates), the frontier walk's greedy search becomes order-dependent and can converge on
a locally-consistent but globally wrong triangulation. Measured: zero failures across 20,000-trial fuzz
sweeps at ambient dimension 2 and 5, but roughly 1-in-170 at ambient dimension 4 with 20-30 points —
ordinary-looking inputs, not contrived counterexamples. A real fix needs joint near-tie detection across all
competing candidates before committing to one; this is a genuine algorithm change, not a bounded bug fix.

**Practical consequence**: Helix is not a fully reliable ground truth for automated cross-validation fuzzing
at ambient dimension ≥ 4 without an assurance against near-cospherical local structure. Broad `forAll`-based
DQP-vs-Helix comparisons in `AlphaCrossValidationSpec` are deliberately kept out of `sbt test` (available as
manually-invoked diagnostic methods instead) — a real Helix failure would otherwise masquerade as a DQP
regression or vice versa.

### `FastAlphaHomologyContext` — dual union-find, and a second, MEASURED limitation this one is exposed to

`homology/FastAlphaHomology.scala` (`.claude/DESIGN-alpha-dual-unionfind.md`), a follow-on to the cubical dual
union-find engine (`FastCubicalHomologyContext`, `persistence-engines.md`'s engine 6): builds a dual graph over
`HelixDelaunay`'s own top simplices (ambient dimension 2 only, currently) and computes `H_0`+`H_1` via the same
Alexander-duality/elder-rule union-find, needing `HelixDelaunay` specifically (never `AlphaShapeDQP`, whose own
documented cospherical-degeneracy hazard can emit an oversized simplex outright) because the dual graph needs
the full, untruncated triangulation and "every facet has exactly 1 or 2 containing top simplices."

That precondition is **not guaranteed by construction** the way it is for a cubical grid, and this session
measured it directly rather than assuming it: 1-in-3000 combined across ambient dimension 2/3 on `Gen.double`
random points, isolated further to roughly 1-in-18700 at ambient dimension 2 alone (`Gen.double`, `n∈[6,16]`,
50000 trials) — real, but genuinely rare at the dimension this class targets, likely the SAME underlying
frontier-walk weakness `AlphaCrossValidationSpec`'s own doc comment already reports (an incomplete complex,
missing a connected sub-chain of genuinely-Delaunay faces, with no exception raised), observed through a
different lens here (a bad facet-multiplicity count instead of a missing-face diff against DQP). This class
validates the precondition explicitly and throws the named `FastAlphaTriangulationException` (never a bare
`IllegalStateException`) naming the offending facet(s) rather than building a silently-wrong dual graph — see
`FastAlphaHomologySpec`'s own pinned regression fixture (a concrete 12-point set that reproduces it
deterministically) for the exact exception shape. Unlike an internal-developer exception, this one's message is
deliberately layered for an unsuspecting MATLAB/CLI end user first ("this is NOT an error in your data," a
plain-language explanation of the HelixDelaunay limitation, and the concrete fix — retry with `engine="naive"`/
`"chunks"`/`"cohomology"`, none of which are affected by it), with the facet-count technical detail kept as a
secondary appendix for developers investigating this class itself.

**Wired into `matlab.TDA4j`/`cli` as `engine="fast-alpha"`/`--engine fast-alpha`**, same as the cubical engine
— valid only for `complex=alpha` with `alphaBackend=helix` (the default; `alphaBackend=DQP` is refused, since
this engine cannot consume `AlphaShapeDQP`'s output at all) and ambient dimension 2 (a 3D point cloud is
refused with a message naming the actual dimension). The project lead reviewed the measured ~1-in-18700 rate
and the resulting exception message and signed off on shipping it as a production option rather than leaving it
Scala-only — the judgment call the engine's earlier, unwired state above was deliberately left pending.

## `AlphaComplexDQP` — dual active-set QP, never builds Delaunay at all

Implements Erik Carlsson & John Carlsson, *Computing the alpha complex using dual active set quadratic
programming*, Scientific Reports 14:19824 (2024), <https://doi.org/10.1038/s41598-024-63971-3>. Instead of
building the Delaunay complex and reading off which simplices survive, it answers a per-simplex
*feasibility* query directly — "is this Voronoi/power face nonempty, and does it meet the ball of radius
`r`?" — posed as a convex QP and answered in the Lagrangian dual, which is what lets it scale to very high
ambient dimension where Delaunay is infeasible.

The QP solver follows DAQP (Arnström, Bemporad & Axehill, IEEE TAC 67(8):4362-4369, 2022), collapsed to
Cholesky update/downdate of the active working set (`CholeskyWorkspace`) since the paper's problem is
already in DAQP's canonical least-distance inner form.

### Math cheat sheet

Base vertex `x`, neighbours `x_i`, power weights `p`. **Filtration values are squared radii (powers)** per
the paper's Definition 10 — `AlphaComplexDQP.radiusOf` takes the square root, and
`AlphaShapeDQP.filtrationValue` goes through `radiusOf` (not the raw squared value) so it matches
`HelixDelaunay.filtrationValue`'s units. The dual objective only needs *squared distances*:
`B_ij = (d²(i,x) + d²(j,x) - d²(i,j))/2` — which is why `PowerDistance` sits on squared distance rather than
extending `FiniteMetricSpace` directly, bridging via `PowerDistance.toMetricSpace` only where interop is
actually needed (e.g. `cechNeighbours()`'s VP-tree spatial index). **Caveat**: `B` is PSD only for
Euclidean-embeddable metrics.

`AlphaShapeDQP` always computes the complete, **untruncated** alpha complex, matching `HelixDelaunay`'s own
always-untruncated behavior (a finite radius bound would silently exclude the arbitrarily-large-circumradius
simplices degenerate configurations legitimately produce — see [Degeneracies](degeneracies.md)).
Callers who want an actually radius-truncated alpha complex should call `AlphaComplexDQP.euclidean(points,
maxRadius, maxDimension, settings)` directly.

### Numerical-robustness decisions — settled, not casually retunable

- **`rankTolerance` default is `1e-6`, not the more "obvious" `1e-12`.** A Schur-complement ratio as large as
  `~1e-8` can poison the Cholesky factor and cause genuine non-terminating active-set cycling; `[1e-7,
  1e-5]` is the empirically-verified safe range, and `1e-4` starts silently rejecting genuinely
  non-degenerate directions.
- **Ratio-test ties break by the constraint's global index**, not its position in the working set (Bland's
  rule anti-cycling) — working-set position isn't a stable ordering.
- **Known, accepted limitation**: when the entering variable's Schur complement is small and no active
  inequality can be swapped out to compensate, `DualQP.solve` conservatively treats the candidate as
  infeasible rather than committing the small pivot directly — this can wrongly exclude a genuinely-Delaunay
  simplex near certain near-degenerate configurations. A "more correct" fix (commit anyway when mathematically
  justified) was tried and reverted: two near-identical Schur-complement ratios required opposite handling in
  practice, showing no fixed numerical threshold can safely distinguish the two cases from that ratio alone —
  it depends on the whole working set's conditioning. `solveAtVertex` has a defense-in-depth per-candidate
  catch so an uncharacterized non-convergence case excludes just that one candidate rather than aborting the
  whole complex.
- **Vertex filtration values must go through `space.weight(x)`, not a bare `0.0` default** — only correct in
  the unweighted case; with nonzero weights a `0.0` default silently breaks the monotonicity invariant that a
  vertex's filtration value must not exceed an edge's through it.

`AlphaComplexDQPRegressionSpec` pins hand-verified adversarial point clouds (the two cases above) as
permanent regression tests, alongside a broader property suite (`minTestsOk = 2000`, not scalacheck's
default of 100 — the bugs above had verified failure rates as low as 1-in-12000).
`AlphaComplexDQPSpatialIndexSpec` cross-checks the VP-tree-based neighbor query against a preserved
brute-force reimplementation — correctness never depends on the spatial index, only performance does.

**Scala-specific gotcha writing specs against this file**: give any `Seq[Simplex[_]]` an explicit type
ascription (`val xs: IndexedSeq[Simplex[Int]] = ...`) before calling `.forall`/similar on it — in a specs2
spec mixing the `ScalaCheck` trait, an un-ascribed `.forall` can resolve to a specs2 extension instead of the
standard-library one, breaking type inference inside the lambda.

## Opt-in parallelism

`AlphaDQPSettings.parallel` (default `false`) runs the per-vertex QP solve loop on the common
`ForkJoinPool`; output is deterministic either way. Worth reaching for once a point cloud is large enough
that the per-vertex solve cost dominates (roughly a few hundred points and up) — measured at 2-4x wall-clock
speedup in that regime.

## Honest framing worth keeping in mind while working on either backend

The paper's own benchmarks are mixed against Ripser (loses on 2 of 4 persistence examples) and against
qhull-based Delaunay on some inputs. DQP's genuine value proposition is high ambient dimension (where
Delaunay is infeasible), exact homology rather than persistence diagrams, and much smaller complexes than
Vietoris-Rips when data sits near a low-dimensional subspace — not raw speed. See the
[User's Guide](../user-guide/README.md) for how this should shape what you tell an end user.
