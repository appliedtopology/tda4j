# Daily SNAPSHOT publishing (2026-10-27)

Request (Mikael): a CI job that publishes daily SNAPSHOT releases to Maven. Result: `.github/workflows/snapshot.yml`,
env-var credentials in `sonatype.sbt`, a "Snapshots" section in `RELEASE.md`, a "Development snapshots" section on the
installation page.

## What existed

`sonatype.sbt` already had `publishTo` = `https://central.sonatype.com/repository/maven-snapshots/` for a `-SNAPSHOT`
version and `localStaging` otherwise, and `credentials += Credentials(~/.sbt/sonatype_credentials)`. `release.yml` publishes
nothing to Maven (`sbt release` does it locally). `version.sbt` says `0.7.0-SNAPSHOT`.

## Findings (measured against a local stand-in for the snapshot repository, not against Central)

- `curl -X PUT` on the snapshot repository without credentials answers 401 with `WWW-Authenticate: BASIC realm="Sonatype
  Nexus Repository Manager"`. sbt 2.0.11's `publish` (Ivy-style) sends a token only to the realm it was made for.
- `RELEASE.md` told the credentials file to say `realm=Sonatype Central`. Against a stub that challenges with the Nexus
  realm, `Credentials("Sonatype Central", host, ...)` gets a 401 and `publish` fails ("refused by the server:
  Unauthorized"); `Credentials("Sonatype Nexus Repository Manager", host, ...)` publishes. So the file does not work for a
  snapshot; `sonatype.sbt` builds a second credential for the Nexus realm from `SONATYPE_USERNAME`/`SONATYPE_PASSWORD`.
  Checked end to end with the stub aliased to `central.sonatype.com` in `/etc/hosts` (restored afterwards): the env-var
  credential authenticates. The realm string is the same one `sbt-ci-release` uses for Central snapshots.
- `tda4j/publish` and `tda4jJS/publish` each upload pom, jar, sources jar and javadoc jar with `.md5`/`.sha1` files, under
  the plain `-SNAPSHOT` file names (no timestamped versions, no `maven-metadata.xml`: Nexus makes the metadata). The
  Scala.js artifact needs no Node and no link. A warm run took 1.5 min for both, 2 min with `cacheStores := Nil`.
- Scaladoc 3.9.0 builds the javadoc jar (11 MB JVM, 5 MB JS); only the docs *site* needs 3.8.4. The jar warned "Variable $CP
  undefined in comment" for `BlockParallelReduction`; fixed with `\$CP`.
- Central docs ("Publishing SNAPSHOTs", checked 2026-10-27): a namespace must have "Enable SNAPSHOTs" switched on in the
  Portal; "no validation is performed" (no signing, no checksums required); snapshots are deleted after 90 days.

## Design decisions

- "No new commits since the last snapshot": HEAD differs from the `headSha` of the last *successful* run of `snapshot.yml` on
  `scala` (`gh run list`). A skipped run succeeds with that same sha, so the chain holds; a failed run is not counted, so
  the next day retries. No tag or file in the repository records it. Considered and not done: gating on `test.yml` being
  green for HEAD (a skip there would have to fail the run, or it would be counted as handled).
- Secrets only in the publish step's `env`, so the setup actions never see them.
- `set Global / cacheStores := Nil` as in the demo build (`WORKLOG-demo-assembly-cache.md`): setup-sbt restores `~/.cache/sbt`.
- Dry run on a PR touching the workflow or `sonatype.sbt`, and on a manual run of any branch other than `scala`: publishes
  into `target/dry-run-repo` and checks the eight files. This is the only way to try the workflow before it merges: a
  `workflow_dispatch` workflow that exists only on a branch cannot be dispatched. A snapshot of a feature branch would also
  replace the one of `scala`, which is why a real publish requires `refs/heads/scala`.
- `TDA4J_DOCS_VERSION` is set to the snapshot's version so the scaladoc in the javadoc jar is titled with it.

## Not verified

- The upload to Central itself (the namespace switch, the token, Nexus accepting non-unique snapshot file names and
  redeploys). `sbt-ci-release` publishes the same way, which is evidence, not a test. The first run on `scala` with `force`
  is the test; `RELEASE.md` says what to look at.
- `gh run list --workflow snapshot.yml --branch scala --status success --json headSha` on the real API (the Plan step's
  logic was run against a fake `gh` for six cases).
