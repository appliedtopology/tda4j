# Cross-platform benchmark

Times TDA4j against the persistent-homology libraries at the frontier on the same inputs, on one machine, and the
Scala.js build of TDA4j (the npm package, on Node.js) beside the JVM build. It also checks that every tool returns the
same barcode. The paper's claim is "what the others do, on the JVM, at a
quantified slowdown", so the output is that slowdown, per case and as a geometric mean. The method is in
`.claude/PLAN-paper.md` §3.0.

## Running it

```sh
# on the compute server, from the repository root
export WORKDIR=/scratch/tda4j-bench        # tools, data and results go here (default ./bench-work)
export JAVAPLEX_JAR=/path/to/javaplex.jar  # optional: TDA4j's predecessor
# julia on PATH (or JULIA=/path/to/julia) adds Ripserer.jl and Eirene.jl
# an MPI implementation with mpicxx (apt install libopenmpi-dev openmpi-bin) and cmake add DIPHA and PHAT
# node 20.16 or newer on PATH (or NODE=/path/to/node) adds the Scala.js build of TDA4j

bench/run.sh setup        # venv + pip tools, FlashCubical, TDA4j (JVM), TDA4j (npm package), ripser.cpp (Z/2 and coefficient builds), RedZeD, Ripserer.jl, JavaPlex, DIPHA, PHAT, Eirene.jl
bench/run.sh prepare      # downloads the Ripser-paper data, generates the synthetic sets (seeded)
bench/run.sh run --fields 2,17 --timeout 3600 --jvm-heap 32g
```

Re-running is safe: `setup` and `prepare` skip what exists, and every `run` writes a new results directory (`RESULTS_DIR=...
bench/run.sh run` names it, so that `--resume` can find it again).
`bench/run.sh setup tda4j-js` redoes one component (`python flashcubical tda4j tda4j-js ripser redzed julia javaplex dipha phat eirene`), for example after
changing the library: the JVM tool uses the compiled classes, the JavaScript one the npm package, and both are rebuilt
by their own `setup` step. Run the steps one after the other: they use sbt, and two sbt processes on one checkout
corrupt each other's builds.

To see the harness work before committing hours, run a small subset:

```sh
bench/run.sh run --cases sphere3_48,sphere3_96,random2d_512,unif2d_1000 --warmup 1 --trials 2
```

Useful options of `run`:

| option | meaning |
|---|---|
| `--tasks vr,cubical,alpha` | which families of cases |
| `--cases a,b` | named cases only (see `CASES` in `bench.py`); names or patterns as for `--tools` (`*_v` the V-construction cubical cases, `!*_v` all others) |
| `--tools tda4j,ripser.cpp` | named tools only (see `TOOLS` in `bench.py`); patterns work (`'tda4j*'`) and `!pattern` leaves tools out (`'tda4j*,!tda4j-js*'` is every JVM row of TDA4j). A row the harness lists as an `experiment` (the bars-only `tda4j-parallel-1t` and `-mt`) runs only when its own name is here |
| `--resume` | continue an interrupted run in `--out`: the (case, field, tool) groups already finished there are skipped, and a timeout among them still stops the later fields. Run it with the same options |
| `--fields 2,17` | coefficient primes; tools that only do Z/2 report `unsupported` at 17 |
| `--warmup N --trials N` | warm runs: untimed and timed computations per process |
| `--timeout S` | seconds per computation; a timeout is recorded, not dropped |
| `--rerun-timeouts` | a computation that timed out at one field is not run at the next (its time does not depend on the field; `--fields 2,17` would cost the timeout twice) and is recorded as timed out; this runs it anyway |
| `--warm-cap S` | if one cold computation takes longer than this (default 60 s), the warm run reuses it instead of repeating |
| `--jvm-heap 32g` | `-Xmx` for TDA4j and JavaPlex. The JVMs use the serial collector with a heap that follows the live data (10-20 % free) up to this size, so peak RSS is close to what a computation needs and comparable with the native tools (one policy for this script, `sweep-cubical`, `sweep-alpha` and `count-cells`). fractal-r needs about 12g; clifford50000 more |
| `--jvm-opts "-Xss8m"` | more JVM options |
| `--jvm-fixed-heap` | `-Xmx` = `-Xms` with the parallel collector instead. RSS is then what a run allocated until the first collection (a 16g heap never collects a 3 GB run), not what it needs, and the trials of a warm run add up in it. `sweep-cubical`, `sweep-alpha` and `count-cells` take it too; `sweep-threads` always uses it, because its multithreaded JVM tools allocate on every thread and a serial collector would collect their garbage on one |
| `--node-heap 32g` | `--max-old-space-size` for the `tda4j-js` tools, in the same notation (default: `--jvm-heap`). Node's own default is a few GB, far below what the large cases need |
| `--node-opts "--stack-size=4000"` | more options for node |

## Counting the cells

