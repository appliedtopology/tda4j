### Added

- Interactive plots (#102, #332). `Plot.filtration(points)` is a self-contained HTML page with a slider for the scale: the
  complex at that scale (balls, edges and triangles) beside the persistence diagram, where each class is a vertical
  segment from the diagonal up to the line that follows the slider, and a point once the line passes its death.
  `Plot.filtration3D` has the complex in 3-D, `barcode = true` swaps the diagram for the barcode, `stops = true` makes
  the slider step through the values at which the complex changes, and `Plot.filtrationOf` takes a complex and a diagram
  of your own. `PersistenceResult.plotFiltration(max)` / `plotFiltration3D(max)` (MATLAB, Java), `--plot filtration` /
  `filtration-3d` with `--at` (CLI), `plotFiltration(max)` (JavaScript) and `plot_filtration(max)` /
  `plot_filtration_3d(max)` (Python) draw a result's own complex.
- `Interactive`, the value behind those pages and the way to make your own: `Control` sliders, `Behavior`s that say what a
  control does to an item of a mark (`shownFrom`, `shownBetween`, `followsY`, `radius`, `custom`), `Panel`s of a figure or
  a scene with behaviors on its marks, chained as `Interactive().withSlider(...).withPanel(...)`, with
  `withScript(javascript)` and `tda4j.reactor` for behaviors of your own. The same behaviors give still pictures:
  `page.at(0.6)`, `frames(n)` and `saveFrames(directory, n)` (also `save_frames` in Python and `saveFrames` in
  MATLAB), the frames of a video or the slides of a talk. The user guide's plotting page has the recipe for an MP4 or a GIF
  with `ffmpeg`, and for PowerPoint.
- Every plot tweak (`+ tweak`, `withPalette`, `withStyle`, a `given Look`) reaches every panel of an `Interactive`.
- The pages of an `Interactive` can share a document with pages written by other runs (a reveal.js deck, a blog post): the
  element ids of a page carry a token drawn per run, so a second page's slider no longer drives the first's picture. The
  panels of a page shrink to a narrower slide or iframe, and stack when it is narrower still. The user guide has a
  reveal.js note.

- Linked views, brushing and zoom (the rest of #102). `Plot.exploreOn(points, bars)` is a page of the point cloud, the
  persistence diagram and the barcode that know about each other: pointing at a class in one lights it up in the others and
  draws its representative cycle on the points, a click keeps it chosen (shift-click adds, a click on the background lets go),
  and dragging a rectangle over the diagram or the barcode chooses every class it touches; the page lists what is chosen.
  `Plot.explore(bars)` is the diagram and the barcode alone, and `Plot.filtration(points, link = true)` links the views of the
  slider page, so a class chosen in the diagram draws its cycle on the complex at the slider's scale. Every picture zooms
  (buttons, or Ctrl and the wheel) and pans, and the axes follow with the numbers the plot would write for that range;
  `Panel.zoomable()` and `Interactive.zoomable()` do it for pages of your own, `Panel.selecting`, `brushable`, `Selection` and
  `Behavior.selected` make selections of your own. The stills follow: `page.at(PageState().select("class", 3).zoom(1,
  Window(...)))`. `plotExplore()` and `plotLinkedFiltration(max)` (MATLAB, Java, JavaScript), `plot_explore()` and
  `plot_linked_filtration(max)` (Python) and `--plot explore` / `linked-filtration` (CLI) draw a result's own bars and cycles.
  The user guide's plotting page has a section on it. Checked in Chromium only; touch and 3-D linking are left (#333).
