# Plotting add-on `tda4j-plot` (2026-10-10)

Point-in-time snapshot. The project lead asked for a plotting platform (barcodes, diagrams, images at minimum; low-
dimensional Cech/VR/alpha complexes; cycles, cocycles, coordinates; on-the-fly display from a REPL as a bonus), in a
separate project that adds no dependency to the library; mid-way, to reuse the brand colors as the defaults. Platform
choice and what was verified: `DESIGN-plotting.md`. Rules distilled: `rules/plotting.md`.

## Build

`plot/` is an sbt project depending on the root; root aggregates it so CI's existing commands (`clean ; test`, the lint
triple, `doc`, `mimaReportBinaryIssues`) cover it with no workflow change. Settings: `ThisBuild / scalaVersion` (was a
root setting) so the docs job's 3.8.4 pin reaches `plot` too; `assembly / aggregate := false` (the release attaches
`target/out/jvm/scala-3.9.0/tda4j/*-assembly.jar`; sbt 2 puts each project's output in its own `.../<name>/` directory,
so `tda4j-plot` would not collide anyway); on `plot`, `publish / skip := true`, `mimaPreviousArtifacts := Set.empty`,
`mimaFailOnNoPrevious := false` (MiMa fails on an empty baseline by default).

Trap: the first version had `lazy val root = ... .aggregate(plot)` and `lazy val plot = ... .dependsOn(root)`. Typed
`: Project` to get past "Recursive lazy value root needs type", it then HUNG at "loading project definition" with no
error -- `jstack` showed the main thread parked in `root$lzyINIT1 -> plot$lzyINIT1 -> root$lzyINIT1` (Scala 3 lazy vals
wait for each other instead of failing). `dependsOn(LocalRootProject)` breaks the cycle.

## Design (as built)

- `Figure` (2-D) and `Scene` (3-D) are immutable scene descriptions; `trait Plot` gives `view()`, `save(path)`, `html`,
  `fragment`, `svgFile`; `object Plot` holds the TDA constructors. `Board` puts figures side by side.
- `Svg.render`: margins from title/legend/colorbar; nice ticks (1-2-5); hairline grid; marks clipped to the plot area;
  points ringed in the surface color; per-item tooltips as `<title>`; legend and colorbar on the right; equal aspect
  for geometry. Number labels: four significant figures, `∞`.
- `Scene`: canvas + ~40 lines of inline JS (orthographic, back-to-front, drag/scroll), colors through the same CSS
  variables (read with `getComputedStyle`, redrawn on a color-scheme change); static SVG from a fixed azimuth/elevation.
- `Viewer`: `com.sun.net.httpserver.HttpServer` on loopback, an index page (list + iframe), `/list` JSON, `/plot/<n>`
  pages, `/events` server-sent events (one per new plot, keep-alive comments every 15 s). Started from a daemon thread
  (the HTTP dispatcher inherits daemon status); `java.awt.Desktop` opens the browser when not headless, else the URL is
  printed. Port 8337 (`tda4j.plot.port`/`TDA4J_PLOT_PORT`), a free one when taken.
- Coefficients to numbers (`Plot.number`): the value for `Double`, else the integer `k` with `|k| <= 64` and `k * 1 == x`
  in the field (balanced representative for `Z/p`), so generic chains (`Chain[?, ?]` from a diagram, coefficient type
  a member) can be colored through their own `coefficientField`.

## Palette (the brand request)

Brand values (`brand/DECISIONS.md`, `_layouts/main.html`): slate `#3c5a6b`/`#456f87`/`#8fb4c7`, gold `#866504`/`#a67f07`/
`#f0c647`, teal `#206f6f`/`#75c7c7`, error `#812318`/`#d88279`, surfaces `#f8f7f4`/`#161b1e`. The dataviz validator
on slate, gold, teal, brick as they are (light, all pairs): FAIL lightness (`#812318` L 0.405), chroma (slate 0.06,
teal 0.074 "read gray"), CVD (teal-slate ΔE 3.6) and normal vision (teal-slate 5.4). So: an OKLCH grid search at the
brand hues (slate 230-238°, gold 82-90°, brick 25-35°; L in the mode's band, C 0.10-0.16, contrast >= 3:1 on the brand
surface), keeping triples whose every pair passes CVD >= 8 and normal-vision >= 15, ranked by distance to the brand
values (Python re-implementation of the validator's Machado-2009 simulation, finalists confirmed with the validator
itself):

