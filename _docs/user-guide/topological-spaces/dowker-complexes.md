---
layout: main
title: Dowker complexes
---

### Dowker complexes

A relation between two sets, given as a matrix `R(x, w)` of strengths (smaller is a stronger tie, `Infinity` for never),
has a Dowker complex: a set of rows is a simplex at time `t` when some column relates to every one of them by time `t`,
that is `f(σ) = min over w of max over x in σ of R(x, w)`. Nothing needs to be a metric: the rows and columns can be
different sets of different sizes, and the relation need not be symmetric (the weights of a directed network, for
example).

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val relation = Array(
  Array(0.0, 1.0, 2.0),  // row 0's relation to each of 3 columns
  Array(1.0, 0.0, 1.0),
  Array(2.0, 1.0, 0.0)
)
val rows = Persistence(Dowker(relation, maxDimension = 1))
val columns = Persistence(Dowker(relation, maxDimension = 1, dual = true))   // the complex on the other side
rows.triples.sorted == columns.triples.sorted                               // true: Dowker duality
```

**Duality.** The complex on the rows and the complex on the columns (`dual = true`) have the same persistent homology
(Chowdhury and Mémoli's functorial Dowker theorem). So build whichever side is smaller; the representatives are on
the side you built, and `Dowker.lift(diagram, relation)` moves them to the other side (below).

**Landmarks and witnesses.** With the distances from a few landmarks (rows) to all the data points (columns) as the
relation, `WitnessGeometry(metricSpace, landmarks).D`, a set of landmarks is a simplex at `t` when one data point is
within `t` of all of them: the Čech complex of the landmarks, with the common point of the balls required to be a data
point. It is contained in every [witness complex](witness-complexes.md) at every `t`, but it is none of them: the lazy
`Witness(nu = 0)` has the same edges and fills in every triangle whose edges are present, and the general variant lets
a witness of a `k`-simplex reach `t` beyond its `(k+1)`-th nearest landmark. Its dual is the complex on all the data
points whose simplices are the sets that fit in one landmark's ball of radius `t`: far larger, with the same barcode.

**Representatives on the other side.** The complex on the landmarks is small, but its representatives are cycles
through landmarks. `Dowker.lift` carries each one to the other side of the relation by the map behind Dowker duality,
keeping the bars: here, to a cycle through the data points themselves, at the bar's birth.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(200)(i => Array(math.cos(i * 0.07), math.sin(i * 0.07)))
val metricSpace = EuclideanMetricSpace(points)
val landmarks = LandmarkSelector.maxmin(metricSpace, numLandmarks = 20).landmarks
val geometry = WitnessGeometry(metricSpace, landmarks)   // the distances from each landmark to every point
val diagram = Persistence(Dowker(geometry.D, maxDimension = 1))
val onPoints = Dowker.lift(diagram, geometry.D)          // the same bars, each cycle on the points' own numbers
onPoints.dim(1).longest.get.representative.cells.toVector
```

A loop through landmarks `l0, l1, l2, ...` becomes the loop `l0, w01, l1, w12, l2, ...`, where `w01` is the point
that sees both `l0` and `l1` soonest. Each edge of it lies in one landmark's ball of radius `t`, so it is also a cycle of
the Čech complex of all the points at `t`. For a diagram computed with `dual = true`, pass `dual = true` to `lift` as
well. MATLAB, the command line and Python take the option `representativesOn` (`rows` or `columns`).

A diagram of cocycles (`representatives = Representatives.Cocycles`) lifts the same way, by the transpose of the map
that goes back from the points to the landmarks: each cocycle becomes a cocycle of the points' complex for the same bar.
That is what circular coordinates read, so the coordinates of every point come from a computation on the landmarks:
`CircularCoordinates(Dowker.lift(cocycles, geometry.D), Dowker(geometry.D, maxDimension = 1, dual = true))`. For many
points, `CircularCoordinates.sparse(diagram, geometry.D)` is the cheaper choice: Perea's sparse coordinates, computed on
the landmarks and spread to the points by a partition of unity (see the
[circular coordinates](../circular-coordinates.md) page). A lifted cocycle has a value on every simplex
of its degree on the points' side below the bar's death, so it is much larger than the cocycle on the landmarks, too
large to hold in degree 2 for many points. Each of its values on an edge is the sum of two values of the landmark
cocycle; if the sum leaves the range that the prime field lifts back to the integers, `CircularCoordinates` says the
cocycle has no integer lift, and a larger `characteristic` gives it room.

For a plain yes-or-no relation, `DowkerGeometry.fromBoolean(table)` gives the classical, unfiltered Dowker complex. The
complex is not a flag complex, so the Ripser engine does not apply. The [networks tutorial](../../tutorials/networks-and-relations.md)
works through an example.

From MATLAB, `TDA4j.computeFromRelation(relation, options)` (with the option `dual`); from the command line,
`--input-format csv-relation` (and `--dual`).
