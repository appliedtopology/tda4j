# Interactive pages: the follow-ups of #331 and #333 (2026-11-13/14)

Ask (the project lead, 2026-11-13): "Let's take care of #331, #333 and #335", one thread, one agent at a time. #335 (the
demo page's jar and `gh-pages`) is in `WORKLOG-demo-page-jars.md`. This log is the interactive pages. It follows
`WORKLOG-interactive-plots.md` (PR #332). Branch `claude/project-thread-qez6wb`.

## What each item came to

**#331**
1. CLI `--frames N` with `--output <folder>`: built (`TDA4jConf`, `TDA4jCLI.writePlot`, `PlotCLISpec`). A folder that does
   not exist is made; a file, one frame, a kind that has no slider and a missing `--output` are errors before anything runs.
2. JavaScript `Chart.frames(count)` (the SVG texts, in a browser too) and `Chart.saveFrames(directory, count)` (Node):
   built (`Exports.scala`, `npm/index.d.ts`, `ExportsSpec`).
3. Python `_repr_html_`: built, a plot with a slider or linked views shows as the live page in a notebook, others as SVG
   (`_results.py`, `test_results.py`). **Unverified**: a real Jupyter (the test calls the method; a notebook that has not been
   trusted does not run the page's script).
4. Large complexes: built. `Plot.filtration` draws up to `Plot.svgCells` (20,000) cells as an SVG and more on a flat canvas
   seen from above (`Scene.turns = false`: drag pans, wheel zooms, no axes), `canvas = true | false` chooses, `maxCells`
   defaults to 200,000. The canvas takes about a seventh of the bytes and a hundredth of the elements of the SVG.
5. 3-D balls and `Radius`: built (`Layer.Balls`, a `Radius` reaction of the slider; `Behavior.radius` works on a ball layer).
   **`Follows` on a 3-D layer: declined.** Nothing in 3-D follows a value in the library's pages (the sweeping line is in the
   2-D diagram), a moving coordinate also moves the item in the layer's depth order, which the still and the canvas would have
   to recompute at every value, and a `Behavior.Custom` with a script does it for a page that needs it. The developer's guide
   says so. **The frame is fitted to the points, not the balls** (decision): it stays still while the balls grow, and a ball
   larger than the picture is cut at its edge.
6. "More behaviors of #102": all of it was in PR #332; nothing left to build.

**#333**
- 3-D linking: built (the canvas picks the nearest projected item by pointing and clicking, halos are layers, the cycle of a
  class chosen elsewhere is drawn). Not built: a rectangle in a 3-D canvas; the page chooses one item at a time there.
- Zoom on non-linear axes: built for log, sqrt, symlog and reversed axes, with `Axis.format` (`%f` and `%e`, flags `-`, `+`,
  `0`, `,`, width, precision, non-finite words), number style, slanted labels and minor ticks while zoomed. Refused, with the
  reason: `Transform.Custom`, a log axis with a `-∞` band, another number format.
- Touch: pinch and two-finger pan on flat pictures, pinch on 3-D canvases and the flat canvas. One finger scrolls the page (a flat
  picture has `touch-action: pan-x pan-y`), so **there is no brush by touch**; a 3-D canvas has `touch-action: none` and one
  finger turns it. **Unverified: any real touch screen.** Touch was driven through the Chrome DevTools protocol in Chromium
  (`Input.dispatchTouchEvent`), which Firefox and WebKit do not take, so touch is checked in Chromium only.
- Keyboard: `+`, `-`, `0` and the arrows on a zoomable picture (the arrows where a drag pans); on a brushable one the arrows move a
  cross (4%, shift 16% of the plot area), `Enter` sets a corner and then chooses (shift adds), `Escape` lets go. Costly on a
  crowded picture: many presses to cover a plot (the cross steps by a fraction of the plot, not by item).
- A still of a brush rectangle: built (`PageState.brush`, `Mark.Region`, `tda4j.brush`, `tda4j.pointAt`).
- Browsers: Chromium, Firefox and WebKit run `check-interactive-page.mjs` in the new CI job `interactive-pages` (`test.yml`,
  Playwright 1.63.0). Here, on the final Gallery: Chromium 208 checks, Firefox 189, WebKit 189 (the difference is touch).
  **Not checked**: Safari itself, iOS, Android browsers, a Windows or macOS Chromium. The job reads the Gallery from
  `Test/runMain`; a PR that touches only `.github/` or `.claude/` skips it (like the other build jobs).

## Traps found on the way

- **A position table built with `Set.flatMap` collapsed equal positions** (two items at one place became one); build such
  tables from a `Vector`.
- **`Test/runMain` on the root aggregate** gave 600 compile errors: use `tda4j/Test/runMain`.
- **`DOMParser` does not exist in Node**: a check that parses a page's text there needs another route. The JS spec that
  compares frames normalises the `tda4j[0-9]+` render ids (they differ between renders) and imports `FileAccess`.
- **`java.lang.String.format` is not `toFixed`**: the page's `javaFormat` is a port of `%f` and `%e` (the shortest decimal
  rounded half up, non-finite words padded and signed as Java does, `%E` upper-cases the words). 24 mismatches in the first
  comparison, all non-finite padding and sign. `4.9e-324` prints two digits in Java, so it is not in the comparison.
