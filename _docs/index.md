---
layout: main
---

<center>

![TDA4j](/images/large-logo.svg)

</center>

TDA4j is a library for persistent homology and related techniques of computational and applied topology, for Scala
and the JVM, with interfaces for MATLAB, Java and the command line. It continues the libraries of the Computational
Topology workgroup at Stanford (JavaPlex and its predecessors).

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(40)(i => Array(math.cos(i * 0.16), math.sin(i * 0.16)))
val diagram = Persistence(points)          // the Vietoris-Rips diagram, degrees 0 and 1
diagram.dim(1).longest                     // the loop, with a cycle that goes around it
```

## What it offers

* **Many complexes**: Vietoris-Rips, Čech, alpha, sparse Rips, witness, distance-to-measure, Dowker and cubical
  complexes, and simplicial sets for spaces you build yourself, down to classifying spaces of finite groups.
* **Any coefficients**: every engine works over any prime field or the reals. The default is the field with 17
  elements, where signs and odd torsion are visible.
* **Representatives**: every bar comes with a cycle (or cocycle) that witnesses it, so you can see where a feature is,
  and compute circular and toroidal coordinates from it.
* **Several engines** for the same computation, from a reference implementation to Ripser's algorithm and specialized
  engines for images and alpha complexes, checked against each other.
* **Long computations** that can be advanced in steps and read at any scale while they run.

## Where to start

* The [user guide](user-guide/index.md), starting with the [quickstart](user-guide/quickstart.md).
* The [tutorials](tutorials/index.md): one question and one data set each, worked through to the answer.
* The [developer's guide](developers-guide/index.md), for how the library is built.
* The API documentation: [[org.appliedtopology.tda4j]].

For persistent homology itself, we recommend the book
[Topological Data Analysis with Applications](https://www.cambridge.org/core/books/topological-data-analysis-with-applications/00B93B496EBB97FB6E7A9CA0176F0E12)
by Gunnar Carlsson and Mikael Vejdemo-Johansson, and the survey
[Topology and Data](http://www.ams.org/journals/bull/2009-46-02/S0273-0979-09-01249-X/S0273-0979-09-01249-X.pdf) by
Gunnar Carlsson.
