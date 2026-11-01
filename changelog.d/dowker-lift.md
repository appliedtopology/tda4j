### Added

- `Dowker.lift(diagram, relation)` moves every representative of a Dowker diagram to the other side of the relation,
  keeping the bars, by the chain map behind Dowker duality (`DowkerGeometry.lift` for one chain). With distances from
  landmarks to all the points (`WitnessGeometry.D`) it turns a loop through landmarks into a loop through the data
  points, at the bar's birth. A diagram of cocycles lifts too (`DowkerGeometry.liftCocycle` for one cocycle), and
  `CircularCoordinates` reads the lifted diagram with the complex on the points, so every point gets a circle coordinate
  from a computation on the landmarks. MATLAB and Python take the option `representativesOn` (`rows` or `columns`) in
  `computeFromRelation`, the command line `--representatives-on`, for cycles and cocycles.
