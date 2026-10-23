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
| `AlphaComplexDQPWeightedSpec` | 19.1 | 10.5 | two properties of 2000 cases (JS: 200) |
| `PackedCubicalCohomologySpec` | 11.1 | 29.5 | |
| `AlphaComplexSpec` | 9.7 | 6.3 | 2000 cases per backend (JS: 200) |
| `CofaceSimplexStreamSpec` | 8.1 | 47.5 | five 100-case properties |
| `WideIndicesSpec` | 7.9 | 23.8 | |
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
