# Sheehy's approximate/sparse Vietoris-Rips filtration (2026-09-24)

Asked to implement Sheehy's approximate VR complexes. There are two papers, not one, and they are not
interchangeable:

- D.R. Sheehy, "Linear-Size Approximations to the Vietoris-Rips Filtration," Discrete & Computational
  Geometry 49(4), 2013 (arXiv:1203.6786) -- the original, built on a **net-tree** hierarchy, approximation
  factor `1/(1-2*epsilon)`.
- Cavanna, Jahanseir & Sheehy, "A Geometric Perspective on Sparse Filtrations," CCCG 2015 (arXiv:1506.03797,
  "CJS 2015" below) -- a reformulation of the same idea from a **greedy permutation** (farthest-point
  sampling) instead of a net-tree, with a cleaner, more geometric proof (the sparse complex is literally a
  nerve one dimension higher, Section 4). Approximation factor `(1+epsilon)`. **This is what got implemented**
  -- it is simpler to get right and to verify, and this codebase already had `LandmarkSelector.maxmin`
  (farthest-point sampling) to build on. The two papers' own `epsilon` values are NOT comparable.

Fetched both papers as PDFs (`arxiv.org/pdf/1203.6786`, `arxiv.org/pdf/1506.03797`) and read the CJS 2015 PDF
page-by-page (not a text-extraction summary -- a first WebFetch-based extraction of the formulas came back
with garbled sub/superscripts, e.g. an apparently-backwards `r_i(alpha)` piecewise definition; reading the
actual rendered pages resolved this and is what the implementation is checked against).

## The construction (CJS 2015, Sections 2-5)

Given a greedy permutation `p_1, ..., p_n` of the point set (`p_i` is the farthest point from `{p_1,...,
p_{i-1}}`; `lambda_i := d(p_i, {p_1,...,p_{i-1}})`, the *insertion radius*, with `lambda_1 := infinity` by
convention -- there is no "distance to the empty set", and the first point must never be pruned away) and a
sparsity parameter `epsilon in (0,1)`:

- `r_i(alpha) := min(alpha, lambda_i*(1+epsilon)/epsilon)` -- point `i`'s ball radius at scale `alpha`, grows
  with `alpha` until it saturates.
- `vanish_i := lambda_i*(1+epsilon)^2/epsilon` -- the scale beyond which `p_i`'s ball is empty. Past this,
  no NEW simplex may use `p_i`, but nothing already present is removed: the actual filtration is
  `S^alpha := union_{delta<=alpha} Q^delta` (a running union of the raw nerves `Q^delta`), which is what makes
  it a genuine, monotone filtration despite individual balls vanishing (CJS 2015 Section 4). This is the
  paper's own resolution of "how can a filtration un-include a vertex" -- it doesn't; it just stops growing
  that vertex's star.
- Algorithm 3 (`EdgeBirthTime`), `lambda_i <= lambda_j` WLOG:
  - `d <= 2*lambda_i*(1+epsilon)/epsilon` -> birth `d/2`
  - elif `d <= (lambda_i+lambda_j)*(1+epsilon)/epsilon` -> birth `d - lambda_i*(1+epsilon)/epsilon`
  - else -> `infinity` (never appears)
- Section 5.3, `SimplexBirthTime` for `k>1`: `max` over the simplex's own edges' `EdgeBirthTime`, but only if
  that value is `<= min_{p in sigma} vanish_p` -- otherwise `infinity`. Valid because balls are convex and
  pairwise intersection of convex sets implies a common intersection (the same Helly-type fact that makes
  plain Rips a flag/nerve complex in the first place).

## A real gap in the paper's own Algorithm 3, verified, not just suspected

Algorithm 3 as stated computes an edge's birth from only its two endpoints, with **no check against
`vanish`** -- but Section 5.3's own `SimplexBirthTime` definition (the general `k`-simplex rule) requires
exactly that check, and an edge is simply its `k=1` case, not a special one. First counterexample (advisor,
checked by hand): `epsilon=1, lambda_i=1, lambda_j=10, d=10`. Algorithm 3's second branch fires and returns
`8` (radius units), but `p_i` vanishes at `lambda_i*(1+epsilon)^2/epsilon = 4`: for the entire time both balls
exist, their radii are capped at `2` and `4` respectively, summing to `6 < 10` -- they never actually touch.

