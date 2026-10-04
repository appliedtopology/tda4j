# Plan: the work to do before writing the TDA4j paper (2026-10-04)

Planning document for the project lead. Nothing here is implemented.
- Facts marked **(verified)** were checked against a primary source this session; §8 says how.
- Every other number cites the worklog that measured it and inherits that worklog's caveats.

## Bottom line

- **What we can write today is a system paper.** Its three strengths:
  - generality: any cell type, any field, and cycles or cocycles from every engine;
  - breadth: most current constructions in one typed library, with a MATLAB facade and a CLI;
  - an unusually explicit correctness methodology.
- **It is not a speed paper.** The VR engine runs 3–90x behind `ripser.cpp` in the numbers we have, and those numbers are not
  citable yet: mixed machines, single trials, code from before later fixes.
- **Research-level novelty is thin, and two candidates are not new:**
  - dense integer cell ids are how GUDHI's persistent cohomology already works (**verified**);
  - persistent homology along a chain of subgroups is already a HAP function, `PersistentHomologyOfSubGroupSeries`
    (**verified**), and Ellis–King (2011) introduced persistent homology of groups.

  Both stay in the paper as implementation and showcase material, with citations.
- **The strongest candidate is the dual union-find engine, generalized beyond Flash Cubical.**
  - What it adds: T-construction cubes, Delaunay/alpha in any dimension (with a chunks hybrid for the middle degrees), any
    field, and signed representatives.
  - Its representatives should be exactly the volume-optimal codimension-1 cycles at birth (Lenzen–Renkin, Thm 8). Those
    cycles are known objects (Obayashi; HomCloud), but here they come at union-find cost.
  - Three things must happen before we claim it: a proof check, a test, and a fix to the current quadratic representative
    bookkeeping (§2.2).
- **Before benchmarking anything,** three things must be in place:
  - the MATLAB facade run in real MATLAB (never done; our bytecode needs MATLAB R2024a or later, §2.1);
  - the fast engines' representatives made lazy;
  - one clean same-machine VR baseline.
- **The benchmark harness should report agreement as well as time.** Each run's barcode is checked against the other
  tools. That makes a cross-library correctness study, and so far nobody, us included, has run one at scale.

## 0. Decisions for the lead (answer these first)

1. **Venue.** Recommendation: **JACT** (Journal of Applied and Computational Topology), as a full system-and-methods paper.
   - Ripser's paper appeared there, and the readers are the right ones.
   - If the correctness methodology ends up as the spine, ACM TOMS (with its Replicated Computational Results review) fits
     better.
   - A JOSS paper is a cheap, citable add-on. It does not replace the full paper.
2. **VR speed target (§2.3).** Either accept the gap and build the paper on generality, or spend engineering time on the
   candidate levers:
   - a Ripser-style working column (a primitive heap with lazy cancellation) in place of the `TreeMap` accumulator;
   - unboxed `F_p` arithmetic;
   - emergent pairs.

   No valid profile shows a dominant cost yet, and every payoff is unmeasured. So take this decision after the clean
   baseline and a fresh profile.
3. **Group homology.** Keep it as a showcase cross-checked against HAP (recommended), or drop it from the paper.
4. **Alpha default at ambient dimension ≥ 4.** Options:
   - Helix, which gives an invalid triangulation for about 1 cloud in 170 at d = 4 with 20–30 points;
   - Helix with repair turned on;
   - DQP.

   Whatever we choose, measure DQP's speed there first.
5. **Showcases (§5).** Pick two or three.
6. **Which triangle of a full distance matrix to read (§2.8).** Today we read the upper triangle; Ripser and GUDHI read the
   lower one. Recommendation: switch to the lower triangle, and canonicalize every input in the harness anyway.
7. **Authorship and an AI-use statement.** The commit history shows Claude's share of the code. Check the venue's policy and
   decide what the paper says. (Springer, which publishes JACT, does not accept LLMs as authors and asks for use to be
   documented; re-check the current wording.)

## 1. The contributions ledger

### 1a. Candidates that can carry a section, each with the check that decides it

**A. Dual union-find, generalized.** (`FastCubicalHomologyEngine`, `FastAlphaHomologyEngine`)
- **What it does:**
  - `H_{d-1}` comes from union-find on the dual graph of top cells, read in the superlevel direction with `∞` as the point
    at infinity;
  - `H_0` comes from primal union-find;
  - for d ≥ 3, the middle degrees go to chunks on the complex with its top cells hidden;
  - it works for cubical images (T-construction) and for Helix Delaunay alpha complexes in any ambient dimension, over any
    field;
  - each bar gets a signed representative: the boundary of the coherently oriented sum of the top cells in the component
    that dies.
