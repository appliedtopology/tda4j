# Test-suite compiler warnings (2026-10-20)

Asked to triage the warnings printed when building the test suite and fix those that fit our habits.

## What was there

`sbt tda4j/clean tda4j/Test/compile` printed 157 warnings (the Scala.js test compile shares the same sources):

- 153 `implicitConversions` feature warnings, in 18 JVM-shared and 5 `scalajvm` spec files (`PlotGrammarSpec` alone 100). All from
  specs2 5.9's matcher DSL: `typedValueCheck` 116 (`must contain(x)`, `allOf(...)`), `matcherIsValueCheck` 19,
  `asResultToProp` 16 (`(d > e) ==> ...`), `given_Conversion_Boolean_Result` 2.
- 3 E165 "pattern selector should be an instance of Matchable" (`NeighbourListsSpec`, `PackedWorkingColumnSpec` x2): `case x: T`
  on an `Any` selector.
- 1 E129 "pure expression does nothing" in the generated `CircularAndToroidalCoordinatesScript.scala`: the page's narrative fence
  had a bare `circleBars` line, which is compiled into the never-called `Narrative.narrative()`.

## Fixed

- `commonSettings`: `Test / scalacOptions += "-language:implicitConversions"`. The 2026-09-19 cleanup (`WORKLOG-compiler-warnings.md`)
  had made the flag project-wide; 451eeb2 dropped it from main when `into` replaced the language feature, which left the specs2
  DSL warning again (`WORKLOG-release-prep-2026-10-14.md` noted 57 of them and left them). The alternative, an
  `import scala.language.implicitConversions` in ~25 files, would be 25 copies of a line that every new spec must remember.
  Main keeps the strict setting, so a conversion of our own code still warns.
- The 3 Matchable warnings: `c.asMatchable match` (same idiom as `ChainPackedRipserReference.equals`, a compile-time cast).
- The pure expression: `circleBars.toVector` in the tutorial page, so the comment still shows the value.

## Checked

`tda4j/Test/compile` and `tda4jJS/Test/compile`: no warnings (Scala.js main and test compiled from the same flags). `NeighbourListsSpec`,
`PackedWorkingColumnSpec`, `FiniteFieldSpec` pass. Main sources were served from the disk cache in the first run, so main's own
warning count was not re-measured here.

## Not touched

`-Wunused:all` (~319, `WORKLOG-compiler-warnings.md`) is not on by default and not part of "building the test suite".
