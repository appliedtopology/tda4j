---
layout: main
title: Quickstart
---

### Persistent homology in one call

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(30)(i => Array(math.cos(i * 0.21), math.sin(i * 0.21)))
val diagram = Persistence(points)
println(diagram)          // a summary: bars per degree, the longest first
diagram.dim(1).longest    // the most persistent loop
diagram.bettiNumbers      // Vector(1, 1): one component, one loop alive at the end
```

`Persistence(...)` builds a filtered complex, computes its persistent homology and returns a `PersistenceDiagram`: the
bars with their representatives, as an immutable value. It takes

* points (`Array[Array[Double]]`, `Seq[Seq[Double]]`, ...) or a metric space, for the Vietoris-Rips complex by default;
* an `Image(...)`, for a cubical complex (see [cubical complexes](topological-spaces/cubical-complexes.md));
* any complex you built yourself, such as `Witness(...)`, `Dowker(...)` or `SparseRips(...)`
  (see [complexes](topological-spaces/index.md)).

and the options, all with defaults:

| option | default | |
|---|---|---|
| `maxDimension` | `1` | the top homological degree: components (0) and loops (1) |
| `maxFiltrationValue` | the minimum enclosing radius | where to stop the filtration; past the default nothing new is born |
| `complex` | `VietorisRips` | or `Cech`, `AlphaShapes`, for points |
| `characteristic` | `17` | the coefficients: a prime `p` for the field with `p` elements, `0` for real numbers |
| `engine` | `Persistence.Engine.Chunks` | or `Naive`, `Cohomology`, `Ripser` (see [engines](homology-computation/choosing-engine.md)) |
| `includeZeroLength` | `false` | also report bars `[v, v)`, cells paired with cells entering at the same value |

The default field has 17 elements rather than 2: over the field with 2 elements signs disappear, and so do classes
that only exist with odd coefficients.

### Reading a diagram

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(30)(i => Array(math.cos(i * 0.21), math.sin(i * 0.21)))
val diagram = Persistence(points)

diagram.bars                    // every bar, as PersistenceBar
diagram.dim(0)                  // the diagram restricted to degree 0
diagram.longest(3)              // the three most persistent bars
diagram.essential               // the bars that never die
diagram.at(0.5)                 // the diagram of the filtration up to 0.5
diagram.triples                 // (degree, birth, death) for each bar; death is Infinity for an essential bar

val loop = diagram.dim(1).longest.get
(loop.birth, loop.death, loop.persistence)
```

### Short bars

Zero-length bars are left out (pass `includeZeroLength = true` to see them). Short bars are usually sampling noise, and
are one call away:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array(Array(0.0, 0.0), Array(0.001, 0.0), Array(1.0, 0.0), Array(0.5, 0.9))
val diagram = Persistence(points)
diagram.longerThan(0.1)         // essential bars, and bars that persist for more than 0.1
diagram.significant()           // longer than 1% of the point cloud's scale, its minimum enclosing radius
diagram.significant(0.1)        // longer than 10% of it
```

`significant()` is what the command line and MATLAB show by default. The same two calls work on any list of bars an
engine returns: `bars.longerThan(0.1)`, `bars.significant()`.

### Representatives

Every bar carries a representative: a cycle (or, from the cohomology engines, a cocycle) that witnesses the class.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(30)(i => Array(math.cos(i * 0.21), math.sin(i * 0.21)))
val loop = Persistence(points).dim(1).longest.get
val cycle = loop.representative     // a chain of edges going around the loop
cycle.cells                         // the edges, each a Simplex of two point indices
cycle.terms                         // the edges with their coefficients
```

### Long computations: the cursor

`Persistence` runs to the end. For a computation that may take days, run an engine yourself: its state is a cursor
that processes the complex in steps, and can be read at any scale while it runs, so a run that is stopped still leaves
its output so far. Running an engine means choosing the coefficient field yourself.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import scala.concurrent.duration.*

given Double is Field = Field.DoubleApproximated(1e-9)   // or: val f = FiniteField(17); import f.given
val points = Array.tabulate(30)(i => Array(math.cos(i * 0.21), math.sin(i * 0.21)))
val state = SimplicialHomologyEngine.persistentHomology(VietorisRips(EuclideanMetricSpace(points), maxDimension = 1))

while !state.advanceFor(10.seconds) do          // true once the whole complex is processed
  println(s"${state.processedCells} / ${state.totalCells} cells")
state.barcodeAt(0.5)                            // the bars of the filtration up to 0.5, wherever the cursor is
val sofar = state.snapshotAt(0.5)               // the same, as a PersistenceDiagram
```

A stream built for degrees `0 .. k` contains cells up to dimension `k + 1`, which is what lets a degree-`k` class die;
an engine run on it directly also reports the degree-`(k + 1)` bars, which are incomplete. `Persistence` leaves them
out for you.

### Chains, and a lab for interactive work

`Simplex(1, 2, 3)` (also written `∆(1, 2, 3)`) is a simplex; its boundary has coefficients in whatever field you
choose:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)
Simplex(1, 2, 3).boundary[Double]    // (∆(2,3), 1.0), (∆(1,3), -1.0), (∆(1,2), 1.0)
```

For interactive work with chains, a *lab* is one import that also fixes a coefficient field and turns on chain
arithmetic: `TDAlab.F2`, `TDAlab.F3`, `TDAlab.F17` and `TDAlab.Reals` are ready-made, `val lab = TDAlab(p); import
lab.{*, given}` makes one for any prime, and `CubicalLab.F17` and friends do the same for cubes.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab.F17.{*, given}

val chain = Fp(2) ⊠ ∆(1, 2) - ∆(2, 3)
println(chain.show)
```
