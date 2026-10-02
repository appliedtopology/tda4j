---
layout: main
title: Finding a loop in noisy data
---

The most common question in topological data analysis is also the simplest: *does my data have a hole in it?* This tutorial
answers it for a small noisy point cloud, from reading the file to reading the barcode, and introduces the pieces every other
tutorial uses: the Vietoris-Rips complex, a persistence engine, and the question of which bars are signal.

**The data.** [`data/noisy-circle.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/noisy-circle.csv) holds 60 points sampled at random angles from the unit circle, each
coordinate then perturbed by Gaussian noise of size 0.05. (Every data file in these tutorials is produced by a seeded generator
checked into the repository, so you can rebuild or change it.) There is one obvious hole in this data, and no point lies on it.

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = TDAlab(2)          // coefficients in the field with 2 elements
import lab.{*, given}

val metricSpace = io.CSV.readEuclideanMetricSpace("_docs/tutorials/data/noisy-circle.csv")
val enclosingRadius = metricSpace.minimumEnclosingRadius   // 1.95: beyond this radius nothing new can be born

// 1. The complex: at each scale r, join every group of points that are pairwise within r of each other.
val stream = streams.VietorisRips(metricSpace, maxDimension = 1)
stream.iterator.size         // 24711 simplices in all

// 2. The engine: it reads the simplices in order of when they appear and tracks when holes are born and die.
val engine = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]()
val state = engine.persistentHomology(stream)

// 3. The barcode, as (dimension, birth, death) triples. Death is Infinity for a hole that never fills in.
val bars = state.diagramAt(Double.PositiveInfinity).filter((dim, _, _) => dim <= 1)
bars.size                    // 1544
```

`maxDimension = 1` means "I want to see components (dimension 0) and loops (dimension 1)". The stream contains the triangles too,
because a loop is only a loop if no triangle fills it, so asking for loops builds one dimension more than you ask to see. That is
also why the `filter(dim <= 1)`: the engine reports whatever dimension it was given, and the top one is incomplete.

## Reading the barcode

There are 1,544 bars, which is far too many to look at. Almost all of them are noise of a particular, harmless kind:

```scala sc:nocompile
val zeroLength = bars.count((_, birth, death) => death - birth <= 1e-12)   // 1483
```

1,483 bars are born and die at the same instant. A loop that appears and is filled by a triangle in the very same step never
existed at any scale you could observe. The facade (the command line and MATLAB) and `PersistenceFilter` discard bars that
persist for less than 1% of the enclosing radius, which removes exactly these:

```scala sc:nocompile
val threshold = 0.01 * enclosingRadius
val shown = bars.filter((_, birth, death) => death.isInfinite || death - birth > threshold)
shown.size                                      // 58
shown.groupBy(_._1).view.mapValues(_.size).toMap   // Map(0 -> 57, 1 -> 1)
```

That leaves 58 bars: 57 in dimension 0 and one in dimension 1. The dimension-1 bar is our loop. The 57 dimension-0 bars are
the points of the sample merging into one component as the scale grows. They are not "wrong", but they tell you about the spacing
of your sample, not about its shape, and a 1% threshold cannot separate those from real features because it knows nothing about
your sample. Reading a barcode always comes down to **comparing bars with each other**:

```scala sc:nocompile
val loop = bars.filter(_._1 == 1).maxBy((_, birth, death) => death - birth)
// loop == (1, 0.595, 1.707): born at scale 0.59, filled in at scale 1.71

val longestFiniteH0 = bars.filter(b => b._1 == 0 && b._3.isFinite).map(b => b._3 - b._2).max
// 0.484: the biggest gap in the sample, where two groups of points finally join
```

The loop lives for 1.11, more than twice as long as the longest sampling gap, and there is exactly one of it. That is a
circle. The loop is born at 0.59, not at the typical spacing between neighbours, because it needs *every* gap around the circle
to be closed, and with 60 random angles the biggest gap is large. It dies at 1.71, close to √3 = 1.73, the scale at which the
points of a unit circle start to see across it.

The one essential dimension-0 bar, `[0, Infinity)`, says the data is one connected piece.

## Seeing the loop itself

The engine does not only report *that* there is a loop, it records a representative, a cycle of edges that goes around it:

```scala sc:nocompile
val withCycles = state.diagramWithGeneratorsAt(Double.PositiveInfinity).filter((dim, _, _, _) => dim == 1)
val (_, _, _, cycle) = withCycles.maxBy((_, birth, death, _) => death - birth)
cycle.rawEntries.size        // 52 edges, each a pair of row numbers in the CSV
```

Each term of the chain is an edge between two points of your data (by row number, starting at 0), with a coefficient. Having
the cycle means you can mark which points make up the hole, which is often the real answer to "where is it?". On noisy data the
cycle is a jagged path rather than a clean polygon: it is *a* representative of the loop, one of many that are equivalent.

## Cutting the complex off early

Most of the 24,711 simplices appear at large scales that no feature of interest needs. `VietorisRips` defaults to cutting off at
the enclosing radius, past which the complex is just a cone and nothing is born. You can cut earlier when you know what scale you
care about:

```scala sc:nocompile
val shortStream = streams.VietorisRips(metricSpace, maxDimension = 1, maxFiltrationValue = Some(1.0))
shortStream.iterator.size    // 3629 simplices instead of 24711

val shortLoop = engine.persistentHomology(shortStream).diagramAt(Double.PositiveInfinity).filter(_._1 == 1).maxBy((_, birth, death) => death - birth)
// shortLoop == (1, 0.595, Infinity)
```

The cost is that features still alive at the cut-off have no death inside the window. Run the same engine on this short stream
and the loop is reported as `[0.595, Infinity)`: it was born, and had not died by the time you stopped looking.

## The whole script

The fragments above, in one piece (this block is compiled with the documentation, and `FindALoopSpec` runs it and checks every
number quoted on this page):

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = TDAlab(2)
import lab.{*, given}

val metricSpace = io.CSV.readEuclideanMetricSpace("_docs/tutorials/data/noisy-circle.csv")
val enclosingRadius = metricSpace.minimumEnclosingRadius

val stream = streams.VietorisRips(metricSpace, maxDimension = 1)
val engine = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]()
val state = engine.persistentHomology(stream)

