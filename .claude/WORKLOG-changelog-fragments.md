# Changelog fragments (2026-10-27)

**Problem.** Every user-visible PR added lines to the same lists under `## Unreleased` in `CHANGELOG.md`. Two PRs that
insert into the same list (or into adjacent lines) conflict, and in the merge queue the conflict is found only when the
second PR is merged onto the first: the queue drops it. #238 was dropped behind #235 this way; it had happened before.
Rebasing a PR after each merge ahead of it is what a queue exists to avoid, so the fix is that PRs stop touching a
shared file, not a better conflict routine.

**Options.**

| option | verdict |
|---|---|
| `CHANGELOG.md merge=union` in `.gitattributes` | not available: GitHub's merge and the queue's merge groups do not run custom merge drivers (as far as documented), so it works on a laptop and not where the conflict happens |
| Release notes generated from PR titles and labels (`gh release create --generate-notes`, which `release.yml` already uses) | no file conflicts, but the entries here are written for users: a break says what to write instead, an addition says what to call. A PR title cannot carry that, and the changelog would stop being the release notes (RELEASE.md pastes the section into the Release) |
| One fragment file per PR in `changelog.d/`, folded in at the release (towncrier's idea) | chosen: a new file conflicts with nothing, entries keep their form, nothing is lost at the release |

**Choices inside the fragment scheme.**

- Named `changelog.d/<topic>.md`, not by PR number: a cloud session writes the entry before the PR exists, and the entry
  names the number in its text, as before. Plain script (`assemble.py`, standard library only; Python is on the runner and
  on every dev machine) instead of towncrier: one dependency less, and the file format is just a piece of `CHANGELOG.md`.
- A fragment holds `###` sections with `- ` entries, so it reads like the changelog it becomes. Section names are the five
  the changelog already uses; an empty section is left out at the release (an absent Breaking section means no breaks).
- The release step is local (`python3 changelog.d/assemble.py release X.Y.Z`) and committed before `sbt release`, where
  RELEASE.md already had the human rename the section. It appends the fragments' entries, in file-name order, to whatever
  entries are still under `## Unreleased` (the 0.7.0 entries written before this change stay there; no migration), writes
  `## X.Y.Z (date)`, deletes the fragments and leaves `## Unreleased` with a one-line pointer to `changelog.d/`.
- CI job `changelog` (in `lint.yml`, so `merge_group` runs it): fragments well-formed, the script's unit tests, and on a
  pull request the count of entries under Unreleased in `CHANGELOG.md` must not grow. A count, not a ban on editing: an
  entry reworded by a later PR is a legitimate edit, and the release commit, which moves every entry out, passes without
  a bypass. It is a job of its own because the `lint` job skips when a PR touches only `.github/` or `.claude/` and is
  slow; this one needs no build.

**Not done.** `.claude/CLAUDE.md` is another file PRs append to (#238 touched it too); it is the next candidate if the queue keeps dropping PRs. It
conflicted on this very PR: #238 merged first and condensed the same paragraph of CLAUDE.md that this PR condensed. `release.yml` still uses
`--generate-notes`; building the Release description from the changelog section would drop the manual paste in
RELEASE.md. The repository's required checks need `changelog` added in the settings for it to block a merge.
