---
layout: main
title: Circular coordinates
---


For a point cloud with cyclic/periodic structure (e.g. samples along a loop), `TDA4j.h1Bars`/
`circularCoordinates` (de Silva-Morozov-Vejdemo-Johansson 2011) turn a persistent H¹ class into a map from
each point to an angle in `[0, 1)` — a genuinely topological coordinate, not a barcode, so these are their own
entry points rather than a new `complex=` value on `computeFromPoints`:

```java
double[][] bars = TDA4j.h1Bars(points);      // row i: (birth_i, death_i), sorted by persistence descending
double r = bars[0][0] + (bars[0][1] - bars[0][0]) * 0.5; // pick r inside the most persistent bar's own range

CircularCoordinatesResult result = TDA4j.circularCoordinates(points, r); // cocycleIndex=0, prime=47 defaults
double[] theta = result.theta();             // one entry per input point, Double.NaN outside the class's own
                                              // connected component -- not every point necessarily gets one
boolean covered = result.hasCoordinate(3);
```

`h1Bars` is the required first call: there is no way to pick a meaningful `r` without first knowing a target
bar's own `[birth, death)` range. `circularCoordinates` throws `IllegalArgumentException` for an `r` outside
that range (or a bad `cocycleIndex`/`prime`), and `NoIntegerCocycleException` (also a plain `RuntimeException`,
so it crosses the MATLAB bridge the same way) if the chosen class has no exact integer lift at `prime` —
usually resolved by retrying with a larger odd prime; a genuinely torsion class (no real/integer lift at any
prime, RP²'s own fundamental class being the standard example) will keep failing regardless. Not mirrored on
the CLI, for the same reason as the vectorizations and boundary-matrix export above (its output is a per-point
array, not a diagram) plus the inherently two-step, data-dependent nature of picking `r` — see the [Developer's
Guide](../developers-guide/architecture.md)'s `CircularCoordinates` section for the full construction
(the truncated-complex `K_r` reframing, the harmonic-smoothing linear system, and the integer-lift check).

### Toroidal coordinates

When a data set has SEVERAL independent cyclic structures alive at once (e.g. samples on a torus), picking
`k` bars from `h1Bars` and calling `circularCoordinates` on each separately gives `k` valid angle maps, but not
a canonical ONE: any unimodular integer combination of `k` independent H¹ generators is an equally valid choice
of generators for the same cohomology, so which combination a persistent-cohomology computation happens to
return is arbitrary (this is the ambiguity Edelsbrunner raised about circular coordinates when the construction
was first presented). `TDA4j.toroidalCoordinates` picks the combination that is shortest and most nearly
orthogonal under the classes' own harmonic-representative inner product, via lattice reduction (Scoccola,
Gakhar, Bush, Schonsheck, Rask, Zhou, Perea 2022, "Toroidal Coordinates," arXiv:2212.07201):

```java
double[][] bars = TDA4j.h1Bars(points);
// pick k indices simultaneously alive over a common range, and an r inside that intersection
ToroidalCoordinatesResult result = TDA4j.toroidalCoordinates(points, r, new int[] {0, 1});

double[] theta0 = result.theta(0);           // coordinate 0 across all points, Double.NaN outside the component
double[] theta1 = result.theta(1);           // coordinate 1
int[][] basisChange = result.basisChange();  // the unimodular integer combination LLL settled on
```

Pass `reduce=false` (the 5-argument overload) to get the SAME `k` coordinates without reduction (`basisChange`
the identity) — useful for comparing directly against the reduced version via `result.originalGram()`/
`result.reducedGram()`, or to opt out if the raw persistent-cohomology basis is already what you want. Same
`IllegalArgumentException`/`NoIntegerCocycleException` behavior as `circularCoordinates`, plus: `cocycleIndices`
must be duplicate-free, `r` must lie in every chosen class's own `[birth, death)` simultaneously, and every
chosen class must live on the same connected component of `K_r` (two classes native to disconnected pieces of
the data have no joint torus coordinate to be given). Not mirrored on the CLI, same reasoning as
`circularCoordinates` above — see the [Developer's Guide](../developers-guide/architecture.md)'s
`LatticeReduction`/`computeToroidal` section for the full construction.
