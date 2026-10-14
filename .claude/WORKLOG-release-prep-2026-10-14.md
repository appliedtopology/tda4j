# Release prep, 2026-10-14 (branch `claude/release-prep-bugs-rkjpln`)

One worklog for the whole arc, a section per task, in the order they were done. The session was told mid-way that
the usage limit would stop it before the release, so the short, release-critical items went first and #51 / #82 are
notes only (see the end).

## specs2 5.5.1 -> 5.9.1 and #79

- `build.sbt`: the three specs2 artifacts are 5.9.1 (latest on Maven Central today).
- 5.9.1 types `===`. Four lines stopped compiling, and every one was a comparison that 5.5.1's untyped `===` let
  through: `FiniteFieldSpec` "units" (#79: `x - x` on `Int`, never an `Fp`, so the property could not fail) and three
  `Int === Long` size checks (`VietorisRipsSpec` x3, `SimplexStreamSpec` x1; fixed with `.toLong`, the values were
  always equal).
- #79: the two properties now wrap `x` in `Fp` and compare through the field (`eql`), because an `Fp` has several `Int`
  forms.
- Checked: `Test / compile` clean; `FiniteFieldSpec`, `VietorisRipsSpec`, `SimplexStreamSpec` pass (45 examples).
- 57 new `implicitConversions` feature warnings from specs2 5.9.1 matchers (`matcherIsValueCheck`, `typedValueCheck`),
  in test code only. Left alone (CLAUDE.md: the warning count is deliberately not chased); they are warnings, not
  errors.

## #80: `Plot.number` decided by the runtime class

- Fix: a `Field.toDouble(x)` member, concrete (so a user's own `Field` compiles unchanged and stays binary compatible):
  the old search for `k` with `k * 1 == x`, `|k| <= 64`, `NaN` if none. `Field.DoubleApproximated` overrides it with the
  value itself. `Plot.number` is `f.toDouble(x)`; the `asMatchable` import is gone.
- Why a member and not "is `f` the Double instance": a user's real-valued field (`Double`-backed or not) then plots
  by its own definition, and `Z/p` over `Double` carriers no longer hits the raw-carrier branch.
- Discriminating test (`PlotSpec`): Z/5 with `Double` carriers. The old `case d: Double` returns `4.0`; the field's view
  is `-1`. It reproduces on the JVM the failure the issue measured on Scala.js, so the fix needs no JS build to test.
- Checked: `PlotSpec`, `FiniteFieldSpec` pass. NOT checked: an actual Scala.js run (no JS build in this repo).

## #60: the Playground link loaded tda4j 0.4.0

- `build.sbt` generates the Scastie link and the snippet configuration (`scastieLink`, `scastieSettings`) from the last
  plain `v*` release tag (`releaseTags`, the function the MiMa baseline uses), so a release needs no edit. The example
  is the 0.5 verb (`Persistence(points)`), with `import scala.language.experimental.modularity`. `ScastieFallback`
  (`0.5.0`) applies only without tags; `docs.yml` now checks out with `fetch-depth: 0` (`release.yml` already did).
- Verified: decoded the generated link from `show Compile / doc / scalacOptions`; compiled and ran its code and sbt
  settings (Scala 3.9.0, the same `scalacOptions`, `tda4j % 0.5.0` from Maven Central) in a scratch project: prints the
  four H0 bars (one essential) and the H1 bar `(1, 1.0, 1.4142...)` of a unit square.
- NOT verified: opening the link in Scastie itself (no browser session to scastie.scala-lang.org from here). The
  issue's "tried by hand" is therefore 30 seconds for the project lead after the release: click Playground on the docs
  page. Note the link names the last RELEASE: right after tagging 0.6.0 (or 0.5.1) it points at that version, which
  Scastie can resolve only once Maven Central has synced it (minutes to an hour).
- Maven Central answered HTTP 429 twice during the probe (the cold-cache rate limit CLAUDE.md warns about); a retry
  after the `Retry-After` window worked.
