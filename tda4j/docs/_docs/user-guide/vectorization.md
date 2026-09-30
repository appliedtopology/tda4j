---
layout: main
title: Comparing diagrams and turning them into vectors
---


`PersistenceResult` also answers "how different are these two barcodes" (bottleneck/Wasserstein distance) and
"turn this barcode into a fixed-size array" (persistence landscapes/images, for feeding into ordinary ML
tooling) — both compare/summarize an *already-computed* result, so they're instance methods, not part of the
`computeFrom*` option table above:

```java
double[][] points1 = { {0.0, 0.0}, {1.0, 0.0}, {0.5, 0.8} };
double[][] points2 = { {0.0, 0.0}, {1.05, 0.0}, {0.5, 0.85} }; // a small perturbation of points1
PersistenceResult r1 = TDA4j.computeFromPoints(points1, new String[]{"maxDimension", "1"});
PersistenceResult r2 = TDA4j.computeFromPoints(points2, new String[]{"maxDimension", "1"});

double d0 = r1.bottleneckDistance(r2, 0);       // dimension 0, L-infinity ground norm (the usual TDA default)
double w1 = r1.wassersteinDistance(r2, 1, 2.0); // dimension 1, order 2

// 5 landscape levels, sampled at 100 points across [0.0, 2.0]
double[][] landscape = r1.landscape(1, 5, 0.0, 2.0, 100);

// a 20x20 persistence image, sigma=0.1, weight cap defaulted to the diagram's own max persistence
double[][] image = r1.persistenceImage(1, 0.1, 0.0, 2.0, 0.0, 2.0, 20, 20);
```

`bottleneckDistance`/`wassersteinDistance` return `Double.POSITIVE_INFINITY` when the two diagrams have
different numbers of essential (never-dying) bars in that dimension — a real answer ("no finite matching
exists"), not a failure. `--distance-to <file>` mirrors the distance methods on the `tda4j` command line
(comparing the diagram just computed against one already saved as `csv`/`gudhi`/`dipha`; `--distance-format`
selects which), printing one `dim <k>: bottleneck=... wasserstein=...` line per dimension instead of writing
a diagram; the two vectorizations are MATLAB/Java-only for now (they produce a matrix, not a diagram, which
doesn't fit the CLI's diagram-in-diagram-out shape). From plain Scala, use
`org.appliedtopology.tda4j.barcode.BarcodeDistance`/`Vectorization` directly on `List[PersistenceBar[Double,
_]]` — see the [Developer's Guide](../developers-guide/architecture.md)'s "`Barcode.scala`" section for the
ground-metric convention, the essential-bar policy (the two vectorizations handle it differently, on
purpose), and the literature this follows (Kerber-Morozov-Nigmetov 2017 for the distances; Bubenik 2013 for
landscapes; Adams et al. 2017 for persistence images).
