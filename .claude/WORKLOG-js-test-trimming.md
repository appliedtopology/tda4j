# Trimming the Scala.js test suite (2026-10-20)

Project lead's question: CI takes almost 15 minutes, mostly because of the new Scala.js test suite; is there anything we
can safely reduce? Base: `scala` at 0d43d90 (merge of #140). Report for the project lead:
`/mnt/project-files/js-test-trimming/report.md` (project files, not in the repo).

## Where the time went

CI run 39437202763: `test-js` 12 min of the 12.5 min wall clock (JVM `test` 5.5 min); compile and link about 1.5 min,
specs about 10.4 min. In that log the second-to-last spec reported at 14:21:16 and `APISpec` at 14:25:20.

**specs2's "Finished in" is wrong on Scala.js for many specs** (`APISpec`: 13 ms for 12 minutes; `AlphaComplexSpec`:
19 ms for 101 s), so the CI log cannot rank JS specs. Method that works: run with
`sbt 'set LocalProject("tda4jJS") / Test / parallelExecution := false' tda4jJS/testFull`, timestamp each output line, and
take the gap between consecutive "Total for specification" lines (a spec's report is printed whole when it finishes).
(`set tda4jJS / Test / ...` fails: the matrix row's id is not a Scala identifier in `set`.)

Local, 4-core cloud sandbox, Node 22.22:

| run | time |
|---|---|
| sequential, total spec work | 32.4 min (1943 s) |
| parallel (`testOnly *`, sbt's default: one Node process per core) | 16.0 min (963 s) |
| parallel, `testOnly * -- ex ^(?!A.full.persistent).*` (one example of `APISpec` excluded) | 5.9 min (355 s) |

In the 16-min run every other spec had finished at 318 s; `APISpec` alone ran to 969 s. In the 5.9-min run the tail was
`BowyerWatsonSpec` (334 s) and `AlphaComplexSpec` (362 s).

Sequential seconds per spec, JS (JVM figure: specs2's report from the CI log): `APISpec` 712 (67), `BowyerWatsonSpec` 228
(60; its "degenerate input" example alone 156), `AlphaComplexDQPWeightedSpec` 132 (21), `AlphaComplexSpec` 101 (16),
`CofacetsSpec` 82 (19), `CofaceSimplexStreamSpec` 62 (11), `SheehyRipsStreamSpec` 57 (12), `CollapsedNerveSpec` 55 (13),
`ClassifyingSpaceSpec` 53 (18), `VietorisRipsSpec` 52 (16), `RipserCohomologySpec` 38 (11), `PackedCubicalCohomologySpec`
37 (0.6, emulated `Long`), `AlphaComplexDQPSpatialIndexSpec` 24 (1.3).

`APISpec`'s "A full persistent homology computation" built `LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(50
unseeded random points on a circle), 4)`: the enumerating stream walks every 5-subset (C(50,5) = 2.1 million) and the
example asserted only that `diagramAt(0.15)` was non-empty, which 50 vertices guarantee. Timed alone, the other three
examples took 2 to 4 s together.

## Changes (project lead chose all three, and to delete `APISpec` whole: "a testing ground for figuring out call syntax")

1. `APISpec` deleted. Its other checks are covered: `TDAlab(4)` goes through `Coefficients.apply`, which
   `PersistenceVerbSpec` checks with `characteristic = 4`; lab chain arithmetic is `LabSpec`/`UserImportsSpec`.
2. `TestPlatform.propertyCases(n)`, a test-side seam (`src/test/scalajvm`: `n`; `src/test/scalajs`, a new directory:
   `n / 10`), in `AlphaComplexSpec` (2000), `AlphaComplexDQPWeightedSpec` (2000), `AlphaComplexDQPSpatialIndexSpec`
   (500). Reason: those counts exist to hit 1-in-500 to 1-in-12000 inputs; the code is shared and the JVM job runs the
   full search on every push.
3. `CofacetsSpec` asserted `allCofacets.size >= 0` on 25 to 250 points. Now: a vertex and up to two of its neighbours
   on 10 to 40 points (half of the clouds on a coarse grid, for ties and repeats), against a brute-force list (each
   finite coface once, ascending diameter). **It failed on the first case**: `CofacetIterator`'s `process()` restarted
   every vertex's neighbour walk at index 0 although the initial pass had already counted the neighbours within the
   simplex's own diameter, so each of those was counted twice and a vertex near only some of the simplex's vertices
   reached the count of a cofacet. Fix: start the walk where the initial pass stopped (`vPointers`). Mutation-checked
   (the spec fails with the fix line removed), and 5000 cases pass on the JVM. `CofacetIterator` is used by nothing in
   the library (only exported by `TDAlab`).
4. `.claude/scripts/cloud-setup-script.sh` also runs `tda4jJS/fastLinkJS`: the Scala.js plugin resolves
   `scalajs-linker_3` (and `closure-compiler`, `scala3-library_3` 3.8.3) only when it first links, so a fresh session's
   first JS test run met Maven Central's 429s. `tda4jJS/scalaJSLinkerImpl` does not download it (checked: it succeeds
   with the linker jar removed from the coursier cache). The environment's Setup script must be re-pasted for this to
   take effect.

Left alone, on purpose: `BowyerWatsonSpec`'s degenerate-input example (exact `BigDecimal` predicates, whose Scala.js
implementation is a port: JS-specific coverage), `PackedCubicalCohomologySpec` (slow on JS because `Long` is emulated,
which is why it should run there), and the `clean` in CI's `test-js` step (about 50 s of compiling).

## Environment notes

repo1.maven.org answered 429 for the linker the whole session; Google's mirror
(`https://maven-central.storage-download.googleapis.com/maven2`) did not. A coursier `mirror.properties` was not honored
by sbt; seeding `~/.cache/coursier/v1/https/repo1.maven.org/maven2/<path>` with the poms, jars and `.sha1` files from
the mirror worked.

## Verified

- After the changes, same sandbox, `tda4jJS/testOnly *` in parallel: **258 s (4.3 min), 1052 of 1052 examples pass**
  (was 963 s and 1056: `APISpec` had 4). One trial each; the difference is far outside run-to-run noise. The new tail
  is `CofaceSimplexStreamSpec`, `BowyerWatsonSpec` and `CollapsedNerveSpec`, all done by 263 s.
- `tda4j/testFull`: 1528 examples, 1520 passed, 8 skipped (the benchmarks), 0 failures.
- The alpha specs ran 200 cases per property on JS (400 expectations per spec) and 2000 on the JVM.
- The CI wall clock after the change is not measured here; the PR's own CI run is the measurement.

## Not done

- `BowyerWatsonSpec` (228 s of sequential JS work) and the specs listed above are now the tail on JS; nothing more is
  cut (see "Left alone").
- The environment's Setup script has to be re-pasted by the project lead for the linker warm-up to apply.
