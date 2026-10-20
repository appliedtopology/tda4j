---
layout: main
title: Troubleshooting
---

### The compiler rejects the library's types, or reports dozens of errors

Add `import scala.language.experimental.modularity` at the top of the file. The library is built with experimental
Scala features, and Scala asks every file that uses it to opt in. In sbt, `-experimental` does the same for the whole
project.

### The barcode is empty or shorter than expected

* Zero-length bars are dropped. Pass `includeZeroLength = true` (MATLAB `includeZeroLength`, command line
  `--include-zero-length`) to see them.
* `Persistence(...)` computes degrees 0 to 2 by default. Raise `maxDimension` for higher degrees.
* `maxFiltrationValue` defaults to the minimum enclosing radius for point clouds, past which nothing new is born.
  A smaller value can cut features off.
* A pixel of value `NaN` in an image is masked out of the complex. In MATLAB and on the command line, `nanMask false` makes it an error.

### The answer differs between runs with different coefficients

The default field has 17 elements. Classes that exist only with odd torsion appear over `characteristic = 17` and
not over 2; use `characteristic = 0` for real coefficients. Compare like with like when checking against another
library, which often defaults to the field with 2 elements.

### `OutOfMemoryError`, or a computation that does not finish

* Give the JVM more memory: `java -Xmx8g -jar tda4j-VERSION-assembly.jar ...`, or `-Xmx` in your build's
  `javaOptions`.
* Lower `maxDimension` or `maxFiltrationValue`: the number of simplices grows quickly with both.
* Use a smaller complex for the same question: `SparseRips`, `Witness`, `AlphaShapes` for low-dimensional points,
  or an edge collapse. See [choosing a complex](../tutorials/choosing-a-complex.md) and
  [scaling up](../tutorials/scaling-up.md).
* Pick the engine on purpose: see [which engine](homology-computation/choosing-engine.md).

### Cocycles are refused

The `fast-cubical` and `fast-alpha` engines give cycles only. Choose another engine for cocycles.

### JavaScript: the package does not load

Node.js 20.16 or newer is required. In a browser, load the package as an ES module.

### Still stuck

Search the [issues](https://github.com/appliedtopology/tda4j/issues), or open one with the smallest input that shows
the problem, the version, and what you expected.
