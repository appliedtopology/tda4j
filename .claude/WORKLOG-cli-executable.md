# WORKLOG: `tda4j` CLI executable

Point-in-time record, per this project's worklog convention (`[[tda4j-worklog-convention]]`). Not retroactively
edited after the fact.

## Ask

"It may also be useful to have an executable for the library - we can use the Matlab layer as a guide for what to
include in the executable. Decline and Scallop look like good CLI arguments parsers - figure out which one we may
want to use and set it up."

## Parser choice: Scallop over Decline

Checked both against their own current source/release, not from memory (`api.github.com/.../releases/latest` for
exact version tags, each project's own `build.sbt` for cross-build/dependency facts):

- **Decline** (`bkirwi/decline`), latest release `v2.2.0`, cross-built for Scala `2.12.21`/`2.13.18`/`3.3.7`. Its
  core module depends on `cats-core % "2.13.0"` -- confirmed directly from decline's own `build.sbt`
  (`"org.typelevel" %%% "cats-core" % catsVersion`). Applicative-style, composable `Opts[A]` values combined via
  `mapN`.
- **Scallop** (`scallop/scallop`), latest release `v6.0.0`, cross-built for Scala 3.7+/2.13/2.12/2.11/2.10, plus
  Scala.js/Native. Zero external runtime dependencies. Mutable-builder style: a class extending `ScallopConf`
  declares `opt[T]`/`trailArg[T]` vals, calls `verify()`.

Decision: **Scallop**. Nothing else in this codebase depends on cats or any typelevel-effect library -- adding
decline would introduce a genuinely new dependency family to the whole build for a single CLI module, where
Scallop's mutable-conf style is also simply a closer stylistic match to `Tda4j`/`PersistenceResult`, the existing
imperative facade layer this CLI sits directly on top of (flat key/value options, `IllegalArgumentException` for
bad input, no generics across the Java/CLI boundary).

## Design: a thin translator over the existing MATLAB facade, not a new implementation

Per the ask ("use the Matlab layer as a guide"), `Tda4jCli` does not reimplement any option validation or defaults
-- it translates Scallop-parsed flags into exactly the same flat `String[]` options array
`org.appliedtopology.tda4j.matlab.Tda4j.computeFromPoints`/`computeFromDistanceMatrix` already accepts and
validates, then calls that facade directly, then reads the returned `PersistenceResult` through its existing public
accessors. `--complex`, `--engine`, `--alpha-backend`, `--max-dimension`, `--max-filtration-value`, `--field`,
`--prime`, `--epsilon` are 1:1 mirrors of `Tda4j`'s own recognized option keys; unrecognized values or unrecognized
option combinations (e.g. `--complex alpha --engine ripser`) surface `Tda4j`'s own existing, already-tested
`IllegalArgumentException` messages verbatim -- confirmed live (see "Manual verification" below), not just assumed
to flow through.

**Crucial design choice, confirmed correct empirically, not just planned**: every CLI flag is defined WITHOUT a
Scallop-level default, so `ScallopOption[T].toOption` is `None` exactly when the user didn't pass that flag; the
options array `buildOptions` builds omits the key entirely in that case, rather than filling in a CLI-side copy of
`Tda4j`'s own default (`complex` defaulting to `"vr"`, `maxDimension` to `2`, etc.). This means there is exactly
ONE place in the whole codebase that knows what "unset" means for any given `Tda4j` option, and it cannot drift
out of sync with this file the way two independently-maintained default values could. Pinned by `CliSpec`'s first
test (`buildOptions` on a conf with no optional flags must be empty).

Input loading goes through the `io` module built in the prior session (`.claude/WORKLOG-io-module.md`) -- a single
`--input-format` flag maps 1:1 onto one `io.*` loader method and onto exactly one of point-cloud-vs-distance-matrix,
so there's no separate "kind" flag that could disagree with the format choice: `csv-points`/`ripser-points`/`off`
always yield points (`Left`); `csv-distances`/`csv-lower`/`ripser-lower`/`ripser-upper`/`ripser-distance`/
`ripser-binary`/`dipha-distance` always yield a distance matrix (`Right`). Output similarly reuses the `io` module's
existing writers (`Csv`/`Gudhi`/`Dipha`/`Perseus`) rather than inventing a new format.

## A real design bug caught before shipping: Perseus output would have been silently useless

`Perseus.writePersistenceIntervals` (from the `io` module) rounds `Double` birth/death to the nearest integer,
matching Perseus's own step-indexed format -- correct and already documented there. But a typical Vietoris-Rips or
alpha barcode has real-valued birth/death, frequently well under `1.0` (e.g. this session's own unit-square test
fixture: birth/death values `1.0`, `1.414...`). Wiring `--output-format perseus` straight through would have
rounded nearly every bar to `0 0` -- output that parses fine and LOOKS like a legitimate Perseus file but reports
no real information, exactly the "internally consistent but silently wrong" failure shape this codebase has hit
repeatedly elsewhere (`filtrationOrdering`/axis-order bugs, see CLAUDE.md). Flagged by `advisor()` before this was
shipped as an equal-looking output choice alongside `csv`/`gudhi`/`dipha`/`text`.

Fixed by refusing the combination outright: `requireIntegralForPerseus` checks every bar's finite endpoints before
calling into `Perseus.writePersistenceIntervals`, and throws a clear, actionable `IllegalArgumentException`
("Perseus's own format stores filtration STEP INDICES, not raw filtration values...") rather than silently
rounding. Confirmed live against the same unit-square fixture used elsewhere in this worklog: the CLI now refuses
with that exact message instead of writing a `0 0`-collapsed file.

## Testability: `run` returns an exit code, `main` is the only place that calls `sys.exit`

`Tda4jCli.run(args: Seq[String], out: PrintStream): Int` contains the entire CLI (parse, load, compute, write) and
returns `0`/`1` rather than exiting -- `main` is a two-line wrapper (`if run(...) != 0 then sys.exit(...)`). This
is what lets `CliSpec` call `run` in-process and assert on both the returned code and the captured output stream,
without a subprocess.

**A real, checked-not-assumed limitation surfaced by this design, documented directly on `run`'s own doc comment
(not just here)**: this only covers errors `Tda4j` itself raises (`IllegalArgumentException`, after Scallop has
already successfully parsed the command line). A genuine PARSE-level error -- a malformed `--max-dimension`
value, a missing required trailing argument, or `--help`/`--version` themselves -- is handled entirely inside
`new Tda4jConf(args)`'s own `verify()` call, before `run` gets control back at all. Traced directly in Scallop's
own source (`ScallopConfBase.scala`, fetched fresh from `github.com/scallop/scallop`, not recalled): the default
`onError` prints directly and calls `exitHandler` (`sys.exit` by default) unconditionally for `Help`/`Version`/any
`ScallopException` subtype, UNLESS the `org.rogach.scallop.throwError` `DynamicVariable[Boolean]` is set to `true`
around the call, in which case `onError`'s entire match (including the `Help`/`Version` printing branches) is
skipped and the raw exception is re-thrown instead.

Considered and deliberately not used: wrapping `Tda4jConf` construction in `throwError.withValue(true)` would make
`run` fully exception-safe, but `throwError`'s effect is all-or-nothing -- it would ALSO turn `--help`/`--version`
into raw thrown exceptions instead of Scallop's own nicely-formatted help text, which would then need to be
reimplemented by hand to preserve real-user UX. Not worth it for a code path `CliSpec` simply avoids exercising by
construction (every test either passes fully valid flags, or a value Scallop parses successfully but `Tda4j`
itself later rejects, e.g. `--complex bogus`). Confirmed directly, not guessed: `sbt runMain ... --max-dimension
notanumber <file>` and `sbt runMain ... --max-dimension 1` (no input file) each printed a `[scallop] Error: ...`
line and terminated the forked JVM without sbt's usual `[success]`/`[error]` trailer -- i.e. `System.exit` really
does fire, exactly as traced from source.

## Manual verification (per advisor's explicit instruction: build AND run the actual jar, don't just compile)

- `sbt "runMain org.appliedtopology.tda4j.cli.Tda4jCli --help"` -- renders full, correctly auto-generated help
  text (short flags auto-assigned alphabetically, e.g. `-c, --complex`, `-r, --representatives` with no `<arg>`,
  confirming a boolean opt with no explicit `default`-vs-`toggle` distinction still parses as a bare switch).
- A real unit-square point cloud (4 corners, CSV) through `--max-dimension 1`: produces a topologically correct
  barcode by hand-check -- three finite $H_0$ bars closing at edge length $1.0$, one essential $H_0$; two
  zero-persistence $H_1$ bars at the diagonal length $\sqrt2$ (killed immediately by the two triangles, since
  `maxDimension=1` makes `Tda4j` build to dimension 2 internally per its own documented truncation-artifact fix),
  and one genuine finite $H_1$ bar `[1.0, 1.414...)` -- exactly the square's own hole, opening when the four edges
  connect and closing when the diagonals (and their triangles) appear.
- `--representatives` on the same input: prints each bar's actual representative chain (vertex sets + Z/2
  coefficients), reading `PersistenceResult.cycleVertices`/`cycleCoefficients` -- the same accessors the MATLAB
  bridge exposes.