- **Prior art:**
  - **Flash Cubical** (Le Breton–Szustakowski–Piraud, arXiv:2606.04801): V-construction, F₂, 2D/3D, union-find plus pruning
    and lookup tables. Its abstract says the ideas "generalise naturally to T-filtrations ... and suggest promising directions
    for other complexes" (**verified**). We have done exactly that generalization. The abstract does not mention
    representatives, and our design note says it produces none.
  - **Classical:** union-find for `β_2` of complexes in `S^3` (Delfinado–Edelsbrunner 1995), and duality for image
    persistence (Garin et al. 2020).
  - **Representatives:** volume-optimal codimension-1 cycles from complement components (Obayashi 2018, implemented in
    HomCloud). Lenzen–Renkin (arXiv:2512.09668, Dec 2025) prove in Thm 8 that the boundaries of the complement's components
    form a minimal cycle basis for any positive weights (**verified**, PDF pp. 1–5). Their algorithm tracks representatives
    through the whole filtration in `O((#K)^2)`, over a field, for complexes that triangulate `S^{d+1}`.
- **The checks that decide it:**
  1. Make representatives lazy (§2.2); today they cost quadratic time too.
  2. Prove that our representative is `∂Φ(c)` for the complement component `c` at birth. Then test it internally,
     including tie-heavy inputs and the `+∞`-valued missing-pixel case. If it holds, the claim becomes "volume-optimal
     codimension-1 representatives at birth, at union-find cost plus output size, over any field".
  3. Compare with HomCloud's volume-optimal cycles cell for cell, but **only on inputs with distinct filtration values**.
     - With ties, which bar gets which complement component depends on the tie-break, so representatives can legitimately
       differ; Thm 8 is about a minimal basis at one level, not about which bar gets which cycle.
     - First check which cubical construction HomCloud's bitmap filtration uses, T or V.
  4. Benchmark against CubicalRipser and GUDHI in T mode (§3.3).
  5. Measure, by ambient dimension and point count, how often the alpha precondition (every facet has at most two
     cofaces) fails.

**B. Representatives as a design invariant.**
- **What it is:**
  - every engine returns a representative for every bar, including the apparent-pairs and union-find fast paths;
  - cycles or cocycles come on request;
  - `Involution` derives the other kind in both directions: cycles from a cohomology pairing, and cocycles from a homology
    pairing;
  - every pivot is checked against the pairing, and a mismatch throws.
- **Measured overhead:**
  - a derived kind costs 1.1–1.3x the native kind in degrees 0–1, and about 2.5x for VR cycles in degree 2
    (`WORKLOG-involution.md`);
  - for comparison, chunks needs 113 s or more, or runs out of memory, on the inputs where Ripser plus the involution takes
    0.7–4 s.
- **Prior art:**
  - Čufar–Virk 2021 (arXiv:2105.03629): cycles from cohomology, available in Ripserer.jl as `alg = :involuted`;
  - de Silva–Morozov–Vejdemo-Johansson 2011: dualities between persistent homology and cohomology;
  - Ripserer.jl and OAT both already advertise generic fields with representatives.
- **The checks that decide it:**
  1. Read Čufar–Virk for whether the reverse direction (cocycles from a homology pairing) is stated there. It is probably
     folklore given the 2011 dualities.
  2. Measure the overhead of representatives against Ripserer.jl and OAT on the same data (§3.2).

  The honest claim is "uniform and checked", not "new".

**C. The correctness methodology, plus a cross-library agreement study.**
- **What we have:**
  - **discriminating oracles:**
    - F₃ against F₂ as a sign check;
    - cup products, which caught a wrong torus fixture;
    - stream-against-stream comparisons cell for cell;
    - pairing equality across engines;
    - the involution's pivot checks;
  - **docs as tests:** every tutorial number is asserted by a spec;
  - **a catalogue of PH-software pitfalls, each with an incident in this codebase:**
    - truncation artifacts: a direct caller once got 1,072,740 bars where Ripser reports 18,145
      (`WORKLOG-ripser-comparison.md`);
    - F₂ hiding signs and odd torsion;
    - tie orders that disagree between stream and engine;
    - Miniball radii that are non-monotone by one ULP;
    - asymmetric distance matrices, which once caused an infinite loop;
    - zero-length bars and Dowker duality;
    - essential-bar conventions in distances;
  - **defects found in references** (§1e).
- **Prior art:** tool comparisons so far report timings: the roadmap (Otter et al. 2017), the Ripser paper, giotto-ph, and
  an R benchmark (R Journal 2021). Whether any of them checked outputs against each other at scale is to be confirmed when
  writing related work.
- **The check that decides it:** run the agreement study (§3.0). Report every disagreement and its root cause, ours or
  theirs.

### 1b. Design and engineering: section material, not novelty claims

- **Generic at the surface, dense inside.** `CellularCohomologyEngine` numbers each dimension's cells densely and reduces
  over `Int`s (`WORKLOG-dense-cohomology.md`).
  - **Not original.** GUDHI's `FilteredComplex` concept stores an integer `Simplex_key` per simplex for its
    persistent-cohomology code (**verified**). PHAT and DIPHA reduce over column indices, and Ripser over combinatorial
    indices.
  - **What belongs in the paper is the measurement:** the JVM tax on boxed, hashed generic cells is 1.9–3.0x end to end.
    About 10x on the reduction alone is inferred, not measured. Contrast this with Ripserer.jl, where Julia specializes
    generic code and the tax never arises.
