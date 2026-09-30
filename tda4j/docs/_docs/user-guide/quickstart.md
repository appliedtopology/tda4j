---
layout: main
title: Quick-start: Scala
---


Snippets included via `@:snip` (with a source-file link) are compiled and exercised directly by the test
suite. The rest are illustrative and hand-maintained, not mechanically checked — if you find one has
drifted, trust the source over this page.

### Imports

TDA4j's package is split into subpackages (`algebra`, `cells`, `streams`, `homology`, `alpha`, ...). Bring
in what you need with the `{given, *}` form — a plain `import pkg.*` does **not** bring `given` instances
(coefficient fields, orderings) into scope in Scala 3:

```scala 3
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
```

The rest of this guide assumes these four imports (plus `alpha.{given, *}` where alpha complexes come up).

### Building and taking the boundary of a simplex

```scala 3
// Coefficients need an explicit Field instance in scope -- there is no default one for Double.
// DoubleApproximated treats two coefficients as equal within epsilon, which matters for the
// zero-checks that drive chain reduction.
given Double is Field = Field.DoubleApproximated(1e-9)

val triangle = Simplex(1, 2, 3)      // same as ∆(1, 2, 3)
triangle.boundary[Double]            // Seq((Simplex(2,3), 1.0), (Simplex(1,3), -1.0), (Simplex(1,2), 1.0))
```

### A full Vietoris-Rips persistence computation

@:snip(/src/test/scala/org/appliedtopology/tda4j/APISpec.scala, full-vr-computation)

`TDAContext[VertexT, CoefficientT, FiltrationT]` bundles the naive, reference-grade persistence engine
together with chain-arithmetic operators, so `1.0 ⊠ ∆(1,2) - ∆(2,3)` works directly once `ctx`'s members are
imported. It's a good default for exploration and for anything where you want to query the diagram at
intermediate filtration values or get representative cycles back (`state.diagramAt(f)`/`state.barcodeAt(f)`)
— see "Which persistence engine?" below for when a different engine is worth reaching for instead.

**A default worth knowing**: `EnumeratingCofaceSimplexStream` and the other Vietoris-Rips stream
implementations default `maxFiltrationValue` to the point cloud's own *minimum enclosing radius*, not
unbounded, since nothing past that radius contributes new homology. Pass `Some(Double.PositiveInfinity)`
explicitly if you want the old always-unbounded behavior.