| mode | slot 0 (slate) | slot 1 (gold) | slot 2 (brick) | worst all-pairs CVD / normal ΔE |
|---|---|---|---|---|
| light | `#1e749d` | `#a68018` | `#b54436` | 8.5 / 15.3 (brick-gold) |
| dark | `#4aa0c7` | `#b98e1b` | `#c65954` | 9.2 / 15.4 (brick-gold) |

Slots 3-7 (teal, orange, violet, olive, rose) by a permutation x lightness search on adjacent pairs: light `#099393,
#aa5910, #7f5bb6, #5b7f1d, #a84e7c`, dark `#25a6a6, #bd6b2a, #916dca, #6c9133, #bc5f8e`; the validator passes all eight
(adjacent) in both modes. Teal and orange first came out with an olive-orange pair at deutan ΔE 1.7 and a rose-orange
normal-vision 14.2 in the hand-picked order; the search fixed both. Dark gold is darker than the brand's `#f0c647`
(L 0.84): the dark band tops out at L 0.67. Sequential ramp: slate hue 236°, L 0.95 -> 0.28, chroma peaking mid-ramp
(monotone, steps >= 0.06 ΔL; its light end is the near-surface "zero", which the ordinal check flags and the continuous
case allows). Diverging: slate vs gold (a blue-yellow axis).

## Looking at it

`Gallery` + Playwright screenshots, read one by one. Found and fixed by looking: the legend's labels clipped at the right
edge (width estimate too small); the side-by-side SVG left a white strip under the shorter figure (no board
background); a bar's cocycle drawn whole was 70 chords across the circle (it lives on the complex at the bar's death) --
`cocycle(..., at = bar.birth)` restricts it to the cells of diameter <= `at` and then shows ONE edge, the one closing the
loop, as theory says; the `H2` representative of a sphere (116 triangles at opacity 0.5) was a solid blob -- now 0.22
with its triangles outlined. The barcode's `H1` born at 0.50 after the last `H0` merge at 0.35 looked suspicious and is
right: the minimum spanning tree skips the largest angular gap, which the loop needs.

## Tests

`PlotSpec` (18): one tooltip per bar/marker/cell/pixel (barcode, longest-n, plain bar lists, diagram, persistence image,
landscape, VR complex of a unit square at 1 and 1.5 = 4 and 6 + 4 cells, cycle, cocycle, circular coordinates, image,
cubical representative), well-formed XML (parsed), theme colors (Auto = CSS variables + dark block; Light/Dark = brand
hex), palette swap, seamless cyclic map, `Plot.number` on `Z/17` and reals, 3-D (canvas in the page, 40 markers in the
static SVG), a board as one SVG, and the live viewer end to end (list, page, index, an event after a second `view()`,
daemon threads).

## Not done / next

- (Superseded below: the project lead asked for its own artifact.)
- 3-D has no tooltips; the canvas viewer has no picking.
- Barcodes of thousands of bars draw every bar (an SVG of a few MB): `longest = Some(n)` is the way out today.
- No MATLAB/CLI surface (plots are for Scala users; MATLAB has its own plotting).
- An option to depend on a charting library later stays open: the scene model would be the adapter's input.

## Publishing as `tda4j-plot` (project lead, same day)

The lead: publish it as its own artifact (they set it up on Maven Central). Done: `publish / skip` dropped; the Central
metadata in `sonatype.sbt` made build-wide (`ThisBuild /`), `plot` has its own `description` and `versionScheme`; its MiMa
baseline is `mimaBaselineVersions` filtered to versions >= `firstPlotRelease` (0.5.1), empty and non-failing until then.
`sbt publishLocal` (local Ivy only) published `tda4j_3` and `tda4j-plot_3` with jars, sources, scaladoc and POMs; the
plot POM has name, description, url, MIT license, scm, developer, and depends on `tda4j_3` (compile) and specs2 (test).
A real `publishSigned` was not run (blocked here as a publishing action, and there is no key); `inspect` lists
`publishSigned` among the readers of `publish / skip`, which no project sets now.

Found on the way: **sbt 2 applies every bare setting of `build.sbt`/`sonatype.sbt` to every project**, so `plot`
inherited the core's library dependencies (the first POM listed commons-math3, scallop, cats, ... as its own), its
compiler flags twice, the docs-site scaladoc options, the CLI main class and the tutorial generator. `plot` now replaces
those (`:=`). And a confusion: with `publish / skip := true` set on `plot`, `show root / publish / skip` printed `true`
(the aggregated plot value, unlabelled; also "1 disk cache hit"); the old single-project build answers "No such
setting/task" for the same query, and so does the current one now that `plot` no longer sets it -- root was never skipped.
