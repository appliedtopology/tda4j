---
layout: main
title: Sparse Rips complexes
---

### Sparse Rips

The sparse Rips filtration of Cavanna, Jahanseir and Sheehy keeps, at each scale, only a well-spread subset of the
points. Its size is linear in the number of points (for data of bounded doubling dimension), and its diagram
approximates the Vietoris-Rips diagram within a factor `1 + ε` in the scale.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(40)(i => Array(math.cos(i * 0.16), math.sin(i * 0.16)))
val metricSpace = EuclideanMetricSpace(points)
val diagram = Persistence(SparseRips(metricSpace, epsilon = 0.5, maxDimension = 1))
```

`epsilon` is strictly between 0 and 1: smaller is closer to Vietoris-Rips and larger. Use it when the Vietoris-Rips
complex is too big and an approximation is acceptable; the [tutorial](../../tutorials/choosing-a-complex.md) compares it
with the exact complexes. The filtration is not a plain diameter filtration, so the Ripser engine does not apply.

A note on the reference: the paper's Algorithm 3 computes an edge's birth without checking that both endpoints are still
present (their balls have not vanished), which its own Section 5.3 requires for the filtration to be monotone. The
implementation follows Section 5.3.

From MATLAB and the command line: `complex=sparse-rips` with `sparseEpsilon`.
