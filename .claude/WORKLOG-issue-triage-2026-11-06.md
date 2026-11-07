# Issue triage before 1.0.0 and the paper (2026-11-06)

Point-in-time snapshot (thread "Triage issues before 1.0.0 and paper"). 96 issues were open on `scala` at 1bb9b89 (CI green
on that head; open PRs #309 cell counts, #310 a flaky Wasserstein spec). 20 are closed now, each with a comment saying why;
76 remain. Earlier passes: `issue-triage-2026-10-24`, the 2026-10-29 performance sweep, `WORKLOG-final-run-orchestration.md`.
Each closure cites the code, the CI record or the earlier comment it rests on.

## What decides "repeat the timing run"

The final run is split (`bench/final_run.sh`): the external tools' rows are measured once and reused; only TDA4j's rows run on
the frozen commit. On the 10-27 breakdown TDA4j's JVM rows were 7% of the hours of the first VR pass (3.9 h of 54 h); "a TDA4j-only
run is 1 to 2 days" is an inference from that share, not a measurement. So a change that moves only TDA4j's numbers costs
days, while a change to the protocol, the case list or what an external row computes would cost the week. The list below is
sorted by that: first what changes comparability (harness), then what moves a TDA4j number by more than the machine's noise
(10-30%), then everything else.

## 1. Closed in this pass (20)

| issue | why |
|---|---|
| #76 | PR #213: `drawnComplex` rebuilds every point-cloud complex a facade result used |
| #103 | PR #213: `legendBox`, `Guide.legend`, `withLegendBox`, `withLegendColumns` |
| #104 | PR #213: `Axis.crossesAt`, `Scale.x/y(crossesAt = ...)` |
| #106 | PR #213: `--caption`, `--tag`, `--description`, `--look` in `TDA4jConf` |
| #108 | PR #213: `TextWidth` tables, `TextFace.fit` by measured width |
| #111 | `rules/engines.md` is 11,976 characters (budget about 12k) |
| #124 | obsolete: the A/B has no variant to run; Helix equals GUDHI in the alpha sweeps |
| #169 | `cech` cases and `tda4j-cech` / `gudhi` rows exist (cechmate not added) |
| #170 | `dtm` cases and `tda4j` / `gudhi` rows exist (no standalone k-d tree row) |
| #172 | `dowker` cases and `tda4j`, `gudhi`, `dowker-nerves`, `dowker-yoon` rows exist (no Dowker-Rips) |
| #191 | `EulerCharacteristicCurve` is built and specced (the facades are #248) |
| #220 | decided and done in #222 (`ParallelRipser` with representatives; `Auto` picks it with threads > 1) |
| #231 | duplicate of #267: only `alpha.md` and `plotting.md` are over budget |
| #239 | all three open checks seen: skip day green, second publish replaced the files, `tda4j_sjs1_3` fetchable |
| #276 | PR #286: coordinate snapshot, `point(index)`, copies out |
| #277 | PR #286: iterative cocycle expansion, cyclic log is an error |
| #278 | PR #286: `computeAllRepresentatives` scaladoc |
| #279 | PR #286: `insertionDiameter` is `private[tda4j]` |
| #280 | PR #286: `PersistenceMap.integer` by doubling, `Int.MinValue` fixed |
| #281 | PR #286 + 4015779: `BootstrapBand` builds no representatives |

#76, #103, #104, #106 and #108 were implemented by PR #213 (merged) and stayed open because the PR's "Closes" line took effect
for only some of its numbers. #231 is closed as a duplicate of #267, which the triage PR fixes (`alpha.md` 12,188 to 11,965
characters, `plotting.md` 12,241 to 11,989).

## 2. Before the final timing run

### 2a. To fix in this thread (one PR each; #242 and #243 first)

| issue | who | what |
|---|---|---|
| #242 | this thread | sweep_alpha gives TDA4j the enclosing radius, GUDHI none: the sweep's alpha times are not comparable |
| #243 | this thread | sweep directories cannot be merged: a TDA4j-only rerun reruns the external tools' rows of every sweep |
| #232 | this thread | no JVM facade row, so the JS rows are compared with the verb, not the facade they wrap |
| #287 | this thread | measure the depth of real `ParallelRipser` logs; a deep log would put memory into the `parallelverb` rows |
| #288 | this thread | measure the coordinate snapshot at scale (expected small next to the O(n^2) tables) |