- `--complex bogus`: prints `tda4j: unrecognized complex 'bogus'; expected 'vr' or 'alpha'` to stderr -- `Tda4j`'s
  own message, unmodified, confirming the pass-through design end to end, not merely in the option-array builder.
- `--output-format csv`/`gudhi`/`dipha` to a file: all three write successfully; the DIPHA file's byte count
  (`8+8+8+7*24 = 192` bytes for 7 bars) matches `Dipha.writePersistenceDiagram`'s own documented layout exactly.
- `--output-format perseus`: refused with the message described above, confirmed live, not just read from the
  source.
- `sbt assembly` succeeds with sbt-assembly's own DEFAULT merge strategies (`Rename` for 15 files, `Discard` for
  71 -- no custom `assemblyMergeStrategy` was needed, unlike the "expect a possible module-info.class conflict"
  warning that prompted checking this in the first place). `java -jar target/scala-3.9.0/TDA4j-<version>-assembly.jar
  --help` and a real computation both work standalone, confirming `assembly / mainClass` is wired correctly (a
  setting `Compile / mainClass` alone would NOT have covered -- the two are read by different tasks).

## Testing

New `CliSpec` (6 examples, all passing), scoped per advisor's guidance to test the CLI's OWN logic rather than
re-testing `Tda4j`: `buildOptions` (empty-when-unset; correct-pairs-when-set), `resolveInput`'s
points-vs-distances dispatch, and one genuine end-to-end comparison (a real CSV file on disk, run through
`Tda4jCli.run`, compared line-for-line against calling `Tda4j.computeFromPoints` directly on the same data) --
this is the actual guarantee the CLI exists to provide, not merely "the loader didn't crash." Full `sbt test`:
305 examples (299 prior + 6 new), 0 failures, 0 errors (300 passed, 5 skipped, 1 pending -- unchanged from before
this session). `sbt scalafmtAll`/`scalafmtCheckAll scalafmtSbtCheck` clean.

## What's still open

- No boundary-matrix / representative-cycle export format for any of the external tools (PHAT's own format,
  say) -- `--representatives` only supports `--output-format=text`, printing vertex sets inline; this was already
  a known gap in the MATLAB facade itself (`PersistenceResult`'s own doc), not newly introduced here.
- Sparse distance-matrix input formats (Ripser `--format sparse`, DIPHA's sparse type) are not offered as
  `--input-format` choices, for the same reason they're not in the `io` module yet (see
  `.claude/WORKLOG-io-module.md`'s own "what's still open" section) -- a real design decision about what type
  represents "only some pairs are known," not attempted here.
- `run`'s parse-level-error/`System.exit` limitation, described above -- accepted, not fixed, for the stated
  reason (the `throwError` escape hatch would require reimplementing Scallop's own help/version printing by hand).
- Cubical persistent homology (`CubicalGridStream`/`CubicalHomologyContext`) is not exposed by this CLI at all --
  matching the ask's own scope ("use the Matlab layer as a guide"): `Tda4j`/`PersistenceResult` themselves don't
  expose it either, so there was nothing to mirror. A future session wanting cubical-image persistence from the
  command line would need to either extend `Tda4j` itself first, or give the CLI its own separate code path (and
  its own design discussion about scope).
