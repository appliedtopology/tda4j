# Generic persistent cohomology (2026-09-22)

Point-in-time record of the arc that shipped `CellularCohomologyContext`. The design itself — the key idea,
what carries over from `RipserCohomologyContext`/`PackedRipserCohomologyContext` and what deliberately
doesn't, the advisor review, and the two corrections the project lead made to the original plan (drop
apparent pairs; fully generic `FiltrationT`; own file) — lives in `.claude/DESIGN-generic-cohomology.md`,
written and reviewed before any code. This worklog covers what happened once implementation actually started:
two real findings, both in test design rather than the engine itself, both caught by writing a test that
turned out to assert something false, not by inspection.

## What shipped

`homology/Cohomology.scala`: `CellularCohomologyContext[CellT: OrderedCell, CoefficientT: Field, FiltrationT:
Ordering]`, mirroring `CellularHomologyContext`'s genericity exactly. `persistentCohomology(stream)`
materializes the whole stream (`stream.iterator.toVector.groupBy(_.dim)` — no `StratifiedCellStream`/
`iterateDimension` dependency, a drop-in for any `CellStream`), builds each dimension's coboundary block by
inverting `boundary[CoefficientT]` on the next dimension up, runs Algorithm 1 against it via `Chain.reduceBy`
(no `fallback` — there's no apparent-pairs substitution to wire in), discards the block, and moves on. No
`maxDim` parameter anywhere. `coboundaryOfChain(chain, cofacets)` is a second, small public method — the
verification entry point that makes a returned representative checkable.

Finalization (per the new `CLAUDE.md` "Session practices" rule this arc itself prompted — see the parent
conversation): `matlab.TDA4j` and `cli.TDA4jCLI` both gained `engine="cohomology"`, valid for `complex=vr`,
`complex=alpha`, `complex=cech`, and `computeFromCubicalImage`/`computeFromImage`. `developers-guide/
persistence-engines.md` gained a sixth numbered section; `user-guide/index.md`'s options table, engine-choice
table, and representative-caveat prose were all updated. `complex=simplicialset` was explicitly NOT added
anywhere — a real scope discovery, not an oversight: no MATLAB/CLI entry point for `FiniteSimplicialSet`
exists at all, for either homology or cohomology, and building one needs a way to encode a user-supplied
simplicial set's degeneracy/face data through MATLAB's primitives-only bridge, a separate design question.

Also in this arc, on direct instruction, before the engine itself was written: `Cocell`/`OrderedCocell`
(`Chain.scala`) were removed outright, not left as dead scaffolding — confirmed unused anywhere in the
codebase first (zero non-declaration hits across `src/main`, `src/test`, and the docs), then deleted along
with every doc reference (`CLAUDE.md`, `architecture.md`, `class-diagrams.md`'s mermaid diagram). They modeled
coboundary as intrinsic to a cell, the wrong shape for what this whole arc's key idea depends on (coboundary
is extrinsic — it depends on which higher-dimensional cells exist in the ambient complex).

## Finding 1: "every bar" was wrong — only essential bars are cocycles

The first test written asserted `coboundaryOfChain(rep, cofacets).isZero()` for every bar returned by
`persistentCohomology`, across VR/Cube/`FiniteSimplicialSet`/Cech. All four failed immediately.

Root cause, once traced through: Algorithm 1's invariant is `d(V_j) = R_j` throughout the reduction — `R_j` is
the fully reduced column, `V_j` its own tracked V-column. `R_j` is zero exactly when the bar is essential; for
a finite bar, `R_j` is the nonzero reduced pivot chain that made it finite in the first place, so `d(V_j)` is
that same nonzero chain, not zero. This isn't a bug — `RipserCohomologyContext`'s own test suite already
encodes exactly this ("Essential representatives are genuine cocycles ... including at the top dimension",
`RipserCohomologySpec.scala`), with a comment explicitly warning that asserting `isZero()` on a finite bar's
representative "would be a test bug, not a property of the algorithm." The new engine's first test simply
hadn't been checked against that existing precedent before being written.

Fixed by scoping the check to `bars.filter(_.upper == PositiveInfinity[Double]())` — `essentialRepsAreCocycles`
in `CohomologySpec.scala` — matching the established pattern exactly. All four cell types pass once scoped
correctly. The design doc and `Cohomology.scala`'s own doc comment, which had both said "every bar" in the
same overstated way, were corrected in the same pass — a finite bar's V-column is described as "a valid
representative — it witnesses the class on the sub-level set strictly before the bar's death... just not a
cocycle over the whole complex," not merely as an exception to wave past.

## Finding 2: representative content need not match RipserCohomologyContext, even bar-for-bar

A second test asserted something stronger: that matching bars' representative *chains* (not just their
birth/death values) would agree exactly between `CellularCohomologyContext` and `RipserCohomologyContext` on
the same VR complex. The reasoning, written into the test's own comment before it was run: both engines'
`cohomologyOrdering` reduce to the same tie-break (colex) when filtration values tie, and by the
canonical-reduced-matrix argument this codebase already relies on elsewhere (`unionFindDim01`/`vcolOf` — "a
fixed total order over a fixed cell set determines a unique reduced boundary matrix regardless of which
algorithm computes it"), a shared tie-break should force identical V-columns.

This failed on the very first randomly-generated point cloud tried — not a rare edge case. Debugged directly
(a throwaway spec printing both engines' bars and representatives side by side on the small, deterministic
`threePointLine` fixture, since the sbt-hosted REPL wasn't usable in this environment — `jline`
`NoSuchMethodError` under batch/non-interactive `Test/console`) rather than reasoned about further in the
abstract. The two engines assign *different specific vertices* to the same `(dim, birth, death)` triple: on
`threePointLine`, `CellularCohomologyContext` pairs vertex 1 with the bar dying at `1.0` where
`RipserCohomologyContext` pairs vertex 0 with it.

Root cause, worked out by hand from each engine's own comparator: VR's three vertices are *always* tied at
`fv = 0` (every vertex is born at filtration value 0, by convention, for every point cloud) — dimension 0 is
never NOT tied. `RipserCohomologyContext.compareFvThenIndex` breaks fv ties so a LARGER combinatorial index
sorts as smaller/older. `CellularCohomologyContext.cohomologyOrdering` (`Ordering.by(fv).orElse(stream.
filtrationOrdering)`) falls through, on a tie, to `stream.filtrationOrdering`'s own tie-break — colex, but
ascending, i.e. SMALLER index sorts as smaller — the OPPOSITE direction. **Verified directly, not left as a
read-through of the two comparators' source**: a throwaway debug spec reconstructed each engine's exact
processing order over `threePointLine`'s three tied vertices (`cellsAtD.sorted(using cohomologyOrdering.
reverse)` for the generic engine, `currentLevel.sorted(using diameterSimplexOrdering.reverse)`'s equivalent for
Ripser) and printed it: generic processes `[2, 1, 0]`, Ripser processes `[0, 1, 2]` — genuinely, measurably
reversed, and consistent with the specific vertex/bar pairings (1) above already showed. Both are legitimate,
individually consistent total orders; they just aren't the *same* order, so the premise that they must agree
was false from the start. This is not a bug in either engine: which specific tied cell gets reported as dying at which death
time is tie-order-dependent by design whenever cells share a birth time — the same point
`HomologyFixtures.tetrahedronBoundaryDegenerateExpected`'s own comment already makes for homology's elder
rule, just never previously connected to cohomology's representative assignment specifically. The
canonical-reduced-matrix argument's actual claim is narrower than the test's premise: it guarantees a *unique*
answer for a *single* fixed total order, never that two independently-chosen, each individually valid, total
orders must agree with each other.

The test was removed, not patched around — replaced with a comment recording the investigation and why the
claim isn't sound, so a future session doesn't re-attempt the same cross-check without knowing it was already
checked and found false. `CohomologySpec.scala`'s own class doc, and the corresponding claim in
`DESIGN-generic-cohomology.md`'s validation-plan item 1 (struck through, corrected in place rather than
silently rewritten), were both updated to match: bar VALUES are cross-validated against
`RipserCohomologyContext` (two tests, both green — calibration example and 100+ random point clouds);
representative content is not.

## A third, smaller thing worth recording: alpha-complex construction nondeterminism in the MATLAB facade test

Not a finding about the new engine — a repeat of an already-documented class of test-writing hazard.
`TDA4jSpec.scala`'s first `complex=alpha`, `engine=cohomology`-vs-`engine=naive` test called
`TDA4j.computeFromPoints` twice independently (once per engine), each call constructing its own fresh
`Alpha(pts, alphaBackend)` internally. Two of eleven bars disagreed at the last ULP or two
(`0.6249999999999997` vs `0.6249999999999998`, etc.) — exactly the "HelixDelaunay's own filtration-value
computation touches a `mutable.Set` whose iteration order isn't guaranteed identical between two independent
constructions of 'the same' complex" nondeterminism `AlphaComplexSpec.scala`'s own comment already documents,
just hit here for the first time through the MATLAB facade specifically (which has no way to share one
construction across two calls the way a direct-Scala test can). Fixed by switching that one test to the same
tolerance-based comparison `TDA4jSpec`'s own `field=R`-vs-`field=Z` test already uses (`< 1e-9`), not by
touching alpha construction itself.

## Verification

`sbt test`: 349 total, 339 passed, 10 skipped, 1 pending — the established baseline (10 skipped/1 pending
unchanged from before this arc) plus every new example across `CohomologySpec` (10), `TDA4jSpec` (+7),
`CLISpec` (+1). `sbt compile`/`Test/compile` clean throughout. `sbt makeSite` builds cleanly, with the new
`persistence-engines.md`/`user-guide/index.md` content confirmed present in the built HTML output (not just
"the build didn't fail"). `sbt scalafmtCheck scalafmtSbtCheck` initially failed on `TDA4j.scala` (the MATLAB
dispatch edits weren't run through `scalafmtAll` before that check) — `scalafmtAll` reformatted it plus three
other already-edited files (cosmetic reflow only; `git diff` after confirms no semantic change), then
`scalafmtCheck` passed clean and the full suite was re-run to confirm nothing shifted. `sbt
mimaReportBinaryIssues` reports "mimaPreviousArtifacts is empty, not analyzing" — a pre-existing, pre-1.0
no-op for this project (no published baseline artifact to compare against yet), not a finding from this arc.

**Re-verified after the advisor-review follow-up above** (the two new `require`s and the two new F3
`CohomologySpec` examples): `sbt test` — 351 total, 341 passed, 10 skipped, 1 pending (the +2 over the prior
349/339 is exactly the two new F3 examples; nothing else shifted, confirming the new `require`s changed no
existing behavior). `sbt scalafmtAll` followed by `scalafmtCheck scalafmtSbtCheck`, run in that order after
the last source edit in this follow-up, came back clean — the real evidence that `scalafmtAll` had nothing
left to reformat, not (an earlier draft of this note wrongly claimed) anything inferable from `git status`'s
tracked/untracked distinction, which says nothing about whether a run touched a file. `sbt
mimaReportBinaryIssues` unchanged no-op.

## Follow-up: an advisor review of the finished engine found four real gaps, all closed

Run after the arc above was otherwise complete (finalization done, `sbt test`/scalafmt/mima all clean), on the
standing "call advisor before declaring done" practice. Four findings, all addressed in the same pass:

1. **`coboundaryOfChain` had no contract enforcement.** It silently computed a partial, meaningless answer for
   a caller passing a non-homogeneous `chain` or a `cofacets` band at the wrong dimension — every existing
   caller (internal and test) happens to always pass a single bar's own-dimension representative alongside
   exactly `cellsByDim(bar.dim + 1)`, so this never fired, but nothing enforced it. This sits on a genuine
   public-method boundary a caller can actually violate, so it clearly earns its place. Fixed with two
   `require`s: one on `chain`'s own homogeneity, one per-`cofacets`-element checking `cof.dim == chainDim + 1`,
   both raising immediately with a message naming the actual mismatch rather than continuing to compute
   nonsense.
2. **`persistentCohomology` never checked dimension-contiguity.** `cellsByDim.getOrElse(d+1, Vector.empty)`
   treats a genuinely missing dimension the same as an empty one. This is the weaker of the two guards, and
   presented as such, not equally warranted: unlike (1), it's belt-and-braces on an invariant already
   established as structurally impossible for every stream this class targets (a `d`-cell forces its own
   faces, hence lower dimensions, to exist) — CLAUDE.md's own "don't validate scenarios that can't happen"
   principle would defensibly argue against it. Kept anyway, since a violation would otherwise silently leave a
   whole dimension's coboundary block empty rather than fail loudly. Fixed with a `require(cellsByDim.keySet ==
   (0 to topDim).toSet, ...)` right after `topDim` is computed.
3. **No coverage over a signed field, anywhere.** Every existing `CohomologySpec` example used `Double`; every
   `TDA4jSpec` facade test defaulted to F2 (`field=Z`, `prime=2`), where a sign error is invisible by
   construction (`-1 == 1`). Nothing had ever exercised this engine's own coboundary-is-transpose-of-boundary
   construction over F3, or on a torsion-sensitive fixture. Closed with two new `CohomologySpec` examples: RP²
   (`SimplicialSetFixtures.realProjectiveSpace(2)`, this codebase's own established sign-discriminating fixture
   — `H_1=H_2=F2` over F2, both `0` over F3) over `f3.Fp`, cross-validated against `CellularHomologyContext`
   AND against the independently hand-derived expected barcode (`(0,0,∞),(1,0,0)`, no essential `H_1`/`H_2`),
   plus the existing `essentialRepsAreCocycles` check (generalized from `Double`-only to generic
   `CoefficientT`/`FiltrationT` to allow reuse) run over the same F3 fixture. Both passed on the first run —
   real evidence signs are inherited correctly through the transpose, not merely a plausible claim.
4. **Finding 2's "opposite direction" claim was inferred from reading two comparators' source, not measured.**
   The conclusion itself (the two engines' tie-breaks disagree) was never in doubt — the specific vertex/bar
   pairings observed in Finding 2's original debug run already demonstrated it empirically — but the *exact
   mechanism* ("opposite direction") had only been argued from `compareFvThenIndex`'s and `cohomologyOrdering`'s
   source, the same shape as this project's own past "~48%" misattribution (`WORKLOG-ripser-profiling.md`).
   Closed with a second throwaway debug spec, printing each engine's raw processing order over `threePointLine`'s
   three tied vertices directly: generic `[2, 1, 0]`, Ripser `[0, 1, 2]` — confirmed genuinely reversed, matching
   the original Finding 2 pairings exactly. `CohomologySpec.scala`'s own comment and this worklog's Finding 2
   section were both updated to cite this as measured, not merely reasoned through.

All four fixes verified: `CohomologySpec` (now 12 examples, up from 10), `TDA4jSpec`/`CLISpec` re-run clean
(the two new `require`s changed no existing behavior — every existing call site already satisfied both
preconditions).

## What's still open, explicitly not attempted in this arc

- A chunked/parallelizable cohomology engine (`CellularCohomologyContext`'s own `CellularPersistenceInChunksContext`-
  style counterpart) — real, natural, but a separate pass; see the design doc's own "Explicitly out of scope."
- `complex=simplicialset` MATLAB/CLI dispatch — blocked on a real, separate design question (how to encode a
  simplicial set's face data through MATLAB's primitives-only bridge), not something this arc could extend.
- Splitting every existing `Homology.scala` engine into its own file (a broader idea raised alongside this
  plan, recorded in the design doc's "Where this lives" section) — independent of this arc, not attempted.
- `sbt scalafmtAll`/`sbt mimaReportBinaryIssues` — run as part of this arc's own final verification pass (see
  the parent conversation for the actual results), not narrated here since neither found anything to report.
