# WORKLOG: customizing plots, on the grammar of graphics (2026-10-16)

Session on cloud branch `claude/plot-customization-api-horkas` (from `scala`). Point-in-time snapshot; the resulting rules are
in `rules/plotting.md`. Every decision below was taken without the project lead and says why, so it can be overturned cheaply.

## The ask (project lead, after the 2026-10-16 tutorial session)

> Customizations of the plot output. Users need a decently wide range of customizations: color schemes, dimensions,
> labels, scales, at a minimum. For API style I'm a big fan of the Grammar of Graphics approach, and some of our current
> plot API is already reminiscent of that, so I'm interested in seeing if we can lean on that paradigm.

Later in the session: rename `RingModule.scale` to `scalarMultiply` ("probably a mistake to choose scale"), fine in this
PR. Done (below).

## Can we lean on the grammar of graphics? Yes for half of it, deliberately not the other half

The grammar's parts and where each lives now:

| grammar | here |
|---|---|
| data, statistic, geometry | the constructors of `Plot` (`barcode`, `diagram`, ...) build `Figure.marks`; the statistic is TDA-specific (a barcode's row packing, the `∞` convention) and stays in them |
| layers | `Figure.marks`, `fig + mark`, `over` (already there) |
| position scales | `Axis`: `Transform` (identity, log, sqrt, symlog), `min`/`max`, tick choice, number format |
| aesthetic scales | the categorical one: `Palette` + `DegreeStyle` overrides; the continuous one: `ColorScale` (sequential, diverging, cyclic; limits, transform, stops) |
| guides | axes, legend (right, bottom, hidden; heading), color bar, derived from the scales |
| theme | `Style` (font, text scale, mark scale, grid, legend place, transparent) |
| facets | `Board`, now with `shared()` axes; no more than that |
| labels | `Labels` |
| composition | `fig + tweak`, `tweak + tweak`, a `Tweak` is a value |

**Not adopted, on purpose**: `aes()` mappings from a data frame to marks, and statistics as separate layer objects. We have
no data-frame type, the set of TDA plots is closed and each one's layout IS its statistic, and a general plotting
DSL would be a rewrite of the part users like. If users later want to plot their own tables, a small `aes` helper over
`Mark` is the next step, not a different architecture. Facets beyond `Board` likewise.

The two real defects that made customizing hard (both found by reading, not by users):
1. `Paint.Fixed(hex)` baked heatmap, cocycle and circular-coordinate colors at construction, so `fig.copy(palette = ...)`
   recolored the chrome and the degrees but left the data colors (and the color bar's closure) in the old palette. The
   palette was "a role" for half the colors. Now `Paint.Value(v)` + `ColorScale`, resolved at render.
2. The barcode and diagram built LINEAR tick lists in their constructors, and the diagram's diagonal and `∞` line as
   data-space segments, so no axis scale could work; margins, fonts and the legend's place were constants in `Svg.render`.

## Decisions

1. **Chained `with...` methods are the primary Scala surface; tweaks are the values behind them** (`Tweak.scala`). First
   cut had `+` as the one way and `with...` only for MATLAB; the project lead said chaining is close enough to the grammar and
   `+` need not be the API, so the typed methods (`withLabels`, `withXScale`, `withColorScale`, `withDegree`, `withPalette`,
   `withStyle`, ...) lead the docs, and each is `this + <tweak>` (no second implementation). `+` and `Tweak` stay for what only
   a value can do: a look kept in a `val` and added to figures, scenes and boards alike. Plain-argument overloads of the same
   names are for MATLAB (no named or default arguments, no `Optional`), as `save(path)` is the overload without its default;
   the facade spec lists the names and requires one such overload each. Overloads: only the typed one has defaults, and
   `withXScale("log")` (word) vs `withXScale(Transform.Log10)` (value) resolve by argument type (a spec pins it).
2. **Naming**: `Style` for the non-data look, NOT `Theme`: `Theme` is the existing light/dark/auto mode that `save` and `html`
   take, and renaming it would break every `Theme.Dark` in the docs. The docs say so in one sentence. Overturn by renaming
   `Theme` to `ColorMode` and `Style` to `Theme` if the project lead prefers ggplot's word.
3. **A `Style` replaces the whole style** (copy a preset to change one setting) rather than merging partial styles. Partial
   styles need an `Optional` per field and an "unset" state in the figure; the explicit `copy` is one line.
4. **`Palette` is a `Tweak`**, so `fig + Palette.reference` works and a bundle can hold one.
5. **Degree overrides sit beside the palette** (`Figure.degrees`), not inside it, so `fig + degree + palette` and
   `fig + palette + degree` agree (a spec checks both orders).
6. **Every value has a place on every scale** (first cut: a mark with no place was left out and counted; the project lead
   rejected that twice). (a) What is at exactly 0 on a log axis sits on a `-∞` line below the lowest number, 34 px clear of
   it and 10 px from the edge, labelled `-∞` as the `∞` tick is, with a line drawn across (the project lead: "a -infinity
   line for H0 to be born at, similar to the +infinity for essential bars"). It is the renderer's, not a constructor's,
   because a tweak adds the log scale after the plot was built. `Axis.minusInfinity`: `None` automatic, `Some(false)` never
   (zeros left out and counted), `Some(true)` always (`Board.shared` sets it on every panel when one has the line, so the
   numbers line up). (b) Negative values ("a negative filtration value is perfectly reasonable"): a log axis whose MARKS
   have a negative value is drawn as `SymLog(c)`, `c` the smallest nonzero magnitude on the axis, and the small print says
   so; two axes that were both log and both switched share the smaller `c`, so a diagram's diagonal stays a line and equal
   aspect applies; `Board.shared` gives every panel the same `SymLog`. A LIMIT alone never switches: the barcode pads its
   own lower bound below zero, and a first cut that switched on it drew a barcode of non-negative births as a symmetric
   log. The alternative, a log of the magnitude mirrored about a zero line in the middle, is the more literal log but needs
   a data-dependent piecewise layout (ticks, diagonal, shared panels); `SymLog` is the standard signed answer and
   reuses everything. (c) The square root is `sign(v) sqrt|v|`. (d) A log color scale starting at 0 or below is a symmetric
   log (`c` a thousandth of the largest magnitude). Only a value that is not a number is left out, and counted. Overturn
   (b) by replacing `resolve` in `Svg.scala`.
7. **The diagram's diagonal and `∞` line are marks drawn by the renderer** (`Mark.Diagonal`, `Mark.Rule`), sampled along each
   axis when the axes differ in scale, so `y = x` stays `y = x` on a log-log diagram (spec: it passes through `(v, v)` and is
   straight; with only x logarithmic it is bent and still passes through `(v, v)`). `Rule`/`Diagonal` also serve as
   threshold lines for users, and do not take part in choosing the range.
8. **`Board.shared()`** unions the panels' ranges; an `∞` tick that marks a panel's own upper end moves to the shared one
   (found by looking at the first screenshot: the label sat beside `1.5`), and automatic ticks keep 5% of the axis clear of an
   `extra` tick so two labels never overprint.
9. **Scales take plain numbers**: `Scale.x(min = 0, max = 2, breaks = Seq(0, 1, 2), format = "%.1f")`. `Optional[Seq[Double]]`
   needed a conversion from `Seq[Int]` (added to `Optional`); tick format is a printf pattern (a lambda cannot be an
   `Optional` argument, and a string can come from MATLAB and the CLI).
10. **Mid-session request**: `RingModule.scale` is `scalarMultiply`: the trait, the infix extension (its JVM name
    `infixScalarMultiply`), `Chain`, `UnionFind`, tests, the developer's guide; the labs lose `scale`/`infixScale`.
    `Persistence.scale`, `PersistenceDiagram.scale` and `PersistenceFilter`'s "scale" are another thing (the scale of the
    input) and stay.

11. **Two looks for publication** (project lead, after the PR was opened: "we definitely should have one with white or
    transparent background, black foreground, since the cream background won't work in publications, where it will show
    up as a grey background splotch"; then, on seeing the first cut: "the printer look needs to cope with white
    background and is a different spec from a transparent look with both white and black element"). The first cut
    made ONE look, `Palette.print` plus a transparent `Style.print`, which is neither: transparent black ink is
    unreadable on a dark page, and a print look should keep its white. Now two, each a palette and a style, not a third
    `Theme` case (`Theme` is light/dark/auto and these are palettes, whichever mode).
    **Print** (`Palette.print`, `Style.print`): a figure with a white background of its own. Surface exactly `#ffffff`
    (`Palette.reference`'s `#fcfcfb` is near white but still a rectangle in a converted PDF), ink `#000000`, one look (dark
    chrome and series equal the light ones). Series: Okabe-Ito blue, vermillion and bluish green, then violet, ochre,
    rose, olive and brown; the first three pass every check of the dataviz validator on ALL pairs on `#ffffff` (CVD worst
    11.0, normal-vision 18.7), all eight on adjacent pairs. Okabe-Ito's own orange, sky blue and yellow fail 3:1 against
    white, so it was not taken whole. Blue, vermillion and green are close in lightness (L* 46, 54, 58), so a grayscale
    printer separates degrees by marker shape and legend, not gray level; the sequential ramp (ColorBrewer Blues) keeps its
    order. **Clear** (`Palette.clear`, `Style.clear`): no background, and the project lead's "both white and black
    elements" read as the two modes of one palette: black chrome on a light page (`Theme.Light`), white on a dark page
    (`Theme.Dark`; `Theme.Auto` follows the reader's setting). Its light mode IS `Palette.print`'s; its dark series are the
    brand's dark steps, which pass the validator on `#000000` (first three on all pairs, all eight adjacent; the first
    Okabe-Ito dark guess failed the dark lightness band 0.48-0.67). The reading is an interpretation, not confirmed: the
    other one is a halo (each text and mark drawn in both colors so that it reads on any page), which was not built.
    `Style.print` and `Style.clear` both name `Helvetica, Arial, 'Liberation Sans', sans-serif` because `system-ui` is a
    CSS keyword of browsers; whether Inkscape, rsvg or a LaTeX pipeline resolves it was NOT tested here (no converter in
    the container but ImageMagick), so that is the reason, not a measured failure. `Style.named`, `withStyle(name)` and
    `--style` exist so MATLAB and the CLI reach the presets; `--style` and `--text-scale` are one change in the CLI, because
    a `Style` replaces the whole style and two tweaks would have lost the preset's font. Names: `print` and `clear`
    (clear = no background); overturn cheaply, they are four vals and two `named` cases.

12. **Finer-grained elements** (project lead: "certainly for text styling, but arguably also tick marks, grid lines, the
    shaded half plane in the diagram, axis positions"; asked for the other areas other packages tune, and then for them).
    The areas, from the theme and axis options of ggplot2, matplotlib rcParams, plotly, Vega-Lite and seaborn: text per role
    (ggplot `element_text`), lines (`element_line`: grid, minor grid, axis line, frame), tick marks (matplotlib
    `tick_params`, plotly `ticks`/`ticklen`), a panel fill (`panel.background`), axis sides (ggplot `position`, plotly
    `side`, Vega `orient`), minor ticks, and slanted tick labels (ggplot `angle`, matplotlib `labelrotation`). (a) The
    settings live in `Figure.elements`, BESIDE `style`, as `degrees` sit beside `palette` (decision 5): a `Style` replaces
    the style whole, and a tweak of one element must not be undone by it, nor depend on its order. (b) The layout of
    `Svg.render` was written for the default size of each text; each constant is now that constant times
    `sc(role) = textScale * size / defaultSize(role)`, so a role that is at its default gives exactly the old number, and the
    gallery (41 files: figures, boards, a scene, light and dark) renders byte-identically before and after. That check is the
    contract; any new layout term must keep it. (c) Eight text roles, not seven: a color bar's title is 11 px and
    `Secondary`, which is no other role's default (the legend heading is 12 px and bold), so it has its own; a color bar's
    numbers are `TickLabel`. (d) `angle` is for the x axis's tick labels only: a slanted y label has no obvious anchor and
    nobody asked. The room a slanted label needs is a guess from the longest label's characters (0.6 em each), because the
    ticks are chosen after the margins. (e) Tick marks default to `Paint.Muted`, not `Paint.Axis`: the brand's axis color is
    a pale hairline and tick marks in it are invisible. (f) Minor ticks: on a log axis whose ticks are all a whole number
    times a power of ten the whole numbers of each decade that are not ticks (so 3, 4, 6, 7, 8, 9 beside 1, 2, 5), else the
    halves between ticks in the axis's own scale. (g) `Settings.parse("title.size=18")` is the one string grammar for MATLAB
    (`withSetting`) and the CLI (`--set`), instead of a method per property: MATLAB passes strings and numbers and could not
    build a `Text`; twenty overloads would have been the other choice. A tweak refuses where it was made (a side the axis
    cannot be on), so the CLI says so before computing. (h) Bug found by reading while doing this: a board saved as `.svg`
    always painted a background rectangle from its first panel's palette, so `Style.clear` on a board was not clear.

## What was verified

- **Default plots are unchanged**: `Gallery` run on the original commit (a `git worktree`) and on the new code, SVGs
  compared with `tda4j[0-9]+` ids normalized: 16 of 20 identical, among them the heatmaps, cocycle and circular coordinates
  (so `Paint.Value` reproduces the old colors exactly). The four that differ are explained: the diagram (the diagonal and
  the `∞` line now span the plot area, the `∞` line used to start 8 px inside the axis), the board holding it, and `diagram-dark`;
  `sphere.html` (CSS `font:` shorthand split into properties). All screenshotted and read.
- **Specs**: `PlotGrammarSpec` (55), the facade (`PlotCustomizationFacadeSpec`, 7), the CLI (`PlotCLISpec`, 15), the
  MATLAB tab (`MatlabTabsSpec`), the existing plot specs (`PlotSpec` 20, `PersistenceResultPlotSpec`) green.
- **Mutation checks** (break the code, the example must fail): log scale as identity (10 examples failed), degree shape
  ignored, palette ignored by `ColorScale`, text scale ignored, two-point diagonal on a curved axis, `∞` stays at its own end
  on a shared board, CLI ignores the look flags: each caught by the example written for it. One SURVIVED: the "keep clear of
  an extra tick" example, because of the specs2 trap below.
- **specs2 trap, found by that survivor**: in a non-final statement `(a must X).and(b must Y)` checks only `a`; the by-name `b`
  is never forced. 21 such statements in `PlotGrammarSpec` were half-vacuous; all are separate statements now. Existing specs
  in the repo that use `.and(...)` mid-body have the same weakness (not audited).
- **Looked at** (screenshots, light): default barcode/diagram/persistence image; log-log diagram (the diagonal is a straight
  45 degree line, sub-decade ticks fall back to readable values); slides style; bottom legend with heading and renamed
  degrees; viridis and reference-palette heatmaps (reference with a transparent background); shared board; threshold
  lines; 3-D scene with style and palette.
- **MiMa**: needed tags (`git fetch --tags`), then 26 problems against 0.6.2, all deliberate (the `RingModule` rename and the
  labs; `Axis`, `Colorbar`, `Figure`, `Scene`), plus one I introduced and removed (an abstract `+` on the public `Plot`
  trait). The project lead chose to target 0.7.0 instead of writing exceptions: `version.sbt` is `0.7.0-SNAPSHOT`, whose
  baseline is empty (a new `0.Y` series), and the whole `mimaBinaryIssueFilters` block is deleted (the 13 entries that were there,
  all for unreleased 0.6.3 changes, and the 26 I had added, now match nothing).
- Viridis stops are matplotlib's own (computed in a scratch venv): 3 of the 10 I first wrote from memory were wrong.
- Okabe-Ito was NOT shipped as a built-in: the dataviz validator on the brand light surface FAILS the lightness band for the
  yellow and the black, the chroma floor for the black, and warns on contrast (orange 2.1, sky blue 2.15, yellow 1.23 against
  3:1). The first three slots alone pass all-pairs CVD (worst 11.4) and the normal-vision floor (18.4).

## Environment notes

- The container started with a cold dependency cache: `sbt` died on Maven Central 429s. `.claude/scripts/install-sbt.sh`'s
  paced loop converged in 7 attempts (it also runs `compile`, so stop it once it reaches your own half-written code).
- `sbt mimaReportBinaryIssues` says "mimaPreviousArtifacts is empty" without release tags: `git fetch --tags origin`.
- Playwright: the `chrome-linux/chrome` executable path in `rules/plotting.md` hung on `page.screenshot`; its default headless
  shell with an element screenshot of inline SVG works (rules corrected).
- Comparing against the old code needs a second checkout (`git worktree add`), never two sbt processes on one.

## Not verified / open (filed as issues)

- #96 (question, answered by the project lead and closed): zeros on a log axis, see decision 6.
- #97 (enhancement): PNG/PDF export (a new module), a session-wide default style, color bar ticks on a transformed
  scale, a visible board title, balls (`Mark.Discs`) on a curved axis, print palettes (Okabe-Ito fails the validator on the
  brand surface), an `aes` helper for the user's own tables.
- #98 (unverified): MATLAB itself (the `with...` methods are called from Scala), Safari and Firefox rendering of
  `stroke-dasharray`, `<ellipse>` and the clipped half-plane polygon, the interactive 3-D canvas (only its CSS changed).
- #99 (unverified): `(a must X).and(b must Y)` before the last statement of an example checks only `a`; 329 uses of
  `.and(` in 40 specs, nobody has checked which are mid-body.
