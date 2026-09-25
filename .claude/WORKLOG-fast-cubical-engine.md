# WORKLOG: dual union-find cubical engine (Flash Cubical)

Session: 2026-09-25. Item 6 of `.claude/WORKLOG-mainstream-feature-gap-analysis.md`'s recommended execution
order (autonomous session, "Auto Mode Active"). Prior items 1-5 (bottleneck/Wasserstein distance, diagram
vectorizations, boundary-matrix export, circular coordinates, flag-complex edge collapse) landed in earlier
commits this same session; this worklog covers item 6 only. Item 7 (dual union-find alpha-complex extension)
was explicitly deferred by the source worklog until this item validates, and is picked up separately.

## Goal

A faster engine for cubical persistent homology, following up on `DESIGN-fast-cubical-engine.md`'s own
"future direction" note (originally written against `CubicalRipser`/Wagner-Chen-Vuçini as the model). The
gap-analysis worklog instead pointed at Flash Cubical (Le Breton-Szustakowski-Piraud, arXiv:2606.04801): a
dual-graph union-find approach specifically for the TOP homological degree of a cubical grid, via Alexander
duality, rather than a grid-exploiting reduction algorithm.

## What was already done before this session (Phase 1)

Before writing any new code, I searched for a class named "SimplicialHomologyByDimensionContext," which the
originating gap-analysis worklog's own phrasing seemed to assume existed as a prerequisite. It does not exist
under that name anywhere in current source. Grepping `CellularPersistenceInChunksContext[Cube` and checking
`CubicalBenchmarkSpec`/`CubicalStreamSpec`/`CubicalProfileDriver` showed the actual prerequisite — genericizing
the chunks engine's own `unionFindDim01` fast path so it works for `Cube`, not just `Simplex[Int]` — was
**already done** in an earlier, undocumented session: `CellularPersistenceInChunksContext[Cube, CoefficientT]`
was already cross-validated against the naive engine in `CubicalStreamSpec.scala`. The only missing piece was
the ergonomic wrapper class matching `PersistenceInChunksContext`'s own one-line-subclass pattern:

```scala
class CubicalPersistenceInChunksContext[CoefficientT: Field](maxDim: Int = 5)
    extends CellularPersistenceInChunksContext[Cube, CoefficientT](maxDim) {}
```

Added to `homology/Homology.scala`, right after `PersistenceInChunksContext`. Compiles clean; no new tests
needed beyond what already existed, since the underlying mechanism was already validated.

## Phase 2: no paper access, original derivation

Every `WebFetch` attempt (arxiv.org/html, export.arxiv.org/abs, two third-party paper-mirror sites) returned
`EGRESS_BLOCKED`. `WebSearch` found no GitHub implementation to `add_repo` and read directly (the way item 5's
`EdgeCollapse` could clone `GUDHI/gudhi-devel` and read `Flag_complex_edge_collapser.h` verbatim). This is a
materially different, higher-risk situation than item 5's: a port checked against a reference implementation
vs. an original derivation checked only against itself.