Each case has one reference count of the cells of the complex it reduces, in `bench/cells-reference.csv` (checked in). A
benchmark run never counts: `bench.py summarize` joins the reference as the `cells` column of `summary.csv` and `summary.md`, so
a time can be set against the size of the problem. The unit is the cells of dimension 0 to `dim + 1` at the case's threshold: the
whole complex, not what an engine touches (no apparent pairs, no clearing, no implicit complex). The file also has the cells of
each dimension, the source and a check, and the SHA-256 of the data file that was counted.

`bench/run.sh count-cells --reference` makes or updates it (`count_cells.py`; `--cases a,b` recounts only those, a count that
fails never replaces a good one), and `bench/run.sh count-cells --verify` checks that your `data/` has the files it was counted
from. Without `--reference` the counts go to `$WORKDIR/results/cells-<timestamp>/` only: `cells.md` (the table), `cells.csv`,
`cells_sources.csv` (one row per case and source, with time and peak memory).

| task | counted by | check |
|---|---|---|
| Vietoris–Rips | `workers/count_cliques.cpp` (compiled with `g++`, OpenMP if there is any): every clique of the threshold graph, by degeneracy ordering and bitsets; exact | TDA4j's own stream iterated (`PaperBenchmarkDriver count=true`), when the count is under `--cross-check-max` (3e5; the stream yields about 8,000 cells per second) |
| cubical | the images are full grids: the product over the axes of `(n + 1) + n x` (a `_v` case counts the grid of `n - 1` top cells per axis: its array holds the vertices) | the unit tests enumerate small grids cell by cell |
| alpha | GUDHI's alpha complex up to `--gudhi-max-ambient` (5), and TDA4j's stream; the second is the only count for the cases CGAL cannot reach (`unif10d_1000`, `o3_1024_alpha`, `cyclooctane_alpha`) | where both ran they are compared; a case with one source says "single source" |

A count that needs more than `--timeout` seconds (3600) is a row that says so, with the vertices, the edges and the bound
"every subset"; no row is an estimate. The graph is `distance <= threshold` with a relative margin of 1e-9, so the edge that
defines the enclosing radius is in, and a distance matrix is read from its lower triangle (as ripser.cpp and TDA4j do).
`--no-jvm` skips TDA4j (no `setup tda4j` needed), `--no-gudhi` skips GUDHI, `--threads N` limits the counter, and `--join
RESULTS` adds the totals to a finished run's `summary.csv` as `summary_with_cells.csv` (for a run made before the reference existed).

## The image-engine size sweep

`bench/run.sh sweep-cubical` decides which engine `Persistence(Image(...))` should pick by default. It times:
- fast cubical, today's `Auto`;
- cohomology, with cycles and with cocycles;
- chunks, 2-D only and capped at 512²;
- CubicalRipser and GUDHI, alongside for scale;
- the Scala.js build of TDA4j (`js-fastcubical`, `js-cohomology-cycles`: the first two engines on Node.js) when
  `setup` installed it, with a `js/jvm` column in `sweep.md`; `--no-js` leaves it out.

The images are noise and a blob, in 2-D up to 4096² and 3-D up to 128³.

Each point runs cold first. It is repeated (one warm-up, then `--trials`) only if the cold run took under `--warm-cap`
seconds. A series stops at its first timeout. Output: `sweep.csv`, and `sweep.md` with µs per pixel and the
fast/cohomology ratio.

```sh
bench/run.sh sweep-cubical --dims 2,3 --jvm-heap 32g --timeout 1800
```

## The alpha dimension sweep

`bench/run.sh sweep-alpha` asks two things of the alpha complex: whether TDA4j's code holds up against other
implementations, and what the ambient dimension and the point count cost each backend. The inputs are random points of
the unit cube, the kind the Helix thesis uses (as it was described to us; it was not read): n = 40 and 60 points uniform
in the interior or on the surface, over the dimensions 5, 6, 7, 8, 10, 12, 15, 20, 25 (`--points`, `--dims`, `--shapes`);
`--grid "11:40,100,300,900"` runs a series over n at a fixed d, and `--extra 800:11:interior` single cells. Points on a
sphere are deliberately not sampled: they are all cospherical, and every Delaunay-based tool then triangulates the one
degenerate cell its own way.

Four presets (`--path`) cover what the sweep is read by, each setting the options you do not give:

| `--path` | what it runs |
|---|---|
| `top` | R^d for d = 3 to 12, every degree up to d - 1: the whole barcode, where the fast alpha engine's dual union-find does the top degree and `tda4j-fastalpha-top` computes that degree alone |
| `four` | R^d for d = 5 to 15, degrees up to 4: more than the default sweep's 2, less than the whole barcode |
| `plane` | R^2, the point count from 1 000 to 3 000 000 (`--grid`), degrees 0 and 1: the point count is raised until each tool gives up |
| `radius` | R^d for d = 8 to 10, n = 40 and 60, interior points, every degree up to d - 1, at a quarter, a half, three quarters and all of the enclosing radius (`--radii`) for `tda4j`, `tda4j-helix`, `tda4j-dqp` and `gudhi`: the triangulations build the whole complex whatever the radius, the dual quadratic program only what lies within it |

