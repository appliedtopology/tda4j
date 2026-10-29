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

**A leaf subpackage of the core** (`org.appliedtopology.tda4j.plot`, directory `plot/`), like `matlab`/`cli`: nothing in the
core uses it. Its one dependency is cask (Undertow and XNIO, for `view()` only; the JDK's own HTTP server crashed,
`be3f71a7`). **A plot
feature that needs a library goes in a new module**; `view()`'s cask is the standing exception (`WORKLOG-demo-jar.md`). Not
re-exported by the labs. `matlab.PersistenceResult`'s `plot...` methods (hence the CLI's `--plot`) draw through it
(`WORKLOG-facade-plots.md`).

**Public core API only.** It must not use `private[tda4j]` members: what it needs, a user's own
plotting code needs too.

**Scene model, two renderers.** `Figure` = marks in data coordinates (`Mark.Points/Segments/Polygons/Discs/Boxes/Line/
Text`, one paint or one per item, optional tooltip per item = SVG `<title>`), axes, legend, colorbar; `Svg.render` draws it.
`Scene` = 3-D layers: a canvas viewer with inline JS (no CDN) for pages, a back-to-front orthographic SVG for files.
`trait Plot` (`view`, `save`, `html`, `fragment`, `svgFile`) + `object Plot` (the TDA constructors) share the name on
purpose. Every plot is an immutable value. Representatives are `Plot.Terms` (`Chain[C, ?] | Seq[(C, Double)]`, like
`Plot.Bars`): never build a `Chain` just to plot (an invented real field's epsilon drops small coefficients).
`save(path)` exists without the default argument for Java/MATLAB. Each render numbers its `<svg>` id (`tda4jN`).
`Viewer.browse` alone opens a browser.

**Colors are roles, resolved by `Palette` x `Theme`.** `Paint.Series(k)` for homological degree `k` (never by rank),
chrome roles (`Ink`, `Muted`, `Grid`, ...), `Paint.Value(v)` for a data value that the figure's `ColorScale` (ramp, limits,
transform, stops) colors through the palette AT RENDER, `Fixed(hex)` only for a color the user chose. A mark never holds a
color computed from a palette at construction: that is what made `copy(palette = ...)` leave heatmaps behind. `Theme.Auto` = CSS variables of the `<svg>` with a
`prefers-color-scheme` block (pages, viewer); `Light`/`Dark` = plain hex (files; `.svg` saves default to `Light`).
`Palette.brand` (default, Teal & Gold from `brand/DECISIONS.md`) is checked with the dataviz validator: slots 0-2 pass ALL
pairs in both modes (a diagram puts any two degrees side by side), 0-7 adjacent pairs. Re-run the validator after changing any slot; never cycle hues past 8. Degree also gets a marker shape.

**Live viewer** (`Viewer`): one page listing every `view()`ed plot (iframe per plot, `/list`, `/plot/<n>`, server-sent events
on `/events`), browser opened via `java.awt.Desktop` when there is a desktop, else the URL printed
(`tda4j.plot.browser=none`/`TDA4J_PLOT_BROWSER=none`: never; `PlotSpec` sets it, or `sbt testFull` would open a tab). The
server starts FROM a daemon thread with a daemon XNIO worker, so it never keeps a REPL alive; `PlotSpec` reads an event with
a 10 s deadline (a lost event must fail, not hang CI). A plot prints as one line (`Figure("Barcode", 50 marks)`), because a
REPL echoes `fig.view()`; keep it so on a new plot kind.

**Look at what you draw.** `src/test/.../plot/Gallery.scala` writes every kind of plot and the customizations (`sbt
"Test/runMain org.appliedtopology.tda4j.plot.Gallery <dir>"`). Screenshot with node Playwright
(`/opt/node-tools/node_modules/playwright`, `executablePath: '/opt/pw-browsers/chromium'`; the SVG file opens by `file://`,
or put its text in a page and take an element screenshot) and read the PNGs. Specs check structure, not looks. Chromium's
accessibility tree, a canvas drag and text widths can be checked there (`WORKLOG-plot-issues-sweep.md`); Firefox, Safari
and MATLAB cannot.

**Docs fences in `_docs/user-guide/plotting.md` are compiled** like every fence (each self-contained); `PlotSpec` and the
gallery run the same calls.

**The grammar** (`Tweak.scala`, `Style.scala`, `ColorScale.scala`, `Transform.scala`; `WORKLOG-plot-grammar.md`). A plot
constructor chooses WHAT is drawn (data, statistic, marks); everything else is a `Tweak` added with `+`, never a new
constructor parameter: `Labels`, `Size`, `Style`, `Palette` and `Scale.x/y/xy/color/degree`. The primary Scala surface is
the chain of typed `with...` methods on `Figure` (and `Scene`), each `this + <tweak>`; the tweaks stay the values for a look
shared by many plots, for boards and for scenes (project lead: chaining is close enough to the grammar, `+` need not be the
API). Rules:
- Positions pass through an axis's `Transform` in `Svg.render` ONLY; marks hold data in its own units. Every value has a
  place (project lead: a negative filtration value is reasonable, and a limit of the log transform is not a reason to refuse
  data): 0 on a log axis is a `-∞` line, a log axis with a negative value is drawn as a symmetric log and says so in small
  print (only negative DATA switches it), the square root is signed. Only a non-number is left out, and COUNTED in small
  print. A tweak that needs a place on the axis (`Board.shared`) goes through `Svg.resolved`, what an axis is drawn with.
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
  cream, and `Palette.print` on `#ffffff` needed different ones. `Palette.print`/`Style.print` are the look for a paper
  (white, black, named fonts), `Palette.clear`/`Style.clear` for a figure that takes its page's color (no background);
  `PlotGrammarSpec` pins the contrast of their inks and series. A preset reachable from Scala is reachable by name from
  MATLAB and the CLI (`Palette.named`, `Style.named`, `withPalette(name)`, `--palette`, `--style`).
