---
layout: main
title: Pairs of spaces
---

The homology of a space `X` relative to a subspace `A` counts the classes of `X` that `A` does not account for: cycles
of `X` are taken modulo the chains of `A`. A circle relative to three of its points has three loops, one for each arc
between them; a disc relative to its boundary circle has a class in degree 2, the disc itself. `Relative` makes the
persistent version: it takes a stream of cells and the subcomplex `A`, and gives the stream of the pair, which every
engine and `Persistence` take like any other.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(12)(i => Array(math.cos(2 * math.Pi * i / 12), math.sin(2 * math.Pi * i / 12)))
val rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 2, maxFiltrationValue = 2.0)

Persistence(rips).dim(1).bars.size                                          // 1: the circle
Persistence(Relative.ofVertices(rips, Set(0, 4, 8))).dim(1).bars.size        // 3: the arcs between three points
Persistence(Relative.ofVertices(rips, Set(0, 1, 2, 3, 4, 5, 6))).dim(0).size // 5: the points off the arc, each joined to it
```

The pair at the value `t` is `(X_t, A_t)` with `A_t` the cells of `A` that have entered by `t`. A bar of the pair is
born when a cycle of `X` is closed up to chains of `A`, and dies when it is filled in `X` or in `A`; the bars carry
chains of `RelativeCell`s, the cell of `X` being `cell`.

| call | the subcomplex `A` |
|---|---|
| `Relative.ofVertices(stream, vertices)` | the simplices all of whose vertices are in the set |
| `Relative.ofBoundary(grid)` | the cubes on the boundary of an image or volume (`grid.onBoundary`) |
| `Relative(stream, inSubcomplex)` | the cells for which `inSubcomplex(cell)` holds, for cells of any kind |

`A` has to be closed under faces: a face of a cell in `A` is in `A`. This is not checked. The cells of `A` keep the
filtration values they have in `X`, so `A_t` is `A` intersected with `X_t`.

## An image relative to its boundary

A curve that runs from one edge of a picture to another is not a cycle of the picture, but it is a cycle of the picture
modulo its edge. `Relative.ofBoundary` takes the grid of an image and divides out the cubes on its boundary:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val pixels = Array.tabulate(7, 7)((row, column) => if row == 3 then 0.0 else 1.0)   // a line from edge to edge, value 0
val grid = CubicalImage.fromGrayscale2D(pixels)

Persistence(Image(pixels), maxDimension = 1).dim(1).size                // 0: the line is no loop
Persistence(Relative.ofBoundary(grid), maxDimension = 2).dim(1).size    // 1: the line is a cycle of the pair
```

The pair of a picture and its boundary has its top class, the picture itself, from the value where the last pixel enters. For
a large image there is a faster way to the same bars of degrees 0 and 1, through the fast cubical engine; the
[Truchet tutorial](../tutorials/truchet-tiles.md) shows it on a random tiling of arcs, whose long bars are exactly its curves.

A single vertex gives the reduced homology: for a connected complex whose vertices all enter at the start, the one
essential bar of degree 0 is gone and everything else stays. If you want what a sample adds to a subsample, with the
map between the two, see [maps between spaces](barcode-algebra.md#maps-between-spaces).

## From the other interfaces

The Vietoris-Rips pair is also an entry point of the interfaces that do not run Scala: you give the points (or the
distances) and the numbers of the points that span the subcomplex.

| interface | call |
|---|---|
| [MATLAB and Java](matlab.md) | `TDA4j.computeRelativeFromPoints(points, subcomplex, options)`; `computeRelativeFromDistanceMatrix` |
| [JavaScript](javascript.md) | `relativePersistence(points, subcomplex, options)`; `relativePersistenceFromDistances` |
| [Command line](cli.md#relative-persistence) | `tda4j --relative-to 0,3,5 points.csv` |

Each takes the options of the Vietoris-Rips computation, and a saved result remembers what it was relative to.