- **Type-level discipline.**
  - opaque types (`Simplex`, `Cube`) and `is`-typeclasses;
  - no instances in a typeclass companion: a default `Double is Field` there would silently turn a forgotten `F_p` import
    into real coefficients and wrong torsion;
  - the price: every user file needs `import scala.language.experimental.modularity`.
- **Defaults that encode lessons:**
  - F₁₇, because F₂ hides signs and odd torsion;
  - zero-length bars dropped;
  - degrees 0–2 computed with cohomology;
  - the enclosing-radius threshold;
  - `homologyDegreeLimit`, a truncated stream that says so.
- **The prefix-exact cursor.**
  - **What it is:** `diagramAt(f)` is exact wherever the cursor stands; `advanceFor(budget)` runs in time slices; progress
    counters report how far it has got.
  - **Prior art:** the prefix property is inherent to the standard algorithm. TTK's progressive persistence (Vidal–Tierny)
    is interruptible but approximate and multiresolution; streaming persistence (Kerber–Schreiber) is also related.
  - **Trade-off to state:** only the homology engines are prefix-ordered, and they are the slow ones (chunks: about 97 s at
    degree 2 on 60 points, `WORKLOG-homology-profile.md`).
  - The claim is weak until those engines get dense numbering (§2.4).
- **MATLAB facade and CLI parity**, as the successor to JavaPlex. This depends on §2.1.

### 1c. Breadth: reimplementations, each cited to its original

- **Ripser:** the packed `(Double, Long)` engine with clearing and apparent pairs; emergent pairs are not implemented.
- **Reduction algorithms:**
  - the chunk algorithm (Bauer–Kerber–Reininghaus), with union-find in degrees 0–1;
  - generic cohomology with clearing;
  - New-VR (Rieser), as a cross-validation baseline.
