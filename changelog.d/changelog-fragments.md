### Build, CI and Documentation

- A pull request adds its changelog entry as a file in `changelog.d/` instead of a line in `CHANGELOG.md`, so two pull
  requests no longer conflict on the changelog and drop out of the merge queue. The release folds the files into the
  version's section (`python3 changelog.d/assemble.py release X.Y.Z`, `RELEASE.md`), and CI's new `changelog` job checks
  the files and fails a pull request that adds an entry to `CHANGELOG.md` itself.
