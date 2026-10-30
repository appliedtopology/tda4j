---
layout: main
title: The persistent Laplacian
---

The persistent Laplacian of a filtered complex is a symmetric matrix, one for each dimension `k` and each pair of filtration
values `from <= to`. Its kernel has the dimension of the persistent Betti number: the number of classes of dimension `k`
in the complex at `from` that are still classes in the complex at `to`, which is what the barcode counts. Its other
eigenvalues are what the barcode leaves out: how well connected the classes are, so how much a small change of the
complex can do to them. The smallest eigenvalue that is not zero, the spectral gap, is the usual summary of that.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
val complex = VietorisRips(EuclideanMetricSpace(square), maxDimension = 1)

val loop = PersistentLaplacian(complex, dimension = 1, from = 1.0, to = 1.0)
loop.bettiNumber    // 1: the loop of the four sides
loop.eigenvalues    // 0, 2, 2, 4
loop.spectralGap    // Some(2.0)

val filled = PersistentLaplacian(complex, dimension = 1, from = 1.0, to = math.sqrt(2))
filled.bettiNumber  // 0: the diagonals enter and the loop is a boundary
```

`PersistentLaplacian(stream, dimension, from, to)` takes any stream of cells, a Vietoris-Rips complex or a stream you built,
and returns a result with `cells` (the simplices at `from`, the rows and columns of the matrix), `matrix`, `eigenvalues`
(smallest first), `bettiNumber` and `spectralGap`. The stream has to hold the cells one dimension above `dimension`: a
Vietoris-Rips complex built with `maxDimension = k` serves dimensions up to `k`.

## What it is

Take the chains of the complex `K_to` whose boundary lies in the complex `K_from`, and the boundary map from them into the
chains of `K_from`. With the cells as an orthonormal basis, that map has an adjoint, and the persistent Laplacian is the
map composed with its adjoint, plus the down Laplacian `∂ᵀ∂` of `K_from`. When nothing enters between `from` and `to` it
is the Hodge Laplacian `∂∂ᵀ + ∂ᵀ∂`. The coefficients are real numbers, and the matrices are dense: dimensions up to a
few thousand cells are fine, which is what a Vietoris-Rips complex of a few dozen points has.

The chains to take are those with a boundary in `K_from`, not all of `K_to`: a chain through a simplex that enters after
`from` has no boundary there. That is the difference from the Laplacian of `K_to` restricted to the cells of `K_from`, which
has a kernel of the wrong size.

## From the other interfaces

| interface | call |
|---|---|
| [MATLAB and Java](matlab.md) | `TDA4j.persistentLaplacianFromPoints(points, dimension, from, to)` and `...FromDistanceMatrix`: a `PersistentLaplacianResult` with `matrix()`, `eigenvalues()`, `bettiNumber()`, `spectralGap()` (`NaN` if there is none; `hasSpectralGap()`), `cells()` |
| [JavaScript](javascript.md) | `persistentLaplacian(points, dimension, from, to)` and `persistentLaplacianFromDistances`: an object with `matrix`, `eigenvalues`, `bettiNumber`, `spectralGap` and `cells` |
| [Python](python.md) | `tda4j.persistent_laplacian(points, dimension, start, stop)` and `persistent_laplacian_from_distances`: the same attributes, `betti_number` and `spectral_gap` (`None` if all are zero) |
| [Command line](cli.md#the-persistent-laplacian) | `tda4j --persistent-laplacian --laplacian-dimension 1 --laplacian-from 1 --laplacian-to 1 points.csv` |
