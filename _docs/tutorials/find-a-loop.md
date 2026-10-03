---
layout: main
title: Finding a loop in noisy data
---

The most common question in topological data analysis is also the simplest: *does my data have a hole in it?* This
tutorial answers it for a small noisy point cloud, from reading the file to reading the barcode, and introduces what every
other tutorial uses: `Persistence`, the persistence diagram it returns, and the question of which bars are signal.

**The data.** [`data/noisy-circle.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/noisy-circle.csv)
holds 60 points sampled at random angles from the unit circle, each coordinate then perturbed by Gaussian noise of size
0.05. (Every data file in these tutorials is produced by a seeded generator checked into the repository, so you can
rebuild or change it.) There is one obvious hole in this data, and no point lies on it.

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val diagram = Persistence(points)
println(diagram)
// PersistenceDiagram(64 bars, degrees 0..2)
//   H0: 60 bars, longest [0.000, Infinity) [0.000, 0.4836) [0.000, 0.3752) [0.000, 0.2874) [0.000, 0.2747) ...
//   H1: 1 bar, longest [0.5945, 1.707)
//   H2: 3 bars, longest [1.710, 1.813) [1.750, 1.800) [1.739, 1.761)
```

`Persistence(points)` builds the Vietoris-Rips complex of the points (at each scale, join every group of points that
are pairwise within that distance of each other), computes its persistent homology in degrees 0, 1 and 2 (components,
loops and voids), and returns the diagram: one bar per feature, from the scale where it appears to the scale where it
disappears.

## Reading the diagram

There is one bar in degree 1: one loop. The 60 bars in degree 0 are the 60 points, merging into one component as the
scale grows; the one that never dies, `[0, Infinity)`, says the data is one connected piece. The three bars in degree 2
are small voids that open for a moment while the loop is being filled in, around scale 1.7, and close again; on data
from a circle they are noise.

```scala sc:nocompile
val loop = diagram.dim(1).longest.get             // [0.595, 1.707): born at scale 0.59, filled in at 1.71
val longestGap = diagram.dim(0).bars.map(_.persistence).filter(_.isFinite).max   // 0.484
```

Reading a barcode comes down to comparing bars with each other. The loop lives for 1.11, more than twice as long as the
longest-lived finite bar in degree 0, which measures the biggest gap in the sample (where two groups of points finally
join). That is a circle. The loop is born at 0.59, not at the typical spacing between neighbours, because it needs
*every* gap around the circle to be closed, and with 60 random angles the biggest gap is large. It dies at 1.71, close to
√3 = 1.73, the scale at which the points of a unit circle start to see across it.

Many bars in degree 0 are short: points that are close to each other merge early. They are not wrong, but they describe
the spacing of your sample, not its shape. Two ways to set them aside:

```scala sc:nocompile
diagram.longerThan(0.1).size     // 24 bars: everything that lives longer than 0.1
diagram.significant().size       // 61 bars: longer than 1% of the point cloud's enclosing radius (1.95)
```

`longerThan` takes a threshold in the units of the diagram; `significant()` takes a fraction of the data's own scale, which
is what the command line and MATLAB show by default.

## Seeing the loop itself

The diagram does not only say *that* there is a loop: every bar carries a representative. There are two kinds, and you
choose between them with the engine.

By default `Persistence` computes persistent *cohomology* (with Ripser's algorithm, much the fastest way to reach degree
2), and a representative is a **cocycle**: a set of edges that cuts *across* the loop, like a cut through a ring. It is
what [circular coordinates](circular-and-toroidal-coordinates.md) are built from.

```scala sc:nocompile
val cocycle = loop.representative
cocycle.cells.size               // 121 edges, each a pair of row numbers in the CSV
```

To see *where* the loop is, ask for a **cycle**, a path of edges going around it. The chunks engine computes homology and
gives cycles; it is slow in degree 2 on a Vietoris-Rips complex, so ask it for degrees 0 and 1 only:

```scala sc:nocompile
val withCycles = Persistence(points, maxDimension = 1, engine = Persistence.Engine.Chunks)
val cycle = withCycles.dim(1).longest.get.representative
cycle.cells.size                 // 52 edges
```

The bars are the same either way. Each cell is an edge between two points of your data (by row number, starting at 0).
Having the cycle means you can mark which points make up the hole, which is often the real answer to "where is it?". On
noisy data the cycle is a jagged path rather than a clean polygon: it is *a* representative of the loop, one of many
equivalent ones.

## Cutting the complex off early

The Vietoris-Rips complex grows fast: up to triangles, these 60 points already give 24,711 simplices (and 231,961
tetrahedra on top of those for degree 2). `Persistence` stops
at the enclosing radius by default, past which nothing new is born. You can stop earlier when you know what scale you
care about:

```scala sc:nocompile
val short = Persistence(points, maxFiltrationValue = 1.0)
val shortLoop = short.dim(1).longest.get          // [0.595, Infinity): born, but not dead by 1.0
```

The cost is that features still alive at the cut-off have no death inside the window: the loop is reported as essential.

## The whole script

The fragments above, in one piece (this block is compiled with the documentation, and `FindALoopSpec` runs it and checks
every number quoted on this page):

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val diagram = Persistence(points)

val loop = diagram.dim(1).longest.get
val longestGap = diagram.dim(0).bars.map(_.persistence).filter(_.isFinite).max
val longerThanATenth = diagram.longerThan(0.1)
val significant = diagram.significant()

val cocycle = loop.representative
val withCycles = Persistence(points, maxDimension = 1, engine = Persistence.Engine.Chunks)
val cycle = withCycles.dim(1).longest.get.representative

val complexSize = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1).iterator.size
val short = Persistence(points, maxFiltrationValue = 1.0)
val shortLoop = short.dim(1).longest.get
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');   % from the repository root, after sbt assembly
import org.appliedtopology.tda4j.matlab.*;

