---
layout: main
title: Scaling up
---

# Scaling up: engines and edge collapse

A Vietoris-Rips complex grows fast. The 60-point noisy circle from [Find a loop](find-a-loop.md) has 24,711
simplices up to dimension 2, and only 1,544 bars come out of it. This page shows two ways to spend less on the
same answer: pick a different engine, and shrink the graph before building the complex. Both are checked below
to return exactly the same barcode. For the full table of what each engine supports, see the
[engine guide](../developers-guide/persistence-engines.md).

The data is [`noisy-circle.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/noisy-circle.csv).

## Four engines, one answer

The naive, chunks and cohomology engines take a stream; packed Ripser takes the metric space directly. They differ
in how much raw output they return: the naive and cohomology engines list every bar, including the zero-length ones
(23,168 here), while chunks and packed Ripser return 1,544.

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = TDAlab(2)
import lab.{*, given}

val metricSpace = CSV.readEuclideanMetricSpace("_docs/tutorials/data/noisy-circle.csv")
def stream = VietorisRips(metricSpace, maxDimension = 1)

val naive = SimplicialHomologyEngine[Int, CoefficientT, Double]().persistentHomology(stream).diagramAt(Double.PositiveInfinity)
val chunks = CellularPersistenceInChunksEngine[org.appliedtopology.tda4j.Simplex[Int], CoefficientT](1).persistentHomology(stream).diagramAt(Double.PositiveInfinity)
val cohomology = CellularCohomologyEngine[org.appliedtopology.tda4j.Simplex[Int], CoefficientT, Double]().persistentCohomology(stream).map(_.toTriple)
val ripser = PackedRipserCohomologyEngine[CoefficientT](metricSpace, 1).persistentCohomology().map(_.toTriple)
```

After dropping zero-length bars and anything above dimension 1, and rounding to 1e-6, all four agree: 61 bars each.
The choice is therefore about cost and about what you need beyond the barcode (the naive and chunks engines are
incremental; only the Ripser engines are specialised to Vietoris-Rips on a metric space).

## Edge collapse

Many edges of the Vietoris-Rips graph are dominated by another vertex and cannot change the persistent homology.
`EdgeCollapse.collapse` removes them before any triangle is built.

```scala sc:nocompile
val collapsed = EdgeCollapse.collapse(metricSpace)
```

On this data it keeps 321 of the 1,543 edges, and the complex drops from 24,711 to 1,547 simplices. Running the
naive engine on the collapsed space gives the same 61 bars.

## The whole script

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = TDAlab(2)
import lab.{*, given}

val metricSpace = CSV.readEuclideanMetricSpace("_docs/tutorials/data/noisy-circle.csv")
def stream = VietorisRips(metricSpace, maxDimension = 1)

// Four engines on the same data. Each returns bars in its own way, so reduce them to comparable (dimension, birth, death) triples
val naive = SimplicialHomologyEngine[Int, CoefficientT, Double]().persistentHomology(stream).diagramAt(Double.PositiveInfinity)
val chunks = CellularPersistenceInChunksEngine[org.appliedtopology.tda4j.Simplex[Int], CoefficientT](1).persistentHomology(stream).diagramAt(Double.PositiveInfinity)
val cohomology = CellularCohomologyEngine[org.appliedtopology.tda4j.Simplex[Int], CoefficientT, Double]().persistentCohomology(stream).map(_.toTriple)
val ripser = PackedRipserCohomologyEngine[CoefficientT](metricSpace, 1).persistentCohomology().map(_.toTriple)

// Drop what is not part of the answer (zero-length bars, and any dimension above the one asked for), and round the rest
def answer(bars: Seq[(Int, Double, Double)]) =
  bars
    .filter((dim, birth, death) => dim <= 1 && (death.isInfinite || death - birth > 1e-9))
    .map((dim, birth, death) => (dim, math.round(birth * 1e6), if death.isInfinite then Long.MaxValue else math.round(death * 1e6)))
    .sorted

// Edge collapse: remove the edges of the Vietoris-Rips graph that cannot matter, before any triangle is built
val collapsed = EdgeCollapse.collapse(metricSpace)
val collapsedStream = VietorisRips(collapsed, maxDimension = 1)
val collapsedBars = SimplicialHomologyEngine[Int, CoefficientT, Double]()
  .persistentHomology(collapsedStream).diagramAt(Double.PositiveInfinity)
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');   % from the repository root, after sbt assembly
import org.appliedtopology.tda4j.matlab.*;

points = readmatrix('_docs/tutorials/data/noisy-circle.csv');

% Four engines. minPersistence 1e-9 hides only the bars of zero length, which is what the Scala script drops
engines = {'naive', 'chunks', 'cohomology', 'ripser'};
answers = cell(1, 4);
for e = 1:4
    result = TDA4j.computeFromPoints(points, {'maxDimension', '1', 'minPersistence', '1e-9', 'engine', engines{e}});
    answers{e} = sortrows(round(result.toArray() * 1e6));   % rounded to 1e-6 and sorted, so they can be compared
    size(answers{e}, 1)                                      % 61 bars from each
end
isequal(answers{:})                                          % true: the engines agree

% Edge collapse: remove the edges of the Vietoris-Rips graph that cannot matter, before any triangle is built
collapsed = TDA4j.computeFromPoints(points, {'maxDimension', '1', 'minPersistence', '1e-9', 'edgeCollapse', 'true'});
collapsed.numCells()                                         % 1547 simplices instead of 24711
isequal(sortrows(round(collapsed.toArray() * 1e6)), answers{1})   % true: the same barcode
```

</div>
</div>

The `engine` option takes `naive`, `chunks`, `cohomology` and `ripser`, and `edgeCollapse` applies to the Vietoris-Rips complex only. `MatlabTabsSpec` makes these same calls from Scala and checks every number quoted in the MATLAB tab; MATLAB itself is not run by the test suite.