- **Firefox keeps `globalAlpha` as float32**: the canvas comparison has a tolerance of 1e-6.
- **A wheel check that picks a mark outside the `<svg>` zooms the browser page** (Ctrl and wheel), not the picture: the check
  picks the mark nearest the middle and follows it through the drag and the zoom.
- **WebKit needs `playwright install-deps webkit`** in the container; the three browsers live in `PLAYWRIGHT_BROWSERS_PATH`.
- **Title text is an axis record to a naive reader**: the zoom comparison skips the figure title (weight 600, y < 30).

## Mutation checks (break the page runtime in the generated HTML, count the checks that fail)

`mutate.py`/`mutate2.py` replace one expression in the pages of a Gallery (the zoom-axes pages for the first set); the check
script must fail on each.

| mutant | failures |
|---|---|
| reversed axes' positions (`revpos`) | 11 |
| minor ticks at half steps (`minorhalf`) | 5 |
| round-half-up tie (`roundtie`) | 1 |
| sqrt inverse (`sqrtinv`), symlog inverse (`symloginv`), slant (`slant`), decimal separator (`decimal`), whole-number minors (`wholeminor`), negative zero (`negzero`) | 1 each |
| tick stride (`stride`) | 2 |
| log label guard (`loglabel`), clear distance (`clear`) | 0 at first; the pages `zoomaxes-logfar` and `zoomaxes-extras` were added: **1 each** on the final Gallery |
| shift-drag adds (`shiftadd`) | 2 |
| pinch keeps the midpoint (`keepswap`) | 5 |
| window clamp on zoom (`clampzoom`) | 3 |
| pinch distance ratio (`pinchinv`) | 6 |
| canvas pan while pinching (`canvaspan`) | 1 |
| point under the pointer on a reversed axis (`pointrev`), arrows on a reversed axis (`panrev`) | 1 each |
| keyboard step 4% (`kbstep`) | 0 at first (nothing checked the plain step); **1** after the check "plain arrows move the cross 4%" |

`kbstep`, `loglabel` and `clear` were rerun on the final Gallery (written from the committed code) after the check or the pages
that kill them were added: one failure each.

## Not done, and why

- Real devices (touch, Safari on iOS, Android), Firefox and WebKit touch: not reachable from the container (#333 stays open
  for them, see the issue filed).
- A brush by touch and a rectangle in 3-D (above).
- Axes on the flat canvas: not built; the canvas is for the sizes where an SVG is the problem.
- Jupyter itself (#331 item 3).

## Addendum (2026-11-14): checks after the merge of #332's current head

- The npm package, packed and installed in an empty project: `plotFiltration(2.5).frames(3)` gives three different SVGs,
  `saveFrames` writes `frame-0000.svg` and on. `plotExplore().frames(3)` threw the library's "requirement failed" (a linked
  page is an `Interactive` without a control, the export only tested for the type); the export now checks for a control and
  `ExportsSpec` pins the message.
- `TDA4J_SCALA_VERSION=3.8.4 sbt tda4j/doc` on a fresh server: passes (every fence of the new pages compiled).
- Merging #332's branch at `f8cbb439` (it had merged `scala` and split the rules): `rules/plotting.md` is that branch's
  version, and what this log's work adds to the rules is in `rules/interactive-plots.md`.