points = readmatrix('_docs/tutorials/data/noisy-circle.csv');

% Vietoris-Rips in degrees 0 and 1, with the chunks engine, for cycles as representatives
result = TDA4j.computeFromPoints(points, {'maxDimension', '1', 'engine', 'chunks'});

% Short bars (up to 1% of the enclosing radius) are hidden by default
bars = result.toArray();               % 58 rows [dimension birth death]
result.hiddenCount()                   % 3 more, shorter than the threshold

loop = bars(bars(:,1) == 1, :)         % [1 0.595 1.707]
h0 = bars(bars(:,1) == 0 & isfinite(bars(:,3)), :);
longestGap = max(h0(:,3) - h0(:,2))    % 0.484

% The representative cycle. Java counts from 0, so the bar's index is its row number minus 1
k = find(bars(:,1) == 1) - 1;
edges = double(result.cycleVertices(k)) + 1;   % 52 rows, one edge each, as 1-based row numbers of the CSV

% Cutting the complex off early
short = TDA4j.computeFromPoints(points, {'maxDimension', '1', 'engine', 'chunks', 'maxFiltrationValue', '1.0'});
shortBars = short.toArray();
shortBars(shortBars(:,1) == 1, :)      % [1 0.595 Inf]: born, not dead by the cut-off
```

</div>
</div>

The MATLAB tab makes the same calls through the `TDA4j` facade. It shows the bars `significant()` keeps (pass
`'minPersistence', '0'` for all of them), and it counts bars from 0 (Java's convention), so `cycleVertices(k)` takes the
bar's row number minus 1. `MatlabTabsSpec` makes these same calls from Scala and checks every number quoted in the MATLAB
tab; MATLAB itself is not run by the test suite.

## Where to go next

* [Choosing a complex](choosing-a-complex.md): the same data through Čech, alpha, sparse Rips and witness complexes.
* [Noise and outliers](noise-and-outliers.md): what happens when the hole is full of junk points.
* [Scaling up](scaling-up.md): the engines behind `Persistence`, and what to do when the complex gets big.
