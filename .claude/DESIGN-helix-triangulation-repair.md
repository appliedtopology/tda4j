# DESIGN: an opt-in flag on `HelixDelaunay` guaranteeing a genuine ambient-dimensional triangulation

Status: **SHIPPED, 2026-09-25.** `HelixDelaunay(pts, seed, requireValidTriangulation = true)`
(`alpha/AlphaShapes.scala`) is live. Four designs were tried in sequence; the first two (coning, discard-without-
replacement pruning) were implemented and rejected on real, confirmed correctness flaws — recorded below in full,
not deleted, because the reasoning that sank them is what makes the fourth design's own validation trustworthy.
A third avenue (diagonal/bistellar flip) was investigated analytically and found promising but out of scope to
implement safely in the time available. The fourth design — jitter the near-tied vertices only, then re-run the
same already-tested global builder, recomputing every circumsphere from the real coordinates afterward — is what
shipped, after its own first version was ALSO caught by measurement (a real, high disagreement rate at `d=3`) and
fixed by adding a second, independent correctness check (see "Fourth design" below).

Requested by the project lead directly, with the exact repair algorithm explicitly delegated ("I can imagine a
number of ways to get some arbitrary triangulation, and I don't think either of them will end up with a clear
advantage over the others. Pick one."). The user went to sleep partway through ("stick with the document your
choices paradigm... I'm about to go to sleep") but returned mid-investigation with two further, decisive
contributions: proposing the jitter-and-recompute idea that became the shipped design, and later pushing back
directly on a "missing essential H2 bar" finding with "Delaunay should fully triangulate the convex hull — there
shouldn't be a possibility of interior voids. Is the naive engine struggling here?" — which was the correct
question and led straight to the real bug (see "Fourth design" below). Every design here, including the shipped
one, was carried through to empirical validation before being trusted, per that same "document your choices"
instruction — argument alone was not treated as sufficient at any point in this investigation, and twice
(the second design, and the first version of the fourth) that discipline caught a real bug that pure reasoning
had missed.

## The premise, and why it needed checking before designing anything

The request's own framing was: "instead of building up the full [cospherical] simplex we would triangulate the
interior of the sphere... putting in a cone with an arbitrary vertex as cone point" — i.e. the assumption that
`HelixDelaunay`, on a cluster of `k > ambientDim+1` cospherical points, currently emits ONE big degenerate
`(k-1)`-simplex (the way `AlphaShapeDQP`/the textbook alpha-complex definition does; CLAUDE.md's own "unit grid
in R² → 3-simplices" example), and that this is the mechanism producing `FastAlphaHomologyContext`'s
facet-multiplicity violations.

**This is not what the current code does, and it is not the actual failure mechanism.** Read
`HelixDelaunayBuilder.handleCosphericalPoints` (`alpha/AlphaShapes.scala`) directly: when it detects
`spherepoints.size > ambientDimension + 1` on one simplex's own circumsphere, it does NOT emit one fat simplex
— it already runs a local "pulling" triangulation (cone the lowest-remaining-index cospherical point onto each
newly-exposed facet, recursively), producing genuine `(ambientDimension+1)`-vertex simplices throughout. So the
textbook cospherical-degeneracy hazard CLAUDE.md documents is real for `AlphaShapeDQP`, but `HelixDelaunay`
already has its own answer to it, and that answer was not the thing failing.

**Traced the actual failure mechanism directly** (temporary `println`s at every silent branch of
`HelixDelaunayBuilder.compute`/`handleCosphericalPoints`, run against `FastAlphaHomologySpec`'s own pinned
`facetMultiplicityViolationFixture`, reverted before writing this note — not left in the tree). The three
simplices producing the reported bad facet `{8,10} -> [ids 9,10,12]` were:

```
TreeSet(3, 8, 10)   -- found by the GENERAL frontier walk, well before point 11's own cospherical cluster
TreeSet(5, 8, 10)   -- ALSO found by the general frontier walk, independently, from a different direction
TreeSet(8, 10, 11)  -- the seed simplex that later triggers handleCosphericalPoints for a DIFFERENT cluster
```

All three genuinely pass the algorithm's own (epsilon-tolerant) "circumsphere contains no other point" test
independently, each approached from a different facet, each unaware of the others. `addFrontierCase`'s own
cancellation logic (`removeIf` a pending case for the same facet, then only re-enqueue if nothing was removed)
is built to handle exactly TWO simplices meeting at a facet cancelling each other's pending search — it has no
way to notice a THIRD, independently-discovered claim on the same facet, and nothing anywhere checks for that
after the fact. This is `HelixDelaunayBuilder`'s own already-documented "Accepted limitation" (near-cospherical
clusters making the frontier walk order-dependent, "a real fix needs joint near-tie detection... not attempted
here") manifesting concretely — not a new bug, and not the cospherical-fat-simplex hazard. **The two are
genuinely different phenomena that happen to share a symptom (a facet-multiplicity precondition violation)**.

## Why this is NOT being fixed by changing `HelixDelaunayBuilder`'s own construction

A principled fix to the actual mechanism (three independently-valid near-tied candidates around one facet)
needs "joint near-tie detection across all currently-competing candidates before committing to any one of
them" — the project's own prior conclusion, already on record as a deliberate non-goal: it would mean touching
every circumsphere-containment test in the incremental algorithm with a globally-consistent tie-break (likely
symbolic perturbation, the textbook answer to this whole class of problem) — a genuine algorithm redesign of a
historically fragile, bug-history-laden piece of this codebase, not something to attempt unsupervised
overnight. The fix below therefore runs strictly as a post-processing pass, touching nothing about how
`compute()` itself works, and is off by default.

## First design attempt (rejected): discard the conflict region and cone it from an apex

The first, more literal reading of the request: find the full set of top simplices touching any facet with
`> 2` cofaces, discard all of them, take the union of their own OTHER (non-bad) facets as the "boundary" of the
discarded region, and re-fill that boundary by coning every boundary facet not already containing a chosen
apex vertex.

**Worked through against the actual traced failure and found a real flaw, not a style question.** The
discarded region there is `{3,8,10}, {5,8,10}, {8,10,11}` (three triangles all claiming shared edge `{8,10}`).
Their boundary facets (each appearing in exactly one discarded triangle) are `{3,10},{3,8},{5,10},{5,8},
{8,11},{10,11}` — six edges over five vertices `{3,5,8,10,11}`. A valid 2D region's boundary should be a simple
cycle, where every vertex has degree exactly 2 in the boundary graph. Here vertices `8` and `10` each have
degree 3 (`8` touches `{3,8}`,`{5,8}`,`{8,11}`; `10` touches `{3,10}`,`{5,10}`,`{10,11}`). A degree-3 vertex in
what should be a simple boundary cycle means the three discarded triangles do not form a simple, non-overlapping
2D region at all — geometrically, three triangles all claiming ONE shared edge with three different third
vertices cannot be pairwise non-overlapping (a shared edge only has two sides), which IS exactly the observed
near-tie phenomenon, described more precisely. Coning naively from an apex over a boundary graph like this
double-counts: working the construction through by hand, edge `{3,10}` (which touches the chosen apex `3`
directly) ends up as a face of TWO of the newly-coned triangles (`{3,5,10}` and `{3,10,11}`), which is only
correct if `{3,10}` had no OTHER, external coface to begin with — not something this construction can assume
or verify cheaply. **Rejected**: a general, correct "retriangulate a region with a possibly non-manifold
boundary" is a real computational-geometry problem, not a one-line coning loop, and belongs in the same
risk category as the near-tie fix already declined above — not something to ship unverified.

This is worth recording precisely (not just "tried coning, didn't work") because it corrects a natural
first instinct: the shared-facet-count-3 failure mode is NOT "a clean local pocket with a well-behaved
boundary" the way an exactly-cospherical cluster is — it is closer to "several independently-plausible,
mutually overlapping candidates," and any repair has to treat it that way.

## Second design attempt (implemented, then also rejected): prune each over-claimed facet to its two
most-Delaunay-like claimants

Given the actual failure is "too many candidates independently claimed one facet," the next thing tried was:
for every facet with `> 2` cofaces, keep the two claimants with the smallest circumradius (the same criterion
the algorithm's own bootstrap/frontier search already uses to prefer one candidate over another) and discard
the rest, with no replacement. Implemented as `HelixDelaunay.repairFacetMultiplicity` (`alpha/AlphaShapes.scala`),
threaded through a `requireValidTriangulation` constructor flag, off by default.

This is provably loss-only and therefore provably cannot introduce a NEW `>2`-claimant violation: pruning only
ever removes a top simplex, never adds one, so no facet's own coface count can increase as a result — for each
originally-bad facet, at least `(claimants - 2)` of its claimants are unconditionally in the discard set
regardless of what other facets' own pruning discards, so every originally-bad facet ends up with `<=2`
claimants no matter how discard sets overlap. This part of the argument held and was reconfirmed empirically
(the self-check re-scan after pruning always came back clean).

**But loss-only is exactly the problem, and this was checked empirically, not just argued through.** Running
`FastAlphaHomologySpec`'s new test (`requireValidTriangulation=true` on `facetMultiplicityViolationFixture`,
barcode compared against the naive engine on the SAME repaired stream) failed:
`FastAlphaHomologyContext`'s own barcode was missing an essential `H_1` bar `(1, 1.2622866810415936, Infinity)`
that the naive engine correctly found on the identical repaired stream. A standalone diagnostic script
(`Test/runMain`, reverted before finalizing) traced the cause precisely: pruning discarded exactly one top
simplex, `{3,8,10}`, from the 12-point fixture's 17 total. Removing that single triangle without replacement
does not just shrink the outer hull — it punches a genuine hole through the interior of the mesh, which the
NAIVE engine correctly reports as a brand-new essential `H_1` class (confirmed: the naive engine's own barcode
on the unrepaired 17-triangle complex has no such essential bar; on the repaired 16-triangle complex it does).
`FastAlphaHomologyContext`'s dual-graph technique (Alexander duality via a single shared `∞` sentinel vertex,
`homology/FastAlphaHomology.scala`) implicitly assumes the primal complex's complement has exactly one
connected component (the true unbounded exterior) — a discard-without-replacement repair that creates an
interior void breaks that assumption outright, and the engine silently drops the resulting class rather than
erroring. **Confirmed dead end, not a hedge**: any "just discard the extra claimants" design is fundamentally
incompatible with this specific fast engine, independent of which claimants are chosen or which tie-break
picks the survivors — because discarding without replacement is exactly what creates the void the engine can't
see. Reverted in full (`AlphaShapes.scala`, `FastAlphaHomologySpec.scala`); no code from this attempt ships.

## Third avenue (investigated, not implemented): recognizing this as a diagonal/bistellar flip

Mid-investigation, prompted by the project lead's own follow-up ("even if coning didn't work we should still be
able to remove the conflicts and retriangulate the void though? ... start with the leftmost k vertices of the
void and repeatedly drop one and add the next boundary vertex"), the actual traced failure's geometry was
checked directly rather than assumed to be a generic polygonal void. It is not one, and it is not the disjoint
three-way overlap the first (coning) design assumed either — it is something more specific and better-understood.

For the traced facet `{8,10}` with claimants `{3,8,10}`, `{5,8,10}`, `{8,10,11}`: computing which side of the
line through points 8 and 10 each third vertex falls on gives point 3 on one side (`cross = -0.0217`) and
points 5, 11 together on the OTHER side (`cross = +0.0396`, `+0.1037`) — verified numerically against the
fixture's exact coordinates, not eyeballed. Checking `{5,8,10,11}` for convexity (all four consecutive cross
products of the cyclic order `5,8,10,11` positive: `0.0396, 0.1037, 0.1724, 0.1083`) confirms it is a convex
quadrilateral, with `{8,10}` as one of its own SIDES (not a diagonal). That means `{5,8,10}` and `{8,10,11}`
are not two arbitrary independent candidates — they are the two triangles you get from the TWO DIFFERENT
diagonal choices of this one quadrilateral (`{5,8,10}` uses diagonal `5-10`; `{8,10,11}` uses diagonal `8-11`),
which is exactly why they overlap: a correct triangulation must pick ONE diagonal and use both of that
diagonal's triangles consistently (`{5,8,10},{5,10,11}` OR `{5,8,11},{8,10,11}`), never one triangle from each.
Point 3, on the genuinely opposite side, was never really in conflict at all.

