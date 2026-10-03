---
layout: main
title: Loading and saving data: the `io` module
---


`org.appliedtopology.tda4j.io` reads and writes the file formats the wider TDA ecosystem uses, so you don't
have to hand-roll parsing:

| Object | Formats |
|---|---|
| `CSV` | plain CSV point clouds, full/lower-triangular distance matrices, persistence diagrams |
| `Ripser` | Ripser's point-cloud/lower/upper/full/binary distance-matrix formats |
| `Dipha` | DIPHA's distance-matrix, cubical-image, and persistence-diagram formats |
| `Gudhi` | GUDHI's OFF point-cloud format and persistence-diagram format |
| `Perseus` | Perseus's cubical toplex and persistence-interval formats |

Each object offers a raw loader (`readPointCloud`/`readFullDistanceMatrix`/...) and a one-line convenience
constructor on top (`readEuclideanMetricSpace`/`readExplicitMetricSpace`/`readCubicalGridStream`/...):

```scala 3
import org.appliedtopology.tda4j.{given, *}

val metricSpace = Ripser.readEuclideanMetricSpace("points.txt")
val stream = Perseus.readCubicalToplex("image.txt")
```

Two format details worth knowing if you're comparing output against another tool: Ripser's binary
distance-matrix format is 32-bit `float`, not 64-bit `double`. DIPHA's and Perseus's cubical-grid axis order
is the opposite of `CubicalImage`'s own convention (first axis fastest-varying vs. last); both readers/
writers here handle the transposition for you.
