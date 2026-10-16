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

## Customizing a plot

A plot is an immutable value, and each customization returns a new plot, so they chain:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val d = Persistence(points, maxDimension = 1)

val loops = Plot.diagram(d.dim(1))
  .withLabels(title = "The loops of a noisy circle", x = "birth", y = "death")
  .withXYScale(Transform.Log10)
  .withSize(640, 640)
loops.view()
```

The design follows the grammar of graphics: the plot constructors (`Plot.barcode`, `Plot.diagram`, ...) choose what is
drawn, and the `with...` methods change the scales, the labels, the colors and the look, each independently of the others.
Every argument is optional, and only what is given changes: `withLabels(x = "birth (cm)")` leaves the title as it is.
Changes of different parts can come in any order; of the same part, the later one wins, keeping the rest of the earlier
one (`.withLabels(title = "A").withLabels(title = "B")` titles the plot `B`).

| to change | method | the same as a value |
|---|---|---|
| the words: title, subtitle, axis labels, legend heading, color bar title | `withLabels(title = ..., subtitle = ..., x = ..., y = ..., legend = ..., color = ...)` | `Labels(...)` |
| the size in pixels | `withSize(960, 540)`, or `withSize(width = 900)` to keep the height | `Size(...)` |
| an axis: scale, limits, ticks, number format, gridlines | `withXScale(...)`, `withYScale(...)`, `withXYScale(...)` | `Scale.x(...)`, `Scale.y(...)`, `Scale.xy(...)` |
| the scale of a heatmap or a cocycle's values | `withColorScale(min = ..., max = ..., stops = ..., transform = ...)` | `Scale.color(...)` |
| the color, legend label and marker of a homological degree | `withDegree(1, color = "#c2185b", label = "loops", shape = Shape.Diamond)` | `Scale.degree(...)` |
| the color scheme | `withPalette(Palette.reference)` | the `Palette` itself |
| fonts, size of text and marks, gridlines, the legend's place, the background | `withStyle(Style.slides)` | the `Style` itself |
| what is drawn | `plot + mark`: `Mark.Rule.horizontal(0.1)`, `Mark.Diagonal(offset = 0.1)`, your own `Mark.Points(...)` | |

The right-hand column matters when a look is shared. Each change is also a *tweak*, a value: you can keep it in a `val`,
combine tweaks with `+` and add the result to any plot, of any kind, with `+`. The look of a paper or a talk is then one
`val` (see [The look](#the-look) below). A tweak that means nothing for a plot (an axis of a 3-D scene) says so; one that
merely has nothing to change (a color scale on a barcode) leaves the plot as it is, so one bundle fits every plot.

### Labels, size and scales

An axis takes a scale (`Transform.Log10`, `Transform.Sqrt`, or `Transform.SymLog(linear)`, logarithmic away from zero and
linear within `linear` of it), the ends of the visible range, the tick positions and a printf pattern for the tick
numbers, and can drop its gridlines or disappear altogether:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val d = Persistence(points, maxDimension = 1)

val barcode = Plot.barcode(d)
  .withXScale(max = 2, breaks = Seq(0, 0.5, 1, 2), format = "%.1f")
  .withYScale(grid = false)
barcode.save("barcode.svg")
```

`min` and `max` move one end each, so `withXScale(max = 2)` keeps the plot's own lower end.

Every value has a place on every scale. On a log axis what is at exactly `0`, such as an `H0` bar's birth, sits on a
`-∞` line just below the lowest number, labelled `-∞` as an essential bar's end is labelled `∞`; a bar born there starts
from the line. A log axis with a negative value (a sublevel set of a function that goes below zero) is drawn as a
symmetric log: logarithmic away from zero, linear within the smallest magnitude on the axis, zero in its place, and a
line of small print under the plot says so. A square root keeps the sign, so a negative value is mirrored, and
`Transform.SymLog(linear)` sets the linear stretch yourself. Only a value that is not a number at all is left out, and
the small print counts it. `withXScale(minusInfinity = false)` leaves what is at 0 out of a log axis instead (counting
it), and `minusInfinity = true` keeps the line with nothing on it, so that panels line up (`Board.shared()` does that
for you). The diagonal of a diagram is drawn in the plot's own scales, so it is still the line `y = x` on a log-log
diagram, and it starts where the numbers do, not at the `-∞` line.

### Colors

