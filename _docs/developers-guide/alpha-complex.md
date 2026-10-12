---
layout: main
title: "Alpha complex: Bowyer-Watson, Helix and DQP"
---

Three backends compute alpha complexes; `AlphaShapes(points, backend)` (`alpha/AlphaShapes.scala`) chooses between
them. Two triangulate (`BowyerWatsonDelaunay`, `HelixDelaunay`, both `DelaunayAlphaShapes`); `AlphaComplexDQP` does not. This page is the developer-facing view. For the user-facing framing (which one to
pick, and the honest tradeoffs), see the [User's Guide](../user-guide/index.md).

## Dispatch

```scala sc:nocompile
object AlphaShapes:
  def apply(
    points: PointCloud,
    backend: AlphaBackend = AlphaBackend.Default,
    requireValidTriangulation: Boolean = false,
    maxRadius: Optional[Double] = Optional.empty,
    maxDimension: Optional[Int] = Optional.empty
  )(using epsilon: Epsilon = Epsilon(1e-5)): AlphaShapes
```

With a `maxRadius`, every backend returns the simplices of alpha value at most `maxRadius`, with the same values (in
general position; on cospherical points DQP keeps the simplex they span where a triangulation splits it): `BowyerWatson`
and `Helix` build the whole triangulation and filter it (`RadiusLimitedAlphaShapes`); `DQP` builds only up to the
radius, dimension by dimension, up to `maxDimension + 1` (`AlphaComplexDQPStream`). `AlphaBackend.Default` resolves to
`BowyerWatson` for points at most 4 coordinates wide and `Helix` above, unless a radius is given and
`AlphaShapes.prefersDQP` expects DQP to be faster: it estimates the mean number of points within `2 maxRadius` of a
point (64 sample points) and compares a per-point cost model fitted to measurements (DQP about `c_d k^1.6`,
BowyerWatson about `b_d (n/1000)^0.2`, Helix in 5-D about `150 (n/1000)^0.4`; in ambient dimension 6 and up DQP
whenever a radius is given). `AlphaShapes.fromPoints` (the `Persistence` verb) and the MATLAB/CLI `maxFiltrationValue`
pass the radius through; `engine=fast-alpha` needs a whole triangulation and refuses a radius. The facade's `alphaBackend`
string goes through `AlphaBackend.parse` (default `default`). All backends extend the common `AlphaShapes` abstract
class, so they are interchangeable for any code consuming the stream: `AlphaComplexSpec` runs identical property
checks against both, and `AlphaDispatchSpec` checks that the radius-limited complexes and the verb's bars agree.

## `DelaunayAlphaShapes` — the alpha complex of a triangulation

The shared base of the two triangulating backends: a subclass supplies the top simplices, their values and any exactly
repeated points; the base enumerates the faces (a hash table of sorted vertex rows per dimension, each face found from
its immediate cofaces), assigns values top-down by the Gabriel rule below, and sorts each dimension in the filtration
order. Circumspheres come from a Householder QR of the edge vectors (the Gram system squares the condition number and
lost digits on slivers).

## `BowyerWatsonDelaunay` — incremental Delaunay with exact predicates, 1 to 4 dimensions

`alpha/BowyerWatson.scala`, predicates in `alpha/DelaunayPredicates.scala`. Bowyer–Watson on flat arrays (cell
vertices and neighbours, `d + 1` each, a free list), with a symbolic vertex at infinity so the hull needs no special
case; points are inserted in BRIO order (random rounds, Z-order within a round) and located by a visibility walk from
the last new cell. Orientation and in-sphere are determinants expanded over permutations, evaluated in floating point
with a conservative error bound and redone in exact `BigDecimal` arithmetic when the bound does not settle the sign.
An exact in-sphere tie is broken by a symbolic perturbation of the lifted coordinate in descending point index, so the
result is the Delaunay triangulation of an infinitesimally perturbed point set: valid on grids and cospherical input,
and identical for every insertion order (`BowyerWatsonSpec`). Exactly repeated points keep the smallest index in the
triangulation and join it by an edge of value 0. The input is first projected onto its affine span; a span of more
than 4 dimensions is refused (the determinant expansion is `n!` terms; Helix and DQP cover higher dimensions).

## `HelixDelaunay` — an actual Delaunay triangulation

Builds an actual Delaunay triangulation incrementally: finds a bootstrap simplex, then walks the frontier of
facets, testing candidate points against each facet's supporting hyperplane and circumsphere.
`filtrationValue` returns unsquared radii, matching DQP's own units after `radiusOf`. Values are assigned top-down: a
top-dimensional simplex gets its circumradius; a lower simplex gets the radius of its own smallest circumsphere (centre
in its affine hull) if it is Gabriel -- no vertex of a coface strictly inside that sphere -- and otherwise the smallest
value among its immediate cofaces. For points in general position this is the alpha filtration exactly, and
`HelixDqpAgreementSpec` checks that Helix and DQP give the same barcode in dimensions 2, 3 and 4.

**Known limitation:** `HelixDelaunay`'s greedy walk can produce an invalid
triangulation on near-cospherical point configurations. Every result is checked,
and an invalid one is repaired by retriangulating slightly perturbed points
(see Triangulation repair below). At a very small tolerance (`1e-9`) a 3-D grid
can still fail to repair.

For this reason, broad randomized DQP-vs-Helix cross-validation is kept out of
the default test suite; Helix should not be treated as an unconditional oracle
on near-degenerate inputs.

### `FastAlphaHomologyEngine`

`FastAlphaHomologyEngine` applies the dual-graph union-find strategy used by
`FastCubicalHomologyEngine` to a Delaunay triangulation (`BowyerWatsonDelaunay`
or `HelixDelaunay`).

It requires a full triangulation in which every codimension-1 facet has one
or two top-dimensional cofaces. `BowyerWatsonDelaunay`'s exact predicates
guarantee this. Because `HelixDelaunay` can violate it on rare degenerate
inputs, the engine validates the triangulation and throws
`FastAlphaTriangulationException` rather than constructing an invalid dual
graph.

For ambient dimension `d >= 3`, `H_0` and `H_{d-1}` use the fast union-find
paths while intermediate dimensions are delegated to
`CellularCohomologyEngine` (cycles by the involution).

The engine is exposed as `engine="fast-alpha"` and is available with the
triangulating alpha backends (`default`, `bowyer-watson`, `helix`), not DQP.

### Triangulation repair

When `requireValidTriangulation` is enabled, a Helix triangulation that violates
the facet-multiplicity requirement is retriangulated after a small perturbation
of the near-degenerate points. Filtration values are then recomputed from the
original coordinates. Without the option, a cheaper structural check runs on
every triangulation, and a failure is repaired the same way.

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