# WORKLOG: `requireValidTriangulation` repair for `HelixDelaunay` facet-multiplicity violations

2026-09-25. Full derivation, measurements, and rejected designs are in `.claude/DESIGN-helix-triangulation-repair.md`
(written and revised across the investigation, not retroactively). This worklog is the narrative summary.

## What shipped

`HelixDelaunay(pts, seed, requireValidTriangulation = true)` (`alpha/AlphaShapes.scala`). Off by default (zero
behavior change to every existing call site). When on, and `compute()` produces a facet-multiplicity violation
(`FastAlphaHomologyContext`'s own precondition -- more than 2 top simplices independently claiming one facet),
runs `HelixDelaunay.repairByJitterRetriangulation`:

1. Nudge exactly the vertices involved in a violation by a small random perturbation.
2. Re-run `HelixDelaunayBuilder` -- the same already-tested global algorithm -- on the full (mostly unperturbed)
   point set. No manual "gluing" of a local patch: the builder's own global frontier walk does that implicitly.
3. Recompute every resulting simplex's circumsphere from the ORIGINAL, un-nudged coordinates, so the
   perturbation never reaches a real filtration value.
4. Check two things, not one: (a) no facet has `>2` claimants, and (b) `hasNoInteriorVoid` -- the full unfiltered
   candidate complex's `H_{d-1}` is trivial, since a genuine Delaunay triangulation's convex hull is convex,
   hence contractible. Widen the jitter set and retry (bounded attempts) if either fails; throw a named,
   actionable exception if all attempts are exhausted.

`FastAlphaHomologySpec` carries two regression fixtures (`d=2` and a real `d=3` point cloud found by the stress
sweep itself, not hand-built) confirming the repaired stream's `FastAlphaHomologyContext` barcode agrees with
the naive engine's own barcode on the identical repaired stream.

## Why it took four designs

Requested by the project lead with the exact algorithm delegated ("pick one"). The premise in the request itself
(that `HelixDelaunay` builds one fat degenerate simplex for cospherical points, the way `AlphaShapeDQP` does) was
checked first and found wrong -- `handleCosphericalPoints` already does local pulling-triangulation. Tracing the
actual failure (three top simplices independently claiming one facet, via temporary instrumentation, reverted
before finalizing) found a different, more specific mechanism: `HelixDelaunayBuilder`'s own already-documented
near-tie order-dependency, not the textbook cospherical-fat-simplex hazard.

1. **Coning** the discarded conflict region from an arbitrary apex (the request's own literal framing): worked
   through by hand against the traced failure; found the discarded region's own boundary has degree-3 vertices
   (not a simple cycle), so naive coning double-counts facets. Rejected before implementation.
2. **Pruning** each over-claimed facet to its two smallest-circumradius claimants, discarding the rest with no
   replacement: implemented, provably cannot re-introduce a `>2`-claimant violation. But discarding a top
   simplex without replacement can punch a genuine interior hole through the mesh, which
   `FastAlphaHomologyContext`'s single-`∞`-sentinel dual-graph technique can't see -- confirmed empirically (a
   missing essential `H_1` bar on the pinned fixture) via a standalone diagnostic script, not just argued.
   Reverted in full.
3. **Diagonal/bistellar flip** (investigated, not implemented): the project lead's own follow-up proposal
   ("start with the leftmost k vertices of the void...") prompted a direct geometric check of the traced
   failure, which found it is not a 3-way tie at all -- it decomposes into one genuinely opposite-side triangle
   plus a textbook 2D Delaunay diagonal flip (two triangulations of one convex quadrilateral), generalizing to a
   Radon-partition bistellar flip in higher dimension. Promising, area/topology-preserving by construction, but
   real, unattempted computational geometry (robust Radon partitions, handling `>2`-claimants-on-one-side cases)
   -- set aside as a properly-scoped future project rather than rushed.
4. **Jitter + global recompute** (the project lead's own next proposal: "jitter and recalculate for just the
   void points and then glue back?"): implemented WITHOUT the risky manual "glue back" step, by re-running the
   whole already-tested builder instead of hand-splicing a local patch. Validated clean at `d=2` (316/316 hit
   violations in a 20000-trial targeted sweep). Failed at `d=3`: a real ~10.5% disagreement rate (656/6272) in
   an identically-structured sweep -- the facet-count self-check alone was insufficient. Root-caused via the
   project lead's own direct challenge to an initial "engine limitation" hypothesis ("Delaunay should fully
   triangulate the convex hull -- shouldn't be a possibility of interior voids. Is the naive engine struggling
   here?"), which was correct: a direct tetrahedra-volume measurement confirmed a real ~33% shortfall on the
   failing case, meaning the repair itself -- not `FastAlphaHomologyContext` -- was silently producing an
   incomplete triangulation. Fixed with the `hasNoInteriorVoid` check described above; re-validated on the SAME
   `d=3` sweep that found the 656 disagreements (now 6272/6272 clean) plus an independent 5000-trial sweep
   (1585/1585).

## Validation

`sbt clean test`: 572 examples, 0 failures (up from 571 -- the new `d=3` regression fixture).
`scalafmtCheck`/`scalafmtSbtCheck`: clean. `mimaReportBinaryIssues`: clean (no previous artifacts configured).
Two independent 20000-trial stress sweeps (near-cospherical point clouds, deliberately biased to trigger the
violation far above its natural rate) at `d=2` and `d=3`, plus a third 5000-trial `d=3` sweep: zero barcode
disagreements across every genuinely-hit violation in all three. All diagnostic/stress scripts were throwaway
(`Test/runMain`, deleted before finalizing), per this codebase's own convention.

## Known gaps, stated plainly

- **Not validated at `d>=4`.** `HelixDelaunay`'s own construction is already documented as unreliable there for
  unrelated reasons (near-cospherical order-dependency, ~1/170 at ambient dimension 4); this repair inherits
  that gap rather than adding a new one, and simply has no evidence behind it at that dimension.
- **Not threaded through `matlab.TDA4j`/`cli`.** This session's "finalizing a user-visible capability" checklist
  (CLAUDE.md's own session-practices section) has four surfaces; only the core Scala capability and its test
  coverage are done. `matlab.TDA4j`'s `engine="fast-alpha"` dispatch, `cli.TDA4jCLI`/`TDA4jConf`'s 1:1 mirror,
  and the developers-guide/user-guide docs still need a `requireValidTriangulation`-equivalent option threaded
  through -- left as an explicit follow-up, not silently dropped.
