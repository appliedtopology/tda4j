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

## Addendum 2026-11-12: a deck in reveal.js (Mikael: the talk reproduced the conference template in reveal.js)

Tried in Chromium with reveal.js 5.2.1 (`white` theme), Playwright, scratch deck with a title slide, one slide with
`<iframe data-src="a-full.html" data-preload>` and two slides with `fragment()` output pasted in.

- **Iframe route works.** The slider moves (set 0.3, ArrowRight in the iframe: 0.301) and the deck stays on its slide, so
  reveal's arrow keys do not take the slider's. A page is as wide as its panels side by side (2 x 560 + 12 = 1132 px): in an
  iframe of 1100 px the second panel wraps below the first. Not a bug; the user guide says to give the iframe the page's size.
- **Inline route works with one page.** No key conflict, no visual clash with the theme (the page paints its own surface).
- **Defect found: ids.** Two fragments written by separate runs both start `tda4jia1` (the counter restarts per JVM), the
  script does `getElementById`, so in one deck the second page's slider drove the first page's picture (B's visible items
  1124 before and after moving B's slider; A's changed). The same holds for the pictures' clip paths and style scopes
  (`tda4j1`) and the 3-D canvas (`tda4j3dN`), which depend on the id.
- **Fix (PR #332):** the wrapper id is `tda4jia-<nonce>-<n>` (nonce drawn per run), and every id inside the page is the
  wrapper's plus `-p<k>`, passed as `Svg.Tags.id` and `Scene.fragmentReacting(.., id)`. `InteractiveSpec` has two examples
  (every id under the root, none shared with a second render of the same page; the 3-D canvas too), both fail when the
  inner ids go back to the renderers' own numbering (mutation run). A plain `fragment()` keeps `tda4j<n>`: saved SVGs
  stay reproducible. The consequence: an interactive page's text is no longer the same from run to run; specs normalize it.
- **Layout (same PR):** the iframe of the first try wrapped its panels: reveal caps `section iframe` at 95% of the slide, and
  the page was two fixed 560 px pictures (1132 + 32 px of `main` padding). Panels now sit in `div.tda4j-panel` (cap = the
  picture's width, flex basis 320 px, `svg{width:100%;height:auto}`), so a narrower slide shrinks them and below about 650 px
  they stack; the script works in the picture's own units, so nothing else changed. `check-interactive-page.mjs` has a layout
  block (2400, 1300, 1000, 800, 500, 360 px). An early screenshot looked clipped: it was taken mid slide transition (reveal's
  default 0.8 s), not a page defect; the scratch deck sets `transition: "none"`. In the final deck the iframe is
  `class="r-stretch" style="width:100%;border:0"`; Gallery diff of the default plots against the previous commit: 74/74 identical.
- **Not checked:** reveal.js with its Markdown plugin, `innerHTML` insertion (a browser never runs those scripts), Firefox,
  Safari (#330).


## Addendum 2026-11-12: the rest of #102 (linked views, brushing, zoom and pan; Mikael: "do the rest of #102 for a full interactivity suite")

Four commits on PR #332 (31635b3 selections, 4b7965c brushing, a7973c2 + 9638450 zoom), then the facades and docs.

**Selections (31635b3).** A `Selection(name, labels, hover, click, listed, title)` is a named set of `Int` keys, the pointer's
counterpart of a `Control`. `Panel.selecting(mark, name, keys)` makes a mark's items choose keys (`Behavior.Selects`) and adds a
translucent halo mark (`Behavior.Selected`, shown while the key is chosen); other items listen with `Selected`, AND-combined with
`Shown`/`Follows` (a cycle cell appears only when its key is chosen AND the slider has passed it). The key of an `explore`
class is the position of the bar in the list given, in the diagram, the barcode and the labels, which the specs build to be
neither the diagram's nor the barcode's sort order. Hit testing is by bounding box (4 px, nearest, then smallest). Pinned and
hovered sets are kept apart and unioned; a click on the background lets the pinned set go. `PageState(values, selected, windows)`
is the viewer's state for a still (`Interactive.at(PageState)`).

**Brushing (4b7965c).** `Panel.Flat.brushable(selection)`; a drag of more than 5 px draws `.tda4j-brush` and chooses every item whose
box touches the rectangle (shift adds). The click that ends a drag is swallowed (`svg._dragged`); one rectangle at a time,
removed by a click on the background. Chromium checks use the one H1 bar to calibrate pixels for barcode stretches.

**Zoom and pan (a7973c2, 9638450).**
- Renderer: with `Svg.Tags.zoom` the marks are wrapped by kind (`<g data-z="s">` for strokes and areas, `<g data-z="p">` for points
  and text, each point carrying `data-c`), and every axis element carries `data-k` (kind) and, for a label, `data-v`; the rule
  that makes ticks (automatic, within a range, fixed list, plus extras such as a diagram's `∞`) and the aspect are in the
  `<svg>`'s `data-zm` JSON. The page moves a stroke group with one `matrix()` (CSS `vector-effect: non-scaling-stroke` keeps
  widths) and each point with a `translate` from its centre, so sizes are kept. Without tags the output is byte-identical.
- Ticks: the script has its own `niceTicks` and number writer (`Svg.number`: four significant digits, and Java's `%.3g` outside
  1e-3 to 1e5, so `1.00e+05`, `2.50e-05`) and draws the axes again for a window from templates cloned from the originals
  (minor ticks are dropped while zoomed). Refused with a reason (`Interactive.zoomProblem`): log/sqrt/symlog, reversed axes,
  `Axis.format`, slanted tick labels or a number style. `Interactive.zoomable()` skips those panels.
- Equal aspect: the renderer widens one range of a window to keep the aspect, so a still of a window asked for differs from
  the window; the page mirrors the same widening (`data-zm` carries the ratio), so `tda4j.window(i)` is the effective window.
- Limits: a window is clamped inside the picture; zoom stops at 1/200 of it; a window change removes the brush rectangle; hover
  and brush are clamped to the plot area (what the window cuts away still exists under the margins).
- Traps found by the Chromium checks, none by the Scala specs: (1) templates were cloned after the originals were hidden, so every
  generated tick was `display:none` (the page drew 7 elements, the still 25); (2) the page wrote `1.00e+5` where Java writes
  `1.00e+05` (found by comparing 60 random windows' words with the still's); (3) the `−` button showed the literal escape
  (a `\\u2212` in a Scala triple-quoted string reaches the page unchanged: write `−`); (4) an equal-aspect still was not the
  page (above); (5) a board still resizes the barcode panel (640x411 against the page's 640x440), so page-versus-still element
  comparison holds for panels of equal size only. Mutation checks: no clamp of the brush to the plot area (4 of 5 cut-away
  classes chosen), `inPlot` always true (3 of 4 cut-away classes chosen by hover), a still that ignores its window (4 specs fail),
  cycles in the wrong order in `plotExplore` (2 specs fail).
- Checks (`check-interactive-page.mjs`, 94 ok): the zoom-only page against the stills of three windows (visible lines, circles,
  polygons and texts matched by box within 0.1 unit), the words of 60 seeded windows of every size and place
  (`interactive-zoom-words.json`), reset, events, + and - buttons, Ctrl+wheel keeps the point under the pointer, a drag keeps the
  grabbed point under the pointer, limits, the explore page's diagram zoomed against its still, brush and hover with a window,
  the pan toggle, the barcode zooming along x only. Gallery diff against a worktree of 4b7965c: 80 files identical modulo run
  ids; the four pages that embed the runtime differ in the script, and the linked and explore pages now carry the zoom
  (explore 68 to 75 kB; linked sweep 1.18 to 1.19 MB). Default unlinked filtration and 3-D pages equal modulo the script.

**Facades.** `PersistenceResult.plotExplore()` and `plotLinkedFiltration(max)` (the facade's bars carry no typed representative:
`Plot.exploreOn`/`filtrationOf` take `cycles: Optional[Plot.Cycles]`, the result's own arrays as simplices with coefficients, one
per reported bar, used in place of the bars' own); CLI `--plot explore` and `--plot linked-filtration --at` (each alone);
JavaScript `plotExplore()`/`plotLinkedFiltration(max)` and `npm/index.d.ts`; Python `plot_explore()`/`plot_linked_filtration(max)`
(`test_facade_coverage.py` passes). Specs: the chosen class draws exactly `plotRepresentative(k)`'s cells for every reported
bar of a result whose threshold hid some bars; CLI page equals the facade's page.

**Unverified.** Firefox and Safari; touch (a drag scrolls and a tap chooses, not tried); the page check in CI (#330); MATLAB;
reveal.js with the explore page (the slider page was tried, these use the same wrapper but were not put in a deck).

**Left (#333).** 3-D linking (a canvas has its own zoom), zoom of log axes, pinch and touch brushing, minor ticks while zoomed,
a keyboard route to brush and pan, brushing a still, `explore` over a cursor.