Without `--path`, `--dim` (a number, or `top`; at most d - 1) sets the highest degree, 2 by default.

`--radii 0.25,0.5,1` runs every cell once per fraction of the enclosing radius (the smallest over the points of the largest distance from a point), giving each tool that radius as its largest; a tool that takes no radius (the fast alpha engine) is left out. `sweep.md` has one block per radius, and `sweep.csv` a `radius` column.

Tools: `tda4j`, `tda4j-helix`, `tda4j-fastalpha`, `tda4j-fastalpha-top` (the top degree alone), `tda4j-dqp`, `tda4j-cech`, `gudhi`, and the `tda4j-js` twins. A barcode is
compared with the first of GUDHI, the Cech complex and the dual-QP that finished on the same draw. GUDHI (CGAL) is the other
implementation but stops early in d. The Cech complex (minimum enclosing balls, no triangulation) and the dual-QP (a
quadratic program per simplex) are other algorithms than the Delaunay walk, and by the nerve lemma the Cech and alpha
complexes have the same barcode in general position, so their agreement to rounding is a check at dimensions where GUDHI
cannot run. At these point counts the dual-QP needs no radius: it builds every candidate simplex up to dimension 3 (at most
C(n, 4) of them). Nothing from another paper's tables is used: their timings were measured in other settings.

Each cell runs on `--seeds` draws (default 3; start with 1), cold and warm, and a tool's series stops at its first timeout or
out-of-memory. The defaults assume a machine like the compute server's (`--jvm-heap 64g`, `--timeout 1800`, GUDHI capped at
`--py-mem-gb 128`).

```sh
bench/run.sh sweep-alpha --seeds 1 --dims 5,6,7,8 --points 40 --shapes interior   # a quick look
bench/run.sh sweep-alpha --seeds 1 --path top                                       # the whole barcode in R^d
bench/run.sh sweep-alpha --seeds 1 --path radius                                    # time and memory against the radius
bench/run.sh sweep-alpha --extra 800:11:interior                                    # the whole grid
```

Failure rates and simplex counts are separate options (`--validity`, `--count`): a rate needs hundreds of draws per cell, not
three, so it runs in one JVM per cell on the driver's own draws. The points are noise: in high dimension the
distances concentrate, filtration values cluster, and a few nearly flat simplices have circumradii of 50 and more in a unit
cube, so the bottleneck distance between backends is mostly the agreement of near-degenerate values; read it as a
correctness stress, not a statement about topology. Cube-surface draws with d + 1 points on one facet are not in general
position (marked †).

## The thread-scaling sweep

`bench/run.sh sweep-threads` runs every multithreaded Vietoris–Rips tool (`tda4j-parallelverb-mt`, the engine `Engine.Auto`
picks with several threads, with its representatives; `tda4j-parallel-mt`, the bars-only block-parallel experiment;
`giotto-ph-mt`, and `dipha-mt`, whose threads are MPI ranks) at 1, 2, 4, ... threads up to the machine's logical CPUs
(`--threads 1,8,32` picks others), with `ripser.cpp` once per case for scale (`--baselines`). The default cases are
fractal-r (2.2 billion cells to H2) and `cube10_40`, which the sweep generates: 40 points uniform in [0,1]^10 to H10
at t = 1.6 (235 million cells), high degrees where the work splits by edge block. `--cases o3_4096` is a heavy case whose
keys are two words; `--cases clifford50000` is the largest point set (the bars-only experiment tabulates its distances, 20 GB of
heap; it takes up to 60,000 points).

A run at t threads is pinned to the CPUs 0 to t - 1 with `taskset`, so the JVM's garbage collector and compiler and the
MPI ranks get those t CPUs too (with Open MPI the sweep adds `--bind-to none`, so the ranks stay inside the set). Use
`--no-pin` to let them use the whole machine. A tool runs from the most threads down; its series stops at the first
timeout or error, and goes on after running out of memory.

