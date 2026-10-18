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

## Addendum (same day): the alpha dimension sweep

The project lead described the Helix master's thesis and paper (neither read here) and asked for a spread over dimension:
- the thesis: time and memory over the dimension for 40 and 60 points uniform in the interior or on the surface of the unit cube;
  the heaviest case is 800 points in R^11;
- the paper: points on Tadasets' d-spheres for d = 11 (n from 40 to 900), 14 (40 to 125) and 17 (40 to 75), with Helix's serial times
  on the 40-point ones reported as 0.18, 1.58 and 7.87 minutes (10.8, 94.8 and 472 s);
- the compute server has about 160 GB of memory free, so heap size is not a limit (`--jvm-heap 64g`, `--timeout 1800`).

Pushing back on one thing and agreeing with another. Agreed: a fixed small n over a range of d is the right controlled sweep
(the cubical sweep's twin) and it is cheap; the dual-QP at n = 40 needs no radius (at most C(40, 4) = 91,390 candidate
tetrahedra), which is what the ambient-dimension claim needs and `o3_1024_alpha` cannot show. Caveats, now in the README and the
script's docstring: the points are noise (concentrated distances, nearly flat simplices with circumradii of 50 or more in a unit
cube), so agreement is a correctness stress and not a topological statement; three seeds give timings and not failure rates
(about 1 in 170 clouds at d = 4); cube-surface draws are degenerate when d + 1 points share a facet (probability for n = 40: 0.90
at d = 5, 0.43 at d = 6, 0.09 at d = 7, 0.01 at d = 8; for n = 60: 1.0, 0.96, 0.60, 0.17) and spheres always are.