- **Complexes:**
  - Čech (Miniball);
  - alpha by dual QP (Carlsson–Carlsson 2024) and by Helix Delaunay (IEEE doc. 10917453);
  - witness complexes (de Silva–Carlsson, with JavaPlex's semantics);
  - Dowker (Chowdhury–Mémoli);
  - DTM filtrations (Anai et al.);
  - sparse Rips (Cavanna–Jahanseir–Sheehy);
  - edge collapse (Boissonnat–Pritam, Glisse–Pritam).
- **Coordinates:** circular (de Silva–Morozov–Vejdemo-Johansson 2011) and toroidal (Scoccola et al. 2022, with our own LLL).
- **Diagram tools:** bottleneck and Wasserstein distances (Hungarian and Hopcroft–Karp, with Hera's conventions),
  landscapes, persistence images.
- **Simplicial sets:** at parity with Sage's catalogue for what we ship, plus cup products, Steenrod squares and `π_1`
  presentations.
- **Group homology:** the bar construction, see §1d.
- **File formats:** Ripser, DIPHA, GUDHI, Perseus, CSV.

### 1d. Not innovations: say so, or leave them out

- **Dense cell ids.** See 1b.
- **Persistent group homology along subgroup chains.** HAP's
  `PersistentHomologyOfSubGroupSeries(S, n[, p, Resolution_Algorithm])` returns "the bar code of the persistent mod p
  homology in degree n of the sequence of inclusion homomorphisms S_k → S_{k-1} → ... → S_1 = G" (**verified**, HAP manual
  §11.1-9).
  - Ellis–King 2011 use quotient towers along five central series (**verified**, PDF).
  - HAP computes from resolutions, which scale far better than our bar construction: S₄ to `H_3` did not finish in 9 minutes
    (`DESIGN-persistent-group-cohomology.md`).
  - What remains ours: the same barcode as an honest filtered simplicial set, with representatives, inside a general
    engine. That makes a nice tutorial, not a contribution.
- **Persistence of filtered simplicial sets, as such.** Any boundary-matrix tool computes it once handed the normalized chain
  complex.
  - What is distinctive is the constructive layer, integrated with filtrations: products, quotients and `identify`, `BG`,
    cup products, Steenrod squares, π₁ presentations.
  - Its natural comparison is Sage and Kenzo, not PH libraries.
- **The packed Ripser engine.** It is a port.

### 1e. Findings about others' work (`BUGS-IN-REFERENCES.md`)

- **Cavanna–Jahanseir–Sheehy 2015.** Algorithm 3 omits the vanishing clamp that the paper's own Section 5.3 requires. Shown
  with a hand-verified counterexample.
- **DREiMac's `_gram_schmidt`.** The isolated subroutine is wrong. No end-to-end failure has been found, so do not overclaim.
- **The PH-roadmap `fractal_9_5_2` distance matrix is not symmetric** (**verified** on the full file):
  - 2,135 of the 130,816 pairs differ (1.6%), by up to 1e-5 absolute and 1e-4 relative;
  - all of them still differ after rounding to float32;
  - consequences: tools that read different triangles see different inputs, and our packed engine looped forever on it
    before the fix;
  - whether any other tool is affected is not checked.

## 2. Blockers, ordered by how hard a reviewer would hit them

**2.1 The MATLAB facade has never run in MATLAB.** (S to M)
- Our classes are Java 17 bytecode (class-file major version 61, **verified**).
- MathWorks lists OpenJDK 17 as supported only from R2024a; R2023b and earlier take Java 8 and 11 at most.
  - This was read through a summarizer of MathWorks' "Versions of OpenJDK Compatible with MATLAB by Release" page. The raw
    page did not load here. Re-check before relying on it.
- So: test in R2024a or later, with `jenv` pointing at a JDK 17 or 21, end to end:
  - `matlab_example.m`;
  - one tutorial MATLAB tab;
  - representatives and the boundary-matrix export.
- Document the requirement. For older MATLAB the CLI is the fallback; check whether Scala 3.9 can emit older bytecode at all.

**2.2 The fast engines build representatives eagerly, at quadratic cost.** (M)
- **The cause:** on every union, `FastCubicalHomology` and `FastAlphaHomology` copy the surviving component's whole
  coefficient map, merge, and copy back (`mutable.Map.from(chainOf(oldRoot))` … `.toMap`). They also build the boundary of
  every dying component for every bar.
- **The evidence:** on a 200×200 image the "fast" engine takes 2.4–2.6 s. The general dense cohomology engine takes 1.5 s for
  cocycles and 2.4 s for cycles (`WORKLOG-dense-cohomology.md`, `WORKLOG-involution.md`).
- **The fix:** record the merge forest (parent, orientation flip, event) and materialize a representative only when one is
  read, by walking the dying component's subtree.
- **Gate:** representatives identical to today's eager ones on the existing specs.
- **Then:** a capacity sweep in 2D (up to 4096²) and 3D (up to 256³). A 3D grid of 2.1M cells has only been measured with
  chunks, which took 90 s and 10 GB (`WORKLOG-cubical-capacity-sweep.md`).

**2.3 The VR numbers are not citable.** (M, plus machine time)
- **The evidence so far:**
  - against `ripser.cpp`'s median of 5: 18.8x, 38.6x and 64.0x on the three sphere3 sizes (one machine);
  - warm in-process: 3.0x and 17.7x on sphere3_48 and sphere3_96;
  - against a 2026-09-17 snapshot of `ripser.cpp` timings: about 33–41x on o3_1024 (single trials) and about 87x on
    fractal-r (a different machine);
  - dragon and random16 were measured only before the fixes;
  - o3_4096 and the 50,000-point Clifford torus have never finished.
  - Peak RSS on sphere3_96 is 336 MB against 8.3 MB.
  - Sources: `WORKLOG-ripser-profiling.md`, `WORKLOG-o3-1024-fractal-r-session-2026-09-25.md`.
- **Plan:** run all seven cases of the ripser-benchmark harness on one dedicated machine (§3.1), then take decision 0.2.
- **What the profiles say** (`WORKLOG-ripser-profiling.md`):
  - in the last valid CPU profile (sphere3_96, packed engine), `Chain.reduceLoop`'s red-black-tree accumulator is about
    14% of the samples;
  - boxing (`DiameterIndex` and generic `Field` coefficients) is 36% of the remaining allocation, not of CPU time;
  - Ripser's own working column is a heap with lazy cancellation;
  - emergent pairs are not implemented.
  - **Do not cite the fractal-r profile.** Its 75–80% in the accumulator and 21% `Integer.valueOf` were recorded while the
    packed engine was stuck in the asymmetric-matrix infinite loop, fixed on 2026-10-03.
- **Before any lever is pulled:** take a fresh profile on the clean baseline. The current profiles do not show one dominant
  cost.

**2.4 The cursor engines still work on cells.** (M)
- Naive, chunks and `Involution.cocycleBars` do not use dense numbering yet.
- Apply it with the same gate as `CohomologyNumberingSpec`: the cell-keyed version kept as a test oracle, compared bar for
  bar and term for term.

**2.5 Alpha robustness.** (M)
- Decide the default at d ≥ 4 (decision 0.4).
- Explain why the same flat-torus file gives 512 bars through `Persistence(points, complex = AlphaShapes)` and 1029 bars
  from `AlphaShapes(pts)` (`WORKLOG-dense-numbering.md`). The likely cause is zero-length bars, but that is not checked.
- Add the Čech-against-alpha oracle (§3.4).

**2.6 Release 0.5.0, with a DOI.** (S to M)
- The release pipeline has never run end to end (`RELEASE.md`).
- The paper needs a cited version and a Zenodo archive holding the code, the benchmark harness and the raw results.

**2.7 Group homology against HAP.** (S)
- Cross-check with `PersistentHomologyOfSubGroupSeries`. S₄ is not a p-group, so use the four-argument form with `p = 2`
  and a resolution algorithm.
- Finish S₄ to `H_3` on the dense cohomology engine.
- Add one sentence to the tutorial: HAP computes the same barcode from resolutions. That is a docs fact, not history.

**2.8 We read the other triangle from Ripser.** (S)
- **The mismatch:**
  - `ExplicitMetricSpace.distance(x, y)` reads `dist(min)(max)`, the upper triangle;
  - `ripser.cpp`'s default `distance` format reads "only [the] lower triangular part" (row i, columns j < i, **verified**
    in its source);
  - GUDHI's CSV reader is also lower-triangular (from memory; check before relying on it).
