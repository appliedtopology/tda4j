# Interactive plots: a slider through the filtration, frames, a video and PowerPoint (2026-11-12)

Request (Mikael, after a talk): a Vietoris-Rips complex with a filtration slider coupled to a persistence diagram whose
horizontal line replaces the slider (a class is a vertical segment from the diagonal until the line passes its death, then
a point), inside the plot module, and usable in PowerPoint. After the estimate he asked for all three phases (frames and a
video recipe; a self-contained page; a 3-D page and a barcode panel) and for "generic interactivity as a target, with
enough built to enable this specific idea, but making it easy to add additional interactivity later or for users", in the
combinator style of the rest of the plotting module. Issue #102 (the list of interactivity ideas) stays open for the rest.

## Design

- **No new library, no new module.** `Scene.fragment` already emitted a page with inline JS; the page script is about a
  hundred lines (`Interactive.runtime`) and loads nothing.
- **Values, not a framework.** `Control` (a named slider), `Behavior` (`Shown(from, until)`, `Follows`, `Radius`,
  `Custom`), `Reaction` (behaviors of one item, `a + b`), `Panel.Flat/Spatial` (a `Figure` or `Scene` and
  `reactions: Map[markIndex, IndexedSeq[Reaction]]`, one reaction for all items or one per item, as `paints` work),
  `Interactive(panels, controls, title, scripts)` with chained `withSlider`, `withPanel`, `withPlay`, `withScript`, and the
  grammar's `with...`/`+ tweak` mapped over every panel. Extension: `withScript`, `tda4j.reactor(kind, f)`,
  `Behavior.Custom`, `tda4j:change` DOM events.
- **The still picture is the reference semantics.** `Interactive.at(values)` applies the behaviors in Scala (filters items,
  moves ends, resizes discs) and draws the figure again with the axes pinned to the full figure's ranges (without it,
  frames jitter). The page script is a second implementation; `.claude/scripts/check-interactive-page.mjs` compares what
  the page draws with the still, element for element, in Chromium. `Shown(from, until)` means `from <= v < until`: a cell
  is in the complex at the exact value it enters (`<=`, the same rule as `Plot.complex`), a class's segment is shown on
  `[b, d)` and its point from `d`. An essential class's segment grows with the line (clamped at the `∞` line) and never
  becomes a point.
- **Renderer hook.** `Svg.renderTagged` / `Svg.Tags` write `data-ia="kind,control,args;..."` on items (fields comma-,
  semicolon- and percent-escaped) and `data-fr` (offset, length, lo, hi, reversed, transform, constant per axis) on the
  `<svg>`; `Scene` writes an `ia` per layer. Untagged output is unchanged: the Gallery diff against a worktree of the
  previous commit was 74 of 74 files identical (ids normalized), re-run after the last change to the runtime.
- Balls are 0.5 x scale for Vietoris-Rips (the complex's diameters) and 1.0 x scale for Cech and alpha (radii). Custom
  transforms and a `Radius` on a non-linear axis are refused in Scala, since the script only knows identity, log, sqrt and
  symlog.
- Sizes: 40 points, 10,660 cells: a 1.15 MB page; the noisy circle (60 points) to scale 1.0: 737 KB. `maxCells` (20,000)
  refuses a page that would be larger and says to lower `max`.

## Facades (the new-capability checklist)

`matlab.PersistenceResult.plotFiltration(max)` and `plotFiltration3D(max)` (the result's own complex via `drawn`; the
slider runs to the scale where the computation stopped when that is sooner); the String overloads of `saveFrames` return an
`Array[String]` because MATLAB and Python have no `Seq`. CLI `--plot filtration|filtration-3d` with `--at` as the slider's
top, alone (not in a list), `.html`, or an `.svg` of the picture at the slider's start. JS `plotFiltration(max)` in
`Exports.scala` and `npm/index.d.ts`; JS has no frames. Python `plot_filtration`, `plot_filtration_3d`, `Plot.save_frames`.
`PersistenceResultPlotSpec`, `ResultFileSpec` (a saved copy draws the same page), `PlotCLISpec`, `MatlabTabsSpec`,
`python/tests/test_results.py`, `test_docs.py` (an oracle for the new Python fence).

## Checks that found something

- Mutations of the specs: `from < v`, `v <= until`, a doubled radius, points shown from the birth were each killed at the
  first run; two survived (no lower clamp on `Follows`; a segment shown only to `d` for an essential class, which is the
  `∞` line above the slider's maximum in the default page) and got examples of their own (a held line at both ends; an
  essential class on its segment with the slider past the `∞` line). The Chromium script fails when the ball radius in the
  page script is scaled by 1.1.
- The first Chromium check compared the page's visible reacting elements with ALL the lines and polygons of the still, and
  reported three differences that were the static `∞` rule, the diagonal's shade and the point markers: the script now
  compares every drawn element on both sides.
- The readout showed `ε = 0.5` beside the label `ε`: the output is the number alone.

## The video and PowerPoint recipe (run, not written from memory)

Python: `plot_filtration(1.0).with_palette("print").with_style("print").save_frames("frames", 60)` (a white background; the
default look has none, which a video shows as black), `cairosvg` at scale 2 (17.5 s for 60 frames, 2264 x 1116), `ffmpeg
-framerate 12 ... -c:v libx264 -pix_fmt yuv420p -vf pad=...` gave a 5 s H.264 MP4 of 439 KB (ffmpeg 6.1, cairosvg 2.9); the
palette-generated GIF is 2.0 MB at scale 1. A Playwright script that sets the slider through `tda4j.set` and screenshots
the page (30 shots in 6 s) is the way to record a page with a script of one's own, since stills show a `Custom` item as
drawn. `python-pptx` `add_movie` with the MP4 and a poster frame produced a `.pptx` that LibreOffice converts to a PDF whose
slide shows the poster; the video was not played and nothing was opened in PowerPoint.

What web search said (not verified): PowerPoint has no native HTML/JS embedding; an MP4 or GIF embeds reliably; Microsoft's
Web Viewer add-in needs an https address and internet, and sources conflict about whether the original is retired (December
2024) in favor of "Web Viewer 2.0"; LiveWeb is a Windows-only third-party add-in; whether an SVG animates on a slide was
not found, the guide says it is a still picture.

## Open items

- #330: unverified (Firefox, Safari, PowerPoint itself, Web Viewer, MATLAB, the page check in CI).
- #331: frames from the CLI and JS, a notebook display (`_repr_html_`), pages for large complexes, balls and `Radius` in 3-D.
- #102 stays open: linked hover between barcode and diagram, zoom and pan, brushing; `Behavior` and `Panel` were built so
  each is a case and a reactor.
- The idea of a `Plot.filtration` over a cursor (the engine's `diagramAt(f)` at each frame) was not needed: the diagram is
  computed once and the line only moves; the exactness oracle in the specs is `diagram.at(t)`.
