---
layout: main
title: Plotting internals
---

The plots are a leaf subpackage of the core, `org.appliedtopology.tda4j.plot`
(directory `plot/` under the core's sources), like `matlab` and `cli`. Nothing
in the core uses it. Plots are SVG strings and HTML pages, which need no
dependency; the live viewer's web server is cask, the subpackage's one
library. What a user calls is on the [Plotting](../user-guide/plotting.md)
page of the user guide; this page is about how it is built.

It stays in the core only while it needs no other library. A plotting
feature that needs one (PNG export, a native window) belongs in a
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
a disc, or of the balls of a 3-D layer, is `factor` times the value) and `Custom` (a kind of your own). A `Reaction` is a list of behaviors for one item,
and a `Panel` is a `Figure` (`Panel.Flat`) or a `Scene` (`Panel.Spatial`) with a reaction for all items of a mark or one
for each (`reactions: Map[markIndex, IndexedSeq[Reaction]]`, the convention of `paints`). A panel refuses a behavior its
mark cannot carry out, naming the reason (`Radius` on a log axis, `Follows` on a 3-D layer). `Follows` is not built for 3-D:
nothing in 3-D follows a value in the library's pages (the sweeping line is in the 2-D diagram), a coordinate that moves also
moves the item in the layer's depth order, which the still and the canvas would have to recompute at every value, and a
`Behavior.Custom` with a script does it for a page that needs it.

**Stills are the reference semantics.** `Interactive.at(values)` applies the behaviors in Scala: it filters the items
with `Shown`, moves the ends with `Follows`, resizes the discs, and draws the figure again, with the axes pinned to the
ranges of the full figure so nothing jumps between frames. `frames`, `saveFrames` and the specs use it. The page's
controller is a second implementation of the same rules in about a hundred lines of JavaScript (`Interactive.runtime`),
and the two are compared on the same values in Chromium, Firefox and WebKit (the page's visible items against the still's;
see "Checking the pages in browsers" below), because a page that differs from its frames is a bug in one of them. `InteractiveSpec` checks the Scala side cell for cell
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
(`maxCells` refuses a page that would be too large, and says to lower the scale). Above `Plot.svgCells` (20,000) cells the complex
is drawn on a canvas seen from above instead of an SVG (`Scene` with `turns = false`: a drag pans, the wheel zooms about the
pointer, and no axes), which takes about a seventh of the bytes and a hundredth of the elements; `canvas = true` or `false`
chooses. A 3-D page's balls are a `Layer.Balls` whose radius is a `Radius` reaction of the slider. The scene's range comes from
the points, not from the balls, so that the frame stays still while they grow and a ball larger than the picture is cut at its edge.

### Linked views, brushing and zoom

**Selections.** A `Selection` is a named set of `Int` keys that the viewer chooses by pointing, the pointer's counterpart of a
`Control`. `Panel.selecting(index, name, keys)` makes the items of a mark choose a key (`Behavior.Selects`) and adds a
translucent halo, a mark of its own that follows the item it surrounds and is shown while its key is chosen
(`Behavior.Selected`); other marks listen with `Selected`, combined with `Shown` and `Follows` (every behavior of an item must
hold for it to be there). `Plot.explore` keys a class by the position of its bar in the list it was given, in every panel. The
page keeps pinned keys (click, shift-click, brush) and hovered keys apart and shows their union; hit testing is by bounding box
(within 4 pixels, nearest first). A still takes the same choice as data: `Interactive.at(PageState().select("class", 3))`.

