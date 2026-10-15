# The demo REPL jar in the release (2026-10-15)

Ask from the project lead: wire `demo/` into `release.yml` so the jar is built and attached to the GitHub release.

## Design

- **Where the demo gets the library.** `demo/build.sbt` depends on a VERSION of `tda4j` (`TDA4J_VERSION`, else the
  checkout's `version.sbt`), never on the root's sources. `sbt release` publishes to Maven Central and THEN pushes the tag
  that starts `release.yml`, so resolving `tda4j % VERSION` from Central in the workflow races Central's sync (RELEASE.md
  says an exact coordinate is "immediately resolvable"; a workflow that fails on the one run that matters is a poor place to
  rely on that). The workflow instead runs `publishLocal` of the tag's own checkout (the tag is the commit that set the
  release version, so `version.sbt` and `TDA4J_VERSION` agree) and builds the demo against `~/.ivy2/local`. The jar holds the
  same sources as the artifact on Central. Everything else the demo needs (the Scala REPL, cask) still comes from Central.
- **One sbt invocation.** `publishLocal` is added to the existing `assembly ; packageSrc ; ++3.8.4! ; packageDoc ; doc`
  chain, before `++3.8.4!`, because the docs-version variable and the Scala switch both live in that one server session
  (`rules/docs-and-tutorials.md`, `release.yml`'s own comment): a separate `sbt publishLocal` before it would start the
  server without `TDA4J_DOCS_VERSION`. The scaladoc jar is left out of that local publish
  (`set Compile / packageDoc / publishArtifact := false`): the release's javadoc jar is the 3.8.4 one built by `packageDoc`.
- **The demo build is a different directory,** so it has its own sbt server: `working-directory: demo`, with
  `TDA4J_VERSION` in its environment.
- **Early, not last.** The demo is built and smoke-run BEFORE the docs are pushed to `gh-pages` and before the release is
  created, so a broken demo stops the workflow while nothing has been published; re-running the workflow from the Actions tab
  stays safe (RELEASE.md).
- **Smoke test.** The REPL evaluates the first piped line and ends (`WORKLOG-demo-jar.md`), so the step pipes
  `:load /tmp/demo-smoke.scala`; the script computes the persistence of the four corners of a unit square and prints
  `demo-smoke: bars=5 h1=1`; the step greps for it. A REPL that starts but cannot run the library would still exit 0.

## Checked here (Linux, Java 21)

- The two sbt commands, as the workflow runs them: the root with the `set ... ; assembly ; publishLocal` chain (exit 0), then
  `sbt assembly` in `demo/` (exit 0): `demo/target/out/jvm/scala-3.9.0/tda4j-demo/tda4j-demo-0.6.1-SNAPSHOT.jar`, 74 MB. The
  path is the one the `gh release create` line uses.
- The smoke step, extracted from the parsed workflow YAML and run with `bash -e`: exit 0. The same step with the expected
  string changed to `bars=9 h1=9`: exit 1 (a negative control).
- The workflow parses as YAML (`yaml.safe_load`), steps in the intended order.

## NOT checked

- **A run on GitHub.** Nothing here can start `release.yml`. The first real run is the next release tag. Things that could
  differ on the runner: the demo resolving a version that is on BOTH ivy-local and Central (sbt's default resolver order lists
  ivy-local first, so the local one should win; not tested, since here the version was on ivy-local only); the `set ... publishArtifact := false` idiom in sbt 2's
  server (worked here); how long the extra `publishLocal` and the demo's dependency resolution add (not measured on a
  runner); the `gh release create` with a 74 MB asset (limit 2 GB).
- **A pull-request check.** No CI job builds `demo/` on pull requests: the README's design is that the root's CI does not see
  it, so a change that breaks it is found at the release. A path-filtered job in `test.yml` (on `demo/**`) would catch it
  earlier; not added, since the ask was the release.
