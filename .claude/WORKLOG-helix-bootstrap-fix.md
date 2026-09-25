# WORKLOG: HelixDelaunay higher-dimension issues (bootstrap crash fixed; two order-dependency issues traced but not fixed)

2026-09-25. Requested directly: "follow-up on the issues with Helix in higher dimensions. What are they? Can we
fix them?" followed by "let's fix A, B and D" (D already shipped this session, see
`WORKLOG-helix-triangulation-repair.md`). This worklog covers A and B.

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

## B: root-caused to the SAME mechanism as C, not a separate bug

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
of whether a later, more-correct candidate is discovered. Everything reachable only through the vertex-1 side
(the three simplices sharing `{1,3,9}`, and all of their own sub-faces) is then permanently unreachable, with no
exception and no signal that anything was lost.

This is not a new, distinct bug: it is the EXACT mechanism already documented as `HelixDelaunayBuilder`'s own
"Accepted limitation" for issue C (near-cospherical clusters making the frontier walk order-dependent) --
`visitedFacets`'s first-come-first-served, never-reconsidered lock is what turns "two individually valid
choices" (C's own symptom) into "one choice silently drops real content" (B's symptom) whenever the discarded
side isn't reachable any other way. A genuine fix for either needs the same joint near-tie detection (symbolic
perturbation, or an equivalent canonical tie-break shared with DQP) already on record as a deliberate,
out-of-scope algorithm redesign -- not attempted this session, consistent with the project lead's own WONTFIX
call on C, pending the same call being made (or not) for B specifically.

## Validation

`sbt clean test`: 577 examples, 0 failures (up from 572 -- `AlphaComplexSpec`'s `"helix"` re-inclusion adds real
coverage, not new example count by itself, since it already existed for `"DQP"`). `scalafmtCheck`/
`scalafmtSbtCheck` clean. Multiple independent stress sweeps: the original documented generator (`Gen.double`,
dim 2-5, size 6-12, 10000+ trials, 0 hits after the fix, consistent with 0 before too -- confirming this
generator alone was never a reliable reproduction for A, contrary to the "1-in-600" figure it was originally
measured against, which likely used a different point distribution or version); a targeted grid/near-grid
generator built specifically to stress the diagnosed pattern (30000 trials, 2226 -> 0 across the four fixes,
each intermediate count independently re-measured before moving to the next fix, not assumed). All diagnostic
and stress scripts were throwaway (`Test/runMain`, deleted before finalizing), matching this codebase's own
convention; all temporary trace `println`s were reverted before finalizing.

## Known gaps, stated plainly

- **B is traced, not fixed.** A real fix needs the same joint near-tie detection deliberately out of scope for
  C. Shipping a fix for B without also fixing C's own underlying mechanism isn't really possible, since they are
  the same mechanism -- this is a scope question for the project lead, not an implementation gap.
- **The global-coplanarity projection (fix 3) has not been separately stress-tested at scale** the way fixes 1/2
  and the tolerance fix (4) were -- it resolved the one concrete case it was built for and the full sweep then
  went to 0, but a dedicated stress generator that specifically targets "points near, but not exactly on, a
  lower-dimensional flat" (as opposed to grid points, which happen to trigger it but weren't designed to) has
  not been built.
