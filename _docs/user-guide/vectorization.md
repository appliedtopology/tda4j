---
layout: main
title: Comparing diagrams and turning them into vectors
---

### Distances between diagrams

The bottleneck and Wasserstein distances compare two diagrams of one degree: think of each bar as the point
`(birth, death)` and match the points of one diagram with the points of the other, where a point may also be matched to
the diagonal (disappear). The bottleneck distance is the largest move in the best matching, the Wasserstein distance the
sum of the moves (to the power `order`).

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val circle = Array.tabulate(30)(i => Array(math.cos(i * 0.21), math.sin(i * 0.21)))
val ellipse = circle.map(p => Array(1.2 * p(0), p(1)))
val (a, b) = (Persistence(circle).dim(1), Persistence(ellipse).dim(1))

BarcodeDistance.bottleneckDistance(a.bars, b.bars)
BarcodeDistance.wassersteinDistance(a.bars, b.bars, order = 2.0)
BarcodeDistance.bottleneckDistanceByDimension(Persistence(circle).bars, Persistence(ellipse).bars)   // degree -> distance
```

The ground metric between two points is the `L∞` distance (`groundNorm = BarcodeDistance.GroundNorm.LP(2.0)` for
another), as in Hera and GUDHI. Two diagrams with different numbers of essential bars in a degree are at distance `Infinity`: no
finite matching exists. The distances follow Kerber, Morozov and Nigmetov (2017).

### Diagrams as vectors

For machine learning, a diagram becomes a fixed-size array:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = Array.tabulate(30)(i => Array(math.cos(i * 0.21), math.sin(i * 0.21)))
val loops = Persistence(points).dim(1).bars

// 3 landscape levels, sampled at 100 points of [0, 2]: an array of 3 rows
val landscape = Vectorization.landscape(loops, numLevels = 3, tMin = 0.0, tMax = 2.0, resolution = 100)

// a 20 x 20 persistence image of (birth, persistence) over [0, 2] x [0, 2], blurred with sigma = 0.1
val image = Vectorization.persistenceImage(loops, 0.1, (0.0, 2.0), (0.0, 2.0), 20, 20, weightCap = 1.0)
```

* A **persistence landscape** (Bubenik 2015) turns each bar into a tent and lists the tallest, second tallest, ... tent
  at each sample point. An essential bar is a ramp `t - birth`, finite at every sample point.
* A **persistence image** (Adams et al. 2017) places each bar at `(birth, persistence)`, weighted by a function rising
  from 0 at persistence 0 to 1 at `weightCap` (default: the largest persistence), blurs it with a Gaussian, and
  integrates over each pixel. Essential bars are left out. The result matches `persim`.

The [comparing barcodes](../tutorials/comparing-barcodes.md) tutorial uses all four. From MATLAB, these are methods of a
result: `r1.bottleneckDistance(r2, dim)`, `wassersteinDistance`, `landscape`, `persistenceImage`. From the command line,
`--distance-to saved.csv` prints the distances to a diagram saved earlier.
