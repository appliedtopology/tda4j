# Docs-publish performance: sbt-github-pages replaced with a native git push

Project lead reported the docs-publishing step of `docs.yml` taking "over 20 minutes" in its last run and
asked for an investigation. Full arc: diagnose from real CI data (not guesswork), confirm the mechanism
against the plugin's own source, find a second live bug along the way, fix both, verify the fix against a
scratch repo before merging (this workflow only fires on push to `scala`, so it can't be dry-run for real from
a feature branch).

## Diagnosis

Pulled the actual run history via the GitHub API (`mcp__github__actions_list`/`get_job_logs`), not just the
YAML. Run #163 (`36255241509`, the one referenced): total 22m39s, step-by-step:

- Set up job / checkout / JDK / sbt launcher: ~14s combined.
- `sbt laikaSite` (full build: HTML + linked scaladoc + PDF + EPUB): 63s.
- `sbt publishToGitHubPages`: **21m13s** — essentially the entire run.

Downloaded the job log and scanned it for large time gaps between consecutive timestamped lines. Found
exactly one: **1268 seconds of complete silence** between the last "about to add this file" log line and
"Successfully pushed to gh-pages" — no build work, no network stall visible in the log, just silence. That run
staged 1,017 files (a single `dev` build; scaladoc's own bundled UI icon assets alone are hundreds of tiny
files).

Rather than guess why, pulled `sbt-github-pages` (`io.kevinlee % sbt-github-pages % 0.19.0`)'s actual source
(`GitHubApi.scala`, via `mcp__github__search_code` + fetching the raw file). It pushes through the GitHub Git
Data API — `createBlob` → `createTree` → `createCommit` — the *correct* API shape for a single-commit push in
principle, but:

- `createBlob` is called once per file, in a strict `files.traverse(...)` sequential loop — no batching, no
  parallelism.
- Every single call is preceded by `Temporal[F].sleep(1.second)` (`githubWithAbuseRateLimit`), a hardcoded,
  unconditional delay added specifically to dodge GitHub's secondary abuse rate limit.

1,017 files × ~1.25s/file (the sleep plus real request latency) ≈ 1,271s ≈ 21m11s — matches the observed
1268s almost exactly. Confirmed, not inferred: this is deliberate behavior in the plugin, not a bug or a
tunable setting (no batching/chunking config exists), and it scales linearly with file count — which only
grows as more release versions' scaladoc accumulates on `gh-pages` over time.

## A second, already-live bug found along the way

While tracing how versions get merged forward, checked the real `gh-pages` branch directly
(`git fetch origin gh-pages`) rather than assuming the workflow's own logic was doing what its comments said.
Found:

- The root `index.html` was redirecting to `user-guide/` — not `dev/`, not a real release version.
- `git ls-tree origin/gh-pages` showed top-level `api/`, `developers-guide/`, `downloads/`, `helium/`,
  `tutorials/`, `user-guide/` directories sitting *alongside* `dev/` — confirmed stale (last touched
  2026-09-25, a full day before `dev/api/` was, per `git log -- <path>`), left over from before docs
  versioning existed. Nothing had ever deleted them, because the old "copy forward every directory in
  `gh-pages` that isn't already staged" loop had no way to distinguish "an old release version, deliberately
  kept" from "dead weight from a bygone layout, never cleaned up" — and neither did the old
  `LATEST=$(ls -1 "$PUBLISH_DIR" | grep -vE '^(dev|index\.html)$' | sort -V | tail -n1)` redirect computation,
  which just excluded two known names and treated everything else as a version candidate. `sort -V` on a set
  including `dev`, `tutorials`, `user-guide`, etc. doesn't reliably rank a real semver string highest, and in
  this case it didn't.
- **These stale top-level directories were left in place, on purpose** — deleting live, currently-served site
  content wasn't part of what was asked, and is a separate, reversible-but-not-trivially-so decision from
  fixing the publish mechanism and the redirect logic. Flagged to the project lead; not yet acted on.

## Fix

Replaced `sbt publishToGitHubPages` in both `docs.yml` and `release.yml` with a plain git sequence against a
`gh-pages` worktree:

1. `git worktree add` (or, first time, `--orphan` bootstrap) a real checkout of `gh-pages`.
2. Replace *only* `<version>/` (`dev`, or the release tag) inside that checkout with the fresh
   `target/docs/site` output. Nothing else in the tree is touched, so there's no "copy forward what's not
   already here" step to get wrong — every other version, and root files like `.nojekyll`/`CNAME`, survive by
   construction.
3. Recompute the root redirect from a `find . -maxdepth 1 -type d -name '[0-9]*.[0-9]*.[0-9]*'` glob (matches
   the *shape* of a real version, not "isn't a name I recognize") rather than an exclusion list.
4. `git add -A`; commit and push only if `git diff --cached` is non-empty.

Removed the `sbt-github-pages` plugin entirely (`project/plugins.sbt`), `GitHubPagesPlugin` and its three
`gitHubPages*` settings (`build.sbt`) — nothing else in the build used them.

## Verification

Dry-ran the exact shell logic (copied verbatim from the committed workflow files, not re-derived) against a
scratch bare repo + working clone in `/tmp` (built and torn down within this session, nothing left behind),
covering:

1. Bootstrap: no `gh-pages` branch exists yet → created via `--orphan`, `dev/` populated, `.nojekyll` written,
   redirect → `dev/`.
2. A second `dev/` update, with a stale non-version directory (`user-guide/`, injected to mirror the real
   repo's actual state) present → redirect correctly stays on `dev/`, does NOT pick `user-guide/` (this is the
   live bug, reproduced and confirmed fixed).
3. A release (`0.1.3/`) lands alongside `dev/` → redirect moves to `0.1.3/`.
4. A further `dev/` update after a release exists → `0.1.3/` untouched, redirect stays on `0.1.3/` (doesn't
   revert to `dev/`).
5. Re-publishing identical content → genuine no-op: `git diff --cached --quiet` catches it, no empty commit,
   `origin/gh-pages` SHA unchanged before/after.

**Not yet verified**: an actual `docs.yml`/`release.yml` run against the real repo — that workflow only
triggers on push to `scala`, which a feature branch can't do. The project lead will merge and watch the first
real run. If it needs a rollback, `sbt-github-pages`'s prior config is in git history (this commit's parent).

## What this doesn't fix

The stale top-level directories on the live site (`api/`, `developers-guide/`, `downloads/`, `helium/`,
`tutorials/`, `user-guide/`) are still there — the redirect just correctly ignores them now. Deleting them is
a separate, explicit decision (it's live-site content deletion, not a mechanism fix) that hasn't been made
yet.
