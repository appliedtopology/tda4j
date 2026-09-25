# WORKLOG: HelixDelaunay higher-dimension issues (A and B fixed; C remains WONTFIX)

2026-09-25. Requested directly: "follow-up on the issues with Helix in higher dimensions. What are they? Can we
fix them?" followed by "let's fix A, B and D" (D already shipped this session, see
`WORKLOG-helix-triangulation-repair.md`), then, after B was first (wrongly) reported as sharing C's own
WONTFIX mechanism, "Indeed, the incomplete triangulation moves this from WONTFIX to a priority. What do we need
to do to fix this root cause?" This worklog covers A and B; both are now fixed and validated.

## The three issues, precisely

Found in `AlphaComplexSpec.scala`'s own doc comments (`AlphaCrossValidationSpec`, `AlphaComplexSpec`), not
previously root-caused in this much detail:

- **A** — `assert(validated.nonEmpty)` in `HelixDelaunayBuilder`'s bootstrap: a real crash, ~1-in-600 in the
  generator used to find it.
- **B** — a silently incomplete complex: the frontier walk can fail to discover a whole connected region of
  genuinely-Delaunay faces, with no exception at all.
- **C** — order-dependent disagreement with `AlphaShapeDQP` on near-cospherical clusters (~1/170 at ambient dim
  4, 20-30 points). The project lead: "I'm okay with C persisting as a WONTFIX issue."

## A: root-caused to three independent, additive mechanisms

Could not reproduce with uniform random point clouds (30000+ trials, both a plain `[-1,1]` sweep and the exact
`Gen.double`/`Gen.chooseNum` generators the original "1-in-600" figure came from) -- zero hits. A grid-like/
near-degenerate generator (points near integer grid positions, deliberately targeting the pattern an existing
code comment already flagged: "common for grid-like or otherwise partly-degenerate point clouds") reproduced it
readily: 2226/30000.

1. **Skipped-interior-point facets.** Traced to an exact geometric fact, not numerical noise: on a plain 3x3
   integer grid, the bootstrap's own affinely-independent-subset picker (when the hull-supporting hyperplane has
   more than `ambientDimension` coincident points) could pick two FAR points on a hull edge while skipping an
   interior point that lies exactly between them (verified directly: `(0,0)` and `(2,0)` chosen, skipping
   `(1,0)`). Proven that `(1,0)` is then unconditionally inside every circumcircle through the chosen pair and
   any third point, so the brute-force seed search can never succeed. Fixed by sorting candidates by distance
   from each candidate root before the greedy affinely-independent pick, and retrying the WHOLE brute-force seed
   search across every affinely-independent subset of the coincident-point set (not just one greedily-chosen
   one), ordered by increasing total pairwise span. Cut the sweep to 62/30000 -- real progress, not complete.
2. **A genuinely degenerate candidate handed to `Hyperplane.from`.** The remaining failures were seed-dependent
   on the SAME point cloud (195/201 seeds succeeded). Traced via targeted instrumentation (reverted before
   finalizing): the outer "find a hull-supporting hyperplane" loop had converged on a 5-point `startingSimplex`
   whose own affine rank was 3, not the required 4 -- these 5 points didn't even span a genuine hyperplane
   themselves. `Hyperplane.from`'s SVD-based normal extraction is under-determined for such input (there's a
   whole FAMILY of hyperplanes containing a more-degenerate-than-expected point set, not one), and the specific
   one it happened to compute had points strictly on BOTH sides -- not a supporting hyperplane at all -- yet the
   loop's own `lightPoints.isEmpty` check spuriously accepted it. Fixed by rejecting an affinely-degenerate
   candidate outright before it ever reaches `Hyperplane.from`, both for the initial `startingSimplex` and every
   reshuffled candidate in that loop (the same affinely-independent-pick technique as fix 1, applied one level
   earlier). Cut the sweep to 43/30000.
