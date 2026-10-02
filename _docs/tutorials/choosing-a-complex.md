---
layout: main
title: Choosing a complex
---

[Finding a loop](find-a-loop.md) used the Vietoris-Rips complex because it is the default. TDA4j offers several other ways to
turn a point cloud into a filtered complex, and they trade off size, exactness and what they need to know about your data. This
tutorial runs the *same* data, [`data/noisy-circle.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/noisy-circle.csv), through five of them and compares what comes out.

Every complex below is built the same way, an object that takes your points and a `maxDimension` (the highest homology
dimension you want) and returns a stream of simplices that any engine can read. So one small function is enough to compare them:

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = TDAlab(2)
import lab.{*, given}

val points = io.CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val metricSpace = streams.EuclideanMetricSpace(points)
val engine = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]()

def summarize(stream: streams.LevelwiseSimplexStream[Int, Double]): (Int, (Double, Double), Int) =
  val size = stream.iterator.size                    // how many simplices the engine will have to process
  val bars = engine.persistentHomology(stream).diagramAt(Double.PositiveInfinity)
  val loop = bars.filter(_._1 == 1).maxBy((_, birth, death) => death - birth)   // the longest-lived loop
  (size, (loop._2, loop._3), bars.count((dim, _, death) => dim == 0 && death.isInfinite))   // and the number of components
```

## The five complexes

```scala sc:nocompile
val vr       = summarize(streams.VietorisRips(metricSpace, maxDimension = 1))
val cech     = summarize(streams.Cech(metricSpace, maxDimension = 1))
val delaunay = summarize(alpha.AlphaShapes(points.toSeq))
val sparse   = summarize(streams.SparseRips(metricSpace, epsilon = 0.5, maxDimension = 1))
val witness  = summarize(streams.Witness(metricSpace, streams.LandmarkSelector.maxmin(metricSpace, 15).landmarks, maxDimension = 1))
```

| complex | simplices | the loop (birth, death) |
|---|---|---|
| Vietoris-Rips | 24,711 | (0.595, 1.707) |
| Čech | 36,050 | (0.297, 0.935) |
| alpha | 325 | (0.297, 0.935) |
| sparse Rips (ε = 0.5) | 1,730 | (0.708, 1.789) |
| witness (15 landmarks) | 441 | (0.278, 0.742) |

All five find the loop, and all five find exactly one connected component. What differs is the cost and the units.

### Vietoris-Rips: the default

The Vietoris-Rips complex joins any group of points whose *pairwise distances* are all at most `r`. It needs only distances,
not coordinates, so it works on any distance matrix, and it has the fastest engines (the Ripser-style engine behind the command
line and MATLAB defaults is specific to it). Its drawback is size: 60 points already give 24,711 simplices, and the count grows
quickly with the number of points and the dimension you ask for.

### Čech: the geometrically exact one

The Čech complex at scale `r` joins any group of points whose balls of radius `r` have a common point. It is the complex the
"nerve theorem" speaks about directly: it has the same shape as the union of the balls. It also needs coordinates (it finds
the smallest ball around each group), and it is the largest of the five (36,050 simplices here).

Notice the units. Čech scale is a *radius*, Vietoris-Rips scale is a *diameter*, so the two loops are born at scales that differ
by a factor of two (0.595 against 0.297) and die at different places, √3 = 1.73 for Rips against 1 for Čech. Bars from different
complexes must not be compared by their endpoints, only by their shape: here, one clear loop, far longer-lived than anything else.

### Alpha: Čech without the bulk

The alpha complex is a subcomplex of the Delaunay triangulation of the points, chosen so that at each scale it has the same shape
as the union of balls, which is to say the same shape as the Čech complex. So the two give **identical bars**, the same loop to
six digits, from 325 simplices instead of 36,050. When you can use it, that is a hundredfold saving for nothing.

You can use it when your points have coordinates in a low-dimensional space (the Delaunay triangulation is what limits it:
fine in two or three dimensions, rapidly harder beyond). The `fast-alpha` engine, described in the
[user guide](../user-guide/homology-computation/fast-alpha-complexes.md), goes further by skipping the generic reduction.

### Sparse Rips: Rips for many points

Sparse Rips (Cavanna, Jahanseir and Sheehy) keeps only a well-spread subset of the points at each scale, which makes the complex
linear in the number of points. The price is approximation: bars are guaranteed correct only up to a factor `1 + ε`. Here the loop
is born at 0.708 instead of 0.595 and dies at 1.789 instead of 1.707, so it is clearly the same loop, with the endpoints shifted
by the approximation. Smaller `ε` is closer to plain Rips and larger complexes. Use it when plain Vietoris-Rips is too big.

### Witness: a few landmarks, all the points

The witness complex first picks a small set of landmark points (here 15, chosen by the furthest-point rule so that they cover the
cloud evenly) and builds the complex on them only, using the other points as witnesses that decide which simplices exist. That
makes it tiny (441 simplices) and very cheap, at the cost of thinking about landmarks. The loop is still there, at its own
scale. See the [user guide](../user-guide/topological-spaces/sparse-vietoris-rips.md) for choosing landmarks and the two witness
variants.

## Which one?

* **Start with Vietoris-Rips.** It needs the least and is the fastest to compute with.
* **Low-dimensional coordinates, want the exact geometry:** alpha. It is the same answer as Čech at a hundredth of the size.
* **Too many points for Vietoris-Rips:** sparse Rips if you want to keep all the points and accept an approximation, witness if you
  are happy to work with landmarks.
* **Noisy data, or outliers:** none of these is robust; see [the next tutorial](noise-and-outliers.md).

## The whole script

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = TDAlab(2)
import lab.{*, given}

val points = io.CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val metricSpace = streams.EuclideanMetricSpace(points)
val engine = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]()