This is the textbook 2D Delaunay "flip" — and it generalizes cleanly, not by coincidence: a set of `d+2` points
in general position in R^d has EXACTLY two triangulations (regular/Delaunay-consistent ones), related by a
single bistellar flip, determined by that point set's own Radon partition. Unlike either design already tried,
a correctly-implemented flip is **area/topology-preserving by construction** (both diagonal choices of a convex
quadrilateral cover the exact same region) — so it would not create the interior-void problem that sank the
pruning design, and it does not have the pruning design's own double-counting flaw either, because it commits
to one FULL, internally-consistent local triangulation rather than an arbitrary subset of independently-found
candidates.

**Not implemented this session.** Three reasons, stated plainly rather than glossed over:
1. Detecting "this facet's violation decomposes into exactly one opposite-side triangle plus a same-side flip"
   (the pattern the traced example happens to have) is not obviously the only shape a `>2`-claimant violation
   can take — a facet could in principle have `>2` claimants on the SAME side (no longer a simple 2-triangulation
   flip), or the "opposite side" could itself be ambiguous. This needs its own case analysis and its own
   fixtures before it could be trusted, not just the one already-traced example.
2. The 2D case (a literal diagonal flip) is simple and well-scoped; the general ambient-dimension case (a
   Radon-partition-based bistellar flip on `d+2` points) is real, nontrivial computational geometry — computing
   a Radon partition robustly, picking the "more Delaunay" side of the flip, and validating it needs the kind
   of careful, measured implementation-plus-validation work this session already spent on two rejected designs,
   not a third attempt rushed to fit the remaining time with no one available to review it.
