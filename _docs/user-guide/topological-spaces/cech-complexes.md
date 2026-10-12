---
layout: main
title: Čech complexes
---

### Čech complexes

At scale `r`, the Čech complex has a simplex for every set of points whose balls of radius `r` have a common point; a
simplex enters at the radius of its smallest enclosing ball. By the nerve theorem it has the shape of the union of the
balls at every scale. It needs coordinates.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8), Array(0.2, 0.5))
val diagram = Persistence(points, complex = Cech, maxFiltrationValue = 2.0)

val stream = Cech(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 2.0)   // the complex itself
```

Filtration values are radii, not diameters: a loop born at diameter `d` in Vietoris-Rips is born near radius `d / 2`
here. The Čech complex is larger than the Vietoris-Rips complex at the same scale; the [alpha complex](alpha-complexes.md)
has the same diagram at a fraction of the size, for points in low dimension. `parallelFiltrationValue = true` computes
the radii on several threads (the output is the same).

The Čech complex is not a flag complex, so the Ripser engine does not apply; the cohomology (the default here), chunks
and naive engines do. It is expensive: each simplex needs its own smallest enclosing ball, and it has many more
simplices than the Vietoris-Rips complex at the same scale. Pass `maxDimension = 1` when you only need components and
loops, and use the chunks engine only with `maxDimension = 1`.
