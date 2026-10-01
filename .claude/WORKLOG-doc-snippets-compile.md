# Worklog: make every doc snippet compile (2026-10-01)

Goal: `-snippet-compiler:compile` on in build.sbt, `sbt doc` (TDA4J_SCALA_VERSION=3.8.4) clean.
Plan: enable flag -> read the compiler's failure list -> fix sequentially (setup lines / hidden setup / `nocompile`
for genuinely illustrative blocks) -> re-run until clean. Only one sbt at a time.

## Log
- Branch reset to origin/scala (PR #31 merged). 29 scala fences in 15 pages (28 `scala 3`, 1 `sc:compile`).
- Flag enabled as the bare `-snippet-compiler:compile`.
- Baseline with flag on: `sbt doc` fails ("DottyDoc Compilation Failed"); 13 of 15 pages with Scala fences fail
  (sparse-vietoris-rips 10 errors, quickstart 7, scala3-primer 5, simplicial-sets 4, dowker 4, user-guide/index 4,
  architecture 4, 2 each in cubical/cech/fast-cubical/fast-alpha, 1 each in alpha-complexes/developers alpha-complex).
  Only input-output.md and tutorials/index.md already pass. Compiler reports page:line, so no manual triage needed.
- Decision (per user, after a delegation discussion): do this sequentially myself; no sub-agents (one sbt at a time).
- Pass 1 edits: declaration-only fences -> `scala 3 nocompile` (alpha-complex 1, architecture 1+3, primer 4); usage fences made self-contained (imports + tiny data). Next: run doc, fix residuals.
- Pass 1 compiler residuals were real doc drift: FiniteSimplicialSet ctor (explicit Ordering arg is wrong; it is a trailing using clause), FilteredSimplicialSetStream needs [G] ascription, HelixDelaunay needs a given Epsilon, AlphaShapes.apply signature in developers-guide/alpha-complex.md lacked requireValidTriangulation and the Epsilon default. Info string for illustrative fences must be `scala sc:nocompile` (`scala 3 nocompile` is silently ignored).
- RESULT: `TDA4J_SCALA_VERSION=3.8.4 sbt doc` is clean with `-snippet-compiler:compile` on. Negative test (typo in the
  Cech snippet) made the build fail at cech-complexes.md:16, so the check is live, not a stale cache hit.
  Final state: 29 fences; 8 are `sc:nocompile` (pure declaration restatements: alpha-complex 1, architecture 2,
  scala3-primer 4 (+ none elsewhere)); the rest compile. No sub-agents used. Remaining warning: scaladoc's own
  "Option -classpath was updated" (unrelated).
- Lesson: doc drift found by this was real (4 items above), so keep the flag on; add a new fence => it must compile.
