---
layout: main
title: Alpha complexes
---

### Alpha complexes

The alpha complex is the part of the Delaunay triangulation of the points that lies inside the union of balls of
radius `r`. It has the same persistent homology as the [Čech complex](cech-complexes.md), with far fewer simplices: on
the 60-point circle of the [tutorials](../../tutorials/choosing-a-complex.md), 325 against 36,050. It needs coordinates,
in a low-dimensional space.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8), Array(0.2, 0.5))
val diagram = Persistence(points, complex = AlphaShapes)

val stream = AlphaShapes(points, AlphaBackend.Helix)     // the complex itself
```

The whole complex is built (there is no `maxFiltrationValue`); read it at a smaller scale with `diagram.at(r)`.
Filtration values are radii.

### Two backends

* **`AlphaBackend.Helix`** (the default, `HelixDelaunay`): builds the Delaunay triangulation incrementally. On point
  clouds with nearly cospherical clusters the triangulation can come out invalid; at ambient dimension 4 with 20-30
  points this happens for about one cloud in 170, while no failures were seen in dimensions 2 and 5. Treat its output in
  dimension 4 and above with care.
* **`AlphaBackend.DQP`** (`AlphaShapeDQP`): decides each simplex by a dual active-set quadratic program (Carlsson and
  Carlsson 2024) without building a triangulation. It works in high ambient dimension, where a Delaunay triangulation is
  out of reach. It is not generally faster.

In degenerate position the alpha complex contains higher-dimensional simplices than a triangulation would: four
cospherical points span a 3-simplex, so a square grid in the plane produces 3-simplices. Both backends do this, and
it is correct.

For large planar point clouds, the [fast alpha engine](../homology-computation/fast-alpha-complexes.md) computes the
diagram of the Helix complex without general matrix reduction.
