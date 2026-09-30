---
layout: main
title: Boundary-matrix export
---


`PersistenceResult` also exports the boundary matrix of the full complex it was computed from, for anything
that wants to do its own linear algebra over it (an optimal-cycle solver, harmonic smoothing for circular
coordinates, ...) rather than TDA4j's own reduction:

```java
PersistenceResult result = TDA4j.computeFromPoints(points, new String[]{"maxDimension", "1"});
int n = result.numCells();               // NOT result.size() -- cells, not bars
int[] rows = result.boundaryRows();      // 0-based
int[] cols = result.boundaryCols();
double[] values = result.boundaryValues();
int dim7 = result.columnDimension(7);
int[] verts7 = result.columnVertices(7); // same per-complex-type shape cycleVertices documents
double fv7 = result.columnFiltrationValue(7);
```

```matlab
n = result.numCells();
M = sparse(double(result.boundaryRows())+1, double(result.boundaryCols())+1, result.boundaryValues(), n, n);
```

Computed lazily (nothing is built until the first `numCells`/`boundaryRows`/... call) and cached after that,
and is the SAME matrix regardless of which `engine` actually computed this result's own bars — the boundary
matrix is a property of the complex, not of which reduction algorithm ran over it. Column `j`'s own dimension/
vertices/filtration value describe cell `j`, not bar `j` — there are generally far more cells than bars, and
this is a different indexing than `dimension(i)`/`cycleVertices(i)`/etc. above. Not mirrored on the CLI (a
sparse matrix doesn't fit its diagram-in-diagram-out shape) — see the [Developer's
Guide](../developers-guide/architecture.md)'s "`Barcode.scala`" section for the full construction.