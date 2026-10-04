---
layout: main
title: The fast alpha engine
---

#### A faster engine for alpha complexes

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)
given Epsilon = Epsilon(1e-5)        // the numerical tolerance of the triangulation

val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8), Array(0.2, 0.5))
val helix = HelixDelaunay(points)
val bars = FastAlphaHomologyEngine[Double]().persistentHomology(helix)
```

`FastAlphaHomologyEngine` gives the same bars, with representatives, as the general engines on the alpha complex of the
Helix backend, by union-find on the vertices (degree 0) and on the dual graph of the top-dimensional simplices (the top
degree). In the plane those two cover everything; in 3-D and above, the degrees in between are computed by the
cohomology engine. It does not work with the DQP backend, which builds no triangulation.

It relies on the triangulation being valid. On some point clouds (about 1 in 18,700 in the plane, about 1 in 1,700 in
3-D with 20-30 points) it is not, and the engine throws a `FastAlphaTriangulationException` saying so; any general
engine handles the same points. Alternatively `HelixDelaunay(points, requireValidTriangulation = true)` (also an option
of `AlphaShapes(...)`, MATLAB and the command line) repairs the triangulation:
it moves the nearly tied points slightly, triangulates again, and recomputes every radius from the original
coordinates. The repair is tested in dimensions 2 and 3.

From MATLAB and the command line: `complex=alpha` with `engine=fast-alpha`.
