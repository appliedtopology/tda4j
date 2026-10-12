---
layout: main
title: Complexes
---

A filtered complex is what persistent homology reads. Each complex below is an object that takes your data and a
`maxDimension` (the top homological degree you want) and returns a stream of cells that any engine, and
`Persistence(...)`, can read. Three of them take the points directly as an option of `Persistence`:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(20)(i => Array(math.cos(i * 0.31), math.sin(i * 0.31)))
Persistence(points)                          // Vietoris-Rips
Persistence(points, complex = Cech)
Persistence(points, complex = AlphaShapes)
Persistence(SparseRips(EuclideanMetricSpace(points), epsilon = 0.5, maxDimension = 1))   // any other complex
```

| complex | from | filtration value of a simplex | page |
|---|---|---|---|
| Vietoris-Rips | points, or any distances | its diameter | [Vietoris-Rips](vietoris-rips.md) |
| DTM-weighted Rips | points, or any distances | diameter, with outliers entering late | [Vietoris-Rips](vietoris-rips.md#distance-to-measure-weighting) |
| Čech | points | radius of its smallest enclosing ball | [Čech](cech-complexes.md) |
| alpha | points, low dimension | radius, restricted to the Delaunay triangulation | [alpha](alpha-complexes.md) |
| sparse Rips | points, or any distances | an approximation of the diameter, within `1 + ε` | [sparse Rips](sparse-vietoris-rips.md) |
| witness | points, or any distances | a few landmarks, witnessed by all the points | [witness](witness-complexes.md) |
| Dowker | a relation (a matrix, any shape) | when a column relates to all its rows | [Dowker](dowker-complexes.md) |
| cubical | an image or a voxel grid | the pixel values | [cubical](cubical-complexes.md) |
| simplicial set | a space you build | your choice | [simplicial sets](simplicial-sets.md) |

**Units.** Vietoris-Rips and its variants report diameters; Čech and alpha report radii. Bars from different complexes
are compared by their shape, not their endpoints.

**Cost.** The Vietoris-Rips complex grows fast with the number of points and the dimension; alpha, sparse Rips and
witness complexes are the ways to keep it small, and edge collapse shrinks it without changing the answer.
