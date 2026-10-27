---
layout: main
title: Circular and toroidal coordinates
---

### Circular coordinates

A persistent loop in the data gives each point an angle: a map to the circle, `[0, 1)` as a fraction of a turn, that
goes once around the loop (de Silva, Morozov and Vejdemo-Johansson 2011).

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(40)(i => Array(math.cos(i * 0.16), math.sin(i * 0.16)))
val metricSpace = EuclideanMetricSpace(points)

val loops = CircularCoordinates.h1Bars(metricSpace)          // (birth, death) of each loop, most persistent first
val (birth, death) = loops.head
val coordinate = CircularCoordinates.compute(metricSpace, r = (birth + death) / 2, cocycleIndex = 0)
coordinate.theta                                              // point index -> angle in [0, 1)
```

Choose the loop (`cocycleIndex`, counted in the order of `h1Bars`) and a scale `r` at which it is alive, between its
birth and death; the middle is a natural choice. The coordinate is computed from the cohomology of the Vietoris-Rips
complex at scale `r`: an integer cocycle, smoothed to the harmonic one. Only the points in the same connected piece as the
loop get an angle; `theta` maps point indices to angles.

The computation is over a field with an odd prime number of elements (`prime`, 47 by default), and the cocycle is lifted
to integer coefficients, which is checked exactly. If the class has no integer lift at that prime,
`NoIntegerCocycleException` says so; a larger prime usually helps, and a class that is truly torsion (the loop of the
projective plane) has none at any prime.

### From a diagram you already have

`CircularCoordinates(diagram, input)` takes the loop from a persistence diagram instead of computing the Vietoris-Rips
complex itself, so the complex, the field and the engine are the ones you chose for the diagram:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(40)(i => Array(math.cos(i * 0.16), math.sin(i * 0.16)))

val diagram = Persistence(points, maxDimension = 1, complex = Cech, representatives = Representatives.Cocycles)
val loops = CircularCoordinates.h1Bars(diagram)                         // (birth, death), most persistent first
val coordinate = CircularCoordinates(diagram, points, complex = Cech)   // the longest loop, at the middle of its bar
coordinate.theta
```

The `input` is what the diagram was computed from, as `Persistence` took it (points, a metric space or a stream), and
`complex` the construction a point cloud was turned into; the diagram does not remember them. Choose the loop with `bar`
(counted in the order of `h1Bars(diagram)`) and the scale with `r`, between its birth and death. The field is the
diagram's own, so its `characteristic` must be an odd prime (the default, 17, will do); over 2 or the reals the
cocycle has no integer lift, and the call says so.

With `representatives = Representatives.Cocycles` the cocycle of the bar is read off the diagram, restricted to the
complex at `r` and checked exactly over the integers. A diagram of cycles, which is what `Persistence` gives by default,
has the class recovered by one more cohomology computation of that complex, matched to the bar by its birth. If the
`input` is not what the diagram was computed from, the cocycle will not fit it, and the error says to pass the same
complex.

When shorter loops nested inside the one you chose are also alive at `r`, its class is only defined up to adding theirs,
and each engine picks its own representative, so the coordinate you get depends on the engine (every choice is a valid
circle map). Choose `r` where only the loops you ask for are alive to get the same answer from every route.

### Toroidal coordinates

When several loops are alive at the same scale, as on a torus, `computeToroidal` gives each point one angle per loop
(Scoccola, Gakhar, Bush, Schonsheck, Rask, Zhou and Perea 2022):

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val random = new scala.util.Random(1)
val points = Array.fill(120) {
  val (a, b) = (random.nextDouble() * 2 * math.Pi, random.nextDouble() * 2 * math.Pi)
  Array(math.cos(a), math.sin(a), math.cos(b), math.sin(b))
}
val metricSpace = EuclideanMetricSpace(points)
val loops = CircularCoordinates.h1Bars(metricSpace, maxFiltrationValue = 1.8)
val r = (loops.take(2).map(_._1).max + loops.take(2).map(_._2).min) / 2    // both loops alive
val coordinates = CircularCoordinates.computeToroidal(metricSpace, r, cocycleIndices = Seq(0, 1), maxFiltrationValue = 1.8)
coordinates.theta          // one map from point to angle per loop
coordinates.basisChange    // the integer change of basis chosen
```

Any invertible integer combination of the loops describes the same torus, so the coordinates are first changed to the
shortest, most nearly orthogonal combination, by lattice reduction (`reduce = false` keeps the original one). The loops
must be alive at `r` together and lie in one connected piece of the complex. From a diagram,
`CircularCoordinates.toroidal(diagram, points, bars = Seq(0, 1))` takes the same arguments as the circular one, with `bars`
for the loops. The
[circular and toroidal coordinates](../tutorials/circular-and-toroidal-coordinates.md) tutorial checks both against
known angles.

From MATLAB: `TDA4j.h1Bars(points)`, `circularCoordinates(points, r)`, `toroidalCoordinates(points, r, indices)`, with
indices counted from 0.
