# Release process

This document describes how to cut a release of TDA4j: publish to Maven Central, attach a fat jar/sources
jar/docs bundle to a GitHub Release, and snapshot the docs site so old versions stay browsable as the site
keeps changing.

**Docs are currently built with Scala 3.8.4 while everything else is 3.9.0** (a scaladoc 3.9.0 JavaScript bug);
the docs steps set `TDA4J_SCALA_VERSION=3.8.4` (read by `scalaVersion` in `build.sbt`); see the TODOs there and in both workflows to remove this when 3.9.1 is released.

## Versioning

- `version.sbt` is the single source of truth for the Scala/Maven
  artifact version. `versionScheme := Some("semver-spec")` in `build.sbt` means MiMa and Maven Central
  itself will hold later releases to semver compatibility rules against whatever the last non-SNAPSHOT
  version was — bump `MAJOR` for a binary-incompatible break, `MINOR` for additive-only, `PATCH` for
  bugfix-only. Since the library is pre-1.0, treat `MINOR` bumps as the ones allowed to break API for now,
  same convention most pre-1.0 semver projects use.
- `sbt-release` (`sbt-release` 1.5.0, wired via `release.sbt`) drives the version bump/tag/publish sequence —
  see [Release steps](#release-steps) below. It edits `version.sbt` itself (`setReleaseVersion`/
  `setNextVersion`); don't hand-edit that file as part of a release.
- Tags: `sbt-release`'s `tagRelease` step creates a `v<version>` tag and pushes it as part of `pushChanges`. That push
  is what triggers `release.yml`.

## What gets built

| Artifact | How | Where it goes |
|---|---|---|
| Library jar | `sbt tda4j/package` (part of `publishSigned`): `tda4j_3`, plots included (`org.appliedtopology.tda4j.plot`) | Maven Central |
| Scala.js library | `tda4jJS/package` (part of `publishSigned`): `tda4j_sjs1_3`, the same sources linked by Scala.js users' builds; no MiMa check until a second release of the series exists | Maven Central |
| npm package | `sbt tda4jJS/npmPack` → `target/npm/tda4j-<version>.tgz`: the optimized link as ES modules, `npm/package.json` with the version filled in, `npm/index.d.ts`, `npm/README.md`; the `test.yml` job `npm-package` installs it into an empty project and runs the library and the `tda4j` command | npm (`release.yml`, once `NPM_TOKEN` exists, see "npm" below) + GitHub Release |
| Sources jar | `publishMavenStyle := true` + sbt's default `publishArtifact` behavior (`Compile / packageSrc`) | Maven Central + GitHub Release |
| Scaladoc jar | sbt's default `Compile / packageDoc` | Maven Central + GitHub Release |
| Fat jar (CLI/MATLAB) | `sbt tda4j/assembly` → `target/out/jvm/scala-3.9.0/tda4j/tda4j-<version>-assembly.jar` (name from `assembly / assemblyJarName` in `build.sbt`); CI's `test` job builds it and runs it on a sample for every pull request | GitHub Release only (not published to Maven — fat jars with bundled deps are a poor Maven citizen) |
| Demo REPL jar | `demo/` is a separate sbt build (`demo/README.md`) that bundles a *version* of the library with the Scala REPL: `TDA4J_VERSION=<version> sbt assembly` in `demo/` → `demo/target/out/jvm/scala-3.9.0/tda4j-demo/tda4j-demo-<version>.jar` (about 74 MB). `release.yml` builds it against the tag's own sources and smoke-runs it | GitHub Release only |
| Docs site | `sbt tda4j/doc` → `target/out/jvm/scala-<docs scala version>/tda4j/api` (scaladoc static site built from `_docs/`) | GitHub Pages, under a per-version path (`/X.Y.Z/`, `/dev/` for in-progress docs) + a zipped copy on the GitHub Release |

