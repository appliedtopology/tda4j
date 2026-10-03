---
layout: main
title: Quick-start: Scala
---


Snippets marked with a `Source:` line are copied from a region of a real source file that the test suite
compiles and exercises. The rest are illustrative and hand-maintained, not mechanically checked — if you
find one has drifted, trust the source over this page.

### Imports

TDA4j's package is split into subpackages (`algebra`, `cells`, `streams`, `homology`, `alpha`, ...). Bring
in what you need with the `{given, *}` form — a plain `import pkg.*` does **not** bring `given` instances
(coefficient fields, orderings) into scope in Scala 3:

```scala 3
import org.appliedtopology.tda4j.*
```

The rest of this guide assumes these four imports (plus `alpha.{given, *}` where alpha complexes come up).

### Building and taking the boundary of a simplex

```scala 3
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)

val triangle = Simplex(1, 2, 3)      // same as ∆(1, 2, 3)
triangle.boundary[Double]            // Seq((Simplex(2,3), 1.0), (Simplex(1,3), -1.0), (Simplex(1,2), 1.0))
```

### Chain arithmetic with `TDAlab`

For interactive use, `TDAlab` is a pylab-style entry point: pick a field once, import its members, and compute.

```scala 3
import language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val tdalab = TDAlab(17)          // Z/17; TDAlab(0) uses Double coefficients
import tdalab.{*, given}
val chain = Fp(1) ⊠ ∆(1, 2) - ∆(2, 3)
println(chain.show)
```

### A full Vietoris-Rips persistence computation

```scala 3
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)
val engine = SimplicialHomologyEngine[Int, Double, Double]()

val points: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8))
val metricSpace = EuclideanMetricSpace(points)
val stream = VietorisRips(metricSpace, maxFiltrationValue = Some(2.0))

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
default; from Scala you opt in with `PersistenceFilter` (essential bars are always kept; a threshold of `0` keeps
everything):

```scala 3
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)
val points: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(0.001, 0.0), Array(1.0, 0.0))
val stream = VietorisRips(EuclideanMetricSpace(points), maxFiltrationValue = Some(2.0))
val state = SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(stream)
state.advanceAll()
val bars = state.barcodeAt(Double.PositiveInfinity)

val metricSpace = EuclideanMetricSpace(points)
val scale = Some(metricSpace.minimumEnclosingRadius)

val worthReporting = PersistenceFilter.significant(bars, scale = scale)          // default: 1% of the scale
val everything = PersistenceFilter.significant(bars, minPersistence = Some(0.0))
val aTenthOfIt = PersistenceFilter.significant(bars, fraction = 0.1, scale = scale)
```

**A default worth knowing**: `VietorisRips` (whichever implementation you pick) defaults `maxFiltrationValue` to the point cloud's own *minimum enclosing radius*, not
unbounded, since nothing past that radius contributes new homology. Pass `Some(Double.PositiveInfinity)`
explicitly if you want the old always-unbounded behavior.
