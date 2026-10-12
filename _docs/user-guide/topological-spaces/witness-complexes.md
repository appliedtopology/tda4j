---
layout: main
title: Witness complexes
---

### Witness complexes

The witness complex of de Silva and Carlsson builds a complex on a small set of **landmarks**, using all the points as
**witnesses** that decide which simplices exist. It is the complex behind most of the JavaPlex tutorials, and the way to
work with a point cloud too large for a Vietoris-Rips complex.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(200)(i => Array(math.cos(i * 0.07), math.sin(i * 0.07)))
val metricSpace = EuclideanMetricSpace(points)
val selection = LandmarkSelector.maxmin(metricSpace, numLandmarks = 20)
val diagram = Persistence(Witness(metricSpace, selection.landmarks, maxDimension = 1))
selection.coveringRadius        // R: every point is within R of a landmark
```

`LandmarkSelector.maxmin` picks landmarks by furthest-point sampling (every point ends up within the covering radius `R`
of a landmark); `LandmarkSelector.random(metricSpace, n, seed)` is cheaper, with no such guarantee. Both work from any
metric space, so from a distance matrix too.

The vertices of the complex are **landmark numbers** `0 until landmarks.size`; `landmarks(i)` is the point that
landmark `i` is. A common choice of `maxFiltrationValue` is `2R`, as in the JavaPlex tutorials.

Two variants:

* **`Witness.Variant.Lazy`** (the default; JavaPlex's `LazyWitnessStream`): a flag complex, so every engine applies,
  including Ripser on the `WitnessMetricSpace`. `nu` (0, 1 or 2, default 2) sets how forgiving a witness is.
* **`Witness.Variant.General`** (JavaPlex's `WitnessStream`): not a flag complex. `maxFiltrationValue` defaults to
  `Infinity`, so keep `maxDimension` small or pass a finite value.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(60)(i => Array(math.cos(i * 0.1), math.sin(i * 0.1)))
val metricSpace = EuclideanMetricSpace(points)
val landmarks = LandmarkSelector.maxmin(metricSpace, numLandmarks = 15).landmarks
val general = Witness(metricSpace, landmarks, maxDimension = 1, variant = Witness.Variant.General, maxFiltrationValue = 2.0)
```

### From MATLAB and the command line

`complex=witness` with `numLandmarks` chooses the landmarks and computes in one call, but does not report `R`. To use
`2R` as the threshold, split it in two: `TDA4j.selectLandmarksFromPoints(points, {'numLandmarks', '50'})` returns the
landmarks (0-based) and `coveringRadius()`, and `TDA4j.computeFromPointsAndLandmarks(points, landmarks, options)`
computes with them; `coveringRadiusFromPoints` gives `R` for landmarks of your own. In MATLAB, index `points` with
`landmarks + 1`, and write the threshold with `sprintf('%.17g', 2 * R)` (MATLAB's `num2str` rounds).

On the command line, `--select-landmarks --num-landmarks 50 --output landmarks.txt` writes the landmarks and prints
`R` and the exact `2R` to pass on; `--landmarks-file landmarks.txt --max-filtration-value <2R>` then computes. See
[MATLAB](../matlab.md) and the [command line](../cli.md).