What was built: `bench/sweep_alpha.py` and `run.sh sweep-alpha` (shapes `interior`, `surface`, `sphere`; series along d for the cubes and
along n for the spheres, each stopping at its first timeout or out-of-memory; `--seeds`; GUDHI under an address-space cap
(`run_proc(address_space_mb=)`, tested); the thesis's sphere times as a column in the time table); `bench/test_bench.py` has 51
checks. `bench.py` also had two file-handle leaks and a `Popen` that was never marked reaped; fixed.

Measured here (one draw each, 4 cores, 15 GB, `-Xmx9g`, so only the shape of the curves is worth reading):
- Helix on 40 uniform cube points: 3.2 s at d = 5, 14 s at d = 6, 80 s at d = 7 (2.9 GB), a factor 4 to 5 per dimension; the
  dual-QP on the same points: 1.9, 3.2, 4.2 s, then 18.7 s at d = 8. So a Helix series at n = 40 stops near d = 8 to 9.
- At d = 5 and 6 the dual-QP and Helix barcodes agree except for two H2 bars of length 3e-9 at filtration values 49 and 83 (nearly
  flat simplices), below any tolerance.
- **40 points on the 11-sphere (R^12): Helix timed out at 420 s; the paper's Helix needs 10.8 s.** The dual-QP took 27 s, and 10 s on
  40 points of the 14-sphere. Our Helix is therefore at least 39 times slower than the original on its own workload, or the
  cospherical cluster (all 40 points are cospherical up to rounding) sends it down a slow branch; #68 (symbolic perturbation for
  cospherical points) is the same area. One draw, one machine: a lead, not a measurement (#134).
- On the 3-sphere (R^4) with 80 points the dual-QP timed out at 150 s where Bowyer-Watson takes 3.5 s: the dual-QP is not free of
  the number of points, either.

Not run: GUDHI (so the reference column and every agreement figure), the Scala.js twins, the seeds, the larger spheres and the 800-point
case. The thesis's own cube numbers were not given; they would go in `THESIS_HELIX_SECONDS`.

## Second addendum (same day): decisions, cyclooctane, and a cross-check by another algorithm

Decisions of the project lead, which supersede parts of the first addendum:
- **No other paper's numbers in our tables** (they were computed in another setting): the column with Helix's reported times, the
  comparison "10.8 s there against more than 420 s here" and its issue (#134, closed as a duplicate of #68 with the measurement kept
  there) are withdrawn. The question is whether our alpha implementation stands up to other implementations.
- **No sphere samples** (cospherical points are degenerate for every Delaunay-based tool): the `sphere` shape, `--spheres` and the
  anchors are gone from `sweep_alpha.py`; the unit cube (interior and surface) stays, with `--grid` for a series over n at fixed d.
  The one sphere measurement kept for the record, on 40 points of the 11-sphere in R^12: Helix did not finish in 420 s, the dual-QP took
  27 s; on the 14-sphere the dual-QP took 9.9 s.
- The compute server has about 160 GB free, so the sweep's defaults are `--jvm-heap 64g`, `--timeout 1800`, GUDHI capped at 128 GB.

**Cyclooctane.** The data is `src/matlab/for_distribution/tutorial_examples/pointsCycloOctane.mat` of `appliedtopology/javaplex` (found by
the project lead; that repository is outside this session's scope, but `git clone` of a public repository works through the proxy, and a
direct raw-file download does not). Checked here: one variable, `pointsCycloOctane`, 6040 x 24 doubles, coordinates in [-1.95, 1.95];
SHA-256 `6725fa21...f79cc` at javaplex commit `05a288a4`; a median of 37 other points within 0.5 and 8 within 0.25. `prepare` fetches it
from the pinned URL, checks the hash, converts it with scipy (`cyclooctane.txt`); the cases are `cyclooctane` (Ripser to 0.5) and
`cyclooctane_alpha` (DQP to 0.25, degrees 0 to 2). The same directory has `pointsOpticalDct_k15.mat` (15000 x 8), an optical-patch set
that is not the DQP paper's k = 300 set (that file is not in the repository).
- DQP on all of it at the radius 0.25, `-Xmx10g`: 224 s of computation at p = 2 and 234 s at p = 17, 3.6 GB RSS, bars per degree
  (6040, 10959, 528); the bars alive at the radius are (1, 1, 2) over both fields, and the two barcodes are identical bar for bar (all
  17,527 pairs). Those are the Betti numbers of the complex, computed first and not compared with anything.
  An earlier version of `bench.py` carried an expected value and a claim that F_2 would differ because of the Klein bottle's torsion;
  the run does not support that claim and both were removed (the plan's rule: compute first, quote after).

**Cross-check by another algorithm** (`sweep_alpha.py` and `tda4j-cech`): TDA4j's Cech complex, built from minimum enclosing balls with no
triangulation, has the barcode of the alpha complex in general position (nerve lemma). On 40 interior cube points (one draw each) the
Cech, dual-QP and Helix barcodes agree, in the number of bars longer than 1e-6 in every degree and in their values to at most 5.6e-16,
at d = 5 (H0 to H2: 40, 120, 111 bars), d = 6 (40, 232, 417) and d = 7 (40, 263, 626); the dual-QP has two more H2 bars at d = 6, of
length 2.5e-9 and 4.2e-9 at filtration values 49 and 83 (nearly flat simplices). Times at n = 40: Cech 16 to 38 s, nearly independent of d.
The first real run of the sweep with GUDHI 3.13 (installed in the scratch venv): see the next section when it is done.

## Third addendum (same day, after the merge of #125): the sweep against GUDHI, on small draws

`bench/sweep_alpha.py` with GUDHI 3.13 (installed in a scratch venv; it also runs the CGAL alpha complex) on 40 points of the unit cube,
interior and surface, d = 5, 6, 7, two draws per cell, 4 cores and 15 GB (`--jvm-heap 6g`), warm medians in seconds. The run stopped
after the last GUDHI cell of the surface at d = 7, before `tda4j-cech` there and before its own summary (the process was gone when I
looked; the sandbox session had been reconnecting, so a restart is the likely cause; not investigated), so `sweep.md` was rebuilt
from `sweep.csv` with `--summarize` and that one cell is empty.

| 40 points | tda4j-helix | tda4j-dqp | tda4j-cech | gudhi |
|---|---|---|---|---|
| interior, d = 5 | 2.78 | 1.60 | 17.7 | 0.020 |
| interior, d = 6 | 4.18 | 0.90 | 10.2 | 0.105 |
| interior, d = 7 | 39.9 | 1.81 | 13.5 | 0.524 |
| surface, d = 5 (some draw has d + 1 points on a facet) | 1.06 | 0.76 | 17.7 | 0.021 |
| surface, d = 6 | 6.52 | 3.30 | 15.2 | 0.106 |
| surface, d = 7 | 55.4 | 1.77 | | 0.458 |

- **Agreement with GUDHI** (largest bottleneck distance to the reference over the two draws; the tolerance is 1e-5): Helix at most
  2.9e-11 (0 in five cells of six), the Cech complex 0, the dual-QP at most 2.8e-8 (the near-zero bars it keeps that the others
  drop). GUDHI against the Cech complex: 0, and 4.3e-9 against the dual-QP where the Cech complex is missing. So on these inputs
  TDA4j's alpha complex agrees with CGAL's in all three of its implementations, degenerate surface draws included.
- **Cost.** GUDHI is 100 times faster than Helix at d = 5 and 76 times at d = 7, and 3.5 times faster than the dual-QP at d = 7. GUDHI's time
  grows by a factor 5 per dimension (0.020, 0.105, 0.524 s), Helix's by 1.5 to 10 (2.8, 4.2, 39.9), the dual-QP's hardly at all (1.6,
  0.9, 1.8). If the trends hold the dual-QP overtakes GUDHI near d = 8 to 9: a projection from three points and two draws, not a
  measurement; the server run measures it.
- **Memory.** GUDHI 44 to 94 MB of RSS; TDA4j 1.0 to 2.5 GB, with the JVM heap pools' peak within a few percent of the RSS here
  (the pre-sized heap and the parallel collector keep young garbage around). The Cech complex uses the most (2.2 to 2.5 GB) and takes
  10 to 18 s, almost independent of d.
- **Not shown:** anything above d = 7, where GUDHI is expected to stop; the cyclooctane and other real data against GUDHI (CGAL does
  not reach 24 coordinates); more than two draws; failure rates.
