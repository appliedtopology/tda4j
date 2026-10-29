### Breaking

- A saved plot has no background. `Plot.save("d.svg")`, `svgFile`, the MATLAB `save`, the CLI's `--output d.svg` and the
  JavaScript `svg()` write a figure that takes the color of the page, slide or paper it goes onto; it had the palette's
  cream (or the dark theme's blue-black) behind it. The viewer, `figure.svg()`, `fragment` and `html` still draw it. For the
  old file, `figure.withOpaqueBackground()` (or `Style(transparent = false)`); for a paper's white, `Palette.print +
  Style.print`, which keeps its white background in a file. `Style.transparent` is an `Optional[Boolean]`, unset by default,
  where it was a `Boolean`: read it with `style.hasBackground(saved)` (#253).

### Added

- `Figure.withOpaqueBackground()` and `Scene.withOpaqueBackground()` (MATLAB: `fig.withOpaqueBackground()`) draw the
  background in a saved `.svg` too (#253).
