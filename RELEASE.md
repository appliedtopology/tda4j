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
5. The docs site is plain scaladoc (the Laika/Paradox generators were removed): `build.sbt`'s
   `Compile / doc / scalacOptions` configure scaladoc's static-site mode (`-siteroot`, with pages in `_docs/` and
   navigation in `sidebar.yml`). `docs.yml` publishes by committing the generated `api` directory into a
   `gh-pages` git worktree, and `release.yml` does the same under a per-version directory, so every
   already-published version is left untouched by construction rather than by an explicit merge-forward step.
   `TDA4J_DOCS_VERSION` (set by `release.yml`) becomes scaladoc's `-project-version`.

**Docs are currently built with Scala 3.8.4 while everything else is 3.9.0** (a scaladoc 3.9.0 JavaScript bug);
the docs steps set `TDA4J_SCALA_VERSION=3.8.4` (read by `scalaVersion` in `build.sbt`); see the TODOs there and in both workflows to remove this when 3.9.1 is released.

**None of this has been exercised against the real Sonatype Central Portal or a real tag push yet** — it's
wired up and locally verified as far as this sandbox allows, not proven end-to-end. The overall
versioned-docs-publish *concept* has a real track record (`docs.yml` has run successfully dozens of times) —
but the git-native publish mechanism described in [Docs site](#5-docs-site-versioned-publish) below,
replacing `sbt-github-pages`, has only been dry-run locally against a scratch repo, not through a real
`docs.yml`/`release.yml` run yet (that workflow only fires on push to `scala`, which a feature branch can't
trigger). Treat its first real run as the actual first test, and treat the first tagged release as the first
real test of the Maven/GitHub-Release half of this pipeline either way — watch every step, don't assume
silence means success. In particular:

- No secrets are configured yet — see [Credentials and secrets](#2-credentials-and-secrets).
- `mimaPreviousArtifacts` is still `Set.empty` (correct today, since nothing has published to Maven) — the
  first release must update it, see [Pre-flight](#1-pre-flight).

## Versioning

- `version.sbt` (`ThisBuild / version := "0.4.1-SNAPSHOT"`) is the single source of truth for the Scala/Maven
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
| Fat jar (CLI/MATLAB) | `sbt assembly` → `target/out/jvm/scala-3.9.0/tda4j/tda4j-<version>-assembly.jar` (sbt 2 layout, verified) (name from `assembly / assemblyJarName` in `build.sbt`) | GitHub Release only (not published to Maven — fat jars with bundled deps are a poor Maven citizen) |
| Docs site | `sbt doc` → `target/out/jvm/scala-<docs scala version>/tda4j/api` (scaladoc static site built from `_docs/`) | GitHub Pages, under a per-version path (`/X.Y.Z/`, `/dev/` for in-progress docs) + a zipped copy on the GitHub Release |

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
- Confirm `mimaPreviousArtifacts`: `build.sbt` derives it from the `v*` git tags (excluding the version being
  published and any `0.1.*` tag). A tag with no published Maven artifact makes `mimaReportBinaryIssues` fail to
  resolve, so check that the most recent tags really were published.

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
  Nothing in this project's own build files references that path — sbt-sonatype/sbt-pgp pick it up through
  their own default credential discovery, the same way for every project on the machine, not something wired
  up here. (An earlier version of `sonatype.sbt` added an explicit `credentials +=` pointing at this file,
  reasoning from `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` env vars as the primary mechanism — removed once it
  became clear that framing didn't match how credentials actually get supplied here, which is entirely this
  file, discovered by the plugins' own defaults; no env vars involved.) `host` must say `central.sonatype.com`
  exactly (a file left over from before the Central Portal migration may still say the old OSSRH host, which
  won't match and leaves the build effectively uncredentialed). This is a different file and format from
  Maven's own `~/.m2/settings.xml` — sbt doesn't read that XML format, so a token stored only there isn't
  picked up here, whatever else might read it.

**GitHub Actions repo secrets** (for `release.yml` — it never touches Maven, so it needs none of the above):

- **`GITHUB_TOKEN`**: automatic, no setup needed — the workflow's `permissions: contents: write` covers both
  `gh release create` and the plain `git push` to `gh-pages` (via `actions/checkout`'s own persisted
  credentials, which cover any git operation against the same repo, not just the checked-out ref).

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
2. Commits the freshly-built docs directly into a `gh-pages` worktree, at their own version path, alongside
   whatever's already there (see [Docs versioning](#5-docs-site-versioned-publish) below), and pushes.
3. Zips this version's docs (`target/docs/site`, the same build step 2 just published) into `docs-X.Y.Z.zip`.
4. Runs `gh release create` on the pushed tag, title `TDA4j X.Y.Z`, `--generate-notes`, `--prerelease` while
   the version is still `0.x`, with the fat jar, sources jar, scaladoc jar, and docs zip attached.

If it fails partway (e.g. a transient GitHub API error), it's safe to just re-run the workflow from the
Actions tab — nothing it does touches Maven, and `gh release create`/the docs `git push` are both safe to
repeat for the same tag (the push is a no-op if the content hasn't changed). If it needs to be done by hand
instead, replicate steps 1–4 locally; the commands are in the workflow file.

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
manifest (whose entries point at URLs like `/0.1.3/...` that something else has to make real). So each
workflow's own "Publish docs"/"Publish versioned docs" step (not `sbt`) does the real work, after `laikaSite`:

1. Check out (or, the first time, create) the `gh-pages` branch into a throwaway git worktree — a real
   checkout of the actual published history, not a scratch copy assembled by hand.
2. Inside that worktree, replace only `<version>/` (`<version>` = `dev` or the release tag) with this build's
   fresh `target/docs/site` output. Every other already-published version, and root files like `.nojekyll`/
   `CNAME`, are untouched by construction — there's no "copy forward what's not already here" step to get
   wrong, because nothing outside `<version>/` is ever removed in the first place.
3. Recompute the root `index.html` redirect: the newest directory whose name looks like a real release
   version (matched by a `[0-9]*.[0-9]*.[0-9]*` glob, not by "isn't dev"), or `dev/` if none has been released
   yet. Matching on the version shape rather than excluding known names is deliberate — a plain exclusion
   list let stale, no-longer-updated directories from before docs versioning existed outrank real versions in
   a `sort -V` comparison and become the redirect target, which is exactly what was found live on the
   published site (root redirecting to a leftover `user-guide/` directory) before this fix.
4. `git add -A`, then commit and push only if anything actually changed.

This replaced an earlier design (and, before that, a plugin — `sbt-github-pages` — that pushed via the GitHub
API, one `createBlob` HTTP call per file with a hardcoded 1-second sleep before each; measured at 21+ minutes
for the ~1000 files a single `dev` build already produces, from a real job log, and only getting slower as
more release versions accumulate: `.claude/WORKLOG-docs-publish-performance.md`). Committing directly into a
real checkout means git only ever hashes and writes objects for what actually changed, and pushes once,
regardless of how large the accumulated site gets. The new steps were dry-run against a scratch git repo in
this sandbox (bootstrap-from-nothing, an existing `dev` update, and a new version landing alongside `dev`),
but not yet through a real `docs.yml`/`release.yml` run — see the warning in [Status](#status) above.

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
