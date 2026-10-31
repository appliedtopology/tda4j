### Added

- `Relative.ofBoundary(grid)`: an image or volume modulo its boundary, so that a curve from edge to edge is a cycle of the
  pair, and `CubicalGridStream.onBoundary(cube)`, the set of cubes it divides out.

### Build, CI and Documentation

- A tutorial, "Curves in a Truchet pattern": a random tiling of arcs, whose curves, closed or running off the edge, are
  the long bars of its homology relative to the boundary, checked against a trace of the tiles, and how to get those bars
  from the fast cubical engine for a large image.
