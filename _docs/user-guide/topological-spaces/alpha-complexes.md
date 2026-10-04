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
val upTo = Persistence(points, complex = AlphaShapes, maxFiltrationValue = 0.4)  // only radii up to 0.4
```

Filtration values are radii. Without `maxFiltrationValue` the whole complex is built; with it, only the simplices of
radius at most that value (also `AlphaShapes(points, maxRadius = r)`), and for a small radius that is much cheaper.

### Backends

* **`AlphaBackend.Helix`** (`HelixDelaunay`): builds the whole Delaunay triangulation, then reads the alpha values off
  it. It checks every triangulation it builds and repairs one that fails, so degenerate inputs such as grids come out
  valid.
* **`AlphaBackend.DQP`** (`AlphaShapeDQP`): decides each simplex by a dual active-set quadratic program (Carlsson and
  Carlsson 2024), dimension by dimension, among points within twice the radius of each other. It never builds the
  whole triangulation, so a small radius makes it cheap, and in high ambient dimension it reaches the low-dimensional
  simplices where a Delaunay triangulation is out of reach. Without a radius it is much slower than Helix.
* **`AlphaBackend.Default`** (the default): Helix without a radius; with one, whichever of the two is expected to be
  faster, judged from how many points lie within twice the radius of a point. The result is the same either way.

In degenerate position the alpha complex contains higher-dimensional simplices than a triangulation would: four
cospherical points span a 3-simplex, so a square grid in the plane produces 3-simplices with DQP. Helix triangulates
such a cluster instead. The persistent homology is the same, up to zero-length bars.

For large planar point clouds, the [fast alpha engine](../homology-computation/fast-alpha-complexes.md) computes the
diagram of the Helix complex without general matrix reduction.
