# RedZeD in the benchmark (2026-10-30)

Point-in-time snapshot. Request (project lead): the benchmarks should compare with RedZeD, which Ulrich Bauer called the
current leader in persistence timings. This change adds it as a Vietoris-Rips row; nothing was run on the compute server.

## What RedZeD is

Kapulkin and Kershaw, "RedZeD: Computing persistent homology by Reduction to Zero Differentials", arXiv:2606.06310 (v3);
code `github.com/nkershaw01/RedZeD`, MIT, commit `22ef1bf` ("Updated readme", the head on 2026-10-30; no tags or releases).
A Julia reference (`redzed.jl`), a C++ file (`python/cpp/redzed.cpp`, 1,527 lines) and a PyPI wheel `redzed-tda` around the
C++. The idea: replace a filtered complex by a quasi-isomorphic one with zero differentials; on its own that is no faster
than the usual reductions. The speed comes from "active enumeration" for Vietoris-Rips filtrations (RedZeD_VR): a birth
simplex is active if its image under the reduction map is nonzero, and the (n+1)-simplices with no active face, which are
most of the costly (n+1)-birth columns, are never generated.

What it computes (read from `redzed.cpp`, and the README): homology of the Vietoris-Rips filtration over **Z/2 only**
(chains are sorted sets combined by XOR; no modulus option), **bars only** (`--barcode`; `--simplex-pairs` adds the birth and
death simplices, not chains), one thread (no `std::thread`, OpenMP or async in the file), `float` values, `int64`
combinatorial indices with **no overflow check** (ripser.cpp throws `std::overflow_error`; RedZeD's binomial table just adds
in `int64_t`). Default threshold is the enclosing radius (`min(threshold, cone)`). Zero-length bars are dropped. The
paper's comparison is with Ripser 1.2.1 only, on one laptop (Intel i9-13900HX, 64 GB), 22 dataset/degree cases (the
Ripser paper's `sphere_3`, `fract_r`, `dragon`, `o3_1024`, `o3_4096` and synthetic sets); it reports RedZeD_VR faster in 21,
H1 speedups from about 8.5x (dragon) to about 620x (random distance matrix), Ripser faster on time for H3 of a noisy S^3 at
n = 430 (189 s against 219 s), and Ripser lower on memory in 5 of the 22 cases, all H2 or H3. The paper's own caveat: gains
are smaller in higher degrees and when the data has little topological signal. Those facts are from reading the preprint
through a summarizer (the later sections of the HTML were read, tables not checked cell by cell); Ripser's flags, field and
thread count are not stated in the text that was read. The authors disclose that Claude Opus 5 was used to optimize and
translate the code.

So "current leader" is the authors' claim about Ripser on H1-H2-heavy data, over Z/2, bars only. It is not a comparison with
GUDHI, DIPHA, Dionysus or giotto-ph, and says nothing about p != 2 or representatives.

## What was added

- `bench/redzed_io.py`: RedZeD's input, the float32 lower triangle of the distance matrix, row by row, written once per case
  (untimed, like DIPHA's input) from the float64 points by exact coordinate differences, a block of rows at a time (a
  50,000-point case never holds its n x n matrix).
- `bench.py`: the `redzed` row (`vr`, `only_p: 2`), kind `redzed` in `BINARY_KINDS`, `parse_redzed_output` (the `BAR` lines),
  `available`/`command`, `redzed_git` in `env.json`; `write_bar_rows` is shared with the ripser parser.
- `run.sh`: `setup redzed` (clone, checkout the pinned commit, `g++ -O3 -std=c++17 -DNDEBUG`, the README's flags and no
  `-march=native`, as ripser.cpp's Makefile), exports `REDZED_BIN`.
- `final_run.sh`: a `redzed` stage (p = 2, resumable, merged in), a smoke step (RedZeD against ripser.cpp at p = 2 on the
  smoke cases, a missing tool is an error), and `redzed` in the merged check.
- Tests: `RedzedTests` (the row, the input layout at several block sizes, lower triangle only, reuse, command, parser,
  availability, and with the binary installed its barcode against GUDHI's on points and on a distance matrix) and the
  `FinalRunScriptTests` additions. `python3 bench/test_bench.py`: 181 tests, 7 skipped, with numpy, scipy and gudhi in a
  scratch venv and `REDZED_BIN`, `RIPSER_BIN` set.

## Checked here

- The reference binary builds (g++ 13.3) and `bench/run.sh setup redzed` works from a clean `WORKDIR`.
- 12 point sets (random 2-D, noisy circle, S^2, S^3, torus, random 5-D; H1 and H2): same bars as ripser.cpp built from its
  master, within 1e-4.
- Through the harness (`bench.py run --tools redzed,ripser.cpp`, synthetic data under the case file names): `agrees = True`
  on the sphere, a distance-matrix case and a 300-point S^3 stand-in to H3; p = 17 is `unsupported`.
- RedZeD's values are closer to the exact ones than ripser.cpp's: against float64 GUDHI the distance is 5.9e-8 (RedZeD) and
  5.0e-6 (ripser.cpp, which computes point-cloud distances in float32). That is why the harness's Delta against its
  reference reads about 5e-6 for RedZeD: it is ripser.cpp's rounding, inside the tolerance (1e-5 x threshold).

## Timings seen here: not a measurement

A cloud container, other load unknown, one trial per cell, synthetic stand-ins under real case names (the Ripser-paper data
was not downloaded). The pattern matches the paper's caveat and nothing more should be read into it:
`sphere3_192` (192 points on S^3 in R^4, H2): RedZeD 0.18 s, ripser.cpp 0.50 s. `o3_1024` (300 points on S^3 as a stand-in,
H3 to threshold 1.8): RedZeD 156 s and 1.30 GB, ripser.cpp 108 s and 1.14 GB. The harness's own VR cases go to H3 for `o3_1024`
and `o3_4096` and H2 for the rest, so the table will probably show RedZeD ahead at H1/H2 and not at H3. The server run decides.

## NOT checked

- Nothing on the compute server: no `setup redzed` there, no run on the real data (`sphere3_192`, `o3_1024`, `o3_4096`,
  `dragon2000`, `fractal-r`, `clifford50000`), no `smoke` stage.
- `clifford50000` and `o3_4096`: whether RedZeD's 64-bit indices overflow silently there, and whether its n(n-1)/2 float32
  triangle (5 GB for 50,000 points) plus its edge lists fit; `redzed_io` writing 50,000 points was not run (the code is
  blockwise; its timing is unknown).
- The input asymmetry: RedZeD reads a prepared binary triangle (untimed), ripser.cpp parses text or computes distances inside
  its time. On the cases that take minutes it is noise; on the sub-second ones it favours RedZeD slightly.
- The Python wheel `redzed-tda` was not used: the harness times the CLI, as the paper does.

## Left open

- `sweep-threads --baselines`: RedZeD is single-threaded, so it could be a second baseline for scale. Not added, not tried.
- RedZeD's idea as a TDA4j experiment (an engine that skips (n+1)-births with no active face; Z/2 first, the contract is
  generic `Field` and representatives): not started, not requested.
