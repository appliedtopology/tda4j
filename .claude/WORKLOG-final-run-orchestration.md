# Final benchmark run: what to fix first, and the script that runs it (2026-10-27)

Point-in-time snapshot. Question (project lead): a full benchmark run takes about a week, the paper's run happens once;
which open issues change the numbers, which to fix before it, and a script that runs the final one.

## What the running full run is

The full run (`runs.csv` breakdown pasted by the project lead, 798 rows, 54 h of reported wall time) was on
`f70191c` (the merge of #212, 2026-10-23), had run only the Vietoris-Rips task and stood at clifford50000: cubical, alpha
and the rarer complexes had not started. Where the time went: Dionysus (3 rows) 14.2 h, tda4j-js (2 rows) 9.4 h, JavaPlex
7.0 h (8 ok, 4 real one-hour timeouts), DIPHA (2) 6.3 h, GUDHI (2) 5.9 h, TDA4j JVM rows 3.9 h (7 %). So the week is the
external tools. The three sweeps (alpha, threads, cubical) were also run on `f70191c`.

Merged after it, with a bearing on TDA4j's rows: #217 (emergent pairs, cubical allocation, the compact JVM policy as the
bench default), #222 (`Engine.Auto` picks `ParallelRipser` when `Parallel.threads > 1`; the Čech, DTM, sparse, witness,
alpha and DQP builders are parallel by default), #216 (alpha stops at the enclosing radius), #233 (the JS facade honours
`maxDimension`), #235 (the fastalpha cap, `sweep-alpha --path`). The external tools' rows do not depend on any of it.
Consequence: TDA4j's rows, the JS twins' and the sweeps' TDA4j sides of the `f70191c` run are stale; the others stand.

## Harness gaps found at head (none had an issue), and what this change does

| gap | change |
|---|---|
| `bench.py` never set `sequential=true`, so after #222 TDA4j's rows ran on every core while ripser.cpp and GUDHI ran on one | `command()` adds `sequential=true` to every TDA4j JVM row that is not `multithreaded`; `sweep_cubical.py` too |
| only the bars-only experiment was a TOOLS row, not the shipped `ParallelRipser` | `tda4j-parallelverb-1t` / `-mt` (engine `parallelverb`, cocycles). The driver wraps `parallelverb` in `Parallel.withThreads(threads)` when `threads=` is given. The experiment's rows are `experiment` rows, run only when `--tools` names them |
| alpha cases without a radius: TDA4j stopped at the enclosing radius (#216), GUDHI got none | `default_alpha_radius`: tools that take a radius get the enclosing radius; `no_radius` tools none; `skip_reason` still judges the case's own threshold (so DQP stays out of cases without a radius). `sweep_alpha.py` still passes none: #242 |
| no way to combine a rerun of some tools with the rest of an earlier run | `bench.py merge OUT DIR[:tools]...` (last source wins per (case, p, tool); `!pattern`), `anchors NEW OLD` (do rows both runs have take the same time), `check DIR` (failed rows, disagreeing barcodes), `constraints DIR` (pip pins from `env.json`), `--resume` for `run`, `--tools` patterns |
| `tda4j-js` rows did not record the commit of their npm package (#202) | `setup tda4j-js` writes `tda4j-js.git`; `env.json` has `tda4j_js_git`, `_dirty`, `_stale`; both summaries name it where it differs |
| the bars-only experiment stopped at 46,340 points (a flat `n*n` table of doubles), so it could not run clifford50000 | the table is one row of doubles per point and the limit 60,000 points (`MaxExperimentPoints`; 28.8 GB of heap at the limit, 20 GB for clifford50000). The shipped `ParallelRipser` never had the limit (sparse neighbour lists above `DenseRankLimit = 4096`) |

## Decisions (project lead, 2026-10-27)

- #218 probably no. `Engine.Auto` does not change until the benchmark answers are in place (#223); both engines are
  reported on one thread instead. Raise the experiment's limit to 60k: the server has 256 GB and gives 160 GB to a run.
- Plan agreed: the final run measures TDA4j's rows on one frozen commit, merged with the earlier run's rows for the other
  tools; the script is separate from `run.sh`: `bench/final_run.sh`.

## The script

`bench/final_run.sh` stages: `freeze` (clean tracked tree, commit recorded in `$FINAL/commit`, `setup tda4j tda4j-js`;
every later stage refuses another commit), `smoke` (sphere3_192, o3_1024, dragon2000 at p = 17, TDA4j against ripser.cpp,
`check` stops on an error or disagreement), `anchors` (ripser.cpp, gudhi, giotto-ph-1t on three cases against BASE; verdict
in `anchors.verdict`, the merge refuses a drift), `tda4j` (every JVM row plus the two experiment rows, resumable), `js` (a
subset of the cases at one field, resumable), `merge` (BASE without its TDA4j rows, then the two new directories), `sweeps`.
The protocol (fields, timeout, warm-up, trials, heap) defaults to BASE's own `env.json` arguments. `plan` is a dry run.

## Verified, and not

- Verified: `python3 bench/test_bench.py` (161 tests: the new ones cover the single-thread flag, the alpha radius, merge,
  anchors, check, resume, the pattern syntax, the JS provenance and `final_run.sh` with a fake `run.sh`). `sbt
  tda4j/Test/compile`; the driver's `engine=parallel`, `engine=ripser` and `engine=parallelverb threads=3` give the same
  barcode (cell for cell, F_17) on 150 random points in R^3.
- Not verified: no stage was run against the real tools (no ripser.cpp, GUDHI, Node link or compute server in the cloud
  container). The 60,000-point limit was not exercised past the small cloud: the table is 28.8 GB there. `bench/run.sh
  setup tda4j-js` changes were not run (needs sbt for the npm package, whose Scala.js dependencies the container
  fetches from a mirror). The JS case subset and the anchor cases are guesses (names checked against `CASES`, times
  not): sizes of JS rows other than o3_4096 (about two hours a row) are not known.
- The time of a tda4j-only run (1 to 2 days) is an inference from the 7 % share of the TDA4j rows; with the experiment
  rows on clifford50000 and the parallelverb rows it is not measured.

## Issues

#242 (sweep_alpha radius parity), #243 (sweep directories do not merge), #202 closed by this change, #223 commented.
