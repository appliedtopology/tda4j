---
layout: main
title: Quick-start: Scala
---


Snippets marked with a `Source:` line are copied from a region of a real source file that the test suite
compiles and exercises. The rest are illustrative and hand-maintained, not mechanically checked — if you
find one has drifted, trust the source over this page.

### Imports

Two lines start every file. The first tells Scala that you accept the experimental language features the library is
built with (its typeclasses use them, so every user file needs this line, or the `-experimental` compiler flag); the
second brings in the whole library: complexes, engines, barcodes, file formats. Default instances (a simplex's order
and boundary, `Show` for simplices and chains) are found automatically, with no `given` import:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
```

Simplicial sets and group classifying spaces are an add-on with their own import,
`import org.appliedtopology.tda4j.sset.*` (see [Simplicial sets](topological-spaces/simplicial-sets.md)). For
interactive work, a lab (`import org.appliedtopology.tda4j.TDAlab.F17.{*, given}`) replaces both and also fixes a
coefficient field (below).

### Persistent homology in one call

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8))
val diagram = Persistence(points, maxFiltrationValue = 2.0)   // Vietoris-Rips, degrees 0..1, coefficients F_17
diagram.bettiNumbers                                          // Vector(1, 0): one component, no loop left at 2.0
diagram.dim(0).longest                                        // the essential component, with its representative
```

`Persistence(...)` takes points (an `Array[Array[Double]]`, a `Seq[Seq[Double]]`, ...), a metric space, an
`Image(...)`, or any stream you built yourself, and returns an immutable `PersistenceDiagram`: every bar with its
representative cycle, plus `dim(k)`, `at(f)` (the diagram truncated at `f`), `longest`, `significant()` and
`bettiNumbers`. Named options: `maxDimension` (top homological degree, default 1), `maxFiltrationValue` (a number),
`complex = VietorisRips | Cech | AlphaShapes`, `characteristic` (a prime, default 17 -- deliberately not 2, which hides
signs and odd torsion -- or 0 for real coefficients), `engine`.

### Long computations: the cursor

`Persistence` runs to the end. For a computation that may take days, use an engine directly: its state is a *cursor*
that you can advance in slices and inspect at any time, so a run that dies still leaves you its output so far.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import scala.concurrent.duration.*

given Double is Field = Field.DoubleApproximated(1e-9)
val points = Array.tabulate(30)(i => Array(math.cos(i * 0.21), math.sin(i * 0.21)))
val state = SimplicialHomologyEngine.persistentHomology(VietorisRips(EuclideanMetricSpace(points), maxDimension = 1))

while !state.advanceFor(10.seconds) do          // true once the whole complex is processed
  println(s"${state.processedCells} / ${state.totalCells} cells")
state.diagramAt(0.5)                            // exact at any f, wherever the cursor is
val sofar = state.snapshotAt(0.5)               // an immutable PersistenceDiagram of everything up to 0.5
```

### Building and taking the boundary of a simplex

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)

val triangle = Simplex(1, 2, 3)      // same as ∆(1, 2, 3)
triangle.boundary[Double]            // Seq((Simplex(2,3), 1.0), (Simplex(1,3), -1.0), (Simplex(1,2), 1.0))
```

### Chain arithmetic with a lab

For interactive use, a lab is a pylab-style entry point: one import picks a coefficient field and brings in the library
and chain arithmetic. `TDAlab.F2`, `TDAlab.F3`, `TDAlab.F17` and `TDAlab.Reals` are prebuilt (`val lab = TDAlab(p);
import lab.{*, given}` for any other prime); `CubicalLab.F17` etc. do the same for chains of cubes.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab.F17.{*, given}

val chain = Fp(1) ⊠ ∆(1, 2) - ∆(2, 3)
println(chain.show)
```

### A full Vietoris-Rips persistence computation

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)
val engine = SimplicialHomologyEngine[Int, Double, Double]()

val points: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8))
val metricSpace = EuclideanMetricSpace(points)
val stream = VietorisRips(metricSpace, maxFiltrationValue = 2.0)

val state = engine.persistentHomology(stream)
state.barcodeAt(Double.PositiveInfinity).foreach(println)
```

_Source: `src/test/scala/org/appliedtopology/tda4j/APISpec.scala`, region `full-vr-computation`._

`SimplicialHomologyEngine[VertexT, CoefficientT, FiltrationT]` is the naive, reference-grade persistence engine.
It's a good default for exploration and for anything where you want to query the diagram at
intermediate filtration values or get representative cycles back (`state.diagramAt(f)`/`state.barcodeAt(f)`)
— see "Which persistence engine?" below for when a different engine is worth reaching for instead.

### Dropping short bars

Engines return every bar. The MATLAB facade and the CLI hide bars shorter than 1% of the input's minimum enclosing radius by
default; from Scala, `diagram.significant()` does the same for a `PersistenceDiagram`, and `PersistenceFilter` for a list of
bars (essential bars are always kept; a threshold of `0` keeps everything):

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(0.001, 0.0), Array(1.0, 0.0))
val diagram = Persistence(points, maxFiltrationValue = 2.0)
val worthReporting = diagram.significant()                  // default: 1% of the minimum enclosing radius
val aTenthOfIt = diagram.significant(fraction = 0.1)

val bars = diagram.bars
val everything = PersistenceFilter.significant(bars, minPersistence = 0.0)
```

**A default worth knowing**: `VietorisRips` (whichever implementation you pick) defaults `maxFiltrationValue` to the point cloud's own *minimum enclosing radius*, not
unbounded, since nothing past that radius contributes new homology. Pass `maxFiltrationValue = Double.PositiveInfinity`
explicitly if you want the old always-unbounded behavior.
