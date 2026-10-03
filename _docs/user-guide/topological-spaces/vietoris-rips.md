---
layout: main
title: Vietoris-Rips complexes
---

### Vietoris-Rips

At scale `r`, the Vietoris-Rips complex has a simplex for every set of points that are pairwise within distance `r`; a
simplex enters at its diameter. It needs only distances, so it works from a point cloud or from any distance matrix.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(20)(i => Array(math.cos(i * 0.31), math.sin(i * 0.31)))
val diagram = Persistence(points)                                  // degrees 0 and 1

val metricSpace = EuclideanMetricSpace(points)                     // or any FiniteMetricSpace[Int]
val stream = VietorisRips(metricSpace, maxDimension = 1, maxFiltrationValue = 1.5)
Persistence(stream)                                                // the same, from the stream
```

`maxFiltrationValue` defaults to the *minimum enclosing radius* of the points (the smallest `r` at which some point is
within `r` of all others): past it the complex is a cone and nothing new is born, so stopping there gives the same
diagram. Pass `Double.PositiveInfinity` for the whole complex. `maxDimension` is the top homological degree; the stream
contains simplices one dimension higher, which is what lets a class of that degree die.

`VietorisRips(..., implementation = VietorisRips.Implementation.RipserCoface)` (or `Inorder`, `Incremental`) picks
another construction of the same complex, cell for cell; the default, `Enumerating`, is a good choice. For the fastest
computation, `Persistence(points, engine = Persistence.Engine.Ripser)` runs Ripser's algorithm on the metric space
without building a stream at all.

Distance matrices: `ExplicitMetricSpace(rows)` wraps a full symmetric matrix (a `Seq[Seq[Double]]`), and the readers in
[files](../input-output.md) produce one from Ripser, DIPHA and CSV formats.

### Distance-to-measure weighting

A few stray points can plant spurious loops in a Vietoris-Rips diagram. The distance-to-measure weighted Rips complex
(Anai, Chazal, Glisse, Ike, Inakoshi, Tinarrage, Umeda) lets each point enter only once the scale reaches its weight,
the typical distance to its `k` nearest neighbours, so isolated points enter late:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(20)(i => Array(math.cos(i * 0.31), math.sin(i * 0.31))) :+ Array(0.1, 0.0)
val metricSpace = EuclideanMetricSpace(points)
val diagram = Persistence(DtmRips.fromNeighbours(metricSpace, k = 5, maxDimension = 1))
val weights = DistanceToMeasure(metricSpace, 5, 2.0)        // the weights themselves: k = 5, exponent q = 2
```

`k` should be larger than any cluster you want to treat as noise and small next to the features you want to keep. `p`
(1, the default, or 2) chooses between the two weighted constructions of the paper. The weights change the units, so
compare DTM diagrams with each other, not with plain Vietoris-Rips ones. `DtmRips(metricSpace, weights, ...)` takes
weights of your own. The [noise and outliers](../../tutorials/noise-and-outliers.md) tutorial works through an example.
From MATLAB and the command line: `complex=dtm-rips` with `dtmK`; `complex=dtm-alpha` is the alpha-complex version.

### Edge collapse

Many edges of a Vietoris-Rips complex are dominated by another vertex and cannot change its persistent homology. Edge
collapse (Boissonnat and Pritam; Glisse and Pritam) removes them before any higher simplex is built. The result is a
metric space with the same persistent homology at every scale, usually much smaller:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(40)(i => Array(math.cos(i * 0.16), math.sin(i * 0.16)))
val collapsed = EdgeCollapse.collapse(EuclideanMetricSpace(points))
collapsed.stats                      // edges before and after
val diagram = Persistence(collapsed) // the same diagram as Persistence(points)
```

Representatives found on the collapsed complex are cycles of the original complex too. From MATLAB and the command
line: `edgeCollapse=true`.
