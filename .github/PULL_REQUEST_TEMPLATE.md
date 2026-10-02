<!-- Title: a short imperative summary. Delete any section or checklist item that does not apply. -->

## What and why

<!-- What does this change, and why? Link the issue or design doc (.claude/DESIGN-*.md) if there is one. -->

## Changes

<!-- The user-visible and API changes, grouped. Call out anything that breaks 0.4.x compatibility (0.5.0 does not keep it, but say so). -->

-

## Checks (run locally before requesting review)

- [ ] `sbt scalafmtAll` -- formatter output on files you did not otherwise touch goes in its own `Format:` commit
- [ ] `sbt scalafmtSbtCheck scalafmtCheck "Test / scalafmtCheck"` -- exactly what CI's lint job runs (`build.sbt` and test sources included)
- [ ] `sbt testFull` (plain `sbt test` can run 0 specs in sbt 2 because of its disk cache)
- [ ] `TDA4J_SCALA_VERSION=3.8.4 sbt doc` if `_docs/`, `_layouts/`, `sidebar.yml` or public scaladoc changed (every Scala fence is compiled)
- [ ] `sbt mimaReportBinaryIssues` if public API changed, or the break is intentional and explained above

## If this adds or changes a user-visible capability (complex, engine, option)

- [ ] `matlab.TDA4j` dispatch
- [ ] `cli.TDA4jCLI` / `TDA4jConf` (a 1:1 mirror of the MATLAB option keys)
- [ ] `_docs/developers-guide/` (`persistence-engines.md`, `architecture.md`, `class-diagrams.md`)
- [ ] `_docs/user-guide/`

## If this touches a tutorial page (`_docs/tutorials/`)

- [ ] The page's "whole script" fence defines every value its spec asserts on (the docs are the tests), and every number the prose quotes is asserted
- [ ] A MATLAB tab, where the facade supports the task, with a `MatlabTabsSpec` case making the same calls
- [ ] No timings in the prose; public API only

## Notes for the reviewer

<!-- What is NOT verified (for example MATLAB code, which CI cannot run), what is deliberately left out, and where you want a second opinion. Performance claims need an isolated A/B measurement. -->

## Bookkeeping

- [ ] A `.claude/WORKLOG-<topic>.md` for any substantial investigation, and a rule or limitation (with a pointer, no narrative) in `.claude/CLAUDE.md`
- [ ] A bug found in a paper or reference implementation is logged in `.claude/BUGS-IN-REFERENCES.md`