3. This is, in substance, the same "joint near-tie detection across competing candidates" fix that
   `HelixDelaunayBuilder`'s own class doc and this note's own "Why this is NOT being fixed by changing
   HelixDelaunayBuilder's own construction" section above already concluded was a real algorithm-redesign
   project and out of scope for an unsupervised patch — this section just gives that conclusion a sharper,
   more concrete shape (a flip/Radon-partition problem specifically) than "needs symbolic perturbation" did,
   which is real progress worth recording, but does not change the scope conclusion.

## Fourth design (shipped): jitter the near-tied vertices, re-run the same global builder, recompute from real coordinates

Prompted directly by the project lead: "pick out the void boundary points on their own, jitter and recalculate
for just the void points and then glue back?" The "glue back" half was the risky part — manually splicing a
locally-recomputed patch back into a kept-fixed outside region needs its own boundary-matching logic, a new
correctness argument, and was exactly the kind of hand-built local surgery that sank the first (coning) design.
The version actually implemented sidesteps that entirely: instead of computing a local patch and gluing it in,
**re-run `HelixDelaunayBuilder` — the same already-tested global algorithm — on the FULL point set**, with only
the vertices genuinely involved in a violation nudged by a small random perturbation. The builder's own global
frontier walk does the "gluing" itself, correctly, by construction, exactly as it does for any other input — no
new geometry code needed. Once the combinatorics are decided, every resulting simplex's circumsphere is
recomputed from the ORIGINAL, un-nudged coordinates, so the perturbation never contaminates a real filtration
value (the classic "simulation of simplicity" discipline: perturb only to break a tie, discard the perturbation
for every numeric output). If the result still has a `>2`-claimant violation, the jitter set widens (folding in
the new violation's own vertices) and retries with a fresh seed, up to a bounded attempt count, throwing a named
exception rather than ever returning a silently-broken result.

**First version validated cleanly at `d=2`, then failed at `d=3` — caught by the SAME discipline that rejected
the second design, not assumed away because the mechanism "should" generalize.** A targeted stress sweep against
near-cospherical point clouds (points sampled near a common circle/sphere with tight radial noise, deliberately
built to trigger the violation far above its natural ~1-in-18700 baseline rate) found ZERO barcode disagreements
across 316 genuinely-hit violations at `d=2`, but a real, high ~10.5% disagreement rate (656 of 6272 hit
violations) at `d=3` in a 20000-trial sweep. The self-check this version relied on — "no facet has `>2`
claimants" — is necessary but was NOT sufficient.

**Root-caused via a direct challenge, not further guessing.** Presented with one traced `d=3` mismatch (naive
engine reporting an extra essential `H_2` bar that `FastAlphaHomologyContext` missed on the identical repaired
stream), the project lead pushed back immediately: "Delaunay should fully triangulate the convex hull — there
shouldn't be a possibility of interior voids. Is the naive engine struggling here?" This was the right question,
and checking it directly (rather than trusting the earlier working hypothesis that `FastAlphaHomologyContext`'s
own single-`∞`-vertex dual graph was again at fault, as it genuinely was for the second design) found: the
repaired complex's own total tetrahedra volume was a measured ~33% SHORT of the (violation-inflated) unrepaired
complex's — direct, independent confirmation of a real gap, not a disagreement about how to interpret the
barcode. `HelixDelaunayBuilder`, re-run on jittered coordinates, was silently failing to place a tetrahedron's
second coface in some cases: a facet that ends up with exactly 1 claimant is indistinguishable, by the
claimant-count check alone, from a facet that is genuinely on the outer hull. But a genuine Delaunay
triangulation's own convex hull is convex, hence contractible, so the FULL, unfiltered, all-cells-at-once
complex must have trivial `H_{d-1}` — a nonzero `H_{d-1}` there is never a legitimate feature, only ever evidence
of a missed simplex. So `naive`'s extra essential bar was correct, and `FastAlphaHomologyContext` was the one
missing a real class — the SAME failure signature as the second design (a discard-created interior void the
single-`∞`-vertex dual graph can't see), but this time the void was an unintended BUG in the repair's own output,
not an inherent consequence of the repair's own strategy the way it was for discard-without-replacement.

**Fix**: add a second, independent self-check — `HelixDelaunay.hasNoInteriorVoid` — alongside the facet-claimant
check, built by running `SimplicialHomologyContext` on the full unfiltered candidate complex (every cell at a
single filtration value) and confirming no essential bar at dimension `ambientDimension - 1`. On failure, widen
the jitter set to the candidate's own current boundary vertices (facets with exactly 1 claimant — the specific
vertices where a missed coface would be) and retry. Re-running the SAME two stress sweeps that found the
original gap:

- `d=2`: 316/316 hit violations repaired with zero barcode disagreement (unchanged from before — the fix adds
  no regression at the dimension that already worked).
- `d=3`, the SAME 20000-trial sweep that previously found 656 disagreements: 6272/6272 hit violations repaired
  with zero disagreement. A second, independent 5000-trial sweep also came back 0/1585.

`FastAlphaHomologySpec` carries both a `d=2` and a `d=3` regression fixture pinning this (the `d=3` one is the
exact real point cloud a stress trial found — not hand-constructed).

## Where this leaves `requireValidTriangulation`

**Shipped.** Two designs (coning, discard-without-replacement pruning) were implemented and reverted in full
after being proven wrong — one by hand-verified boundary-graph analysis, one by empirical barcode disagreement.
A third avenue (diagonal/bistellar flip) was investigated analytically and set aside as a properly-scoped future
project rather than rushed. The fourth design (jitter + global recompute + real-coordinate circumsphere) is what
shipped, but only after its own first cut was caught failing at `d=3` by the same measurement discipline, and
fixed with a second, independent correctness check (`hasNoInteriorVoid`) rather than patched around the symptom.
Not validated at `d>=4` — `HelixDelaunayBuilder` itself is already documented as "not reliable ground truth"
there for unrelated reasons, so this repair inherits that pre-existing limitation rather than introducing a new
one; `requireValidTriangulation` does not special-case or refuse `d>=4`, it simply has no evidence behind it there.

## What this deliberately does not attempt

- **Does not fix `HelixDelaunayBuilder`'s own near-tie order-dependency at its source.** The shipped design is a
  post-processing pass; `compute()` itself is untouched, and every other caller of `HelixDelaunay` (the flag
  off, the default) sees zero behavior change.
- **Does not attempt `d>=4`.** No evidence either way; `HelixDelaunayBuilder`'s own pre-existing dimension-4+
  construction unreliability would need its own resolution first.
- **Does not claim the repaired triangulation is closer to "the true" Delaunay triangulation** than any other
  resolution of the same near-tie would be — the near-tie is near because there is no principled way to prefer
  one geometric resolution over another; the jitter's own direction is effectively arbitrary (seeded, so
  reproducible, but not privileged).

