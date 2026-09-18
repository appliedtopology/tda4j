# Package reorganization: flat `tda4j` -> subpackages

Point-in-time record of splitting the previously-flat `org.appliedtopology.tda4j` package (28 source files,
~7,370 lines, plus `matlab/`) into subpackages. See `CLAUDE.md`'s "Package layout" section for the final,
maintained state; this worklog is the derivation and is not retroactively updated as the code evolves further.

## Why, and the plan that was agreed first

The user asked to "plan out a sensible selection of subpackages" for a codebase that had grown sprawling.
Before writing any code, surveyed the existing flat layout (line counts per file, cross-file import patterns)
and cross-checked the proposed grouping against `src/main/paradox/developers-guide/architecture.md`, which
already used almost exactly this taxonomy in prose ("the algebraic core", "Complex construction: streams",
"`Barcode.scala`: representing the output") — strong evidence the cut was natural rather than invented for
this session. Presented the plan (7 subpackages + root + existing `matlab/`) with an explicit cost/risk callout
before touching anything, and the user chose "full reorg now, one package at a time."

The originally-planned `util` bucket (for `UnionFind.scala`/`PrintingHelper.scala`) didn't survive contact with
the actual file contents: `UnionFind.scala` also defines `Kruskal`, an MST/cycle-basis algorithm over
`FiniteMetricSpace` that produces `Chain[Simplex[T], CoefficientT]` and is consumed by `Homology.scala` and
`RipserStream.scala` — not a generic, algebra-free utility at all. Rather than force a bucket that didn't fit,
the whole file went to `streams` instead (its real dependency), and `util` was dropped. `PrintingHelper.scala`
turned out to already be its own nested `package unicode` (like `Barcode.scala`'s existing `package barcode`) —
a precedent that had already established "nested package declaration, file physically flat" as a working
pattern in this codebase before this session.

## Order and method

Moved packages in dependency order (leaves first): `unicode` (trivial, zero real consumers) -> `algebra` ->
`cells` -> `streams` -> `homology` -> `barcode`/`alpha` -> a final pass physically relocating all ~33 test spec
files to mirror the new main-source layout. After each package: `git mv` the files, add a nested `package <name>`
line, then **compile-driven discovery** of consumers — `sbt compile`, read the "not found" errors to find every
file that needs an import (rather than grepping for every symbol name by hand, which is error-prone for a
typeclass-heavy Scala 3 codebase with dozens of extension methods and givens), add a blanket
`import org.appliedtopology.tda4j.<pkg>.{given, *}` to every consumer, recompile, then run the FULL test suite
(not just compile) before moving to the next package — compiling clean doesn't rule out a silent
extension-method/given resolution change, only behavior does.

Every one of the 6 package moves (`algebra`, `cells`, `streams`, `homology`, `barcode`, `alpha`) plus the final
test-file relocation produced the **identical** test baseline: 204 total, 0 failed, 0 errors, 199 passed, 5
skipped, 1 pending — no regressions at any step. Final verification was a `sbt clean test` from scratch (not
just incremental) to rule out anything masked by Zinc's incremental compilation state, plus
`scalafmtCheck`/`scalafmtSbtCheck`/`mimaReportBinaryIssues` (mima is a no-op — `mimaPreviousArtifacts := Set.empty`
— but confirmed clean anyway).

## Two real Scala 3 gotchas hit, both fixed, neither the ones CLAUDE.md already documented

1. **`import pkg.*` does not import `given` instances.** The first blanket-import pass (`algebra.*`) compiled
   with 484 errors, nearly all "given instance ... was not considered because it was not imported with
   `import given`". This is standard Scala 3 semantics (given instances need `import pkg.given` or
   `{given, *}`), not the companion-object-vs-wildcard-import hazard CLAUDE.md already documents from the
   cubical-complex session — a different failure mode that happens to look similar at first glance. Fixed by
   switching every blanket import to `import org.appliedtopology.tda4j.<pkg>.{given, *}`, which dropped the
   error count from 484 to 12 in one pass.
2. **A nested `package X` declaration must immediately follow the enclosing `package` line(s), with nothing
   (not even a doc comment) in between, unless using the brace/colon form.** The mechanical import-insertion
   script found the *last* `package` line before a run of blank lines and inserted there — which happened to
   land an `import` between `package org.appliedtopology.tda4j` and a doc-commented `package barcode` in
   `Barcode.scala`, since the doc comment breaks the "contiguous package lines" heuristic the script used.
   Produced a clear syntax error (`Nested package statements that are not at the beginning of the file require
   braces or ':'`), fixed by hand-placing the import after `package barcode` instead. Worth remembering for any
   future scripted edit near this codebase's `barcode`/`unicode`-style nested-package files.

Neither of the two *previously*-documented CLAUDE.md hazards (opaque-type transparency scoped to a whole file;
a companion-object extension losing to a same-named stdlib extension reachable via wildcard import) was
actually triggered by this reorg — both are properties of file boundaries and import scope shape, which this
reorg deliberately preserved (no file was split or merged; every blanket import is a superset of what was
already implicitly visible in the old flat package). Checked for both anyway via the full-test-suite-after-
every-package discipline above, since "should be fine in theory" and "confirmed fine" are different claims and
this codebase has a specific, recorded history of exactly this class of bug.

## One genuine pre-existing cross-dependency surfaced, not introduced

`streams/CubicalStream.scala` extends `CellularHomologyContext` directly (`CubicalHomologyContext` is defined
right there, one-line wrapper). This means `streams` has a real dependency on `homology` for that one file —
already true before this session, just invisible when everything shared one package. Not treated as a bug or
a layering violation to fix; documented in `CLAUDE.md`'s package-layout section as-is.

## What was deliberately not done

- **No import pruning.** Every blanket `{given, *}` import landed in every file it was applied to, whether or
  not that specific file actually needed it (e.g. `package.scala` picked up an unused `alpha` import). No
  `-Wunused` flag is set in `build.sbt` and CI's lint job only checks formatting, so these are silently harmless
  — but a future session wanting tighter per-file imports would need to do that pass separately; it wasn't
  attempted here given the mechanical scale (~90 files touched) and zero functional benefit.
- **No splitting of any file.** `Chain.scala` (defines `Cell`/`Cocell`/`OrderedCell`/`OrderedBasis` *and*
  `Chain` *and* its `RingModule` instance) and `UnionFind.scala` (defines both the generic `UnionFind` class and
  the TDA-specific `Kruskal`) are both legitimately mixed-concern at the file level, but splitting a file is a
  different, larger kind of change than a package reorg and was out of scope for this session.
- **No rewrite of CLAUDE.md's historical narrative sections** (the detailed bug-fix arcs under "Persistent
  homology", "Alpha complex: DQP vs Helix", etc.) — those describe what happened and why at the time, and
  remain accurate as history regardless of which directory a file now lives in. Only the one exact
  package-qualified example (`sbt testOnly org.appliedtopology.tda4j.HomologySpec`) needed a literal fix, since
  it's a copy-pasteable command, not narrative.