Each run is the main harness's: a cold run, then one warm-up and three timed computations (`--warmup`, `--trials`) if
the cold computation took under `--warm-cap` seconds. Output, in `$WORKDIR/sweeps/threads-<timestamp>/`: `sweep.csv`
(the main run's columns plus `threads` and `pinned`), `bars/`, `logs/`, `env.json`, and `sweep.md`. Per case it shows
each tool's time at each count, the speedup over its own single thread, its fastest time against ripser.cpp's, and two
checks: the barcode is the same at every count, and its distance to ripser.cpp's.

```sh
bench/run.sh sweep-threads --jvm-heap 32g
bench/run.sh sweep-threads --cases o3_1024 --tools tda4j-parallelverb-mt,giotto-ph-mt --threads 1,4,16,32
```

## The rarer complexes

Tasks `cech`, `dtm`, `witness`, `sparse` and `dowker` (and the `tda4j-collapse` row of `vr`) time the complexes that few
other libraries have. What each row computes is not always the same object, so read the agreement column with that in mind:

| task | TDA4j | other rows | reference | what the agreement column means |
|---|---|---|---|---|
| `cech` | `Cech` | GUDHI `DelaunayCechComplex` | GUDHI | same persistent homology, so a real check (general position) |
| `dtm` | `DtmRips.fromNeighbours` (`k`, p = 1) | GUDHI `DTMRipsComplex` | GUDHI | a real check (byte-identical on small inputs) |
| `witness` | lazy (nu = 2) and `wvariant=general` | GUDHI weak and strong witness | GUDHI | unverified that GUDHI's are our definitions (#171): a nonzero distance is a finding, not yet a bug |
| `sparse` | `SparseRips` (Cavanna-Jahanseir-Sheehy) | GUDHI `RipsComplex(sparse=eps)` (Buchet-Chazal-Oudot-Sheehy) | ripser.cpp, exact Rips | the approximation error of each construction; the two epsilons are not comparable |
| `dowker` | `Dowker`, and `.dual` (`tda4j-dual`) | a GUDHI `SimplexTree` filled by hand, Blaser and Brun's `dowker_homology`, Yoon's Julia package | GUDHI | a real check for exact tools |

A case's `params` (`eps`, `k`, `L`) reach every tool as `key=value`. A witness case's landmarks are chosen once by maxmin and
written to `data/landmarks/` so that every tool uses the same ones. `bench/run.sh setup dowker` clones the two Dowker
packages at pinned commits (neither is pip-installable with current tools; the Python one runs with three small shims in
`py_worker.dowker_nerves_tool`, the Julia one wants Julia 1.7.2 and is skipped if its dependencies do not resolve).

## One thread, or all of them

The main tables are single-threaded, as ripser.cpp, RedZeD and GUDHI are: every TDA4j row runs with `sequential=true`, so the
library's parallel steps (the Čech, DTM, sparse Rips and witness builders, alpha, `ParallelRipser`) run on the main thread.
The rows that run on every processor say so in their name and are `multithreaded` in `runs.csv`: `tda4j-parallelverb-mt`
(`Engine.ParallelRipser` through `Persistence`, with cocycles), `tda4j-parallel-mt` (the bars-only experiment),
`giotto-ph-mt` and `dipha-mt`. Their single-thread twins are `tda4j-parallelverb-1t`, `tda4j-parallel-1t` and
`giotto-ph-1t`, and `sweep-threads` gives the scaling in between. `tda4j` itself is `Engine.Auto` on one thread, i.e.
Ripser; whether `Auto` should choose `ParallelRipser` on one thread is the comparison of `tda4j` against
`tda4j-parallelverb-1t` (#223).

The alpha cases without a radius of their own give every tool that takes one the enclosing radius: TDA4j's alpha complex
stops there by default and GUDHI's does not, and the barcode no longer changes after it. The tools that take none
(`tda4j-fastalpha`, which reduces the whole triangulation) are given none.

## The final run

`bench/final_run.sh` runs the final benchmark of the paper in stages on one frozen commit of TDA4j. A full run takes about
a week, and nearly all of it is the other tools, whose rows do not depend on TDA4j's commit; so the final run measures
TDA4j's rows (the JVM build everywhere, the Scala.js build on a subset), checks that the machine still runs the other tools
as it did, and merges both with the earlier run's rows into one set of tables.

```sh
BASE=$WORKDIR/results/20261020-101500 bench/final_run.sh plan    # every command, nothing run
BASE=$WORKDIR/results/20261020-101500 JVM_HEAP=160g bench/final_run.sh all
bench/final_run.sh status                                        # while it runs
```

| stage | what it does |
|---|---|
| `freeze` | requires a clean checkout, records its commit in `$FINAL/commit`, builds the JVM classes and the npm package. Every later stage refuses to run at another commit |
| `smoke` | `sphere3_192`, `o3_1024`, `dragon2000` at p = 17: every TDA4j row against ripser.cpp; RedZeD against ripser.cpp on the same cases at p = 2; then `random2d_512_v` and `random3d_32_v` at p = 2: TDA4j, CubicalRipser and FlashCubical against GUDHI (`vertices=`), installing FlashCubical first. Stops the run on an error, a barcode that disagrees or a tool that is not installed |
| `anchors` | reruns `ripser.cpp`, `gudhi` and `giotto-ph-1t` on three cases and compares the times with `BASE`'s (`bench.py anchors`). A row more than 20 % away from the earlier run's means this is not the machine, or not the load, that measured it |
| `tda4j` | every TDA4j JVM row on every task, including the shipped parallel engine and the bars-only experiment (`clifford50000` too: the experiment takes up to 60,000 points). Resumable |
| `js` | the Scala.js twins on a subset of the cases at one field. Resumable |
| `cubicalv` | GUDHI, CubicalRipser and FlashCubical on the `_v` cubical cases at p = 2 (`BASE` has no rows for them; TDA4j's own `_v` rows are in the `tda4j` stage). Resumable |
| `redzed` | RedZeD on every Vietoris–Rips case at p = 2 (`BASE` has no rows for it). Resumable |
| `merge` | `BASE` without its TDA4j rows (they are from the older commit), then `$FINAL/tda4j`, `$FINAL/js`, `$FINAL/cubicalv` and `$FINAL/redzed`: `bench.py merge`. Refuses when the anchors drifted (`ALLOW_DRIFT=1` overrides). Runs `bench.py check` on the result |
| `sweeps` | the threads, cubical and alpha sweeps. A sweep directory has its own tables and does not merge, so each sweep reruns its external tools too |

The measurement protocol (fields, timeout, warm-up, trials, heap) is `BASE`'s own, read from its `env.json`, unless the
environment says otherwise; the header of the script lists the variables. The bars-only experiment tabulates all distances
(8 n² bytes: 20 GB for `clifford50000`), so the heap must hold that.

The commands under it are `bench.py`'s, usable on their own:

| command | what it does |
|---|---|
| `bench/run.sh merge OUT DIR[:tools]...` | one results directory from several. For each (case, field, tool) the rows come from the last source that has them, with their bars and logs; `DIR:tool,tool` takes only those tools, `DIR:'!tda4j*'` all but those; `--drop` leaves tools out. `OUT` is summarized, and its `env.json` is the last source's that ran TDA4j (so the summary names its commit) with each source's directory, date, host and tools under `merged_from` |
| `bench/run.sh anchors NEW OLD [--tools a,b] [--tolerance 0.2]` | the rows two runs share, with their times and the ratio; exit status 1 when one is outside the tolerance, 2 when none is common |
| `bench/run.sh check DIR [--tools 'tda4j*'] [--allow unsupported,missing]` | the rows of a summarized run that failed or whose barcode differs from the reference's (for the Vietoris–Rips, cubical and alpha tasks); exit status 1 if there are any |
| `bench/run.sh constraints DIR` | the Python packages a run measured, as a pip constraints file; `PIP_CONSTRAINTS=file bench/run.sh setup python` installs them in a new `WORKDIR` |

`setup tda4j-js` leaves a note of the commit and the changed files its npm package was built from; `env.json` records it as
`tda4j_js_git` (with `tda4j_js_stale`, whether `src/main` has changed since), and both summaries name it where it is not the
JVM classes' commit.

## What is measured

- **cold:** one process, one computation, from launch to exit: startup, JIT, reading the input. This is what a
  command-line user, or a first call from MATLAB, sees.
- **warm:** one process, `--warmup` untimed then `--trials` timed computations; the median of the timed ones. This is
  what a library user in a long-running JVM or MATLAB session sees. For ripser.cpp, which has no loop, warm is the
  median of `--trials` cold runs.
- **peak RSS** of each process: the kernel's `VmHWM`, sampled from `/proc` every 10 ms while it runs. (`wait4`'s
  `ru_maxrss` includes the harness's own RSS at the moment of the spawn, because the kernel folds the parent's
  high-water mark into the child at `exec`: a child that used 40 MB reported 261 MB from a 250 MB parent. In the first
  run, 41 of 338 rows read exactly 130 MB.) A process too short to be sampled falls back to `ru_maxrss` and is marked
  `≤` in the summary.
- **JVM heap:** a JVM's RSS follows its collector (a parallel collector with a large heap shows its total allocation
  until the first collection), so the TDA4j driver also reports the peak use of the heap pools over the timed runs
  (`heap_peak_mb`: the sum of the pools' peaks, an upper bound on the heap in use at once; `old_peak_mb`: the old
  generation alone, the closest to what the computation keeps). Use `heap_peak_mb` for a statement about what TDA4j
  needs; RSS is what the machine saw. Node's heap grows on demand, so its RSS is already close to its need.
- **Scala.js:** the `tda4j-js` tools run the optimized link (`fullLinkJS`, what the npm package ships) in Node.js, one
  process per computation like the others. Cold therefore includes Node's startup and loading the library's module;
  warm is the V8 JIT after `--warmup` computations. There is one thread (`Parallel` is a loop there). The package's
  `persistence` call is timed with `minPersistence: 0`, so every bar of positive length is reported, as by the JVM
  driver. `read=true` (a worker option, not set by the harness) also reads every representative inside the timed region.
- **agreement:** each barcode is compared with the reference tool's: ripser.cpp for Vietoris–Rips, GUDHI for cubical
  and alpha. The comparison uses the bottleneck distance per degree, and essential bars matched by birth.
  - ripser.cpp computes in float32, so the tolerance is `1e-5 × max(1, threshold)`.
  - For JavaPlex the tolerance is `threshold / divisions`, since JavaPlex snaps filtration values to a grid.
  - A distance below 1e-12 is 0. `inf` means a different number of essential bars in some degree, and the summary's
    notes say which degree and how many.
  - The `bars` column of the summary counts the bars that are essential or longer than the tolerance, so a tool that
    keeps near-zero bars does not look as if it disagreed; the raw counts are in `runs.csv`.
- **failures:** a run that does not finish has a status and a reason (the stderr line that explains it):
  `timeout`; `limit` (the tool's own range: Ripser's coefficient builds keep the coefficient in the same 64-bit field
  as the simplex index, with 8 bits fixed whatever the prime, so a simplex index above 2^55 - 1 is an uncaught
  `std::overflow_error`; clifford50000 needs C(50000, 4) = 2.6e17 at degree 2, and fails at p = 17 for ripser.cpp and
  giotto-ph, and at every p for ripser.py, which always builds with coefficients); `oom`; `unsupported` (the tool does not
  offer it, or the harness knows it cannot run, for example Bowyer–Watson above 4-D); `error(code)`; `missing` (not
  installed). `summary.md` lists them with their reasons under "Failures and limits".

## The cases

- **Vietoris–Rips:**
  - the Ripser paper's benchmark: the cases and flags of `Ripser/ripser-benchmark`'s Dockerfile, minus `--ratio`,
    which only filters printed output;
  - a sphere3 size ladder (48, 96, 192 points).
- **Inputs are made identical across tools:**
  - **fractal-r is symmetrized.** It is not symmetric as distributed. ripser.cpp reads the lower triangle, so every
    tool gets that lower triangle mirrored.
  - **Every tool gets the same explicit threshold.** Where a case has none, it is the enclosing radius, ripser.cpp's
    default.
- **Cubical:**
  - sublevel filtrations, in the T-construction (top cells carry the values);
  - uniform noise, and a bright Gaussian blob on a dark background, in 2-D up to 2048² and 3-D up to 128³;
  - GUDHI with `top_dimensional_cells`, and CubicalRipser with `filtration="T"` (the `tcripser` module in older releases).
  - **V-construction twins (`_v`)** of every case, on the same arrays: the array holds the values of the *vertices* and a
    cell is born at the largest value among its vertices (the lower star filtration). FlashCubical, DIPHA and
    CubicalRipser's default take this construction, so it is the one on which FlashCubical can be compared; a T case and a
    `_v` case are different complexes with different barcodes, and each tool runs only the cases it can read
    (`flashcubical` the `_v` ones, the `tda4j-js` rows the T ones). GUDHI reads a `_v` case with `vertices=`, CubicalRipser
    with its V default, and **TDA4j has no vertex-valued input** (#245): it runs on the T image of `(2n - 1)` cells per axis
    that gives every cell its birth value, which has the same persistent homology and 4 (2-D) or 8 (3-D) times the top
    cells, and building it is inside the timed run. The reference for a `_v` case is GUDHI as for the others; a `_v` row of
    TDA4j therefore costs what a user of today's API pays, not what a native input would.
- **Alpha:** filtration values are circumradii (GUDHI reports squared radii, so we take square roots).
  - Uniform points in 2-D and 3-D.
  - The Stanford dragon (2000 points) and sphere3 (192 points on S³ in R⁴) are **degenerate**: the sphere's points are
    cospherical, the dragon is a scan. Each tool triangulates a degenerate cell its own way, so GUDHI is no ground
    truth and `agrees = False` there is a property of the input (`rules/alpha.md`; #130). They are kept and flagged †,
    and every run also has `dragon2000_alpha_gp` and `sphere3_192_alpha_gp`: the same points moved by Gaussian noise of
    1e-3 of the bounding-box diagonal (seeded, one file for all tools). If a `_gp` case still disagrees, that is a TDA4j
    bug.
  - **Higher ambient dimension** (#131, #133), the regime the dual-QP (DQP) and Helix backends are for: `unif5d_500` (the
    Helix crossover table's 5-D case; CGAL still finishes it); `unif10d_1000`, with the parameters of the DQP paper's random
    example (Carlsson and Carlsson, Sci. Rep. 14:19824, §4.2: 1000 points uniform in [-1,1]¹⁰, radius 1.0, complex up to
    3-simplices), where a full Delaunay triangulation is out of reach, so GUDHI and Helix are expected to time out: that is
    a result; `o3_1024_alpha` (the 3×3 orthogonal matrices of the Ripser benchmark, in R⁹, with the radius at which a
    point has about 25 neighbours within 2r, the statistic that decides between DQP and a triangulation); and
    `cyclooctane_alpha` (below).
  - **Cyclooctane** (`cyclooctane`, `cyclooctane_alpha`): 6040 conformations of the molecule in R²⁴, which JavaPlex's MATLAB
    tutorial ships as `pointsCycloOctane.mat`. `prepare` downloads it from the JavaPlex repository, pinned to a commit and
    checked against its SHA-256, and converts it with scipy (`cyclooctane.txt`). The Vietoris-Rips case runs Ripser to the
    cutoff 0.5, the alpha case DQP to the radius 0.25 (two balls of radius r meet at distance 2r), degrees 0 to 2. The
    summary's `betti` column has the bars alive at the radius, the Betti numbers of the complex there, per field; no number
    from a paper is compared with.
  - A case with a radius gives it to every tool: TDA4j's dispatch and DQP build only what lies within it (bars alive at
    the radius are essential), GUDHI gets `max_alpha_square`, and the tree is cut above dimension `dim + 1`.

## The tools

| tool | how | notes |
|---|---|---|
| `tda4j` | `PaperBenchmarkDriver` (test scope) through the `Persistence` verb | the defaults a user gets: cycle representatives, `Engine.Auto` |
| `tda4j-cocycles` | the same with `Representatives.Cocycles` | the native kind of the Ripser engine; closest to bars-only |
| `tda4j-js`, `tda4j-js-cocycles` | `workers/tda4j_js_worker.mjs` on Node.js, through the npm package's `persistence`, `persistenceFromDistances` and `persistenceFromGrid` | the Scala.js build of the two rows above: the same library, the same engines, compiled to JavaScript |
| `tda4j-js-cohomology`, `tda4j-js-fastalpha`, `tda4j-js-bw`, `tda4j-js-helix` | the same, named engines and backends | JavaScript twins of the next row's tools |
| `tda4j-cohomology`, `tda4j-fastalpha` | named engines | cohomology on images (on a grid: the packed grid engine, the general engine's output); the dual union-find on alpha (on the default triangulation) |
| `tda4j-bw`, `tda4j-helix`, `tda4j-dqp` | named alpha backends | `tda4j` uses the default: Bowyer–Watson up to 4 dimensions, Helix above, DQP for a small radius; these name each. Bowyer–Watson is `unsupported` above 4-D; DQP only runs on a case with a radius (untruncated it is about 100 times slower than Helix); `tda4j-fastalpha` takes no radius |
| `tda4j-parallel-1t`, `tda4j-parallel-mt` | `PaperBenchmarkDriver engine=parallel threads=N` | the block-parallel experiment `ParallelPackedCohomology` (JVM only): implicit coboundaries, bars only, on 1 thread or every CPU. A simplex is one `Long` key where it fits and a block and an index beyond (o3_4096); it fails on a case whose n × n tables do not fit (clifford50000) |
| `ripser.cpp` | vanilla build for p = 2, `ripser-coeff` for other primes | the reference for Vietoris–Rips |
| `redzed` | C++17, built from a pinned commit (`setup redzed`: `g++ -O3 -std=c++17 -DNDEBUG`) | RedZeD (Kapulkin and Kershaw, arXiv:2606.06310; `nkershaw01/RedZeD`, commit `22ef1bf`), Vietoris–Rips only, **Z/2 only**, homology, one thread, bars (`--barcode`) and no representatives. Its input is the binary lower triangle of the distance matrix as float32 (`redzed_io.py`), made once from the float64 distances before the run and not timed, as DIPHA's input is; ripser.cpp's own parse or distance computation is inside its time. It caps the threshold at the enclosing radius itself, drops zero-length bars, and has no overflow check on its 64-bit simplex indices where ripser.cpp throws (the agreement column is the only guard near 2^63 simplices) |
| `ripser.py`, `giotto-ph-1t`, `giotto-ph-mt` | pip | giotto-ph multithreaded is reported, never the baseline |
| `gudhi`, `gudhi-collapse` | pip | edge collapse first in the second variant; on the cubical cases `top_dimensional_cells` (T) or `vertices` (the `_v` cases) |
| `cripser` | pip | CubicalRipser, Z/2 only; `filtration="T"` (or `tcripser`) on the T cases, the V default on the `_v` ones |
| `flashcubical` | pip, built from a pinned commit (`setup flashcubical`: a C++17 compiler and CMake 3.20) | FlashCubical (T-prog123, pybind11; commit `eaff91f`), **Z/2 only, 2-D and 3-D arrays, the V-construction only**, so it runs the `_v` cases and is `unsupported` on the others. `flash_cubical.compute(array).values` is one row per bar (birth, death, degree; an essential class has death inf); zero-persistence pairs are not reported. The package's version is 0.1.0 whatever the commit, so the pinned commit is in `run.sh` |
| `dionysus`, `dionysus-homology`, `dionysus-cocycles` | pip (`dionysus`) | Dionysus 2 on Vietoris–Rips only: `fill_rips`, then its cohomology algorithm (the fast one, so the main row), the clearing homology reduction, or cohomology with a representative cocycle read for every bar. float32. No alpha complex in the wheel (that needs CGAL), and `fill_freudenthal` is a lower-star filtration of the Freudenthal triangulation, not the T-construction of the cubical cases |
| `oat`, `oat-cycles` | pip (`oat_python`) | OpenAppliedTopology (Rust), Vietoris–Rips only: a U-match decomposition of the boundary matrix; the second returns a cycle representative for every bar. It computes **over the rationals** whatever `p` is (no modulus), so a row at `p = 2` or `17` can differ from the reference where there is `p`-torsion; `summary.md` says so under "Agreement notes" |
| `ripserer`, `ripserer-involuted` | Julia | the second asks for cycles (involuted), like TDA4j's default |
| `eirene`, `eirene-cycles` | Julia (`setup eirene`) | Eirene.jl 1.4.0 (GitHub master at a pinned commit: the registered 1.0.0 to 1.3.6 cannot be resolved on Julia 1.10, their CSV and DataFrames bounds exclude each other), Vietoris–Rips, **Z/2 only**. `eirene` is `record="none"`, the barcode, as the Ripser paper's Dockerfile runs it; `eirene-cycles` is `record="cyclerep"` and reads a representative cycle for every bar |
| `dipha`, `dipha-mt` | CMake + MPI (`setup dipha`) | DIPHA at a pinned commit, Vietoris–Rips, **Z/2 only**, always `--dual` as in the Ripser paper's Dockerfile, `--upper_dim dim + 1`. Its input is a sparse distance matrix with the threshold in it (`dipha_io.py`, written once into `data/dipha/` and not timed); its values are radii (half a distance), so the bars are doubled; the second row is `mpiexec -n <cores>`, reported but never the baseline, and its memory is the sum over the ranks. DIPHA's cubical input is the V-construction, which the `_v` cubical cases are; there is no DIPHA row for them yet (#139), so it runs only Vietoris–Rips |
| `phat`, `phat-dual` | CMake (`setup phat`) | PHAT at a pinned commit reduces a prebuilt filtered boundary matrix over Z/2 (twist, bit-tree pivot columns; the second on the dual). It builds no complex: `make_phat` writes DIPHA's Vietoris–Rips complex as a boundary matrix beforehand (`data/phat/`, not timed), so these `kernel` rows time the reduction alone (reading the matrix, reducing, writing the pairs) and are never the baseline. A case above `--explicit-max-cells` (1e8, by `cells-reference.csv`) is not written out |
| `javaplex` | `workers/JavaPlexWorker.java` | written against JavaPlex 4.x's API but **not yet compiled against a real jar** |

PHAT, DIPHA, RedZeD and the plain Eirene row return pairs or bars and no representative; `eirene-cycles`, `oat-cycles` and `dionysus-cocycles`
read one for every bar. TDA4j always returns a representative with every bar, while most of the others return bars only. `tda4j-cocycles`
against `ripser.cpp` (and `redzed`, at p = 2) is the like-for-like comparison. `tda4j` against `ripserer-involuted`, `oat-cycles` compares cycles
with cycles, and `tda4j-cocycles` against `dionysus-cocycles` cocycles with cocycles.

A variant that does more work than another tool (`dionysus-homology` and `dionysus-cocycles` after `dionysus`,
`oat-cycles` after `oat`) is not run on a case where that tool timed out or ran out of memory: it would only spend the
timeout again, and the table says so (`--rerun-timeouts` runs it anyway). Both build the complex explicitly, where
Ripser's is implicit, so the large Vietoris–Rips cases may end in `timeout` or `oom` for them (not measured at that scale
yet); `--tools` leaves them out of a quick run.

## Output (`$WORKDIR/results/<timestamp>/`)

- `runs.csv`: one row per process (cold, warm), with every trial's time, the failure reason, where the RSS came from
  and the JVM's heap peaks.
- `summary.csv`: one row per case, field and tool, with agreement, the significant bar counts and the notes.
- `summary.md`: the same as a table, then the failures with their reasons and the agreement notes, then TDA4j's
  slowdown against the reference and against the fastest other single-threaded tool, per case. The reference is the
  fixed baseline; "best other" changes from row to row, so a row whose reference did not finish is marked † and is left
  out of the means. The means are per task (never pooled across tasks), with median, range and how many cases the
  variant finished; the `tda4j-js` rows count as TDA4j variants, never as the baseline. Last, **Scala.js against the
  JVM**: for each `tda4j-js*` row, the warm and cold time ratios and the memory ratios to its JVM twin (`tda4j-js` to
  `tda4j`, `tda4j-js-cocycles` to `tda4j-cocycles`, ...), per case and as geometric means per task.
- `env.json`: the machine, the JVM, Node.js and the npm package's version, every Python package's version, the
  ripser.cpp commit, the options, and which TDA4j was measured: `tda4j_git` (the HEAD of the checkout the JVM classes
  were built in), `tda4j_dirty` (the tracked files that differ from it; untracked ones are not listed) and
  `tda4j_classes_stale` (a JVM source is newer than the newest class: the checkout moved after `setup`, so rerun it).
  `summary.md` and `sweep.md` repeat the commit in their first lines, with the changed files when there are any.
- `bars/*.tsv`: every barcode (`dim birth death`); `logs/`: each process's stdout and stderr.

`bench.py summarize RESULTS_DIR` rebuilds the tables, for example after merging the directories of several partial
runs.

## Before quoting numbers


- Run on a quiet machine. Record it, and fix its frequency governor if you can.
- The harness's own checks: `python3 bench/test_bench.py` (synthetic processes and results; nothing to install).
- A tool's failure on a case is a result; read "Failures and limits" before calling a gap in a table a gap in the data.
- The JavaScript rows depend on the Node.js version (V8): `env.json` records it; use the same one for a comparison.
- Pin the pip versions for the final run (`setup_python` in `run.sh`). `env.json` records what was used either way.
- MATLAB is not exercised here. The MATLAB snippets are checked separately; see `.claude/PLAN-paper.md` §2.1.
