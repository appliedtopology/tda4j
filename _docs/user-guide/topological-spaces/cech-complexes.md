### Cech complexes

```scala 3
val cechStream = CechCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(2.0))
val homology = SimplicialHomologyContext[Int, Double, Double]().persistentHomology(cechStream)
```

`maxFiltrationValue` here is a Cech **radius**, not a Vietoris-Rips diameter — the two aren't
interchangeable units. Only the naive engine (`SimplicialHomologyContext`/`CellularHomologyContext`) is used
for Cech complexes; the packed Ripser engine's optimizations don't carry over (see the
[Developer's Guide](../../developers-guide/architecture.md)).