- **The consequence:** on an asymmetric file such as fractal-r (§1e), we and Ripser compute from different inputs, so every
  agreement check there would show discrepancies of about 1e-5 that come from us.
- **Options:**
  - switch to `dist(max)(min)`: one line plus a spec, and a lead decision because ~20 files use `ExplicitMetricSpace`;
  - or leave the library alone and have the harness give every tool a symmetrized copy.

  Recommended: both (decision 0.6).

## 3. Benchmarks and comparisons, by part of the library

### 3.0 Methodology rules for every table

- **Machine.** One dedicated Linux machine: fixed frequency governor, no other workload, hardware and OS recorded. (Earlier
  runs were spoiled by a laptop at load average 20.)
- **Versions.** Pin every tool.
- **Container.** Extend the Ripser paper's own multi-stage Dockerfile (`Ripser/ripser-benchmark`, **verified**), which
  already has stages for:
  - Ripser and its ablations (no apparent pairs, no emergent pairs, two reduced-matrix variants);
  - GUDHI;
  - DIPHA;
  - Eirene;
  - Dionysus 2.

  Add stages for TDA4j, giotto-ph, Ripserer.jl, OAT, JavaPlex, CubicalRipser, HomCloud and Hera. Reviewers know that
  harness.
  - Its base image is `ubuntu:20.10`, which is end of life and probably will not build as it stands. Rebase it and re-pin,
    and say in the paper that the baseline comes from a rebuilt harness.
- **Invocations come from primary sources:** the Dockerfile's flags, the tools' own docs.
  - Ripser's `--ratio` only filters printed pairs (its help text, **verified**). Drop it for agreement runs, or filter our
    output the same way.
- **Threads.** The primary tables are single-threaded. Multi-threaded tools (giotto-ph, DIPHA with MPI) go in separately
  labelled rows.
- **Field.** Use F₂ wherever an F₂-only tool is in the table. Also report TDA4j at its default F₁₇, and Ripser with
  `USE_COEFFICIENTS` at the same prime.
- **JVM timing.** Report both:
  - a cold process, as a `java -jar` CLI user sees it (hyperfine-style);
  - warm in-process, after warm-up, the median of at least 5 runs.

  Run one engine per JVM process, since JIT state leaks between engines (`WORKLOG-packed-ripser-engine.md`).
- **Outcomes.** Timeouts and out-of-memory errors are recorded outcomes, never dropped.
  - Isolate each case in its own process. The in-process harness once let abandoned computations keep running and
    contaminate later cases.
- **Statistics.** Report the median and IQR, peak RSS, cell counts, and **µs per cell** as the scaling measure, with log–log
  plots.
- **Representatives on and off.** Report both wherever a tool can do both.
- **Agreement:**
  - every run's barcode is compared with a reference by bottleneck distance, per degree;
  - the tolerance follows from the inputs: `ripser.cpp` computes in float32 (`typedef float value_t`, **verified**), and
    fractal-r is asymmetric at 1e-5;
  - every tool is given the same symmetrized input (§2.8);
  - zero-length and essential bars use one stated convention;
  - alpha radius against squared radius is converted explicitly.

### 3.1 Vietoris–Rips (the `Persistence` default; `PackedRipserCohomologyEngine`)

- **Comparators:**
  - `ripser.cpp` and ripser.py;
  - giotto-ph, on 1 thread and on N threads;
  - GUDHI, with and without edge collapse;
  - Ripserer.jl;
  - OAT;
  - Dionysus 2;
  - JavaPlex, mandatory as the predecessor;
  - Eirene, optional, already in the harness.
- **Datasets:** the seven cases in the Ripser-paper Dockerfile, with its exact flags (**verified**):

  | case | flags |
  |---|---|
  | `sphere_3_192` | `--dim 2` |
  | `o3_1024` | `--dim 3 --threshold 1.8` |
  | `o3_4096` | `--dim 3 --threshold 1.4` |
  | `clifford_torus_50000` | `--dim 2 --threshold .15` |
  | `random_point_cloud_50_16` | `--dim 7` |
  | `fractal-r` (distance matrix) | `--dim 2` |
  | `dragon` (2000 points) | `--dim 1` |

  Add a size ladder (sphere3 at 48, 96 and 192 points, already scripted) and the PH-roadmap's other point clouds and
  distance matrices (take the file list from `n-otter/PH-roadmap`):
  - genomic Hamming distances;
  - the C. elegans network;
  - Klein-bottle samples;
  - random clouds.
- **Metrics:** time (cold and warm), RSS, cells, apparent-pair counts, the cost of representatives, agreement.
- **Already in place:**
  - `RipserPaperBenchmarkSpec` with `-DripserBin`;
  - `.claude/scripts/run-ripser-paper-benchmark.sh`;
  - `SingleEngineProfileDriver`.
- **Missing:** the other comparators, the cold JVM measurement, agreement checks beyond bar counts, and the Clifford torus
  case.

### 3.2 Representatives

