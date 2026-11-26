### Changed

- `Engine.ParallelRipser` (what `Auto` picks with several threads or `blockOrder = SmallerIndexFirst`) is much faster and
  needs much less memory when it asks for cocycles or bars. It finds the columns of the coboundary matrices from the
  maximal-facet forest of the edge order instead of listing every simplex, and keeps a working column as lazily generated
  cofacet streams instead of a heap of every entry of every added coboundary.
  The bars, the cocycles and the reduction are the same: on one thread a 32 x 32 lattice to degree 3 takes 0.06 s instead
  of 6 s, 192 points on a sphere to degree 2 0.1 s instead of 0.7 s, and a torus of 1,000 points to degree 2 2 s in 315 MB
  where the heap ran out of 3 GB. A run that asks for cycles keeps its pairing and lists the simplices as before. The edge
  ranking sorts the lengths with a primitive merge sort (the order of equal lengths is unchanged).
