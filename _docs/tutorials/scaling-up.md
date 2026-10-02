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
val metricSpace = io.CSV.readEuclideanMetricSpace("_docs/tutorials/data/noisy-circle.csv")
def stream = streams.VietorisRips(metricSpace, maxDimension = 1)

val naive = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]().persistentHomology(stream).diagramAt(Double.PositiveInfinity)
val chunks = homology.CellularPersistenceInChunksEngine[org.appliedtopology.tda4j.cells.Simplex[Int], CoefficientT](1).persistentHomology(stream).diagramAt(Double.PositiveInfinity)
val cohomology = homology.CellularCohomologyEngine[org.appliedtopology.tda4j.cells.Simplex[Int], CoefficientT, Double]().persistentCohomology(stream).map(_.toTriple)
val ripser = homology.PackedRipserCohomologyEngine[CoefficientT](metricSpace, 1).persistentCohomology().map(_.toTriple)
```

After dropping zero-length bars and anything above dimension 1, and rounding to 1e-6, all four agree: 61 bars each.
The choice is therefore about cost and about what you need beyond the barcode (the naive and chunks engines are
incremental; only the Ripser engines are specialised to Vietoris-Rips on a metric space).

## Edge collapse

Many edges of the Vietoris-Rips graph are dominated by another vertex and cannot change the persistent homology.
`EdgeCollapse.collapse` removes them before any triangle is built.

```scala sc:nocompile
val collapsed = streams.EdgeCollapse.collapse(metricSpace)
```

On this data it keeps 321 of the 1,543 edges, and the complex drops from 24,711 to 1,547 simplices. Running the
naive engine on the collapsed space gives the same 61 bars.

## The whole script

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val tdalab = TDAlab(2)
import tdalab.{*, given}

val metricSpace = io.CSV.readEuclideanMetricSpace("_docs/tutorials/data/noisy-circle.csv")
def stream = streams.VietorisRips(metricSpace, maxDimension = 1)

// Four engines on the same data. Each returns bars in its own way, so reduce them to comparable (dimension, birth, death) triples
val naive = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]().persistentHomology(stream).diagramAt(Double.PositiveInfinity)
val chunks = homology.CellularPersistenceInChunksEngine[org.appliedtopology.tda4j.cells.Simplex[Int], CoefficientT](1).persistentHomology(stream).diagramAt(Double.PositiveInfinity)
val cohomology = homology.CellularCohomologyEngine[org.appliedtopology.tda4j.cells.Simplex[Int], CoefficientT, Double]().persistentCohomology(stream).map(_.toTriple)
val ripser = homology.PackedRipserCohomologyEngine[CoefficientT](metricSpace, 1).persistentCohomology().map(_.toTriple)

// Drop what is not part of the answer (zero-length bars, and any dimension above the one asked for), and round the rest
def answer(bars: Seq[(Int, Double, Double)]) =
  bars
    .filter((dim, birth, death) => dim <= 1 && (death.isInfinite || death - birth > 1e-9))
    .map((dim, birth, death) => (dim, math.round(birth * 1e6), if death.isInfinite then Long.MaxValue else math.round(death * 1e6)))
    .sorted

// Edge collapse: remove the edges of the Vietoris-Rips graph that cannot matter, before any triangle is built
val collapsed = streams.EdgeCollapse.collapse(metricSpace)
val collapsedBars = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]()
  .persistentHomology(streams.VietorisRips(collapsed, maxDimension = 1)).diagramAt(Double.PositiveInfinity)

println((answer(naive).size, collapsed.stats))
```
