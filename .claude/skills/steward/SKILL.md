---
name: steward
description: How to drive a tda4j pull request to green and keep it there. Use when watching a PR, fixing its CI (test, docs-build, mima, lint), answering its review comments, or resolving its merge conflicts. Covers each CI job's local equivalent, how proactive to be, and the bookkeeping a push carries.
---

# Stewarding a tda4j pull request

Read `.claude/CLAUDE.md` first; this skill is only about getting a PR merged. It does not restate the coding rules.

## Whose PR it is

- **A PR from a Claude session branch** (`ccr-...`, `claude/...`) is Claude's to drive to green without asking: fix red CI,
  merge conflicts and review nits, and push. The project lead opens and merges these PRs; nobody else will fix them.
- **A PR a person opened** is theirs: fix lint and plainly small things, and put anything larger to the project lead.
- **PR scope:** the project lead does not require focused PRs yet; related follow-up work may land on the open PR.
  When the PR's scope grows, update its description to cover all of it.

## The CI jobs and their local equivalents

Run everything below in sequence, never two `sbt` processes against one checkout at once. One process can read the
other's class files mid-write, and the resulting `NoClassDefFoundError` looks like a regression. Check that no sbt
server is still running (`jps -l`) before starting one. Reproduce a CI failure locally before fixing it, and push once,
after every job below passes locally.

| CI job (workflow) | what CI runs | run locally | fix |
|---|---|---|---|
| `lint` (`lint.yml`) | `sbt "scalafmtSbtCheck ; scalafmtCheck ; Test / scalafmtCheck"` | the same | `sbt scalafmtAll scalafmtSbt` |
| `test` (`test.yml`) | `sbt "clean ; tda4j/test"` on a fresh runner, then `sbt tda4j/assembly` and the jar run on `noisy-circle.csv` (exactly one degree-1 bar) | `sbt clean tda4j/testFull`; `sbt tda4j/assembly` and the same run | root-cause it; never skip, disable or loosen a test |
| `test-js` (`test.yml`) | `sbt "clean ; tda4jJS/test"` with Node 22 | the same (Node 20.16 or newer) | root-cause it; a spec that needs a JVM moves to `src/test/scalajvm`, a failure on shared code is fixed in the shared code (`rules/scalajs.md`) |
| `npm-package` (`test.yml`) | `sbt tda4jJS/npmPack`, then install the tarball into an empty project and run the library and `npx tda4j` | the same | the step that failed names it |
| `docs-build` (`test.yml`) | `TDA4J_SCALA_VERSION=3.8.4 sbt tda4j/doc` | `sbt shutdown`, then the same | the error names `page.md:line` |
| `mima` (`test.yml`) | `sbt tda4j/mimaReportBinaryIssues` | the same | see below |

CI starts every one of these with `sbt --server` (sbt in the foreground, exiting with its step), in every workflow; the
table leaves the flag out because locally plain `sbt` is right. Through the thin client a server stays behind and
replaces its log file under `target/global-logging/` just as setup-sbt's post-job cache step hashes the workspace. A job
whose sbt step passed and that fails only in "Post Setup sbt launcher" with `hashFiles('**/*.sbt', '**/*.properties')
failed` is that race: find the sbt call without `--server` (`WORKLOG-ci-sbt-post-step-race.md`). It is not a test or
MiMa failure, and a re-run hides it without fixing it.

`coverage.yml` (weekly and on demand, uploaded to Codecov) is a map, not a check: nothing fails on a coverage number, and
Codecov's commit statuses are informational (`codecov.yml`).

### Lint

- **The pre-commit hook** (`.githooks/pre-commit`, turned on in cloud sessions by `.claude/settings.json`) checks the
  staged files with the native scalafmt and blocks a misformatted commit. It lets a merge commit through (the base's
  unformatted files then get their own `Format:` commit); otherwise fix the files, never `--no-verify` past it.
- **scalafmt only sees git-tracked files:** `git add` a new file before formatting.
- **Formatter output on files the change did not otherwise touch** goes in its own commit:
  "Format: <files>, formatter output only, no behavior change".
- **The project lead's rule:** if lint fails on the PR and this session's own work did not cause it, fix it anyway, in
  its own lint-fixing commit.

### Tests

- **Plain `sbt test` can run 0 specs locally**, because sbt 2 caches incrementally; `tda4j/testFull` runs every spec of
  the JVM build. Use `tda4j/testOnly *Spec` while iterating.
- **The tutorial specs run code from `_docs/tutorials/`:** a failing tutorial spec means fix the page and its numbers
  together (`rules/docs-and-tutorials.md`).

### Docs

- **Fresh sbt server:** the docs build uses Scala 3.8.4, which only a fresh sbt server sees. Run `sbt shutdown` before
  the build, and `sbt shutdown` then `sbt clean` after it, before any test compile.
- **Every Scala fence is compiled**, each on its own with its own imports.
- **Mermaid diagrams are not parsed by `sbt doc`.** Check a new one with `mermaid.parse` in a headless browser.

### MiMa

- **Added classes or members** are fine.
- **A deliberate break** inside the 0.5.x series gets a `mimaBinaryIssueFilters` entry in `build.sbt`, with a comment
  saying what changed and why. Breaks are allowed before 1.0, but they are always named.
- **An accidental break** (a renamed or removed public member nobody chose to drop) is restored, not filtered.

## How proactive to be

- **Do without asking**, on a PR this session created:
  - CI fixes in code the PR touches;
  - lint, in any file;
  - merge conflicts: merge the base in, never rebase a shared branch;
  - the developer's guide and user guide pages of a subsystem the PR changes (a fact the change makes false, a new
    capability the page should name);
  - review nits;
  - small asks from a review.
- **Ask the project lead first** about:
  - API or design changes a reviewer asks for;
  - a change to an engine's algorithm;
  - anything sold as faster or leaner.
- **Performance needs a measurement.** A claim that something is faster or uses less memory needs an isolated A/B
  (`_docs/developers-guide/performance.md`). Report an effect within the noise as unconfirmed.

## What a push carries

- **A `changelog.d/<topic>.md` fragment** for anything a user sees or calls (format: `changelog.d/README.md`); a break
  goes under Breaking, with what to write instead. Never a line of `CHANGELOG.md`: the `changelog` job fails it, and two
  PRs that add to the same list conflict and drop out of the merge queue.
- **An issue for everything left open**: a question for the project lead (`question`), a limitation (`enhancement`,
  `performance`), a claim nobody has checked (`unverified`). Search the issues first, open and closed. The body says
  what, where (files, worklog) and what done looks like, and ends with the attribution footer. Link the issues from
  the PR's "Left open" section and from the worklog, instead of keeping the list only there.
- **A worklog** for any substantial investigation (`.claude/WORKLOG-<topic>.md`). It is never edited afterwards; later
  results go in an addendum.
- **The resulting rule** goes in `CLAUDE.md` or the matching `.claude/rules/` file, with a pointer to the worklog and no
  narrative.
- **A new or opened public top-level type** means rerunning `python3 .claude/scripts/tdalab-exports.py`.
- **A user-visible capability** means checking four surfaces: MATLAB, CLI, the developer's guide and the user guide.
- **Commit messages** end with the attribution lines the session provides and name no model.

## Never

- **History:** force-push or rebase a branch someone else has checked out.
- **Kicking CI:** push an empty commit, or close and reopen the PR.
- **Tests:** skip, disable or quarantine one to get green.
- **New PRs:** open one nobody asked for.
