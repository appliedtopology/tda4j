# Cross-platform benchmark

Times TDA4j against the persistent-homology libraries at the frontier on the same inputs, on one machine. It also
checks that every tool returns the same barcode. The paper's claim is "what the others do, on the JVM, at a
quantified slowdown", so the output is that slowdown, per case and as a geometric mean. The method is in
`.claude/PLAN-paper.md` §3.0.

## Running it

```sh
# on the compute server, from the repository root
export WORKDIR=/scratch/tda4j-bench        # tools, data and results go here (default ./bench-work)
export JAVAPLEX_JAR=/path/to/javaplex.jar  # optional: TDA4j's predecessor
# julia on PATH (or JULIA=/path/to/julia) adds Ripserer.jl

bench/run.sh setup        # venv + pip tools, ripser.cpp (Z/2 and coefficient builds), Ripserer.jl, JavaPlex, TDA4j
bench/run.sh prepare      # downloads the Ripser-paper data, generates the synthetic sets (seeded)
bench/run.sh run --fields 2,17 --timeout 3600 --jvm-heap 32g
```

Re-running is safe: `setup` and `prepare` skip what exists, and every `run` writes a new results directory.

To see the harness work before committing hours, run a small subset:

```sh
bench/run.sh run --cases sphere3_48,sphere3_96,random2d_512,unif2d_1000 --warmup 1 --trials 2
```

Useful options of `run`:

| option | meaning |
|---|---|
| `--tasks vr,cubical,alpha` | which families of cases |
| `--cases a,b` | named cases only (see `CASES` in `bench.py`) |
| `--tools tda4j,ripser.cpp` | named tools only (see `TOOLS` in `bench.py`) |
| `--fields 2,17` | coefficient primes; tools that only do Z/2 report `unsupported` at 17 |
| `--warmup N --trials N` | warm runs: untimed and timed computations per process |
| `--timeout S` | seconds per computation; a timeout is recorded, not dropped |
| `--warm-cap S` | if one cold computation takes longer than this (default 60 s), the warm run reuses it instead of repeating |
| `--jvm-heap 32g` | `-Xmx` for TDA4j and JavaPlex. fractal-r needs about 12g; clifford50000 more |

## What is measured

- **cold:** one process, one computation, from launch to exit: startup, JIT, reading the input. This is what a
  command-line user, or a first call from MATLAB, sees.
- **warm:** one process, `--warmup` untimed then `--trials` timed computations; the median of the timed ones. This is
  what a library user in a long-running JVM or MATLAB session sees. For ripser.cpp, which has no loop, warm is the
  median of `--trials` cold runs.
- **peak RSS** of each process, from `wait4`.
- **agreement:** each barcode is compared with the reference tool's: ripser.cpp for Vietoris–Rips, GUDHI for cubical
  and alpha. The comparison uses the bottleneck distance per degree, and essential bars matched by birth.
  - ripser.cpp computes in float32, so the tolerance is `1e-5 × max(1, threshold)`.
  - For JavaPlex the tolerance is `threshold / divisions`, since JavaPlex snaps filtration values to a grid.

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
  - GUDHI with `top_dimensional_cells`, and CubicalRipser with `filtration="T"`.
- **Alpha:** uniform points in 2-D and 3-D, the Stanford dragon (2000 points), and sphere3 in R⁴. GUDHI reports squared
  radii, so we take square roots.

## The tools

| tool | how | notes |
|---|---|---|
| `tda4j` | `PaperBenchmarkDriver` (test scope) through the `Persistence` verb | the defaults a user gets: cycle representatives, `Engine.Auto` |
| `tda4j-cocycles` | the same with `Representatives.Cocycles` | the native kind of the Ripser engine; closest to bars-only |
| `tda4j-cohomology`, `tda4j-fastalpha` | named engines | the general engine on images; the dual union-find on alpha |
| `ripser.cpp` | vanilla build for p = 2, `ripser-coeff` for other primes | the reference for Vietoris–Rips |
| `ripser.py`, `giotto-ph-1t`, `giotto-ph-mt` | pip | giotto-ph multithreaded is reported, never the baseline |
| `gudhi`, `gudhi-collapse` | pip | edge collapse first in the second variant |
| `cripser` | pip | CubicalRipser, Z/2 only |
| `ripserer`, `ripserer-involuted` | Julia | the second asks for cycles (involuted), like TDA4j's default |
| `javaplex` | `workers/JavaPlexWorker.java` | written against JavaPlex 4.x's API but **not yet compiled against a real jar** |

TDA4j always returns a representative with every bar, while most of the others return bars only. `tda4j-cocycles`
against `ripser.cpp` is the like-for-like comparison. `tda4j` against `ripserer-involuted` compares cycles with cycles.

## Output (`$WORKDIR/results/<timestamp>/`)

- `runs.csv`: one row per process (cold, warm), with every trial's time.
- `summary.csv`: one row per case, field and tool, with agreement.
- `summary.md`: the same as a table, then TDA4j's slowdown against the fastest other single-threaded tool and
  against the reference, per case and as geometric means.
- `env.json`: the machine, the JVM, every Python package's version, the ripser.cpp and TDA4j commits, the options.
- `bars/*.tsv`: every barcode (`dim birth death`); `logs/`: each process's stdout and stderr.

`bench.py summarize RESULTS_DIR` rebuilds the tables, for example after merging the directories of several partial
runs.

## Before quoting numbers


- Run on a quiet machine. Record it, and fix its frequency governor if you can.
- Pin the pip versions for the final run (`setup_python` in `run.sh`). `env.json` records what was used either way.
- MATLAB is not exercised here. The MATLAB snippets are checked separately; see `.claude/PLAN-paper.md` §2.1.
