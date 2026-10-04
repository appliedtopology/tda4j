---
layout: main
title: Loading and saving data
---

These objects read and write the file formats of the wider TDA ecosystem:

| object | formats |
|---|---|
| `CSV` | point clouds, full and lower-triangular distance matrices, persistence diagrams |
| `Ripser` | Ripser's point cloud, lower, upper, full and binary distance-matrix formats |
| `Dipha` | DIPHA's distance-matrix, image and persistence-diagram formats |
| `Gudhi` | GUDHI's OFF point clouds and persistence diagrams |
| `Perseus` | Perseus's cubical toplex and persistence-interval formats |

Each has raw readers (`readPointCloud`, `readFullDistanceMatrix`, ...) and readers that return something to compute
with (`readEuclideanMetricSpace`, `readExplicitMetricSpace`, `readCubicalGridStream`, ...):

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = CSV.readPointCloud("points.csv")
val diagram = Persistence(points)
val distances = Ripser.readLowerDistanceExplicitMetricSpace("distances.txt")
val fromDistances = Persistence(distances)
val image = Persistence(Perseus.readCubicalToplex("image.txt"))
CSV.writePersistenceDiagram("diagram.csv", diagram.bars)
```

Two details if you compare with another tool: Ripser's binary distance matrices are 32-bit floats, and DIPHA's and
Perseus's grids list the first axis fastest (the readers and writers convert for you). A Perseus value of `-1` is a
missing cell, read as `Infinity`.
