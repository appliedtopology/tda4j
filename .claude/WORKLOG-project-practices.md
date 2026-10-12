# WORKLOG: project practices (2026-10-11)

The project lead asked for six practices to be established: open items as GitHub issues (the emphasis), a changelog,
the fat jar built in CI, a format check before each commit, coverage as a map rather than a gate, and automated
dependency updates. Also asked: would a git hook propagate to all developers? And, mid-arc: overhaul CONTRIBUTING.md
and the other guide posts for the dev team.

## Would a git hook propagate?

No. `.git/hooks` is not part of the repository, and git never installs hooks from a clone. Three layers:
- a versioned hook (`.githooks/pre-commit`) that each clone opts into once with `git config core.hooksPath .githooks`
  (documented in CONTRIBUTING.md);
- cloud Claude sessions opt in themselves: a SessionStart hook in the checked-in `.claude/settings.json` runs
  `.claude/scripts/session-start.sh`, which installs the native scalafmt and sets `core.hooksPath`. Only when
  `CLAUDE_CODE_REMOTE` is set: a local session does not change a developer's git config behind their back;
- CI's lint job, the only enforced gate.

A Claude `PreToolUse` hook on `git commit` was considered and not used: at PreToolUse time a compound
`git add ... && git commit` has not staged anything yet, so the hook would check the previous index. The git-level hook
sees the real index at commit time, for Claude and humans alike.

## Format hook

- **Native scalafmt.** The GitHub release binary (`scalafmt-linux-musl`, 84 MB) starts in ~24 ms. It checks all 247
  tracked files in 8 s, so a commit's few staged files take well under a second. It is a dynamic launcher: a
  `.scalafmt.conf` naming another version makes it download that core. Through this sandbox's TLS proxy the download
  fails with exit 8, so the hook treats any exit other than 0 or 1 as "could not check" and lets the commit through.
- **What it checks.** The hook runs `git show :<file> | scalafmt --stdin --assume-filename <file> --test`, which checks
  the staged content, not the working tree. This was tested.
- **Cases tested in a scratch clone:**
  1. a clean staged change: committed;
  2. a misformatted staged file: blocked, exit 1, file listed;
  3. staged misformatted while the working tree was already fixed: blocked;
  4. no scalafmt on PATH or in the cache: a warning, then committed;
  5. a scalafmt that errors (exit 8): a warning, then committed;
  6. a non-Scala commit: no check.
- **Installer.** `install-scalafmt.sh` reads the version from `.scalafmt.conf`, downloads in 1.5 s, and is a no-op on a
  rerun. The `scalafmt-macos` asset exists, but the script is untested on macOS (#61).

## Fat jar in CI

`sbt assembly` after the plot move gives a 27 MB jar with the 98 plot classes; `--help` exits 0. The CI smoke run
checks an answer: `--max-dimension 1 _docs/tutorials/data/noisy-circle.csv` reports exactly one degree-1 bar (the CLI
hides 3 short bars below 1% of the enclosing radius). Run locally under `bash -e`, as GitHub runs it.

## Coverage

- **Setup.** `sbt-scoverage` 2.4.4 (sbt 2 build) with Scala 3.9.0 and `-preview` instruments through the compiler's
  own coverage support.
- **Result.** 1071 tests, 0 failed, 11 skipped (the gated benchmarks). Statement coverage 89.3%, branch 83.9%, in
  6m44s against roughly 5 minutes uninstrumented.
- **Least covered:**
  - `package.scala`, 14% (the TDAlab re-exports);
  - `cells/SimplexOps.scala`, 47%;
  - `plot/Figure.scala`, 54%;
  - `cli/TDA4jCLI.scala`, 70%;
  - `io/Ripser.scala`, 74%.
- **Inert by default.** The plugin adds nothing to a normal build: `coverageEnabled` is false and `libraryDependencies`
  is unchanged.
- **Disk-cache pitfall.** A second local run failed in 7 s with `FileNotFoundException` on
  `scoverage-data/scoverage.measurements.*`. sbt 2's disk cache restored the instrumented classes (cache hit: same
  sources and options) but not the `scoverage-data/` directory the compiler writes beside them. The fix is
  `set Global / cacheStores := Nil` for the run. With it, the third run passed (1071 tests; 89.4% / 84.0%).
  `coverage.yml` and CONTRIBUTING.md use that exact command.
