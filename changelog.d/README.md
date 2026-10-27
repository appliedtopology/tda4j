# Changelog fragments

A pull request that changes what a user sees or calls adds **one new file here**, `changelog.d/<topic>.md`, and does
not touch [`CHANGELOG.md`](../CHANGELOG.md). Two pull requests that add a line to the same list conflict, and a conflict
drops the second one out of the merge queue; two new files never do. At a release the files are folded into
`CHANGELOG.md` and deleted (`RELEASE.md`).

## The file

Name it for the change (`circular-from-diagram.md`), not for the pull request: the number is not known until the pull
request is open, and an entry names it in the text, `(#236)`, as the entries of `CHANGELOG.md` do. If a name is taken,
choose another.

The content is a piece of `CHANGELOG.md`: `###` headings from the list below, each followed by `- ` entries. Use only the
headings you need.

```markdown
### Breaking

- `Plot.save` returns the absolute path as a `String`, not a `java.nio.file.Path`. Call `Paths.get(result)` where you need
  a `Path` (#229).

### Added

- `Persistence.circularCoordinates` takes the diagram it came from (#236).
```

| heading | for |
|---|---|
| `Breaking` | a change that breaks existing code, with what to write instead; a break inside a release series also has a commented `mimaBinaryIssueFilters` entry in `build.sbt` |
| `Added` | a new capability or option |
| `Changed` | a change in behaviour, speed or memory that breaks nothing |
| `Fixed` | a bug that gave a wrong answer or a crash |
| `Build, CI and Documentation` | what contributors and readers of the docs see |

An entry is written for a user of the library, as the entries of `CHANGELOG.md` are: what is different, and what to call.
It continues on indented lines. A later pull request that changes what an earlier, unreleased entry says edits that
entry's file.

## Checks

`python3 changelog.d/assemble.py check` validates the files; CI's `changelog` job runs it, and fails a pull request that
adds an entry under Unreleased in `CHANGELOG.md`. `python3 changelog.d/assemble.py release X.Y.Z` is the release step
(`RELEASE.md`). The script's tests are `python3 -m unittest discover -s changelog.d`.
