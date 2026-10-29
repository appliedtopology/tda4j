### Changed

- The MATLAB, CLI and JavaScript `engine=cohomology` and `engine=naive` on a Vietoris-Rips complex build the complex
  the way `Persistence` does, instead of by the older per-simplex enumeration: the same bars and representatives,
  about 50 times sooner (100 uniform points in R^3, degrees 0..2: 22 s to 0.4 s on the JVM; 60 s to 1.0 s in
  JavaScript, which runs this about 2.5 to 3 times slower than the JVM).
- The layered in-order generator, `ParallelRipser` and the Euler characteristic curve read the edges of a Euclidean or
  explicit metric space without boxing and along a row of the distance cache: the edge pass of a Vietoris-Rips
  stream takes 2 to 7 times less (3,000 points in R^3: 125 ms to 31 ms; 8,000 points: 900 ms to 130 ms; an explicit
  matrix of 3,000 points: 145 ms to 76 ms). Bars, cells and their order are unchanged (#268).
