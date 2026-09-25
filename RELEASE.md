# Release process

This document describes how to cut a release of TDA4j: publish to Maven Central, attach a fat jar/sources
jar/docs bundle to a GitHub Release, and snapshot the docs site so old versions stay browsable as the site
keeps changing.

## Status

All five gaps from the first pass at this document are closed:

1. `project/plugins.sbt` now adds `sbt-sonatype` 3.12.2, which provides the `sonaUpload`/`sonaRelease`
   commands `release.sbt` already called.
2. `sonatype.sbt` now sets `sonatypeCredentialHost := "central.sonatype.com"` (Central Portal, not the legacy
   OSSRH host) plus a `credentials` entry and `pgpPassphrase`, both read from environment variables — nothing
   is hardcoded.
3. `LICENSE.md`'s copyright line now names its holders (matching `build.sbt`'s own site footer).
4. `.github/workflows/release.yml` now runs on every `vX.Y.Z` tag push.
5. `build.sbt` now configures Laika's `Versions` support (`laikaConfig`), and both `docs.yml` and
   `release.yml` merge forward previously-published doc versions before publishing so `sbt-github-pages`
   (which has no "keep remote-only files" option) doesn't delete them.

`sbt compile` and `sbt laikaSite` were both run successfully against these changes (after retrying through this
sandbox's Maven Central rate limiting), confirming `build.sbt`/`sonatype.sbt`/`release.sbt` all load and the
Laika `Versions`/`VersionMenu` API calls type-check and execute — that includes discovering, the hard way, that
`laikaConfig`'s `Versions` value does **not** physically nest a build's output under its own version path
(confirmed by inspecting real `laikaSite` output: `index.html` etc. land at the site root regardless; only
`laika/versionInfo.json` and the version-switcher dropdown reflect it). `docs.yml`/`release.yml` do that nesting
themselves in a "Stage versioned docs for publish" step, which was dry-run locally (see each workflow's own
comments for why).

**None of this has been exercised against the real Sonatype Central Portal, a real tag push, or a real
`publishToGitHubPages` run yet** — it's wired up and locally verified as far as this sandbox allows, not proven
end-to-end. Treat the first release as the first real test of this whole pipeline, not a routine run: watch
every step, don't assume silence means success. In particular:

- No secrets are configured yet — see [Credentials and secrets](#2-credentials-and-secrets).
- `mimaPreviousArtifacts` is still `Set.empty` (correct today, since nothing has published to Maven) — the
  first release must update it, see [Pre-flight](#1-pre-flight).
- The "Stage versioned docs for publish" steps were only exercised against a synthetic fixture directory in
  this sandbox, not against a real `gh-pages` branch or a second real version to merge alongside — run the
  first docs publish somewhere it's easy to inspect (or revert) before trusting it unattended.

## Versioning

- `version.sbt` (`ThisBuild / version := "0.1.3-SNAPSHOT"`) is the single source of truth for the Scala/Maven
  artifact version. `versionScheme := Some("semver-spec")` in `build.sbt` means MiMa and Maven Central
  itself will hold later releases to semver compatibility rules against whatever the last non-SNAPSHOT
  version was — bump `MAJOR` for a binary-incompatible break, `MINOR` for additive-only, `PATCH` for
  bugfix-only. Since the library is pre-1.0, treat `MINOR` bumps as the ones allowed to break API for now,
  same convention most pre-1.0 semver projects use.
- `sbt-release` (`sbt-release` 1.5.0, wired via `release.sbt`) drives the version bump/tag/publish sequence —
  see [Release steps](#release-steps) below. It edits `version.sbt` itself (`setReleaseVersion`/
  `setNextVersion`); don't hand-edit that file as part of a release.
- Tags: `sbt-release`'s `tagRelease` step creates a `v<version>` tag (matches the existing `v0.1.0-alpha` tag
  already in the repo) and pushes it as part of `pushChanges`. That push is what triggers `release.yml`.

## What gets built

| Artifact | How | Where it goes |
|---|---|---|
| Library jar | `sbt package` (part of `publishSigned`) | Maven Central |
| Sources jar | `publishMavenStyle := true` + sbt's default `publishArtifact` behavior (`Compile / packageSrc`) | Maven Central + GitHub Release |
| Scaladoc jar | sbt's default `Compile / packageDoc` | Maven Central + GitHub Release |
| Fat jar (CLI/MATLAB) | `sbt assembly` → `target/scala-3.9.0/TDA4j-<version>-assembly.jar` (name from `assembly / assemblyJarName` in `build.sbt`) | GitHub Release only (not published to Maven — fat jars with bundled deps are a poor Maven citizen) |
| Docs site | `sbt laikaSite` → `target/docs/site` (includes linked scaladoc via `laikaIncludeAPI`, and a PDF via `laikaIncludePDF`) | GitHub Pages, under a per-version path (`/X.Y.Z/`, `/dev/` for in-progress docs) + a zipped copy on the GitHub Release |

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

- On `scala` (or whatever branch is being released from), working tree clean, `sbt clean test` and
  `sbt mimaReportBinaryIssues` both green — same as CI (`test.yml`), run locally first so the interactive
  `sbt-release` sequence doesn't fail partway through.
- `sbt scalafmtCheck scalafmtSbtCheck` clean (`lint.yml`'s own check).
- Skim `CONTRIBUTORS.md` and the worklogs since the last tag for anything release-notes-worthy.
  `release.yml`'s `gh release create --generate-notes` will produce a commit-based changelog automatically,
  but it's worth reading over and editing by hand for anything a commit-log summary won't convey (this repo's
  worklog discipline — `.claude/WORKLOG-*.md` — is the fastest way to reconstruct what actually happened).
- Confirm `mimaPreviousArtifacts` — it's currently `Set.empty` in `build.sbt`, which is correct only because
  nothing has published to Maven yet and there's nothing to diff against. **The first real Maven publish must
  be followed by setting `mimaPreviousArtifacts` to that version**, or every later release silently stops
  checking binary compatibility (`mimaReportBinaryIssues` passes vacuously against an empty set).

### 2. Credentials and secrets

**Local machine** (for `sbt release`):

- **PGP signing key**: a key in the local GPG keyring (or one `sbt-pgp` can otherwise reach). `publishSigned`
  fails without one. `sonatype.sbt`'s `pgpPassphrase` reads `PGP_PASSPHRASE` from the environment if set,
  falling back to `sbt-pgp`'s own interactive/gpg-agent prompt otherwise — fine for a local run, set the env
  var if the key needs a passphrase and prompting is inconvenient.
- **Sonatype Central Portal user token** (not the old OSSRH username/password — Central Portal auth is
  token-based). Generate it from the Central Portal account settings and export as `SONATYPE_USERNAME`/
  `SONATYPE_PASSWORD` before running `sbt release` — `sonatype.sbt` reads exactly those two env vars.

**GitHub Actions repo secrets** (for `release.yml` — it never touches Maven, so it needs none of the above):

- **`GITHUB_TOKEN`**: automatic, no setup needed — the workflow's `permissions: contents: write` covers both
  `gh release create` and the `publishToGitHubPages` push.

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

1. Builds `sbt assembly packageSrc packageDoc laikaSite` with `TDA4J_DOCS_VERSION` set to the tag's version.
2. Merges the freshly-built docs into whatever's already on `gh-pages` (see
   [Docs versioning](#5-docs-site-versioned-publish) below) and publishes.
3. Zips this version's staged docs (`target/docs/publish/X.Y.Z`, see step 5) into `docs-X.Y.Z.zip`.
4. Runs `gh release create` on the pushed tag, title `TDA4j X.Y.Z`, `--generate-notes`, `--prerelease` while
   the version is still `0.x`, with the fat jar, sources jar, scaladoc jar, and docs zip attached.

If it fails partway (e.g. a transient GitHub API error), it's safe to just re-run the workflow from the
Actions tab — nothing it does touches Maven, and `gh release create`/`publishToGitHubPages` are both safe to
repeat for the same tag. If it needs to be done by hand instead, replicate steps 1–4 locally; the commands are
in the workflow file.

### 5. Docs site: versioned publish

`build.sbt` configures Laika's own versioning support (`laika.config.Versions`) via a `laikaConfig` setting,
plus a `VersionMenu` in the Helium top nav to actually render the switcher and emit `laika/versionInfo.json`
(without a `VersionMenu` in the theme, the `Versions` config is inert — confirmed: adding it is what made
`laika/versionInfo.json` start appearing in the output). The current build's version comes from the
`TDA4J_DOCS_VERSION` env var (`release.yml` sets it to the tag's version; `docs.yml`'s push-to-`scala` builds
leave it unset, which defaults to `"dev"` so in-progress docs don't collide with a real release's own path),
and the list of older versions is read directly from `git tag --list 'v*'` rather than hand-maintained.

**Laika does not physically nest a build's own output under its version path** — confirmed by inspecting a
real `sbt laikaSite` run: `index.html` and friends land at `target/docs/site`'s root regardless of the
configured version. The `Versions` config only drives the switcher dropdown and the `versionInfo.json`
manifest (whose entries point at URLs like `/0.1.3/...` that something else has to make real). So
`gitHubPagesSiteDir` is set to a separate `target/docs/publish` directory that `laikaSite` itself never
touches, and each workflow's own "Stage versioned docs for publish" step (not `sbt`) does the real work,
after `laikaSite` and before `publishToGitHubPages`:

1. Copy `target/docs/site` into `target/docs/publish/<version>/` (`<version>` = `dev` or the release tag).
2. Fetch the `gh-pages` branch into a throwaway worktree and copy forward any other top-level version
   directory already published there that this build doesn't already have — `sbt-github-pages` has no option
   to preserve remote-only content, so skipping this would delete every other version's docs on next publish.
3. Write a root `index.html` that redirects to the newest released version's directory (or to `dev/` if none
   has been released yet).

This means every publish — dev or release — re-uploads the entire accumulated site, not just what changed;
that's expected and is what keeps old versions from disappearing. Steps 1–3 were dry-run against a synthetic
fixture directory in this sandbox (real `laikaSite` output plus a fake second version) and produced the
expected `dev/`, `<fake-version>/`, and a correctly-targeted redirect `index.html` — but not against a real
`gh-pages` branch or a real second release, so treat the first real docs publish as the actual first test.

## Post-release

- Verify the Central Portal listing (can take minutes to hours to become searchable after `sonaRelease`,
  even though the artifact is immediately resolvable by exact coordinates).
- Verify a fresh `libraryDependencies += "org.appliedtopology" %% "TDA4j" % "X.Y.Z"` resolves in a scratch
  project.
- Set `mimaPreviousArtifacts` to the just-released version if this was the first Maven publish (see
  [Pre-flight](#1-pre-flight) above) or confirm it's already tracking the prior release otherwise.
- Skim the new `-SNAPSHOT` commit `sbt release` pushed — confirm it actually landed on `scala` and the version
  bump is sane before walking away.
- Confirm `release.yml` finished green in the Actions tab and the GitHub Release looks right (all four files
  attached, notes readable) before announcing the release anywhere.