val bars = state.diagramAt(Double.PositiveInfinity).filter((dim, _, _) => dim <= 1)
val zeroLength = bars.count((_, birth, death) => death - birth <= 1e-12)
val threshold = 0.01 * enclosingRadius
val shown = bars.filter((_, birth, death) => death.isInfinite || death - birth > threshold)

val loop = bars.filter(_._1 == 1).maxBy((_, birth, death) => death - birth)
val longestFiniteH0 = bars.filter(b => b._1 == 0 && b._3.isFinite).map(b => b._3 - b._2).max

val withCycles = state.diagramWithGeneratorsAt(Double.PositiveInfinity).filter((dim, _, _, _) => dim == 1)
val (_, _, _, cycle) = withCycles.maxBy((_, birth, death, _) => death - birth)

val shortStream = streams.VietorisRips(metricSpace, maxDimension = 1, maxFiltrationValue = Some(1.0))
val shortLoop = engine.persistentHomology(shortStream).diagramAt(Double.PositiveInfinity).filter(_._1 == 1).maxBy((_, birth, death) => death - birth)
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');   % from the repository root, after sbt assembly
import org.appliedtopology.tda4j.matlab.*;

points = readmatrix('_docs/tutorials/data/noisy-circle.csv');

% Vietoris-Rips with the naive engine, the one the Scala script uses; dimensions 0 and 1
result = TDA4j.computeFromPoints(points, {'maxDimension', '1', 'engine', 'naive'});

% The facade hides bars shorter than 1% of the enclosing radius, so toArray() has 58 rows [dimension birth death]
bars = result.toArray();
result.hiddenCount()                  % 1486 bars hidden
everything = result.toArrayUnfiltered();
size(everything, 1)                   % 1544 bars in all
sum(everything(:,3) - everything(:,2) <= 1e-12)   % 1483 of them have zero length

loop = bars(bars(:,1) == 1, :)        % [1 0.595 1.707]
h0 = bars(bars(:,1) == 0 & isfinite(bars(:,3)), :);
longestFiniteH0 = max(h0(:,3) - h0(:,2))          % 0.484

% The representative cycle. Java counts from 0, so the bar's index is its row number minus 1
k = find(bars(:,1) == 1) - 1;
edges = double(result.cycleVertices(k)) + 1;       % 52 rows, one edge each, as 1-based row numbers of the CSV
coefficients = result.cycleCoefficients(k);

% Cutting the complex off early
shortResult = TDA4j.computeFromPoints(points, {'maxDimension', '1', 'engine', 'naive', 'maxFiltrationValue', '1.0'});
shortResult.numCells()                % 3629 simplices instead of 24711
shortBars = shortResult.toArray();
shortBars(shortBars(:,1) == 1, :)     % [1 0.595 Inf]: born, not dead by the cut-off
```

</div>
</div>

The MATLAB tab makes the same calls through the `TDA4j` facade. Two things differ from the Scala script: the facade hides short bars unless you ask for `toArrayUnfiltered()`, and it counts bars and cycles from 0 (Java's convention), so `cycleVertices(k)` takes the bar's row number minus 1. `MatlabTabsSpec` makes these same calls from Scala and checks every number quoted in the MATLAB tab; MATLAB itself is not run by the test suite.

## Where to go next

* [Choosing a complex](choosing-a-complex.md): the same data through Čech, alpha, sparse Rips and witness complexes.
* [Noise and outliers](noise-and-outliers.md): what happens when the hole is full of junk points.
* The user guide's [quickstart](../user-guide/quickstart.md) and [persistence engines](../user-guide/homology-computation/choosing-engine.md)
  cover faster engines, which matter once you have thousands of points.
