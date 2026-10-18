# WORKLOG: benchmark harness fixes after the first full run (2026-10-18)

Source: the summary of the first full cross-tool run (`results/20261014-115750`, before the Scala.js build and the tutorial
feedback; not in the repository, and its numbers are not citable: the paper's run is a later one). Reading it for the
paper's structure turned up eight harness problems; they are issues #126 to #133 and this worklog is the record of
what was checked and changed. Point-in-time snapshot: later results go in an addendum.

## What the first run showed (for the structure of the paper, not for quoting)

338 rows: 309 ok, 14 timeouts (JavaPlex 10, GUDHI 2, GUDHI-collapse 2), 10 unsupported (cripser at p = 17), 5 errors
(clifford50000: ripser.cpp and giotto-ph at p = 17, ripser.py at both primes). All 124 TDA4j rows finished.

- Against the specialised C++ tools: `tda4j-cocycles` 2.9 to 7.8 times ripser.cpp on Vietoris-Rips (median about 5),
  `tda4j` (cycles) 4.6 to 11.6; 2-D cubical 1.7 to 2.0 times CubicalRipser and 1.6 to 2 times faster than GUDHI; 3-D
  cubical 2.1 to 2.5 times GUDHI and about 6 times CubicalRipser.
- Against the general frameworks: faster than GUDHI on 6 of 9 VR cases (dragon2000 51 times, fractal-r more than 29,
  sphere3_192 7); 32 to 500 times faster than JavaPlex on the 4 it finishes (5 of 9 time out).
- Cycles against cycles, Ripserer.jl with involution beats TDA4j on 6 of 9 VR cases by 3.5 to 6.9 times; TDA4j wins
  o3_1024, o3_4096 and clifford50000 by 1.6 to 2.5.
- Representatives: `tda4j` over `tda4j-cocycles` 0.96 to 1.75 (median 1.3); Ripserer's involution 1.04 to 8.6.
- The field is free for every tool (p = 17 over p = 2: 0.9 to 1.2).
- Engine choice: the general cohomology engine is 3.6 to 6.2 times slower than the dedicated one on 2-D images and 1.05 to
  1.4 on 3-D; the default alpha path is up to 8.8 times slower than `fastalpha` (10^4 points, 2-D).
- Alpha is 10 to 43 times GUDHI by default and 5 to 22 with `fastalpha`; the benchmark has nothing above 4-D, which is where
  the rules file says the value is (#131).

## What was checked

- **The RSS floor (#126).** A child that used 40 MB, spawned by a Python process holding 250 MB, reports
  `ru_maxrss` = 261 MB (measured here). `Popen` uses `vfork`, and the kernel folds the old address space's high-water mark
  into the child's at `exec`. In the run, 41 of 338 rows read exactly 130 MB on 21 different cases; the first rows read 38
  and 70 MB, so the floor was the harness growing. `VmHWM` read from `/proc/<pid>/status` after `Popen` returns (the new image is
  in place) is the child's own.
- **Ripser's limit on clifford50000 (#127).** The project lead's guess (the coefficient shares a bit field with the simplex
  index) is right, and sharper than "p = 17 eats bits". Read in `ripser.cpp` as shipped in ripser.py 0.6.15 (PyPI sdist; the
  master copy that `run.sh setup` clones is still to be compared): `num_coefficient_bits = 8` is a constant, not a function
  of the prime; `max_simplex_index = 2^(64 - 1 - 8) - 1 = 36,028,797,018,963,967`; `check_overflow` throws
  `std::overflow_error`, which nothing catches. Degree 2 needs the cofaces of 2-simplices: C(50000, 4) =
  260,385,417,812,487,500 > 2^55. Five bits would do for p = 17 (limit 2^58 - 1 = 2.88e17 > 2.6e17). The vanilla Z/2 build
  only checks `i < 0`, so its limit is 2^63 - 1 (why p = 2 runs in 70 s), and ripser.py always compiles with coefficients (why
  it fails at both primes, after building a dense 50000 x 50000 matrix: 71.6 GB). This accounts for all four failures, but only the
  arithmetic and the source were checked: the reproduction, and the giotto-ph message, need the compute server.
- **The DQP paper's examples (#131, #133).** Carlsson and Carlsson (Sci. Rep. 14:19824; arXiv 2310.00536), section 4, read
  from the arXiv PDF with `pdftotext`: Stanford bunny 5000 points in R^3 (radius 1/15 of the diameter, 12 s); 1000 uniform
  points in [-1,1]^10, Alpha(S, 1.0) to 3-simplices, 5 minutes, sizes (1000, 64785, 560459, 1783194); cyclooctane, 6040 points
  in R^24, Alpha(S, 0.25), 7 s, (6040, 24646, 28352, 11858), Betti (1, 1, 2), Ripser to 0.5 in 48 s; image patches X(300,30),
  1000 points in R^9, Alpha(S, 0.5) to 2-simplices, (1000, 44080, 454843), 38 s, Betti (1, 1), Ripser 1.5 s. The Helix paper (IEEE
  10917453) could not be read, so no example comes from it; the plan's own Helix crossover table (5-D, 500 points: 57 s) gave
  `unif5d_500`.

## What changed

- `bench/bench.py`
  - `run_proc` samples `VmHWM` (10 ms) and returns a `Proc` with a status, a reason and the RSS source; failures are classified from
    stderr (`limit`, `oom`, `unsupported`, `error(code)`).
  - A timeout at one field is recorded, not rerun, at the next (`--rerun-timeouts` opts out); `unsupported` rows for
    combinations the harness knows cannot run (`max_ambient`, `needs_radius`, `no_radius`).
  - `Case` is a named tuple (`radius_k`, `degenerate`); new alpha cases and tool `tda4j-dqp` (and the JS twin); the radius reaches every tool of an alpha case.
  - `prepare` writes the new point sets from their own seeded generator after the old ones (the old files are byte-identical) and the
    jittered `_gp` copies.
  - `summarize`: distances below 1e-12 are 0; `agreement` returns a note naming the degree and counts; significant bar counts;
    "Failures and limits" and "Agreement notes" sections; † on rows whose reference did not finish and on degenerate cases;
    means per task, with median, range and finished/attempted; JS-against-JVM memory two ways.
  - `jvm_flags`: one JVM policy (`-Xmx`, `-Xms`, ParallelGC) for the run and the sweep, recorded in `env.json`. This changes the main run from
    the default collector to ParallelGC with a pre-sized heap, as the sweep and the paper-benchmark worklogs already did, so timings
    of the new run are not comparable with the first run's.
- `PaperBenchmarkDriver.scala`: `threshold` reaches alpha (verb dispatch; `maxRadius`/`maxDimension` for a named backend; `fastalpha` refuses
  it); the JSON line has `heap_peak_mb` and `old_peak_mb` (heap pool peaks over the timed runs).
- `py_worker.py`: GUDHI alpha with `max_alpha_square` and the tree cut above `dim + 1`; `tda4j_js_worker.mjs`: `fastalpha` refuses a threshold.
- `sweep_cubical.py`: the shared flags and `Proc`; `rss_src`, `heap_peak_mb` and `reason` columns.
- `bench/test_bench.py` (new): 34 checks on synthetic processes and results (`python3 bench/test_bench.py`).

## Not verified

- **Run here:** the Scala driver compiles (`sbt tda4j/Test/compile`, 7 min cold) and is scalafmt-clean. On 70 uniform points in R^5
  with a radius at which a point has about 8 neighbours within 2r: the default dispatch, `backend=dqp` and `backend=helix` with that
  radius give the same barcode (worst difference 1.7e-16), and it equals the full complex's barcode cut at the radius (bars born
  before it, essential if they die after it): so the convention "bars alive at the radius are essential" is the same as in
  `py_worker.py`'s `max_alpha_square`, and `engine=fastalpha` with a radius is refused. The real loop of `bench.py run` (JVM driver, VmHWM
  sampling, `unsupported` rows, summary) ran end to end on that case. 34 Python checks pass.
- **Not run:** anything with GUDHI, ripser, Julia, JavaPlex or the npm package (not installed here), so the agreement code ran only
  with a stub bottleneck distance and `py_worker.py`'s alpha change and the JS worker's one-line refusal are untested beyond a syntax
  check; the radius paths at 10-D and on real data; `unif10d_1000` is expected to take far longer than the paper's 5 minutes in TDA4j or
  to time out; `o3_1024_alpha`'s radius (`radius_k=25`) was chosen from the cost model in `WORKLOG-helix-construction-speed.md`, blind to
  the data's scale, and is the first thing to retune.
- Whether `dragon2000_alpha` is degenerate in the same way as the sphere, or a TDA4j bug (#130); the `_gp` pair will tell.
- giotto-ph's `error(1)` message and the master copy of `ripser.cpp` (#127).
- Whether `heap_peak_mb` tracks the live set closely enough for a memory exhibit (#132): compare it with a minimum-`-Xmx` bisection on
  one case before relying on it.

## Open items

#126 to #133 each carry their own "done when"; #133 (cyclooctane and the image patches need data files, nothing from the Helix paper) is the
one nobody has started.
