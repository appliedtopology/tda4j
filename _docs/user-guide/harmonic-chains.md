---
layout: main
title: Harmonic chains
---

A bar of the barcode comes with a cycle that shows where the feature is, but the cycle is only one of many: add any
boundary and it is still a representative. The harmonic cycle is the shortest one in its class: the cycle that is
orthogonal to every boundary of the complex (the kernel of the Hodge Laplacian). Each class has exactly one, so it is a
canonical choice. There are two ways to follow harmonic chains along a filtration, and `tda4j` has both.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
val rips = VietorisRips(EuclideanMetricSpace(square), maxDimension = 1)

val diagram = HarmonicRepresentatives(rips, maxDimension = 1)
val loop = diagram.dim(1).longest.get
loop.birth                                                        // 1.0, when the fourth side enters
loop.death                                                        // sqrt(2), when the diagonals fill the square
loop.representative.terms.map((side, c) => side.underlying.toList -> c)
// the four sides, with coefficients 1, 1, 1 and -1: a cycle
```

`HarmonicRepresentatives(stream, maxDimension)` gives the ordinary barcode, bar for bar, and replaces each bar's
representative by the harmonic cycle at the bar's birth (Basu and Cox, *Harmonic persistent homology*). The cycle is
picked out by the filtration's values alone, so it does not depend on how cells of equal value are ordered. A bar
that is the only one with its endpoints has a harmonic space of dimension one, and the chain is scaled to 1 at its first
cell; a group of bars with the same endpoints gets an orthogonal basis of their space.

## Harmonic cocycles

The same construction runs on cohomology: `representatives = Representatives.Cocycles` gives each bar the harmonic
cocycle of its class instead of the harmonic cycle. Cochains and chains share one coordinate system (the cells are
orthonormal), so a harmonic cocycle is a vector on the same cells, and the harmonic cochains of a complex are its
harmonic chains. What differs is where the representative sits and which vector it is. Cocycles restrict from a larger
complex to a smaller one, so a class is born at the largest value of its bar and dies going down: the bar `[s, t)` is
born at the last value before `t`, and its representative is the harmonic cochain there. The maps are the orthogonal
projections of the harmonic cochains at a later value onto those at an earlier one, so the representative is chosen
orthogonal to other classes than the cycle is.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
val rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1)

val cocycles = HarmonicRepresentatives(rips, maxDimension = 1, representatives = Representatives.Cocycles)
cocycles.dim(1).longest.get.representative.terms   // the harmonic cochain of the loop, at the last value before sqrt(2)
```

With two loops the difference shows. If a loop `abc` appears at 1, a second loop `abd` at 2 and a triangle fills `abd`
at 3, the cycle of the long bar `[1, ∞)` is the plain loop `abc`, and its cocycle is `abc` tilted away from `abd`, the
harmonic cochain of the filled complex; for the short bar `[2, 3)` the cocycle is the plain loop `abd`, and its cycle is
the tilted one. A cocycle is what circular coordinates are built from; the harmonic one is the smoothest in its class.

## The harmonic chain barcode

`HarmonicChainBarcode` follows the harmonic chains themselves (Hou, Parsa and Wang, *Tracking the persistence of
harmonic chains*). The harmonic cycles of the complex change whenever a cell enters, and a cycle stops being harmonic as
soon as it is no longer orthogonal to the boundaries, which is before it becomes a boundary. A bar of this barcode is a
cycle that stays harmonic for its whole life, and the representative is that one cycle.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
val rips = VietorisRips(EuclideanMetricSpace(square), maxDimension = 1)

