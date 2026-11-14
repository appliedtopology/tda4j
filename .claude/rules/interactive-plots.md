---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/plot/Interactive.scala"
  - "src/main/scala/org/appliedtopology/tda4j/plot/Selection.scala"
  - "src/main/scala/org/appliedtopology/tda4j/plot/Zoom.scala"
  - "src/test/**/plot/InteractiveSpec.scala"
  - "src/test/**/plot/SelectionSpec.scala"
  - "src/test/**/plot/SpatialSpec.scala"
  - "src/test/**/plot/ZoomSpec.scala"
  - ".claude/scripts/check-interactive-page.mjs"
---

# Interactive plot pages: sliders, linked views, brushing, zoom, touch, keyboard

Loads for the interactive pages of the `plot` subpackage (#102). The rest of the plot rules are in `rules/plotting.md`;
the derivation, traps and mutation checks are in `WORKLOG-interactive-plots.md` (addenda: reveal.js, the rest of #102) and
`WORKLOG-interactive-followups.md` (#331, #333: touch, keyboard, zoom of every axis, 3-D, large complexes, frames, browsers).
Open items: #330 (PowerPoint itself, the Web Viewer, MATLAB, Safari proper), #341 (touch on real devices, notebook
display), #342 (3-D `Follows`, a rectangle in 3-D, brush by touch, axes on the flat canvas, a keyboard rectangle).

- **What a page is** (`Interactive.scala`): `Control`s and `Panel`s (a `Figure` or `Scene` with a `Reaction` per mark item;
  `Behavior` = `Shown | Follows | Radius | Custom | Selects | Selected`), chained like the tweaks. Inline JavaScript only, no
  library and no network; the static SVG still renders with scripting off and in a `.svg` file.
- **The still is the reference semantics.** `at(PageState)`, `frames` and `saveFrames` apply the behaviors in Scala
  (`Shown` is `from <= v < until`; axes pinned to the full figure; `PageState` also holds the chosen keys, the zoom windows
  and the brush rectangles); the page script (`Interactive.runtime`) implements the same rules, and
  `.claude/scripts/check-interactive-page.mjs` compares the two in Chromium, Firefox and WebKit (`BROWSER=`; Firefox and
  WebKit under `PLAYWRIGHT_BROWSERS_PATH`, WebKit needs `playwright install-deps webkit`). Run it after touching either, on
  a FRESH `Gallery` (a stale one gives a false FAIL; use `tda4j/Test/runMain`, not the root aggregate). CI job
  `interactive-pages` runs it in all three; a PR that touches only `.github/` or `.claude/` skips the job, so run it yourself
  then. Touch is driven through the Chrome DevTools protocol, so it is checked in Chromium only; a real touch screen, Safari
  proper and phones are not checked (#341). Page-versus-still comparisons hold for panels of equal size only (a board still
  resizes the barcode panel).
- **The renderers' only hook is `Svg.Tags`** (`data-ia` per item, `data-fr` per `<svg>`, with `zoom`:
  `data-z`/`data-c`/`data-k`/`data-zm`) and a `Scene` layer's `ia`. Without tags the output is byte-identical (Gallery diff
  against a build of the previous commit). A page's ids are `tda4jia-<run nonce>-<n>` and every id inside derives from it
  (two pages from separate runs must not share one); panels are `div.tda4j-panel`s that shrink to the room.
- **A new behavior** is a case, a rule in `inside`/`follow`, a `Tags` encoding, a `reactors` entry and a spec that makes `<`
  for `<=` and a missing clamp fail (mutation-check it). **`Follows` on a 3-D layer is declined** (#342; the developer's guide
  says why): a `Behavior.Custom` does it for a page that needs it.
- **Selections and brushing** (`Selection.scala`): a selection is a set of `Int` keys (the position of a bar in the list
  given); `Panel.selecting` adds a halo mark per item. Hover, clicks and brush rectangles are page-only, clamped to the plot
  area when zoomed (`inPlot`), and a brush never chooses a class that the window has cut away. `PageState.brush` is the
  still of a rectangle (`Mark.Region` draws it, `select` chooses). Build a position table from a `Vector`: a `Set.flatMap`
  merges two items at one place.
- **Zoom and pan** (`Zoom.scala`: `Window`, `ZoomAxes`): stroke groups move by `matrix()` with a non-scaling stroke, points
  and text by `translate` from `data-c`; windows live in TRANSFORMED positions (`viewOf`/`spaceAt`), so log, sqrt, symlog and
  reversed axes zoom too. The axes are redrawn from the script's own copies of `Svg.niceTicks`/`Svg.number`, the log and
  symlog tick rules and `javaFormat` (a port of Java's `%f`/`%e`, non-finite words included): change either side, change
  both; the seeded windows, the log/symlog/reversed/format pages and the line-and-word comparison of the check catch drift.
  Refused, with a message (`Interactive.zoomProblem`; `Interactive.zoomable()` skips such panels): `Transform.Custom`, a log
  axis with a `-∞` band, a number format beyond `%f`/`%e`. Generated ticks are clones of hidden templates: clear their
  inline `display`. An equal-aspect still widens a window as the page does (`asp` in `data-zm`).
- **Touch and keyboard** (page only; the still has no counterpart). Touch: a flat picture takes touch events with
  `touch-action: pan-x pan-y` (one finger scrolls the page, two pinch and pan, a tap chooses; **no brush by touch**); a 3-D
  canvas takes pointer events with `touch-action: none` (one finger turns it, two pinch). Keyboard: a focused picture
  (`tabindex=0`) takes `+ - 0` and arrows, which pan where a drag pans (10%, Shift 50%); where a drag chooses, they move a
  cross (4%, Shift 16% of the plot area), `Enter` or space sets a corner and then chooses (Shift adds), `Escape` or blur lets
  go. The drag, the cross and `Enter` share one `choose`.
- **3-D and large pages.** A 3-D panel picks the nearest projected item by pointing and clicking, halos are layers, and the
  cycle of a class chosen elsewhere is drawn; `Layer.Balls` follows `Radius` (the frame is fitted to the points, not the
  balls). `Plot.filtration` draws up to `Plot.svgCells` (20,000) cells as an SVG and more on a flat canvas seen from above
  (`Scene.turns = false`: drag pans, wheel zooms, no axes); `canvas = true | false` chooses, `maxCells` defaults to 200,000.
- **The facades hold cycles as arrays**: `Plot.exploreOn(.., cycles = ..)` and `Plot.filtrationOf(.., cycles = ..)` take the
  reported bars' cycles (`Plot.Cycles`), and a spec compares the chosen class's drawn cycle with `plotRepresentative(k)`
  for every reported bar, so a facade that draws another class's cycle fails.
- **A new interactive entry point** is mirrored in `matlab.PersistenceResult`, `cli.TDA4jConf`/`TDA4jCLI` (`--frames N`
  with `--output <folder>`), the JS exports (`Chart.frames`, `Chart.saveFrames`) and `index.d.ts`, `python/` (`_repr_html_`
  shows a slider or linked page live), the user guide (a MATLAB tab needs a `MatlabTabsSpec` case, a Python fence a
  `test_docs.py` oracle) and the developer's guide.
- **Mutation-check page code** (break the runtime in a generated page, the check script must fail): a mutant that survives
  means a missing check or page (`zoomaxes-logfar`, `zoomaxes-extras` and the plain-arrow check were added for three).
