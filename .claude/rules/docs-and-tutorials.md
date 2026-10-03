---
paths:
  - "_docs/**"
  - "_layouts/**"
  - "sidebar.yml"
  - "build.sbt"
  - "src/test/scala/org/appliedtopology/tda4j/tutorial/**"
  - ".github/workflows/docs.yml"
  - ".github/workflows/release.yml"
---

# The docs site, tutorial pages and language tabs

Loads when you work on docs, the tutorial specs or `build.sbt`. Project-wide rules are in `.claude/CLAUDE.md` (the Scala 3.8.4 docs pin is under "Commands").

**Docs site is pure scaladoc** (Laika/Paradox fully removed; `WORKLOG-laika-migration.md` and
`WORKLOG-docs-site-fixes.md` are history only). Pages are Markdown in `_docs/` (front matter `layout: main`,
`_layouts/main.html`), navigation in `sidebar.yml`, all configured through `Compile / doc / scalacOptions` in
`build.sbt` (`-siteroot`, `-project-logo`, `-quick-links`, `-scastie-configuration`, ...). **No Laika directives**
(`@:snip`, `@:callout`, ...) — use fenced code and blockquotes; **every Scala fence is compiled** by scaladoc's snippet compiler
(`"-snippet-compiler:compile"` in `build.sbt`), each fence independently — so each needs its own imports and data
(no shared prelude). A fence that only restates a source declaration (`trait RingModule`, `opaque type ...`) is marked
```` ```scala sc:nocompile ````; **`scala 3 nocompile` is silently ignored** (the info string must be `scala
sc:nocompile`). A failing snippet fails `sbt doc` with page:line. `WORKLOG-doc-snippets-compile.md`. Cross-links use
scaladoc's `[[org.appliedtopology.tda4j.Foo]]`/relative `.md` links.

**Tutorial pages** (`_docs/tutorials/`, `WORKLOG-tutorial-pages.md`): compute first, write second — run the code, read the output, then write
the prose around what you saw; if a dataset does not show the intended effect, change the dataset, never the claim. **The docs ARE the tests** (`WORKLOG-tutorial-docs-as-tests.md`): `build.sbt`'s `Test / sourceGenerators` copies each page's
`## The whole script` fence (the first `scala` fence after that heading) into a generated `object <Page>Script` (package `tutorial`) and
joins the page's `scala sc:nocompile` narrative fences into a never-called `<Page>Narrative.narrative()`, so a drifted narrative fence fails
`Test/compile`. `src/test/.../tutorial/<Page>Spec` asserts every quoted number on `<Page>Script.<val>` -- never re-type page code in a spec;
if a spec needs a value the script doesn't define, add that `val` to the page (the narrative should show it) or compute it in the spec from
exposed values. The first narrative fence must carry the page's imports (the narrative compiles alone). `sbt doc` (CI `test.yml` runs it,
with `TDA4J_SCALA_VERSION=3.8.4`) compiles every fence but runs none; the generated objects are what execute. Not covered:
`all-ways-to-call.md` (no whole-script section; `AllWaysToCallSpec` still mirrors it by hand) and MATLAB tabs (`MatlabTabsSpec`, below).
Narrative fences are `scala sc:nocompile` (they share values, and the snippet compiler compiles each fence alone), and each page ends
with a "whole script" fence that IS compiled by the docs build. Shared point clouds live in `_docs/tutorials/data/`, written by the seeded `tutorial/TutorialData` (change the generator, run
`sbt "Test/runMain org.appliedtopology.tda4j.tutorial.TutorialData"`; `TutorialDataSpec` guards drift). Style (Li Haoyi's "easy"): the page's imports are `scala.language.experimental.modularity` and
`org.appliedtopology.tda4j.*` (+ `.sset.*` for simplicial sets), and the computation is the `Persistence` verb returning a
`PersistenceDiagram` (`dim`, `longest`, `significant()`, `longerThan`); reach for a stream + engine only where the page is
about them (cursor, engine choice), and for a lab (`import TDAlab.F17.{*, given}`) only where hand-written chain algebra
is the point. No implementation history, worklog pointers or "we fixed X" in pages -- state what the library does.
`barcodeAt(f)` at an intermediate `f` is safe since the query-contract fix (`rules/engines.md`); public API only (a fence naming a `private[tda4j]` class or a test fixture
fails `sbt doc`); no timings in prose. `Map[G, Fp]` equality compares raw representatives (-1 vs 1 over F_2 differ): compare cochains with
`CupProduct.isCoboundary`, never `==`. Tutorial specs add ~90 s to `testFull`.
**Language tabs** (`WORKLOG-tutorial-tabs.md`): where `matlab.TDA4j` supports the task, show the code in a `<div class="tabset">` with
`<div class="tab" data-lang="Scala">` / `"MATLAB"` children (always in that order), a BLANK LINE between each HTML line and the fence
(otherwise the strict markdown parser eats it). Every MATLAB tab needs a spec calling `matlab.TDA4j` directly (not `FullBarcode`)
with the same options, asserting the numbers the tab quotes; the facade hides bars <= 1% of the enclosing radius, so quote
`hiddenCount()` or pass `minPersistence 0`. MATLAB syntax/marshalling are never run here -- say so.
