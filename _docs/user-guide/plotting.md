---
layout: main
title: Plotting
---

The plots draw what a persistence computation returns: barcodes, persistence diagrams, persistence images and
landscapes, images, complexes at a scale, cycles, cocycles and circular coordinates, in 2-D and in 3-D. They are part of
the library, in the package `org.appliedtopology.tda4j.plot`, and need no other dependency: plots are SVG, or a page with
a small built-in 3-D viewer, and they show up in your browser while you work in a REPL. The API is young and may still
change between releases.

## In a REPL: `view()`

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = Array.tabulate(40)(i => Array(math.cos(i * 0.157), math.sin(i * 0.157)))
val diagram = Persistence(points, maxDimension = 1)

Plot.barcode(diagram).view()
Plot.diagram(diagram).view()
```

The first `view()` starts a small web server on your machine (`http://127.0.0.1:8337/`) and opens it in your browser:
one tab that lists every plot you have viewed, newest first, and shows each new one as it arrives. Nothing to install,
no windows to manage, and the REPL exits as usual. On a remote machine or in a container the address is printed instead;
forward the port (`ssh -L 8337:localhost:8337 host`) and open it locally. Set `TDA4J_PLOT_BROWSER=none` (or the system property
`tda4j.plot.browser=none`) to never open a browser, and the system property `tda4j.plot.port` (or `TDA4J_PLOT_PORT`) for another port.

## Into files: `save`

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = Array.tabulate(40)(i => Array(math.cos(i * 0.157), math.sin(i * 0.157)))
val diagram = Persistence(points, maxDimension = 1)

Plot.barcode(diagram).save("barcode.svg")            // plain light colors, for a paper
Plot.diagram(diagram).save("diagram.svg", Some(Theme.Dark))
Plot.diagram(diagram).save("diagram.html")           // follows the reader's light or dark setting
Plot.side(Plot.barcode(diagram), Plot.diagram(diagram)).save("both.svg")
```

`figure.svg()` is the SVG as a string: a notebook cell that displays HTML, or any web page, shows it as it is.

## From MATLAB, Java and the command line

A result of the [MATLAB interface](matlab.md) draws itself: each `plot...` method returns a figure, to `view()` or
`save` as above. Here, the loop of a noisy circle and its cycle, drawn over the complex at the scale where the loop is
born:

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val d = Persistence(points, maxDimension = 1)
import d.given
val loop = d.dim(1).longest.get

Plot.barcode(d).view()
Plot.cycle(points, loop.representative).over(Plot.complex(points, loop.birth)).save("loop.svg")
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
import org.appliedtopology.tda4j.matlab.*;

points = readmatrix('_docs/tutorials/data/noisy-circle.csv');
result = TDA4j.computeFromPoints(points, {'maxDimension', '1'});

result.plotBarcode().view();
loop = result.mostPersistentBar(1);          % the index of the loop's bar (counting from 0)
result.plotRepresentative(loop, result.birth(loop)).save('loop.svg');   % its 18 edges over the complex

result.save('circle.json');                  % everything, to plot again later
result = TDA4j.loadResult('circle.json');
```

</div>
</div>

Call the methods with dot syntax (`fig.view()`, `fig.save('loop.svg')`): `save` and `view` are also MATLAB
functions. MATLAB itself is not run by the test suite; the same calls are made from Scala.

