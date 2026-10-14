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

## Dependencies and plugins (checked on Maven Central, 2026-10-14)

| artifact | in the build | latest | action |
|---|---|---|---|
| specs2 (3 artifacts) | 5.5.1 | 5.9.1 | bumped (above) |
| sbt-scalafmt | 2.6.1 | 2.6.2 | bumped; the formatter itself is pinned in `.scalafmt.conf` (3.11.1), so no reformatting; the three lint checks pass |
| sbt-assembly | 2.3.1 | 2.5.0 | bumped; fat jar built and smoke-run exactly as CI does (`--help`, noisy circle: one degree-1 bar) |
| sbt-pgp | 2.3.1 | 2.3.2 | NOT bumped: it signs the release, and nothing here can exercise signing (no credentials). Bump after the release |
| sbt-release | 1.5.0 | 1.6.0-RC1 | NOT bumped: a release candidate, and it drives the release itself |
| sbt-mima-plugin, sbt-scoverage | 1.2.1, 2.4.4 | same | current |
| cats-core/kernel, kittens, scallop, scala-parallel-collections, commons-numbers, commons-math3, jvptree, miniball | | same as the build | current (parallel-collections is `1.2.0` in the build and on Central) |
| scalacheck | 1.20.0 | not determined | the metadata lookup returned nothing (rate limit?); Dependabot will say |
| Scala | 3.9.0 (LTS) | 3.10.0 | stay: Dependabot is set to patch releases of 3.9.x, and the docs workaround waits for 3.9.1, which does not exist yet (Central lists only 3.9.0) |
| sbt | 2.0.10 | not checked | |

No library other than specs2 was behind, so nothing else changes in the runtime classpath.

## 0.5.1 or 0.6.0? Recommendation: 0.6.0

Evidence, all in the repo:

1. **The project's own scheme says so.** `build.sbt` defines the compatibility series as `0.Y`, and checks every
   `0.5.x` build against `0.5.0` (`mimaBaselineVersions`); `RELEASE.md`: PATCH is for bug fixes only, and pre-1.0 the
   MINOR bump is the one allowed to break. The `Unreleased` section breaks in **seven** places (`CHANGELOG.md`
   "Breaking"): `persistentGroupHomology`'s return type (source AND binary), `Chain` becoming an abstract class,
   `nanMask` as a new last parameter of seven entry points, `Chain.chainShow`'s bounds (these three binary only),
   `Chain` equality, the meaning of a `NaN` pixel and of Perseus's `-1`, and `Chain.reduceBy` throwing. Four of them
   needed `mimaBinaryIssueFilters` entries whose comment reads "Deliberate breaks inside the series". Shipping those
   as 0.5.1 would spend that exception on a release whose number promises none.
2. **It is more than fixes.** New public API: `plot`, saved results, `PackedCubicalCohomologyEngine`, `PackedChain`,
   the whole `sset` category/nerve layer, `Field.toDouble`; construction classes made public. That is a MINOR at least.
3. **Behaviour changes a 0.5.0 user can see**: a `NaN` image pixel is now a mask where it used to be an error; Perseus
   files with `-1` read differently; `Chain` values that compared unequal now compare equal.
4. The cost of 0.6.0 is small: two minor series within a week (0.5.0 is dated 2026-10-09; pre-1.0, so harmless, and
   the 0.5.0 GitHub release is itself marked pre-release); the MiMa filters become dead entries (delete them after the
   release; the 0.6 series starts with an empty baseline, which is what the code in `build.sbt` already does).

How: `version.sbt` says `0.5.1-SNAPSHOT` and `sbt release` PROPOSES `0.5.1` in `inquireVersions`; type `0.6.0` when it
asks (next version `0.6.1-SNAPSHOT`), or `sbt "release release-version 0.6.0 next-version 0.6.1-SNAPSHOT"`. Rename the
`CHANGELOG.md` `Unreleased` heading to `0.6.0 (2026-10-xx)` first (RELEASE.md pre-flight). Nothing in the repo hard-codes
`0.5.1` (checked `grep -rn "0\.5\.1"` over `*.md`, `*.sbt`, `*.yml`), and the docs Playground link follows the tag.
This session changed neither `version.sbt` nor the `Unreleased` heading: both belong to the release step.