**My first draft claimed this gap doesn't reach the paper's own restricted `O(n log n)` algorithm**, reasoning
that its neighbor search (Lemma 6/7) only ever proposes candidate pairs already known to be `lambda`-close.
Advisor checked this directly against the paper's own Neighbor Invariant (`kappa = (epsilon^2+3epsilon+2)/
epsilon`, `d(p_i,p_j) <= kappa*2^ceil(lg lambda_i)` puts `p_j` in `p_i`'s candidate list) and found a SECOND
counterexample that passes that restriction too: `epsilon=1, lambda_i=1.1, lambda_j=10, d=10` -- `ceil(lg
1.1)=1`, so the neighbor bound is `kappa*2=12 >= 10`, meaning the paper's own Algorithm 1/2 pipeline WOULD
propose this pair, and Algorithm 3 WOULD return `7.8`, past `p_i`'s own vanish time of `4.4`. So the claim as
first drafted was wrong, not merely unverified -- correcting it here rather than leaving an overclaim about a
published paper in the class doc, the worklog, and CLAUDE.md (all three said it originally; all three are now
reworded to state only what was actually checked: whether the paper's FULL pipeline compensates for this
elsewhere was not verified either way, only that the gap itself is real and survives the paper's own
neighbor-list restriction). `edgeBirth` in `SheehyRipsStream.scala` applies the `vanish` clamp directly inside
the edge formula, not as a bolt-on at higher dimensions, per advisor's explicit instruction ("one memoized
override for all dim >= 1, not a reified distance plus a separate dim-2 patch").

Verified this is not a testing artifact by constructing a genuine discriminating fixture (see
`SheehyRipsStreamSpec`'s triangle test): a triangle with all three of ITS edges individually finite, one
excluded outright by the global min-vanish check even though it would be admitted by a naive "max pairwise
edge value" (ordinary flag-complex) computation. This is the sharpest possible test of the "vanish check
applies uniformly, not just pairwise" claim.

## Units

Every other stream in this codebase records filtration values in "diameter" units (an edge's value is the
raw ambient distance, not half of it). CJS 2015's own `alpha` is a RADIUS parameter (`R_alpha := {J : max
d(p,q) <= 2*alpha}`; Algorithm 3's own first branch literally returns `d/2`). So every OUTPUT this
implementation reports (`edgeBirth`'s return value, and the public `vanishDoubled`) is the paper's own value
doubled -- `lambda` itself is never doubled (it's an ordinary ambient distance, entering the formulas
unchanged). After doubling, the unsparsified branch collapses to exactly `d`, matching plain VR's own edge
value exactly -- confirmed as a real regression test (`SheehyRipsStreamSpec`'s "reduce to plain VR
cell-for-cell" property, not just informally).

**Getting the "reduce to plain VR" limit backwards, and un-backwards**: reducing to plain VR needs
`2*lambda_i*(1+epsilon)/epsilon >= diameter` for every pair, i.e. the unsparsified branch always fires. My
first instinct was "epsilon close to 1 (large) should mean less sparsification" -- wrong. `(1+epsilon)/epsilon
-> infinity` as `epsilon -> 0`, not as `epsilon -> 1`; SMALL epsilon is what recovers plain VR (matching the
paper's own approximation factor `(1+epsilon) -> 1` as `epsilon -> 0`, which should have been the tell).
Concretely: for a given point cloud, compute `lambda_min` (the smallest insertion radius, excluding the
seed's `infinity`) and an upper bound `D` on the diameter, then pick `epsilon <= lambda_min/(D-2*lambda_min)`
when `D > 2*lambda_min` (any `epsilon` works if `D <= 2*lambda_min`) -- this is always satisfiable by a small
enough positive `epsilon`, unlike a bound requiring epsilon large.

## Architecture

Reused the existing "reify as a `FiniteMetricSpace`-adjacent construction + `filtrationValueOverride`,
subclass `RipserCofaceSimplexStream`" pattern (`DtmRipsSimplexStream`, `WitnessCofaceSimplexStream`) -- but
NOT via a reified `FiniteMetricSpace[Int]` whose `distance` returns edge births: the vanish check needs to see
ALL of a simplex's own vertices, not just a pair, so a plain "flag complex over a weighted distance" shape
doesn't carry the necessary information at dimension >= 2. Instead, `SheehyRipsSimplexStream` passes the
ORIGINAL ambient metric space straight through (used only for combinatorial enumeration -- `.size`/
`.elements`/`.contains`, never `.distance`) and supplies ONE `filtrationValueOverride` computing every
dimension >= 1 uniformly from the greedy permutation's own lambda map, exactly as advisor directed.

**`LandmarkSelector.maxmin` (`WitnessStream.scala`) extended, not duplicated**: added an `insertionRadius:
Map[Int,Double]` field to `LandmarkSelection` (default `Map.empty`, so `random`'s existing construction and
every field-by-name call site elsewhere is unaffected -- confirmed by running `WitnessStreamSpec`/
`TDA4jSpec`/`CLISpec` unchanged, all still green) and recorded each landmark's own `minDistToLandmarks(next)`
right before the existing loop's update -- free, the value was already being computed. Calling
`maxmin(space, space.size)` (full size, not a subset) gives exactly the greedy permutation
`SheehyRipsSimplexStream` needs, with no second farthest-point loop anywhere in the codebase.

**`maxFiltrationValue` is UNCONDITIONALLY clamped** to `maxFiniteFiltrationValue` -- the largest FINITE edge
birth the construction produces (materializing every pairwise `edgeBirth` once; a valid bound since any
finite simplex's value is a `max` over its own edges) -- not just defaulted to it. First draft only applied
this as a `None` fallback (`maxFiltrationValue.getOrElse(maxFiniteFiltrationValue(...))`), which left the
IEEE-754 hazard wide open for any caller passing an EXPLICIT threshold, including the obvious
`Some(Double.PositiveInfinity)` spelling of "untruncated" every other stream in this codebase uses. Advisor
caught this by pointing out my own test suite had walked straight into it (see Testing below): passing
`Some(Double.PositiveInfinity)` explicitly compares `Double.PositiveInfinity <= Double.PositiveInfinity`
(`true` in plain IEEE-754), silently readmitting every excluded pair. Fixed at the constructor level
(`math.min(maxFiltrationValue.getOrElse(Double.PositiveInfinity), maxFiniteFiltrationValue(...))`) so the
hazard cannot recur regardless of what a caller passes, not just documented as a thing not to do. NOT
`metricSpace.minimumEnclosingRadius` either (not a cone construction -- an edge to the anchor point/seed can
be unboundedly large, so no point has a finite max distance to every other point).

## Scope, honestly

This is the CJS 2015 greedy-permutation reformulation, not Sheehy 2013's net-tree construction, and
**`O(n^2)`, not the paper's own `O(n log n)`** -- every pairwise `edgeBirth` is materialized directly
(`filtrationValueOverride`, `maxFiniteFiltrationValue`), the same reference-implementation-first choice this
codebase already made for e.g. plain VR/Cech. The Algorithms 1-4 fast neighbor-search machinery (Section 5) is
not implemented. The payoff is a smaller complex to REDUCE (linear in `n` for bounded doubling dimension,
Lemma 6/7/Theorem 9/10), not a faster one to build.

Refuses `engine=ripser`: a simplex's value here is not simply the maximum ambient pairwise distance among its
vertices, so both Ripser engines' incremental `insertionDiameter`/apparent-pairs machinery (which assume the
filtration functional literally IS `MaximumDistanceFiltrationValue` on the metric space handed to them) do
not apply.

**All four finalization surfaces done in this same session** (per CLAUDE.md's "finalizing a user-visible
capability" checklist, and per advisor's explicit push-back against deferring it): `matlab.TDA4j` dispatch
(`complex=sheehy-rips`, option key `sheehyEpsilon`, required, no sensible universal default -- same reasoning
as `dtmK`), `cli.TDA4jCLI`/`TDA4jConf` mirroring (`--sheehy-epsilon`), and the developers-guide
(`architecture.md`'s own construction section, `class-diagrams.md`'s stream hierarchy,
`persistence-engines.md`'s new streams-vs-engines compatibility table covering every construction in the
codebase, generated by reading `matlab.TDA4j`'s own dispatch code directly rather than inferring it, per the
project lead's request mid-session) and user-guide (`README.md`'s quick-start/CLI/MATLAB reference sections).
Cross-checked against `matlab.TDA4j`'s own dispatch code for every "refuses X" claim in the new table --
several of those refusal reasons (e.g. `alpha`/`dtm-alpha` refusing `chunks` for a stall/OOM reason, not an
algorithmic one) were not obvious without reading the actual `dispatch` match arms.

## Testing

`SheehyRipsStreamSpec.scala`: hand-derived `edgeBirth` fixtures pinning each of the four branches (case 1,
case 2, the vanish-excluded counterexample above, the never-reachable-else branch), symmetry in the two
lambda arguments, the triangle discriminator described above, `LandmarkSelector.maxmin`'s new
`insertionRadius` field (seed = infinity, every other entry finite and non-increasing along the permutation),
the "reduces to plain VR cell-for-cell" property (not just barcode-for-barcode, following DTM's own
precedent), a `chunks`-vs-`naive` cross-validation, a SEPARATE deterministic fixture proving real
sparsification actually happens (not just theoretically possible), and an INDEPENDENT check of CJS 2015's own
Theorem 5 claim (a `(1+epsilon)`-approximation to plain VR's H0 barcode) that reuses none of this class's own
formulas -- advisor's suggestion, and the one check that would have caught a wrong case-2 constant or a
misplaced doubling factor, which the other tests (built from the same formulas they're checking) structurally
cannot.

**Four distinct bugs found while writing these tests, none of them in the actual construction**:

1. **A real, pre-existing `LandmarkSelector.maxmin` bug**, exposed (not caused) by this session's new
   full-permutation use case: `maxBy`'s "first occurrence wins a tie" rule can re-select an ALREADY-chosen
   landmark when an UNCHOSEN point happens to be its exact duplicate (both tie at `minDistToLandmarks = 0`),
   silently leaving the duplicate permanently unselected -- so a "full" permutation (`numLandmarks =
   metricSpace.size`) can end up with fewer than `metricSpace.size` DISTINCT entries, and
   `GreedyPermutation.insertionRadius` ends up missing a real ambient index's key entirely (a
   `NoSuchElementException` downstream, not a silent wrong answer). Reproduced deterministically with two
   coincident points; the existing (subset-sized) witness-complex use case was far less likely to hit this
   degenerate a tie, which is presumably why it went unnoticed until now. Fixed by tracking chosen points in a
   separate `Set` and excluding them from the `maxBy` candidates, rather than relying on their
   `minDistToLandmarks` value alone to keep them out of contention.
2. **A footgun in this codebase's own coface-stream contract, not a bug, but sharp enough to note -- and narrower
   than my own first write-up of it claimed (advisor caught the overstatement)**: calling
   `stream.iterateDimension(1)` SPECIFICALLY, directly, on a stream that has never been iterated, silently
   returns EMPTY results, not an error. Dimension 1's own candidate generation reads dimension 0's cache, and
   both `currentDimension` and `currentDimensionCache` default to "dimension 0, already cached, empty" on a
   fresh instance -- which satisfies dimension 1's own cache-freshness check (`currentDimension != d - 1`, i.e.
   `0 != 0`, is FALSE) despite dimension 0 never having actually been visited. Dimension `d >= 2` does NOT have
   this problem: its own freshness check (`0 != d - 1`) is TRUE on a fresh stream, forcing a full, correct
   rebuild of dimension `d-1` directly via `simplexIndexing`, with no dependency on dimension `d-2`'s cache at
   all. My first draft of this note said "d >= 1", which overstates it -- it's `d == 1` only, a coincidence of
   `currentDimension`'s own default value (`0`) happening to equal what dimension 1's check wants to see for a
   "nothing stale" verdict. `.iterator` (the documented, sequential
   `Iterator.from(0).takeWhile(...).flatMap(...)` traversal) does not have this problem at any dimension. Hit
   this writing the edge-count diagnostics below; fixed by using `.iterator.count(_.dim == d)` instead.
3. **Test bug, not a construction bug**: an early draft of the chunks-vs-naive test compared `sheehyEdgeCount`
   (under `SheehyRipsSimplexStream`'s own default `maxFiniteFiltrationValue` threshold, which can be as large
   as the cloud's diameter) against `RipserCofaceSimplexStream`'s own DEFAULT threshold
   (`minimumEnclosingRadius`, generally smaller) and found `sheehyEdgeCount > vrEdgeCount` -- an artifact of
   comparing two DIFFERENT thresholds, not real desparsification. Fixed by giving the comparison VR stream an
   explicit `Some(Double.PositiveInfinity)` threshold (always safe for plain VR, which never produces a literal
   `Infinity` filtration value itself).
4. **Test bug, not a construction bug**: comparing `SimplicialHomologyContext`'s raw diagram against
   `PersistenceInChunksContext(maxDim = homDim)`'s raw diagram, on a stream capped at cell-dimension
   `homDim + 1`, found spurious `dim == homDim + 1` "essential" bars on the naive side that chunks correctly
   omits -- a truncation artifact (a capped-dimension stream has no higher simplex a top-dimension cell could
   be a boundary of, so every not-otherwise-paired top-dimension cell looks spuriously essential), not a chunks
   defect. Fixed by filtering both diagrams to `dim <= homDim` before comparing, the same convention every
   other `maxDim`-aware cross-validation in this codebase already follows -- this one just hadn't been
   written down as an explicit gotcha before.
5. **Also a test bug**: the H0 approximation-ratio check divided `s / v` for matched death times `s`
   (Sheehy's) and `v` (plain VR's) without guarding `v == 0.0` -- which happens whenever two generated points
   coincide exactly (bounded-coordinate random generation makes this a real, if rare, occurrence, not a
   pathological input to exclude). Both sides agree exactly in that case (`s == v == 0.0`, confirmed by
   printing the actual failing sample), but `0.0/0.0` is `NaN`, and every comparison against `NaN` is `false`.
   Fixed by checking `s == v` first, before dividing.

Deterministic sparsification fixture: three tight clusters (5 points each, radius ~0.5) placed ~50 apart,
found by direct search rather than guessed -- random clouds at `n` up to 18 and `epsilon` up to 0.9 often
don't sparsify AT ALL (the case-1 threshold's floor as `epsilon -> 1` is a fixed multiple of `lambda`
regardless of `epsilon`, so whether sparsification happens is really a property of the DATA's own scale
spread, not something `epsilon` alone can force), so the property tests above deliberately do NOT assert
sparsification on every random sample -- this fixture is the reliable, non-flaky demonstration that it
happens (105 -> 26 edges) instead, paired with a chunks-vs-naive check on the same instance.

**A methodology gap in the first draft, caught by a second advisor pass, not by any test result**: I originally
described the random-cloud H0 test above as "the one check that would have caught a wrong case-2 constant or a
misplaced doubling factor." That was wrong, and provably so: at these random-cloud sizes/epsilon values the
H0 test's own random samples essentially never sparsify at all (verified directly -- every sample's sorted
death list came out IDENTICAL to plain VR's, ratio exactly `1.0` throughout), because a point's edge to its own
greedy-permutation predecessor is `case 1` with value exactly `d` by construction (`d = lambda_p` for that
specific pair, always at or under the case-1 threshold), and those predecessor edges alone already form a
spanning tree -- so the test was silently comparing VR against VR, and could not have caught a case-2 bug no
matter how wrong case 2 was. Two real fixes followed:
- **Added a genuinely independent oracle for `edgeBirth`**: a bisection search directly against CJS 2015's OWN
  ball-radius definition (`r(lambda,alpha) = min(alpha, lambda*(1+epsilon)/epsilon)`, find the smallest alpha
  with `d <= r_p(alpha) + r_q(alpha)`), not a re-derivation of the closed-form formula being tested. Generated
  `d` values spanning the case-1/case-2 boundary up to and beyond Lemma 6's own neighbor bound
  (`kappa*lo`), so case 2 and the vanish-exclusion boundary are both actually exercised, not just asserted.
  This is the check that fills the role I wrongly attributed to the H0 property test.
- **Tightened the H0 bound from CJS 2015's own symmetric `[1/(1+epsilon), 1+epsilon]` to `[1, 1+epsilon]`**:
  every sparse edge's value is either exactly its VR counterpart (case 1) or strictly larger (case 2 only
  fires past case 1's own threshold, and is itself provably `>= d` there), and an excluded pair is effectively
  "even larger" (infinite) -- so a sparse death can never be earlier than its VR counterpart, only equal or
  later. This is a strictly stronger claim than the paper's own general `c`-approximation bound (which allows
  either direction) and would catch a bug the symmetric bound alone would miss (sparsification somehow merging
  components earlier than VR itself).
- **Added an H0 assertion on the deterministic three-cluster fixture itself**: at least one sparse/VR death
  ratio must be strictly `> 1` there, i.e. sparsification demonstrably changes a REPORTED barcode value on that
  fixture, not just the edge count.

Full suite (`sbt clean test`) green afterward, `scalafmtCheck`/`Test/scalafmtCheck` show the same
pre-existing, unrelated formatting drift (`DistanceToMeasure.scala`/`DtmRipsStream.scala` and four spec files)
present on a clean `git stash` checkout too -- confirmed not introduced by this change; running
`scalafmtAll` once did reformat all of them (plus `TDA4j.scala`, which this session DID touch and which
genuinely needed it), and the unrelated files' reformatting was reverted (`git checkout --`) before doing
anything else, keeping this session's diff scoped to what it actually changed.

## Four-surface finalization: what a mid-session request changed

The project lead asked, mid-session, for a streams-vs-engines compatibility matrix specifically -- prompted by
this arc, but framed as a general documentation need, not Sheehy-specific. Built it in
`persistence-engines.md` by reading `matlab.TDA4j`'s own `dispatch`/`resolveWitnessEngine`/`dispatchCubical`
code directly (every complex's default engine and every refusal reason), not by inferring it from each
complex's own doc -- several refusal reasons are not obvious without reading the actual dispatch match arms
(e.g. `alpha`/`dtm-alpha` refuse `chunks` for a known stall/OOM reason, unrelated to why they refuse
`ripser`). A first draft of that table's explanatory grouping put `sheehy-rips` in the same bucket as
`dtm-rips` ("vertices not born at 0, filtration functional isn't max-pairwise-distance... but still,
combinatorially, a flag/clique complex") -- wrong for `sheehy-rips` specifically, and this project's OWN
triangle fixture already proves it: a flag complex cannot exclude a triangle whose three edges are all
individually present. Advisor caught this on a second pass; `sheehy-rips` now has its own bullet, grouped with
`cech`/`witness`-general ("not a flag complex") instead, with the triangle fixture cited as the proof, not an
assertion.

## What this session did NOT do

No external reference implementation (e.g. GUDHI's own sparse-Rips code, if it has one) was fetched or
compared against -- unlike DTM's own arc, which was checked byte-for-byte against GUDHI's `DTMRipsComplex`.
This implementation's correctness rests on: reading the primary papers directly (not from memory), two
independent verification strategies for the formula itself (hand-derived counterexamples checked by hand, and
the bisection-against-the-definition test above), and cross-engine agreement (chunks vs. naive) -- not on
agreement with any other library's implementation of this specific construction. Flagging this explicitly
rather than letting the other verification work imply more than it does.

## Addendum (same day): the `iterateDimension(1)` footgun, fixed at the source

The user asked, in the very next turn after this worklog's own end-of-session summary, to fix the footgun
(item 2 above) rather than leave it merely documented. Root-caused precisely to
`EnumeratingCofaceSimplexStream.currentDimension`'s own default value (`SimplexStream.scala`): it was `0`,
literally indistinguishable from "dimension 0 was genuinely computed," which is exactly what
`RipserCofaceSimplexStream`'s (and every subclass sharing its `iterateDimension`, including
`DtmRipsSimplexStream` and this session's own `SheehyRipsSimplexStream`) dimension-1 freshness check
(`currentDimension != d - 1`) reads as "nothing to rebuild." Fixed by changing the field's default to `-1`, a
value no legitimate `d - 1` (for any `d >= 0` a caller could ask for) can ever equal -- confirmed directly
(a fresh stream's `iterateDimension(1)` now matches `.iterator`'s own edge count exactly, verified before AND
after the fix on the same random cloud) and regression-pinned in `CofaceSimplexStreamSpec`
("`RipserCofaceSimplexStream.iterateDimension(1), called directly on a stream that was never iterated
before...`"). One-line fix, no other behavior change: every other dimension's own freshness check already used
`0` as a real, correct "nothing cached yet" signal only because `-1` was never a possibility for `d >= 2`
`(0 != d-1` was already true there regardless) -- this was purely `d == 1`'s own coincidence with the field's
particular default value, not a deeper design problem needing more than a one-line fix.

Full suite green after the fix (446 examples, same count as before -- confirms no regression), scalafmt clean
on both touched files (`SimplexStream.scala`, `SimplexStreamSpec.scala`).
