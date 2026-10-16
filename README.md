[![test](https://github.com/appliedtopology/tda4j/actions/workflows/test.yml/badge.svg?branch=scala)](https://github.com/appliedtopology/tda4j/actions/workflows/test.yml)
[![lint](https://github.com/appliedtopology/tda4j/actions/workflows/lint.yml/badge.svg?branch=scala)](https://github.com/appliedtopology/tda4j/actions/workflows/lint.yml)
[![docs](https://github.com/appliedtopology/tda4j/actions/workflows/docs.yml/badge.svg?branch=scala)](https://tda4j.appliedtopology.org/)
[![coverage](https://codecov.io/gh/appliedtopology/tda4j/branch/scala/graph/badge.svg)](https://app.codecov.io/gh/appliedtopology/tda4j)
[![Latest Release](https://maven-badges.sml.io/sonatype-central/org.appliedtopology/tda4j_3/badge.svg?subject=Latest%20Release)](https://maven-badges.sml.io/sonatype-central/org.appliedtopology/tda4j_3/)

# tda4j

Persistent homology and topological data analysis on the JVM: from Scala, Java, MATLAB and the command line.

tda4j computes persistent homology of point clouds (Vietoris-Rips, Čech, alpha, witness, Dowker, DTM and sparse Rips
complexes), images (cubical complexes), simplicial sets and nerves of groups, monoids and categories. Every barcode
comes with a representative cycle or cocycle for each bar, over any prime field. It is built for ease of use, for
access from MATLAB and other JVM languages, and as a platform for research: the machinery behind the convenient entry
points is public and meant to be extended. It descends from the libraries of the Computational Topology workgroup at
Stanford University (JavaPlex, JPlex, PLEX).

## Using it

From sbt (Scala 3.9):

```scala
libraryDependencies += "org.appliedtopology" %% "tda4j" % "<latest version>"
```

Maven coordinates: `org.appliedtopology:tda4j_3:<latest version>`. Then, in a Scala file:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(30)(i => Array(math.cos(i * 0.21), math.sin(i * 0.21)))
val diagram = Persistence(points)   // degrees 0 to 2, coefficients in the field with 17 elements
diagram.dim(1).longest              // the most persistent loop, with a representative cycle
```

For the command line and MATLAB, download `tda4j-<version>-assembly.jar` from the
[releases](https://github.com/appliedtopology/tda4j/releases) and run `java -jar tda4j-<version>-assembly.jar --help`, or
add it to MATLAB's Java class path.

The distributed `tda4j-demo-<version>.jar` is a self-contained demo jar: execute it as
`java -jar tda4j-demo-<latest version>.jar` to get a Scala REPL environment with imports in place without
needing a local Scala installation.

## Documentation

[tda4j.appliedtopology.org](https://tda4j.appliedtopology.org/) has the user guide (with the
[quickstart](https://tda4j.appliedtopology.org/user-guide/quickstart.html)), tutorials, the API reference and the
developer's guide. For persistent homology itself, we recommend the book 
[Topological Data Analysis with Applications](https://www.cambridge.org/core/books/topological-data-analysis-with-applications/00B93B496EBB97FB6E7A9CA0176F0E12) 
or Gunnar Carlsson's survey [Topology and Data](http://www.ams.org/journals/bull/2009-46-02/S0273-0979-09-01249-X/S0273-0979-09-01249-X.pdf).

## Presentations

* [Tutorial Seminar Talk, 2026-10-16, CUNY Graduate Center](https://tda4j.appliedtopology.org/presentations/tutorial-2026-10-16)

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers setting up, the commands, the workflow from issue to pull request, and the
design principles. Questions, limitations and planned work are tracked as
[issues](https://github.com/appliedtopology/tda4j/issues); [CHANGELOG.md](CHANGELOG.md) lists what changed in each
release. The people behind tda4j are in [CONTRIBUTORS.md](CONTRIBUTORS.md).

tda4j is released under the [MIT License](LICENSE.md).
