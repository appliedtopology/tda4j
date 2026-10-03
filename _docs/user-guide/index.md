---
layout: main
title: User Guide for TDA4j
---

TDA4j implements persistent homology and related techniques from computational and applied topology. This
guide assumes you know what a simplicial complex, a filtration, and a persistence barcode are — it does not
assume you know Scala. If you want to understand *why* the library is built the way it is, or you're
planning to write new code against it, see the [Developer's Guide](../developers-guide/index.md)
instead; this page is about getting things done as a caller.

### Alpha complexes

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8), Array(0.2, 0.5))
val shape = AlphaShapes(points, AlphaBackend.Helix)            // or AlphaBackend.DQP: the alpha complex as a stream
val diagram = Persistence(points, complex = AlphaShapes)       // or straight to its persistence diagram
```

`AlphaShapes(points)` with no backend, or `AlphaBackend.Default`, always resolves to `AlphaBackend.Helix` — ask for
`AlphaBackend.DQP` explicitly if you want it. See "Which alpha-complex backend?" below for the tradeoffs.




### Flag-complex edge collapse

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)
val points: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8), Array(0.2, 0.5))
val metricSpace = EuclideanMetricSpace(points)

val collapsed = EdgeCollapse.collapse(metricSpace) // a FiniteMetricSpace[Int], drop-in for any VR-consuming stream
val stream = VietorisRips(collapsed, maxDimension = 2)
val homology = SimplicialHomologyEngine.persistentHomology(stream)   // every type argument inferred
```

Unlike Sheehy's construction above, this is not an approximation: Boissonnat-Pritam/Glisse-Pritam edge
collapse reduces a Vietoris-Rips filtration's own 1-skeleton to a smaller weighted graph with the EXACT same
persistent homology at every filtration level, before anything is built on top of it. `EdgeCollapse.collapse`
returns an ordinary `FiniteMetricSpace[Int]` (`EdgeCollapsedMetricSpace`), so it plugs into
`VietorisRips` — and every engine that consumes them — with no
other code changes; representatives transfer for free (the collapsed complex is a literal subcomplex of the
original at every level). Measured 73-76% of edges removed and a 43-47x reduction-phase speedup on random
point clouds — see the [Developer's Guide](../developers-guide/architecture.md)'s "Flag-complex edge collapse"
section for the construction-vs-reduction breakdown and why they differ so much. `matlab.TDA4j`'s
`edgeCollapse=true` option (and the CLI's `--edge-collapse`) apply this automatically for `complex=vr` — see
"Calling from MATLAB or Java" below.

## Performance: opt-in parallelism

A few of the more expensive per-cell computations can run on the JVM's common thread pool, opt-in via a
constructor flag (`AlphaDQPSettings.parallel`, `CubicalGridStream.parallelFiltrationValue`,
`Cech(..., parallelFiltrationValue = true)`), all defaulting to `false`, with deterministic output
either way. Worth turning on for a large alpha-complex computation (each vertex's own QP solve is genuinely
expensive — measured 2-4x speedup at a few hundred points and above); cubical images and Cech complexes see
a smaller win (a few percent) since the per-cell cost there is lighter.

## Tutorials

[Tutorials](../tutorials/index.md) — currently a placeholder; porting Henry Adams' JavaPlex tutorials
to TDA4j is tracked there as future work, not yet done. The witness-complex construction those tutorials
lean on heavily is now implemented (`LandmarkSelector`/`WitnessGeometry`/`Witness`, see "Witness complexes" above) — a building block for that port, not the port
itself.
