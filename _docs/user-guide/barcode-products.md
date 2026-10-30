---
layout: main
title: Products, Hom and duals of barcodes
---

A barcode is a persistence module, and modules can be multiplied, mapped between and dualized. `Barcode` does that on the
bars: each call below takes lists of bars, `diagram.bars` for instance, and gives a barcode again, in the degrees that add
up as the algebra says. Only the numbers of the endpoints count.

| call | result |
|---|---|
| `Barcode.tensor(a, b)` | the pointwise tensor product `t -> A_t ⊗ B_t`: `[a, b)` and `[c, d)` give `[max(a, c), min(b, d))`, if that is not empty, and the degrees add |
| `Barcode.convolve(a, b)` | the tensor product over the filtration parameter (the Day convolution): `[a, b)` and `[c, d)` give `[a + c, min(b + c, a + d))` |
| `Barcode.tor(a, b)` | the first Tor over the filtration parameter: two bars that die give `[max(b + c, a + d), b + d)`, in degree `p + q + 1` |
| `Barcode.kunneth(a, b, combine)` | the barcode of the homology of a product of two filtered complexes, from the barcodes of the factors |
| `Barcode.hom(a, b)` | the internal Hom: `[a, b)` and `[c, d)` give `[max(c - a, d - b), d - a)`, in degree `q - p` |
| `Barcode.homDimension(a, b)` | the dimension of the space of maps from `a` to `b`, degree by degree |
| `Barcode.dual(a)` | the dual: `[b, d)` becomes `(-d, -b]` |

## The product of two complexes

The Vietoris-Rips complex of a point cloud `X` and the one of `Y` have a product, a complex with a cell `σ × τ` for each
pair of cells, and a filtration on it has to say when `σ × τ` enters. `ProductComplex` offers the two natural answers, the
larger of the two values (`Combine.Max`, the default) and their sum (`Combine.Sum`), and returns a stream any engine
computes on:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val circle = Array.tabulate(12)(i => Array(math.cos(2 * math.Pi * i / 12), math.sin(2 * math.Pi * i / 12)))
val line = Array.tabulate(5)(i => Array(i.toDouble, 0.0))

val x = VietorisRips(EuclideanMetricSpace(circle), maxDimension = 2)
val y = VietorisRips(EuclideanMetricSpace(line), maxDimension = 2)

val product = Persistence(ProductComplex(x, y), maxDimension = 2)
val predicted = Barcode.kunneth(Persistence(x, maxDimension = 2).bars, Persistence(y, maxDimension = 2).bars)
```

`product` and `predicted` have the same bars. That is the persistent Künneth theorem:

- With `Max`, a sublevel set of the product is the product of the two sublevel sets, so `H_n(X × Y)` is the sum of
  `H_p(X) ⊗ H_q(Y)` over `p + q = n` at every value of the filtration, and the barcode is `Barcode.tensor` of the two.
- With `Sum`, the chain complex of the product is the tensor product of the two filtered chain complexes. The homology of
  that is `Barcode.convolve` of the homologies, plus `Barcode.tor` of the homologies of degrees adding up to `n - 1`: a
  class that exists only because two classes of the factors die at the right times.

Two segments, each two points that an edge joins at 1, are the smallest case. Their product is a square: with `Max` all
four sides and the square enter at 1 and nothing is left but a point, with `Sum` the four sides enter at 1 and the square at
2, so a loop lives from 1 to 2. That loop is the Tor term.

The product has a cell for every pair, so it is for factors of a few hundred cells each. A factor cut off at a homological
degree `k` makes the product right up to the smaller of the two degrees. The bars carry chains of `ProductCell`s, and
`cell.left` and `cell.right` are the cells of the factors.

For the Vietoris-Rips complex there is a check that needs no product complex: the complex of the product point cloud with
the larger of the two distances is homotopy equivalent to the product of the two complexes at every scale, so it has the
same barcode. That is also how to get the barcode of a product from an interface that has no product complexes.

## Hom and the dual

`Barcode.hom(a, b)` is the module of maps `a -> b` shifted by `t`. At `t` the space of maps is spanned by the pairs of a
bar `[b₁, d₁)` of `a` and a bar `[b₂, d₂)` of `b` that have a map of interval modules after the shift, and `Barcode.hom` is
the barcode of how they live together as `t` grows. It is the right adjoint of `Barcode.convolve`:
`Hom(A ⊛ B, C)` and `Hom(A, [B, C])` always have the same dimension. This is the module of maps that exist, not the derived
one: there is no Ext.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val b = BarcodeBuilder[Double]()
import b.*

val from = List(dim(0)(0.0 bc 3.0))
val to = List(dim(0)(1.0 bc 5.0))

Barcode.hom(from, to)         // [2, 5): maps appear when `to` is shifted back by 2
Barcode.homDimension(from, to) // 0: no map without a shift
Barcode.homDimension(to, from) // 1
Barcode.dual(from)             // (-3, 0]
```

The dual of the barcode of the homology in degree `k` is the barcode of the cohomology in degree `k`, indexed by `-t`: the
pairs of values are the same, which is why the cohomology engines find the bars the homology engines do. `Barcode.dual` of
`Barcode.dual` is the barcode you started with.

## From the other interfaces

The algebra takes rows `(dimension, birth, death)` and gives rows back; the product of complexes is Scala only.

| interface | call |
|---|---|
| [MATLAB and Java](matlab.md) | `TDA4j.productBars(first, second, filtration)` (`"max"` or `"sum"`), `homBars(first, second)`, `homDimension(first, second)`, `dualBars(bars)`; the arrays are `result.toArrayUnfiltered()` |
| [JavaScript](javascript.md) | `productBars(first, second, filtration)`, `homBars`, `homDimension`, `dualBars`; the arrays are `result.bars` |
| [Python](python.md) | `tda4j.product_bars(first, second, filtration="max")`, `hom_bars`, `hom_dimension`, `dual_bars`; each barcode is a result (all of its bars) or an array of rows |
| [Command line](cli.md#the-algebra-of-barcodes) | `tda4j --barcode-op product-sum --barcode-with other.csv points.csv` |
