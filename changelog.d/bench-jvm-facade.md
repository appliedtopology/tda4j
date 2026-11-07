### Build, CI and Documentation

- `PaperBenchmarkDriver` takes `facade=true`: the call the Scala.js worker makes, through `matlab.TDA4j` with the same
  option strings, so the benchmark can set a JavaScript row against the JVM doing the same work and not only against the
  `Persistence` verb. `bench/bench.py` has a `tda4j-facade*` row for each `tda4j-js*` row (run when `--tools` names them),
  the summary compares the bar counts of the JS, the facade and the verb rows, and the alpha and cubical sweeps take the
  facade rows too (#232).
