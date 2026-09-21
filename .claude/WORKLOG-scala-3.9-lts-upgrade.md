# Upgrade to Scala 3.9.0 LTS (2026-09-21)

Requested by the project lead as queued follow-up work: "update the code base to use Scala 3.9.0 (the new lts)
and make sure the build is clean and all warnings handled." Scala 3.9.0 released 2026-09-03, opening the new
LTS line (succeeding 3.3 LTS); this codebase was on 3.8.4 (a "Next" series release, not the old LTS), so this
was a routine minor-version bump, not a migration from an old LTS baseline -- the scala-lang.org announcement's
own `-source:3.4-migration` through `-source:3.9-migration` sequential-rewrite guidance is for 3.3-LTS-origin
projects and didn't apply here.

Checked before touching anything: release notes for breaking changes relevant to this codebase's own feature
set (`-source:future`, `-language:experimental.modularity`, `implicitConversions`, `adhocExtensions`). Nothing
found that this codebase's own code paths exercise -- the closest was a Scala 3.10-targeted deprecation
("implicit search will no longer find instances from inaccessible companions"), not yet enforced in 3.9 itself,
and not something `-deprecation` (already permanent in this project's `scalacOptions`) flagged on this codebase.

**Change**: `build.sbt`'s `scalaVersion := "3.8.4"` to `"3.9.0"`. Nothing else in `build.sbt` needed touching --
no hardcoded Scala-version-specific dependency cross-builds, no CI workflow pins a Scala version directly (only
`java-version: 21`, already satisfying 3.9's JDK 17+ requirement).

**Verified, each independently**:
- `sbt clean compile` (main sources, 33 files): clean, zero warnings, zero errors.
- `sbt Test/compile` (test sources, 46 files): clean, zero warnings, zero errors.
- `sbt test`: 262 examples, 0 failures, 0 errors (257 passed, 5 skipped, 1 pending) -- identical to the 3.8.4
  baseline this same session established right before the upgrade (same numbers, same skip/pending set).
- `sbt scalafmtCheck scalafmtSbtCheck`: clean (this is what CI's `lint.yml` runs).
- `sbt mimaReportBinaryIssues`: no-op ("mimaPreviousArtifacts is empty, not analyzing"), matching the
  pre-existing `mimaPreviousArtifacts := Set.empty` setting in `build.sbt` -- not a new gap this upgrade
  introduced.

The prior session's warning-cleanup work (`.claude/WORKLOG-*` compiler-warnings-cleanup entries, summarized in
CLAUDE.md: "fixed all 102 visible warnings, made -feature/-deprecation/-unchecked/implicitConversions/
adhocExtensions permanent") is what made this a zero-friction bump -- no new warnings surfaced because there
was no backlog of suppressed ones to newly trip over.

CLAUDE.md's own "Commands" section line ("Build and test with sbt (Java 21, Scala 3.8.4)") updated to read
3.9.0 LTS.