- **Comparators:**
  - Ripserer.jl, both `alg = :involuted` and cocycles;
  - OAT, which also offers optimal cycles;
  - Dionysus 2;
  - JavaPlex's annotated intervals;
  - HomCloud, for codimension-1 volume-optimal cycles;
  - giotto-ph's flag generators, which are vertex pairs, not cycles.
- **Datasets:** the §3.1 point clouds, the §3.3 images, alpha complexes in 2D and 3D.
- **Metrics:**
  - overhead against bars only;
  - representative size, in cells and in diameter;
  - validity: closed, correct birth cell, correct death cell;
  - for codimension 1, equality with HomCloud's volume-optimal cycles, on inputs with distinct filtration values only
    (decides claim A, §1a).

### 3.3 Cubical images (the fast cubical engine; `CubicalGridStream` uses the T-construction)

- **Comparators:**
  - CubicalRipser, in its T mode (check the flag or module in the pinned version);
  - GUDHI `CubicalComplex` with `top_dimensional_cells`;
  - DIPHA (T);
  - HomCloud (with representatives);
  - Ripserer.jl;
  - Flash Cubical, which is V only. A fair comparison needs a small V-construction loader on our side: vertex values,
    each cube taking the max. That is optional work, and also a feature users ask other tools for.
- **Datasets:**
  - **synthetic:** uniform noise (the worst case) and Gaussian random fields with a range of correlation lengths, both in 2D
    (256² to 4096²) and 3D (64³ to 256³);
  - **real volumes:** take the exact lists from the FlashCubical repository and the CubicalRipser paper rather than from
    memory. Our design note records Flash Cubical's 128³ Bonsai at 0.53 s / 194 MB, CubicalRipser 2.94 s / 440 MB and
    GUDHI 7.28 s / 1384 MB, on their machine;
  - **throughput:** MNIST, many small images, where the JVM's warm-up is amortized;
  - **4D:** synthetic only. Few tools take 4D, and our d ≥ 4 path has one smoke test.
- **Metrics:** time, RSS, µs per cell, representatives on and off, agreement.
- **Already in place:** `CubicalBenchmarkSpec`, `CubicalProfileDriver`.
- **Missing:** everything after §2.2.

### 3.4 Alpha and Čech

- **Comparators:**
  - GUDHI `AlphaComplex` (CGAL, any dimension; filtration values are squared radii);
  - CGAL and Diode in 3D;
  - HomCloud (with representatives);
  - Ripserer.jl's alpha;
  - Carlsson–Carlsson's own dual-QP code, if it is public;
  - GUDHI's `CechComplex`, for Čech.
- **Datasets:**
  - uniform points in `[0,1]^d` for d = 2 to 8;
  - points on manifolds, where ambient dimension is much larger than intrinsic dimension, the regime DQP is built for:
    - `S^2 ⊂ R^3`;
    - the flat torus in `R^4`;
    - `o3_1024`, samples of 3×3 orthogonal matrices in `R^9` (ambient dimension 9 confirmed in
      `WORKLOG-o3-1024-fractal-r-session-2026-09-25.md`);
    - cyclooctane in `R^24`, §5 (check the file's size and provenance when fetching);
  - Stanford bunny and dragon in 3D;
  - degenerate inputs: grids and cospherical points.
- **Internal oracle:** in general position, Čech and alpha barcodes coincide by the persistent nerve lemma. This makes a
  scalable cross-check of Helix and DQP against an independent construction.
- **Metrics:** construction time, number of simplices, total time, failure rates by (d, n), agreement with GUDHI.

### 3.5 Witness, Dowker, DTM, sparse Rips, edge collapse

- **Witness:**
  - against JavaPlex, which should agree exactly on the same landmarks. JavaPlex quantizes filtration values
    (`numDivisions`), so use a fine quantization or integer data;
  - against GUDHI's witness complexes, which use different definitions, so qualitatively only;
  - data: natural image patches, cyclooctane.
- **Dowker:**
  - no mainstream implementation is known (to check);
  - validate by duality (the X-side and Y-side barcodes agree) and on exhaustive small cases;
  - data: bipartite relations such as the Southern Women data and Web of Life plant–pollinator networks.
- **DTM:**
  - against GUDHI's `DTMRipsComplex` at scale (so far only GUDHI's own worked examples match exactly);
  - against GUDHI's weighted alpha for DTM-alpha in 2D and 3D;
  - an outlier-robustness experiment: the bottleneck distance to the clean diagram as the outlier fraction grows.
- **Sparse Rips:**
  - against GUDHI's sparse Rips;
  - size against ε, and the bottleneck distance to exact VR against the theoretical bound;
  - data: `clifford_torus_50000`, dragon subsamples from 10k to 100k points.
- **Edge collapse:**
  - against GUDHI's collapser; the collapsed edge sets should match, since ours is a faithful port;
  - then timings at scale. We only have n = 30 and 50 so far: 73–76% of edges removed, 43–47x faster reduction
    (`WORKLOG-edge-collapse.md`).

### 3.6 Distances and vectorizations (`barcode/`)

- **Comparators:**
  - Hera, for exact and approximate bottleneck and for auction Wasserstein;
  - GUDHI, through its Hera bindings and through POT;
  - persim;
  - giotto-tda and GUDHI's representations, for vectorizations.
