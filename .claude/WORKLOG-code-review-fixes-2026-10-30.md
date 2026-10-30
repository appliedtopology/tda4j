# WORKLOG: code review fixes (2026-10-30)

## Scope

Started the fixes from `.claude/WORKLOG-code-review-2026-10-30.md`, prioritizing correctness and readable phase boundaries
while keeping the primitive inner loops intact.

## Changes

- `EuclideanMetricSpace` now validates equal coordinate counts and finite entries on direct construction, snapshots its
  input arrays, and returns defensive copies from `pts`. `pointSqDistance` rejects mismatched arrays; internal metric
  loops use the unchecked helper after construction established the dimension invariant. `CechFiltration` reads an
  internally owned point without cloning on every vertex access.
- `ParallelPackedCohomology.cocycle` expands reduction logs with an explicit depth-first stack and memo. A cyclic log
  fails explicitly instead of overflowing the JVM stack or looping forever.
- `Persistence` Scaladoc now distinguishes per-bar lazy cocycles from cycles, which are built together on first read.
- `insertionDiameter` is package-private so it does not enter the flat wildcard-import surface.
- `PersistenceMap.integer` uses repeated doubling in logarithmic time and promotes the magnitude to `Long`, including
  for `Int.MinValue`.
- `BootstrapBand` asks `Persistence` not to precompute representatives for endpoint-only resampling.
- `ParallelPackedCohomology.persistence` now delegates work-unit partitioning, unit lookup, and next-degree assembly
  to named helpers. The phase reductions and pairing collection remain in the method for a later extraction.

## Verification and remaining work

`git diff --check` passed. Scalafmt completed on the seven changed Scala files. JVM and Scala.js compilation started, but
the sbt server disconnected before reporting a result. No tests were added or run.

Issue creation remains pending: duplicate searches found no matching issues for these findings, but the GitHub connector's
create call returned 403 (`Resource not accessible by integration`), and the local `gh auth status` still reports an
invalid credential. Retry after GitHub issue-write access is restored. The initial review worklog contains the issue
descriptions and supporting code locations.
