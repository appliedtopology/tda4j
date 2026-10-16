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
6. **A mark with no place on a scale is left out and counted** ("2 marks not shown: outside the log scale"), never dropped
   silently (the project lead's stance on zero-length bars) and never clamped (a clamped bar lies about its birth). The
   consequence the docs state: a log diagram or barcode wants `d.dim(1)` or `Sqrt`/`SymLog`, because every `H0` bar is born
   at 0. Open question #3 below.
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

- #96 (question): a log axis leaves out every `H0` bar, which is born at 0. Drop and count (as now), clamp, symmetric log
  by default, or refuse?
- #97 (enhancement): PNG/PDF export (a new module), a session-wide default style, color bar ticks on a transformed
  scale, a visible board title, balls (`Mark.Discs`) on a curved axis, print palettes (Okabe-Ito fails the validator on the
  brand surface), an `aes` helper for the user's own tables.
- #98 (unverified): MATLAB itself (the `with...` methods are called from Scala), Safari and Firefox rendering of
  `stroke-dasharray`, `<ellipse>` and the clipped half-plane polygon, the interactive 3-D canvas (only its CSS changed).
- #99 (unverified): `(a must X).and(b must Y)` before the last statement of an example checks only `a`; 329 uses of
  `.and(` in 40 specs, nobody has checked which are mid-body.