val barcode = HarmonicChainBarcode(rips, maxDimension = 1, coefficients = Coefficients.Rationals)
barcode.dim(1).bars.map(bar => (bar.birth, bar.death))     // one loop, from 1.0 to sqrt(2)
barcode.dim(1).longest.get.representative.terms            // exact rational coefficients
```

The harmonic chain barcode is not the ordinary barcode. It has the same births and the same deaths, and the same
number of bars alive at every value, but it can pair them differently: a filtration whose ordinary bars are
`[4, 9), [5, 10), [7, 8)` can have the harmonic bars `[4, 8), [5, 9), [7, 10)`. The two answer different questions. The
ordinary bar asks how long a class lives; the harmonic bar asks how long one particular cycle stays the best
representative.

| | `HarmonicRepresentatives` | `HarmonicChainBarcode` |
|---|---|---|
| bars | the ordinary bars | the harmonic bars: the same births and deaths, paired differently |
| representative | the harmonic cycle at the birth | the cycle that is harmonic for the whole bar |
| depends on the order of cells of equal value | no | yes |
| bars of length zero | none (they are not classes of the persistence module) | dropped unless `includeZeroLength = true` |

Use `HarmonicRepresentatives` for a Vietoris-Rips complex. In a Vietoris-Rips filtration a triangle enters at the same
value as its longest edge, and where that edge starts a new loop, the harmonic rule ends the oldest loop that the
triangle's boundary is not orthogonal to, and not the new one. A long ordinary bar is cut in two there, and a bar with
several such events inside it appears as a run of consecutive bars. The harmonic chain barcode is at its best for a
filtration whose cells enter at distinct values, or one you order yourself with `HarmonicChainBarcode.ofOrder`.

## Coefficients

Harmonic means orthogonal, so the coefficients are the real numbers (`Coefficients(0)`, the default: a tolerance
decides which entries are zero) or the exact rationals (`Coefficients.Rationals`, no tolerance, and numbers that can
grow). A finite field is refused. The dot product exists over `Z/p`, but it is not positive definite: a nonzero chain can
be orthogonal to itself (the boundary of a triangle has length 3, which is 0 modulo 3), and then an orthogonal
complement does not complement. The algorithm divides by such lengths and the harmonic space can be larger than the
homology, so over a small prime it stops with an error.

The chains at one value are available as well: `HarmonicChainBarcode.harmonicChains(stream, dimension, value,
coefficients)` is a basis of the harmonic chains of a degree at a value, one for each Betti number, in the order they
were born.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
val rips = VietorisRips(EuclideanMetricSpace(square), maxDimension = 1)

HarmonicChainBarcode.harmonicChains(rips, dimension = 1, value = 1.0, Coefficients.Rationals).size  // 1: the loop
HarmonicChainBarcode.harmonicChains(rips, dimension = 1, value = 2.0, Coefficients.Rationals).size  // 0: filled in
```

## Cost

The cost is cubic in the number of cells in the worst case. The default keeps an orthogonal basis of all the cycles of a
degree in memory, and that is what limits it: it grows with every positive cell and holds a dense chain for each. For a
larger complex, `orthogonalization = HarmonicChainBarcode.Orthogonalization.LaplacianSolve` (on `HarmonicChainBarcode` and on
`HarmonicRepresentatives`) keeps only the boundaries and finds each new chain by solving a linear system, which takes a
fraction of the memory. The two give the same chains over the rationals. Over the reals each decision is a comparison with
the tolerance, so on a large cloud with many nearly tied values they can pair a few bars differently; `Coefficients.Rationals` gives the
exact pairing, and the solve says what to use instead if it cannot reach the tolerance.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
val rips = VietorisRips(EuclideanMetricSpace(square), maxDimension = 1)

val solved = HarmonicChainBarcode(
  rips,
  maxDimension = 1,
  coefficients = Coefficients.Rationals,
  orthogonalization = HarmonicChainBarcode.Orthogonalization.LaplacianSolve
)
solved.dim(1).longest.get.representative.terms   // the loop of the four sides, as with the default
```

The chains are kept sparse. The faster engines do not apply: a harmonic chain depends on the inner product of
the cells, which a reduction of the complex (discrete Morse theory, an edge collapse) does not keep, so those change the
harmonic barcode instead of computing it faster.

## From the other interfaces

| interface | call |
|---|---|
| [MATLAB and Java](matlab.md) | `TDA4j.harmonicChainBarcodeFromPoints(points, options)` and `harmonicRepresentativesFromPoints`, with `...FromDistanceMatrix` twins: a `PersistenceResult` whose `cycleVertices(i)` and `cycleCoefficients(i)` are the harmonic cycle |
| [JavaScript](javascript.md) | `harmonicChainBarcode(points, options)` and `harmonicRepresentatives`, with `...FromDistances` twins: a `Result`, `representative(i)` |
| [Python](python.md) | `tda4j.harmonic_chain_barcode(points, **options)` and `harmonic_representatives`, with `..._from_distances` twins |
| [Command line](cli.md#harmonic-chains) | `tda4j --harmonic chains --representatives points.csv`, or `--harmonic representatives` |

The options are `maxDimension` (default 2), `maxFiltrationValue`, `field` (`R`, the default, or `Q`), `epsilon` (the
tolerance of `R`), `orthogonalization` (`gram-schmidt`, the default, or `laplacian-solve`; see Cost), `includeZeroLength`
(the barcode only), `representativeType` (the representatives only), `minPersistence`, `minPersistenceFraction` and `threads`. A
finite field, or an option of another computation such as `engine` or `complex`, is an error that names it.
