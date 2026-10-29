### Changed

- `Engine.Cohomology` on simplices with `Int` vertices (the Vietoris-Rips stream, alpha, Cech) finds a face by one `Long`
  key where the vertices fit in it (one memory read per lookup instead of two), lays out each coboundary block on all
  threads, and no longer reduces the degrees above the stream's `homologyDegreeLimit` only to discard their bars. The
  bars and representatives are unchanged (`PackedSimplexCohomologySpec` compares them with the unpacked reduction term for
  term, on one thread and on several). On the Vietoris-Rips stream of 250 uniform points in R^3 the call takes 7.8 to 9.1
  s instead of 11.8 to 13.0 s, on 150 points on S^2 4.8 to 5.3 s instead of 6.5 to 7.2 s (4 cores, median of 5 trials, two
  JVMs in each of two series). Peak memory is unchanged (#251).
