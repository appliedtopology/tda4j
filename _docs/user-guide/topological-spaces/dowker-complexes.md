---
layout: main
title: Dowker complexes
---

### Dowker complexes

A relation between two sets, given as a matrix `R(x, w)` of strengths (smaller is a stronger tie, `Infinity` for never),
has a Dowker complex: a set of rows is a simplex at time `t` when some column relates to every one of them by time `t`,
that is `f(σ) = min over w of max over x in σ of R(x, w)`. Nothing needs to be a metric: the rows and columns can be
different sets of different sizes, and the relation need not be symmetric (the weights of a directed network, for
example).

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val relation = Array(
  Array(0.0, 1.0, 2.0),  // row 0's relation to each of 3 columns
  Array(1.0, 0.0, 1.0),
  Array(2.0, 1.0, 0.0)
)
val rows = Persistence(Dowker(relation, maxDimension = 1))
val columns = Persistence(Dowker(relation, maxDimension = 1, dual = true))   // the complex on the other side
rows.triples.sorted == columns.triples.sorted                               // true: Dowker duality
```

**Duality.** The complex on the rows and the complex on the columns (`dual = true`) have the same persistent homology
(Chowdhury and Mémoli's functorial Dowker theorem). So build whichever side is smaller; the representatives are on
the side you built.

With a witness's distances to landmarks as the relation, this is the witness complex with `nu = 0`. For a plain
yes-or-no relation, `DowkerGeometry.fromBoolean(table)` gives the classical, unfiltered Dowker complex. The
complex is not a flag complex, so the Ripser engine does not apply. The [networks tutorial](../../tutorials/networks-and-relations.md)
works through an example.

From MATLAB, `TDA4j.computeFromRelation(relation, options)` (with the option `dual`); from the command line,
`--input-format csv-relation` (and `--dual`).
