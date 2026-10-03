---
layout: main
---

### Alpha complexes

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8), Array(0.2, 0.5))
val shape = AlphaShapes(points, AlphaBackend.Helix)            // or AlphaBackend.DQP: the alpha complex as a stream
val diagram = Persistence(points, complex = AlphaShapes)       // or straight to its persistence diagram
```

`AlphaShapes(points)` with no backend, or `AlphaBackend.Default`, always resolves to `AlphaBackend.Helix` — ask for
`AlphaBackend.DQP` explicitly if you want it. See "Which alpha-complex backend?" below for the tradeoffs.

## Which alpha-complex backend?

- **`AlphaBackend.Helix`** (`HelixDelaunay`) — an actual Delaunay triangulation, computed incrementally. What
  `AlphaBackend.Default` resolves to. Has a known, quantified failure mode on point clouds with a
  near-cospherical local cluster: zero failures across 20,000-trial fuzz testing at ambient dimension 2 and
  5, but roughly 1-in-170 at ambient dimension 4 with 20-30 points, on ordinary-looking input. Don't treat
  its output as unconditionally reliable ground truth at ambient dimension 4 or higher.
- **`AlphaBackend.DQP`** (`AlphaShapeDQP`) — a dual active-set quadratic-programming method (Carlsson & Carlsson 2024)
  that never builds a Delaunay triangulation at all. Its real strength is high ambient dimension, where
  Delaunay becomes infeasible, and exact homology rather than an approximate persistence diagram. The
  paper's own published benchmarks are mixed — it loses to Ripser on 2 of 4 of its own persistence examples,
  and to qhull-based Delaunay on some inputs. The honest value proposition is high-dimensional feasibility
  and exactness, not raw speed.

Both backends agree that in degenerate (cospherical) point configurations — e.g. points on a regular grid —
the alpha complex genuinely contains higher-dimensional simplices than a triangulation-based mental model
would suggest (a unit grid in the plane produces 3-simplices, one per unit square, not just triangles). This
is correct behavior, not a bug in either backend; users coming from CGAL or GUDHI may find it surprising.