- **The workflow.** Weekly plus `workflow_dispatch`, with no threshold. The summary script was tested on the real
  `scoverage.xml`.
- **Issue #16** asked for posting to a coverage service. That was deliberately not done; the HTML report is an
  artifact instead. It was left open for the project lead.

## Dependency updates

GitHub's Dependabot documentation now lists an `sbt` ecosystem. It reads `build.sbt`, `project/plugins.sbt`,
`project/build.properties` and `project/*.scala`, with no stated sbt-version support, so Scala Steward was not needed.
`dependabot.yml` covers `github-actions` and `sbt`: weekly, with minor and patch updates grouped and Scala held to
3.9.x. Whether it parses this sbt 2 build is known only after the first run on `scala` (#61).

## Changelog

`CHANGELOG.md` is seeded with the changes since v0.5.0:
- binary breaks from the commented MiMa filters in `build.sbt`;
- behaviour changes from the bodies of PRs #46–#49 and this PR.

PRs #44 and #45 (tutorial slides, release credentials) carried no library change. `RELEASE.md` gains the step that
turns Unreleased into the version.

## Issues filed (#51–#69)

Each was checked against the current code (paths, `skipAll`, grep for emergent pairs, `ls-remote` for the stray tag)
before filing; the labels `performance` and `unverified` were created by using them.

| issue | label | subject |
|---|---|---|
| #51 | performance | chunks and naive engines reduce every top cell; `BarcodeRegressionSpec` skipped |
| #52 | question | NaN as the mask value for images |
| #53 | enhancement | Brown's collapse for chains that do not refine |
| #54 | performance | collapse: S₄ degree 8 out of memory |
| #55 | enhancement | cup products on the collapsed complex |
| #56 | performance | cubical peak memory |
| #57 | performance | emergent pairs |
| #58 | bug | `Chain` equals without hashCode |
| #59 | maintenance | the 3.8.4 docs pin |
| #60 | documentation | the Playground link loads tda4j 0.4.0 |
| #61 | unverified | checklist: MATLAB, viewer, cubical d ≥ 5, GL(2,3), hooks, Dependabot sbt, coverage workflow |
| #62 | enhancement | Sage parity: pullbacks, integer coefficients, CP³/CP⁴ |
| #63 | enhancement | MATLAB/CLI input for simplicial sets, groups, plots |
| #64 | performance | sparse Rips O(n log n) neighbour search |
| #65 | maintenance | the stray `scala` tag and release |
| #66 | documentation | merging the student's guide branch |
| #67 | documentation | port Henry Adams' tutorials (from TODO.md) |
| #68 | enhancement | Helix Delaunay symbolic perturbation |
| #69 | enhancement | PHAT, sparse triplet and Perseus simplicial formats |

Not filed:
- the 3-D grid cubical engine idea in `rules/cubical.md`, since `PackedCubicalCohomologyEngine` now does Ripser-style
  reduction on grids;
- `TODO.md`'s RedBlackTree-merge idea, since chains are no longer `SortedMap`s;
- `TODO.md`'s MATLAB-API note, since the facade exists.

## Guide posts

- **CONTRIBUTING.md**, rewritten:
  - a map of the guide posts;
  - setup, including the hook opt-in;
  - the commands, the issue-to-PR workflow with the labels, CI, compatibility and the four surfaces;
  - the design principles, adding "easy, not simple" and the flat package;
  - tests, documentation (with the Scastie recipe moved from the README), performance, and working with Claude Code.
  - Removed: the stale "publish a release" section and the 1.x-branch advice.
- **README.md**, rewritten for users: what tda4j is, sbt and Maven coordinates (0.5.0 is on Central), a five-line
  example, the fat jar for the CLI and MATLAB, and links.
- **Pull request template:** "Closes #N", a changelog line, the assembly check, a "Left open" section.
- **Issue templates:** bug, limitation/capability, unverified claim, decision.
- **RELEASE.md**, fact fixes:
  - the release.yml steps and their order;
  - the zip path;
  - a broken anchor;
  - `TDA4j` → `tda4j` coordinates;
  - the changelog steps;
  - a history parenthetical dropped.
- **Developer's guide index:** a Contributing section pointing to CONTRIBUTING.md and the issues.
- **The steward skill:** the hook, the changelog line, issues for what is left open, and the CI table including the
  smoke run and coverage.
- **CLAUDE.md:**
  - the "open items are issues" rule;
  - the CI paragraph condensed to a pointer at the steward skill, to stay roughly at its size;
  - it is still about 25.8k characters, over the ~25k budget, as before this arc.

## Addendum: review fixes

- **Dependabot's `sbt` ecosystem, confirmed from primary sources** rather than a summarized page:
  - `sbt` is in SchemaStore's `package-ecosystem` enum (`json.schemastore.org/dependabot-2.0.json`);
  - the raw docs page has an `id="sbt"` section;
  - `dependabot.yml` validates against that schema with `jsonschema`.
- **The hook skips merge commits** (`MERGE_HEAD` present). A merge stages everything the other branch changed, so a
  misformatted base would block the merge-then-`Format:`-commit flow that CONTRIBUTING.md and the steward skill ask
  for. Tested in a scratch clone: a conflicted merge with a misformatted base commits with a note, and an ordinary
  commit of a misformatted file is still blocked.
- **Issue fixes:**
  - #51 named a non-existent `PersistenceInChunks.scala`; the engine is in `homology/Homology.scala`.
  - #65 now states only what was observed: the tag on ed72777, and the release page.
  - #66 now carries the stale-package facts and the expected conflicts, checked on the branch: `architecture.md` lines
    16, 37–38 and 97–99 still teach subpackages and `{given, *}`.
  - #68 named the right component (`HelixDelaunayBuilder`) but cited `requireValidTriangulation` (a flag) as the
    repair; it now names `looksValid`, `interiorVoidVertices` and `repairByJitterRetriangulation`.
- **CLAUDE.md is back to 25.0k characters:**
  - the engine detail already in `rules/engines.md` became pointers;
  - the `Chain` hashCode footgun points to #58;
  - the package-layout list was trimmed of instance file names.

## Addendum: Codecov (2026-10-11)

- **Setup.** The project lead reactivated Codecov and stored its token as `CODECOV_TOKEN` in the `codecov` GitHub
  environment. `coverage.yml` runs in that environment and uploads scoverage's Cobertura report with
  `codecov/codecov-action@v6` (`disable_search`, `fail_ci_if_error`). `codecov.yml` keeps coverage a map: the
  statuses are informational and there are no PR comments.
- **First upload.** The upload was accepted ("queued for processing"), but Codecov never recorded the commit, because
  the repository's Codecov activation was still in progress. Re-running after reactivation worked.
- **Paths came out doubled.** With a `fixes: ["::src/main/scala/"]` prefix, the report listed every file as
  `src/main/scala/src/main/scala/...`: Codecov resolves the Cobertura source root by itself. The prefix was removed.
- **Result on e421205** (run 38180489875): 94 files, 9081 of 9987 lines covered (90.92%), and every path listed exists
  in the repository.
- **The checks behind this:**
  - The first path check only tested for the `src/main/scala/` prefix, so it passed on the doubled paths. Check that
    each path is an existing file instead.
  - Codecov counts lines (Cobertura), while scoverage counts statements (89.4%), so the two numbers differ.
