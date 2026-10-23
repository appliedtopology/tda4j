# Test suite audit (2026-10-23)

Project lead's question: the suite keeps growing and takes fairly long; which tests can be purged or combined without
losing coverage? Base: `scala` at d562ece. Method and numbers below; the cuts follow in the sections after them.
Running log: newest entries at the bottom of each section.

## Baseline (where the time goes)

Sizes: 195 test files, 31k lines, 172 specs, 1660 examples on the JVM (8 skipped: the benchmark specs), 1171 examples on
Scala.js (128 specs; the other 45 are JVM-only: facade, CLI, plot, io, tutorial pages, benchmarks).

CI run 39987498508 on d562ece (4-core runners):

| job | step | time |
|---|---|---|
| `test` (JVM) | `clean ; tda4j/test` | 3 min 40 s: about 108 s sbt start, clean build, compile, then 112 s of specs in parallel |
| `test-js` | `clean ; tda4jJS/test` | 8 min 03 s: 43 s resolve, 70 s compile, 25 s link, 350 s of specs |
| the other jobs | | 1.5 to 2.5 min |

`test-js` is the critical path of the whole workflow again (it was 4 min 41 s after PR #146, 8 min now).

Sequential measurements on the 4-core cloud sandbox (one spec at a time, so a spec's time is its own):

- JVM: `sbt 'set LocalProject("tda4j") / Test / parallelExecution := false' 'tda4j/testOnly * -- threadsNb 1'`, then the
  per-example `time` attribute of the JUnit XML in `target/out/jvm/scala-3.9.0/tda4j/test-reports`. **200 s of spec
  work** (1660 examples). specs2's own "Finished in" of a parallel run is inflated by contention (289 s total).
- Scala.js: `sbt 'set LocalProject("tda4jJS") / Test / parallelExecution := false' tda4jJS/testFull`, every output line
  timestamped, a spec's time = the gap between consecutive "Total for specification" lines (specs2's own time is wrong
  on JS, see `WORKLOG-js-test-trimming.md`). **730 s of spec work** (12.2 min), 128 specs. The 45 JVM-only specs add
  20 s to the JVM figure; the 128 shared specs cost 180 s on the JVM and 729 s on Scala.js, a factor of 4.

Top specs, sequential seconds:

| spec | JVM | JS | note |
|---|---|---|---|
| `BowyerWatsonSpec` | 27.0 | 171.6 | 24% of all JS work; its "degenerate input" example is 25.9 s of the JVM figure |
| `AlphaComplexDQPWeightedSpec`: 400 cases (was 2000) plus the hand-derived and power-limit examples | 19.1 -> 3.2 s | 10.5 -> 1.9 s |
| `PackedCubicalCohomologySpec` | 11.1 | 29.5 | |
| `AlphaComplexSpec` | 9.7 | 6.3 | 2000 cases per backend (JS: 200) |
| `CofaceSimplexStreamSpec` | 8.1 | 47.5 | five 100-case properties |
| `WideIndicesSpec`: `beEqualTo` on a 5,000-element listing rendered both sides before comparing; `==`, and 2 seeds instead of 8 on JS | 7.9 -> 3.1 s | 23.8 -> 1.5 s |
| `CollapsedNerveSpec` | 6.8 | 46.1 | |
| `ClassifyingSpaceSpec` | 5.1 | 35.5 | |
| `FastRepresentativesSpec` | 5.0 | 20.7 | |
| `RipserCohomologySpec` | 4.4 | 24.8 | |
| `VietorisRipsSpec` | 2.9 | 32.8 | JS 11x the JVM: prints one line per case |
| `NaiveCappedSpec` | 1.5 | 28.4 | JS 19x the JVM |
| `BrownCollapseReferenceSpec` | 3.5 | 25.1 | |
| `SheehyRipsStreamSpec` | 3.2 | 22.0 | |
| `MatlabTabsSpec` (JVM only) | 8.3 | | |

The 12 slowest JS specs are 70% of the JS work.

## What the cost is made of

Per-example JVM times (JUnit XML) and per-spec JS gaps: **50 examples are 68% of the JVM spec time**; the cheap bulk
(1,500 examples) is under a third of it. Purging many small tests would save little. The time is in a few examples that
repeat one check over a grid: seeds x option combinations, a property run 2000 times, a whole-structure verification
after every step. The worst of these were not redundant specs but redundant iterations inside an example.

Scala.js runs the shared specs 4x slower on average, up to 20x (`NaiveCappedSpec`), and the suite's own rule is that the
JS run is there to catch platform differences (`rules/scalajs.md`): `Long` emulation, number formatting, `Math`, the
`BigDecimal` port. Any input shows those, so a JS run does not need the JVM's search breadth. That makes JS the place to
cut without a mutation argument; the JVM run keeps the full grid.

## Coverage: why it cannot decide what to remove

scoverage on Scala 3 (compiler built-in; the data directory holds one measurement file per JVM, so the specs were run
one JVM each). 172 specs, 33,846 statements, 90.4% covered by the specs together.

- 54 specs have no statement that no other spec covers; a cost-weighted greedy cover needs 134 of the 172.
- The 38 specs it leaves out are **19 s of the JVM's 200 s and 77 s of Scala.js's 730 s**, and they carry oracles
  (a different computation of the expected value, a hand-derived barcode) that statement coverage cannot see.
- A spec removed on this argument loses the check that a *wrong value* is caught, not the execution of a line.

So no spec is removed on coverage. The list is in `/mnt/project-files/test-suite-audit/per-spec.csv`
(`needed_for_statement_cover`): treat it as candidates for merging into a neighbour, not for deletion.

## Mutation spot-checks: the criterion for shrinking an example

Protocol: apply a source mutant to a main file, run the old spec (a scratch copy) and the reduced spec, record which
fail. A reduction is safe when it kills every mutant the old example killed.

| mutant | what it breaks | old | reduced |
|---|---|---|---|
| B1 hull facet never in conflict | `BowyerWatson` conflict test | killed | killed |
| B3 walk crosses zero-orientation facets | point location | killed | killed |
| B7 new cell not linked back to the outside cell | neighbour relation | killed | killed |
| D1 perturbation order by position, not index | `DelaunayPredicates` tie-break | killed | killed |
| D2 ties always inside | tie-break | killed | killed |
| B2 locate accepts a zero-orientation hull facet | point location | **alive** | **alive** |
| D3 tie-break by first non-zero ascending | tie-break | **alive** | **alive** |
| W1 / W3 weight and distance signs in `dualLinear` | weighted alpha | killed | killed |
| W2 `ballRadius` weight sign | weighted alpha | **alive** | killed |
| W4 vertex-dominated test off by one weight | weighted alpha | **alive** | killed |

B2 and D3 survive both versions of the spec. D3 is probably equivalent (the spec's circumsphere check uses the same
tie-break as the engine, so any consistent perturbation order satisfies it); B2 is not understood. Both are filed (see
"Open items"). W2 and W4 survived the *old* weighted spec: its properties check structure (face closure, no duplicates,
monotone filtration) and that zero weights give the unweighted complex, and a wrong weight sign keeps the structure
valid. The reduced spec has examples derived by hand (two points with weights (0.3, -0.3): vertex values -0.3 and 0.3,
the edge 0.34; a dominated vertex; an acute triangle; a power limit that keeps one vertex) and kills all four.

## Cuts made

| change | JVM | JS |
|---|---|---|
| `BowyerWatsonSpec`, degenerate input: inputs of more than 70 points (the 81-point 3^4 grid) are verified once, at the end, not after each insertion (16 s of the example's 26); on JS also 2 insertion orders, stepwise only up to 40 points, and no 3^4 grid | 27.0 -> 9.7 s | 171.6 -> 12.2 s |
| `AlphaComplexDQPWeightedSpec`: 400 cases (was 2000) plus the hand-derived and power-limit examples | 19.1 -> 3.2 s | 10.5 -> 1.9 s |
| `WideIndicesSpec`: `beEqualTo` on a 5,000-element listing rendered both sides before comparing; `==`, and 2 seeds instead of 8 on JS | 7.9 -> 3.1 s | 23.8 -> 1.5 s |
| `PlatformScalaCheck` (default 100 cases on the JVM, a tenth on JS) for the specs that took ScalaCheck's default: `CofaceSimplexStreamSpec` 47.5 -> 6.2 s, `VietorisRipsSpec` 32.8 -> 3.0 s, `RipserCohomologySpec` 24.8 -> 3.1 s on JS | 0 | -90% of their property work |
| `TestPlatform.propertyCases` for the explicit counts left (Ripser, packed Ripser, Cohomology, SimplexIndexing, BarcodeDistance, AlphaComplex) | 0 | tenth |
| `TestPlatform.perPlatform` seeds and grids on JS (the JVM keeps every seed): NeighbourLists 10 -> 3, WideIndices 8 -> 2, PackedCubical 3 and 2 -> 1 (29.5 -> 10.1 s), FastRepresentatives 3/12/6/4 -> 1/3/2/1 (20.7 -> 6.3 s), FlatLayers (DTM p, Dowker seeds; 13.9 -> 12.8 s), NaiveCapped primes {2,3,17} -> {3} (28.4 -> 22.1 s), S_4 comparisons against the bar complex (CollapsedNerve 46.1 -> 20.4 s, ClassifyingSpace 35.5 -> 26.4 s, BrownCollapseReference 25.1 -> 5.6 s), BarcodeRegression cloud 25-30 -> 12-15 points (14.2 -> 0.2 s) | 0 | see the per-spec figures |

Why `PlatformScalaCheck` is a trait with its own `given` and not a command-line setting: `scalacheck.mintestsok` on the
sbt command line and through `Test / testOptions` has no effect on a spec that takes specs2's default here. A given in a
subtrait beats the inherited one, and a spec's own `given Parameters` beats both (checked with a probe spec).

## Traps found

- `must beEqualTo(big)` renders both sides before it compares (3.5 s for 5,000 pairs). Where the failure text is not
  needed, compare with `==` and assert `must beTrue`; `rules/testing.md` already says this for OOM, the time is the
  same cause.
- `checkEveryStep = true` in `BowyerWatsonTriangulation` verifies the whole triangulation after each insertion: quadratic
  in the point count. Reserve it for the small inputs.
- A property that checks only structure (closure, monotonicity, duplicates) cannot see a sign error in the values. The
  weighted alpha spec had only those; examples with values derived by hand are the cheap fix.
- A seed or grid loop is a search. If a mutant is only killed by the third seed, the loop is doing work; check by
  mutation before shrinking it on the platform that is the only one to run it.

## Results

Sequential spec work on the 4-core cloud sandbox, same method as the baseline:

| | before | after |
|---|---|---|
| JVM (`tda4j/testOnly *`, one spec at a time) | 200 s, 1660 examples | 160 s, 1662 examples |
| Scala.js (`tda4jJS/testFull`, one spec at a time) | 730 s, 1171 examples | 270 s, 1173 examples |

All pass on both platforms. CI's `test-js` ran its specs 2.1x faster than the sequential sum (350 s for 730 s), so the
step should drop from about 350 s to about 130 s and the job from 8 min to about 4.5 to 5 min (resolve, compile and link,
about 140 s, are untouched); the PR's own CI run is the confirmation. The JVM job's spec step should lose about 20 s of
its 112 s. The two examples added are the hand-derived weighted alpha ones.

Scala.js is now dominated by `ClassifyingSpaceSpec` (26 s), `NaiveCappedSpec` (22 s), `CollapsedNerveSpec` (20 s),
`FlatLayersSpec` (13 s), `BowyerWatsonSpec` (12 s), `SheehyRipsStreamSpec` (12 s). They are 27 examples of a few
hundred ms each, not one hot spot, so further cuts there are small per example.

### Unchecked semantics for the Scala.js test link (measured, not applied)

`set tda4jJS / Test / fastLinkJS / scalaJSLinkerConfig ~= (_.withSemantics(_.optimized))` turns off the runtime checks the
dev-mode linker keeps (array bounds, null pointers, class casts become undefined behaviour instead of exceptions). Same
specs, same machine, after the cuts above: **311 s -> 210 s (-32%)**, all 1173 pass. Not applied: those checks are what
turns a platform-specific indexing mistake into a failure rather than a wrong answer, and the JS run exists to catch
platform differences. Offered to the project lead as a decision.

### Not changed, and why

- Spec removals: none. The coverage-redundant specs are 19 s of JVM time; every one carries an oracle.
- `NaiveCappedSpec` runs 15x slower on JS than on the JVM (the median shared spec is 4x). Its examples run alone on JS
  take about 8 to 12 s together (timed one at a time, each including sbt's dispatch) against 22 s inside the full run,
  so part of it is probably the state of the Node process (heap, warm-up) after earlier specs; not chased further.
- Moving the JVM-only benchmark and driver sources (about 189 kB of 525 kB under `src/test/scalajvm`) to their own
  module would shorten the JVM job's test compile by an estimated few seconds (size share, not measured); not worth a
  structural change.

## Open items

- #211: two Bowyer-Watson mutants survive every spec.
- Unchecked-semantics linking for the Scala.js tests (above): the project lead's decision.
