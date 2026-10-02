---
layout: main
title: Alpha complex: DQP vs Helix
---

Two independent backends compute alpha complexes; `AlphaShapes(points, dispatch)` (`alpha/AlphaShapes.scala`)
chooses between them. This page is the developer-facing view. For the user-facing framing (which one to
pick, and the honest tradeoffs), see the [User's Guide](../user-guide/index.md).

## Dispatch

```scala sc:nocompile
object AlphaShapes:
  def apply(pts: Seq[Array[Double]], dispatch: String = "default", requireValidTriangulation: Boolean = false)(using
    epsilon: Epsilon = Epsilon(1e-5)
  ): AlphaShapes
```

`dispatch = "default"` **always resolves to `"helix"` regardless of point-cloud shape** — `"DQP"` must be
requested explicitly. Both backends extend the common `AlphaShapes` abstract class, so they're
dispatch-interchangeable as far as any code consuming the resulting stream is concerned —
`AlphaComplexSpec` runs identical property checks against both to enforce this.

## `HelixDelaunay` — an actual Delaunay triangulation

Builds an actual Delaunay triangulation incrementally: finds a bootstrap simplex, then walks the frontier of
facets, testing candidate points against each facet's supporting hyperplane and circumsphere.
`filtrationValue` returns the unsquared circumradius, matching DQP's own units after `radiusOf`.

**Known limitation:** `HelixDelaunay` can become order-dependent on
near-cospherical point configurations and produce an incorrect triangulation.
The issue becomes more frequent in higher ambient dimensions.

For this reason, broad randomized DQP-vs-Helix cross-validation is kept out of
the default test suite; Helix should not be treated as an unconditional oracle
on near-degenerate inputs.

Broad randomized DQP-vs-Helix cross-validation is therefore kept out of the default test suite.

### `FastAlphaHomologyEngine`

`FastAlphaHomologyEngine` applies the dual-graph union-find strategy used by
`FastCubicalHomologyEngine` to the triangulation produced by
`HelixDelaunay`.

It requires a full triangulation in which every codimension-1 facet has one
or two top-dimensional cofaces. Because `HelixDelaunay` can violate this
precondition on rare degenerate inputs, the engine validates the triangulation
and throws `FastAlphaTriangulationException` rather than constructing an
invalid dual graph.

For ambient dimension `d >= 3`, `H_0` and `H_{d-1}` use the fast union-find
paths while intermediate dimensions are delegated to
`PersistenceInChunksEngine`.

The engine is exposed as `engine="fast-alpha"` and is available only with the
Helix alpha backend.

### Triangulation repair

When `requireValidTriangulation` is enabled, a Helix triangulation that violates
the facet-multiplicity requirement is retriangulated after a small perturbation
of the near-degenerate points. Filtration values are then recomputed from the
original coordinates.

This option is mainly intended for `FastAlphaHomologyEngine`, whose dual graph
requires a valid triangulation.

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

### Numerical robustness

- `rankTolerance` defaults to `1e-6`; smaller tolerances can destabilize the
  active-set Cholesky updates, while substantially larger values can reject
  valid directions.
- Ratio-test ties use the constraint's global index (Bland-style ordering).
- When a small Schur complement cannot be handled safely, `DualQP.solve`
  conservatively rejects that candidate simplex rather than risking
  non-termination.
- Vertex filtration values must use `space.weight(x)`; hard-coding `0.0`
  breaks weighted alpha filtrations.

## Opt-in parallelism

`AlphaDQPSettings.parallel` runs the per-vertex QP solves on the common
`ForkJoinPool`. It is disabled by default and preserves deterministic output.

## Backend tradeoffs

DQP is primarily valuable for high ambient dimension and direct alpha-complex
construction, not as a universal speed improvement over Delaunay-based methods.
See the User's Guide for user-facing tradeoffs.