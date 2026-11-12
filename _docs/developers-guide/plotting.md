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
- An axis can run backwards (`Axis.reverse`), keeps a margin of `padding` of the data's extent, and the plot area can hold
  an aspect ratio (`Figure.aspect`, which wins over the diagram's default of equal units); the renderer builds a frame
  whose `px`/`py` mirror the line, and draws the diagonal's shade in the usual frame and mirrors it back, so a new mark
  that is clipped to a half plane does the same. `Transform.Custom` holds two functions: compare axes by them, so keep
  them in a `val`.
- Text that carries numbers goes through `Elements.numbers` (`NumberStyle`) at the point it is drawn: write a number into
  a label with `Svg.number` and let the renderer localize it, never format it with a symbol of your own. A text longer than
  its `TextStyle.limit` is cut by `TextFace.fit`, and a legend is sized, by `TextWidth`: the advance widths of Helvetica or
  Times (regular and bold) letter by letter, or 0.6 em in a monospace face, as no font is known when the SVG is written.
- The legend can have a box (`Elements.legendBox`: fill, frame, padding) and columns, and an axis can cross the other at a
  value (`Axis.crossesAt`, in the units of the other axis) instead of sitting at the edge; `Svg.render` decides both before
  it fixes the margins, since the room an axis takes depends on where it is drawn.
- `over` stacks one figure on another, such as a cycle on its complex.
- A figure is changed with a `Tweak` (`Tweak.scala`): `fig + tweak`. `Labels`,
  `Size`, `Style`, `Palette` and the `Scale` constructors are tweaks; see the
  user guide's "Customizing a plot".

**`Scene`** (`Scene.scala`) is a 3-D scene made of layers. It renders two ways:
- for a page, as an inline canvas viewer (drag to turn, scroll to zoom), with
  its script in the page and no CDN;
- for a file, as a static SVG: an orthographic view drawn back to front. The view is the scene's `azimuth`, `elevation`
  and `zoom` (set with the `Camera` tweak); the page's canvas starts from the same three numbers.

A **`Board`** is a row of figures, or figures on a `Layout` (`Layout.scala`): a grid of rows and columns with weights, whose
tracks are whole pixels that add up exactly to the drawing, and a `Slot` (row, column, spans) for each panel. A panel is
sized to its slot when the board is drawn (`Layout.box`), never stored resized, so tweaks and `shared` see the figures
the user placed. A page gets a CSS grid with the same tracks; a file gets nested `<svg x y>` at the same boxes.

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

## Defaults: `Look`

A `Look` (`Look.scala`) is a list of tweaks that a plot constructor applies to what it builds, found as a `given` (the
default one is in `Look`'s companion and does nothing). The constructors take `(using look: Look)`. A constructor that builds
its figure itself wraps the body in `look.figure(palette):` (or `look.scene:`), which applies the look and then the `palette`
the caller named, so that the call beats the look; one that only delegates to another public constructor just passes the
`given` on; and a call to a public constructor from inside a wrapped body passes `Look.none`, so that the look is applied
once. A tweak that raises `UnsupportedOperationException` for a kind of plot is skipped for it. Because there is always a
`given Look` to find, a constructor that forgets to pass it on would silently use the empty one: `PlotLookSpec` builds every
constructor under a look and checks each one, so a new constructor goes in its table.

## Interactive plots

`Interactive` (`Interactive.scala`) is a `Plot` made of panels and controls. A `Control` is a named slider. A `Behavior`
is what a control does to one item of a mark: `Shown(from, until)` (the item is there while `from <= v < until`),
`Follows` (a coordinate, or an end of a segment, equals the value held between `lo` and `hi`), `Radius` (the radius of
a disc is `factor` times the value) and `Custom` (a kind of your own). A `Reaction` is a list of behaviors for one item,
and a `Panel` is a `Figure` (`Panel.Flat`) or a `Scene` (`Panel.Spatial`) with a reaction for all items of a mark or one
for each (`reactions: Map[markIndex, IndexedSeq[Reaction]]`, the convention of `paints`). A panel refuses a behavior its
mark cannot carry out, naming the reason (`Radius` on a log axis, `Follows` on a 3-D layer).

**Stills are the reference semantics.** `Interactive.at(values)` applies the behaviors in Scala: it filters the items
with `Shown`, moves the ends with `Follows`, resizes the discs, and draws the figure again, with the axes pinned to the
ranges of the full figure so nothing jumps between frames. `frames`, `saveFrames` and the specs use it. The page's
controller is a second implementation of the same rules in about a hundred lines of JavaScript (`Interactive.runtime`),
and the two are compared on the same values in Chromium (the page's visible items against the still's), because a
page that differs from its frames is a bug in one of them. `InteractiveSpec` checks the Scala side cell for cell
against the stream (`from <= v` at the exact value a cell enters, a diagram checked against `diagram.at(t)`), and that
what the page is told matches what the renderer draws.

**The renderer's hook.** `Svg.renderTagged` takes `Svg.Tags`, which adds a `data-ia="kind,control,args;..."` attribute to an
item (each field percent-escaped) and a `data-fr` to the `<svg>`, the frame of its two axes as `offset,length,lo,hi,
reversed,transform,constant`, so that the script puts a value on the plot by the renderer's own formula (identity, log, square
root and symmetric log). `Svg.render` without tags is unchanged, byte for byte: a spec and a diff of every `Gallery` file against
a build of the previous commit hold the default output to that, and `Scene` does the same for its layers (`ia` in the layer's
JSON, empty when not reacting). A transform the script cannot reproduce (`Transform.Custom`) is refused where it would be needed.

**Ids.** A page is meant to be pasted into a document next to pages other runs wrote (a slide deck), and its script finds its
root by id, so the wrapper's id is `tda4jia-<nonce>-<n>`: a token drawn once per run and a count of that run's pages. Every id
inside the page (each picture's clip path and style scope, a 3-D panel's canvas) is the wrapper's id plus `-p<k>`, passed down
as `Svg.Tags.id` and to `Scene.fragmentReacting`, so a page owns all of its ids. A plain `fragment()` of a figure keeps its
per-run `tda4j<n>` number, which is what makes saved files reproducible. Each panel sits in a `div.tda4j-panel` capped at its
picture's own width, with a flex basis of 320 pixels, so a page in a slide or an iframe narrower than its pictures shrinks them
(the `viewBox` keeps the drawing, and the script works in the picture's own units) and then stacks them.

**Extending it.** `withScript(js)` adds JavaScript to the page, which finds `tda4j` (the controller of that page: `get`, `set`,
`onChange`, `reactor(kind, f)`, and `tda4j:change` events on the page's element). A `Behavior.Custom` of a kind is carried
out by the function registered for the kind; a still shows such an item as it was drawn. A new behavior of the library is a case
of `Behavior`, its rule in `inside`/`follow` for the stills, in `Svg.Tags` for the page, and in the script's `reactors` table.

`Plot.filtration`, `filtrationOf` and `filtration3D` build the page of a filtration from a point cloud and a stream:
`Panel.sweepDiagram` turns a diagram into segments that grow until the line passes their death and then points. The cells are
reactions of their own (`Shown` from the value at which each enters), so the page holds every cell up to the largest scale
(`maxCells` refuses a page that would be too large, and says to lower the scale).

### Linked views, brushing and zoom

**Selections.** A `Selection` is a named set of `Int` keys that the viewer chooses by pointing, the pointer's counterpart of a
`Control`. `Panel.selecting(index, name, keys)` makes the items of a mark choose a key (`Behavior.Selects`) and adds a
translucent halo, a mark of its own that follows the item it surrounds and is shown while its key is chosen
(`Behavior.Selected`); other marks listen with `Selected`, combined with `Shown` and `Follows` (every behavior of an item must
hold for it to be there). `Plot.explore` keys a class by the position of its bar in the list it was given, in every panel. The
page keeps pinned keys (click, shift-click, brush) and hovered keys apart and shows their union; hit testing is by bounding box
(within 4 pixels, nearest first). A still takes the same choice as data: `Interactive.at(PageState().select("class", 3))`.

**Brushing.** `Panel.Flat.brushable(name)`: a drag of more than 5 pixels draws a rectangle (`.tda4j-brush`) and chooses the items
whose boxes touch it; the click that ends a drag is swallowed.

**Zoom.** `Panel.Flat.zoomable(axes)` asks the renderer for zoom tags (`Svg.Tags.zoom`): each mark is wrapped by kind (`<g data-z="s">`
for strokes and areas, moved by one `matrix()` with `vector-effect: non-scaling-stroke`; `<g data-z="p">` for points and text, each
moved by a `translate` from its own centre, so sizes are kept), and every axis element carries its kind (`data-k`, and `data-v` for
a label), with the rule that makes the ticks and the aspect in the picture's `data-zm`. The script draws the axes again for the
window it shows with its own copy of `Svg.niceTicks` and `Svg.number` (including the exponent form outside 1e-3 to 1e5), so a
change to either goes in both and in `check-interactive-page.mjs`, which compares the words of sixty windows with the stills'.
`PageState.zoom(panel, Window)` is the still of a window; a figure of equal aspect widens one range of a window to keep it,
in the still and on the page alike. Axes the page cannot draw again are refused when the page is built
(`Interactive.zoomProblem` says why), and `Interactive.zoomable()` skips them.

**Facades.** A result keeps its representatives as arrays, so `PersistenceResult.plotExplore` and `plotLinkedFiltration` give
`Plot.exploreOn` and `filtrationOf` the cycles of the reported bars as simplices with coefficients (`cycles = ...`), in place of
the bars' own typed representatives.

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
`#ffffff`, all eight on adjacent pairs. `Palette.clear` is the same light mode
with a dark mode of its own, pure black chrome with white inks, and the brand's
dark series, which pass the validator on `#000000` the same way. `PlotGrammarSpec`
pins the contrast of both palettes' inks and series against their surfaces.
`Style.print` is a font stack of named families, since `system-ui` is a browser
keyword; `Style.clear` is that and no background. The two looks are separate
because a figure with a white background of its own and a figure that takes its
page's color need different elements.

`Style.transparent` is unset, `true` or `false`. Unset, a figure draws its
palette's surface where it is displayed (`svg`, `fragment`, `html`, the viewer)
and none in a file (`svgFile`, so `save` of an `.svg`, the facade and the CLI): a
file is deployed somewhere else, which supplies the color behind it. The two
renderings differ in one argument, `Svg.render(fig, theme, saved)` (a board
and a scene pass it to their panels and their heading, too); `Style.print` is
`false` so that a paper's figure keeps its white in a file, `Style.clear` is
`true`, and `Style.hasBackground(saved)` is the one place that decides.

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
