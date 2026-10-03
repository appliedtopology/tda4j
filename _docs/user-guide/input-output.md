---
layout: main
title: Loading and saving data
---


The objects below (in `org.appliedtopology.tda4j`, so `import org.appliedtopology.tda4j.*` brings them in) read and
write the file formats the wider TDA ecosystem uses, so you don't have to hand-roll parsing:

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
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val metricSpace = Ripser.readEuclideanMetricSpace("points.txt")
val stream = Perseus.readCubicalToplex("image.txt")
```

Two format details worth knowing if you're comparing output against another tool: Ripser's binary
distance-matrix format is 32-bit `float`, not 64-bit `double`. DIPHA's and Perseus's cubical-grid axis order
is the opposite of `CubicalImage`'s own convention (first axis fastest-varying vs. last); both readers/
writers here handle the transposition for you.
