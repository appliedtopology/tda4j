---
layout: main
title: Time series, graphs and functions
---

Not every data set is a point cloud. This page has the ways in from a time series, a graph, a function on a graph or a
mesh, and a directed network. Each ends in something `Persistence(...)` reads.

### A time series: sliding windows

A periodic signal traces a closed curve when you plot each window of consecutive samples as a point. `TakensEmbedding`
makes the windows: the point at time `t` is `(x[t], x[t + delay], ..., x[t + (dimension - 1) * delay])`.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val signal = Seq.tabulate(200)(t => math.sin(2 * math.Pi * t / 40))   // period 40
val delay = TakensEmbedding.delayByAutocorrelation(signal, maxLag = 30) // 10, a quarter of the period
val windows = TakensEmbedding(signal, dimension = 2, delay = delay)
Persistence(windows, maxDimension = 1).dim(1).longest                   // the period shows as one long loop
```

The delay decides what you see: too small flattens the curve onto a line, too large folds it over itself.
`delayByAutocorrelation` gives a first guess (the first lag at which the autocorrelation reaches zero), and you can
look at the barcode for a few delays. A signal with two incommensurable periods traces a torus, which needs
`dimension = 3` or more and degree 2. For a series of vectors, `TakensEmbedding.multivariate(rows, dimension)` puts the
rows of a window one after the other, and `stride` skips windows when the series is long.

### A graph: distances along its edges

When a graph says which points are near each other (a nearest-neighbour graph on a manifold, a road or contact network),
the distance along the graph is often a better distance than the one in the ambient space. `GraphMetric.geodesic`
turns weighted edges into the table of shortest-path distances, a metric space like any other:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val circle = PointCloud(Array.tabulate(100)(i => Array(math.cos(2 * math.Pi * i / 100), math.sin(2 * math.Pi * i / 100))))
val along = GraphMetric.nearestNeighbours(circle, k = 2)   // Isomap's graph: join each point to its 2 nearest
along.distance(0, 50)                                      // about pi, the way round, not 2, the way through
Persistence(along, maxDimension = 1).dim(1).longest
```

Two vertices in different components are at distance `Infinity`, which the Vietoris-Rips complex reads as "never
joined", so a disconnected graph has one essential bar for each component. Raise `k` until the graph is connected if you
want one. The table has `n^2` entries, so this is for graphs of a few thousand vertices.

### A graph that stays a graph

For a network, the question is often not the shortest-path distance but the flag complex of the edges themselves: a set
of vertices is a simplex when every two are joined, and it enters at the heaviest of its edges.
`WeightedGraph` holds the edges as neighbour lists, `n + 2m` numbers where a matrix has `n^2`, and `Persistence` grows
the cliques from the lists:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val network = WeightedGraph(4, Seq((0, 1, 1.0), (1, 2, 1.5), (2, 3, 1.0), (3, 0, 2.0)))
Persistence(network).dim(1)               // the square: a loop from 2.0 on, no triangle fills it
WeightedGraph.unweighted(5, Seq((0, 1), (1, 2), (2, 3), (3, 4), (4, 0)))   // every edge at 1: the clique complex
```

A pair that is not an edge is at distance `Infinity`. Edges listed twice count at their lightest weight. The complex is
held in memory, so a dense graph with many large cliques is expensive; `maxDimension` (default 2) and
`maxFiltrationValue` keep it in bounds. A file of `i j weight` lines (Ripser's `sparse` format) is read into a graph
with `Ripser.readSparseGraph(path)`, and into a matrix with `Ripser.readSparseDistanceMatrix(path)` if you want the
matrix.

### A function on a graph or a mesh: lower-star filtrations

A value at each vertex (a height on a mesh, a measurement at each node) filters the whole complex: a simplex enters when
its last vertex does. `LowerStar` takes the facets of the complex and the value of each vertex; `LowerStar.ofGraph` takes a
graph's edges.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val heights = Vector(0.0, 3.0, 1.0, 4.0, 2.0)
val path = LowerStar.ofGraph(5, Seq((0, 1), (1, 2), (2, 3), (3, 4)), heights)
Persistence(path, maxDimension = 0)       // components of the sublevel sets: (0, inf), (1, 3) and (2, 4)
Persistence(LowerStar.ofGraph(5, Seq((0, 1), (1, 2), (2, 3), (3, 4)), heights, superlevel = true), maxDimension = 0)
```

The three bars are the three minima of the function; the second and third die where each joins an older component, over
the peaks of height 3 and 4. With `superlevel = true` the superlevel sets are filtered instead, and the bars are in
negated units, as for a bright-feature image.

### A directed network: ordered cliques

In a directed graph the order of the vertices matters. The directed flag complex has a simplex `(v0, ..., vk)` for each
sequence in which every edge `vi -> vj` with `i < j` is present, so a directed 3-cycle `a -> b -> c -> a` is a hole
(no ordering has all three edges pointing forward) and a transitive triangle `a -> b -> c`, `a -> c` is filled. This is
the construction behind the cliques-and-cavities analyses of neural networks. Its cells are `DirectedSimplex` values.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val cycle = DirectedFlagStream(3, Seq((0, 1, 1.0), (1, 2, 1.0), (2, 0, 1.0)), maxDimension = 1)
val transitive = DirectedFlagStream(3, Seq((0, 1, 1.0), (1, 2, 1.0), (0, 2, 1.0)), maxDimension = 2)
Persistence(cycle).bettiNumbers           // Vector(1, 1): a loop
Persistence(transitive).bettiNumbers      // Vector(1, 0, 0): filled
```

An edge enters at its weight and a simplex at its largest. Every ordering of every clique is a cell, so a graph with an
edge in both directions everywhere has many more cells than its clique complex: the complete digraph on 4 vertices has
four 0-cells, twelve 1-cells, twenty-four 2-cells and twenty-four 3-cells, and nine independent 3-spheres.
