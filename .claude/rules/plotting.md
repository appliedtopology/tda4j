---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/plot/**"
  - "src/test/scala/org/appliedtopology/tda4j/plot/**"
  - "_docs/user-guide/plotting.md"
  - "_docs/developers-guide/plotting.md"
---

# The `plot` subpackage

Loads when you work in the `plot` subpackage. Project-wide rules are in `.claude/CLAUDE.md`. Platform choice and its
evidence: `DESIGN-plotting.md`; the build-out: `WORKLOG-plotting.md`; the move into the core: `WORKLOG-plot-into-core.md`;
the customization grammar (tweaks, scales, styles): `WORKLOG-plot-grammar.md`.

**A leaf subpackage of the core** (`org.appliedtopology.tda4j.plot`, directory `plot/` in the core's source and test
trees), like `matlab`/`cli`: nothing in the core uses it. Its one dependency is cask (Undertow and XNIO, for `view()`
only; SVG strings and `java.awt.Desktop` need none): the JDK's own HTTP server crashed (`be3f71a7`). It was its own sbt
project and artifact (`tda4j-plot`, never released); the project lead folded it in because it isolated no dependency and
kept the docs build from compiling its fences. **If a plot feature ever needs a library, it goes in a new module**, not
in the core -- `view()`'s cask is the standing exception, and whether the viewer should move out is open
(`WORKLOG-demo-jar.md`). Not
re-exported by the labs (`tdalab-exports.py` scans only the root package and `sset`). `matlab.PersistenceResult`'s
`plot...` methods (hence the CLI's `--plot`) draw through it: `matlab` -> `plot` is the one edge between leaves
(`WORKLOG-facade-plots.md`).

**Public core API only.** The subpackage could see `private[tda4j]` members; it must not -- what it
needs, a user's own plotting code needs too (it needed `CubicalGridStream.topValues`, which the openness audit added).

**Scene model, two renderers.** `Figure` = marks in data coordinates (`Mark.Points/Segments/Polygons/Discs/Boxes/Line/
Text`, one paint or one per item, optional tooltip per item = SVG `<title>`), axes, legend, colorbar; `Svg.render` draws it.
`Scene` = 3-D layers: a canvas viewer with inline JS (no CDN) for pages, a back-to-front orthographic SVG for files.
`trait Plot` (`view`, `save`, `html`, `fragment`, `svgFile`) + `object Plot` (the TDA constructors) share the name on
purpose. Every plot is an immutable value. Representatives are `Plot.Terms` (`Chain[C, ?] | Seq[(C, Double)]`, like
`Plot.Bars`): never build a `Chain` just to plot (an invented real field's epsilon drops small coefficients).
`save(path)` exists without the default argument for Java/MATLAB. Each render numbers its `<svg>` id (`tda4jN`):
compare two renders after replacing `tda4j[0-9]+`. `Viewer.browse` is the one place that opens a browser.

**Colors are roles, resolved by `Palette` x `Theme`.** `Paint.Series(k)` for homological degree `k` (never by rank),
chrome roles (`Ink`, `Muted`, `Grid`, ...), `Paint.Value(v)` for a data value that the figure's `ColorScale` (ramp, limits,
transform, stops) colors through the palette AT RENDER, `Fixed(hex)` only for a color the user chose. A mark never holds a
color computed from a palette at construction: that is what made `copy(palette = ...)` leave heatmaps behind. `Theme.Auto` = CSS variables of the `<svg>` with a
`prefers-color-scheme` block (pages, viewer); `Light`/`Dark` = plain hex (files; `.svg` saves default to `Light`).
`Palette.brand` (default, Slate & Gold from `brand/DECISIONS.md`) was searched in OKLCH around the brand hues and checked
with the dataviz validator: slots 0-2 pass ALL pairs in both modes (a diagram puts any two degrees side by side), 0-7
adjacent pairs. Re-run the validator after changing any slot; never cycle hues past 8. Degree also gets a marker shape.

**Live viewer** (`Viewer`): one page listing every `view()`ed plot (iframe per plot, `/list`, `/plot/<n>`, server-sent
events on `/events`), port 8337 or a free one, browser opened via `java.awt.Desktop` when there is a desktop, else the URL
printed (`tda4j.plot.browser=none`/`TDA4J_PLOT_BROWSER=none`: never; `PlotSpec` sets the property, or `sbt testFull`
would open a tab on a desktop machine). The server is started FROM a daemon thread and its XNIO worker is built daemon, so
it never keeps a REPL alive; `PlotSpec` checks the threads, the list, a page and an event (read with a 10 s deadline: a
lost event must fail, not hang CI). A plot prints as one line (`Figure("Barcode", 50 marks)`), because a REPL echoes the
value of `fig.view()`; keep it so on a new plot kind.

**Look at what you draw.** `src/test/.../plot/Gallery.scala` writes every kind of plot and the customizations (`sbt
"Test/runMain org.appliedtopology.tda4j.plot.Gallery <dir>"`); screenshot through Playwright
(`/opt/node22/lib/node_modules/playwright`) with its DEFAULT headless shell, putting the SVG text in a page and taking an
element screenshot (`page.setContent(svg)`, `(await page.$('svg')).screenshot(...)`) and read the PNGs. Opening the `.svg`
file with `executablePath: .../chromium-1194/chrome-linux/chrome` and `page.screenshot` timed out in the 2026-10 container. Specs check structure (one tooltip per bar/cell, well-formed XML, theme colors), not looks.

**Docs fences in `_docs/user-guide/plotting.md` are compiled** like every fence (each self-contained: imports and data);
`PlotSpec` and the gallery run the same calls.

**The grammar** (`Tweak.scala`, `Style.scala`, `ColorScale.scala`, `Transform.scala`; `WORKLOG-plot-grammar.md`). A plot
constructor chooses WHAT is drawn (data, statistic, marks); everything else is a `Tweak` added with `+`, never a new
constructor parameter: `Labels`, `Size`, `Style`, `Palette` and `Scale.x/y/xy/color/degree`. The primary Scala surface is
the chain of typed `with...` methods on `Figure` (and `Scene`), each `this + <tweak>`; the tweaks stay the values for a look
shared by many plots, for boards and for scenes (project lead: chaining is close enough to the grammar, `+` need not be the
API). Rules:
- Positions pass through an axis's `Transform` in `Svg.render` ONLY; marks hold data in its own units. Every value has a
  place (project lead: a negative filtration value is perfectly reasonable, and a limit of the log transform is not a reason
  to refuse data): 0 on a log axis is a `-∞` line, a log axis with a negative value is drawn as a symmetric log and says so in
  small print (only NEGATIVE DATA switches it, never a limit: plots pad their own lower bound below zero), the square root
  is signed. Only a non-number is left out, and COUNTED in small print, never dropped silently. A tweak that needs a place on
  the axis (`Board.shared`) must go through `Svg.resolved`, which is what an axis is actually drawn with.
- Tweaks of different parts commute (a spec checks it); the same part, later wins; a `Style` replaces the whole style (copy
  a preset to change one setting); `Scale.degree` overrides live beside the palette, not in it, so palette order never
  matters. A tweak with nothing to change on a plot is a no-op; one that cannot apply (an axis of a `Scene`) throws a
  message that names the tweak.
- Constructors must not bake in anything a tweak sets: no linear tick lists (use `Ticks.Within` / `extra`), no ramp colors,
  no data-space geometry that is wrong on a curved axis (the diagonal is `Mark.Diagonal`, drawn in the plot's scales).
- A new tweak gets a typed `with...` method on `Figure` (and `Scene` where it applies), a plain-argument overload of it for
  MATLAB and Java, which have no named or default arguments and cannot build an `Optional`
  (`PlotCustomizationFacadeSpec` lists the names and requires an overload taking only `String`, `int`, `double`), and a CLI
  flag checked in `checkPlotFlags` before anything is computed. `Plot` (the trait) has no `+`: an abstract member would break
  anyone implementing it.
- **A palette is validated on its OWN surface** (`validate_palette.py --surface <hex>`): the brand slots were checked on
  cream, and `Palette.print` on `#ffffff` needed different ones. `Palette.print` (white, black, one look for both modes) and
  `Style.print` (transparent, named fonts) are the look for a paper; `PlotGrammarSpec` pins the contrast of their inks and
  series against the surface. A preset reachable from the Scala API is reachable by name from MATLAB and the CLI
  (`Palette.named`, `Style.named`, `withPalette(name)`, `withStyle(name)`, `--palette`, `--style`).
- Default output is a contract: look at `Gallery` before and after a renderer change and diff the SVGs against a
  `git worktree` build of the old commit (ids normalized: `tda4j[0-9]+`); every difference must be explained.

**Specs for plots**: `PlotGrammarSpec` examples are built so the plausible wrong implementation fails them, and were
mutation-checked (break the code, see the example fail). **specs2 trap**: in a non-final statement `(a must X).and(b must
Y)` checks only `a`, the by-name `b` is never forced; write separate `must` statements.

