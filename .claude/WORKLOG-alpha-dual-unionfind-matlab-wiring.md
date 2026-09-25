# WORKLOG: wiring FastAlphaHomologyContext into matlab.TDA4j + CLI as engine="fast-alpha"

Session: 2026-09-25 (continuation of the same session as `WORKLOG-alpha-dual-unionfind.md`, which left this
engine deliberately unwired pending the project lead's sign-off on shipping its measured ~1-in-18700 exception
rate as a production option). The project lead reviewed that tradeoff and asked for the wiring, with one
explicit condition: "make sure that when the exception throws it is VERY clear for an unsuspecting user about
what's happening."

## Exception redesign: `IllegalStateException` -> named `FastAlphaTriangulationException`

The engine's own precondition check (`facetToTopIds.filter { case (_, ids) => ids.size < 1 || ids.size > 2 }`)
previously threw a bare `IllegalStateException` with a message written for this engine's own developers
(facet ids, coface counts, a pointer to the design note). That's wrong for a production option: a MATLAB/CLI
end user hitting this on real data has no way to know it isn't their fault, or what to do next.

Fixed by:

- A new, named `class FastAlphaTriangulationException(message: String) extends RuntimeException(message)` in
  `FastAlphaHomology.scala`, mirroring `NoIntegerCocycleException`'s already-established pattern (a plain
  `RuntimeException` subtype, not `IllegalStateException`) — lets a caller catch this specific condition
  without also catching unrelated engine bugs, and lets the message be written for two audiences.
