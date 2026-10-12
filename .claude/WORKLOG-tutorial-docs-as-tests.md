# WORKLOG: tutorial docs as tests (2026-10-02)

Problem (project lead): each tutorial's whole-script code was copied into its spec (and a third time into the narrative fences), so
doc and test could drift; `sbt doc` compiles fences but runs nothing and asserts no number.
Fix: `build.sbt` `Test / sourceGenerators` (uncached via `Def.uncached`) generates `target/.../src_managed/test/tutorial/<Page>Script.scala`
from every `_docs/tutorials/*.md` that has `## The whole script`: the first `scala` fence after it becomes `object <Page>Script`, and the
`scala sc:nocompile` fences before it become `<Page>Narrative.narrative()` (compiled, never called). Specs shrank to assertions on
`<Page>Script.<val>`. Two proofs: editing a number in a fence (`Some(1.0)`->`Some(1.2)`) makes `FindALoopSpec` fail (5342 != 3629);
renaming a val in a narrative-only fence fails `Test/compile`. CI: `test.yml` also runs `sbt doc` (3.8.4 pin) so fence compile errors
fail PRs, not only pushes to `scala`.
Page edits this needed: choosing-a-complex `summarize` returns the component count too; find-a-loop's script gained `zeroLength`,
`shortLoop`; scaling-up's script gained `collapsedStream` (and lost a leftover `println`), its first narrative fence now carries the
imports, and `tdalab` was renamed `lab` like every other page.
Not covered: `all-ways-to-call.md` (no whole-script section) and MATLAB tabs (mirrored by `MatlabTabsSpec`, calling `TDA4j` with the same
options -- code in another language cannot be extracted and run here).
Failure messages point into the generated file, not the .md (line numbers differ).

## Bootstrap tabs: tried and reverted (same day)
Loading Bootstrap 5.3.3 (CSS+JS from jsdelivr, SRI hashes computed from the downloaded files) made the tab markup work
(`nav-tabs` + `data-bs-toggle`), but Bootstrap's global CSS (Reboot) restyles the whole scaladoc page: the sidebar's Docs/API
switcher overlaps the tree, content shifts left and loses its margins, the tab bar shows list bullets. It is all-or-nothing from
the CDN, so we kept the 25-line hand-rolled tabset (it also remembers the language across pages and shows both languages without JS).
