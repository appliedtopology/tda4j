### Fixed

- `Engine.ParallelRipser` with two-word simplex indices on Scala.js sorted the keys of each block with
  `java.util.Arrays.sort(a, from, to)`, which allocates a scratch array as long as the whole key array on every call (a
  spec shows it on Scala.js), so the sort took time quadratic in the number of blocks. It now sorts each range in place
  (`Parallel.sortLongs(keys, from, to)`, #229).
