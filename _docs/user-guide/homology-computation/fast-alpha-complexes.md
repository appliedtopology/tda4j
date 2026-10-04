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
val delaunay = BowyerWatsonDelaunay(points)  // or HelixDelaunay(points) above 4 dimensions
val bars = FastAlphaHomologyEngine[Double]().persistentHomology(delaunay)
```

`FastAlphaHomologyEngine` gives the same bars, with representatives, as the general engines on the alpha complex of a
Delaunay triangulation (the BowyerWatson or Helix backend), by union-find on the vertices (degree 0) and on the dual graph of the top-dimensional simplices (the top
degree). In the plane those two cover everything; in 3-D and above, the degrees in between are computed by the
cohomology engine. It does not work with the DQP backend, which builds no triangulation.

It relies on the triangulation being valid. BowyerWatson's exact predicates guarantee it. Helix checks every
triangulation it builds and repairs one that fails (it moves the points slightly, triangulates again, and recomputes
every radius from the original coordinates), so on ordinary input this holds. If it ever does not, the engine throws a `FastAlphaTriangulationException` saying so, and
any general engine handles the same points. `HelixDelaunay(points, requireValidTriangulation = true)` (also an option
of `AlphaShapes(...)`, MATLAB and the command line) adds a check for cavities and raises an error instead of returning
a triangulation it could not repair.

From MATLAB and the command line: `complex=alpha` with `engine=fast-alpha`.