Homological degree `k` always has the same color and marker shape: degree 0 slate, degree 1 gold, degree 2 brick, so
`H1` looks the same in every plot whichever degrees are shown. The default palette is TDA4j's Slate & Gold, stepped for
charts and checked for color-vision deficiencies (the first three degrees stay distinguishable side by side for every
common deficiency). `Palette.reference` is a neutral alternative, and `Palette.print` is for a paper: black text on a
background of exactly white, where the default's cream would print as a grey patch, and `Palette.clear` is black or white
for a figure with no background (see [For a paper](#for-a-paper-and-for-a-page-of-either-color)). A
palette recolors the whole plot, heatmaps and cocycles included, and you can make your own:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val d = Persistence(points, maxDimension = 1)

val onWhite = Palette.brand.withSurface("#ffffff") // a figure for a white page
val bold = Palette.brand.withSeries(Seq("#222222", "#c2185b", "#00796b"))

val diagram = Plot.diagram(d).withPalette(bold)
val image = Plot.persistenceImage(d, 1).withPalette(Palette.reference)
val paper = Plot.barcode(d).withPalette(onWhite)
```

One degree can be changed wherever it appears, in the marks, the legend and the dark mode alike, without touching the
rest, and whichever palette the plot has:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val d = Persistence(points, maxDimension = 1)

val diagram = Plot.diagram(d)
  .withDegree(0, label = "components")
  .withDegree(1, color = "#c2185b", label = "loops", shape = Shape.Diamond)
  .withLabels(legend = "Homology")
```

Heatmaps (persistence images, images, cubical grids), cocycles and circular coordinates have a *color scale*, which maps
the values to colors: heatmaps and cocycles through the palette's sequential and diverging ramps, circular coordinates
through a cyclic one. `withColorScale` sets its limits, so that two plots are read on one scale, and the colors of a
sequential ramp (`ColorScale.viridis`, `ColorScale.greys`, or any list of colors, light to dark) and how values are
spread along it (`Transform.Sqrt` for a heavy-tailed image). A cocycle's scale is symmetric about zero, so it takes only
a `max`.

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val d = Persistence(points, maxDimension = 1)

val image = Plot.persistenceImage(d, 1)
  .withColorScale(max = 0.05, stops = ColorScale.viridis, transform = Transform.Sqrt)
  .withLabels(color = "density")
```

### The look

A `Style` holds what is not data: the font, a factor on the size of text and one on the size of markers and lines,
the gridlines, where the legend goes (`LegendPosition.Right`, `Bottom` or `Hidden`) and whether there is a background at
all. `Style.slides` is a projector-sized preset (text and marks 30% larger); `Style.print` and `Style.clear` are for a paper
and for a page of either color. A style
replaces the plot's whole style; to change one setting of a style, copy it. Keep everything that makes a look a `val`, with `+`, and add it to every plot, of
any kind:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val d = Persistence(points, maxDimension = 1)

val house = Style.slides.copy(legend = LegendPosition.Bottom) + Palette.reference + Size(960, 540)

Plot.barcode(d).withLabels(title = "Barcode") + house
Plot.diagram(d).withLabels(title = "Diagram") + house
Plot.points3D(points) + house
```

(`Theme`, which `save` and `html` take, is a different thing: whether the colors are light, dark, or follow the reader's
own setting.)

### For a paper, and for a page of either color

Two looks are ready-made, each a palette and a style, and they differ in what is behind the figure.

`Palette.print` and `Style.print` are for a figure that has a white background of its own, as in a paper. The palette is
black text and axes on a surface of exactly white (the default's cream would print as a grey patch); its degree colors
stay apart for every common color vision and read on white (blue, vermillion and bluish green for degrees 0 to 2,
checked on all pairs), and its heatmap ramp darkens steadily, so it keeps its order on a grayscale printer. It has one
look: the dark theme draws the same figure. The style names fonts that converters and publishers know
(`Helvetica, Arial`) instead of the browser's own `system-ui`.

`Palette.clear` and `Style.clear` are for a figure with no background, that takes the color of what is behind it. The
style draws none. The palette has two looks, which the theme picks: pure black elements for a light page
(`Theme.Light`, what an `.svg` file gets) and pure white ones for a dark page (`Theme.Dark`), each with degree colors
chosen to read on its own page color. `Theme.Auto` follows the reader's setting, which is right only where the page does
too. Without the style the figure keeps the white or the black background of its mode.

A size is in pixels of 1/96 inch, and text is 11 to 15 pixels. Give the figure the size it has on the page and place it
at that size, and its text comes out at the size the journal asks for: a 3.4-inch column is 326 pixels, in which tick
labels are 8.25 pt. For a serif face to match the text of the paper, copy the style:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val d = Persistence(points, maxDimension = 1)

val paper = Palette.print + Style.print + Size(326, 260)
val serif = Palette.print + Style.print.copy(font = "'Times New Roman', Times, serif") + Size(326, 260)
val overlay = Palette.clear + Style.clear

Plot.diagram(d).withLabels(x = "birth", y = "death") + paper
Plot.barcode(d) + serif
(Plot.diagram(d) + overlay).save("for-a-light-page.svg")
(Plot.diagram(d) + overlay).save("for-a-dark-page.svg", Some(Theme.Dark))
```

### Comparing plots

Plots drawn side by side mean something only on the same scale. `shared()` gives every panel of a board the union of
the panels' ranges, on both axes or on `shared(y = false)` the x axis only; a tweak added to a board with `+` changes
every panel (`Labels(title = ...)` titles the board, the page it is saved as):

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val a = Persistence(CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv"), maxDimension = 1)
val b = Persistence(CSV.readPointCloud("_docs/tutorials/data/noisy-circle-b.csv"), maxDimension = 1)

val board = Plot.side(Plot.barcode(a).copy(title = "first"), Plot.barcode(b).copy(title = "second")).shared(y = false)
(board + Style(grid = false)).save("compare.svg")
```

### Reference lines and marks of your own

`Mark.Rule` draws a line across the plot at one value, and `Mark.Diagonal(offset)` the line `y = x + offset`: a threshold
on a barcode, or the line above which a point of a diagram persists longer than `offset`. They take no part in choosing
the visible range. Any other `Mark` can be added with `+` as well, in data coordinates:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val d = Persistence(points, maxDimension = 1)

val diagram = Plot.diagram(d) +
  Mark.Diagonal(offset = 0.2, paint = Paint.Muted, dashed = true) +
  Mark.Text((0.1, 1.2), "persistence 0.2", anchor = Anchor.Start)
val barcode = Plot.barcode(d) + Mark.Rule.vertical(0.5)
```

### From MATLAB and the command line

MATLAB has no named or default arguments and cannot build a `Palette` or a `Transform`, so every figure also has the
common changes as methods that take only strings and numbers, and return the changed figure: `withTitle`, `withSubtitle`,
`withXLabel`, `withYLabel`, `withLegendTitle`, `withColorLabel`, `withSize(width, height)`, `withXScale('log')`
(`'linear'`, `'log'`, `'sqrt'` or `'symlog'`), `withYScale`, `withXRange(min, max)`, `withYRange`,
`withPalette('print')` (`'brand'`, `'reference'`, `'print'` or `'clear'`), `withStyle('print')` (`'default'`,
`'slides'`, `'print'` or `'clear'`), `withDegreeColor(k, '#c2185b')`, `withDegreeLabel(k, 'loops')`,
`withTextScale(1.3)`, `withMarkScale`, `withLegend('bottom')` (`'right'`, `'bottom'` or `'none'`), `withoutGrid()` and
`withTransparentBackground()`; a 3-D scene has the ones that need no axes. `save` takes the theme by name:
`fig.save('diagram.svg', 'dark')`.

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*

val points = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
val d = Persistence(points, maxDimension = 1)

val diagram = Plot.diagram(d).withLabels(title = "A noisy circle", x = "birth", y = "death").withSize(800, 600)
diagram.save("diagram.svg")
Plot.barcode(d).withXScale(Transform.Sqrt).withStyle(Style(textScale = 1.3)).save("barcode.svg")
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
import org.appliedtopology.tda4j.matlab.*;

points = readmatrix('_docs/tutorials/data/noisy-circle.csv');
result = TDA4j.computeFromPoints(points, {'maxDimension', '1'});

result.plotDiagram().withTitle('A noisy circle').withXLabel('birth').withYLabel('death').withSize(800, 600).save('diagram.svg');
result.plotBarcode().withXScale('sqrt').withTextScale(1.3).save('barcode.svg');
```

</div>
</div>

The command line has a flag for the same changes of a `--plot`: `--title`, `--x-label`, `--y-label`, `--width`,
`--height`, `--palette` (`brand`, `reference`, `print` or `clear`), `--style` (`default`, `slides`, `print` or `clear`), `--theme`
(`light`, `dark` or `auto`), `--text-scale` (applied to the `--style`), `--x-scale` and
`--y-scale` (`linear`, `log`, `sqrt` or `symlog`). A word that is not one, a size out of range and a flag a plot cannot
use (an x scale for a 3-D scene) are errors, reported before anything is computed:

```
java -jar tda4j-assembly.jar --plot diagram --title "A noisy circle" --x-scale sqrt --width 800 --output diagram.svg circle.json
```

Every plot is an immutable value, and `Figure`, `Mark` and `Scene` are there to build plots of your own: `copy(...)`
changes any part directly, `Tweak(f)` makes a tweak of your own from a function on figures, and `over` draws one figure
on another.