All four Maven-bound artifacts (jar, sources, scaladoc, POM) are produced and signed in one shot by
`publishSigned` (`sbt-pgp`) — nothing bespoke needed there beyond having a valid PGP key configured (see
[Credentials](#2-credentials-and-secrets)).

## Division of labor: local `sbt release` vs. `release.yml`

Maven Central publishing and the GitHub-side release artifacts are deliberately split across two different
places, not both automated in CI, to avoid a duplicate-publish race:

- **`sbt release`, run locally**, does the part that's dangerous to retry: version bump, test, tag,
  `publishSigned`, `sonaUpload`, `sonaRelease`, next snapshot, push. This needs the Sonatype/PGP credentials
  on the machine running it.
- **`release.yml`, triggered by the tag `sbt release` pushes**, does the part that's safe to retry: build the
  fat jar and sources/javadoc jars fresh, publish the tag's docs snapshot to GitHub Pages under its own
  version path, zip the docs, and create the GitHub Release with everything attached. It does **not** touch
  Maven Central.

If Maven publishing is ever moved into CI instead (e.g. to avoid needing Sonatype credentials on a laptop),
remove `publishSigned`/`sonaUpload`/`sonaRelease` from `release.sbt`'s `releaseProcess` first — running both
the local sequence and a CI sequence against the same tag will make the second one fail against an
already-released coordinate.

## Release steps

### 1. Pre-flight

- On `scala` (or whatever branch is being released from), working tree clean, `sbt "clean ; test"` (both the JVM and
  the Scala.js specs; the latter needs Node.js 20.16 or newer), `sbt tda4j/mimaReportBinaryIssues` and `sbt
  tda4jJS/npmPack` all green — same as CI (`test.yml`), run locally first so the interactive `sbt-release` sequence
  doesn't fail partway through.
- `sbt scalafmtCheck scalafmtSbtCheck` clean (`lint.yml`'s own check).
- **The changelog.** In `CHANGELOG.md`, rename the `Unreleased` section to `X.Y.Z (YYYY-MM-DD)`, read it over
  (breaking changes first, each with what to write instead), and commit that before `sbt release`. It is the release
  notes: `release.yml`'s `gh release create --generate-notes` lists only the merged pull requests, so paste the
  section into the GitHub Release's description once the workflow has created it.
- **Open issues.** Skim the [issues](https://github.com/appliedtopology/tda4j/issues) for anything that should block
  the release (a `bug` on a default path, an `unverified` claim the release notes would repeat).
- Two artifacts go to Central under the `org.appliedtopology` namespace: `tda4j_3` (JVM) and `tda4j_sjs1_3` (Scala.js),
  from the one `publishSigned` (POM metadata from `sonatype.sbt`; `sbt publishLocal` shows the POMs without publishing
  anything). MiMa checks the JVM artifact only: a Scala.js jar carries no class files for it to read.
- Confirm `mimaPreviousArtifacts` (`sbt show mimaPreviousArtifacts`): `build.sbt` derives it from the `v*` git tags of
  the version's own compatibility series that are older than it. A tag with no published Maven artifact makes
  `mimaReportBinaryIssues` fail to resolve, so check that those tags really were published. A deliberate break inside a
  series needs a commented `mimaBinaryIssueFilters` entry.

### 2. Credentials and secrets

**Local machine** (for `sbt release`):

- **PGP signing key**: a key in the local GPG keyring (or one `sbt-pgp` can otherwise reach). `publishSigned`
  fails without one. `sonatype.sbt` has no `pgpPassphrase` setting of its own — signing goes through
  `sbt-pgp`'s own default resolution (the local gpg-agent's cached passphrase, or an interactive prompt if
  none is cached), not anything project-specific.
- **Sonatype Central Portal user token** (not the old OSSRH username/password — Central Portal auth is
  token-based). Generate it from the Central Portal account settings and put it in a credentials file at
  `~/.sbt/sonatype_credentials` (sbt's native four-line format):
  ```
  realm=Sonatype Central
  host=central.sonatype.com
  user=<token user>
  password=<token password>
  ```
  Nothing in this project's own build files references that path — sbt's built-in Central support and sbt-pgp pick it up through
  their own default credential discovery, the same way for every project on the machine, not something wired
  up here; no environment variables are involved. `host` must say `central.sonatype.com`
  exactly (a file left over from before the Central Portal migration may still say the old OSSRH host, which
  won't match and leaves the build effectively uncredentialed). This is a different file and format from
  Maven's own `~/.m2/settings.xml` — sbt doesn't read that XML format, so a token stored only there isn't
  picked up here, whatever else might read it.

**GitHub Actions repo secrets** (for `release.yml` — it never touches Maven, so it needs none of the above):

- **`GITHUB_TOKEN`**: automatic, no setup needed — the workflow's `permissions: contents: write` covers both
  `gh release create` and the plain `git push` to `gh-pages` (via `actions/checkout`'s own persisted
  credentials, which cover any git operation against the same repo, not just the checked-out ref).

- **`NPM_TOKEN`** (optional until the first npm release): an [npm](https://www.npmjs.com/) access token that may publish
  to the `@appliedtopology` scope; the package is `@appliedtopology/tda4j` (`name` in `npm/package.json`). The scope
  exists (the project lead created it). Once: as a member of the scope with publish rights, with two-factor
  authentication on, create a granular access token with read-and-write permission for packages in the
  `@appliedtopology` scope (the first publish creates the package, so the token cannot yet be limited to it; narrow it
  to `@appliedtopology/tda4j` after that), and add it as the repository secret `NPM_TOKEN`. A scoped package is private
  unless published with `--access public`, which `release.yml` passes. Without the secret, `release.yml` builds the npm
  tarball, attaches it to the GitHub Release and skips the publish step; `npm publish --access public` of that tarball
  by hand does the same.

Nothing else is required in CI today. If Maven publishing is later moved into CI (see the note above), add
`SONATYPE_USERNAME`/`SONATYPE_PASSWORD`/`PGP_PASSPHRASE` (plus the PGP private key itself, e.g. base64 in a
`PGP_SECRET` secret, imported with `gpg --import` before `publishSigned` runs) as repo secrets at that point.

### 3. Run the release

With credentials exported locally, `sbt release` runs `release.sbt`'s `releaseProcess` interactively: it
prompts for the release version and next snapshot version, runs `clean`+`test`, bumps `version.sbt`, commits,
tags (`vX.Y.Z`), `publishSigned`s every module (jar, sources, scaladoc, POM) to Central's staging area, calls
`sonaUpload` then `sonaRelease` to push the staged bundle live, bumps to the next `-SNAPSHOT`, commits, and
pushes both commits and the tag.

Run this from a clean local checkout on `scala`, not from a CI job, so the interactive version prompts work
normally — `release.yml` picks up automatically once the tag lands.

**Do not run `sbt release` twice for the same version** — `sonaRelease` on an already-released coordinate
will fail, and a duplicate tag push is rejected by git. If a step fails partway (e.g. `sonaUpload` rejected
for a validation reason), fix the underlying issue and re-run from the failed step rather than restarting the
whole sequence, per `sbt-release`'s own resume support.

### 4. GitHub Release (automated)

The tag push from step 3 triggers `.github/workflows/release.yml`, which:

1. Checks that the ref is an existing `vX.Y.Z` tag, before anything is built or published.
2. Runs `sbt "set ThisBuild / version := \"X.Y.Z\" ; tda4j/assembly ; tda4j/publishLocal ; tda4j/packageSrc ;
   tda4jJS/npmPack ; ++3.8.4! ; tda4j/packageDoc ; tda4j/doc"`: the npm package, the fat jar, the library published to `~/.ivy2/local` (for the demo jar, below) and the sources jar
   with Scala 3.9.0, then the scaladoc jar and the docs site with 3.8.4 (scaladoc 3.9.0's JavaScript is broken), switched
   inside the one invocation because a second `sbt` call would join the running server and keep 3.9.0. The leading `set`
   pins the build's version to the tag's: it changes nothing after `sbt release` (the tag's `version.sbt` already says
   `X.Y.Z`), and it is what lets a trial tag made by hand on a `-SNAPSHOT` commit (say `v0.7.0-preview`) publish `tda4j_3`,
   name the fat jar and pack the npm package under the tag's version, the one the demo build and the release globs ask for.
   Builds the demo REPL jar (`sbt assembly` in `demo/`, with `TDA4J_VERSION` the tag's version) against that local
   publish, so it does not wait for Maven Central to serve the version `sbt release` has just published, and runs it on
   a four-point square, checking for the five bars.
3. Commits the freshly-built docs directly into a `gh-pages` worktree, under `/X.Y.Z/`, alongside whatever's already
   there (the site root is `docs.yml`'s build of `scala`), and pushes.
4. Zips this version's docs (`target/out/jvm/scala-3.8.4/tda4j/api`, the site step 3 just published) into
   `docs-X.Y.Z.zip`.
5. Runs `gh release create` on the pushed tag, title `TDA4j X.Y.Z`, `--generate-notes`, `--prerelease` while
   the version is still `0.x`, with the fat jar, sources jar, scaladoc jar, npm tarball, demo REPL jar, and docs zip
   attached.
6. Publishes the npm tarball with `npm publish --access public` when the `NPM_TOKEN` secret exists (a version with a
   suffix, such as `0.8.0-RC1`, under the tag `next`, so `npm install @appliedtopology/tda4j` keeps giving the last full release).

If it fails partway (e.g. a transient GitHub API error), it's safe to just re-run the workflow from the
Actions tab — nothing it does touches Maven, and `gh release create`/the docs `git push` are both safe to
repeat for the same tag (the push is a no-op if the content hasn't changed). If it needs to be done by hand
instead, replicate steps 1–4 locally; the commands are in the workflow file.

## Post-release

- Verify the Central Portal listing (can take minutes to hours to become searchable after `sonaRelease`,
  even though the artifact is immediately resolvable by exact coordinates).
- Verify a fresh `libraryDependencies += "org.appliedtopology" %% "tda4j" % "X.Y.Z"` resolves in a scratch
  project.
- Confirm `show mimaPreviousArtifacts` on the new `-SNAPSHOT` now includes the just-released version (same
  series only; a new minor starts with an empty baseline).
- Skim the new `-SNAPSHOT` commit `sbt release` pushed — confirm it actually landed on `scala` and the version
  bump is sane before walking away.
- Verify `npm install @appliedtopology/tda4j@X.Y.Z` in a scratch directory and `import { persistence } from
  "@appliedtopology/tda4j"` (skip until the package has been published once).
- Confirm `release.yml` finished green in the Actions tab and the GitHub Release looks right (all six files
  attached, the changelog section pasted into its description) before announcing the release anywhere.
- Add a new, empty `## Unreleased` section at the top of `CHANGELOG.md` if the release commit did not.
