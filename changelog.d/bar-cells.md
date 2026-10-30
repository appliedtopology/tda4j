### Added

- `bar.birthCell` and `bar.deathCell` on the bars of a diagram: the simplex whose entry creates the class and the one
  that kills it (none for an essential class), as in GUDHI's `persistence_pairs`. Recorded by every engine that computes
  on simplices (`Cohomology`, `Ripser`, `ParallelRipser`, `Chunks`, `Naive`) and by the cubical cohomology engine, for
  cycles and for cocycles; `None` for the fast cubical and fast alpha engines. Cells are not part of a bar's equality.
- The same cells in the facades: `PersistenceResult.birthCell(i)` and `deathCell(i)` (MATLAB, Java), `Result.barCells(i)`
  (JavaScript), `--bar-cells` (command line), and `birthCells`/`deathCells` in the saved result file (a new optional pair
  of keys, so the format version stays 1).
