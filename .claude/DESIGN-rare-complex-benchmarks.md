# Benchmarking the rarer complexes: Cech, DTM, Dowker, witness, sparse Rips

Point-in-time design (2026-10-21). Research and proposal only: no benchmark was run and no harness code was changed.
Searches and installs this session: `pip install gudhi` (3.13.0) to check which classes exist; the rest of the survey is
from web search results and the JavaPlex source tree, and is marked **unverified** where I have not run the tool.

## What the existing harness gives us

`bench/` already has the machinery: cases, per-process timing with timeout and RSS, warm/cold runs, a `cells` column,
barcode agreement by bottleneck distance, the Scala.js twin, and a worker per external tool (`workers/py_worker.py`,
JavaPlex, Julia, DIPHA/PHAT). Tasks today: `vr`, `cubical`, `alpha` (and `cech`, used only by `sweep_alpha.py`). So the work
is new *tasks and workers*, not a new harness. Reference tools per task live in `REFERENCE` in `bench.py`.

## Survey: who else implements these

| complex | other implementations | how comparable |
|---|---|---|
| **Cech** | GUDHI `DelaunayCechComplex` (Python, in 3.13.0, checked); GUDHI C++ `Cech_complex` (no Python class in 3.13.0); `cechmate` (scikit-tda, pure Python); giotto-tda `EuclideanCechPersistence` (unverified; believed to wrap GUDHI's alpha complex) | Delaunay-Cech and Cech have the same persistent homology but different values per simplex and different cell counts. Compare barcodes, not cells. Alpha (GUDHI, already benchmarked) is a third route to the same barcode. |
| **DTM** | GUDHI `WeightedRipsComplex` (importable in 3.13.0), `DTMRipsComplex` (needs scipy), `gudhi.point_cloud.dtm.DistanceToMeasure`; weighted alpha in GUDHI; giotto-tda weighted Rips (unverified); R `TDA::dtm` computes the function only | Our DTM-Rips p = 1 is already byte-identical to GUDHI's, so agreement is expected. |
| **Dowker** | `dowker_homology` (Python/PyPI, sparsified Dowker nerves, uses GUDHI); Dowker-Rips code of arXiv 2508.08025; `dowker_persistence` (Julia, GPL-3.0, degrees 0-1, stale); PETLS (Laplacians). None in GUDHI, Dionysus, Ripser, JavaPlex, giotto-tda | Mostly approximations or surrogates. A hand-built GUDHI `SimplexTree` baseline is the honest "what you would write today" row. |
| **Witness** | JavaPlex `WitnessStream` (general) and `LazyWitnessStream` (our port was read against these); GUDHI `EuclideanWitnessComplex`, `EuclideanStrongWitnessComplex`, `WitnessComplex`, `StrongWitnessComplex` (all present in 3.13.0) | JavaPlex is the safe pair. Whether GUDHI's relaxed weak witness is our general variant is **unverified**: check cell for cell first (#171). |
| **Sparse Rips** | GUDHI `RipsComplex(sparse=eps)` (Buchet-Chazal-Oudot-Sheehy; giotto-tda `SparseRipsPersistence` wraps it); SimBa (Dey-Shi-Wang) and Sparips (Brehm-Hardering) exist as papers, code availability **unverified**. Ripser/Ripserer sparse *matrices* are a threshold, not a Sheehy construction | Our `SparseRips` is Cavanna-Jahanseir-Sheehy 2015; the epsilons of the two constructions are not comparable. |

JavaPlex has no Cech, Dowker, DTM or sparse Rips (its stream implementations are explicit, flag, geometric, lazy witness,
witness, VR, bicomplex). Dionysus, Ripser, DIPHA, PHAT, Eirene and OAT have none of the five either, so for those rows
the only comparators are GUDHI and, for Dowker, the small research packages.

## Design

**Two numbers per case.** (1) Complex construction: iterate the stream, report time and cell count. (2) End to end:
`Persistence(...)` including reduction. External tools differ in what they fold into each step, so (2) is the headline and
(1) explains it. The harness's `cells` column and `count=true` driver mode (already there for `vr`, `alpha`, `cech`) cover
(1); extend the driver's `task=` list.

**Agreement is per complex, not one rule.**
- Cech vs Delaunay-Cech: same barcode up to tolerance, bottleneck distance per degree (in general position).
- DTM-Rips: exact against GUDHI at p = 1.
- Witness: exact against JavaPlex; against GUDHI only after the definition check.
- Dowker: exact only against an exact implementation; otherwise report bottleneck distance and say which side is approximate.
  Our own free check: `.dual` gives the same barcode without zero-length bars.
- Sparse Rips: not an agreement but an *approximation quality* metric, bottleneck distance to the exact Rips barcode on log
  scale, plotted against complex size, with GUDHI's sparse Rips on the same plot. Matched epsilon is meaningless.

**Same inputs.** Landmarks and relations are generated once and passed to every tool as index lists / matrices (never
let each tool pick landmarks). Landmark selection (`maxmin`) is timed separately.

**Data.** Reuse `sphere3_*`, `o3_1024`, `unif*d_*`, `cyclooctane` where the complex makes sense; add: noisy circle with
outliers (DTM: the outlier bars should vanish; this is the point of the method, not only speed), (L, W) point-cloud pairs and a
Boolean relation (Dowker), a landmark ladder L = 50..1000 on an n = 10,000 cloud (witness, where the sparse complexes
should win over full VR).

**Axes.** Cech: n, ambient d (we cost about 120 us/simplex flat in d, #154; GUDHI should win by a large factor, which is the
result worth knowing), maxDimension. DTM: n, k, d; weights alone vs complex. Witness: n, L, maxDimension. Dowker:
|L| and |W| separately, direct vs dual. Sparse Rips: n, epsilon, maxDimension; build time split from reduction time (ours is
deliberately O(n^2) to build).

## Implemented but unbenchmarked (beyond the five)

Each is in the table of #175 except where noted.
- **Edge collapse**: `gudhi-collapse` runs in `bench.py`, but there is no TDA4j row; only the internal
  `EdgeCollapseBenchmarkSpec` (no external comparison). #174.
- **`SparseRipsBenchmarkSpec` is misnamed**: it times `maxFiltrationValue` (a threshold) against untruncated Ripser, not the
  `SparseRips` stream. Filed with #173.
- `DistanceToMeasure` k-NN alone (`BruteForce` vs `VantagePointQuery` crossover). #170.
- `BarcodeDistance` bottleneck/Wasserstein (O(n^3), #166) vs Hera/POT/persim; `Vectorization` vs `gudhi.representations`,
  persim; `CircularCoordinates` vs DREiMac; `sset` vs Sage. #175.
- Representatives for the rare complexes: the `dionysus-cocycles`/`oat-cycles` rows exist for VR only.
- Open bench issues that already cover other gaps: #126-#139 (RSS floor, baselines, DIPHA/PHAT/Eirene/Dionysus/OAT at scale).

## Order of work and cost

1. Witness vs JavaPlex and DTM vs GUDHI (both already known to agree on small inputs; mostly a worker plus cases). #170, #171.
2. Cech vs `DelaunayCechComplex`: one `py_worker` adapter, one case family; the result drives #154. #169.
3. Sparse Rips approximation-quality plot, and the spec rename. #173.
4. Dowker: needs installing three small packages and a decision on what counts as agreement. #172.
5. Edge collapse row, then the #175 inventory as separate issues. #174, #175.

Each of 1-3 is a small change to `bench.py`, `py_worker.py` and `PaperBenchmarkDriver.scala`; the compute cost is the
benchmark runs themselves, which belong on the compute server as for the alpha sweep.

## Risks

- A benchmark of a *different* complex under the same name (GUDHI's sparse Rips, possibly its witness) reads as a speed
  comparison and is not one. Every row must state the construction it runs.
- The Cech and Dowker rows may show large slowdowns against GUDHI's C++ and against nothing for Dowker; that is a result, not
  a reason to drop the row (the alpha benchmark found the quadratic `topValue` this way).
- `pip`-installed research packages (Dowker) may not run on current Python/Julia; budget a failed install as a finding.
