---
layout: main
title: Tutorials for TDA4j
---

# Tutorials for TDA4j

These tutorials each take one question, one or two small data sets, and work through to an answer, showing the code and what it
prints. They assume no topology beyond the idea of a hole; the [user guide](../user-guide/index.md) is the place for complete lists
of options. Every number quoted on these pages is checked by a test that runs the same code, so the pages and the library cannot
quietly drift apart.

**Start here**

* [All the ways to call TDA4j](all-ways-to-call.md): the same three tasks (a hand-built octahedron, a barcode from a file, circular
  coordinates) from the command line, MATLAB, the Scala REPL, a Scala object, and plain Scala with explicit imports.
* [Finding a loop in noisy data](find-a-loop.md): a first barcode, how to read it, and which bars to ignore.

**Point clouds**

* [Choosing a complex](choosing-a-complex.md): Vietoris-Rips, Čech, alpha, sparse Rips and witness complexes on the same data.
* [Scaling up](scaling-up.md): four engines and edge collapse giving the same barcode at different cost.
* [Noise and outliers](noise-and-outliers.md): when stray points plant false holes, and how distance-to-measure weighting copes.
* [Circular and toroidal coordinates](circular-and-toroidal-coordinates.md): not just *that* there is a loop, but where each point is on it.
* [Comparing barcodes](comparing-barcodes.md): distances between barcodes, and barcodes as vectors for machine learning.

**Other kinds of data**

* [Topology of an image](images.md): sublevel and superlevel sets, and the fast cubical engine.
* [Networks and relations](networks-and-relations.md): Dowker complexes of a people-and-clubs table, and Dowker duality.

**Spaces and algebra**

* [Telling spaces apart](telling-spaces-apart.md): Betti numbers over two fields, then cup products and Steenrod squares for what counting
  cannot see.
* [Persistent group cohomology](persistent-group-cohomology.md): the homology of a group as the group grows (a proof of concept).

**The data sets.** The point clouds are in [`data/`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/noisy-circle.csv) and are produced by a seeded generator in the repository's
tests (`TutorialData`), which also checks that the files are exactly what the generator makes. Every other data set is generated in
the code on its page.

## Still to come


TODO: We want to port Henry Adams' excellent JavaPlex tutorials.

Placeholder page; nothing ported yet. When per-language (Java/Scala) tabbed examples are needed here we want some solution with tabsets.

Here's a test for us.

```scala sc:compile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab.F17.{*, given}


val chain = Fp(1) ⊠ ∆(1, 2) - ∆(2, 3)
println(chain.show)
```
