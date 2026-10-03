---
layout: main
---

### Dowker complexes

```scala 3
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)

val relation = Array(
  Array(0.0, 1.0, 2.0),  // point 0's own relation value to each of 3 witnesses
  Array(1.0, 0.0, 1.0),  // point 1's
  Array(2.0, 1.0, 0.0)   // point 2's
)
val geometry = DowkerGeometry(relation)
val stream = Dowker(relation)
val homology = SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(stream)
```

Dowker's complex (1952), generalized to a real-valued, filtered relation `R: L x W -> [0, Infinity]` the way
Chowdhury & Mémoli's "functorial Dowker theorem" does — unlike the witness complex above, `R` need not come
from a metric at all, and `L`/`W` need not be the same set or even the same size. A subset `sigma` of `L`
becomes a simplex at time `t` iff some witness relates to every point of `sigma` by time `t`:
`f(sigma) = min_w max_{x in sigma} R(x,w)`. This directly generalizes witness's own `nu = 0` case — pass the
landmark-to-witness distance matrix as `R` and you get the same construction — but also covers relations with
no metric behind them at all, e.g. persistent homology of a directed/asymmetric network's own edge weights
(`R(x,y)` = the weight of the edge from `x` to `y`, no symmetrization needed).

For the classical (unfiltered) Dowker complex — a plain boolean "is `x` related to `w`" relation, no notion of
time — use `DowkerGeometry.fromBoolean`:

```scala 3
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)

val covers = Seq(
  Seq(true, false, true),   // point 0 is covered by witnesses 0 and 2
  Seq(true, true, false),
  Seq(false, true, true)
)
val classical = DowkerGeometry.fromBoolean(covers)
```

**Duality is the whole point of this construction.** `geometry.dual` (equivalently `stream.dual`) gives the
complex on the OTHER side — vertices = witnesses, related back to `L` via the transposed relation — and the
functorial Dowker duality theorem guarantees its barcode agrees EXACTLY with the original side's, once
zero-persistence (birth == death) bars are dropped from both (a real artifact when `L` and `W` differ in size:
a simplicial filtration records one `H_0` birth per vertex, so differently-sized sides can't match bar-for-bar
without dropping those). Pick whichever side is more convenient — e.g. if `W` is small but you want
representatives over it, `stream.dual` gets you there directly without transposing `relation` by hand.

Like Cech/witness/Sheehy above, this is **not a flag complex** in general (a witness for a whole simplex need
not witness any of its edges), so `engine=ripser`/`chunks` don't apply — use `naive` or `cohomology`.

From MATLAB/CLI, this is its own entry point (`computeFromRelation`/`--input-format csv-relation`), not a
`complex=` value on `computeFromPoints`/`computeFromDistanceMatrix` — a relation isn't a point cloud or a
square/symmetric distance matrix. See "Calling from MATLAB or Java" below; `"dual"`/`--dual` computes the
`W`-side complex directly.