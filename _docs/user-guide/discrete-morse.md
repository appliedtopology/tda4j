---
layout: main
title: Discrete Morse theory
---

A filtered complex often has many cells that cancel each other in pairs: a vertex and the edge that attaches it to the rest,
an edge and the triangle that fills it. Discrete Morse theory pairs such cells off and keeps the cells that are left over, the
critical cells. The critical cells with the Morse boundary between them make a smaller complex with the same persistent
homology, so the same barcode is computed on fewer cells. A representative found on the small complex lifts back to a
cycle of the large one.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val grid = for x <- 0 until 4; y <- 0 until 4 yield Array(x.toDouble, y.toDouble)
val rips = VietorisRips(EuclideanMetricSpace(grid.toArray), maxDimension = 2)

val morse = DiscreteMorse(rips)
morse.critical.size                 // far fewer than the cells of `rips`
morse.criticalCount(1)              // the critical edges
morse.pairs                         // face -> coface, for each pair

val diagram = Persistence(morse.stream, maxDimension = 2)       // the bars of Persistence(rips, maxDimension = 2)
```

`DiscreteMorse(stream, strategy)` takes any stream of cells and returns a `MorseComplex`. Its `critical` cells are in the
order of the stream, `pairs` maps the lower cell of each pair to the upper one, `isCritical(cell)` and `partner(cell)` answer
for one cell, and `stream` is the Morse complex as a stream of `MorseCell`s, which `Persistence` takes like any other. The
bars it computes are the bars of the original complex, except for the bars of length zero: they come from cells that enter
together, which are the cells that were paired off.

## What is paired

A pair is a cell and a facet of it that enter at the same filtration value, so the pairs are made one filtration value at a
time. Within one value, `Strategy.Reduction` (the default) pairs a cell that has exactly one coface among the cells entering
then with that coface, and repeats; `Strategy.Coreduction` pairs a cell that has exactly one facet among them with that
facet. When neither applies, the cell of highest (respectively lowest) dimension is left critical and the pairing goes on.
The two strategies find different matchings, and both are acyclic: no chain of pairs leads back to where it started, which is
what makes the Morse complex a complex with the right homology. Where every cell enters at its own value nothing is paired,
and the Morse complex is the complex. A Vietoris-Rips complex always has pairs, since a simplex enters with the longest
edge it contains, and the more distances repeat, in a lattice or in integer-valued data, the more cells enter together and
the fewer are left.

## Representatives

`morse.lift(chain)` turns a chain of `MorseCell`s, such as the representative of a bar of `Persistence(morse.stream, ...)`
computed with cycles, into a chain of the original complex: the cycle with the same class, supported on cells that entered
no later than the bar was born. Cocycles are not lifted yet.

```scala 3
val cycles = Persistence(morse.stream, maxDimension = 1, representatives = Representatives.Cycles)
import cycles.given
val loops = cycles.bars.filter(_.dim == 1).map(bar => morse.lift(bar.representative))
```

## For your own experiments

The matching is the part to replace. `MorseComplex` is built from a stream and a set of pairs, and the Morse boundary
(`morse.boundary(cell)`, the flow of a cell's boundary down the pairs) and `lift` use only that the pairs are acyclic
and each has an incidence that is a unit in the coefficient field, which is `+1` or `-1` for simplices and cubes. The
library does not check the matching you give it; the Morse boundary of a matching with a cycle may not square to zero.

## From the other interfaces

| interface | call |
|---|---|
| [MATLAB and Java](matlab.md) | `TDA4j.morseReductionFromPoints(points, options)` and `morseReductionFromDistanceMatrix`: a `MorseReductionResult` with `cellCounts()`, `criticalCounts()`, `pairCount()`, `criticalCells()` and `bars()`; options `maxDimension`, `maxFiltrationValue`, `strategy` (`reduction` or `coreduction`), `prime`, `threads` |
| [JavaScript](javascript.md) | `morseReduction(points, options)` and `morseReductionFromDistances`: an object with the same members |
| [Python](python.md) | `tda4j.morse_reduction(points, **options)` and `morse_reduction_from_distances`: `cell_counts`, `critical_counts`, `pair_count`, `critical_cells` and `bars` |
| [Command line](cli.md#discrete-morse-reduction) | `tda4j --morse-reduction points.csv` |
