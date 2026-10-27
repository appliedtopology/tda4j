### Build, CI and Documentation

- `bench/final_run.sh` runs the paper's final benchmark in stages on one frozen commit (smoke test, anchor rows, TDA4j's
  rows, a Scala.js subset, merge with the earlier run's rows for the other tools, the sweeps), and `bench/bench.py` gains
  `merge`, `anchors`, `check` and `constraints` commands, `--resume`, and `--tools` patterns. Every TDA4j row of the
  benchmark now runs on one thread, as ripser.cpp and GUDHI do, with `tda4j-parallelverb-1t` and `-mt` as the rows for the
  shipped parallel engine; alpha cases without a radius give every tool the enclosing radius; `setup tda4j-js` records the
  commit its npm package was built from (#202).
- The bars-only parallel experiment of `PaperBenchmarkDriver` takes up to 60,000 points instead of 46,340, so it runs on
  `clifford50000` (its table of distances is 8 n² bytes of heap).
