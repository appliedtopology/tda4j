---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/plot/**"
  - "src/test/scala/org/appliedtopology/tda4j/plot/**"
  - "_docs/user-guide/plotting.md"
  - "_docs/developers-guide/plotting.md"
---

# The `plot` subpackage

Loads when you work in the `plot` subpackage. Project-wide rules are in `.claude/CLAUDE.md`. Platform choice and its
evidence: `DESIGN-plotting.md`; the build-out: `WORKLOG-plotting.md`; the move into the core: `WORKLOG-plot-into-core.md`.

**A leaf subpackage of the core** (`org.appliedtopology.tda4j.plot`, directory `plot/` in the core's source and test
trees), like `matlab`/`cli`: nothing in the core uses it, and it adds NO dependency (SVG strings, the JDK's
`com.sun.net.httpserver` and `java.awt.Desktop`, loaded only on `view()`). It was its own sbt project and artifact
(`tda4j-plot`, never released); the project lead folded it in because it isolated no dependency and kept the docs build
from compiling its fences. **If a plot feature ever needs a library, it goes in a new module**, not in the core. Not
re-exported by the labs (`tdalab-exports.py` scans only the root package and `sset`).

**Public core API only.** The subpackage could see `private[tda4j]` members; it must not -- what it
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
printed (`tda4j.plot.browser=none`/`TDA4J_PLOT_BROWSER=none`: never; `PlotSpec` sets the property, or `sbt testFull`
would open a tab on a desktop machine). The server is started FROM a daemon thread so the JDK's HTTP dispatcher (which inherits daemon status) does not
keep a REPL alive; `PlotSpec` checks the threads, the list, a page and an event (read with a 10 s deadline: a lost
event must fail, not hang CI).

**Look at what you draw.** `src/test/.../plot/Gallery.scala` writes every kind of plot (`sbt "Test/runMain
org.appliedtopology.tda4j.plot.Gallery <dir>"`); screenshot with the pre-installed Chromium through Playwright
(`/opt/node22/lib/node_modules/playwright`, `executablePath: /opt/pw-browsers/chromium-1194/chrome-linux/chrome`) and read
the PNGs. Specs check structure (one tooltip per bar/cell, well-formed XML, theme colors), not looks.

**Docs fences in `_docs/user-guide/plotting.md` are compiled** like every fence (each self-contained: imports and data);
`PlotSpec` and the gallery run the same calls.
