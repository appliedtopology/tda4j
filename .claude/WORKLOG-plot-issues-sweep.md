# Plot issues sweep (2026-10-23)

One session through the open plot issues (#75, #76, #97, #98, #101-#108, #110), in order of size, one commit per issue
on `claude/project-thread-uhbr7a`. Point-in-time record; the rules that came out of it are in `rules/plotting.md`.

## Decisions (project lead, 2026-10-23, on #110)

- A `Look` keeps skipping a tweak a kind of plot cannot have (a): a look is a house style, not a checklist.
- A diagram keeps equal units in a wide layout cell (a), and `place(..., fill = true)` relaxes it for that panel (b).

## Defaults chosen without waiting (reversible, said in the thread)

- #75: a result's `plotRepresentative(i)` draws a cocycle restricted to the complex at its bar's birth;
  `plotRepresentative(i, Infinity)` draws it whole. `Plot.cocycle` is unchanged (the whole cocycle). Done after #76 made
  every point-cloud complex drawable, so the default has no complex it must refuse.
- #101 (PNG/PDF): no rasterizer module; documented external conversion only. #102 (interactivity): not in this sweep.
- #105: the command line gets no board (one plot per call); MATLAB and Java get `TDA4j.side` / `TDA4j.layout` and
  plain-argument `Board` methods. Java callers of the `Plot.*` constructors (the `using Look` parameter) stay as they are:
  the facade is the supported entry from Java and MATLAB.

## Per issue

- **#110 fill.** `Board.place(..., fill = true)` is `withAspect(free = true)` on that panel. Spec: spread of the
  circles' x in a 2-cell span is more than twice as wide as with the default. Mutation (ignore `fill`): spec fails.
- **#97 color bar ticks.** `ColorScale.barTicks`: round numbers of the data between the ends, placed by the transform
  (`sqrt`: 1 is half way to 4). Only for a `Sequential` scale with a transform; the other ramps are linear. The renderer
  skips a label closer than 1.5 tick-label sizes to the one above or to the bar's end. Mutation (linear fraction): fails.
- **#97 board title.** `Board.heading` (new field, drawn above the panels by `Svg.heading`, in the first panel's title
  face) is set by `Labels(title = ...)` on a board, which already set the page title. `Plot.side`'s computed page
  title (`"A | B"`) is not drawn. Mutation (no band): fails.
- **#106 CLI.** `--caption`, `--tag`, `--description` (2-D only, refused for `-3d` kinds before any computation) and
  `--look` (`Look.named`, applied first; `--text-scale` scales the look's style unless `--style` is given).
- **#105 MATLAB/Java.** `TDA4j.side(a, b[, c])`, `side(Figure[])`, `layout(rows, cols[, width, height[, gap, widths,
  heights]])`; `Board.place` (3 and 5 argument forms), `tagged()`, `shared()`, `withTitle/Palette/Style/TextScale/
  Setting`. A spec requires, for each name, an overload taking only strings, numbers, booleans, figures and arrays.
- **#76 facade complexes.** `TDA4j.drawnComplex` rebuilds the complex from the result's options: alpha backend,
  `requireValidTriangulation`, witness landmarks (one-shot or passed in) and variant, DTM and sparse parameters. A
  witness complex is relabelled from landmark numbers to point numbers (`ExplicitStreamBuilder`), so its tooltips and
  a representative drawn over it agree. `maxFiltrationValue` caps the scale; the subtitle says so. Dowker and
  distance-matrix results still have no coordinates.
- **#108 text width.** `TextWidth.of(text, size, family, bold)`: Helvetica and Times advance widths for ASCII (a 95-entry
  table each), monospace 0.6 em, the characters outside the table by class (`…`/`∞` 1000, accented letters as their
  base, wide scripts 1000); bold is x1.06 (sans) or x1.08 (serif) on everything but digits and spaces. `Family.of`
  takes the first known face of the font list (`sans-serif` is Sans, not Serif). `TextFace.fit` cuts by cumulative
  widths, the ellipsis counting; the legend column and the legend items are `max(old 7.5 px/character estimate,
  34/30/14 scale + measured width)`, so no legend gets narrower. One explained default-output change: the `H0` legend
  column of a diagram is 49.34 px, not 49.0.
- **#103 legend box and columns.** `Elements.legendBox: LegendBox(fill, frame, padding, columns)`, none drawn unless a
  fill or a visible frame is asked for; `Guide.legend(box, background, backgroundPaint, opacity, frame, frameColor,
  framePaint, frameWidth, padding, columns)`; settings `legend-box.background|opacity|frame|frame-width|padding|columns`
  (`none` for the fill or the frame); `Figure.withLegendBox(...)` and the plain `withLegendBox(color)`,
  `withLegendColumns(n)`. Layout in `Svg.render`: `boxPad = padding * sLegends` (padding grows with the legend's text);
  entries in columns are filled a column before the next (`grouped(ceil(n / columns))`: 7 entries in 3 columns are
  3, 3, 1), a column is as wide as its widest item (`pitch`); beside the plot the reserved width is `18 + block + 2
  boxPad` when boxed, so the box ends at the figure's right margin; above or below the plot the columns are a grid
  beside the heading and the rows after the first keep clear of it; in a corner the box is `pad` (8 sLegends) from the
  plot's edges. Columns are given up, from the number asked down, until they fit: two fifths of the figure's width
  beside the plot, the plot's width otherwise. Default output is byte-identical (specs compare up to ids).
  Decisions: the default fill is the page (`Paint.Surface`) at 0.9 with an `Axis`-colored 1 px frame; a legend box on a
  3-D scene is refused like any 2-D tweak. Mutations killed: padding not scaled, row-major fill, columns ignoring the
  room, rows ignoring the title, no room reserved for the box, no fill, columns not limited beside the plot. Looked at
  in Chromium: right, bottom, top (slides) and lower-left with a data point under the box.
- **Formatter.** The native scalafmt rewrote `TextWidth.scala` (the Times table); committed on its own.
- **#104 axis through a value.** `Axis.crossesAt: Option[Double]`, set by `Scale.x/y(crossesAt = ...)`,
  `withXScale/withYScale(crossesAt = ...)`, `x-axis.crosses-at`/`y-axis.crosses-at`, plain `withXCrossesAt(v)` and
  `withYCrossesAt(v)`. Decision (reversible): the value is in the units of the axis it crosses, so `Scale.x(crossesAt = 0)`
  is the x axis through `y = 0` ("the x axis crosses at 0", as MATLAB's `XAxisLocation = 'origin'`); `Scale.xy` has no
  `crossesAt`. A crossing is kept when the other axis shows the value in the range before an equal aspect widens it (the
  margins must not depend on the plot area they shape), else the axis stays at the edge and a note says why; a log axis
  cannot show 0 or a negative number, so that is the same case. The numbers sit beside the line on the axis's side, the
  tick marks start on the line, the gridlines still run across. The label stays at the edge: baseline 12 sAxis below the
  bottom edge (4 above the top, 4 left of the left edge, 12 right of the right edge); the edge band is the label's 16
  sAxis (14 px at least) in place of the numbers' 30 sTick + label + marks + slant. The range block of `Svg.render` moved
  above the margins to decide this. Mutations to run: axis at the edge, numbers at the edge, margin kept, label beside the
  axis.
