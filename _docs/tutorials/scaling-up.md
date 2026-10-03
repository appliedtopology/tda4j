---
layout: main
title: Scaling up
---

# Scaling up: engines and edge collapse

A Vietoris-Rips complex grows fast. The 60-point noisy circle from [Find a loop](find-a-loop.md) has 24,711
simplices up to dimension 2, and 61 bars come out of it. This page shows two ways to spend less on the same answer:
pick a different engine, and shrink the graph before building the complex. Both are checked below to return exactly
the same diagram. For what each engine supports, see the [engine guide](../user-guide/homology-computation/choosing-engine.md).

The data is [`noisy-circle.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/noisy-circle.csv).

## Four engines, one answer

`Persistence` takes the engine as an option:

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")

val chunks = Persistence(points)                                            // the default
val naive = Persistence(points, engine = Persistence.Engine.Naive)
val cohomology = Persistence(points, engine = Persistence.Engine.Cohomology)
val ripser = Persistence(points, engine = Persistence.Engine.Ripser)
List(chunks, naive, cohomology, ripser).map(_.size)                         // List(61, 61, 61, 61)
```

Rounded to 1e-6, all four diagrams are equal. The choice is about cost and about the representatives: the chunks
(default) and naive engines give cycles, the cohomology and Ripser engines give cocycles. Ripser works only for the
Vietoris-Rips complex of points or a metric space, and is the fastest there; the others take any complex, and the
naive engine can also be run step by step (see the [quickstart](../user-guide/quickstart.md)).

## Edge collapse

Many edges of the Vietoris-Rips graph are dominated by another vertex and cannot change the persistent homology.
`EdgeCollapse.collapse` removes them before any triangle is built, and returns a metric space that `Persistence` takes
like any other:

```scala sc:nocompile
val metricSpace = EuclideanMetricSpace(points)
val collapsed = EdgeCollapse.collapse(metricSpace)
val collapsedDiagram = Persistence(collapsed)     // the same 61 bars
```

On this data it keeps 321 of the 1,543 edges, and the complex drops from 24,711 to 1,547 simplices.

## The whole script

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")

val chunks = Persistence(points)
val naive = Persistence(points, engine = Persistence.Engine.Naive)
val cohomology = Persistence(points, engine = Persistence.Engine.Cohomology)
val ripser = Persistence(points, engine = Persistence.Engine.Ripser)

// Bars rounded to 1e-6, so diagrams from different engines can be compared
def rounded(diagram: PersistenceDiagram[Simplex[Int]]) =
  diagram.triples
    .map((dim, birth, death) => (dim, math.round(birth * 1e6), if death.isInfinite then Long.MaxValue else math.round(death * 1e6)))
    .sorted

val metricSpace = EuclideanMetricSpace(points)
val collapsed = EdgeCollapse.collapse(metricSpace)
val collapsedDiagram = Persistence(collapsed)

val complexSize = VietorisRips(metricSpace, maxDimension = 1).iterator.size
val collapsedSize = VietorisRips(collapsed, maxDimension = 1).iterator.size
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');   % from the repository root, after sbt assembly
import org.appliedtopology.tda4j.matlab.*;

points = readmatrix('_docs/tutorials/data/noisy-circle.csv');

% Four engines; minPersistence 0 reports every bar, like the Scala script
engines = {'naive', 'chunks', 'cohomology', 'ripser'};
answers = cell(1, 4);
for e = 1:4
    result = TDA4j.computeFromPoints(points, {'maxDimension', '1', 'minPersistence', '0', 'engine', engines{e}});
    answers{e} = sortrows(round(result.toArray() * 1e6));   % rounded to 1e-6 and sorted, so they can be compared
    size(answers{e}, 1)                                      % 61 bars from each
end
isequal(answers{:})                                          % true: the engines agree

% Edge collapse: remove the edges of the Vietoris-Rips graph that cannot matter, before any triangle is built
collapsed = TDA4j.computeFromPoints(points, {'maxDimension', '1', 'minPersistence', '0', 'edgeCollapse', 'true'});
collapsed.numCells()                                         % 1547 simplices instead of 24711
isequal(sortrows(round(collapsed.toArray() * 1e6)), answers{1})   % true: the same barcode
```

</div>
</div>

The `engine` option takes `naive`, `chunks`, `cohomology` and `ripser`, and `edgeCollapse` applies to the Vietoris-Rips complex only. `MatlabTabsSpec` makes these same calls from Scala and checks every number quoted in the MATLAB tab; MATLAB itself is not run by the test suite.
