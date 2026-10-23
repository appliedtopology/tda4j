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