## Validation performed

1. **Coning** (rejected): worked through by hand against the actual traced 3-simplex conflict; found the
   boundary graph has degree-3 vertices (not a simple cycle), proving naive apex-coning double-counts facets.
   Not implemented past this point.
2. **Pruning** (rejected): implemented, compiled, and run against `FastAlphaHomologySpec`'s agreement test.
   Failed: missing one essential `H_1` bar. Root cause traced via a standalone diagnostic script, confirmed
   against both the naive engine's unrepaired-vs-repaired barcodes and `FastAlphaHomologyContext`'s own output.
   Reverted in full.
3. **Jitter + global recompute, first version** (superseded, not separately shipped): validated clean at `d=2`
   (316/316); failed at `d=3` (656/6272 disagreements in a 20000-trial sweep) — caught by the same empirical
   discipline that rejected design 2, not assumed away.
4. **Jitter + global recompute + `hasNoInteriorVoid`** (shipped): the failing `d=3` case root-caused via direct
   volume measurement (a confirmed ~33% shortfall) before the fix was written, not guessed at. Re-validated on
   the SAME two stress sweeps: `d=2` 316/316, `d=3` 6272/6272 (plus an independent 1585/1585 sweep). Full
   `sbt clean test` green (572 examples), `scalafmtCheck`/`scalafmtSbtCheck` clean, `mimaReportBinaryIssues`
   clean (no previous artifacts configured).
