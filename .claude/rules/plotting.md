---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/plot/**"
  - "src/test/scala/org/appliedtopology/tda4j/plot/**"
  - "_docs/user-guide/plotting.md"
  - "_docs/developers-guide/plotting.md"
---

# The `plot` subpackage

Loads when you work in the `plot` subpackage. Project-wide rules: `.claude/CLAUDE.md`. Platform choice and its
evidence: `DESIGN-plotting.md`; the build-out: `WORKLOG-plotting.md`; the move into the core: `WORKLOG-plot-into-core.md`;
the customization grammar (tweaks, scales, styles): `WORKLOG-plot-grammar.md`; interactive pages and the video recipe:
`WORKLOG-interactive-plots.md`.

**A leaf subpackage of the core** (`org.appliedtopology.tda4j.plot`, directory `plot/`), like `matlab`/`cli`: nothing in the
core uses it. **A plot feature that needs a library goes in a new module**; the one dependency, cask (Undertow and
XNIO, for `view()` only), is the standing exception (`WORKLOG-demo-jar.md`). Not
re-exported by the labs. `matlab.PersistenceResult`'s `plot...` methods (hence the CLI's `--plot`) draw through it
(`WORKLOG-facade-plots.md`).

**Public core API only.** No `private[tda4j]` members: what it needs, a user's own plotting code needs too.

**Scene model, two renderers.** `Figure` = marks in data coordinates (`Mark.Points/Segments/Polygons/Discs/Boxes/Line/
Text`, one paint or one per item, optional tooltip per item = SVG `<title>`), axes, legend, colorbar; `Svg.render` draws it.
`Scene` = 3-D layers: a canvas viewer with inline JS (no CDN) for pages, a back-to-front orthographic SVG for files.
`trait Plot` (`view`, `save`, `html`, `fragment`, `svgFile`) + `object Plot` (the TDA constructors) share the name on
purpose. Every plot is an immutable value. Representatives are `Plot.Terms` (`Chain[C, ?] | Seq[(C, Double)]`, like
`Plot.Bars`): never build a `Chain` just to plot (an invented real field's epsilon drops small coefficients).
`save(path)` exists without the default argument for Java/MATLAB. Each render numbers its `<svg>` id (`tda4jN`).
Only `Viewer.browse` opens a browser.

**Colors are roles, resolved by `Palette` x `Theme`** (the developer's guide has the roles). `Paint.Series(k)` is homological
degree `k` (never its rank); `Paint.Value(v)` is colored by the figure's `ColorScale` through the palette AT RENDER, and `Fixed(hex)`
only for a color the user chose: a mark never holds a color computed from a palette at construction (`copy(palette = ...)` would
leave heatmaps behind). `Theme.Auto` writes CSS variables with a `prefers-color-scheme` block (pages, viewer); `Light`/`Dark` plain
hex (files; `.svg` saves default to `Light`). `Palette.brand` (default, `brand/DECISIONS.md`) is checked with the dataviz validator:
slots 0-2 pass ALL pairs in both modes, 0-7 adjacent pairs; re-run it after changing a slot, never cycle hues past 8.

**Live viewer** (`Viewer`): one page listing every `view()`ed plot (iframe per plot, `/list`, `/plot/<n>`, server-sent events
on `/events`), on `127.0.0.1` unless `tda4j.plot.host` names another address (a container); browser opened via
`java.awt.Desktop` when there is a desktop, else the URL printed (`tda4j.plot.browser=none`: never; `PlotSpec` sets it, or
`testFull` opens a tab). The server starts FROM a daemon thread with a daemon XNIO worker, so it never keeps a
REPL alive; `PlotSpec` reads an event with a 10 s deadline (a lost event must fail, not hang CI).
`Plot.viewWith(target)(body)` sends the block's `view()`s to `target` instead (the demo's web page); a host must run
user code on the copy of the library's classes that set the target (`WORKLOG-web-playground-build.md`).
A plot prints as one line (`Figure("Barcode", 50 marks)`), because a REPL echoes `fig.view()`; a new plot kind too.

**Look at what you draw.** `src/test/.../plot/Gallery.scala` writes every kind of plot and the customizations (`sbt
"Test/runMain org.appliedtopology.tda4j.plot.Gallery <dir>"`). Screenshot with node Playwright (`require("playwright")`
works anywhere in a cloud session; the SVG file opens by `file://`, or put its text in a page and take an element
screenshot) and read the PNGs. Specs check structure, not looks. Chromium's
accessibility tree, a canvas drag and text widths can be checked there (`WORKLOG-plot-issues-sweep.md`); Firefox, Safari
and MATLAB cannot (#98, #330).

**Docs fences in `_docs/user-guide/plotting.md` are compiled**, each alone; `PlotSpec` and the gallery run the same calls.

**The grammar** (`Tweak.scala`, `Style.scala`, `ColorScale.scala`, `Transform.scala`; `WORKLOG-plot-grammar.md`). A plot
constructor chooses WHAT is drawn (data, statistic, marks); everything else is a `Tweak` added with `+`, never a new
constructor parameter: `Labels`, `Size`, `Style`, `Palette` and `Scale.x/y/xy/color/degree`. The primary Scala surface is
the chain of typed `with...` methods on `Figure` (and `Scene`), each `this + <tweak>`; the tweaks stay the values for a look
shared by many plots, boards and scenes (project lead: chaining is close enough to the grammar). Rules:
- Positions pass through an axis's `Transform` in `Svg.render` ONLY; marks hold data in its own units. Every value has a
  place (project lead: a limit of the log transform is no reason to refuse data): 0 on a log axis is a `-∞` line, negative
  DATA on a log axis is drawn as a symmetric log and says so in small print, the square root is signed; only a non-number is
  left out, and COUNTED. A tweak that needs a place on the axis (`Board.shared`) goes through `Svg.resolved`.
- Tweaks of different parts commute (a spec checks it); the same part, later wins; a `Style` replaces the whole style (copy
  a preset to change one setting); `Scale.degree` overrides live beside the palette, so palette order never matters. A tweak
  with nothing to change is a no-op; one that cannot apply (an axis of a `Scene`) throws a message that names the tweak.
- Constructors must not bake in anything a tweak sets: no linear tick lists (use `Ticks.Within` / `extra`), no ramp colors,
  no data-space geometry that is wrong on a curved axis (the diagonal is `Mark.Diagonal`, drawn in the plot's scales).
- A new tweak gets a typed `with...` method on `Figure` (and `Scene` where it applies), a plain-argument overload for MATLAB
  and Java (no named or default arguments, no `Optional`; `PlotCustomizationFacadeSpec` lists the names and requires an
  overload taking only `String`, `int`, `double`), and a CLI flag checked in `checkPlotFlags` before anything is computed.
  `Plot` (the trait) has no `+`: an abstract member would break anyone implementing it.
- **A palette is validated on its OWN surface** (`validate_palette.py --surface <hex>`): `Palette.print` on `#ffffff` needed
  other slots than the brand's on cream. `Palette.print`/`Style.print` are the look for a paper, `Palette.clear`/`Style.clear`
  for a figure that takes its page's color (`PlotGrammarSpec` pins their contrast). A preset reachable from Scala is
  reachable by name from MATLAB and the CLI (`Palette.named`, `Style.named`, `--palette`, `--style`).
- **The background depends on where the figure goes** (#253; `WORKLOG-brand-gold.md`): `Style.transparent` is
  unset/`true`/`false`. Unset draws the surface on a page (`svg`, `fragment`, `html`, the viewer) and none in a file
  (`svgFile`), by `Svg.render(fig, theme, saved)` and `Style.hasBackground(saved)`; `Style.print` is `false`. Code that
  writes a file passes `saved = true`.
- **Finer-grained look lives in `Figure.elements`** (`Elements.scala`: text per `TextRole`, `LineStyle`s, `TickStyle`s, a
  panel fill; tweaks in `ElementTweaks.scala`), beside the style so that tweaks of both commute. A layout constant in
  `Svg.render` is the old constant times `sc(role)`, never a new constant. **Default output is a contract**: after any
  renderer change, diff the `Gallery` SVGs against a `git worktree` build of the previous commit (ids normalized,
  `tda4j[0-9]+`) and explain every difference. A new element needs its tweak, a typed `with...` method, a `Settings` key (the
  one string grammar for MATLAB and `--set`), a spec the plausible wrong layout fails, and a mutation check.
- **A marker's look is `MarkerStyle` layers** (`Markers.scala`), resolved in one place (`MarkerStyle.resolve`); never read a
  shape off a mark directly. A new shape is a case of `Shape`, a drawing in `Svg.marker`, and an entry in
  `MarkerStyle.named`; the first four cases are the degree shapes, in order.
- **Axes and numbers** (`WORKLOG-plot-grammar.md` decision 14): a reversed axis lives in the `Frame` (`revX`/`revY`);
  `Figure.aspect` beats the equal-aspect default; numbers pass through `Elements.numbers` where written (never format one
  with a symbol of your own); text is cut by `TextFace.fit`, a legend sized by `TextWidth` (measured advance widths: exact
  for Helvetica and Times, Inter about 6% wider; say "estimated" in docs). `Settings.parse` trims values.
- **Legend box and axis crossing** (`WORKLOG-plot-issues-sweep.md`): `Elements.legendBox` draws nothing unless a fill or frame
  is asked for; `Axis.crossesAt` is in the units of the OTHER axis, kept only when that axis's range BEFORE the equal-aspect
  widening shows it. `Board.heading` and `barTicks` are drawn in the renderer.
- PNG and PDF are documented external conversion only (project lead, #101): no rasterizer module. A command in the docs is
  run before it is written down.
- **Interactive pages** (`Interactive.scala`, #102, `WORKLOG-interactive-plots.md`): `Control`s and `Panel`s (a `Figure` or
  `Scene` with a `Reaction` per mark item; `Behavior` = `Shown | Follows | Radius | Custom | Selects | Selected`), chained like
  the tweaks. **The still is the reference semantics**: `at(PageState)`/`frames`/`saveFrames` apply the behaviors in Scala
  (`Shown` is `from <= v < until`; axes pinned to the full figure; `PageState` also holds chosen keys and zoom windows); the
  page script (`Interactive.runtime`) implements the same rules, and `.claude/scripts/check-interactive-page.mjs` compares the
  two in Chromium (run it after touching either, on a fresh `Gallery`; not in CI, #330). The renderers' only hook is
  `Svg.Tags` (`data-ia` per item, `data-fr` per `<svg>`, with `zoom`: `data-z`/`data-c`/`data-k`/`data-zm`) and a `Scene`
  layer's `ia`; without tags the output is byte-identical (Gallery diff). A page's ids are `tda4jia-<run nonce>-<n>`, every id
  inside derives from it, and panels are `div.tda4j-panel`s that shrink to the room. The script knows identity/log/sqrt/symlog
  only. A new behavior is a case, a rule in `inside`/`follow`, a `Tags` encoding, a `reactors` entry and a spec that makes `<`
  for `<=` and a missing clamp fail.
- **Selections, brushing, zoom** (`Selection.scala`, `Zoom.scala`): a selection is a set of `Int` keys (the position of a bar in
  the list given); `Panel.selecting` adds a halo mark per item; hover, clicks and brush rectangles are page-only, clamped to the
  plot area when zoomed. Zoom moves stroke groups by `matrix()` (non-scaling stroke) and points by `translate`, and redraws axes
  with the script's copies of `Svg.niceTicks`/`Svg.number` (change either: change both; the 60-window words check catches a
  drift); only linear, forward, unformatted axes (`Interactive.zoomProblem`); an equal-aspect still widens a window as the page
  does. Page-versus-still comparisons hold for panels of equal size only (a board still resizes the barcode). The facades hold
  cycles as arrays: `Plot.exploreOn(.., cycles = ..)`. Traps and mutation checks: the worklog addendum 2026-11-12.
- **`Look` (session defaults)**: every public `Plot` constructor that returns a `Figure` or `Scene` takes `(using look: Look)`;
  one that builds the plot wraps its body in `look.figure(palette):` / `look.scene:`, one that delegates passes the given on,
  and a call to a public constructor inside a wrapped body passes `Look.none` (applied once). Extend the look with `given
  Look = summon[Look] + tweaks` in an INNER scope. Add every new constructor to the table in `PlotLookSpec`: compiling cannot
  catch one that drops the look. MATLAB's session look is per thread (`TDA4j.setPlotDefaults`), so it is a method.
- A plot's own default (a diagram's legend place) goes in the `Figure`, never in the `Style`, which a user replaces whole.

**Specs for plots**: `PlotGrammarSpec` examples are built so the plausible wrong implementation fails them, and were
mutation-checked (break the code, see the example fail; count errors as well as failures). specs2 traps: `rules/testing.md`.

