### Added

- Interactive pages in 3-D and for large complexes (#331). `Plot.filtration3D` draws translucent balls around the points
  that grow with the slider (`Behavior.radius` now works on a layer of balls). With `link = true` its canvas is linked like
  the flat page: pointing at an item of the canvas chooses it, the chosen items get a halo, and a class chosen in the diagram
  draws its cycle in the canvas. `Plot.filtration` draws a complex of more than 20,000 cells on a canvas seen from above (a
  drag pans, the wheel zooms about the pointer) instead of an SVG, a seventh of the bytes and a hundredth of the elements;
  the new `canvas = true | false` chooses either way, and `maxCells` defaults to 200,000 (it was 20,000).
- Zoom reaches every flat axis a page can draw (#333). `Panel.zoomable` and `Interactive.zoomable()` handle logarithmic,
  square-root, symmetric-log and reversed axes, number formats (`%f` and `%e`, with the flags `-`, `+`, `0` and `,`, a width
  and a precision), number styles, slanted labels and minor ticks, and the axes follow with the numbers the plot writes for
  the range shown. Only a transform of your own, a logarithmic axis with a `-∞` line and another number format are refused,
  with the reason.
- Touch and keyboard for the interactive pages (#333). On a touch screen two fingers pinch and drag a flat picture, and pinch
  a 3-D canvas; one finger scrolls the page and a tap chooses. From the keyboard a picture takes focus: `+`, `-`, `0` and
  the arrows zoom and pan, and where a drag chooses, the arrows move a cross, `Enter` sets the corners of a rectangle and
  chooses, `Escape` lets go.
- The rest of the interactive pages (#342). `Behavior.followsZ` and the other `Follows` rules now work on a 3-D layer: a plane
  rises with a slider while the picture stays still. `Panel.Spatial.brushable` gives a 3-D canvas a `box` button that makes a
  drag draw a rectangle in the plane of the view; `PageState.brush` and `Interactive.touched` give its still and the keys it
  touches. A brushable flat picture has a `touch` button that lets one finger draw the rectangle. `Scene.withAxes` gives a flat
  scene (seen from above) an x axis and a y axis that follow the pan and the zoom, and `Plot.filtration`'s canvas of a large
  complex has them. In a picture where a drag chooses, `]` and `[` (or `Page Down` and `Page Up`) put the keyboard's cross on
  the next and the previous item and `Home` and `End` on the first and the last, so two `Enter`s around two items take the
  rectangle between them; a brushable 3-D canvas takes the same keys.
- The axes of a flat scene take what a figure's take (#344, #345). `Scene.withXLabel`/`withYLabel` (and `Labels(x, y)`) name them,
  and `withXScale`/`withYScale`/`withXYScale` (and `Scale.x/y/xy`) set a `transform` (log, square-root or symmetric log), the
  range the picture opens on (`min`, `max`, `padding`), the `breaks` and the printf `format` of the numbers, `visible`,
  `reverse` and the `grid`lines (`Lines.grid` styles them), in the still and on the canvas alike; the canvas's numbers,
  gridlines and labels follow the pan and the zoom. A control that moves an item (`Behavior.Follows`) works on transformed and
  reversed axes: the value goes through the axis. `Scene.withXRange`/`withYRange` are the plain-argument forms for MATLAB.
  A value with no place on its scale (zero on a log axis) is refused with the way out, and so are `position`, `crossesAt` and
  `minusInfinity` (a canvas's axes stay at the foot and the left edge of the plot area) and a transform of your own.
- `PageState.brush(panel, window)` and `Mark.Region`: the still of a rectangle a viewer has dragged out (a dashed box in the
  plot's units, with `select` for what it touches). On the page, `tda4j.brush(panel, [x0, x1, y0, y1])` draws or clears the
  rectangle and `tda4j.pointAt(panel, clientX, clientY)` gives the data under a point.
- Frames from every facade (#331): `tda4j --plot filtration --at 1.0 --frames 60 --output frames points.csv`;
  `chart.frames(count)` (the SVG texts, also in a browser) and `chart.saveFrames(directory, count)` (Node.js) in
  JavaScript. A Python plot with a slider or linked views shows as the live page in a notebook.

### Changed

- `Plot.filtration`'s `maxCells` default is 200,000, with the canvas above 20,000 cells (#331).
- A scene with `withAxes`, and the canvas of a large `Plot.filtration`, draws gridlines at the numbers, as a figure does
  (#344). `Scale.x(grid = false)`, `Scale.y(grid = false)` or `Style(grid = false)` takes them away.
- On a brushable canvas a key that moves the cross, steps to an item or lets go redraws the picture once, not twice (#344).

### Build, CI and Documentation

- The interactive pages are checked in Chromium, Firefox and WebKit on every pull request: the new `interactive-pages` job
  writes the plot gallery and compares each page's state with the still the library draws for it, including the touch
  gestures (Chromium only), the keyboard and the canvas (#330, #333).
- The demo's web page is published as two jars, the library and the demo's classes (6 MB, new with every publish) and the
  rest (66 MB, the same bytes on every build until a dependency changes), so that `gh-pages` stores the big half once
  (#335).
