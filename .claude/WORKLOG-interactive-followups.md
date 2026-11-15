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

## Addendum (2026-11-14): #342, the five items that were left

All five were built and none declined. For each, the still (`PageState`) and the page agree as `check-interactive-page.mjs`
shows (Chromium, Firefox and WebKit; the touch gestures in Chromium only), and a spec or a check fails the plausible wrong
answer. One commit per item (the first three), the last two with the docs.

1. **`Follows` on a 3-D layer.** A moving end of a layer item gets a slot of its own in the canvas's position table
   (`P`), `moveAll()` rewrites it when the control changes and the depth order is sorted again (`order = null`), because a
   coordinate that moves moves the item in depth too. The scene fits its frame to every place an item can reach
   (`Scene.fitted`), so a plane rises inside a picture that stays still. The check compares the page's drawn kinds and
   places with the still at four values in an order that changes the depth order each time; the follow range of the plane is
   narrower than the slider's, so the clamp is compared as well.
2. **A rectangle on a 3-D canvas.** `Panel.Spatial(..., brush)`/`brushable`, a `box` button (a drag draws instead of
   turning or panning), the rectangle in the plane of the view in frame-radius units (x right, y up, whatever the zoom, pan
   and canvas size), `Interactive.touched` (the library's answer), `PageState.brush` (the still, `Scene.region`).
   The geometry is a closed rectangle: a point whose centre is in it, a segment that meets it (Liang-Barsky), a triangle
   that overlaps it; written twice (`Scene.touching`, the page's `touches`/`cuts`/`inTri`). The check drags the library's
   seven cases and compares keys, the rectangle read back, the still, a one-finger drag and a second selection that a
   rectangle must leave alone.
3. **Brush by touch.** A finger scrolls the page, so a rectangle needs a mode: a flat brushable picture has a `touch` button
   (`touch-action: none` while on), a second finger is a pinch and drops the rectangle, a cancelled touch clears it. The 3-D
   canvas does it through the `box` button.
4. **Axes on the flat canvas** (`Scene.axes`, `withAxes`; `Plot.filtration` above 20,000 cells). The plot box is the canvas less
   48 px (times the text scale) on the left, 8 right, 8 above and 26 (times the text scale) below; items are clipped to it,
   the picture is fitted into it and zooms about its middle. Ticks are `Svg.niceTicks`/`Svg.number` in Scala (the still's
   `g.tda4j-axes`) and the same two functions in JavaScript, `Scene.tickScript`, which `Interactive.runtime` shares for the
   zoomed figures (`__TICKS__`): one source for the two scripts, as the still is the reference. The check compares the words
   and the marks' fractions of the plot box with the still's, and then, after a wheel zoom and a drag pan, that **the ticks say
   what the picture shows**: the 60 data points of the page, put at the pixels the ticks give, are where the discs are.
   Writing the check for pointing found a bug: with axes, pointing and a rectangle also saw the items the clip hides. Pointing is
   refused outside the plot box (`pickAt`), the corners of a rectangle are held inside it (`loc`), and a rectangle set from the
   data is clipped to it (`paintRect`); a Gallery page of its own (`interactive-axes-pick`) carries the three checks.
5. **A keyboard route to a rectangle** other than a cross moving by a fraction of the plot area: `]` and `[` (and Page Down and
   Page Up) put the cross on the next and the previous item the rectangle can choose, in the plot area, left to right and top to
   bottom by the middle of the item's box (a point's centre, a segment's middle, a triangle's centroid on the canvas);
   `Home` and `End` on the first and the last; every item at that place lights up, as a click there would choose them. Two
   `Enter`s around two items are a rectangle between them, however far apart. A brushable 3-D canvas takes focus and the same
   keys (it had no keyboard before), with the box button on or off. Considered and not built: zoom about the cross (the
   zoom keys already act about the middle, and a rectangle does not need it), stepping by a count (Home/End and repeat reach
   any item in `n` presses at worst).

Found by running the check in Firefox and WebKit (Chromium had passed it every time):

- **Firefox: the check, not the page.** The keyboard rectangle between "points 3 and 9" of the stepping order held a third
  point in Chromium and nothing in Firefox, whose fonts move the plot area; the 40 points are the same, the layout is not.
  The check now searches the layout for two points that have a third strictly inside the rectangle between them. An oracle
  that names positions in a layout it did not build is a fixed seed in disguise.
- **WebKit: the page.** `hoverAt` hands a pointer move to `requestAnimationFrame`; a key pressed before that frame ran lit its
  item, and the frame then put the light out (the mouse's last position was outside the plot box). `lightUp` now cancels the
  waiting frame, and the check sends a pointer move and `]` in one task, so the order is the same in every browser
  (`cvframe` below). The flat picture has no frame in its hover, so it has no such order; its 120 ms wait before the first key
  stays for the mouse's own last move.

Measured, not changed: one `]` on a brushable canvas costs about 2.5 redraws (190 ms at 20,000 points, ~2.5 s at 200,000,
loaded machine, one run each), because `placesOf` sorts every item on each press. #344 holds this and the bare axes.

Page-code mutants (a generated page's script is broken, the check must fail; counts are failed checks):

| mutant | failed checks |
|---|---|
| axes on the canvas: sign of the y ticks (`yticksign`) | 9 |
| ... origin of the x ticks (`xorigin`), scale of the y ticks (`yscale`) | 4 each |
| ... y words aligned left (`yalign`) | 8 |
| ... three ticks asked for in place of five (`threeticks`) | 2 |
| ... plot centre taken as the canvas centre (`centre`), wheel about the canvas centre (`wheelctr`), a shortened axis line (`shortaxis`), no clip (`noclip`) | 1 each |
| a flat canvas with axes: no guard in `pickAt` (`pickguard`) | 1 |
| ... corners not held in the plot box (`locclamp`, `clampy`) | 2 each |
| ... rectangle from the data not clipped (`rectclip`) | 0 at first (a uniform tint passes a strip test: the check compares the strips with how they were); **1** after |
| keyboard on a canvas: items sorted by y first (`cvsort`) | 4 |
| ... items outside the plot box stepped to (`cvinside`) | 5 |
| ... Home and End swapped (`cvends`) | 6 |
| ... arrow steps 4% and 16% swapped (`cvstep`) | 4 |
| ... no clamp of the cross (`cvclampk`) | 2 |
| ... only one of two items at a place lights (`cvtwin`), a triangle stood at its first vertex (`cvcentroid`) | 3 each |
| ... a segment stood at its end (`cvmid`) | 10 |
| ... `[` takes the first smaller place (`cvprev`), the ends wrap (`cvwrap`), Escape leaves the light on (`cvclear`, `cvend`), Page Up steps forward (`cvpageup`) | 1 each |
| ... Page Down steps back (`cvpage`) | 0 at first (only the flat picture's check pressed Page keys); **3** after |
| ... Shift ignored by the second Enter (`cvshift`) | 0 on the axes page, which has no shift check; **1** on the 3-D page |
| ... a pointer move waiting for its frame runs after the key and puts the light out (`cvframe`) | 1 (0 before the check was written) |
| keyboard on a flat panel: sorted by y first (`kbsort`) | 15 |
| ... Home and End swapped (`kbends`) | 10 |
| ... the points under the margin stepped to when zoomed (`kbinside`), `[` (`kbprev`), Page Down (`kbpage`), the light left on after Escape (`kbleave`), one of two items at a place lights (`kbtwin`) | 1 each |
| ... the ends wrap (`kbwrap`) | 2 |

Scala side: both mutants that ignore the text scale in the axes' margins fail the "make room for the numbers as the text grows"
example (after it was strengthened to read the two axis lines' positions); a canvas that does not take focus fails the
"take focus and the keyboard on a canvas that chooses by rectangle" example. `testFull` found three examples that pinned the old
state (a 3-D `Follows` refused, the canvas script's first `let` line); they now say what is true.

Traps met, so the next session need not:

- The spy counted the axes' own lines as items (`kinds`, the touch check's `extent`): the context's `font` is the tell
  (`ink` on a recorded shape), and the still's counts skip `.tda4j-axes line`.
- A hover from the mouse's last move lights its own point after the first key press (a `requestAnimationFrame` later): the
  keyboard checks wait 120 ms after the last mouse action.
- A rectangle whose corner sits exactly on an item (the keyboard puts the corner on the item's middle) decides by float noise
  at the border: the oracles pick rectangles that do not depend on it, and the mouse route is compared with a rectangle two
  pixels larger all round.
- A Gallery page with only 3 places on a canvas (the 3-D box page) needs the framing anchors left out of the oracle: they are
  the 1.5 px discs, the pickable points are 4 px.
- `placesOf` stepping uses the middle of an element's bounding box: the checks compute the same from the DOM, never from the
  script's own list.

Default output: every `Gallery` SVG of the previous commit (`3a604d24`, built in a worktree, ids normalised) is byte-identical
(86 files), except `interactive-large-still.svg`, which now has axes. The HTML pages that carry a canvas differ by the new
script (`bc`/`box` variables, `inTri` factored out, `idle()` for the cursor): a plain 3-D scene page (`sphere.html`) differs
only by those refactorings, which compute the same numbers.

Facades: no new option. `Plot.filtration`'s canvas has its axes in every facade (MATLAB, the CLI, Python's live page, JS);
`Scene.withAxes` and `Panel.Spatial.brushable` are Scala (a MATLAB user calls `withAxes()` on a scene as it is).

Still not checked: a real touch screen, Safari proper and phones (#341), and the keyboard route in Firefox and WebKit is the
page check's, not a person's.

## Addendum (2026-11-15): #344, the canvas's axes take the figure's grammar, and a key costs one redraw

Two items, both from the first review of the flat canvas's axes (#342's addendum above).

### The keyboard's cost (item 2)

The issue said a key on a brushable canvas costs about 2.5 redraws and guessed at the cost of recomputing every place per key
(`placesOf`, sorted). **The guess was wrong.** A scan instead of the sort (scratch pages, `]` on 20k and 200k points) was within
noise: about 12% at 200k, not confirmed, not kept. Counting the surface painted at the start of a redraw (a `fillRect` on the
spy) showed two redraws per `]`: `lightUp` drew, then the key handler drew again. The fix is the `afterwards(f)` wrapper of
`stepTo`, `letGo` and the two `Enter`/`Escape` cases: it runs `f` and redraws only if `f` did not.

Measured, one Chromium process per page, an idle machine except the first rows of the "before" runs (so those are upper bounds):

| points | before, per `]` | after | one redraw alone |
|---|---|---|---|
| 20,000 | 190-240 ms | about 105 ms | about 90 ms |
| 200,000 | 2.3-2.4 s | about 1.2 s | about 1.05 s |

The cost of a key is now the redraw. Caching the places until the view changes (the issue's other "done") was not written: at
200k the stepping itself is about 12% of a key and within noise of a scan; a cache is code for an effect nobody measured.

A race the Firefox and WebKit runs found while the check was written: a pointer move waiting for its animation frame cleared the
light a key had just lit (WebKit ran the frame after the key). `lightUp` now cancels the pending frame; the check dispatches a
pointer move and a `]` in one task (mutant `cvframe`). The check also counts redraws (`__draws`): a key that steps, jumps or
lets go costs exactly one.

### Labels, gridlines and scales on a canvas (item 1)

Done is "a `Scale`/`Labels` tweak applies to a canvas as it does to a `Figure`, the still stays the reference". It does:
`Scene.xAxis`/`yAxis` (`Axis`: `label`, `transform`, `grid`), `Labels(x, y)`, `Scale.x/y/xy(transform, grid)`, `Lines.grid`, the
chained `withXLabel`/`withYLabel`/`withXScale`/`withYScale`/`withXYScale` (and `withXScale("log")` for MATLAB). Decisions:

- **The data pass through the transforms in one place** (`Scene.place`: `allPoints`, `fitting`, `viewer`, the canvas's position
  table `at`). The picture is isotropic in transformed units, as a figure on a log axis is not (a figure draws two scales); a
  scene has one camera, and `0.46 * min(box)` fits both axes.
- **Gridlines are on by default**, as a figure's (`Style.grid`, `Elements.gridStroke`). This changes the default output of
  `withAxes()` and of the large `Plot.filtration` canvas (the SVG route under 20,000 cells was already a figure with a grid); it is
  listed in the changelog. `Scale.x(grid = false)`, `Style(grid = false)` and `Lines.grid(visible = false)` remove them.
- **Refused, with a message** (#345): the other `Scale` options (a canvas's range is its view), `Transform.Custom` (no ticks the
  page can draw), and `Follows` on transformed axes (the control's value is in data units, the position table after the
  transform).
- `Labels.onScene` took the x and y words out of the "no such label" list; a scene without axes still refuses them, naming
  `withAxes()`.
- The page shares the tick rules with the zoomable figures: `Scene.tickScript` now holds `tr`, `inv`, `logLabel`, `logTicks` and
  `trTicks` as well as `numberText` and `niceTicks`; `Interactive.runtime` drops its own copies, so the existing `zoomaxes-*` checks
  (thirty windows on log, sqrt, symlog) cover the canvas's tick rules too.

Traps met:

- **A stray quote in the grid group's markup** (`stroke-width="1"">`) made the whole still invalid XML: `DOMParser` returned an
  error document, and the check reported that the still held no triangles, not that it was malformed. The spec now counts the
  quotes of every tag (`wellQuoted`).
- **A still and its canvas share a plot box only when the scene has a title.** The still holds a title inside its height (top
  margin `16 + 24 * ts`), the page puts it above the canvas (canvas height `height - 40`); an untitled still keeps 12 px, so the boxes
  differ by 12 px and every mark fraction differs with them. The two new Gallery pages have titles.
- The spy tells axis lines from items by the font in force; the grid's lines had none until `paintGrid` set `g.font`, so they were
  counted as items (mutant `gridfont`).
- The y label is drawn at the origin of a turned context: the spy records the context's transform with each text
  (`turn`), and a label reads upwards when `b < 0` (counter-clockwise), so a quarter turn the wrong way fails (`labelturn`).

### Checks

`check-interactive-page.mjs`: two new Gallery pages, `interactive-axes-log` (log-log, labels "birth" and "death") and
`interactive-axes-scales` (square root over symmetric log with a stretch of 2, negative numbers, gridlines on x only, labels). For
each: words, marks, gridlines and labels against the still's (positions as fractions of the plot box, label sizes), each point at
the place the transformed ticks put it (the line through the first and last tick, in the transform's units), the gridlines on the
marks, and the same after a zoom, a second zoom (inside a decade on the log page: 0.005, 0.01, 0.02, 0.05...) and a pan, with
the labels and the box unmoved. The large page's block gains the grid and "no label asked for, none drawn". The keyboard block
counts redraws.

### Mutation checks (page runtime of the new pages; the number is the checks that fail)

| mutant (a change to the generated page) | checks that fail |
|---|---|
| grid lines lose their font (counted as items, `gridfont`) | 6 |
| grid lines shifted 3 px (`gridx`), cut short (`gridshort`), a y grid on an axis without one (`gridyadd`), no x grid (`gridxdrop`) | 5 each |
| grid color (`gridcolor`), width (`gridwidth`) or opacity (`gridalpha`) wrong | 0 at first; **1 each** after the pens were compared with the still's |
| the y label turned the other way (`labelturn`) | 2 (0 while the check took the absolute sign of the turn) |
| x label too high (`xlabelpos`), y label too far right (`ylabelpos`), wrong text (`xlabeltext`), wrong size (`labelsize`) | 1 each |
| x axis not log (`trlog`), y axis not log (`trlogy`), x not square root (`trsqrt`), symlog stretch 1 for 2 (`trsymlog`) | 11, 11, 11, 10 |
| symlog without its sign (`symlogsign`) | 7 |
| the signed square root without its sign (`sqrtsign`), its inverse (`invsqrt`) | 0 at first (no negative number on the square-root axis); **8 and 9** after `interactive-axes-scales` got negative x |
| the log axis written as plain numbers (`logword`) | 0 at first (no number beyond 1e-3..1e5); **1** after the births ran from 1e-4 to 1e4 |
| log ticks every decade (`logstride`) | 3 |
| a key redraws twice again (`afterwards`) | 2 |
| the symlog inverse without its stretch (`invsym`), on the scales page's copy | **0**: the window's edge falls between two ticks either way. The same text is `Scene.tickScript`, which the `zoomaxes-symlog` page also carries, and the mutant fails there (1), as `sqrtsign` does on `zoomaxes-sqrt` (1) |

Scala side (`SpatialSpec`, "the axes of a flat picture"; each mutant changes the code under test, the number is the examples that fail):
the stray quote of the grid group (2), an x or y gridline ignoring its axis's `grid` (1 each), the label's room ignoring the text scale
(1), the square root placed linearly (1), x placed linearly (1), the x label 10 px too high (1), the y label turned the other way
(1), the axis label's text not escaped (1), `<` not escaped in the script's string (1), `Follows` allowed on transformed axes (1), a
number with no place on its scale let through (1), the page told a linear x axis for a log one (1), gridlines painted although the
style has none (1), the options beyond `transform` and `grid` let through (1), the x label dropped by `Labels` (5). **Two survived
the first spec**: the x tick words written with `Svg.number` instead of `Transform.tickLabel` (the tiny-number example looked at x and y
words together, and y was right) and the same for y; the example now reads each axis's words, and both mutants fail it.

Not done, left open: #345 (the other `Scale` options on a canvas, `Follows` on transformed axes, a custom transform).

Default output: the `Gallery` stills and pages are unchanged except the three that carry axes (`interactive-large*`,
`interactive-axes-pick`), which gain gridlines, and the new pages.