### 2b. Decisions for Mikael (recommendation first)

| issue | who | what |
|---|---|---|
| #245 | Mikael | native vertex-valued images: the `_v` rows pay 4x (2-D) and 8x (3-D) the cells; recommend defer and report |
| #194 | Mikael | recommend stop: what is left is 5-19% per item, below the machine's noise; each would move only TDA4j rows |
| #218 | Mikael | bar object size; options 1-3 are a binary break for roughly a third of the bar's bytes; recommend keep |
| #227 | after the sweep | `Auto` picks FastCubical in 3-D where it gains 7-13%; the final cubical sweep carries both engines |
| #223 | after the run | decided: `Auto` stays; both engines are reported; close when the comparison is in |

- **#245 (native vertex-valued images): defer, and say so in the paper.** It is the one open item that moves a headline ratio
  by a large factor: the `_v` cubical cases run TDA4j on the (2n-1)-per-axis refined image, 4x (2-D) or 8x (3-D) the top cells
  of a native V input, against FlashCubical, DIPHA and CubicalRipser, which take V directly. A native lower-star input is
  engine work (FastCubical is built on the T grid's top cells and codimension-1 cells; the general engines consume a
  stream), not an option flag, and it is additive API, so it can follow in 1.1 without breaking anything. If the paper's
  cubical comparison with FlashCubical must be on equal footing, this is the issue to build before the freeze; then only the
  `_v` rows of TDA4j rerun.
- **#194 (packed Ripser engine): stop.** After #263 (1.2-1.7x), #265 (1.4-1.9x on the default cycles run) and #283 (H0 by
  union-find) the remaining items are each 5-19% of a run (primitive sort key 5-19%, `LongIntMap.update` in `report` about
  10%, the cocycle `rowOfDeath` map about 10% with cocycles only, apparent pairs from the block's edge 14% at 10-d and nothing
  at H2, with a tie-order change). Each is under the noise of the machine and would move TDA4j's rows only.
- **#218 (bar object size): keep the types.** From the issue's own breakdown options 1-3 remove an `Ordering` reference, a
  `Some` and shared endpoints with their boxed `Double`s, my estimate roughly a third of the ~330 B per bar, and each is a
  binary break of `PersistenceBar` right before 1.0 compatibility starts. Revisit only if the paper reports a case with 10^7 bars
  or more.
