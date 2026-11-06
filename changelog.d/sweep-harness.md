### Build, CI and Documentation

- `bench/run.sh sweep-alpha` gives every tool that takes a radius the same one, the draw's enclosing radius, as the main
  run does (GUDHI had none, TDA4j stopped there by default), and none to the tools that take none; the enclosing radius is
  found without a matrix of distances, so the plane sweep's draws of up to 3,000,000 points still run (#242).
- `bench/run.sh sweep-merge` builds one sweep directory from several (`sweep-threads`, `sweep-cubical`, `sweep-alpha`): a
  rerun of some tools replaces their series in an earlier sweep, and `bench/final_run.sh` with `SWEEPS_BASE` reruns only
  TDA4j's rows of each sweep. The sweeps take `--tools` (`--engines`) patterns like `bench.py`, and the alpha and cubical
  sweeps write an `env.json` (#243).
