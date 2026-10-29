---
layout: main
title: Telling features from noise, and clustering
---

A barcode of real data has a few long bars and many short ones. Which short bars are noise is a statistical question that
persistent homology does not answer by itself, and this page has the two tools the library offers for it, and a clustering
method that uses persistence.

### Rules of thumb

`diagram.longerThan(0.1)` keeps the bars that persist longer than 0.1, in the diagram's own units.
`diagram.longerThanFraction()` keeps those longer than 1% of the data's scale (a point cloud's enclosing radius, an
image's range), which is what the command line and MATLAB show. Both say how long a bar is, not how likely it is to be noise.

### A band from resampling

`BootstrapBand` asks how far the diagram moves when the data is resampled. It draws the points again with replacement,
computes the diagram of each draw, and takes the bottleneck distance of each to the diagram of the original points. The
`1 - alpha` quantile of those distances is `band.width`, and a bar that persists longer than `band.threshold`, twice the
width, could not have been moved onto the diagonal by a perturbation that size (Fasy et al. 2014).

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val noise = new scala.util.Random(1)
val points = Array.tabulate(80) { i =>
  val t = 2 * math.Pi * i / 80
  Array(math.cos(t) + 0.05 * noise.nextGaussian(), math.sin(t) + 0.05 * noise.nextGaussian())
}

val band = BootstrapBand(points, dimension = 1, rounds = 100, alpha = 0.05, seed = 7L)
val loops = Persistence(points, maxDimension = 1).dim(1)
loops.longerThan(band.threshold)   // the loops that persist longer than twice the width of the band
```

The band is for one degree at a time. It is a resampling estimate with a statistical reading, not a proof: the guarantee
of the paper is for specific estimators. `resampling = BootstrapBand.Resampling.Subsample(m)` draws `m` points without
replacement, which is cheaper and gives a wider band. Essential bars are left out of the comparison, so the band says
nothing about them.

### Clustering by persistent peaks: ToMATo

ToMATo (Chazal, Guibas, Oudot and Skraba 2013) clusters points around the peaks of a density, and merges two peaks when
the valley between them is shallower than a depth `tau` you choose. Choosing `tau` is reading a barcode: the depths of the
valleys are the lengths of the bars of `ToMATo.diagram`, and a gap in them separates the peaks you want from the noise.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val noise = new scala.util.Random(2)
def blob(x: Double) = Array.fill(60)(Array(x + 0.3 * noise.nextGaussian(), 0.3 * noise.nextGaussian()))
val points = blob(0.0) ++ blob(3.0)

val k = 10
val density = ToMATo.density(points, k)          // minus the distance to measure with k neighbours
val graph = ToMATo.neighbourGraph(points, k)     // each point joined to its k nearest neighbours
val valleys = ToMATo.diagram(density, graph)     // a bar for each valley; its persistence is the valley's depth

val clusters = ToMATo.clusterGraph(density, graph, tau = 0.3)
clusters.labels                                  // the cluster of each point, 0 for the highest peak
clusters.peaks                                   // the point at the top of each cluster
ToMATo.cluster(points, k, tau = 0.3)             // the same, in one call
```

The density can be any function on the vertices and the graph any graph: `ToMATo.clusterGraph(values, neighbours, tau)`
takes a vector of values and a function from a vertex to its neighbours. With `tau = 0` every local maximum is a
cluster, and with an infinite `tau` every connected component is one. The bars use the library's sublevel convention for
a superlevel filtration (a peak of height `h` is born at `-h`), so `Plot.barcode` draws them as it draws any other bars.
