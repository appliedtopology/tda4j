### Fixed

- The DQP alpha backend no longer drops valid simplices of large circumradius. In high ambient dimension (7 and up) it ruled
  out simplices whose active-set pivot was small, and every coface with them: 40 points in R^9 up to radius 20 lost up to
  106 cells, and the barcode of the whole complex in R^10 had spurious classes that never die (15 in degree 9 where the
  true barcode has none). Such a candidate is now solved again with a smaller tolerance and kept only if its multipliers
  satisfy the optimality conditions against the data. Every bar born within 3.5 times the enclosing radius now equals
  GUDHI's, in every degree, for 40 points in R^10. Simplices of circumradius above about a hundred times the point spacing
  still have approximate values; give `maxRadius` rather than building the whole complex with DQP (#135).

### Added

- `AlphaDQPSettings.rescueRankTolerance` (default `1e-11`; 0 turns the second attempt off) (#135).
