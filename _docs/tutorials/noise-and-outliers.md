---
layout: main
title: Noise and outliers
---

Real data has stray points. A sensor glitches, a sample lands in the wrong place, a background you did not mean to include leaks
in. The Vietoris-Rips complex treats every point as equally trustworthy, so a few strays can plant spurious holes in the barcode.
This tutorial shows the problem and one standard remedy, the **distance-to-measure** (DTM) weighting, which makes the complex
trust points in dense regions more than points on their own. If you have not read [Finding a loop](find-a-loop.md) yet, start there.

**The data.** [`data/circle-with-outliers.csv`](https://github.com/appliedtopology/tda4j/blob/scala/_docs/tutorials/data/circle-with-outliers.csv) has 95 points: a noisy unit circle of 70 points
(the first 70 rows), then 25 outliers scattered uniformly over the square `[-2, 2] × [-2, 2]`, with nothing to do with the circle.
The true answer is still one loop.

## What Vietoris-Rips makes of it

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = CSV.readPointCloud("_docs/tutorials/data/circle-with-outliers.csv")
val metricSpace = EuclideanMetricSpace(points)

val vr = Persistence(points)
val vrLoops = vr.dim(1).longest(2)            // the two longest-lived loops
vrLoops.map(_.persistence)                     // List(0.901, 0.127)

vrLoops.map { loop =>
  val pointsOnCycle = loop.representative.cells.flatMap(_.toList).distinct
  (pointsOnCycle.size, pointsOnCycle.count(_ >= 70))   // rows 70 and up are the outliers
}                                              // List((25, 8), (7, 5))
```

The circle is found: one loop persists for 0.90. But there is a second loop of persistence 0.13, about 14% of the first. It is
not on the circle. The representative cycle of the long loop passes through 25 points, 17 of them on the ring (the cycle
takes a few shortcuts through strays that sit near it); that of the short one passes through just 7 points, 5 of which are
outliers.
It is a hole between strays, which exists only because they happen to be arranged around an empty patch. On this data you can
tell the two apart by eye. With more strays, or a messier circle, the
second bar grows, and nothing in the barcode itself says which loop is the real one.

## Weighting by density

The distance-to-measure of a point is, roughly, the typical distance from it to its `k` nearest neighbours. It is small where the
data is dense and large where it is sparse. Compute it for every point and look at where the outliers fall:

```scala sc:nocompile
val weights = DistanceToMeasure(metricSpace, 8, 2.0)   // k = 8 neighbours, exponent 2
val (ring, outliers) = weights.splitAt(70)
ring.sum / ring.size          // 0.176: the typical weight of a ring point
outliers.sum / outliers.size  // 0.543: more than three times as much for an outlier
```

The DTM-weighted Rips complex uses those weights: a point does not join the complex until the scale has reached its own weight, so
sparse points enter late, after the dense structure has formed. The loop through the ring is born early and dies when the
complex fills the ring, long before the outliers matter:

```scala sc:nocompile
val dtm = Persistence(DtmRips.fromNeighbours(metricSpace, k = 8, maxDimension = 1, p = 1.0))
dtm.dim(1).longest(2).map(_.persistence)       // List(0.815, 0.005)
```

The circle's loop persists for 0.81. The runner-up has dropped from 0.13 to 0.005, a margin of over a hundred to one, against
seven to one before. The outliers are still in the data, they just cannot make a hole on their own any more.

## Choosing `k`

`k` is the one parameter that matters. It should be larger than the size of any cluster you want to treat as noise (so that a few
outliers sitting near each other still look sparse) and small compared with the number of points in the features you want to
keep (so that those still look dense). Here 8 sits between: the outliers are mostly isolated, and the ring has dozens of
neighbours. Try other values on your own data and watch how the runner-up bar moves. The cost of the extra robustness is that
DTM bars are measured in different units (they depend on the weights) from plain Rips, so, as in
[Choosing a complex](choosing-a-complex.md), compare shapes, not endpoints.

The exponent `p` of the weighted complex (1 or 2) selects between two constructions from the literature; `p = 1` is the default
and the easier one to compute with. The [user guide](../user-guide/index.md) lists the full set of options, including the
command-line and MATLAB spelling (`complex=dtm-rips`, `dtmK`).

## The whole script

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = CSV.readPointCloud("_docs/tutorials/data/circle-with-outliers.csv")
val metricSpace = EuclideanMetricSpace(points)

val vr = Persistence(points)
val vrLoops = vr.dim(1).longest(2)
val cycles = vrLoops.map { loop =>
  val pointsOnCycle = loop.representative.cells.flatMap(_.toList).distinct
  (pointsOnCycle.size, pointsOnCycle.count(_ >= 70))
}

val weights = DistanceToMeasure(metricSpace, 8, 2.0)
val (ring, outliers) = weights.splitAt(70)

val dtm = Persistence(DtmRips.fromNeighbours(metricSpace, k = 8, maxDimension = 1, p = 1.0))
val dtmLoops = dtm.dim(1).longest(2)
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');   % from the repository root, after sbt assembly
import org.appliedtopology.tda4j.matlab.*;

points = readmatrix('_docs/tutorials/data/circle-with-outliers.csv');

% The two longest-lived loops of a barcode, as lengths (death - birth)
loopLengths = @(bars) sort(bars(bars(:,1) == 1, 3) - bars(bars(:,1) == 1, 2), 'descend');

vr  = TDA4j.computeFromPoints(points);
dtm = TDA4j.computeFromPoints(points, {'maxDimension', '1', 'complex', 'dtm-rips', 'dtmK', '8', 'dtmP', '1.0'});

vrLengths = loopLengths(vr.toArrayUnfiltered());
dtmLengths = loopLengths(dtm.toArrayUnfiltered());
vrLengths(1:2)'                       % 0.901  0.127
dtmLengths(1:2)'                      % 0.815  0.005

% Which points make up the two longest Vietoris-Rips loops? Points 70 onwards (0-based) are the outliers
bars = vr.toArray();
loopRows = find(bars(:,1) == 1);
[~, order] = sort(bars(loopRows,3) - bars(loopRows,2), 'descend');
for row = loopRows(order(1:2))'
    onCycle = unique(double(vr.cycleVertices(row - 1)));   % Java index: row - 1
    fprintf('%d points, %d of them outliers\n', numel(onCycle), sum(onCycle >= 70));   % 25 and 8, then 7 and 5
end
```

</div>
</div>

The MATLAB entry point takes `dtmK` and `dtmP` for the DTM-weighted Rips complex; it does not expose the weights themselves, so the average-weight comparison above has no MATLAB counterpart. `MatlabTabsSpec` makes these same calls from Scala and checks every number quoted in the MATLAB tab; MATLAB itself is not run by the test suite.
