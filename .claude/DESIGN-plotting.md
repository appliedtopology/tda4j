# Plotting: platform choice and the `tda4j-plot` add-on (2026-10-10)

The project lead: the Scala plotting landscape is dreary; find a good platform, draft plotting routines in a separate
project that adds no dependency to the core. Needed: barcodes, persistence diagrams, persistence images; low-dimensional
Cech/VR/alpha complexes; cycles, cocycles and coordinates; and, if it can be made comfortable, showing plots on the fly
from a REPL (the lead struggled with that).

## What TDA plots need

Barcodes are horizontal intervals, diagrams a scatter with a diagonal, images a heatmap: any chart library does those.
The rest is geometry, not charting: filled triangles (a 2-D complex at a scale), tetrahedra and triangles in 3-D, a
cycle's edges drawn over its point cloud, a cocycle's edges colored by coefficient (diverging), circular coordinates
on a cyclic color map. And interactivity is mostly 3-D rotation.

## The options (2026-10)

| platform | Scala 3 | what it gives | against | status of this claim |
|---|---|---|---|---|
| plotly-scala (Archambault) | no `_3` artifact (2.12/2.13 only; usable from 3 through `for3Use2_13`) | typed plotly.js JSON; plotly does 3-D meshes and rotation | Scala 2 dependency tree; last release May 2024 | read (Scaladex, README), not run |
| nspl (Pityka) | yes (0.18.0, May 2026) | AWT window, PDF/SVG/EPS, canvas; scatter/line/raster/contour/bar | no 3-D; filled simplices not documented; AWT window = the usual REPL trouble (macOS main thread, headless) | read (README, Scaladex), not run |
| dedav4s (Quafadas) | yes | thin Vega/Vega-Lite shim, browser/almond/websocket targets | Vega-Lite has no polygon mark (full Vega does, via `path`); no 3-D; CDN import map | read (README, Scaladex), not run |
| Doodle (Creative Scala) | yes | compositional 2-D vector drawing: Java2D window, SVG | no axes/ticks/legends; no 3-D | read, not run |
| XChart / JFreeChart (Java) | n/a (Java) | Swing windows, PNG/SVG/PDF | charts only, no polygons; Swing in a REPL | known, not run |
| breeze-viz, vegas, EvilPlot | dead or Scala 2 only | | | known |
| **own scene model -> SVG + HTML** | yes | exactly the marks TDA needs; zero dependencies; deterministic, testable output | axes/legends are ours to write and maintain | **built and screenshotted (below)** |

No spike was run on nspl or dedav4s: the decision does not hinge on their charting, which is good, but on 3-D and on
display, where neither offers what is needed. If the lead prefers a library, nspl is the one to try first for 2-D
publication figures.

## Decision

A small add-on that owns a **scene model** (marks in data coordinates: points, segments, polygons, rectangles, text,
polylines, with axes, legend and color roles) and renders it itself:
- **SVG** for every 2-D figure: a string, so it can be saved, embedded in docs and notebooks (almond renders SVG/HTML),
  tested as text, and opened anywhere. Light and dark from the same figure: colors are CSS variables with a
  `prefers-color-scheme` block, or fixed hex for a file going into a paper.
- **HTML with an inline canvas viewer** for 3-D (drag to rotate, scroll to zoom), no CDN, works offline; a static SVG
  projection of the same scene for papers.
- **A live viewer** for the REPL: the JDK's own `com.sun.net.httpserver.HttpServer` on `127.0.0.1`, one browser tab
  subscribed by server-sent events; `figure.view()` pushes the figure and the tab redraws. Opens the browser once
  (`java.awt.Desktop`), prints the URL when there is no desktop (remote machine, container: forward the port). Daemon
  threads, so the REPL exits normally. No Swing anywhere.

Plots read only the core's public API (a free test of the openness audit).

## Verified (2026-10-10)

Every kind of plot was rendered by `Gallery` and screenshotted in headless Chromium (Playwright), light and dark, and
the PNGs read: barcode, diagram, side by side, persistence image (two circles: two blobs), landscape, image, a cubical
loop over its image, Vietoris-Rips/Cech/alpha complexes at a scale with their balls, a cycle over the complex at its
birth, a cocycle restricted to the complex at birth (one edge: the one that closes the loop), circular coordinates, a
3-D Vietoris-Rips complex of a sphere (canvas viewer and static SVG), its `H2` representative. The live viewer was
loaded in Chromium against a running JVM: the list held the three viewed plots newest first and showed the newest, in
both color schemes. Not verified: a desktop browser actually opening (`java.awt.Desktop`; the container is headless, so
the printed-URL path ran), macOS/Windows, a Jupyter/Almond notebook, Safari/Firefox.

## Palette

The project lead asked for the brand colors as the defaults: `Palette.brand` (Slate & Gold). The brand hues as they are
fail chart checks (slate's chroma 0.06 "reads gray"; teal sits 5.4 ΔE from slate), so slots were searched in OKLCH at
the brand hues (slate 234°, gold 87°, brick 28° from the site's error color, then teal, orange, violet, olive, rose) for
the lightness band, chroma floor and contrast on the brand surfaces (`#f8f7f4`, `#161b1e`), closest to the brand
values, and confirmed with the dataviz validator. Gold came out at `#a68018` (the brand-mark gold is `#a67f07`).
`Palette.reference` keeps the method's neutral palette.