3. **Global coplanarity** (the project lead's own contribution mid-investigation, from memory of a previously
   unfixed issue: "if the entire point set is coplanar we should probably project points down to that plane and
   run the algorithm there"). Confirmed directly on the next failing case: 6 points nominally in R^5, but their
   own true affine rank was 4 -- the WHOLE point cloud was coplanar in a lower-dimensional flat than declared,
   so there is no genuine full-5D simplex to find at all, structurally, no matter which subset is tried. Fixed
   by checking the whole input's own affine rank up front and, if less than the declared ambient dimension,
   projecting every point onto an orthonormal basis of its own true affine span before construction -- exact,
   not approximate (an orthogonal projection onto the span containing every point changes no pairwise Euclidean
   distance among them), and a no-op for already-full-rank input. Cut the sweep to 23/30000.
4. **Tolerance mismatch.** The remaining cases had jitter around `1e-7` -- below this codebase's own
   `epsilon.epsilon` (`1e-5`) degeneracy threshold, but still "full rank" under
   `SingularValueDecomposition.getRank`'s own default tolerance (far tighter, machine-epsilon-scale), which
   every rank check in the file up to this point (including fixes 1-3 above) was still using. Replaced with one
   shared `rankAtEpsilon` helper, tied to `epsilon.epsilon`, used everywhere this file computes a rank. Cut the
   sweep to **0/30000**.

Re-ran the original uniform-random sweep after all four fixes: still 0/10000. `AlphaComplexSpec`'s own property
test, which previously excluded `"helix"` specifically for this bug (with a code comment explaining why
`pendingUntilFixed` was the wrong tool for a probabilistic failure), now includes it again and passes at its own
`minTestsOk = 2000`.

## B: initially misdiagnosed as sharing C's mechanism; corrected, then fixed

Initially suspected a separate cause (tried and ruled out, in order): `handleCosphericalPoints`'s own local
pulling-triangulation (traced -- never even invoked for the region in question); a wrong-hyperplane-orientation
in the main frontier loop's own facet handling (traced -- the facet in question was never even generated as a
`FrontierCase` at all, ruling out a cancellation-logic bug).

Direct trace on a real 13-point, ambient-dimension-4 example (found via the same stress sweep used for A) found
the actual mechanism: `AlphaShapeDQP` found 45 top simplices to Helix's 43 (not "a whole region missing," just 2
net). The missing ones (`{1,2,3,6,9}`, `{1,3,4,6,9}`, `{1,2,3,4,9}`) all share the 2-face `{1,3,9}`. Checking
Helix's own output: it found simplices around edges `{1,9}` (5 of them) and `{3,9}` (4 of them) but literally
none touching `{1,3}` -- and `addFrontierCase` was never even called with a facet containing both 1 and 3, so
this isn't a case being found-then-lost, it's a region never reached at all.

Traced back one more step: facet `{2,3,6,9}` (a genuine `(ambientDimension-1)`-face, so it should have AT MOST 2
cofaces) has (at least) two legitimately-empty-circumsphere candidate cofaces -- vertex 1 (`{1,2,3,6,9}`, DQP's
own pick, confirmed correct) and vertex 4 (`{2,3,4,6,9}`, what Helix found and validated). Once Helix commits to
the vertex-4 side, `visitedFacets` marks that facet permanently closed -- it is never reconsidered, regardless
of whether a later, more-correct candidate is discovered.

**This was first (wrongly) reported as the exact same mechanism as issue C, hence WONTFIX by default.** The
project lead pushed back directly: "the incomplete triangulation moves this from WONTFIX to a priority." Closer
investigation, prompted by that pushback, found the original diagnosis had conflated two genuinely distinct
outcomes of the same `visitedFacets` lock:

- **C-in-disguise**: the discarded side (here, the vertex-1 side) is reachable through some OTHER already-visited
  facet too, so Helix's own output, though different from DQP's, is still a complete, internally-consistent
  triangulation -- checked directly via a global self-consistency test (`H_{ambientDim-1}` of the full, unfiltered
  complex must be trivial, since a genuine Delaunay triangulation's convex hull is contractible; confirmed
  independently by `FastAlphaHomologyContext` agreeing with the naive engine on the resulting stream). Not a bug:
  a different, equally valid resolution of a real near-tie, exactly C's own accepted symptom.
- **Genuine B**: the discarded side is reachable NO OTHER WAY, so an entire local neighborhood (here, the three
  simplices sharing `{1,3,9}`, plus their own sub-faces) is permanently lost -- a real topological hole (nonzero
  `H_{ambientDim-1}` on the full complex), not a different-but-complete triangulation.

A 20000-trial classification sweep (uniform random, dim 2-4, n 5-14) found 8 "incomplete vs `AlphaShapeDQP`"
hits by the naive diff-based check alone -- but only **2 of the 8** were genuine voids by the
self-consistency check; the other 6 were C-in-disguise. (The ORIGINAL 13-point/`{2,3,6,9}` example traced above
turned out, on this closer check, to be C-in-disguise itself, not a genuine void -- both `{1,2,3,6,9}` and
`{2,3,4,6,9}` are fully valid empty-circumsphere simplices, and Helix's own output on that exact point cloud is
complete and self-consistent. It was still useful: it exposed a real, separate structural bug, below.)

**Two compounding bugs, both fixed, not one:**

1. **A structural exclusion bug in `handleCosphericalPoints`'s own facet-queue construction.** The queue used to
   be built as `frontierCase.facet.toSeq.map(fi => newDelaunaySimplex.simplex - fi)` -- but `frontierCase.facet`
   is exactly `newDelaunaySimplex.simplex` minus the complement vertex the main loop just added, so iterating
   `fi` only over `frontierCase.facet`'s own vertices can NEVER reproduce `frontierCase.facet` itself as one of
   the generated pairs. That facet -- the one this whole method exists to potentially re-examine for a second
   candidate -- was therefore structurally unreachable from this method's own local search, on top of already
   being locked out of the main frontier walk by `visitedFacets`. Fixed by iterating over EVERY vertex of
   `newDelaunaySimplex.simplex` instead (`AlphaShapes.scala`, `handleCosphericalPoints`).
