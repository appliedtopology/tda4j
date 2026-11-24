# The release pre-flight failed on a third copy of the sources (2026-11-24)

Point-in-time snapshot. The rule that came out of it is in `rules/scalajs.md`.

## Symptom

Mikael, running the release on a local `mikael` branch (`sbt "clean ; test"` from RELEASE.md's pre-flight, then
`sbt release`): a pile of "Not Found Error" and complaints about type classes, "as if the compiler forgot the modularity
extensions".

## Cause

`lazy val root = (project in file("."))` aggregates the matrix rows and sets `publish / skip`, nothing else. Its base
directory is the checkout, so sbt gave it the default `src/main/scala` and `src/test/scala`, the same files the matrix
rows compile. Compiled as the root's own (output `target/out/jvm/scala-3.9.0/tda4j-root/classes`) they have no
`libraryDependencies` and none of `commonSettings`' compiler flags:

- `Not found: cats - did you mean caps?`, `value rogach is not a member of org` (no cats, scallop): the "Not Found Error"s;
- `Illegal context bound: ... Field does not take type parameters` (no `-language:experimental.modularity`): the type
  class errors;
- `Not found: type CubicalImagePlatform` (that type is in `src/main/scalajvm` / `scalajs`, which only the rows add).

CI never saw it because every CI command names a project (`tda4j/test`, `tda4jJS/test`). The release does not:
`runTest` in `release.sbt` is the root's `test`, and `sbt "clean ; test"` is the pre-flight in RELEASE.md. Both ran the root's
compile first and stopped there. In `sbt release` that is before `setReleaseVersion`, so a run that failed at the test step
had committed, tagged and published nothing (`git tag` and `git log` on the branch tell).

It was known and not fixed: `WORKLOG-scalajs-numerics-spatial.md` ("Build observations") records that `sbt testFull` at the
root compiles the sources a second time into `tda4j-root` and fails on `cats`, and `WORKLOG-interactive-followups.md` that
`Test/runMain` on the root aggregate gave 600 compile errors. Both took "name the project" as the answer.

## Reproduction

A cold container with the sbt launcher and the Maven mirror flags (`cloud-env-sbt-limits` in project memory):
`sbt --client 'clean ; Test/compile'` on `scala` printed `compiling 157 Scala sources to .../tda4j-root/classes` next to the
rows' own lines, then the errors above.

## Fix and checks

`root` sets `Compile`/`Test` `unmanagedSourceDirectories` and `unmanagedResourceDirectories` to `Nil`.

- `show root/Compile/unmanagedSourceDirectories` is empty; `clean ; test` at the root compiles both rows only.
- A full `sbt release with-defaults` in a scratch clone with a local bare remote, the release steps unchanged except the two
  Central steps (`sonaUpload`, `sonaRelease`: no credentials) and the test step filtered to one spec to save time: clean, test
  on both rows, version 0.7.0 committed, tag `v0.7.0`, `publishSigned` (a throwaway gpg key) staging `tda4j_3` and
  `tda4j_sjs1_3` with jar, sources jar, javadoc jar (scaladoc from Scala 3.9.0, 1,121 files for the JVM row), POM and `.asc`
  files, version 0.7.1-SNAPSHOT committed, commits and tag pushed. The root published nothing (`publish / skip` holds).
- CI guard in `lint.yml`: with `aggregate := false`, `root/Test/compile` compiles only the root. On the old `build.sbt` it
  fails with the same errors; on the fixed one it takes about a second.

Not checked here: `sonaUpload`, `sonaRelease` (credentials), the GitHub Actions run itself.

## Cloud environment notes

- The JVM needs more than 2 GB for the two rows' test compiles at once: a launcher run with `-Xmx2G` died with
  `OutOfMemoryError` in the compiler's class writer; 6 GB was fine. A local `sbt` script reads `.jvmopts` (2 GB), which was
  enough for CI and local runs before; if Mikael's machine hits an OutOfMemoryError in `Test/compile`, raise it.
- The shallow clone of this container needs `receive.shallowUpdate true` on a scratch bare remote to take a push.