- **Datasets:** diagrams of 10² to 10⁵ points, from VR `H_1` of random clouds and from synthetic diagrams.
- **Expected result:** Hera wins on large diagrams. Our claims are exactness, agreement, and an explicit essential-bar
  policy.

### 3.7 Circular and toroidal coordinates

- **Comparators:**
  - DREiMac (`CircularCoords`, `ToroidalCoords`);
  - Dionysus 2's circular-coordinates example;
  - a baseline of ripser.py cocycles plus scipy's `lsqr`.
- **Datasets:**
  - noisy circles, knots, tori in `R^3` and `R^4`;
  - coupled oscillators, for the torus;
  - COIL-20 rotations;
  - sliding-window embeddings of periodic signals.
- **Metrics:**
  - circular correlation, up to rotation and reflection;
  - harmonic energy;
  - runtime;
  - whether non-liftable cocycles are detected (we throw `NoIntegerCocycleException`; what DREiMac does is unverified).

### 3.8 Simplicial sets and group homology (`sset`)

- **Comparators:**
  - Sage simplicial sets: homology, products, quotients, CP², the Hopf map's mapping cone;
  - Kenzo through Sage, optional;
  - HAP: group homology, `PersistentHomologyOfSubGroupSeries` and `...QuotientGroupSeries`.
- **Datasets:**
  - the catalogue spaces;
  - Z/n, D₈, Q₈, A₄, S₃, S₄ and S₅, each with Sylow towers and composition series.
- **What to report:**
  - agreement;
  - a capability matrix (persistence, representatives, cup products, Steenrod squares);
  - honest timings: we lose to HAP beyond small cases.

### 3.9 Interfaces: I/O, MATLAB, CLI and the verb

- Round trips with every external format.
- The MATLAB end-to-end run (§2.1).
- Cold-start time of the CLI.
- **A "first five lines" table:** the code each library needs for "VR barcode with representatives from a CSV". Compare
  TDA4j, JavaPlex, GUDHI, ripser.py, Ripserer.jl and OAT. It is qualitative, but it is the ease-of-use claim made concrete.

### 3.10 Inside TDA4j: choosing an engine, and the cost of generality

- **Engine guidance:** `EngineComparisonBenchmarkSpec`, re-run after §2.2–2.4, as a "which engine for which complex" table.
- **The cost of each kind of representative:** native against derived (`WORKLOG-involution.md`).
- **Dense against cell-keyed:** already A/B-tested for cohomology (1.9–3.0x).
- **Fast against general engines:** cubical and alpha.
- **The cursor:** the overhead of `advanceFor` slices against `advanceAll`, and a long run inspected mid-way with
  `diagramAt(f)`.

## 4. Datasets, consolidated

- **Ripser benchmark** (`Ripser/ripser-benchmark`):
  - `sphere_3_192_points.dat`;
  - `o3_1024.txt`;
  - `o3_4096.txt`;
  - `clifford_torus_50000.points.txt`.

  The Dockerfile pulls three more from the PH-roadmap: `random_point_cloud_50_16_.txt`, the `fractal_9_5_2` distance
  matrix, and `dragon_vrip.ply.txt_2000_.txt`.
- **PH-roadmap** (`n-otter/PH-roadmap`, Otter et al. 2017): point clouds, distance matrices and networks. Take the list from
  the repository.
- **Cubical:**
  - the FlashCubical repository and the CubicalRipser paper, for their exact volumes;
  - Open SciVis volumes;
  - Gaussian random fields (generated, seeded);
  - MNIST and MedMNIST;
  - micro-CT from the Digital Rocks Portal, for porous media.
- **Geometry:**
  - the Stanford 3D Scanning Repository;
  - cyclooctane conformations (6,040 points in `R^24`, Martin–Thompson–Coutsias–Watson 2010);
  - natural image patches (van Hateren, following Carlsson et al. 2008).
- **Relations:** Southern Women; Web of Life.
- **Ours:** `_docs/tutorials/data/*` (seeded, guarded by `TutorialDataSpec`).

Every download goes through a script that records checksums. Datasets do not go into git.

## 5. Showcase applications (pick two or three)

1. **Torsion and high ambient dimension: cyclooctane.**
   - The conformation space is known to be a sphere and a Klein bottle glued along two circles, so it exercises:
     - the F₁₇ default against F₂ and F₃;
     - DQP alpha in `R^24` (Delaunay is out of reach there), or witness complexes on landmarks;
     - representatives that locate the Klein-bottle part.
   - Compute first and quote after; do not state Betti numbers in advance.
   - Natural image patches are an alternative with a strong JavaPlex lineage, at a higher data-preparation cost.
2. **A 3D image with representatives at scale.**
   - The fast cubical engine on a porous-media or medical volume, with `H_2` voids and their (volume-optimal, if A holds)
     representatives;
   - the middle degree through the hybrid;
   - the cursor on the long run.
3. **Exact small models.**
   - Simplicial sets telling spaces apart, with cup products and Steenrod squares.
   - Or persistent group homology along a Sylow tower, checked against HAP.
   - This shows the constructive layer, which is the distinctive part.

