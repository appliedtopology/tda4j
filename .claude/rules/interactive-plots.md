---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/plot/Interactive.scala"
  - "src/main/scala/org/appliedtopology/tda4j/plot/Selection.scala"
  - "src/main/scala/org/appliedtopology/tda4j/plot/Zoom.scala"
  - "src/test/**/plot/InteractiveSpec.scala"
  - "src/test/**/plot/SelectionSpec.scala"
  - "src/test/**/plot/ZoomSpec.scala"
  - ".claude/scripts/check-interactive-page.mjs"
---

# Interactive plot pages: sliders, linked views, brushing, zoom

Loads for the interactive pages of the `plot` subpackage (#102). The rest of the plot rules are in `rules/plotting.md`;
the derivation, traps and mutation checks are in `WORKLOG-interactive-plots.md` (addenda: reveal.js, the rest of #102).
Open items: #330 (browsers, PowerPoint, MATLAB, the page check in CI), #331 (frames from the CLI and JS, notebooks, big
complexes, 3-D balls), #333 (3-D linking, non-linear zoom, touch, keyboard, a still of a brush).

- **What a page is** (`Interactive.scala`): `Control`s and `Panel`s (a `Figure` or `Scene` with a `Reaction` per mark item;
  `Behavior` = `Shown | Follows | Radius | Custom | Selects | Selected`), chained like the tweaks. Inline JavaScript only, no
  library and no network; the static SVG still renders with scripting off and in a `.svg` file.
- **The still is the reference semantics.** `at(PageState)`, `frames` and `saveFrames` apply the behaviors in Scala
  (`Shown` is `from <= v < until`; axes pinned to the full figure; `PageState` also holds the chosen keys and the zoom
  windows); the page script (`Interactive.runtime`) implements the same rules, and `.claude/scripts/check-interactive-page.mjs`
  compares the two in Chromium (run it after touching either, on a FRESH `Gallery`: a stale one gives a false FAIL; not in CI,
  #330). Page-versus-still comparisons hold for panels of equal size only (a board still resizes the barcode panel).
- **The renderers' only hook is `Svg.Tags`** (`data-ia` per item, `data-fr` per `<svg>`, with `zoom`:
  `data-z`/`data-c`/`data-k`/`data-zm`) and a `Scene` layer's `ia`. Without tags the output is byte-identical (Gallery diff
  against a build of the previous commit). A page's ids are `tda4jia-<run nonce>-<n>` and every id inside derives from it
  (two pages from separate runs must not share one); panels are `div.tda4j-panel`s that shrink to the room. The script
  knows identity/log/sqrt/symlog scales only.
- **A new behavior** is a case, a rule in `inside`/`follow`, a `Tags` encoding, a `reactors` entry and a spec that makes `<`
  for `<=` and a missing clamp fail (mutation-check it).
- **Selections and brushing** (`Selection.scala`): a selection is a set of `Int` keys (the position of a bar in the list
  given); `Panel.selecting` adds a halo mark per item. Hover, clicks and brush rectangles are page-only, clamped to the plot
  area when zoomed (`inPlot`), and a brush never chooses a class that the window has cut away.
- **Zoom and pan** (`Zoom.scala`: `Window`, `ZoomAxes`): stroke groups move by `matrix()` with a non-scaling stroke, points
  and text by `translate` from `data-c`; the axes are redrawn from the script's own copies of `Svg.niceTicks`/`Svg.number`
  (change either, change both: the 60 seeded windows of the page check catch a drift). An equal-aspect still widens a window
  as the page does (`asp` in `data-zm`). Only linear, forward, unformatted axes zoom (`Interactive.zoomProblem` says why not;
  `Interactive.zoomable()` skips such panels). Generated ticks are clones of hidden templates: clear their inline `display`.
- **The facades hold cycles as arrays**: `Plot.exploreOn(.., cycles = ..)` and `Plot.filtrationOf(.., cycles = ..)` take the
  reported bars' cycles (`Plot.Cycles`), and a spec compares the chosen class's drawn cycle with `plotRepresentative(k)`
  for every reported bar, so a facade that draws another class's cycle fails.
- **A new interactive entry point** is mirrored in `matlab.PersistenceResult`, `cli.TDA4jConf`/`TDA4jCLI`, the JS exports
  and `index.d.ts`, `python/`, the user guide (a MATLAB tab needs a `MatlabTabsSpec` case, a Python fence a `test_docs.py`
  oracle) and the developer's guide.
