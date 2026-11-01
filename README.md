[![test](https://github.com/appliedtopology/tda4j/actions/workflows/test.yml/badge.svg?branch=scala)](https://github.com/appliedtopology/tda4j/actions/workflows/test.yml)
[![lint](https://github.com/appliedtopology/tda4j/actions/workflows/lint.yml/badge.svg?branch=scala)](https://github.com/appliedtopology/tda4j/actions/workflows/lint.yml)
[![docs](https://github.com/appliedtopology/tda4j/actions/workflows/docs.yml/badge.svg?branch=scala)](https://tda4j.appliedtopology.org/)
[![coverage](https://codecov.io/gh/appliedtopology/tda4j/branch/scala/graph/badge.svg)](https://app.codecov.io/gh/appliedtopology/tda4j)
[![Latest Release](https://maven-badges.sml.io/sonatype-central/org.appliedtopology/tda4j_3/badge.svg?subject=Latest%20Release)](https://maven-badges.sml.io/sonatype-central/org.appliedtopology/tda4j_3/)

# tda4j

Persistent homology and topological data analysis on the JVM and in JavaScript: from Scala, Java, MATLAB, Python, JavaScript, TypeScript and the command line.

tda4j computes persistent homology of point clouds (Vietoris-Rips, Čech, alpha, witness, Dowker, DTM and sparse Rips
complexes), images (cubical complexes), simplicial sets and nerves of groups, monoids and categories. Every barcode
comes with a representative cycle or cocycle for each bar, over any prime field. It is built for ease of use, for
access from MATLAB and other JVM languages, and as a platform for research: the machinery behind the convenient entry
points is public and meant to be extended. It descends from the libraries of the Computational Topology workgroup at
Stanford University (JavaPlex, JPlex, PLEX).

## Using it

[Installation](https://tda4j.appliedtopology.org/user-guide/installation.html) lists requirements and dependency lines
for every build tool. In short, from sbt (Java 21, Scala 3.9):

```scala
libraryDependencies += "org.appliedtopology" %% "tda4j" % "VERSION"
```

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(30)(i => Array(math.cos(i * 0.21), math.sin(i * 0.21)))
val diagram = Persistence(points)   // degrees 0 to 2, coefficients in the field with 17 elements
diagram.dim(1).longest              // the most persistent loop, with a representative cycle
```

From Java, with `tda4j-VERSION-assembly.jar` on the class path:

```java
import org.appliedtopology.tda4j.matlab.*;

double[][] points = { {0, 0}, {1, 0}, {1, 1}, {0, 1} };
PersistenceResult result = TDA4j.computeFromPoints(points, new String[] {"maxDimension", "1"});
double[][] bars = result.toArray();   // one row per bar: dimension, birth, death
```

From MATLAB, `javaaddpath('tda4j-VERSION-assembly.jar')` and the same calls
([MATLAB and Java](https://tda4j.appliedtopology.org/user-guide/matlab.html)). From the command line,
`java -jar tda4j-VERSION-assembly.jar --max-dimension 1 points.csv`. The jar is on the
[releases](https://github.com/appliedtopology/tda4j/releases) page.

From Python (3.10 or newer, with Java 21 or newer installed), with the wheel from the
[releases](https://github.com/appliedtopology/tda4j/releases); the package is not on PyPI yet:

```
pip install tda4j-VERSION-py3-none-any.whl
```

```python
import numpy as np, tda4j
result = tda4j.persistence(np.array([[0, 0], [1, 0], [1, 1], [0, 1]]), max_dimension=1)
result.bars   # rows (dimension, birth, death)
```

From JavaScript or TypeScript (browsers, and Node.js 20.16 or newer):

```
npm install @appliedtopology/tda4j
```

```js
import { persistence } from "@appliedtopology/tda4j";
const result = persistence([[0, 0], [1, 0], [1, 1], [0, 1]], { maxDimension: 1 });
result.bars;   // [{ dim: 0, birth: 0, death: Infinity }, ...]
```

In a Scala.js project the same sbt line picks the Scala.js artifact. `tda4j-demo-VERSION.jar` is a self-contained Scala REPL with the
library imported: `java -jar tda4j-demo-VERSION.jar`.

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

To cite tda4j, use GitHub's "Cite this repository" ([CITATION.cff](CITATION.cff)). Security reports:
[SECURITY.md](SECURITY.md). tda4j is released under the [MIT License](LICENSE.md).