- The message itself is deliberately layered: plain language first ("This is NOT an error in your data...",
  what HelixDelaunay's limitation actually is, in prose a non-Scala-developer can follow), then "TO GET YOUR
  RESULT" with concrete retry code for both MATLAB (`TDA4j.computeFromPoints(...)`) and the CLI
  (`--complex alpha --engine naive`), then a "(Technical detail, for developers...)" appendix with the actual
  facet/coface counts and a pointer to the design note — the same two-audience structure
  `NoIntegerCocycleException` already uses for `CircularCoordinates`, applied here for the first time to an
  engine that crosses the MATLAB/CLI boundary routinely rather than only on request.

`FastAlphaHomologySpec`'s exception test was rewritten to assert on the new message's own content (`"NOT an
error in your data"`, `"TO GET YOUR RESULT"`, `"\"naive\""`, `"containing-top-simplex count"`) rather than the
old message's wording; the random-property test's catch clause now matches `FastAlphaTriangulationException`
by type instead of an `IllegalStateException` message substring.

## `matlab.TDA4j` wiring

Added `EngineKind.FastAlpha` (parsed from `"fast-alpha"`) alongside the existing `FastCubical`. Because
`EngineKind` is matched exhaustively in nine separate places across `TDA4j.scala` (VR, Cech, DtmRips,
SheehyRips, DtmAlpha, witness-lazy, witness-general, `dispatchCubical`, `computeCubicalGeneric`), adding a case
surfaces a non-exhaustive-match warning (`sbt compile`'s own `[E029]`-style warning — not a hard error in this
build, but treated as must-fix, the same defense-in-depth this codebase applied when `FastCubical` was added)
at every one of those sites until each is updated. Concretely:

- The only ACCEPTING arm is inside `computeGeneric`'s `ComplexKind.Alpha` branch: pattern-matches on the
  constructed `alphaStream`'s actual runtime type (`case helix: HelixDelaunay => ...`), not on the
  `alphaBackend` string directly — `alphaBackend="default"` also resolves to `HelixDelaunay` (see
  `AlphaShapes.apply`'s own dispatch), so checking the constructed type is what correctly accepts that case
  too, not just the literal string `"helix"`. Rejects ambient dimension != 2 with a message naming the actual
  dimension; rejects any non-`HelixDelaunay` stream (i.e. `alphaBackend=DQP`) with a message explaining WHY
  (`FastAlphaHomologyContext` cannot consume `AlphaShapeDQP`'s output at all, not just "wrong backend").
  `FastAlphaTriangulationException` itself is deliberately NOT caught/rewrapped here — it already carries an
  end-user-appropriate message, and rewrapping would only lose the original stack trace for nothing.
- Every other `EngineKind` match (the eight sites above, plus `resolveWitnessEngine` and `dispatch`'s own
  complex/engine cross-check) got `FastAlpha` added to its existing reject-catch-all arm (joined via `|` with
  `FastCubical` where those two now share a message, e.g. "engine=fast-alpha is not offered for
  complex=witness... Use complex=alpha for engine=fast-alpha"), each with a reason specific to why THAT
  complex can't use this engine (no notion of a Vietoris-Rips complex / cubical complex / witness complex at
  all — `FastAlphaHomologyContext` is `HelixDelaunay`-specialized, full stop).
- `sbt compile` confirmed zero exhaustiveness warnings after all nine sites were updated.

Updated `computeFromPoints`/`computeFromDistanceMatrix`'s own doc comment (`"engine"` bullet) to describe
`"fast-alpha"` alongside the existing four/five values, including the measured exception rate and the retry
guidance, matching the pattern the `"fast-cubical"` bullet already established.

## CLI wiring

`TDA4jConf.engine`'s description string extended to list `fast-alpha` and its constraints (same substance as
the MATLAB doc comment, condensed for a `--help` line). No new Scallop option needed — `--engine fast-alpha`
flows through the existing `--engine`/`--alpha-backend` flags exactly like every other engine value; CLI
dispatch is a pure pass-through to `matlab.TDA4j`'s own options map (`TDA4jConf`'s own doc: "this class does no
validation of its own"). Verified `--alpha-backend` is the actual flag name (not `--alphaBackend` or similar)
via a throwaway `--help`-printing scratch script before writing CLI-facing doc text — not assumed from the
Scala field name — then deleted the scratch file.

## Tests added

- `TDA4jSpec` (`"complex=alpha, through the facade"` block): five new tests —
  agreement with `engine=naive` up to floating-point tolerance (never exact — see below);
  rejecting `alphaBackend=DQP` combined with `engine=fast-alpha`; rejecting a 3D point cloud; rejecting
  `engine=fast-alpha` combined with `complex=vr` (the reject-everywhere-else pattern); and the exception
  message's own content propagating through the facade unmodified, on the SAME pinned 12-point
  facet-multiplicity-violation fixture `FastAlphaHomologySpec` already uses.
- `CLISpec`: one end-to-end test running `--complex alpha --engine fast-alpha` against a real file on disk.

### A parsing dead-end worth recording: `PersistenceBar.toString` is not comma-separated triples

The first draft of the CLI test assumed the CLI's `--output-format=text` (default) output was parseable as
plain `"dim,birth,death"` triples via `.split(",")`, to compare against a direct `TDA4j.computeFromPoints`
call's own bars. Both assumptions were wrong: `Barcode.scala`'s `PersistenceBar.toString` actually produces
`s"""$dim: $open,$closed$annotationString"""` where `open`/`closed` are THEMSELVES bracket-formatted
substrings (e.g. `"[0.5"`, `"2.3)"`) — a naive split would hand back non-numeric garbage, not birth/death
values. Rather than write a real parser for a format that exists for human readability, not round-tripping
(`--output-format=csv`/`gudhi`/`dipha` exist for that), the test was redesigned around bar COUNT agreement
against a direct `engine=naive` call instead of value-for-value agreement — which also sidesteps a second,
independent hazard: the CLI run and the "direct" call construct two SEPARATE `HelixDelaunay` triangulations
from the same points, and `TDA4jSpec`'s own "complex=alpha, through the facade" section already documents
last-ULP construction nondeterminism between independent `HelixDelaunay` builds (a `mutable.Set` whose
iteration order affects floating-point summation order) — exact comparison across two independent
constructions is already known-unsafe in this codebase, tolerance or count-based comparison is the established
pattern. This CLI test's own job is confirming the plumbing (does `--engine fast-alpha` actually reach
`engine=fast-alpha` through Scallop and a real file on disk, without crashing, with the right SHAPE of result)
— engine correctness itself is already covered directly by `FastAlphaHomologySpec`/`TDA4jSpec`.

## Validation

```
sbt "Test/compile"                                    # clean
sbt "testOnly ...CLISpec"                             # 31 examples, 0 failures (fast-alpha test included)
sbt clean test                                        # 561 examples, 550 passed, 11 skipped (benchmarks), 0 failures
sbt mimaReportBinaryIssues                            # no previous artifacts (pre-1.0) -- nothing to check
sbt scalafmtCheck scalafmtSbtCheck                    # 2 files initially unformatted (FastAlphaHomology.scala,
                                                       # TDA4j.scala) -- fixed via scalafmtAll (pure re-wrapping,
                                                       # confirmed via diff, no semantic change); re-run clean
sbt "testOnly ...FastAlphaHomologySpec ...TDA4jSpec ...CLISpec"   # 121 examples, 0 failures, post-reformat
sbt laikaSite                                         # clean (pre-existing, unrelated scaladoc link warnings only)
```

## Docs updated

Removed the "not yet wired into matlab.TDA4j/cli" framing (item 7's original scope decision) everywhere it
appeared, replacing it with the wired-in state and its actual constraints (`alphaBackend=helix`, ambient
dimension 2): `.claude/CLAUDE.md` (Alpha complex section, MATLAB API section), `alpha-complex.md`,
`persistence-engines.md` (engine 7 section, plus the streams-vs-engines table's own `fast-cubical` footnote,
which got a `fast-alpha` sibling), `class-diagrams.md`, and `user-guide/README.md` (new "A faster engine for 2D
alpha complexes" subsection mirroring the cubical one, plus the options table, the engine-rejection prose, and
the "Which persistence engine?" table).

## Explicitly out of scope for this entry

The project lead separately asked (mid-session, before this wiring was finished) for BOTH `FastCubicalHomologyContext`
and `FastAlphaHomologyContext` to be extended to ambient dimension >= 3 via a hybrid approach — fast union-find
for `H_0`/`H_{d-1}` at any dimension, general `Chain` reduction for the residual "middle" dimensions
(`1 <= k <= d-2`) — since the mechanism itself doesn't degrade with dimension, only the FRACTION of total
homology it resolves for free shrinks as `d` grows (there are zero middle dimensions at `d=2`, `d-2` of them in
general). That is new, unscoped work requiring its own design treatment (see the forthcoming
`DESIGN-fast-engines-hybrid-middle-dimensions.md` if this file's own directory listing shows one by the time
you're reading this) — not started as part of this entry, which is scoped strictly to the matlab/CLI wiring
task above.