**Brushing.** `Panel.Flat.brushable(name)`: a drag of more than 5 pixels draws a rectangle (`.tda4j-brush`) and chooses the items
whose boxes touch it; the click that ends a drag is swallowed. The rectangle is page state like the others: `PageState.brush(panel,
window)` is the still of it, a `Mark.Region` (a translucent dashed box in data units that does not enter the plot's range) added by `Interactive.at`, which refuses a panel that is not brushable and a window the axis
cannot place. What the rectangle touches is chosen with `select`, as the page does, so a state with both is what a viewer sees.
On a 3-D canvas an item is chosen by pointing, nearest first; a rectangle is not built there.

**The keyboard.** A brushable or zoomable picture takes focus (`tabindex=0`). Where a drag pans, the arrows pan by 10% of the window
(`Shift`: 50%); `+`, `-` and `0` zoom about the middle and show everything. Where a drag chooses, the arrows move a cross
(`.tda4j-cursor`, 4% of the plot area, `Shift`: 16%), `Enter` or space sets a corner and then chooses (with `Shift`: adds), and
`Escape` or leaving the picture lets go. The cross and the rectangle go through the same `choose` as a drag, so one rule decides
what a rectangle touches.

**Touch.** A flat picture listens to touch events and sets `touch-action: pan-x pan-y`: one finger scrolls the page (and a tap
chooses), two fingers zoom by the distance between them and pan by their midpoint (`keep` puts the point between the fingers
where it was, on every kind of axis, reversed ones included). A 3-D canvas listens to pointer events with `touch-action: none`,
so one finger turns it and two pinch; the page around it scrolls from outside the canvas. A brush by touch is not offered: the
finger that would drag it is the one that scrolls.

**Zoom.** `Panel.Flat.zoomable(axes)` asks the renderer for zoom tags (`Svg.Tags.zoom`): each mark is wrapped by kind (`<g data-z="s">`
for strokes and areas, moved by one `matrix()` with `vector-effect: non-scaling-stroke`; `<g data-z="p">` for points and text, each
moved by a `translate` from its own centre, so sizes are kept), and every axis element carries its kind (`data-k`, and `data-v` for
a label), with the rule that makes the ticks and the aspect in the picture's `data-zm`: per axis its transform and direction,
the number format, the number style, the label slant, and for a labelled axis its fixed ticks. The script draws the axes again for the
window it shows with its own copy of `Svg.niceTicks`, `Svg.number`, the log and symmetric-log tick rules, the minor ticks and
`String.format`'s `%f` and `%e` (`javaFormat`: the shortest decimal rounded half up, as Java does), so a change to any of them goes
in both and in `check-interactive-page.mjs`, which compares every line and word of thirty windows on each kind of axis with the
stills', and a few hundred formatted numbers with what `RootText.format` writes.
`PageState.zoom(panel, Window)` is the still of a window; a figure of equal aspect widens one range of a window to keep it,
in the still and on the page alike. Axes the page cannot draw again are refused when the page is built
(`Interactive.zoomProblem` says why), and `Interactive.zoomable()` skips them.

**Checking the pages in browsers.** `.claude/scripts/check-interactive-page.mjs` drives every page of a `Gallery` in a browser
(`BROWSER=chromium|firefox|webkit`) and compares what the page draws with the still for the same state: the visible items, the
axes' lines and words, the halos and the cycles, the rectangle, the keyboard's and the touch gestures' effects, and the canvas
through the context it draws on. The CI job `interactive-pages` of `test.yml` writes the Gallery and runs the script in all three
(Playwright 1.63.0). Touch gestures reach the page through the Chrome DevTools protocol and so run in Chromium only. A change to
the page runtime, `Svg.niceTicks`, `Svg.number` or a renderer tag is checked there. The job runs when the other build jobs do:
a pull request that touches only `.github/` or `.claude/`, the script included, skips it, so run the script yourself after
changing it.

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
server-sent events on `/events`. `tda4j.plot.host` (or `TDA4J_PLOT_HOST`)
names another address to listen on, which a container needs: Docker publishes
a port of the container's network address, not of its loopback one.

Inside `Plot.viewWith(target) { ... }`, `view()` calls `target` and never
reaches `Viewer`. The target is held in a `DynamicVariable`, so it is set for
the block's thread (and the threads that thread starts) and restored when the
block ends or throws. The demo's web page uses it: a browser tab has no
sockets for a server, and the page shows each plot under its line.

The viewer opens a browser through `java.awt.Desktop` where there is a desktop,
and prints the URL otherwise. The system property `tda4j.plot.browser=none`
(or `TDA4J_PLOT_BROWSER=none`) stops it from opening one, and the specs set
it.

**Trap:** the server must never keep a REPL from exiting. It is started from
a daemon thread and its XNIO worker is a daemon too; a non-daemon thread of
either would hold the JVM open after `:quit`. `PlotSpec` checks the threads.

## Tests

Specs (`PlotSpec`) check structure: one tooltip per bar or cell, well-formed
XML, and theme colors. They do not check looks. `Gallery` in the test tree
writes every kind of plot to a directory
(`sbt "Test/runMain org.appliedtopology.tda4j.plot.Gallery <dir>"`). Open the
files in a browser, or screenshot them headlessly, and look at them after any
change to a renderer. The user guide's plotting fences are compiled by the
docs build like every other fence.
