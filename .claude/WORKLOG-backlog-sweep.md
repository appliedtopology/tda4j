# Backlog sweep (2026-10-22)

Issues covered: #51, #57, #69, #82, #88, #89, #91, #111, #118, #119, #126-#139, #157-#162, plus #195 (subset of #69).
Work is sequential, one issue at a time, in as few PRs as possible (project lead). Point-in-time record.

## Triage (evidence in the comment on each issue)

| issue | verdict | evidence |
|---|---|---|
| #51 | closed; rest is #91 | skip rule at `maxDimension = 1` and capped naive; `BarcodeRegressionSpec` range narrowed with a measured reason |
| #88 | closed | `CellRanks.ranked`; `CellRanksSpec` reproduces the four-cell stream |
| #89 | closed | mutation checks run on current `scala`: negated fallback and `else 0` both fail `PersistenceInChunksSpec` (the named example) |
| #126, #128, #129, #132 | closed | commit 6e166ca; `bench/test_bench.py` (102 tests, 9 skipped) passes |
| #127, #130, #131 | open | harness side done; each needs a compute-server run (giotto-ph message, dragon2000 after jitter, the three high-dimensional alpha cases) |
| #133, #135, #138, #139 | open | need data files or the server run |
| #157, #161 | closed | grep of `_docs`/README clean; README and installation page carry the Java/MATLAB path |
| #111 (and its duplicate #122) | fixed here | `rules/engines.md` 14,150 -> ~12.05k characters, no rule dropped except worklog pointers |
| #159 | page exists; names checked | Ripser and GUDHI names/defaults checked against their docs; JavaPlex Vietoris-Rips calls checked against its source (other JavaPlex rows not rechecked) |
| #160 | partly | `SECURITY.md`, `CITATION.cff`, README citation line exist; the four stray root files moved (`examples/`, `bench/alpha-dqp-reference/`); `CODE_OF_CONDUCT.md` waits on #167 |
| #162 | open | developer's-guide read-through remains |

## Mutation check for #89 (commands)

`sed -i 's/else streamOrdering.compare(x, y)/else -streamOrdering.compare(x, y)/' CellRanks.scala` (then `else 0`), then
`sbt "tda4j/testOnly *PersistenceInChunksSpec *CellRanksSpec *ChunksTrianglesSpec"`; restored with `git checkout`.

## Outcomes after the triage

- **#118, #195, part of #69 (file formats).** Every format in `io/` has `parse*`/`format*` on text or bytes, with
  `read*`/`write*` delegating through `FileAccess` (`StringFormatsSpec`; Scala.js prints `1.0` as `1`, so shared specs
  interpolate `${1.0}`). New: Ripser sparse (`RipserSparseSpec`, CLI `ripser-sparse`), PHAT boundary matrices (ASCII and
  binary, Z/2), Perseus uniform and non-uniform simplicial toplex (`PhatPerseusToplexSpec`). Not done for #69: facade and
  CLI input for PHAT/toplex (the facade has no entry for an explicit simplicial complex), and a round trip against files
  written by the real PHAT and Perseus tools. #69 stays open, `unverified`.
- **#119 (JavaScript).** `Exports` gained landmarks, `h1Bars`, circular and toroidal coordinates, the three text
  parsers and the `Result` members `persistenceImage`, `numCells`, `boundary()`, `column...`; typings, README, user guide
  and `rules/scalajs.md` follow (`ExportsSpec` on Scala.js, 10 examples).
- **#82 (boxed coefficients).** JFR `ObjectAllocationSample` of `PackedRipserCohomologyEngine` over F2 on 192 random
  points of the 3-sphere in R^4, degrees <= 2, via `SingleEngineProfileDriver` (3 timed trials after the first, median
  3.05 s): of 1,101,696 simplices, 1,078,323 are apparent pairs and only 2,334 need a substitution (a column
  reduction). Allocation by class: `int[]` 29.8%, `long[]` 18.1%, `DiameterIndex` 14.0%, `FacetCursor` 9.8%, `Some` 9.0%,
  `double[]` 6.1%, `Integer` 3.8%, `Object[]` 2.8%; the top allocation sites are `SimplexIndexing.decodeToArray`
  (22.9%), `DiameterIndex.apply` (14.0%), `Arrays.copyOf` (19.5%). So on this input the boxed coefficients are about 6.6% of
  the allocated bytes and the working columns see 2,334 reductions: the cost is the cofacet enumeration of the apparent-pair
  test, not the coefficients. Not measured: F17 (more reductions), the Ripser paper's reduction-heavy sets (`o3_1024`),
  and the Scala Native case that motivates the issue. A 300-point random O(3) cloud I tried runs 0.4 s, too short to say
  anything.
- **#57 (emergent pairs).** Not started. The profile above bounds it on random spheres: 98% of the columns are already
  apparent pairs, so an emergent-pair shortcut can only change the remaining ~2%; what it could save is part of the
  enumeration cost of the apparent test, which needs an implementation to measure. Needs the paper's data sets for the A/B.
- **#91 (chunks from `maxDimension` 2).** Not started; the issue's own estimate is that the gain is bounded by the
  capped naive engine's time (430 vs 220-240 ms at 30 points in R^3).

Native scalafmt (`.claude/scripts/install-scalafmt.sh`) formats; `sbt scalafmtAll` still cannot download its config here.
`sbt tda4jJS/...` works in this environment.