The derivation (recorded in full, with the hand-verified worked example, in `DESIGN-fast-cubical-engine.md`'s
"2026-09-25 update" section — not repeated here) is Alexander duality: `H_{d-1}(X) ≅ H^0(S^d \ X)`, computed by
building a DUAL graph (top cells = dual vertices, codimension-1 cells = dual edges, a shared `∞` sentinel for
the grid's outer boundary) and running an ordinary elder-rule union-find on it in DESCENDING primal-filtration
order (a superlevel sweep), swapping each resulting bar's birth/death. Scope was deliberately narrowed to
ambient dimension 2 only: at `d=2`, `H_0` (primal) plus `H_1` (via the dual construction) account for every
nontrivial cell dimension a 2D grid has (`H_2` is identically zero for any subset of the plane — no 2D voids
possible), so no general `Chain` reduction is needed anywhere. `d=3` would need a genuinely different, harder
piece (general reduction on whatever `H_1` isn't already resolved by the `H_0`/`H_2` union-finds) and was
deferred rather than half-built.

The hand-derived 3×3 fixture (8 border pixels at value 0, one center pixel at value 1 — the same fixture
`CubicalStreamSpec` already uses) was traced completely by hand BEFORE any code was written, predicting exactly
one `H_1` bar `(0, 1)`. This hand trace turned out to be correct — both bugs found afterward (below) were in
the CODE relative to the validated design, not in the mathematical derivation itself.

## Implementation and the two bugs

`homology/FastCubicalHomology.scala`, `FastCubicalHomologyContext[CoefficientT: Field]`. First compile attempt
hit three unrelated Scala-level issues before it ran at all: an unrelated `OrderedCell`-derived implicit search
path getting dragged into a `.sortBy` call on a tuple key (fixed by writing an explicit `Ordering[DualEvent]`
instead), a `Cube | Null` type mismatch from conditionally assigning `null` for "no old-side top cell" (fixed
with `Option[Cube]`), and an indentation slip after restructuring an `if/else` into a `match`.

**Bug 1** (found first, via the hand-verified 3×3 fixture): `java.lang.IllegalStateException: dual component
missing its own boundary top cell Vector(1, 1)`. Root cause: the code computed `oldTopCube` (whether the
surviving side of a merge has a real top cell to look up, vs. being genuinely `∞`) by testing `oldTopId ==
infinityId` — the RAW facet-event id. But by the time a specific edge is processed, that id's own component may
have ALREADY merged into infinity's component via an earlier tied-value edge this same pass, so `find(oldTopId)`
(the RESOLVED root) can be `infinityId` even when the raw `oldTopId` isn't. When that happens, the code took the
"look up a real top cell" branch and searched `chainOf(oldRoot)` = `chainOf(infinityId)`, which is deliberately
never populated (infinity never dies, so its own chain content is never read) — the lookup fails. Fix: test the
resolved root (`oldRoot == infinityId`), not the raw id.

**Bug 2** (found after Bug 1's fix, via `TDA4jSpec`'s own MATLAB-facing "ring" fixture — a 3×3 grid with a
PERMANENTLY missing center pixel, `topValue = +Infinity`, Perseus's `-1` convention): the exact same exception,
different top cell. Root cause: the young/old decision at each merge compared `birthOf(ra) <= birthOf(rb)`,
relying on `birthOf(infinityId) = +Infinity` being the unique MAXIMUM so `infinityId` could never lose that
comparison and be picked as the young (dying) side. That assumption breaks the moment a REAL top cell also
carries `topValue = +Infinity` (a legitimate, real input — a permanently-missing cell) and ties against it:
`+Infinity <= +Infinity` is true regardless of which side is checked first, so `infinityId` could genuinely be
picked as `youngRoot`, then `chainOf(youngRoot) = chainOf(infinityId)` (empty) failed the same lookup. Fix:
special-case `infinityId` explicitly in the young/old decision (`if ra == infinityId then (rb, ra) else if rb
== infinityId then (ra, rb) else ...`), rather than relying on the birth-value comparison alone to break this
specific tie correctly.

Both fixes are in `computeDualTopDimension`'s merge-event branch, with comments explaining the reasoning
in-place (not just here) since the next person touching this code needs the same context.

**Verified the fix is complete, not just "the two cases found so far"**: extended the random-fixture property
test (see below) to inject `+Infinity`-valued top cells (`~1-in-5` per cell, via a sentinel level) across many
random 2D grid configurations, not just the two hand-picked fixtures — including a case where five of six top
cells in a single grid are simultaneously `+Infinity` (found by the property test itself, not hand-constructed:
`shape=(3,2), values=[3,4,4,4,4,4]`). Ran the extended suite four times total (once during the fix, three more
afterward) with fresh random seeds each time; all clean.

## A test-helper limitation found, not an engine bug

That same extreme fixture (`shape=(3,2)`, one finite pixel, five `+Infinity` pixels) initially FAILED the
random property test — not with an exception, but a `false` boolean. Isolated via a throwaway diagnostic
script (`Test/runMain`, deleted before commit) that this engine's own bars matched `CubicalHomologyContext`'s
(the naive oracle) bar-for-bar EXACTLY, and every `H_1` representative was a genuine cycle. The actual failing
sub-check was `HomologyFixtures.totalBarsAccountForAllCells`, whose "essential iff `upper.isFinite == false`"
proxy silently miscounts once a PRIMAL cell can itself carry filtration value `+Infinity`: a genuine PAIRING
between two such cells (a real two-cell contribution to the invariant, exactly like any other finite pairing)
reports both of the resulting bar's endpoints as the literal `Double` value `+Infinity`, which `upper.isFinite`
cannot distinguish from a true unpaired essential class (a one-cell contribution). Confirmed this is a
pre-existing, SHARED limitation of the helper, not specific to the new engine, by checking that the exact same
input triples (this engine's own output, byte-identical to the naive oracle's) would make the helper miscount
regardless of which engine produced them. No existing spec in the codebase had previously combined this helper
with a generator that injects raw `+Infinity` primal values, so this combination had simply never been
exercised before. Fix: the random property test drops the `totalBarsAccountForAllCells` sub-check (with a
comment recording why), keeping the strictly stronger naive-agreement and cycle-check assertions, rather than
either weakening the helper itself (a shared, widely-used piece of test infrastructure, out of scope for this
session) or narrowing the generator to dodge the finding.

## Test suite

`homology/FastCubicalHomologySpec.scala`: the two exact-bar-count fixtures reused verbatim from
`CubicalStreamSpec` (constant 2×2, single hot center in 3×3); a new two-independent-holes 5×5 fixture (stresses
multiple simultaneous non-trivial dual merges, not just one); the `ambientDim != 2` rejection; the
permanently-missing-center regression fixture (Bug 2, pinned independently of the MATLAB-layer test that first
caught it, since the bug is in this engine's own union-find, not in any facade code); a genuine-cycle check
(every `H_1` representative's boundary is zero) across all hand fixtures; an `Fp(3)` sign-genericity check
(same fixtures, `Double` vs. `GF3.Fp`, both barcode values and cycle-ness) per this codebase's own "F2 hides
sign errors" testing lesson; and the random 2D property test described above. All pass, repeatably.

## Four-surface integration

Unlike `RipserCohomologyContext` (kept deliberately internal/test-only because `PackedRipserCohomologyContext`
already supersedes it in production), this engine has no existing production alternative for what it does —
it's a genuinely new, faster option for 2D cubical grids, not a redundant cross-check — so it was wired into
all four surfaces per `CLAUDE.md`'s own "finalizing a user-visible capability" convention, not left as an
internal-only class:

- **`matlab.TDA4j`**: new `EngineKind.FastCubical` case (parsed from `"fast-cubical"`). Rejected early and
  centrally for every non-cubical entry point (`dispatch`'s own guard right after `engine` is resolved catches
  the one-shot point-cloud/distance-matrix paths; `resolveWitnessEngine` catches BOTH witness entry points,
  including the two-step recipe's `dispatchWitnessFromLandmarks`, which does NOT go through `dispatch` at all —
  checked this directly rather than assuming the `dispatch`-level guard was sufficient). Every per-complex
  `engine match` in `computeGeneric` also gained an explicit `FastCubical` arm (extending an existing
  `Ripser | Chunks` catch-all where one existed, adding a new arm where the branch had no catch-all) — the
  compiler's own non-exhaustive-match warnings after adding the enum case named every site that needed one;
  fixed all seven until compilation was silent again, rather than leaving warnings that mask a real
  `MatchError` risk if the earlier guards are ever refactored away. `dispatchCubical` validates
  `stream.ambientDim == 2` before calling the engine, with a message naming the actual dimension (not a bare,
  unlabeled `IllegalArgumentException` from deep inside `FastCubicalHomologyContext`'s own `require`).
  `computeCubicalGeneric`'s new branch calls `FastCubicalHomologyContext[C]().persistentHomology(stream)`
  directly (bypassing `PersistenceEngine[CellT, C]`, the same way `engine=ripser` already does, for the same
  "specialized to a concrete stream type, not generic over `CellT`" reason `PersistenceEngine`'s own doc comment
  gives).
- **`cli`**: `--engine`'s `ScallopOption[String]` was already a free-form passthrough (no Scallop-side enum), so
  `--engine fast-cubical` worked with zero code changes — confirmed this by checking `TDA4jConf.scala`'s own
  definition before assuming a change was needed. Only the description string needed updating.
- **`src/docs/developers-guide/`**: new "6. `FastCubicalHomologyContext`" section in `persistence-engines.md`
  (plus a note on why it isn't a table column, and an updated "Representation-specific (cubical)" reasoning
  bullet), a new "Dual union-find cubical engine (Flash Cubical)" subsection in `architecture.md` right after
  the existing "Cubical complexes" section, and a one-line class/file-list update in `class-diagrams.md`.
- **`src/docs/user-guide/README.md`**: new engine value in the shared options table plus its own refusal-rule
  sentence, a new "A faster engine for 2D images" quick-start subsection with a Scala snippet, and a new row in
  the "Which persistence engine?" table.

`sbt laikaSite` builds clean after all of the above (no new warnings beyond four pre-existing, unrelated
scaladoc cross-reference warnings).

## An unrelated infrastructure finding along the way

Running `sbt scalafmtAll` mid-session (to clean up formatting drift left over from earlier commits this same
session) also silently reformatted `project/SnipDirective.scala` — a file compiled by sbt's own Scala 2.12
meta-build, not this project's Scala 3.9 — into Scala-3-only `then`/indentation syntax that Scala 2 cannot
parse at all, breaking `sbt` itself (had to `git checkout` the file back). A `fileOverride` glob in
`.scalafmt.conf` scoping that one file to `runner.dialect = scala212` was tried first and did NOT take effect
for the `scalafmtSbt`/`scalafmtSbtCheck` tasks specifically (still flagged/still rewrote the file with the
override in place) — apparently a different code path in the sbt-scalafmt plugin that doesn't do path-based
config resolution the way the main `Compile`/`Test` formatting tasks do. A `// format: off` directive at the
top of the file, tried instead, fixed both problems at once (verified directly, not assumed): `scalafmtSbt`
now leaves the file untouched and `scalafmtSbtCheck` passes cleanly. Full reasoning and the "don't try
fileOverride again without confirming it actually takes effect for this task" warning are recorded as a
comment directly in `project/SnipDirective.scala` itself, since that's where the next person touching this
file will actually see it — not repeated as a separate `CLAUDE.md` entry.

## Commands

```
sbt "testOnly org.appliedtopology.tda4j.homology.FastCubicalHomologySpec"
sbt "testOnly org.appliedtopology.tda4j.matlab.TDA4jSpec"
sbt "testOnly org.appliedtopology.tda4j.cli.CLISpec"
sbt clean test        # full suite, confirmed green after every change in this worklog
sbt laikaSite          # docs build, confirmed clean
```
