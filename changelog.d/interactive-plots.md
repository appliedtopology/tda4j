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
