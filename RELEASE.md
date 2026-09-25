# Release process

This document describes how to cut a release of TDA4j: publish to Maven Central, attach a fat jar/sources
jar/docs bundle to a GitHub Release, and snapshot the docs site so old versions stay browsable as the site
keeps changing. It also flags what's already wired up in this repo versus what still needs to be built before
the first real release — read the "Current gaps" section before assuming any of this works end-to-end today.

## Current gaps (read this first)

The repo has partial plumbing for all three of these already, but none of it has been exercised for a real
release yet:

1. **`release.sbt` calls `sonaUpload`/`sonaRelease`, but `project/plugins.sbt` does not add the plugin that
   provides them.** Those commands come from `sbt-sonatype` (Central Portal support, added in the 3.11+
   series). Add it before the first release:
   ```scala
   addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "<latest>")
   ```
   Check the current version against the plugin's own releases — pin an exact version, don't leave it
   floating.
2. **`sonatype.sbt` is otherwise in good shape** (`publishMavenStyle`, `scmInfo`, `developers`, `description`,
   `licenses`, `homepage`, `publishTo` pointed at Central's snapshot repo for `-SNAPSHOT` versions and
   `localStaging` otherwise) — this is what `sbt-sonatype`/`sbt-pgp` expect. Nothing here needs to change,
   only the missing plugin needs adding.
3. **`LICENSE.md`'s copyright line has no name**: `Copyright (c) 2017 ` — fix this before the first public
   release; check with the project lead on the correct holder (the org, or specific individuals from
   `CONTRIBUTORS.md`).
4. **No GitHub Actions release workflow exists.** `docs.yml`/`lint.yml`/`test.yml` all run on pushes/PRs to
   `scala`; nothing runs on a version tag. This doc describes both a manual process (works today, once gap 1
   is fixed) and a `release.yml` workflow to add later so the manual steps aren't the only path.
5. **Laika docs versioning is not configured.** `laikaVersions` isn't set anywhere in `build.sbt`, and
   `docs.yml`'s `publishToGitHubPages` step doesn't currently preserve old content when it publishes (worth
   double-checking `gitHubPagesKeepFiles`/`GitHubPagesPlugin`'s default behavior against `sbt-github-pages`
   0.19.0's own docs before relying on it — its default may overwrite `gh-pages` wholesale rather than merge).
   This doc lays out what versioned publishing needs to look like; it isn't live yet.

None of this blocks writing the process down — it blocks *running* it. Treat the checklist below as the
target state, and close these gaps as part of the first release, not as a follow-up.

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
  already in the repo) and pushes it as part of `pushChanges`.

## What gets built

| Artifact | How | Where it goes |
|---|---|---|
| Library jar | `sbt package` (part of `publishSigned`) | Maven Central |
| Sources jar | `publishMavenStyle := true` + sbt's default `publishArtifact` behavior (`Compile / packageSrc`) | Maven Central + GitHub Release |
| Scaladoc jar | sbt's default `Compile / packageDoc` | Maven Central + GitHub Release |
| Fat jar (CLI/MATLAB) | `sbt assembly` → `target/scala-3.9.0/TDA4j-<version>-assembly.jar` (name from `assembly / assemblyJarName` in `build.sbt`) | GitHub Release only (not published to Maven — fat jars with bundled deps are a poor Maven citizen) |
| Docs site | `sbt laikaSite` → `target/docs/site` (includes linked scaladoc via `laikaIncludeAPI`, and a PDF via `laikaIncludePDF`) | GitHub Pages (versioned) + a zipped copy on the GitHub Release |

