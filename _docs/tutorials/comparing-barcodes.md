---
layout: main
title: Comparing barcodes
---

One barcode describes one data set. Often the real question involves two: *are these two shapes the same? Which of these
three is the odd one out? Did the topology change between yesterday's scan and today's?* To answer, you need a way to measure
how far apart two barcodes are, and a way to turn a barcode into a vector of numbers that a classifier can use. This tutorial does
both, on three small point clouds: two independent samples of the same circle, and a figure eight.

**The data.** [`data/noisy-circle.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/noisy-circle.csv) and [`data/noisy-circle-b.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/noisy-circle-b.csv) are two samples
of 60 points from the unit circle with different random angles and noise. [`data/figure-eight.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/figure-eight.csv) is 70 points
from a figure eight, which has two loops. Call them A, B and 8. A and B should be close, and 8 should be far from both.

## One diagram per cloud

We compare the loops, so for each cloud we keep the dimension-1 bars that survive the default 1% threshold (see
[Finding a loop](find-a-loop.md); this also keeps the matching below cheap, since the noise bars never enter):

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = TDAlab(2)
import lab.{*, given}

val engine = SimplicialHomologyEngine[Int, CoefficientT, Double]()

def loopDiagram(file: String) =
  val metricSpace = CSV.readEuclideanMetricSpace(s"_docs/tutorials/data/$file")
  val stream = VietorisRips(metricSpace, maxDimension = 1)
  val bars = engine.persistentHomology(stream).barcodeAt(Double.PositiveInfinity)
  PersistenceFilter.significant(bars.filter(_.dim == 1), scale = Some(metricSpace.minimumEnclosingRadius))

val circleA = loopDiagram("noisy-circle.csv")      // one loop, (0.595, 1.707)
val circleB = loopDiagram("noisy-circle-b.csv")    // one loop, (0.631, 1.667)
val eight = loopDiagram("figure-eight.csv")        // two loops, (0.412, 0.704) and (0.375, 0.698)
```

Already you can read the difference by eye: the circles have one long-lived loop each, the figure eight has two shorter-lived
ones. (The figure eight's loops are shorter-lived because each is a smaller circle, with less room to be a hole in.) But "by eye"
does not scale to a thousand data sets, so we want numbers.

## Distances between diagrams

Think of each bar as a point in the plane, `(birth, death)`. Two diagrams are close when you can move the points of one onto the
points of the other without moving any of them far, where a point is also allowed to be moved onto the diagonal `birth = death`,
that is, to disappear, which is how a short-lived bar gets matched away. The two usual ways of making "moving far" precise:

* The **bottleneck distance** is the cost of the *worst* single move, in the best matching. It asks "what is the biggest
  difference between the two shapes' features?"
* The **Wasserstein distance** adds up the costs of all the moves (here the first-order version, a plain sum). It asks "how much
  do the shapes differ in total?"

```scala sc:nocompile
val pairs = List("A-B" -> (circleA, circleB), "A-8" -> (circleA, eight), "B-8" -> (circleB, eight))

val bottleneck = pairs.map((name, ds) => name -> BarcodeDistance.bottleneckDistance(ds._1, ds._2)).toMap
val wasserstein = pairs.map((name, ds) => name -> BarcodeDistance.wassersteinDistance(ds._1, ds._2)).toMap
```

| pair | bottleneck | Wasserstein |
|---|---|---|
| A, B (two circles) | 0.040 | 0.040 |
| A, 8 | 0.556 | 0.864 |
| B, 8 | 0.518 | 0.826 |

The two samples of the same circle are 0.04 apart, a small perturbation of the feature (the loop is born at 0.595 in one and 0.631
in the other, and dies at 1.707 and 1.667: noise moved the endpoints by about that much). Each is more than ten times further from
the figure eight. That is the property that makes these distances useful: they are **stable**, so noise moves a diagram a
little, and a change in shape moves it a lot.

The bottleneck and Wasserstein distances coincide for A and B because a single loop is matched with a single loop, so the worst move
is the only move. They differ against the figure eight: the circle's one loop is matched with one of the eight's loops, and
the eight's other loop has to be moved onto the diagonal, which costs extra, and only the Wasserstein distance counts it.

Both functions take two diagrams of **one dimension each** (that is why we filtered to dimension 1), and `bottleneckDistanceByDimension`
and `wassersteinDistanceByDimension` compare every dimension at once if you want that. They work on diagrams from the same
construction on the same scale: do not compare a Vietoris-Rips diagram with a Čech one (see [Choosing a complex](choosing-a-complex.md)).

## Barcodes as vectors

Distances let you ask "is this one closer to A or to 8?". Many tools (a classifier, a clustering algorithm, a regression) instead
want each data set to be a vector of numbers. Two standard ways of turning a barcode into one are in `Vectorization`:

* a **persistence landscape**: each bar becomes a tent, and the landscape lists the tallest, second-tallest, ... tent at each
  point of a grid. Here: three levels, on a grid of 100 points between 0 and 2.
* a **persistence image**: the bars, as points `(birth, persistence)`, are blurred with a Gaussian of width `sigma` and weighted
  by how long they live, then counted in a grid. Here: a 40 by 40 grid on `[0, 2] × [0, 2]`, blur `0.1`, and bars longer than 1
  counted at full weight.

```scala sc:nocompile
val landscapes = Map(
  "A" -> Vectorization.landscape(circleA, numLevels = 3, tMin = 0.0, tMax = 2.0, resolution = 100),
  "B" -> Vectorization.landscape(circleB, numLevels = 3, tMin = 0.0, tMax = 2.0, resolution = 100),
  "8" -> Vectorization.landscape(eight, numLevels = 3, tMin = 0.0, tMax = 2.0, resolution = 100)
)
val images = Map(
  "A" -> Vectorization.persistenceImage(circleA, 0.1, (0.0, 2.0), (0.0, 2.0), 40, 40, Some(1.0)),
  "B" -> Vectorization.persistenceImage(circleB, 0.1, (0.0, 2.0), (0.0, 2.0), 40, 40, Some(1.0)),
  "8" -> Vectorization.persistenceImage(eight, 0.1, (0.0, 2.0), (0.0, 2.0), 40, 40, Some(1.0))
)

def l2(x: Array[Array[Double]], y: Array[Array[Double]]): Double =
  math.sqrt(x.flatten.zip(y.flatten).map((p, q) => (p - q) * (p - q)).sum)
```

Each result is an `Array[Array[Double]]`: flatten it and you have a vector for whatever library you use next. Straight-line
(L2) distances between these vectors tell the same story as the diagram distances:

| pair | landscape | image |
|---|---|---|
| A, B | 0.28 | 0.08 |
| A, 8 | 2.43 | 0.16 |
| B, 8 | 2.20 | 0.16 |

In both vectorizations the two circles are the closest pair. The landscape separates the figure eight by a factor of about
nine, the image by a factor of two: the image blurs, which makes it forgiving of noise and less sharp at telling shapes apart.
Neither is better in general. The bottleneck and Wasserstein distances are the best-behaved mathematically, the vectorizations are
the ones you can feed to a machine-learning pipeline, and you can compute all of them from the same diagrams.

## On the command line

The command line mirrors the distances: `--distance-to saved-barcode.csv` computes the barcode of the input file and, instead of
writing it, prints the bottleneck (or Wasserstein) distance per dimension to a barcode you saved earlier (in CSV, GUDHI or DIPHA
format). Save the barcode of one cloud with `--output`, then compare other clouds against it. See the
[command-line guide](../user-guide/cli.md).

## The whole script

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = TDAlab(2)
import lab.{*, given}

val engine = SimplicialHomologyEngine[Int, CoefficientT, Double]()

def loopDiagram(file: String) =
  val metricSpace = CSV.readEuclideanMetricSpace(s"_docs/tutorials/data/$file")
  val stream = VietorisRips(metricSpace, maxDimension = 1)
  val bars = engine.persistentHomology(stream).barcodeAt(Double.PositiveInfinity)
  PersistenceFilter.significant(bars.filter(_.dim == 1), scale = Some(metricSpace.minimumEnclosingRadius))

val circleA = loopDiagram("noisy-circle.csv")
val circleB = loopDiagram("noisy-circle-b.csv")
val eight = loopDiagram("figure-eight.csv")

val pairs = List("A-B" -> (circleA, circleB), "A-8" -> (circleA, eight), "B-8" -> (circleB, eight))
val bottleneck = pairs.map((name, ds) => name -> BarcodeDistance.bottleneckDistance(ds._1, ds._2)).toMap
val wasserstein = pairs.map((name, ds) => name -> BarcodeDistance.wassersteinDistance(ds._1, ds._2)).toMap

def l2(x: Array[Array[Double]], y: Array[Array[Double]]): Double =
  math.sqrt(x.flatten.zip(y.flatten).map((p, q) => (p - q) * (p - q)).sum)

val landscapes = Map(
  "A" -> Vectorization.landscape(circleA, numLevels = 3, tMin = 0.0, tMax = 2.0, resolution = 100),
  "B" -> Vectorization.landscape(circleB, numLevels = 3, tMin = 0.0, tMax = 2.0, resolution = 100),
  "8" -> Vectorization.landscape(eight, numLevels = 3, tMin = 0.0, tMax = 2.0, resolution = 100)
)
val images = Map(
  "A" -> Vectorization.persistenceImage(circleA, 0.1, (0.0, 2.0), (0.0, 2.0), 40, 40, Some(1.0)),
  "B" -> Vectorization.persistenceImage(circleB, 0.1, (0.0, 2.0), (0.0, 2.0), 40, 40, Some(1.0)),
  "8" -> Vectorization.persistenceImage(eight, 0.1, (0.0, 2.0), (0.0, 2.0), 40, 40, Some(1.0))
)
val landscapeDistance = l2(landscapes("A"), landscapes("8"))
val imageDistance = l2(images("A"), images("8"))
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');   % from the repository root, after sbt assembly
import org.appliedtopology.tda4j.matlab.*;

circleA = TDA4j.computeFromPoints(readmatrix('_docs/tutorials/data/noisy-circle.csv'), {'maxDimension', '1'});
circleB = TDA4j.computeFromPoints(readmatrix('_docs/tutorials/data/noisy-circle-b.csv'), {'maxDimension', '1'});
eight   = TDA4j.computeFromPoints(readmatrix('_docs/tutorials/data/figure-eight.csv'), {'maxDimension', '1'});

% Distances between the dimension-1 bars (the second argument is the dimension)
circleA.bottleneckDistance(circleB, 1)      % 0.040
circleA.bottleneckDistance(eight, 1)        % 0.556
circleB.bottleneckDistance(eight, 1)        % 0.518
circleA.wassersteinDistance(circleB, 1)     % 0.045
circleA.wassersteinDistance(eight, 1)       % 0.864
circleB.wassersteinDistance(eight, 1)       % 0.830

% Landscapes: the first 3 levels, sampled at 100 points of [0, 2]
landscapeA = circleA.landscape(1, 3, 0, 2, 100);
landscapeB = circleB.landscape(1, 3, 0, 2, 100);
landscapeEight = eight.landscape(1, 3, 0, 2, 100);
norm(landscapeA(:) - landscapeB(:))         % 0.279
norm(landscapeA(:) - landscapeEight(:))     % 2.430

% Persistence images: sigma 0.1, birth and persistence both over [0, 2], 40 x 40 pixels, weight capped at 1
imageA = circleA.persistenceImage(1, 0.1, 0, 2, 0, 2, 40, 40, 1.0);
imageB = circleB.persistenceImage(1, 0.1, 0, 2, 0, 2, 40, 40, 1.0);
imageEight = eight.persistenceImage(1, 0.1, 0, 2, 0, 2, 40, 40, 1.0);
norm(imageA(:) - imageB(:))                 % 0.080
norm(imageA(:) - imageEight(:))             % 0.163
```

</div>
</div>

Through the facade, distances, landscapes and images always use the *complete* barcode of each result, including the many tiny bars the Scala script removed first with `PersistenceFilter`. The bottleneck distance does not notice them; the Wasserstein distance, a sum, does, so it comes out slightly larger here (0.045 against 0.040, and 0.830 against 0.826). `MatlabTabsSpec` makes these same calls from Scala and checks every number quoted in the MATLAB tab; MATLAB itself is not run by the test suite.
