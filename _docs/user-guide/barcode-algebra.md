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
| `Barcode.isMap(source, target, matrix)` | whether the matrix is a legal map: a source bar `[b, d)` goes only to target bars `[b', d')` with `b' <= b < d' <= d`, born no later and dying no later, and alive when the source is born |
| `Barcode.image(source, target, matrix)` | the bars of the image, as a barcode |
| `Barcode.kernel(source, target, matrix)` | the bars of the kernel |
| `Barcode.cokernel(source, target, matrix)` | the bars of the cokernel |
| `Barcode.reduceMatrix(matrix)` | the matrix with its columns reduced by their lowest nonzero entries; the argument is unchanged |
| `Barcode.pivotsOf(matrix)` | the `(row, column)` position of the lowest nonzero entry of each nonzero column |

The bars are `List[PersistenceBar[F, A]]`, over any ordered filtration value. Bars of the image and the kernel keep the
annotation of the source bar they come from, and bars of the cokernel the annotation of the target bar. `image`, `kernel`
and `cokernel` refuse a matrix that `isMap` rejects. The matrix is a `DenseMatrix` of `Double`s, so the algebra is over
the real numbers; for another field see [Over another field](#over-another-field).

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

## Over another field

Whether a map is an isomorphism can depend on the field: multiplication by 2 is one over `Z/3` and zero over `Z/2`.
`MapAlgebra` does the same algebra over any `Field`, on bars given as numbers and a map given column by column, a
column for each source bar from the position of a target bar to its coefficient.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val bar = IndexedSeq(MapAlgebra.Interval(0.0, Double.PositiveInfinity))

def kernelOver(p: Int) =
  val coefficients = Coefficients(p)
  import coefficients.given
  MapAlgebra.kernel(bar, bar, IndexedSeq(Map(0 -> coefficients.fromInt(2))))

kernelOver(3)   // empty: an isomorphism
kernelOver(2)   // one bar from 0 to infinity: the map is zero
```

`MapAlgebra.image`, `kernel` and `cokernel` return `(position of the bar, interval)` pairs.

## Maps between spaces

When the map comes from a map of spaces, `PersistenceMap` writes the matrix for you. Give it the persistence diagrams of
the two spaces and the map of cells: it pushes the representative cycle of every bar of the source into the target and
reads off the bars of the target alive at the time the source bar is born.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val whole = Array.tabulate(24)(i => Array(math.cos(2 * math.Pi * i / 24), math.sin(2 * math.Pi * i / 24)))
val sub = Array.tabulate(12)(i => whole(2 * i))   // every other point

def diagram(points: Array[Array[Double]]) =
  Persistence(points, maxDimension = 1, maxFiltrationValue = 2.0, includeZeroLength = true)

val inclusion = PersistenceMap.simplicial(diagram(sub), diagram(whole))(i => 2 * i)

inclusion.image(1)      // the loop of the subsample, as the sample sees it: [0.52, 1.73)
inclusion.cokernel(1)   // the loop the sample has before the subsample does: [0.26, 0.52)
inclusion.kernel(1)     // none: both loops fill at the same value
```

| call | map |
|---|---|
| `PersistenceMap.inclusion(sub, whole)` | the cells of the first diagram are cells of the second |
| `PersistenceMap.simplicial(source, target)(vertexMap)` | simplices go to the simplex on the images of their vertices, with the sign of the permutation that sorts them, and to zero if two vertices agree |
| `PersistenceMap(source, target)(cellMap)` | any map of cells: `cellMap(cell)` lists the cells of the target the cell goes to with integer coefficients |

The result has `image(k)`, `kernel(k)` and `cokernel(k)` as lists of bars, and `entries(k)`, the nonzero entries of the
matrix as `(source bar, target bar, coefficient)` in terms of the positions in `sourceBars(k)` and `targetBars(k)`. The
coefficients are those of the diagrams, so the same map can give different answers over different fields: the boundary
circle of a Möbius band maps to twice its core circle, which is zero over `Z/2` and an isomorphism over `Z/3`.

What the diagrams have to be:

- **Over the same field and to the same filtration value.** Pass the same `characteristic` and the same
  `maxFiltrationValue` to both; the complex of a subsample is then a subcomplex of the sample's. The default
  `maxFiltrationValue` of a point cloud is its own enclosing radius, which differs between the two.
- **With zero-length bars in the target:** `includeZeroLength = true`. Their representatives are what the pushed-forward
  cycles are reduced against. `Engine.ParallelRipser` cannot give them, so it can be a source and not a target.
- **With cycles that die with their bars:** the default engine, `Engine.Cohomology` or `Engine.Ripser`. The cycles of
  `Engine.Naive` and `Engine.Chunks` are generators of the reduction that remain nonzero classes after the bar ends, and
  a matrix written in them is not the matrix of a map of barcodes; `PersistenceMap` refuses them. Cocycles are refused
  too.
- **A map that preserves the filtration:** every cell enters the target no later than it enters the source. A map that
  does not is refused when a cycle lands on cells that are not there yet, or is not a chain map.

### From the other interfaces

The map of a subsample's Vietoris-Rips complex into the whole sample's is an entry point of the interfaces that do not run
Scala. They compute both diagrams with the right engine, the shared `maxFiltrationValue` (by default the sample's
enclosing radius) and the zero-length bars the target needs, and give the bars of the image, kernel and cokernel and the
matrix.

| interface | call |
|---|---|
| [MATLAB and Java](matlab.md) | `TDA4j.computeInclusionFromPoints(points, subsample, options)` returns a `PersistenceMapResult` with `image(k)`, `kernel(k)`, `cokernel(k)`, `sourceBars(k)`, `targetBars(k)` and `entries(k)`; `computeInclusionFromDistanceMatrix`; `computeMapFromPoints(sourcePoints, targetPoints, vertexMap, options)` and `computeMapFromDistanceMatrices` for any map that does not increase a distance |
| [JavaScript](javascript.md) | `inclusionMap(points, subsample, options)`, `simplicialMap(sourcePoints, targetPoints, vertexMap, options)` and their `...FromDistances`: an object with the bars and matrix of each degree |
| [Command line](cli.md#the-map-of-a-subsample) | `tda4j --inclusion-of 0,6,12,18 points.csv` writes the bars of the image, kernel and cokernel |

The options are `engine` (`ripser`, the default, or `cohomology`: the engines whose cycles die with their bars),
`maxDimension` (default 2), `maxFiltrationValue`, `prime` (default 17) and `threads`.
