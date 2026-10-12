---
layout: main
title: Boundary-matrix export
---

The MATLAB and Java facade exports the boundary matrix of the complex a result was computed from, for your own linear
algebra (an optimal-cycle solver, harmonic smoothing, ...):

```java
PersistenceResult result = TDA4j.computeFromPoints(points, new String[]{"maxDimension", "1"});
int n = result.numCells();               // cells, not bars
int[] rows = result.boundaryRows();      // 0-based
int[] cols = result.boundaryCols();
double[] values = result.boundaryValues();
int dim7 = result.columnDimension(7);
int[] vertices7 = result.columnVertices(7);
double value7 = result.columnFiltrationValue(7);
```

```matlab
n = result.numCells();
M = sparse(double(result.boundaryRows())+1, double(result.boundaryCols())+1, result.boundaryValues(), n, n);
```

Columns are the cells of the complex in filtration order, so a face's row comes before its coface's column. The matrix is
the same whichever engine computed the bars, and is built the first time you ask for it. Column `j` describes cell
`j`, which is unrelated to bar `j` of `toArray()`. Not available from the command line.

From Scala, the cells of any stream are `stream.iterator` in filtration order, with `stream.filtrationValue(cell)` and
`cell.boundary[C]`.
