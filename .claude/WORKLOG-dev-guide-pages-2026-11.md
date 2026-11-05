# Developer's Guide pages (2026-11-05)

Request (Mikael): seven pages for the Developer's Guide, one after another, in the style of jmatuszewski's edits
(concise, human readers, no history, no `.claude/` pointers in the docs). Issue #162: the older pages were only spot-fixed.

Added: `facades.md`, `contracts.md`, `adding-engines-and-complexes.md`, `jvm-and-js.md`, `testing.md`, `pr-workflow.md`.
Extended: `performance.md` (worked A/B, steady-state JFR, live-data memory rule, same entry point on both platforms);
`index.md` and `sidebar.yml` list the new pages.

Sources: `.claude/rules/{facade,python,engines,streams,scalajs,testing,docs-and-tutorials,algebra}.md`, `CONTRIBUTING.md`,
`RELEASE.md`, the steward skill, the code (`Persistence.scala`, `matlab.TDA4j` option sets, `TDA4jConf`,
`python/tests/test_facade_coverage.py`, `PaperBenchmarkDriver`), and the project's topic notes on test-suite sizing, Scala.js
scaling, merge-queue stacking, changelog fragments and snapshot publishing.

Not done / not verified: the docs build (`TDA4J_SCALA_VERSION=3.8.4 sbt tda4j/doc`) was not run in this session; the pages
contain no Scala fences, but heading anchors used in links (`#docs-are-tests`, `#memory-measure-the-live-data`,
`architecture.md#two-platforms-the-jvm-and-scala-js`) assume scaladoc's usual slug rule. The existing pages
(`persistence-engines.md`, `architecture.md`, `gotchas.md`) still overlap in places with `contracts.md` and were not condensed.
