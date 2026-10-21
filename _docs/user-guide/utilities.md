---
layout: main
title: Linear algebra, graphs and neighbours
---

Small building blocks the library is made of, public because they are useful on their own.

## Dense vectors and matrices

`DenseVector` and `DenseMatrix` hold `Double`s. Both are immutable values with value equality.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val a = DenseMatrix(Array(Array(2.0, 1.0), Array(1.0, 3.0)))
val b = DenseVector(1.0, 2.0)

val x = a.lu().solve(b)      // exact solve of a square system
val rank = a.svd.rank        // numerical rank
val least = a.svd.solve(b)   // least-squares solution of least norm, also for singular or non-square a
val residual = (a * x - b).norm
```

`DenseVector` has `+`, `-`, `*` (by a scalar), `dot`, `norm` and `distance`; `DenseMatrix` has `*`, `-`, `transpose`,
`row(i)`, `column(j)`, `frobeniusNorm`, `svd` (singular values, `u`, `v`, `rank`, `solve`) and `lu()`, and is built by
`DenseMatrix(rows)`, `zeros`, `identity` or `tabulate`. They are meant for the small matrices of a handful of points; the
persistence engines have their own sparse reduction.

## Row reduction over a field

`LinearAlgebra` works over any `Field`, so it also works with the finite fields used for coefficients:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val f3 = new FiniteField(3)
import f3.given

val rows = Seq(Seq(1, 2, 0), Seq(2, 1, 0), Seq(0, 0, 1)).map(_.map(f3.Fp(_)))
val (reduced, pivotColumns) = LinearAlgebra.rref(rows)
val rank = LinearAlgebra.rank(rows)
val kernelBasis = LinearAlgebra.nullspace(rows, nCols = 3)
```

Here the first two rows are proportional modulo 3, so the rank is 2 and the null space has one basis vector. `rref` returns the nonzero rows of the reduced row echelon form and the pivot column of each; `nullspace` returns a
basis of the solutions of `A x = 0`, each of the length `nCols`.

## Conjugate gradient and the error function

`ConjugateGradient.solve(operator, b)` solves `A x = b` for a symmetric positive definite `A` given only as the function
`x => A x`, so a large sparse system never needs to be formed. By default it stops at a relative residual of `1e-10`
(`tolerance`) or after `maxIterations = 1000`, throwing `ConjugateGradient.NotConvergedException`; with `check = true`
(the default) it also tests that the operator is symmetric with positive curvature and throws
`NotSymmetricPositiveDefiniteException` otherwise.

`Erf.erf(x)` and `Erf.erfc(x)` are the error function and its complement, accurate to about 1e-15.

## Spanning trees

`Kruskal(metricSpace)` computes a minimum spanning forest of the complete graph on a finite metric space:
`mstIterator` gives the tree edges in increasing length, `cyclesIterator` the remaining edges. For each remaining edge
`cycleToChain[C](edge)` is the cycle it closes with the tree, as a `Chain` over the coefficients `C`. The constructor
`new Kruskal(elements, distance, maxDistance)` takes the vertices and a distance function directly, and ignores edges of
length `maxDistance` or more.

## Nearest neighbours

`VantagePointTree(elements, distance)` is a vantage-point tree: `nearest(query, k)` returns the `k` nearest elements,
nearest first, and `withinDistance(query, radius)` the elements within a radius. It prunes with the triangle inequality,
so the distance has to be a genuine metric. `VantagePointQuery(metricSpace)` and `BruteForce(metricSpace)` wrap a finite
metric space behind the same two methods, `nearestNeighbors(v, k)` (which includes `v` itself when it is a point of the
space) and `neighbors(v, epsilon)`; the brute-force scan is also correct for a distance that violates the triangle
inequality, such as one derived from correlations.

## Finite groups

For the groups of [persistent group cohomology](../tutorials/persistent-group-cohomology.md): `g.elementOrder(a)` is the
order of an element, `g.subgroupGeneratedBy(generators)` the subgroup they generate (as a set of element indices),
`g.pSubgroups(p)` the nontrivial `p`-subgroups, and `g.isElementaryAbelian(subgroup, p)` tests one of them.
`FiniteMonoid.multiplicativeResidues(n)` is the integers modulo `n` under multiplication, a monoid that is not a group.
