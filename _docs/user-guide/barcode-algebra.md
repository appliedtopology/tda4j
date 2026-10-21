---
layout: main
title: Maps between barcodes
---

A barcode is a persistence module: a direct sum of interval modules. A map between two barcodes is a matrix with one
column per bar of the source and one row per bar of the target, entry `(r, c)` being the coefficient with which the
source bar `c` maps to the target bar `r`. `Barcode` computes the image, kernel and cokernel of such a map as barcodes
again, for example to compare the homology of a subspace with that of the space it sits in.

| call | result |
|---|---|
| `Barcode.isMap(source, target, matrix)` | whether the matrix is a legal map: every nonzero entry joins two bars that overlap, with the target born no later than the source and dying no later than the source |
| `Barcode.image(source, target, matrix)` | the bars of the image, as a barcode |
| `Barcode.kernel(source, target, matrix)` | the bars of the kernel |
| `Barcode.cokernel(source, target, matrix)` | the bars of the cokernel |
| `Barcode.imageMatrix(...)`, `Barcode.cokernelMatrix(...)` | the reduced matrix the pairing is read from |
| `Barcode.reduceMatrix(matrix)` | the matrix with its columns reduced by their lowest nonzero entries; the argument is unchanged |
| `Barcode.pivotsOf(matrix)` | the `(row, column)` position of the lowest nonzero entry of each nonzero column |

The bars are `List[PersistenceBar[F, A]]`. Bars of the image keep the annotation of the source bar they come from, and
bars of the cokernel the annotation of the target bar. The matrix is a `DenseMatrix` of `Double`s, so the reduction is
over the real numbers.

## Building bars by hand

`BarcodeBuilder` is a small notation for writing bars as intervals: `a bc b` is the half-open bar `[a, b)`, and
`clcl`, `opcl`, `opop`, `clinf(a)` and `infop(b)` give the other endpoint kinds. `dim(d)(...)` sets the degree.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val b = BarcodeBuilder[Double]()
import b.*

val source = List(dim(0)(2.0 bc 5.0), dim(0)(3.0 bc 7.0))
val target = List(dim(0)(1.0 bc 6.0), dim(0)(0.0 bc 4.0))
val matrix = DenseMatrix(Array(Array(0.0, 1.0), Array(1.0, 1.0)))

assert(Barcode.isMap(source, target, matrix))

Barcode.image(source, target, matrix)    // [3, 6) and [2, 4)
Barcode.kernel(source, target, matrix)   // [6, 7) and [4, 5)
Barcode.cokernel(source, target, matrix) // [0, 2) and [1, 3)
```

The first column says that the source bar `[2, 5)` maps to the target bar `[0, 4)`, and the second that `[3, 7)` maps to
both target bars. The results split the bars accordingly: the source bar `[2, 5)` is its image `[2, 4)` followed by its
kernel `[4, 5)`, and the target bar `[1, 6)` is its cokernel `[1, 3)` followed by the image `[3, 6)`.

Bars computed by `Persistence(...)` work the same way, with `diagram.bars` as the lists. Bars with infinite endpoints are
allowed. The matrix has to come from you, for instance from the induced map on homology of an inclusion of complexes.