2. **No empty-circumsphere check on the cospherical-cluster pull itself.** Even with fix 1,
   `handleCosphericalPoints` picks its next point via `spherepoints.filter(hyperplane.isLight...).headOption` --
   first-found, no check that the resulting simplex is actually a valid (empty-circumsphere) Delaunay simplex,
   and no joint consideration of competing candidates. This is the root mechanism, not separately fixed (fixing
   it properly needs the same joint near-tie/symbolic-perturbation redesign already out of scope for C) -- instead
   worked around at the `requireValidTriangulation` repair layer (see next section), which is the pragmatic
   choice the project lead's own earlier D fix already established for this class of problem.

**The fix: extend the already-shipped `requireValidTriangulation` jitter-and-recompute repair to trigger on
genuine voids too, not just facet-multiplicity violations.** `HelixDelaunay.interiorVoidVertices` (renamed and
extended from the boolean `hasNoInteriorVoid` the D fix introduced) now returns `Option[Set[Int]]`: `None` when
the full unfiltered complex is self-consistent, `Some(vertices)` -- the exact vertex support of the essential
`H_{ambientDim-1}` representative(s), extracted from `barcodeAt`'s own annotation chains -- when a genuine void
exists. `repairByJitterRetriangulation` now checks this on the RAW, unrepaired output (not just after an
initial facet-multiplicity-triggered retry), using the void's own representative vertices as a targeted jitter
seed when there's no explicit facet violation to seed from -- reusing the SAME "simulation of simplicity"
jitter-and-globally-recompute mechanism the D fix already validated, not a new algorithm.

## Validation

Fix 1 (facet-queue) validated by direct re-trace on the original 13-point example: `frontierCase.facet` (`{2,3,6,9}`)
is now reachable, though on that specific (C-in-disguise) example the walk still resolves it consistently either
way -- confirming the fix removes a real structural gap without changing outcomes on cases that were never
genuinely broken.

Fix 2 (extended repair) validated directly against a CONFIRMED genuine void, captured from the classification
sweep (13 points, ambient dim 4, `seed=7447`): `HelixDelaunay(pts, seed=7447L)` (raw) is missing 3 top simplices
vs `AlphaShapeDQP`; `HelixDelaunay(pts, seed=7447L, requireValidTriangulation=true)` -- `FastAlphaHomologyContext`
now agrees with the naive engine on the repaired stream (it did not before repair). A broader 20000-trial sweep
with `requireValidTriangulation=true` applied unconditionally (uniform random, dim 2-4, n 5-14, same self-
consistency check used to classify genuine voids above) found **zero** self-consistency failures across all 20000
trials (9 triggered the DQP-diff check; the repair correctly leaves the C-in-disguise cases alone -- confirmed by
their own self-consistency already holding pre-repair -- and resolves every genuine void it encounters).

`sbt clean test`: 578 examples, 0 failures. `scalafmtCheck`/`scalafmtSbtCheck` clean. `mimaReportBinaryIssues`
clean (no published baseline to compare against). All diagnostic and stress scripts were throwaway
(`Test/runMain`, deleted before finalizing), matching this codebase's own convention; all temporary trace
`println`s were reverted before finalizing.

## Known gaps, stated plainly

- **The underlying near-tie mechanism itself is still unfixed, by design.** Fix 2 above is a repair layer, not a
  fix to `handleCosphericalPoints`'s own greedy, no-validity-check pull -- a genuine fix there needs the same
  joint near-tie detection (symbolic perturbation, or an equivalent canonical tie-break shared with
  `AlphaShapeDQP`) already on record as a deliberate, out-of-scope algorithm redesign for issue C. The
  practical difference from before: every KNOWN symptom of that mechanism (crash-free bootstrap via A's fix,
  genuine incompleteness via this fix, and C's own order-dependent-but-complete disagreement, unchanged and
  still WONTFIX) is now either fixed or an accepted, explicitly-scoped limitation -- none of them silently wrong.
- **The classification sweep's generator (dim 2-4, n 5-14, uniform random) is not exhaustive.** Higher ambient
  dimension or larger point counts were not separately swept for this specific fix (though the broader
  `requireValidTriangulation=true` sweep used the same generator and found zero failures within it).
- **The global-coplanarity projection (fix 3 of issue A) has not been separately stress-tested at scale** the
  way fixes 1/2 and the tolerance fix (4) were -- it resolved the one concrete case it was built for and the
  full sweep then went to 0, but a dedicated stress generator that specifically targets "points near, but not
  exactly on, a lower-dimensional flat" (as opposed to grid points, which happen to trigger it but weren't
  designed to) has not been built.
