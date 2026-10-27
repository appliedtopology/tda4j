# FlashCubical in the benchmark (2026-10-27)

Point-in-time snapshot. Question (project lead): does the benchmark compare with FlashCubical
(github.com/T-prog123/FlashCubical, arXiv:2606.04801)? It did not; this change adds it.

## What FlashCubical is

Commit `eaff91f` ("Update README.md v2", 2026-06-04); a pybind11 + scikit-build-core package (`pip install` builds `_core`
with CMake >= 3.20 and a C++17 compiler). `flash_cubical.compute(x, min_persistence=0.0, h1=True)` returns an object whose
`.values` is a float64 `(n, 3)` array: birth, death, degree; an essential class has death +inf. 2-D and 3-D arrays only, F_2
only, H0 .. H(d-1) (the whole barcode of a grid), zero-persistence pairs dropped, `min_persistence != 0` unimplemented,
`h1=False` only in 3-D. It lives in the **V-construction**: the array holds vertex values and a cell is born at the largest
value of its vertices.

## The construction mismatch, and what was done about it

The harness's cubical task is the **T-construction** (top cells carry the values): GUDHI `top_dimensional_cells`,
CubicalRipser T, TDA4j `Image`. V and T are different complexes: `[[0,5],[5,0]]` has two components at 0 joined at 5 as
vertices, and one component as top cells (they share a corner, born at 0). So FlashCubical cannot be run on the T cases.

- Every cubical case has a `_v` twin: the same `.npy`, read as vertex values (`Case.params = (("construction", "V"),)`, which
  reaches every tool as `construction=V`). `skip_reason`: `flashcubical` is `v_only`, the `tda4j-js` cubical rows are `t_only`
  (their API has no V input).
- GUDHI reads a `_v` case with `CubicalComplex(vertices=...)`, CubicalRipser with its V default (`computePH(a, maxdim)`;
  cripser 0.0.37 has no `filtration=` keyword, so the adapter's existing `TypeError` fallback now also covers V).
- **TDA4j has no vertex-valued input.** The V complex of an `n^d` array has the persistent homology of the T complex of the
  `(2n-1)^d` array whose cell values are the largest vertex value of the cell. The driver builds that array inside the timed
  region (`refineVertexValues`, one max pass per axis: the largest over a product of vertex sets is the largest axis by
  axis). It costs 4x (2-D) / 8x (3-D) the top cells, so TDA4j's `_v` rows are what a user of today's API pays. #245.

## Checked here

- The equivalence V(J) = T(refine(J)): GUDHI `vertices=J` = GUDHI `top_dimensional_cells=refine(J)` = cripser V on J, 2-D and
  3-D, random and tie-heavy arrays, 0 mismatches over 10 arrays (scratch script; now `VConstructionTests` for gudhi and
  cripser, which skip when they are not installed).
- `PaperBenchmarkDriverSpec`: the refinement equals the definition cell by cell (1-D, 2-D, 3-D, ties); the checkerboard
  `[[0,5],[5,0]]` through the verb gives the bars (0,5) and (0,inf) in degree 0 (the T reading gives only (0,inf)); a ring
  of 8 low vertices round a high one gives the loop (1: 0 to 5).
- `python3 bench/test_bench.py` (171 tests, 6 skipped for missing tools) with GUDHI and cripser installed from PyPI into a
  scratch venv; the adapter against a stand-in `flash_cubical` module (column order, degree filter, inf, exit 3 for a T case
  or p != 2); `final_run.sh` with the fake `run.sh` (the smoke-v and `cubicalv` stages, the merge sources).

## NOT checked

- **FlashCubical itself was never built or run here.** The cloud session's permission classifier denied the build
  (`pip install --no-build-isolation .`) with no reason, and the build was not retried by another route. So the
  `flash_cubical.compute(...).values` column order and the essential-class convention are read from the source
  (`src/flash_cubical/__init__.py`, `persistence.py`, `cpp/bindings.cpp`) and from its README, not observed, and **its barcode
  against cripser/GUDHI is unverified**: the smoke-v stage of `bench/final_run.sh` is that check, and stops the final run if
  FlashCubical disagrees or is not installed. Run `bench/run.sh setup flashcubical` first on the server.
- The 3-D V rows at 128^3 (255^3 refined for TDA4j, 16.6 million top cells) were not run: sizes of the TDA4j rows are unknown,
  timeouts and out-of-memory runs are recorded as results.
- `setup flashcubical` was not run (no build in the container).

## Left open

- #245: a vertex-valued `Image` in the library (and the facades); the benchmark's V rows would use it.
- #139 (commented): a DIPHA row for the `_v` cases, whose cubical input is the V-construction.
- FlashCubical's own paper reports its speed against which tools, on which data: not read here; the `_v` ladder (512 to 2048
  in 2-D, 32 to 128 in 3-D, uniform noise and a blob) is the harness's existing set, not the paper's.
