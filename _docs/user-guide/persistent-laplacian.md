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
(smallest first), `bettiNumber`, `spectralGap` and `kernel`. The stream has to hold the cells one dimension above `dimension`: a
Vietoris-Rips complex built with `maxDimension = k` serves dimensions up to `k`.

## The kernel

The kernel of the matrix is a space of chains on the cells at `from`: the cycles there that are orthogonal to the
boundaries of the chains of the complex at `to` whose own boundary lies at `from`. Their classes are a basis of what
survives from `from` to `to`, and when nothing enters in between they are the harmonic chains of the complex. `kernel`
is an orthonormal basis of it, one chain for each persistent Betti number. The space is canonical; the basis is not,
unless the dimension is one, where the chain is fixed up to sign (the sign makes its first entry positive).

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
val complex = VietorisRips(EuclideanMetricSpace(square), maxDimension = 1)

val loop = PersistentLaplacian(complex, dimension = 1, from = 1.0, to = 1.0)
loop.kernel.head.terms.map((side, c) => side.underlying.toList -> c)   // the four sides, each 1/2 in absolute value
```

## Of a simplicial map

An inclusion of one complex in a larger one is not the only map with a persistent Laplacian: every simplicial map `f` from
a complex `K` to a complex `L` has one (Gülen, Mémoli, Wan and Wang). It is a matrix on the `k`-simplices of `L` that `f`
hits, and its kernel has the dimension of the rank of `H_k(K) → H_k(L)`. A map of vertices is simplicial if it sends every
simplex of `K` to a simplex of `L`; the simplices it squeezes (two vertices on one image) go to zero.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

// a square 0-1-2-3 folded onto the triangle 0-1-2 by sending the vertex 3 to 0
val square = Seq(Simplex(0), Simplex(1), Simplex(2), Simplex(3),
                 Simplex(0, 1), Simplex(1, 2), Simplex(2, 3), Simplex(0, 3))
val triangle = Seq(Simplex(0), Simplex(1), Simplex(2), Simplex(0, 1), Simplex(1, 2), Simplex(0, 2))

val fold = PersistentLaplacian.ofMap(square, triangle, dimension = 1)(v => if v == 3 then 0 else v)
fold.bettiNumber   // 1: the loop of the square goes to the loop of the triangle
fold.eigenvalues   // 0, 2, 3
fold.cells         // the three edges of the triangle: the edge 3-0 is squeezed, 2-3 lands on 0-2
```

`PersistentLaplacian.ofMap(source, target, dimension)(vertexMap)` takes the two complexes as collections of simplices (all
their dimensions, or the three from `dimension - 1` to `dimension + 1`) and returns the same result as `PersistentLaplacian(...)`:
`cells`, `matrix`, `eigenvalues`, `bettiNumber`, `spectralGap` and `kernel`, with `weights` besides. A vertex map that is not
simplicial is an error that names the simplex. With a stream in place of each collection and a filtration value for each,
`ofMap(source, from, target, to, dimension)(vertexMap)` takes the simplices that have entered by those values: for a
filtered complex and the identity as the vertex map it is the persistent Laplacian above, entry for entry.

Where `f` sends several `k`-simplices onto one, the simplex of `L` counts for as many: its weight is their number, its chain
has the length `1 / sqrt(weight)`, and the map of chains is then an isometry on what it does not kill, which the construction
needs. The matrix is in the orthonormal basis of those chains (the paper's matrix is the same one in the basis of
unscaled chains, and the two are conjugate by the weights); `kernel` gives chains on the cells again. Without such a merge,
and in particular for an inclusion, every weight is 1. The source has to hold its simplices of dimension `k + 1` as well, for
their weights in `L`: they change the eigenvalues, never the kernel.

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
| [MATLAB and Java](matlab.md) | `TDA4j.persistentLaplacianFromPoints(points, dimension, from, to)` and `...FromDistanceMatrix`; for a simplicial map `TDA4j.persistentLaplacianOfMapFromPoints(sourcePoints, targetPoints, vertexMap, dimension, from, to)` and `...FromDistanceMatrices`: a `PersistentLaplacianResult` with `matrix()`, `eigenvalues()`, `kernel()` (one row for each persistent Betti number, an entry for each of `cells()`), `bettiNumber()`, `spectralGap()` (`NaN` if there is none; `hasSpectralGap()`), `cells()` |
| [JavaScript](javascript.md) | `persistentLaplacian(points, dimension, from, to)` and `persistentLaplacianFromDistances`; for a simplicial map `persistentLaplacianOfMap(sourcePoints, targetPoints, vertexMap, dimension, from, to)` and `persistentLaplacianOfMapFromDistances`: an object with `matrix`, `eigenvalues`, `kernel`, `bettiNumber`, `spectralGap` and `cells` |
| [Python](python.md) | `tda4j.persistent_laplacian(points, dimension, start, stop)` and `persistent_laplacian_from_distances`; for a simplicial map `persistent_laplacian_of_map(source_points, target_points, vertex_map, dimension, start, stop)` and `..._from_distances`: the same attributes, `betti_number`, `spectral_gap` (`None` if all are zero) and `kernel` (an array of shape `(betti_number, len(cells))`) |
| [Command line](cli.md#the-persistent-laplacian) | `tda4j --persistent-laplacian --laplacian-dimension 1 --laplacian-from 1 --laplacian-to 1 points.csv`; for a simplicial map add `--laplacian-target other.csv --laplacian-vertex-map 0,1,1,0` |
