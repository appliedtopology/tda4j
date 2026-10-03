---
layout: main
title: Circular and toroidal coordinates
---

A barcode tells you *that* your data has a loop. Sometimes you want to know *where on the loop each point is*: the phase of a
periodic signal, the direction a camera was facing, the angle of a joint. A **circular coordinate** gives every data point an
angle in `[0, 1)` (a fraction of a full turn) that varies continuously around the loop, computed from the persistent homology
alone: you never tell the algorithm what the loop is. When the data has two independent loops at once, as for a
torus, **toroidal coordinates** give every point a pair of angles.

Both build on [Finding a loop](find-a-loop.md). The method is that of de Silva, Morozov and Vejdemo-Johansson for the circle, and
of Scoccola, Gakhar, Bush, Schonsheck, Rask, Zhou and Perea for the torus.

## A circle

We already know the answer for [`data/noisy-circle.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/noisy-circle.csv): the points were sampled from a circle, so each
has a true angle, which we can compute from its coordinates and use to check what the algorithm recovered. First, ask for the loops
in the data (the algorithm works with cohomology rather than homology, which is why it has its own entry point):

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab.F2.{*, given}  // a prebuilt lab: coefficients in Z/2

val circlePoints = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val circle = EuclideanMetricSpace(circlePoints)
val circleBars = CircularCoordinates.h1Bars(circle)     // (birth, death), longest-lived first
circleBars.take(2)    // (0.595, 1.707), then (1.950, 1.950): one real loop, then a bar with no length at all
```

Now choose a scale `r` at which the loop you want is alive, that is, between its birth and its death. The middle of the bar is the
natural choice, and the computation takes the loop's number in the list above:

```scala sc:nocompile
val (birth, death) = circleBars.head
val circleCoordinate = CircularCoordinates.compute(circle, r = (birth + death) / 2, cocycleIndex = 0)
circleCoordinate.theta.size      // 60: an angle for every point
circleCoordinate.theta(0)        // the angle of point 0 (row 0 of the file), in [0, 1)
```

How good is it? An angle is only defined up to where you start counting (a rotation) and which way you go round (a reflection),
so to compare with the true angles we allow the best of those two choices and then average the remaining difference, as a
fraction of a full turn (0 is a perfect match, 0.5 is as wrong as possible, 0.25 is what unrelated angles would give):

```scala sc:nocompile
def circularDistance(a: Double, b: Double): Double =
  val d = math.abs(a - b) % 1.0
  math.min(d, 1.0 - d)

def alignmentError(theta: Map[Int, Double], truth: Int => Double): Double =
  (for
    sign <- Seq(1.0, -1.0)
    offset <- (0 until 200).map(_ / 200.0)
  yield theta.map((i, t) => circularDistance(((sign * t + offset) % 1.0 + 1.0) % 1.0, truth(i))).sum / theta.size).min

def turn(y: Double, x: Double): Double = (math.atan2(y, x) / (2 * math.Pi) + 1.0) % 1.0

alignmentError(circleCoordinate.theta, i => turn(circlePoints(i)(1), circlePoints(i)(0)))    // 0.064
```

An average error of 0.064 of a turn, about 23 degrees. That is not nothing, and it is about what you should expect: the "true" angle
we compare with ignores the noise (each point is 0.05 off the circle in both directions), and the coordinate is the smoothest map
that goes once around the loop, so it spreads the points evenly where the sampling was uneven. What matters is that nothing but
the point cloud went in.

Two practical notes. The algorithm works over a field of **odd prime** characteristic (47 by default), because with 2 it can
mistake a twisted loop for a real one; it checks the result really lifts to a circle and tells you if not (see the
[user guide](../user-guide/circular-coordinates.md)). And only the points in the same connected piece as the loop get a coordinate;
here that is all 60, and `theta.size` tells you in general.

## A torus

The data in [`data/flat-torus.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/flat-torus.csv) is 120 points on the flat torus in four dimensions, `(cos a, sin a, cos b, sin b)`
with the two angles `a` and `b` independent and random. The torus has two independent loops, one for each angle, so we expect two
long bars in dimension 1:

```scala sc:nocompile
val torusPoints = CSV.readPointCloud("_docs/tutorials/data/flat-torus.csv")
val torus = EuclideanMetricSpace(torusPoints)
val torusBars = CircularCoordinates.h1Bars(torus, maxFiltrationValue = Some(1.8))
torusBars.take(4)
// (0.680, 1.751), (0.699, 1.755), (0.771, 1.419), (0.771, 1.407)
```

Two bars of length about 1.07, then a drop to 0.65: the two loops. (We stopped the complex at 1.8, just past where they die, to
save time.) Toroidal coordinates combine several loops that are alive *at the same time*, so we pick a scale at which both are, between the later of the
two births and the earlier of the two deaths, and ask for both:

```scala sc:nocompile
val r = (torusBars.take(2).map(_._1).max + torusBars.take(2).map(_._2).min) / 2     // 1.225
val coordinates =
  CircularCoordinates.computeToroidal(torus, r, cocycleIndices = Seq(0, 1), maxFiltrationValue = Some(1.8))
coordinates.theta.map(_.size)      // Vector(120, 120): two angles for each of the 120 points
```

`coordinates.theta(0)` and `coordinates.theta(1)` are two circle coordinates. A pair of loops can be described in many equivalent
ways (go round the first, or round the first twice and the second once, ...), so the algorithm tidies the pair into the most
"orthogonal" description (an integer change of basis it reports in `coordinates.basisChange`). Because we know the true angles `a`
and `b`, we can ask whether each coordinate follows one of them:

```scala sc:nocompile
coordinates.theta.map { theta =>
  val first = alignmentError(theta, i => turn(torusPoints(i)(1), torusPoints(i)(0)))
  val second = alignmentError(theta, i => turn(torusPoints(i)(3), torusPoints(i)(2)))
  math.min(first, second)
}
// Vector(0.131, 0.148)
```

Each recovered coordinate follows one true angle with an average error of 0.13 and 0.15 of a turn, against 0.25 for unrelated angles.
That is clearly there but rough, and honest data would be rougher: 120 points is a thin sample of a two-dimensional surface in four
dimensions, and the loops are only barely separated from the noise bars. More points, or less noise, sharpen it.

## The whole script

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab.F2.{*, given}  // a prebuilt lab: coefficients in Z/2

def circularDistance(a: Double, b: Double): Double =
  val d = math.abs(a - b) % 1.0
  math.min(d, 1.0 - d)

def alignmentError(theta: Map[Int, Double], truth: Int => Double): Double =
  (for
    sign <- Seq(1.0, -1.0)
    offset <- (0 until 200).map(_ / 200.0)
  yield theta.map((i, t) => circularDistance(((sign * t + offset) % 1.0 + 1.0) % 1.0, truth(i))).sum / theta.size).min

def turn(y: Double, x: Double): Double = (math.atan2(y, x) / (2 * math.Pi) + 1.0) % 1.0

// A circle
val circlePoints = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val circle = EuclideanMetricSpace(circlePoints)
val circleBars = CircularCoordinates.h1Bars(circle)
val (birth, death) = circleBars.head
val circleCoordinate = CircularCoordinates.compute(circle, r = (birth + death) / 2, cocycleIndex = 0)
val circleError = alignmentError(circleCoordinate.theta, i => turn(circlePoints(i)(1), circlePoints(i)(0)))

// A torus
val torusPoints = CSV.readPointCloud("_docs/tutorials/data/flat-torus.csv")
val torus = EuclideanMetricSpace(torusPoints)
val torusBars = CircularCoordinates.h1Bars(torus, maxFiltrationValue = Some(1.8))
val r = (torusBars.take(2).map(_._1).max + torusBars.take(2).map(_._2).min) / 2
val coordinates =
  CircularCoordinates.computeToroidal(torus, r, cocycleIndices = Seq(0, 1), maxFiltrationValue = Some(1.8))
val torusErrors = coordinates.theta.map { theta =>
  val first = alignmentError(theta, i => turn(torusPoints(i)(1), torusPoints(i)(0)))
  val second = alignmentError(theta, i => turn(torusPoints(i)(3), torusPoints(i)(2)))
  math.min(first, second)
}
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');   % from the repository root, after sbt assembly
import org.appliedtopology.tda4j.matlab.*;

% A circle
points = readmatrix('_docs/tutorials/data/noisy-circle.csv');
bars = TDA4j.h1Bars(points);            % one row [birth death] per H1 class, most persistent first
bars(1:2, :)                            % [0.595 1.707] then [1.950 1.950]
r = (bars(1,1) + bars(1,2)) / 2;
coordinate = TDA4j.circularCoordinates(points, r);       % the most persistent class, over the field with 47 elements
theta = coordinate.theta();             % one value in [0,1) per point: a fraction of a turn

truth = mod(atan2(points(:,2), points(:,1)) / (2*pi), 1);
scatter(truth, theta);                  % a diagonal line, up to the sign and the offset the coordinate is free to choose

% A torus
torusPoints = readmatrix('_docs/tutorials/data/flat-torus.csv');
torusBars = TDA4j.h1Bars(torusPoints);  % runs on the whole complex; this entry point has no maxFiltrationValue
torusBars(1:4, :)                       % two long bars, then a drop
r = (max(torusBars(1:2,1)) + min(torusBars(1:2,2))) / 2;     % a scale where both long bars are alive
coordinates = TDA4j.toroidalCoordinates(torusPoints, r, int32([0 1]));   % classes 0 and 1 (counted from 0)
theta1 = coordinates.theta(0);          % one value per point for each of the two circle coordinates
theta2 = coordinates.theta(1);
plot(theta1, theta2, '.');
```

</div>
</div>

`h1Bars` returns the bars most persistent first, and `circularCoordinates`/`toroidalCoordinates` take the class numbers counted from 0, like every Java index. Unlike the Scala version, these MATLAB entry points cannot cap the complex with `maxFiltrationValue`, so the torus takes noticeably longer. `MatlabTabsSpec` makes these same calls from Scala and checks every number quoted in the MATLAB tab; MATLAB itself is not run by the test suite.
