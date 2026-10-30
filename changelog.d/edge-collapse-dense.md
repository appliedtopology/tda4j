### Changed

- `EdgeCollapse.collapse` is about ten times faster on dense graphs, with the same graph: up to 4,096 points it keeps
  the graph as a matrix and bit sets and tests the dominators of an edge incrementally. Collapsing 1,200 uniform points
  in R^3 at their enclosing radius takes 10 s instead of 98 s, 800 points 2.8 s instead of 29 s (median of trials, one
  JVM each, a 4-core sandbox) (#256).
