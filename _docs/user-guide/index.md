---
layout: main
title: User Guide for TDA4j
---

TDA4j computes persistent homology and related invariants: barcodes with representatives (cocycles or cycles), from point clouds,
distance matrices, images, relations and spaces you build yourself, over any prime field or the reals. This guide
assumes you know what a filtered complex and a persistence barcode are; it does not assume you know Scala. For how
the library is built, see the [Developer's Guide](../developers-guide/index.md); for worked examples on real-looking
data, the [tutorials](../tutorials/index.md).

Every Scala file that uses TDA4j starts with two lines:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
```

The first accepts the experimental Scala features the library is built with (its typeclasses use them, so Scala asks
every file that uses it to opt in); the second brings in the whole library. Simplicial sets and group classifying
spaces are an add-on with one more import, `import org.appliedtopology.tda4j.sset.*`.

## Contents

* [Quickstart](quickstart.md): `Persistence(...)` and the diagram it returns, short bars, representatives, long
  computations, chains.
* **Complexes** ([overview](topological-spaces/index.md)): [Vietoris-Rips](topological-spaces/vietoris-rips.md)
  (with DTM weighting and edge collapse), [Čech](topological-spaces/cech-complexes.md),
  [alpha](topological-spaces/alpha-complexes.md), [sparse Rips](topological-spaces/sparse-vietoris-rips.md),
  [witness](topological-spaces/witness-complexes.md), [Dowker](topological-spaces/dowker-complexes.md),
  [cubical](topological-spaces/cubical-complexes.md), and [simplicial sets](topological-spaces/simplicial-sets.md).
* **Engines** ([overview](homology-computation/index.md)): [which one to use](homology-computation/choosing-engine.md),
  the [fast cubical](homology-computation/fast-cubical.md) and [fast alpha](homology-computation/fast-alpha-complexes.md)
  engines.
* **Using the results**: [distances and vectorizations](vectorization.md),
  [circular and toroidal coordinates](circular-coordinates.md), the [boundary matrix](boundary-matrix.md).
* [Files](input-output.md): CSV, Ripser, DIPHA, GUDHI and Perseus formats.
* **Without Scala**: [MATLAB and Java](matlab.md), the [command line](cli.md).
