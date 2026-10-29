### Changed

- `Persistence` with the default `Representatives.Cycles` on a Vietoris-Rips complex of points (`Engine.Ripser`) takes
  1.4 to 1.9 times less: the pass that derives the cycles no longer reduces the boundary of an apparent pair that is not
  reported, which is most of the pairs. The bars and cycles are unchanged, term for term. (Torus of 1,000 points in R^3,
  degrees 0..2: 1.8 s to 1.0 s on one thread.) The cocycles run is untouched (#223, #194).