## 6. Sequencing and rough sizes

S is about one session, M a few sessions, L weeks or the compute machine.

1. **Decisions** (§0). Before anything else.
2. **Literature checks** for A, B and C, finishing what was started here (§8).
   - Tool feature matrix: Ripserer.jl, OAT, GUDHI, giotto-ph, CubicalRipser, HomCloud, JavaPlex. S to M.
3. **Blockers 2.1, 2.2, 2.4, 2.5, 2.7 and 2.8.** Mostly independent; each is M, except 2.7 and 2.8, which are S. 2.2 gates
   every cubical and alpha benchmark.
4. **Harness:** the Docker stages, dataset scripts, a TDA4j driver with cold and warm modes, the agreement checker, and a
   results schema. M to L.
5. **VR baseline** on the dedicated machine, then decision 0.2, then possibly the accumulator and unboxing work. M, or L if
   we act on it.
6. **Full runs.** L, machine time.
7. **Release 0.5.0** with a DOI and an archived harness and results (§2.6). Then write.

## 7. Paper outline (draft)

1. Introduction: from JavaPlex to TDA4j; what generality with representatives buys; contributions (§1a–1b).
2. Design:
   - cells, streams and their ordering contract;
   - engines, and the persistence diagram as a value;
   - typed fields and defaults;
   - the verb, the cursor, `homologyDegreeLimit`.
3. Algorithms:
   - the engine family;
   - the involution in both directions;
   - dual union-find, generalized, with representatives;
   - dense numbering on the JVM.
4. Breadth: complexes, coordinates, distances, simplicial sets (one table with citations).
5. Correctness:
   - oracles;
   - the pitfall catalogue;
   - the agreement study;
   - defects found in references.
6. Performance: §3, honest about where we lose.
7. Applications: §5.
8. Limitations:
   - VR speed;
   - Helix at d ≥ 4;
   - memory;
   - the `experimental.modularity` import;
   - MATLAB R2024a or later.
9. Availability: version, DOI, licence, harness.

## 8. Facts verified this session, and how

- **HAP** `PersistentHomologyOfSubGroupSeries`: the manual's §11.1-9, read as raw HTML from the GitHub Pages mirror
  (`gap-packages.github.io/hap/doc/chap11_mj.html`) and quoted verbatim.
- **Ellis–King 2011** filter by quotients along central series: the arXiv PDF, pp. 1–4.
  - A web summarizer claimed the opposite (subgroup chains). Never trust summaries for prior-art claims.
- **Lenzen–Renkin**, Thm 8, setting and complexity: the arXiv PDF, pp. 1–5.
  - It cites Obayashi's volume-optimal codimension-1 cycles and their implementation in HomCloud.
- **Flash Cubical's abstract** (V-construction, F₂, 2D/3D, "generalise naturally to T-filtrations"): the arXiv abstract
  page, fetched raw. Its code is at `github.com/T-prog123/FlashCubical`, per the search result, not visited.
- **GUDHI's `FilteredComplex` concept:** the raw documentation page (`concept/Persistent_cohomology/FilteredComplex.h`) says
  of `Simplex_key`, "Data stored for each simplex. Must be an integer type". The page does not list which classes model
  the concept.
- **`ripser.cpp`:**
  - `typedef float value_t;`, with `USE_COEFFICIENTS` off by default;
  - `--ratio` only filters printed pairs;
  - the default input format `distance` reads only the lower triangle.

  Read from the source.
- **The ripser-benchmark Dockerfile's** cases, flags and tool stages: read from the file.
- **fractal-r asymmetry:** the full 512×512 file, scanned with Python.
- **Our bytecode:** the class-file major version is 61, read with `od` on `Persistence.class`.
- **MATLAB's supported OpenJDK versions by release:** the MathWorks requirements page, through a summarizer only; the raw
  page did not load. **Not verified**: re-check before relying on it.

## Sources

- HAP manual, chapter 11: https://gap-packages.github.io/hap/doc/chap11_mj.html
- Ellis, King, Persistent homology of groups, J. Group Theory 14 (2011): https://arxiv.org/abs/1006.2237
- Lenzen, Renkin, Persistent cycle representatives and generalized landscapes for codimension 1 persistent homology:
  https://arxiv.org/abs/2512.09668
- Le Breton, Szustakowski, Piraud, Flash Cubical: https://arxiv.org/abs/2606.04801
- Čufar, Virk, Involuted persistent homology: https://arxiv.org/abs/2105.03629; Ripserer.jl (JOSS):
  https://joss.theoj.org/papers/10.21105/joss.02614
- GUDHI `FilteredComplex` concept: https://gudhi.inria.fr/doc/latest/struct_filtered_complex.html
- Ripser benchmark harness: https://github.com/Ripser/ripser-benchmark; `ripser.cpp`: https://github.com/Ripser/ripser
- PH-roadmap datasets: https://github.com/n-otter/PH-roadmap
- MATLAB and OpenJDK versions: https://www.mathworks.com/support/requirements/openjdk.html
- OAT: https://github.com/OpenAppliedTopology
- Obayashi, Volume-optimal cycle: https://arxiv.org/abs/1712.05103
