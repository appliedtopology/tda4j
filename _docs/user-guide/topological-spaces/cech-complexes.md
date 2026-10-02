---
layout: main
---

### Cech complexes

```scala 3
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

given Double is Field = Field.DoubleApproximated(1e-9)

val metricSpace = EuclideanMetricSpace(Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8), Array(0.2, 0.5)))
val cechStream = CechCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(2.0))
val homology = SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(cechStream)
```

`maxFiltrationValue` here is a Cech **radius**, not a Vietoris-Rips diameter — the two aren't
interchangeable units. Only the naive engine (`SimplicialHomologyEngine`/`CellularHomologyEngine`) is used
for Cech complexes; the packed Ripser engine's optimizations don't carry over (see the
[Developer's Guide](../../developers-guide/architecture.md)).
