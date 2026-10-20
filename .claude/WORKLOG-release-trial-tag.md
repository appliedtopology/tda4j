# Trial run of release.yml on a hand-made tag (2026-10-20)

Ask from the project lead: get through `release.yml` with a tag that is not on Maven Central (`v0.7.0-preview`), to check
the npm parts. The demo-jar step failed:

```
Error downloading org.appliedtopology:tda4j_3:0.7.0-preview
  not found: /home/runner/.ivy2/local/org.appliedtopology/tda4j_3/0.7.0-preview/ivys/ivy.xml
  not found: https://repo1.maven.org/maven2/org/appliedtopology/tda4j_3/0.7.0-preview/tda4j_3-0.7.0-preview.pom
```

## Cause

- `release.yml` takes the release version from the TAG NAME (`VERSION="${TAG#v}"`) and hands it to the demo build as
  `TDA4J_VERSION`. The root build's version comes from `version.sbt`. `WORKLOG-demo-release.md` assumed the two agree
  ("the tag is the commit that set the release version"); that holds for a tag `sbt release` made, not for a tag made by
  hand on a `-SNAPSHOT` commit.
- Reproduced here (sbt 2.0.10, JVM only): `sbt tda4j/publishLocal` on the branch writes
  `~/.ivy2/local/org.appliedtopology/tda4j_3/0.7.0-SNAPSHOT/{ivys,poms,jars,srcs,docs}` (Ivy layout, `~/.ivy2/local`, as the
  workflow comment says). `TDA4J_VERSION=0.7.0-preview sbt update` in `demo/` then prints the same four lines as above with
  `/root` for `/home/runner`. With the `0.7.0-preview` directory present, the same `update` succeeds.

## Same cause, later in the workflow

With a snapshot-versioned build the later steps would have failed or misbehaved too:

- `gh release create` globs `*-${VERSION}-assembly.jar`, but `assemblyJarName` is `name-version.value-assembly.jar`, so
  it names the snapshot and the glob matches nothing.
- `tda4jJS/npmPack` fills `@VERSION@` in `npm/package.json` from `version.value`: the tarball would be
  `0.7.0-SNAPSHOT`, and `npm publish` of it (dist-tag `next`, a `-` in the version) would publish the snapshot.

## Fix

The first command of the root sbt chain is `set ThisBuild / version := "$VERSION"`. Checked here: after
`set ThisBuild / version := "0.7.0-preview"`, `++3.8.4!` keeps it (`show tda4j/version` gives `0.7.0-preview` with Scala
3.8.4, `assemblyJarName` gives `tda4j-0.7.0-preview-assembly.jar`), and `publishLocal` writes the `0.7.0-preview` directory.
On a real release `version.sbt` already says the tag's version, so the `set` changes nothing.

## Also fixed in the same change

- The `Publish to npm` step ran `npm ci` in a checkout that has no root `package.json` or lockfile (`npm/package.json` is
  only the template `npmPack` fills in). `npm ci` there exits with EUSAGE ("can only install with an existing
  package-lock.json"), after the GitHub Release was already created and before the publish. Removed; `npm publish` of the
  tarball needs no install.
- The comment in `release.yml` and `RELEASE.md` step 2 described a `set tda4j / Compile / packageDoc / publishArtifact :=
  false` that `3695ce9` dropped from the command. The text now says what the command does (the local publish carries a
  3.9.0 scaladoc jar that nothing reads).

## Not verified

- Scala.js side (`tda4jJS/npmPack`, `npm publish`): the cloud session cannot resolve `scalajs-linker` (HTTP 429), so the
  tarball name and the `npm publish` step were not run. Whether npm's Trusted Publisher accepts the run for a package
  that does not exist yet (first publish) is a question for the run itself.
- The full workflow was not run; the sbt chain was run piecewise (the `set`, `publishLocal`, `++3.8.4!`, the demo's `update`).
- A `-preview` tag publishes `@appliedtopology/tda4j@0.7.0-preview` to npm for real (dist-tag `next`), and a published
  npm version cannot be reused.