| method | draws |
|---|---|
| `plotBarcode()`, `plotBarcode(longest)`, `plotDiagram()` | the reported bars; the subtitle says how many the threshold hid |
| `plotRepresentative(i)` | bar `i`'s cycle or cocycle over the points, or its cubical chain over a 2-D image |
| `plotRepresentative(i, at)` | a cycle over the complex at scale `at`, or a cocycle restricted to the complex at `at` (at the bar's birth: the edges that close the loop) |
| `plotInput()`, `plotComplex(at)` | the points or the image; the `vr`, `cech` or `alpha` complex at `at` |
| `plotLandscape(dimension)`, `plotPersistenceImage(dimension)` | the vectorizations, of every bar (as `landscape` and `persistenceImage` compute them) |
| `plotInput3D()`, `plotComplex3D(at)`, `plotRepresentative3D(i)` | the same in 3-D (cycles only) |
| `mostPersistentBar(dimension)` | not a plot: the index of the most persistent reported bar of that dimension |
| `circularCoordinates(...).plot()`, `toroidalCoordinates(...).plot()` | circular and toroidal coordinates |

A result computed from a distance matrix or a relation has no coordinates: it draws its barcode, diagram, landscapes
and persistence images, and says so for the rest. The command line draws the same figures with `--plot`:

```
java -jar tda4j-assembly.jar --max-dimension 1 --save-result circle.json noisy-circle.csv
java -jar tda4j-assembly.jar --plot diagram --output diagram.svg circle.json
java -jar tda4j-assembly.jar --plot representative circle.json
```

The first line computes and prints the bars, and saves the whole result; the others plot it without computing again.
`--plot representative` draws the most persistent bar of `--dimension` (default 1), or `--bar N` (its line number in
the listing, counting from 0); `--at` gives the scale. Without `--output` the plot opens in the browser. See the
[command-line page](cli.md).

### Saved results

`result.save(path)` and `--save-result` write a JSON file that `TDA4j.loadResult(path)` and the command line read
back, from MATLAB, Java or Scala: every bar, which bars the threshold reported, their representatives, the points or
image, and how the result was computed (options, field, the version of tda4j). It is plain JSON, so other languages
read it too: `dimensions`, `births`, `deaths` hold every bar, `reported` the indices of the reported ones, and
`representatives` their `cells` and `coefficients`. Infinite and missing values are the strings `"Infinity"`,
`"-Infinity"` and `"NaN"`:

```python
import json
r = json.load(open("circle.json"))
deaths = [float(d) for d in r["deaths"]]    # float() reads numbers and "Infinity" alike
```

The boundary matrix is not saved, nor are the representatives of bars the threshold hid. With cocycles and every bar
reported, a dimension-0 cocycle is a whole connected component, so such a file grows quickly with the number of
points.

## What there is

| call | draws |
|---|---|
| `Plot.barcode(d)`, `Plot.barcode(d, longest = Some(10))` | one bar per class, grouped by degree; essential bars run off to `∞` |
| `Plot.diagram(d)` | `(birth, death)` per class above the diagonal; essential classes on the `∞` line |
| `Plot.persistenceImage(d, degree)`, `Plot.landscape(d, degree)` | the vectorizations of `Vectorization` |
| `Plot.image(img)`, `Plot.grid(stream)` | a 2-D image or cubical grid as a heatmap |
| `Plot.cubicalChain(img, bar.representative)` | a cubical cycle or cocycle over its image |
| `Plot.points(points)` | a point cloud (first two coordinates) |
| `Plot.complex(points, r)`, `Plot.complex(points, r, Cech)`, `Plot.complex(points, r, AlphaShapes)` | the complex at scale `r` with the balls whose overlaps it records |
| `Plot.complex(points, stream, r)` | the cells of any simplicial stream up to `r` |
| `Plot.cycle(points, bar.representative)` | a simplicial cycle over its points (a chain, or its cells with coefficients as numbers) |
| `Plot.cocycle(points, bar.representative, at = bar.birth)` | a cocycle, colored by value, restricted to the complex at `at` |
| `Plot.circularCoordinates(points, result.theta)`, `Plot.torus(theta1, theta2)` | circular and toroidal coordinates on a cyclic color map |
| `Plot.points3D`, `Plot.complex3D`, `Plot.cycle3D` | the same in 3-D: drag to turn, scroll to zoom |

Anything that takes a diagram also takes a list of bars. A cycle reads best over the complex it lives in:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = Array.tabulate(40)(i => Array(math.cos(i * 0.157), math.sin(i * 0.157)))
val d = Persistence(points, maxDimension = 1, engine = Persistence.Engine.Chunks)
import d.given
val loop = d.dim(1).longest.get
Plot.cycle(points, loop.representative).over(Plot.complex(points, loop.birth)).view()
```

A cocycle of a bar lives on the complex where the bar dies, so it has edges right across the data; restricted to the
complex at the bar's birth (`at = loop.birth`) it shows the edges that cut the loop as it closes.

## Colors

Homological degree `k` always has the same color and marker shape: degree 0 slate, degree 1 gold, degree 2 brick, so
`H1` looks the same in every plot whichever degrees are shown. The default palette is TDA4j's Slate & Gold, stepped for
charts and checked for color-vision deficiencies (the first three degrees stay distinguishable side by side for every
common deficiency). `Palette.reference` is a neutral alternative, and any figure takes another:
`Plot.diagram(d).copy(palette = Palette.reference)`. Heatmaps use a slate ramp (light for small values), cocycles a
slate-to-gold diverging scale, and circular coordinates a cyclic map with no seam at `0 = 1`.

Every plot is an immutable value: `copy(title = ...)`, `+ mark` and `over` adjust it, and `Figure`, `Mark` and `Scene`
are there to build plots of your own.