- **#227 (`Auto` in 3-D), #223 (`Auto` and one thread):** neither needs a code change before the freeze. The final cubical
  sweep and the one-thread rows report both engines, so the numbers arrive with the run; if FastCubical's 3-D lead stays at
  7-13% (the 10-25 sweep, before #217), switch `Auto` to FastCubical for 2-D only, which reruns only the 3-D `tda4j` rows.

### 2c. Needs a compute-server run (Mikael)

| issue | who | what |
|---|---|---|
| #116 | Mikael | JS/JVM ratios on the Ripser-paper cases and the cubical sweep |
| #127 | Mikael | failure reasons are implemented and tested; confirm on the next real `summary.csv` |
| #131 | Mikael | run `unif5d_500`, `unif10d_1000`, `o3_1024_alpha` |
| #135 | Mikael | alpha sweep with `--count --validity` |
| #138 | Mikael | finish `clifford50000` for Dionysus and OAT; the p-torsion question |
| #139 | Mikael | DIPHA, PHAT and Eirene rows at scale |
| #153 | Mikael | alpha rule d >= 8, cap <= d - 2 is in; n >= 100, caps 4..d-2 and clustered clouds are unmeasured |
| #273 | Mikael | RedZeD row unrun (real data, o3_4096, clifford50000) |

#153 is the only one of these that can change a TDA4j number: `Auto`'s alpha rows at d >= 8 follow the rule from the `four`
sweep (DQP iff d >= 8 and cap <= d - 2, `WORKLOG-dqp-degree-cap.md`), and n >= 100, caps between 4 and d - 2 and clustered
clouds are unmeasured. The n-grid command is in that worklog.

## 3. Before 1.0.0

To fix in this thread, one PR each (#267 is in the triage PR):

| issue | who | what |
|---|---|---|
| #248 | this thread | Euler characteristic curve on MATLAB, CLI, JavaScript and Python (new-capability checklist) |
| #237 | this thread | MATLAB `circularCoordinates`: choose the complex (Cech, alpha) like the Scala entry point |
| #291 | this thread | image relative to its boundary: fast path from `Persistence`, and the facades |
| #159 | this thread | migration guide: JavaPlex rows other than Vietoris-Rips are not checked against the JavaPlex source |
| #267 | this thread | `alpha.md` and `plotting.md` over the 12k budget |
| #99 | this thread | scan for `.and(` chains followed by another statement (half-vacuous specs) |

Not code (a decision, an editor, an outside tool or a release step):

| issue | who | what |
|---|---|---|
| #162 | docs editor | content pass over every page for human readers; the long developer's-guide pages remain |
| #167 | Mikael | CODE_OF_CONDUCT.md needs a reporting contact address |
| #304 | Mikael | harmonic chains over a large prime field: opt-in modular mode? |
| #306 | Mikael | harmonic barcode on VR is cut at apparent pairs; 16 of 84 extra bars unexamined: ship it as experimental? |
| #61 | jsmatuszewski | MATLAB, viewer in desktop browsers, cubical d >= 5, GL(2,3) against GAP, macOS hook, Setup script |
| #98 | jsmatuszewski | plot customization from MATLAB, Safari and Firefox, 3-D viewer |
| #101 | jsmatuszewski | Inkscape and rsvg-convert lines of the PNG/PDF page, never run |
| #107 | jsmatuszewski | screen reader, MATLAB defaults, R `pch`, other JDK locale data |
| #123 | at the release | demo examples use `JVPTree`; rename when the demo moves to the release |
| #59 | Scala release | needs Scala 3.9.1; Maven Central has 3.9.0 and 3.10.0, no 3.9.1 (checked 2026-11-06) |

Nothing else open looks like a 1.0 blocker. #272 stays open as the umbrella for known limits and becomes the limitations
section of the release notes. Two checks that came out clean:

- The main jar's TASTy header is 28.9 with experimental = 0 (read from `PersistenceBar.tasty`), so the library-wide
  `-language:experimental.modularity` and `-preview` do not make the TASTy unstable; downstream needs only the one import.
- Dropping `-preview` from `commonScalacOptions` compiles `tda4j/compile` on 3.9.0 with the same 5 warnings as with the flag
  (a clean build both ways). Not tried: the tests, Scala.js, and the docs build, which uses 3.8.4 where `into` may still need the
  flag. So the `build.sbt` comment ("remove once we're on Scala 3.9.x") is half true: the flag can go together with the 3.8.4
  docs pin of #59, not before.

## 4. After 1.0.0 (44 issues)

| issue | what |
|---|---|
| #53 | group homology: collapse for chains that do not refine |
| #54 | Brown's collapse: S4 at degree 8 runs out of a 2 GB heap |
| #55 | cup products on the collapsed classifying space |
| #62 | simplicial sets: pullbacks, integer coefficients, CP3 and CP4 |
| #63 | MATLAB and CLI input for simplicial sets |
| #64 | sparse Rips O(n log n) neighbour search |
| #67 | port Henry Adams' tutorials |
| #68 | Helix: symbolic perturbation for cospherical points |
| #69 | PHAT, sparse triplets and Perseus toplex readers: need files from the reference tools |
| #82 | boxed coefficients: profiled, no gain at small p on the JVM |
| #83 | Scala Native: no throughput gain measured |
| #91 | Chunks engine from maxDimension 2 |
| #102 | plot interactivity (skipped for now) |
| #105 | 3-D scenes in a board |
| #113 | Scala.js: read PNG images on Node |
| #115 | smoke-test the npm package in a real browser |
| #117 | JavaScript Web Worker |
| #152 | Helix walk is half the alpha time above 7-D (28x CGAL at 40 points in R^10); reported as found |
| #171 | witness rows: GUDHI's relaxation is of squared distances, ours subtracts the nu-th landmark distance; `check` does not treat disagreement as an error |
| #175 | bench for distances, vectorizations, circular coordinates, simplicial sets: the paper decides |
| #178 | Wasserstein by Hera's approach (no benchmark row) |
| #183 | feed the packed engine from the layered generator |
| #185 | research: uses of the three-case theorem (tracking) |
| #188 | VR blocks in parallel by filtration window |
| #189 | edge domination while generating blocks |
| #198 | generic engines and `ParallelRipser` index by one Long; `Ripser` and the VR tie-break are wide (documented) |
| #206 | Cech and Dowker on arrays are done (#259); general witness on arrays and JS timings remain |
| #211 | two Bowyer-Watson mutants survive (test quality) |
| #219 | alpha: Bowyer-Watson insertion is sequential |
| #221 | Helix walk: sequential apply side in 5-6 dimensions |
| #224 | real multi-core parallelism on Scala.js |
| #225 | dense table or neighbour lists by edge density; the `parallelverb` rows only, no default row |
| #230 | Scala.js: Helix at d = 10, heap per bar |
| #264 | ParallelRipser plateau at 16 threads; the tree-merge prototype did not help |
| #271 | birth and death cells from the fast engines (documented limit) |
| #272 | known limits after #270; becomes the 1.0 limitations list (keep open) |
| #282 | refactor `ParallelPackedCohomology.persistence` into phase helpers |
| #293 | FastCubical: large region dying as the younger by value (plateau images; the benchmark's noise and blob images do not show it) |
| #294 | FastAlpha equal-births rule, unmeasured |
| #299 | circular coordinates: weighted harmonic smoothing |
| #305 | harmonic barcode memory of the orthogonal cycle basis |
| #307 | harmonic chains: cocycle variant, Laplacian-kernel chains, poset construction |

## Checked on the way

- #239: the three open checks are seen (skip day green with `publish` and `dry-run` skipped on 11-03; the jars and poms of
  `tda4j_3` and `tda4j_sjs1_3` carry the 11-06 03:22 GMT publish; both artifacts answer 200). Not done: an sbt project resolving
  `tda4j_sjs1_3`. The artifact-level `maven-metadata.xml` of `tda4j_3` lists only 0.5.0-SNAPSHOT and the version-level one is
  a 404, so resolvers fall back to the plain `-SNAPSHOT` names, as Coursier did on 10-27.
- #59: `scala3-library_3` 3.9.1 is still not on Maven Central (3.9.0 and 3.10.0 are).
- #123: `demo/examples` still call `JVPTree`; `src` does not. A release chore.
- Not checked: the witness definitions of #171 (no GUDHI comparison run here); #61, #98, #101, #107 need MATLAB, Safari,
  Firefox, Inkscape or a screen reader.

## Correction, 2026-11-07

- **#248 was already done**, not open work: commit 775f243 (2026-10-29) put the Euler characteristic curve in MATLAB
  (`eulerCurveFromPoints`, `eulerCurveFromDistanceMatrix`), JavaScript (`eulerCurve`), the command line (`--euler-curve`),
  Python (`euler_curve`) and the vectorization page's MATLAB tab with a `MatlabTabsSpec` case. The table row above that
  lists it as to-do is wrong; the issue is closed with that evidence. The one item with no home in the docs, that a
  diagram does not give the curve, is a sentence on the vectorization page now (#159's change).
- #232 and #159 were fixed as planned; #287 and #288 were measured (no code fix; #312 filed for the verb's quadratic
  pre-pass); #242 and #243 are the sweep-harness change. See `WORKLOG-sweep-harness.md`, `WORKLOG-bench-jvm-facade.md`,
  `WORKLOG-migration-guide-check.md` and `WORKLOG-memory-measurements.md` once those branches are merged.
