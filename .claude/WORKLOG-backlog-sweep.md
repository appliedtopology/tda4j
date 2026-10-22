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

## Still to do

Real work, in this order: file I/O strings and formats (#118, #69, #195), JavaScript API (#119), engines (#57, #82, #91).
