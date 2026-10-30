### Build, CI and Documentation

- The benchmark compares with FlashCubical (`flashcubical`, installed by `bench/run.sh setup flashcubical`). It reads the
  array as vertex values (the V-construction), so every cubical case has a `_v` twin on the same array; GUDHI
  (`vertices=`) and CubicalRipser read it that way too, and TDA4j, which has no vertex-valued input (#245), runs on the
  refined image of the same persistent homology (`PaperBenchmarkDriver construction=V`). `bench.py run --cases` takes
  patterns like `--tools`, and `bench/final_run.sh` gains a `cubicalv` stage and a V-construction smoke test.