def summarize(stream: streams.LevelwiseSimplexStream[Int, Double]): (Int, (Double, Double), Int) =
  val size = stream.iterator.size
  val bars = engine.persistentHomology(stream).diagramAt(Double.PositiveInfinity)
  val loop = bars.filter(_._1 == 1).maxBy((_, birth, death) => death - birth)
  (size, (loop._2, loop._3), bars.count((dim, _, death) => dim == 0 && death.isInfinite))   // and the number of components

val landmarks = streams.LandmarkSelector.maxmin(metricSpace, 15).landmarks
val results = Map(
  "vietoris-rips" -> summarize(streams.VietorisRips(metricSpace, maxDimension = 1)),
  "cech" -> summarize(streams.Cech(metricSpace, maxDimension = 1)),
  "alpha" -> summarize(alpha.AlphaShapes(points.toSeq)),
  "sparse-rips" -> summarize(streams.SparseRips(metricSpace, epsilon = 0.5, maxDimension = 1)),
  "witness" -> summarize(streams.Witness(metricSpace, landmarks, maxDimension = 1))
)
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');   % from the repository root, after sbt assembly
import org.appliedtopology.tda4j.matlab.*;

points = readmatrix('_docs/tutorials/data/noisy-circle.csv');

% Each row: the value of the 'complex' option and any extra options it needs
settings = {
    'vr',          {}
    'cech',        {}
    'alpha',       {}
    'sparse-rips', {'sparseEpsilon', '0.5'}
    'witness',     {'numLandmarks', '15'}};

for s = 1:size(settings, 1)
    options = [{'maxDimension', '1', 'complex', settings{s,1}}, settings{s,2}];
    result = TDA4j.computeFromPoints(points, options);
    bars = result.toArray();
    loops = bars(bars(:,1) == 1, :);
    [~, longest] = max(loops(:,3) - loops(:,2));
    fprintf('%-12s %6d simplices, loop [%.3f, %.3f)\n', settings{s,1}, result.numCells(), loops(longest,2), loops(longest,3));
end
```

</div>
</div>

In MATLAB a complex is a value of the `complex` option rather than a function, and `numCells()` is the number of simplices the engine had to process. `MatlabTabsSpec` makes these same calls from Scala and checks every number quoted in the MATLAB tab; MATLAB itself is not run by the test suite.
