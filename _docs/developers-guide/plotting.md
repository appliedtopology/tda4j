---
layout: main
title: Plotting internals
---

The plots are a leaf subpackage of the core, `org.appliedtopology.tda4j.plot`
(directory `plot/` under the core's sources), like `matlab` and `cli`. Nothing
in the core uses it, and it needs no dependency: plots are SVG strings and
HTML pages, and the live viewer uses the JDK's own HTTP server. What a user
calls is on the [Plotting](../user-guide/plotting.md) page of the user guide;
this page is about how it is built.

It stays in the core only while it needs nothing outside the JDK. A plotting
feature that needs a library (PNG export, a native window) belongs in a
separate module, so that the core does not take on the dependency.

## Public core API only

The plot package is inside the core, so it could see `private[tda4j]`
members. It must not use them: whatever a plot needs, a
user's own plotting code needs too. When a plot needs data the core hides,
open the core instead. That is how `CubicalGridStream.topValues` became a
public, read-only view.

## Two scene models, several renderers

Every plot is an immutable value.

**`Figure`** (`Figure.scala`) is a 2-D scene in data coordinates: a list of
`Mark`s plus axes, a legend and an optional color scale.
- The marks are `Points`, `Segments`, `Polygons`, `Discs`, `Boxes`, `Line`,
  `Text`, and two that span the whole plot, `Diagonal` and `Rule`.
- Each mark takes one paint, or one per item, and an optional tooltip per
  item, which becomes an SVG `<title>`.
- `Svg.render` draws it. Positions go through each axis's `Transform`
  (identity, log, square root, symmetric log) there and nowhere else: a figure
  holds data in its own units. What is at 0 on a log axis sits on a `-∞` line
  beside the lowest number, a log axis with a negative value is drawn as a
  symmetric log (and says so under the plot), and only a value that is not a
  number is left out and counted.
- `over` stacks one figure on another, such as a cycle on its complex.
- A figure is changed with a `Tweak` (`Tweak.scala`): `fig + tweak`. `Labels`,
  `Size`, `Style`, `Palette` and the `Scale` constructors are tweaks; see the
  user guide's "Customizing a plot".

**`Scene`** (`Scene.scala`) is a 3-D scene made of layers. It renders two ways:
- for a page, as an inline canvas viewer (drag to turn, scroll to zoom), with
  its script in the page and no CDN;
- for a file, as a static SVG: an orthographic view drawn back to front.

**`trait Plot`** is what the viewer shows and `save` writes. It has `view`,
`save`, `html`, `fragment` and `svgFile`, and is implemented by `Figure`,
`Scene` and `Board` (plots side by side). `object Plot` holds the TDA
constructors (`barcode`, `diagram`, `persistenceImage`, `landscape`, `image`,
`cubicalChain`, `complex`, `cycle`, `cocycle`, `circularCoordinates`, `torus`,
the 3-D ones) and shares the trait's name on purpose. The barcode, diagram,
persistence-image and landscape constructors take a `PersistenceDiagram` or a
list of bars (`Plot.Bars`). The others take what they draw: a `PointCloud`,
an `Image` or a grid, with a chain (or its cells with coefficients as numbers,
`Plot.Terms`) or coordinates on top. `matlab.PersistenceResult`'s `plot...`
methods, and through them the CLI's `--plot`, call these constructors on the
result's arrays.

To add a kind of plot, add a constructor to `object Plot` that builds a
`Figure` or `Scene` out of existing marks. Add a new `Mark` only for a
primitive the renderers do not draw.

## Colors are roles

A mark never holds a color, only a `Paint`:
- `Series(k)`, a categorical slot; homological degree `k` is always slot `k`,
  never its rank among the degrees shown;
- the chrome roles (`Ink`, `Secondary`, `Muted`, `Grid`, `Axis`, `Surface`);
- `Value(v)` for a data value, such as a heatmap cell or a coefficient, which
  the figure's `ColorScale` (a sequential, diverging or cyclic ramp, with limits)
  turns into a color through the palette at render time;
- `Fixed(hex)` for a color of your own.

A `Palette` resolves paints under a `Theme` (`Color.scala`):
- `Theme.Auto` writes CSS variables with a `prefers-color-scheme` block, so
  one page follows the reader's light or dark setting;
- `Light` and `Dark` write plain hex, for files that go into a paper.

`.svg` files default to `Light`. Degree also gets a marker shape
(`Shape.ofDegree`), so a diagram stays readable without color.

`Palette.brand`, the default, was chosen with a color validator. Its first
three slots are distinguishable in every pair in both modes, since a diagram
puts any two degrees side by side, and the first eight in adjacent pairs.
Re-run the validation after changing a slot, and do not cycle hues past eight.
`Palette.reference` is a neutral alternative. `Palette.print` has pure white and
black chrome and a single look (its dark chrome and dark series are the light
ones); its first three series pass every check of the validator on all pairs on
`#ffffff`, all eight on adjacent pairs, and `PlotGrammarSpec` pins the contrast
of its inks and series against its surface. `Style.print` is the transparent
background and a font stack of named families, since `system-ui` is a browser
keyword.

## The live viewer

`view()` hands a plot to `Viewer`. On first use it starts an HTTP server on
`127.0.0.1` (port 8337, or a free one) serving one page that lists every
viewed plot, newest first. Each plot is in its own iframe. New plots arrive as
server-sent events on `/events`.

The viewer opens a browser through `java.awt.Desktop` where there is a desktop,
and prints the URL otherwise. The system property `tda4j.plot.browser=none`
(or `TDA4J_PLOT_BROWSER=none`) stops it from opening one, and the specs set
it.

**Trap:** start the server from a daemon thread. The JDK's HTTP dispatcher
inherits its creator's daemon status, and a non-daemon dispatcher keeps a REPL
from exiting. `PlotSpec` checks the threads.

## Tests

Specs (`PlotSpec`) check structure: one tooltip per bar or cell, well-formed
XML, and theme colors. They do not check looks. `Gallery` in the test tree
writes every kind of plot to a directory
(`sbt "Test/runMain org.appliedtopology.tda4j.plot.Gallery <dir>"`). Open the
files in a browser, or screenshot them headlessly, and look at them after any
change to a renderer. The user guide's plotting fences are compiled by the
docs build like every other fence.
