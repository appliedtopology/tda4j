---
paths:
  - "plot/**"
  - "_docs/user-guide/plotting.md"
---

# The `tda4j-plot` add-on

Loads when you work in `plot/`. Project-wide rules are in `.claude/CLAUDE.md`. Platform choice and its evidence:
`DESIGN-plotting.md`; the build-out: `WORKLOG-plotting.md`.

**A separate sbt project** (`plot/`, package `org.appliedtopology.tda4j.plot`, artifact `tda4j-plot`) that depends on the
core and adds NO dependency to it (nor any of its own: SVG strings, the JDK's `com.sun.net.httpserver`). Root aggregates
it (CI's `test`, lint, `doc`, `mima` cover it without workflow edits); `assembly / aggregate := false` keeps the fat jar
the core's; `publish / skip := true` and an empty, non-failing MiMa baseline until the lead decides to release it.
`root` and `plot` reference each other only through `aggregate(plot)` and `dependsOn(LocalRootProject)`: two `lazy val`s
naming each other deadlock sbt's build loader (it hung, no error).

**Public core API only.** The add-on could see `private[tda4j]` members (same package prefix); it must not -- what it
needs, a user's own plotting code needs too (it needed `CubicalGridStream.topValues`, which the openness audit added).

**Scene model, two renderers.** `Figure` = marks in data coordinates (`Mark.Points/Segments/Polygons/Discs/Boxes/Line/
Text`, one paint or one per item, optional tooltip per item = SVG `<title>`), axes, legend, colorbar; `Svg.render` draws it.
`Scene` = 3-D layers: a canvas viewer with inline JS (no CDN) for pages, a back-to-front orthographic SVG for files.
`trait Plot` (`view`, `save`, `html`, `fragment`, `svgFile`) + `object Plot` (the TDA constructors) share the name on
purpose. Every plot is an immutable value.

**Colors are roles, resolved by `Palette` x `Theme`.** `Paint.Series(k)` for homological degree `k` (never by rank),
chrome roles (`Ink`, `Muted`, `Grid`, ...), `Fixed(hex)` for data colors. `Theme.Auto` = CSS variables of the `<svg>` with a
`prefers-color-scheme` block (pages, viewer); `Light`/`Dark` = plain hex (files; `.svg` saves default to `Light`).
`Palette.brand` (default, Slate & Gold from `brand/DECISIONS.md`) was searched in OKLCH around the brand hues and checked
with the dataviz validator: slots 0-2 pass ALL pairs in both modes (a diagram puts any two degrees side by side), 0-7
adjacent pairs. Re-run the validator after changing any slot; never cycle hues past 8. Degree also gets a marker shape.

**Live viewer** (`Viewer`): one page listing every `view()`ed plot (iframe per plot, `/list`, `/plot/<n>`, server-sent
events on `/events`), port 8337 or a free one, browser opened via `java.awt.Desktop` when there is a desktop, else the URL
printed. The server is started FROM a daemon thread so the JDK's HTTP dispatcher (which inherits daemon status) does not
keep a REPL alive; `PlotSpec` checks the threads, the list, a page and an event.

**Look at what you draw.** `plot/src/test/.../Gallery.scala` writes every kind of plot (`sbt "plot/Test/runMain
org.appliedtopology.tda4j.plot.Gallery <dir>"`); screenshot with the pre-installed Chromium through Playwright
(`/opt/node22/lib/node_modules/playwright`, `executablePath: /opt/pw-browsers/chromium-1194/chrome-linux/chrome`) and read
the PNGs. Specs check structure (one tooltip per bar/cell, well-formed XML, theme colors), not looks.

**Docs fences in `_docs/user-guide/plotting.md` are `scala sc:nocompile`**: the docs build compiles fences against the
core's classpath only. `PlotSpec` and the gallery run the same calls.
