---
layout: main
---

### Cech complexes

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8), Array(0.2, 0.5))
val diagram = Persistence(points, complex = Cech, maxFiltrationValue = 2.0)

// or the stream itself, for an engine of your choice:
given Double is Field = Field.DoubleApproximated(1e-9)
val cechStream = Cech(EuclideanMetricSpace(points), maxFiltrationValue = 2.0)
val homology = SimplicialHomologyEngine.persistentHomology(cechStream)
```

`maxFiltrationValue` here is a Cech **radius**, not a Vietoris-Rips diameter — the two aren't
interchangeable units. Only the naive engine (`SimplicialHomologyEngine`/`CellularHomologyEngine`) is used
for Cech complexes; the packed Ripser engine's optimizations don't carry over (see the
[Developer's Guide](../../developers-guide/architecture.md)).