All four Maven-bound artifacts (jar, sources, scaladoc, POM) are produced and signed in one shot by
`publishSigned` (`sbt-pgp`) — nothing bespoke needed there beyond having a valid PGP key configured (see
[Credentials](#credentials-and-secrets)).

## Release steps

### 1. Pre-flight

- On `scala` (or whatever branch is being released from), working tree clean, `sbt clean test` and
  `sbt mimaReportBinaryIssues` both green — same as CI (`test.yml`), run locally first so the interactive
  `sbt-release` sequence doesn't fail partway through.
- `sbt scalafmtCheck scalafmtSbtCheck` clean (`lint.yml`'s own check).
- Skim `CONTRIBUTORS.md` and the worklogs since the last tag for anything release-notes-worthy — this repo
  has no `CHANGELOG.md` yet; consider whether the first release should add one (`sbt-release`'s
  `releaseNotesFile` support, or just the GitHub Release body — see [GitHub Release](#4-github-release)
  below) rather than expecting the tag alone to explain what changed.
- Confirm `mimaPreviousArtifacts` — it's currently `Set.empty` in `build.sbt`, which is correct only because
  nothing has published to Maven yet and there's nothing to diff against. **The first real Maven publish must
  be followed by setting `mimaPreviousArtifacts` to that version**, or every later release silently stops
  checking binary compatibility (`mimaReportBinaryIssues` passes vacuously against an empty set).

### 2. Credentials and secrets

Needed locally (or as CI secrets, once gap 4 above is closed):

- **PGP signing key** (`sbt-pgp`): either a key already in the local GPG keyring, or credentials for
  `sbt-pgp`'s own key-passing mechanism. `publishSigned` will fail without one.
- **Sonatype Central Portal token** (user token, not the old OSSRH username/password — Central Portal auth is
  token-based): needed by `sonaUpload`/`sonaRelease` once `sbt-sonatype` is added (gap 1). Generate it from
  the Central Portal account settings; store as `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` (or whatever env
  vars the installed `sbt-sonatype` version reads — check its README against the pinned version) rather than
  committing it anywhere.
- **`GITHUB_TOKEN`**: already used by `docs.yml`; a release workflow doing `gh release create` needs the
  same, with `contents: write` permission (the default `GITHUB_TOKEN` covers this for a workflow in the same
  repo).

### 3. Run the release

With `sbt-sonatype` added (gap 1) and credentials in place, `sbt release` runs `release.sbt`'s
`releaseProcess` interactively: it prompts for the release version and next snapshot version, runs
`clean`+`test`, bumps `version.sbt`, commits, tags (`vX.Y.Z`), `publishSigned`s every module (jar, sources,
scaladoc, POM) to Central's staging area, calls `sonaUpload` then `sonaRelease` to push the staged bundle
live, bumps to the next `-SNAPSHOT`, commits, and pushes both commits and the tag.

Until it's automated (gap 4), run this from a clean local checkout on `scala`, not from a CI job, so the
interactive version prompts work normally.

**Do not run `sbt release` twice for the same version** — `sonaRelease` on an already-released coordinate
will fail, and a duplicate tag push is rejected by git. If a step fails partway (e.g. `sonaUpload` rejected
for a validation reason), fix the underlying issue and re-run from the failed step rather than restarting the
whole sequence, per `sbt-release`'s own resume support.

### 4. GitHub Release

Once the tag is pushed (step 3 does this), build the artifacts that don't come from `publishSigned` and
attach them to a GitHub Release on that tag:

```
sbt assembly           # fat jar
sbt laikaSite           # docs site, target/docs/site
```

Then, either manually via the GitHub UI/CLI or (once written) via a `release.yml` workflow triggered on
`push: tags: ['v*']`:

- Create the release from the `vX.Y.Z` tag, title `TDA4j X.Y.Z`.
- Attach:
  - `target/scala-3.9.0/TDA4j-X.Y.Z-assembly.jar` (fat jar)
  - the sources jar and scaladoc jar `publishSigned` already produced (copy from `target/scala-3.9.0/` before
    Sonatype staging is cleaned up, or rebuild with `sbt packageSrc packageDoc`)
  - a zip of `target/docs/site` (`docs-X.Y.Z.zip`) as a static snapshot, in addition to the live versioned
    docs published in step 5
- Release notes: hand-written summary of user-visible changes since the last tag (this repo's worklog
  discipline — `.claude/WORKLOG-*.md` — is the fastest way to reconstruct what happened; don't just paste
  `git log`).
- Mark pre-1.0 releases as "pre-release" in the GitHub UI until `1.0.0`.

### 5. Docs site: versioned publish

Today, `docs.yml` runs `sbt laikaSite publishToGitHubPages` on every push to `scala`, which republishes the
docs at the site root and (per gap 5 above) may not preserve older content. For a released version, the docs
should instead land under a version-specific path (e.g. `/0.1.3/...`) while `/latest/` (or the root) keeps
tracking the newest release, so links into old release's docs (including javadoc/scaladoc cross-references
and the GitHub Release's own docs zip) don't rot as later releases change the API.

Laika (1.3.2, already a dependency here) has first-class support for this via `laika.config.Versions`:

```scala
import laika.config.{Version, Versions}

laikaConfig := LaikaConfig.defaults.withConfigValue(
  Versions
    .forCurrentVersion(Version("0.1.3", "0.1.3")) // (displayValue, pathSegment)
    .withOlderVersions(
      Version("0.1.2", "0.1.2"),
      Version("0.1.1", "0.1.1")
      // ... accumulate one entry per released version
    )
)
```

`Version.apply(displayValue, pathSegment)` takes the label shown in the version switcher and the URL segment
it publishes under; `Versions.forCurrentVersion` marks which one is "this build." This needs to be set (and
`olderVersions` extended) **per release**, driven from the same version list `version.sbt`/git tags already
track — worth writing a small script or sbt task that reads existing tags rather than hand-maintaining the
list, once there are more than a handful of releases.

Suggested target shape for `docs.yml`, to be split out once gap 5 is actually implemented:

- On push to `scala` (unreleased/in-progress docs): keep publishing to a `dev`/`latest-snapshot` path, not
  the release-versioned tree.
- On a `v*` tag push: build with that tag's version as `Versions.forCurrentVersion`, publish under
  `/X.Y.Z/`, and update whatever redirects/aliases `/latest/` to the newest tag.
- Either way, `publishToGitHubPages` (or a lower-level `git worktree`/`gh-pages` push, if `sbt-github-pages`'s
  publish step turns out to always replace the whole site — verify this before relying on it) must **not**
  delete previously-published version directories.

Until this is wired up, treat the GitHub Release's zipped docs snapshot (step 4) as the durable per-version
artifact, and the live GitHub Pages site as tracking `scala` HEAD only.

## Post-release

- Verify the Central Portal listing (can take minutes to hours to become searchable after `sonaRelease`,
  even though the artifact is immediately resolvable by exact coordinates).
- Verify a fresh `libraryDependencies += "org.appliedtopology" %% "TDA4j" % "X.Y.Z"` resolves in a scratch
  project.
- Set `mimaPreviousArtifacts` to the just-released version if this was the first Maven publish (see
  [Pre-flight](#1-pre-flight) above) or confirm it's already tracking the prior release otherwise.
- Skim the new `-SNAPSHOT` commit `sbt release` pushed — confirm it actually landed on `scala` and the version
  bump is sane before walking away.
