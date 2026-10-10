---
layout: main
title: Plotting
---

`tda4j-plot` draws what a persistence computation returns: barcodes, persistence diagrams, persistence images and
landscapes, images, complexes at a scale, cycles, cocycles and circular coordinates, in 2-D and in 3-D. It is a separate
module (`plot/` in the repository) that adds no dependency to the library and needs none itself: plots are SVG, or a page
with a small built-in 3-D viewer, and they show up in your browser while you work in a REPL.

It is its own artifact, published with the library from version 0.5.1 on:

```
libraryDependencies += "org.appliedtopology" %% "tda4j-plot" % "0.5.1"
```

(which brings `tda4j` with it). Its API is young and may still change between releases. From a checkout of the
repository, `sbt plot/console` starts a REPL with it.

## In a REPL: `view()`

```scala sc:nocompile
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

```scala sc:nocompile
Plot.barcode(diagram).save("barcode.svg")            // plain light colors, for a paper
Plot.diagram(diagram).save("diagram.svg", Some(Theme.Dark))
Plot.diagram(diagram).save("diagram.html")           // follows the reader's light or dark setting
Plot.side(Plot.barcode(diagram), Plot.diagram(diagram)).save("both.svg")
```

`figure.svg()` is the SVG as a string: a notebook cell that displays HTML, or any web page, shows it as it is.

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
| `Plot.cycle(points, bar.representative)` | a simplicial cycle over its points |
| `Plot.cocycle(points, bar.representative, at = bar.birth)` | a cocycle, colored by value, restricted to the complex at `at` |
| `Plot.circularCoordinates(points, result.theta)`, `Plot.torus(theta1, theta2)` | circular and toroidal coordinates on a cyclic color map |
| `Plot.points3D`, `Plot.complex3D`, `Plot.cycle3D` | the same in 3-D: drag to turn, scroll to zoom |

Anything that takes a diagram also takes a list of bars. A cycle reads best over the complex it lives in:

```scala sc:nocompile
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
