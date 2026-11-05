---
layout: main
title: Pull request workflow
---

[CONTRIBUTING.md](https://github.com/appliedtopology/tda4j/blob/scala/CONTRIBUTING.md) is the short version: setting up, the
commands, the labels and the review principles. This page is the longer one: what CI runs and how to reproduce each job,
what a change must carry, how the merge queue treats several pull requests at once, and how snapshots and releases leave
the repository.

## The path of a change

1. **An issue first** for anything that is not obvious: a limitation, a question that needs a decision, a claim nobody has
   checked. Everything left open by a pull request becomes an issue and is linked from the pull request.
2. **Branch from `scala`**, the default branch, and open the pull request against it. Turn on the formatter hook once per
   clone: `git config core.hooksPath .githooks`.
3. **Write the spec first**, and run the checks below before you push, in sequence.
4. **Open the pull request** from the template, listing what changed and why, which checks you ran, and what is not verified.
   "Closes #N" closes the issue on merge.
5. **Keep it green** until it is reviewed. The project lead reviews and merges.

## CI and its local equivalents

Run these one at a time. **Two `sbt` processes against one checkout read each other's half-written class files**, and the
`NoClassDefFoundError` that follows looks like a regression. For a comparison, use a second worktree. Reproduce a failure
locally before you fix it, then push once.

| CI job | what it checks | locally |
|---|---|---|
| `lint` | formatting of sources, tests and the build | `sbt "scalafmtSbtCheck ; scalafmtCheck ; Test / scalafmtCheck"`; fix with `sbt scalafmtAll scalafmtSbt` |
| `test` | the JVM specs; then the fat jar is built and run on a sample | `sbt tda4j/testFull`, `sbt tda4j/assembly` |
| `test-js` | the Scala.js specs on Node.js | `sbt tda4jJS/test` ([JVM and Scala.js](jvm-and-js.md)) |
| `npm-package` | the npm tarball installs into an empty project and runs | `sbt tda4jJS/npmPack`, then install the tarball |
| `python-package` | the wheel installs in clean environments and its tests pass | `TDA4J_JAR=<jar> PYTHONPATH=python/src pytest python/tests` |
| `docs-build` | every Scala fence of the docs compiles | `sbt shutdown`, then `TDA4J_SCALA_VERSION=3.8.4 sbt tda4j/doc` |
| `mima` | binary compatibility inside the release series | `sbt tda4j/mimaReportBinaryIssues` |
| `changelog` | the fragments are valid, and `CHANGELOG.md` gained no entry | `python3 changelog.d/assemble.py check` |

Traps:

- **`sbt test` can run no specs**: sbt 2 caches test results on disk. `testFull` runs them all.
- **The docs build needs Scala 3.8.4**, which only a fresh sbt server sees: `sbt shutdown` before the build, and
  `sbt shutdown` then `sbt clean` after it, or a test compile can miss main classes.
- **scalafmt sees only tracked files**: `git add` a new file before formatting.
- Formatter output on files you did not otherwise touch goes in a commit of its own, "Format: ..., formatter output only".
- **Never skip, disable or loosen a test to get green.** A spec that fails on the shared Scala.js code is a bug in the
  code.
- A job that passed its sbt step and fails only in "Post Setup sbt launcher" (`hashFiles(...) failed`) hit a race with a
  server left over from an sbt call without `--server`. It is a workflow problem, not a test failure.

**Binary compatibility.** A 0.Y series does not keep compatibility with the one before. Inside a series, a deliberate
break is a commented `mimaBinaryIssueFilters` entry in `build.sbt` plus a line under Breaking; an accidental one is
restored, not filtered.

## What a change carries

- **A changelog fragment** if a user sees or calls something differently: `changelog.d/<topic>.md`, with `###` sections
  (`Breaking`, `Added`, `Changed`, `Fixed`, `Build, CI and Documentation`) and `- ` entries written for a user of the
  library. **Never a line of `CHANGELOG.md`**: two pull requests that add to one list conflict, and the merge queue then
  drops the second. Two new files never conflict. The release folds the fragments into `CHANGELOG.md`.
- **The facades and the docs**, for a new complex, engine or option: [Extending the facades](facades.md).
- **The developer's guide and the user guide**, for a subsystem the change touches: a page that the change makes false is fixed in
  the change.
- **A new or opened public top-level type** means rerunning `python3 .claude/scripts/tdalab-exports.py`, which regenerates
  the flat re-exports of `TDAlab`.
- **A measurement** for a speed or memory claim ([Measuring performance](performance.md)).

## The merge queue and several pull requests

`scala` merges through GitHub's merge queue, with merge commits. Several pull requests are often queued in one burst, and the
queue has habits worth knowing.

- **A queued branch is locked.** Pushing to it fails with "protected branch hook declined". A conflict fix waits until the
  queue drops the pull request (a `dequeued` event, `MERGE_CONFLICT`).
- **Conflicts come from files that two pull requests both edit**: documentation and rule files, the changelog (hence
  the fragments), `build.sbt`. Before a burst, check pairs for overlap, those files first. A local `git merge` in the
  queue's order on a scratch branch off `origin/scala` is the quick test.
- **Stacked pull requests** (one built on another's commits) are fine for CI, which only runs on pull requests into
  `scala`. But a branch that has merged a sibling branch can have two merge bases, and the queue has dropped such a branch
  for a conflict that local `git merge` did not show. **Merge `origin/scala` into a stacked branch once its parents have
  landed, rather than merging a sibling's branch into it.** After that the branch has one merge base and a diff of its
  own commits.
- Do not enable auto-merge on someone else's pull request.

## Snapshots and releases

- **Snapshots.** `snapshot.yml` publishes `tda4j_3` and `tda4j_sjs1_3` as the `-SNAPSHOT` version to Central's snapshot
  repository every day at 03:17 UTC, unless `HEAD` of `scala` is the commit of the last successful run. It does not run the
  tests; CI has run them on every commit that reached `scala`. A pull request that changes `snapshot.yml` or `sonatype.sbt`
  gets a dry run into `target/dry-run-repo`, with no secrets, and so does a manual run on any other branch (a workflow that
  exists only on a branch cannot be dispatched, so this is how a change to it is tried before the merge). To use a
  snapshot, add the resolver `https://central.sonatype.com/repository/maven-snapshots/` and the `-SNAPSHOT` version.
- **Credentials.** Publishing takes a Central Portal user token, as the environment secrets `SONATYPE_USERNAME` and
  `SONATYPE_PASSWORD`, and "Enable SNAPSHOTs" on the namespace in the Portal, once. If a publish answers 401, check the
  realm first: the snapshot repository asks for `Sonatype Nexus Repository Manager`, and sbt sends a token only to the realm
  it was made for, which `sonatype.sbt` handles.
- **Releases** are cut by the project lead with `sbt release` on a local machine, which bumps the version, tests, tags and
  publishes the signed artifacts to Central; the pushed tag starts `release.yml`, which builds the fat jar, the npm package
  and the wheel, publishes the docs under the version's path, and creates the GitHub Release. The two halves are split so the
  step that cannot be retried is run by a person and the one that can is automatic. Step by step, including the changelog
  fold (`python3 changelog.d/assemble.py release X.Y.Z`), is [RELEASE.md](https://github.com/appliedtopology/tda4j/blob/scala/RELEASE.md).
