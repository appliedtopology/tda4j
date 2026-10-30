### Added

- `bar.birthCell` and `bar.deathCell` on the bars of a diagram: the simplex whose entry creates the class and the one
  that kills it (none for an essential class), as in GUDHI's `persistence_pairs`. Recorded by every engine that computes
  on simplices (`Cohomology`, `Ripser`, `ParallelRipser`, `Chunks`, `Naive`), for cycles and for cocycles; `None` for the
  fast cubical engine. Cells are not part of a bar's equality.
