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