- **The background depends on where the figure goes** (project lead, #253; `WORKLOG-brand-gold.md`): `Style.transparent` is
  unset/`true`/`false`. Unset draws the surface on a page (`svg`, `fragment`, `html`, the viewer) and none in a file
  (`svgFile`: `save` of an `.svg`, MATLAB, the CLI, JS `svg()`), by `Svg.render(fig, theme, saved)` and
  `Style.hasBackground(saved)`, which a board and a scene use for panels, heading and own rectangle. `Style.print` is `false`
  (a paper keeps its white). Code that writes a file passes `saved = true`.
- **Finer-grained look lives in `Figure.elements`** (`Elements.scala`: text per `TextRole`, `LineStyle`s, `TickStyle`s, a
  panel fill; tweaks in `ElementTweaks.scala`: `Text`, `Lines`, `Guide`, `Settings`), beside the style so that tweaks of both
  commute. A layout constant in `Svg.render` is the old constant times `sc(role)`, never a new constant, so a role at its
  default gives the old number: default output is a contract: after any renderer change, diff the `Gallery` SVGs against a
  `git worktree` build of the previous commit (ids normalized, `tda4j[0-9]+`) and explain every difference. A new element needs its tweak, a typed `with...` method, a key in `Settings` (the one
  string grammar for MATLAB and `--set`), a spec that makes the plausible wrong layout fail (a margin that did not follow the
  size), and a mutation check.
- **A marker's look is `MarkerStyle` layers** (`Markers.scala`), resolved in one place (`MarkerStyle.resolve`) for plots,
  legend keys and scenes; never read a shape off a mark directly. A new shape is a case of `Shape`, a drawing in
  `Svg.marker`, and an entry in `MarkerStyle.named`. The first four cases of `Shape` are the degree shapes, in order.
- **Axes and numbers** (`WORKLOG-plot-grammar.md` decision 14): a reversed axis lives in the `Frame` (`revX`/`revY`), so a
  new mark that covers a half plane draws in the usual frame and mirrors back, as the diagonal's shade does; `Figure.aspect`
  beats the equal-aspect default; numbers pass through `Elements.numbers` where they are written (never format a number
  with a symbol of your own); text is cut by `TextFace.fit` and a legend sized by `TextWidth` (Helvetica and
  Times advance widths measured from the Liberation faces: exact for those, Inter about 6% wider; add a character by
  measuring it, never by a factor; say "estimated" in docs). `Numbers.locale` takes the
  separators of the Latin digits (`nu=latn`, `numberingSystem: "latn"`). `Settings.parse` trims values, so a blank symbol
  is `space` or kept raw.
- **Legend box and axis crossing** (`WORKLOG-plot-issues-sweep.md`). `Elements.legendBox` draws nothing unless a fill or a
  frame is asked for; its padding scales with the legend text, columns are given up until they fit, and the room beside the
  plot includes the box. `Axis.crossesAt` is in the units of the OTHER axis (`Scale.x(crossesAt = 0)` is the x axis through
  `y = 0`), kept only when that axis's range BEFORE the equal-aspect widening shows the value (else the axis stays at the
  edge and a note says why). `Palette.grayscale` is checked by lightness and contrast in specs (the validator's chroma floor
  fails any gray); `Palette.okabeIto` is the canonical eight on white, first three validated on all pairs. A diagram's
  `Board.heading` and a color bar's `barTicks` are drawn in the renderer, not the constructors; `plotRepresentative(i)` draws
  a cocycle at its bar's birth.
- PNG and PDF are documented external conversion only (project lead, #101): no rasterizer module; interactivity (#102) is not
  wanted. A command in that section is run before it is written down (`cairosvg` and Chromium were; the others come from manuals).
- **`Look` (session defaults)**: every public constructor of `Plot` that returns a `Figure` or `Scene` takes `(using look:
  Look)`; one that builds the plot wraps its body in `look.figure(palette):` / `look.scene:`, one that delegates passes the
  given on, and a call to a public constructor inside a wrapped body passes `Look.none` (applied once). A named `palette` is
  an `Optional`, re-applied after the look. Extend the look with `given Look = summon[Look] + tweaks` in an INNER scope (same
  scope is an ambiguity error). Add every new constructor to the table in `PlotLookSpec`: compiling cannot catch one that
  drops the look. MATLAB's session look is per thread (`TDA4j.setPlotDefaults`), read through a given taking a context
  parameter, so it is a method, not a cached value.
- A plot's own default (a diagram's legend place) goes in the `Figure`, never in the `Style`, which a user replaces whole.

**Specs for plots**: `PlotGrammarSpec` examples are built so the plausible wrong implementation fails them, and were
mutation-checked (break the code, see the example fail; count errors as well as failures). specs2 traps: `rules/testing.md`.

