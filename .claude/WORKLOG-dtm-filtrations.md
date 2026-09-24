# WORKLOG: DTM-based filtrations (distance-to-measure)

2026-09-24. Point-in-time snapshot, not retroactively edited (per CLAUDE.md's worklog convention). Session goal:
add both DTM-based constructions proposed in an earlier exploratory exchange -- a DTM-weighted Rips/Cech
filtration (`streams.DtmRipsSimplexStream`) and a DTM-weighted alpha complex (`alpha.AlphaComplexDQP.dtm`) -- and
wire both through the four user-visible surfaces (MATLAB facade, CLI, docs, this file).

## Sources verified (not recalled from memory)

- Chazal, Cohen-Steiner & Merigot, "Geometric inference for probability measures," Foundations of Computational
  Mathematics 11:733-751 (2011) -- the empirical distance-to-measure (DTM) function itself.
- Anai, Chazal, Glisse, Ike, Lecci, Rouvreau, Saulnier & Wasserman, "DTM-based filtrations," arXiv:1811.04757 --
  the weighted-Rips/Cech filtration built from DTM values (Def. 3.1, Prop. 3.5).
- GUDHI/gudhi-devel@master (fetched live this session, not from training-data recall):
  `src/python/gudhi/point_cloud/dtm.py`, `src/python/gudhi/point_cloud/knn.py`,
  `src/python/gudhi/weighted_rips_complex.py`, `src/python/gudhi/dtm_rips_complex.py`,
  `src/python/doc/rips_complex_user.rst`, `src/python/test/test_dtm.py`, `src/python/test/test_dtm_rips_complex.py`.
  GUDHI has NO alpha-complex analogue of DTM weighting (no `DTMAlphaComplex` in this tree) -- `AlphaComplexDQP.dtm`
  is this codebase's own construction, derived (not copied) from the paper's `p=2` ball equation applied through
  the existing weighted-alpha/power-distance machinery (Carlsson & Carlsson 2024, already in this codebase).
- A WebFetch pass at Buchet-Chazal-Oudot-Sheehy (SODA 2015, arXiv:1306.0039) did NOT confirm it defines this exact
  alpha-weighting construction (only the abstract was available) -- NOT cited for that reason, rather than cited
  on a guess.

## Key facts pinned by this arc

**DTM formula, self-inclusive k-NN.** `f(x) = ((1/k) * sum over k nearest neighbours of x, x ITSELF INCLUDED, of
d(x,y)^q)^(1/q)`, q=2 default. Confirmed two ways: (1) GUDHI's own `KNearestNeighbors` docstring ("k: number of
neighbors (possibly including the point itself)"); (2) reproducing `test_dtm_rips_complex.py`'s own worked example
(`pts=[[2,2],[0,1],[3,4]], k=2` -> persistence `[(3.16227766,5.39834564)]*2 + [(3.16227766, inf)]`) byte-for-byte
through this codebase's own naive engine (`DtmRipsStreamSpec`). At `k=1`, `f=0` everywhere (a point's own nearest
neighbour, itself, is at distance 0) -- `DtmRipsSimplexStream` at `k=1` must reduce EXACTLY to plain Vietoris-Rips
(cells, filtration values, and barcode all checked, `DtmRipsStreamSpec`).

**Doubled units, matching every other VR-flavored stream.** GUDHI's own `WeightedRipsComplex`/`DTMRipsComplex`
double every filtration value vs. the paper's own convention "for consistency with RipsComplex" (whose edge value
is the raw pairwise distance, not half of it) -- `DtmRipsSimplexStream` does the same: vertex = `2*f(x)`, edge =
`2*t(f_x,f_y,d)`. `alpha.AlphaComplexDQP.dtm` stays in alpha's OWN pre-existing convention (squared-radius/power
units, never doubled) -- the two constructions were never going to share units; the cross-check
(`AlphaComplexDQPDtmSpec`) converts explicitly (`2*sqrt(alpha_value)`).

**`p` (Def. 3.1's ball-radius exponent) vs. `q` (DTM's own exponent) are different knobs.** `p=1`:
`t=max(f_x,f_y,(d+f_x+f_y)/2)`, symmetric under argument swap by construction (max doesn't care about order,
float addition is commutative) -- the ONLY variant GUDHI's Python bindings implement, and the one checked
byte-for-byte against them. `p=2`: closed form `t=max(f_x,f_y,sqrt(u^2+f_x^2))`,
`u=(d^2+f_y^2-f_x^2)/(2d)`, valid when `|f_y^2-f_x^2|<=d^2`; otherwise `t=max(f_x,f_y)` (the closed form
OVERSHOOTS outside that regime -- e.g. `f_x=0,f_y=10,d=1` gives 50.5 against a true value of 10 -- this is a
correction to an earlier, wrong "just wrap everything in an outer max" plan from mid-session). NOT symmetric
under raw argument order (computes `f_y^2-f_x^2` as a literal difference) -- `DtmMetricSpace.distance` canonicalizes
to `(min-index, max-index)` order before calling `edgeValue`, so `distance(x,y)` and `distance(y,x)` are always
bit-identical; no Cech-style facet-floor clamp is needed BECAUSE of this, not because the risk was overlooked.
`p=2` has NO external reference implementation to check against (GUDHI doesn't implement it) -- it exists
specifically as the cross-validation device against `AlphaComplexDQP.dtm` (below), not as a recommended default.

**`DtmRipsSimplexStream` is the first coface stream in this codebase whose vertices have nonzero filtration
values.** Every existing VR-flavored coface stream (`EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream`'s
own inherited `case 0`) hardcodes vertex filtration value 0 and emits `metricSpace.elements` UNSORTED and
UNFILTERED -- harmless there only because every vertex ties at 0 (any permutation of tied elements still satisfies
"sorted by `filtrationOrdering.reverse`," and threshold-filtering a nonnegative value against a nonnegative
threshold is a no-op). Neither is safe once vertices have distinct values, so `DtmRipsSimplexStream` overrides
`case 0` explicitly (`sortedByFiltration(...).filter(keptByThresholdAndCriterion)`) -- confirmed empirically, not
just reasoned: an early `k=1`-reduces-to-VR test comparing raw `iterateDimension(0)` SEQUENCES against plain VR's
own (still-unsorted) `case 0` failed, even though both streams' underlying SETS and filtration VALUES matched
exactly -- both tie-break resolutions are individually valid per the ordering contract, just different ones.
Fixed by comparing sets/values (and the actual barcode) instead of raw sequence order, not by "fixing" the base
class (out of scope, could perturb existing tied-vertex H0 representatives elsewhere).

**`maxFiltrationValue` default: `metricSpace.minimumEnclosingRadius` of the REIFIED (doubled) space, like every
other flag-complex VR stream in this codebase -- deliberately NOT GUDHI's own `+Infinity` default.** Proof sketch
(both `p=1` and `p=2`, since `t(f_x,f_y,d)>=max(f_x,f_y)` holds for both by construction -- see class doc): let
`x* = argmin_x max_y distance(x,y)`, `R = max_y distance(x*,y)`. For any vertex `z`: `distance(x*,z) <= R` (by
definition of the max) and `distance(x*,z) = 2*t(f_{x*},f_z,d) >= 2*f_z` (since `t>=max(f_x,f_y)`) -- so
`2*f_z <= R` for EVERY vertex, meaning truncating at `R` can never silently drop a vertex's own birth, and beyond
`R` the complex is a cone from `x*` exactly as in the unweighted case (every edge from `x*` is `<=R` there too).
`DtmRipsStreamSpec`'s hand-derived 0/1/3/7 fixture (`k=2`) was specifically chosen so the edge realizing `R`
(edge 3-7) is exactly AT the threshold, checking this doesn't get excluded by a `<=` vs `<` boundary mistake.

**A pre-existing bug in `AlphaComplexDQPBuilder.compute()`'s vertex-value assignment, found and fixed as a
precondition for `AlphaComplexDQP.dtm`, not a DTM-specific fix.** The old code unconditionally assigned
`weights(f) = -space.weight(x)` for every vertex -- correct ONLY when point `x`'s own coordinates lie inside its
own restricted power cell `V_x`. Whenever some Cech-neighbour `j` has `weight(j)-weight(x) > d^2(x,j)`, point `x`
is dominated by `j` and sits OUTSIDE `V_x`, even though `V_x` can still be nonempty elsewhere (the mildly-weighted
`[-0.3,0.3]` range in the pre-existing `AlphaComplexDQPWeightedSpec` never triggers this; DTM weights, which can
diverge a great deal near outliers, trigger it routinely). Worked R^1 counterexample: points `0,1`, weights
`(0,-4)`. `pi_0(y)=y^2`, `pi_1(y)=(y-1)^2+4`; `pi_1(1)=4 > pi_0(1)=1`, so point 1 is dominated at its own location.
The bisector is at `y=2.5`, `pi_0(2.5)=pi_1(2.5)=6.25` -- the TRUE constrained minimum of `pi_1` over `V_1`. The
old code reported `-weight(1)=4` -- a spurious, 2.25-long H0 bar (component born too early) that shouldn't exist.

Fix (with the project lead's explicit sign-off after this was surfaced mid-session -- see the conversation, not
just this file): reorder `compute()` to solve dimension 1 (edges) BEFORE dimension 0, then for each vertex `x`:
if `x` is inside its own `V_x` (a cheap O(deg(x)) check against Cech-neighbours only), use `-weight(x)` as before;
otherwise use the MINIMUM over `x`'s own incident, already-solved edges' weights (with that edge's own witness,
not `x`'s coordinates -- `Phi({x})` is the boundary point once `x` is excluded from `V_x`, not `x` itself). A
vertex with NO incident edges at all is genuinely hidden (empty power cell, a redundant site in the
regular-triangulation sense) and is DROPPED from `byDim(0)` entirely, not assigned any value.

Why this is not just "monotonicity-safe" but actually CORRECT: for a quadratic objective (`pi_x` is a paraboloid,
so minimizing it subject to linear power-bisector constraints is exactly an orthogonal projection of `x` onto the
convex polytope `V_x`), the projection of an exterior point onto a convex polytope generically lands on a single
FACET (one active constraint) -- exactly the point `solveAtVertex`'s existing (untouched) k=1 QP machinery already
computes for the edge to that one neighbour, since `pi_x` and `pi_j` agree there by construction of the power
bisector, regardless of which of `x`/`j` was used as that edge's own base vertex. No new geometry was written --
only reordering plus a min. Checking whether this reordering could break `k>=2` candidate generation (which reads
`present`, populated by earlier dimensions): traced through `buildCandidates`'s `case _` branch and confirmed it
only ever checks the IMMEDIATELY-PRIOR dimension's own `present` membership (never jumps straight to dimension 0),
so `k=1` not depending on `present`/`byDim(0)` at all means the reorder changes nothing about `k>=2`'s own
correctness -- confirmed by the full existing alpha suite (`AlphaComplexDQPWeightedSpec`'s 4000 property-test
expectations included) passing unchanged after the fix, not just by this argument.

Two new regression tests pin this (`AlphaComplexDQPVertexAttachmentSpec`): the R^1 "attached, not hidden" case
above (expects `6.25`, matching its own attaching edge exactly -- a zero-length bar), and a genuinely-hidden-vertex
case (points `0,1,2` at unit spacing, weights `(0,-2,0)`: `V_1` is empty for EVERY real `y`, since `pi_0<=pi_1` for
`y<=1.5` and `pi_2<=pi_1` for `y>=0.5`, and `[0.5,1.5]` already covers the whole line -- point 1 must be absent
from the complex entirely, not merely mis-valued).

**Cross-validating `AlphaComplexDQP.dtm` against `DtmRipsSimplexStream(p=2)`.** Both are the SAME `p=2` ball union
(`alpha`'s `weight(i)=-f(i)^2` makes `pi_i(y)=||y-x_i||^2+f(i)^2`, so `pi_i(y)<=alpha` iff
`||y-x_i||^2<=alpha-f(i)^2`, exactly Def. 3.1's `r_x(t)^2=t^2-f(x)^2` with `alpha=t^2`), so by the persistent
nerve lemma they report the same number of path components at every threshold -- the same H0 barcode, once
alpha's `alpha=t^2` units are converted to Rips's `2*t` via `birth -> 2*sqrt(birth)`.

This did NOT hold naively on the first attempt, and required working out why by hand before concluding it was a
test bug, not a code bug (documented here since re-deriving this cost real time and future-me should not have to
redo it): a vertex the alpha complex correctly DELAYS (attached-but-not-self-contained) or OMITS (hidden) still
exists as an ordinary vertex in `DtmRipsSimplexStream` from `t=f(x)` onward, since Rips has no notion of a
restricted cell at all. The "extra" Rips vertex is born and merges back into the SAME component in the same
instant (a zero-length bar) -- this carries no persistent signal and is exactly what the nerve lemma's per-
threshold component-COUNT guarantee predicts (both constructions have the same number of components at every t,
even when they disagree about which named simplices realize that count). Concretely verified with the R^1
`f=(0,2)` fixture (points 0,1): alpha delays vertex 1 to `alpha=6.25` (`t=2.5`) vs. Rips's own `f(1)=2` -- but both
report exactly one H0 component throughout, once the zero-length "delayed vertex" artifact is dropped from each
side. `AlphaComplexDQPDtmSpec` compares (birth,death) MULTISETS after dropping zero-length bars, with a tolerance,
using HAND-PICKED (not DTM-derived) `f` values specifically -- a DTM-derived `f` on any fixture small enough to
verify by hand tends to put every point inside its own cell (no delay/omission ever exercised), which would make
this cross-check pass trivially. The second fixture (points 0,1,2, `f=(0,sqrt(2),0)` -- the SAME hidden-vertex
geometry as the alpha-only regression test above) gives a genuinely nontrivial match: both report a finite bar
`[0,2.0)` plus one essential bar.

A `Double.PositiveInfinity - Double.PositiveInfinity = NaN` trap in the FIRST draft of the comparison helper (a
naive `abs(x-y)<tol` on two essential bars' `death=+Infinity` silently fails, since `NaN < tol` is always false) --
fixed by special-casing infinite endpoints to plain `==` before falling back to the tolerance check.

## What shipped

- `streams.SpatialQuery.nearestNeighbors(v,k)` (both `JVPTree` and `BruteForce`); `JVPTree`'s own doc now flags
  that its pruning assumes the triangle inequality, which not every `FiniteMetricSpace` in this codebase actually
  satisfies (`ExplicitMetricSpace` enforces nothing) -- `streams.DistanceToMeasure` defaults to `BruteForce` for
  exactly this reason, `alpha.AlphaComplexDQP.dtm` explicitly opts into `JVPTree` instead (points are always
  genuinely Euclidean there).
- `streams.DistanceToMeasure` -- generic over any `FiniteMetricSpace[Int]` (doesn't need coordinates, just a
  metric), matching GUDHI's own worked examples byte-for-byte (`DistanceToMeasureSpec`).
- `streams.DtmRipsSimplexStream` (`p in {1.0, 2.0}`), `alpha.AlphaComplexDQP.dtm`, and the vertex-attachment fix
  in `AlphaComplexDQPBuilder` (a new, reusable `alpha.AlphaComplexDQPStream` wraps ANY already-built
  `AlphaComplexDQP`, not just an always-untruncated one like the pre-existing `AlphaShapeDQP`).
- `matlab.TDA4j`: `complex=dtm-rips` (works from `computeFromDistanceMatrix` too, unlike `alpha`/`cech` --
  `DistanceToMeasure` needs no coordinates) and `complex=dtm-alpha` (needs `computeFromPoints`, like `alpha`/
  `cech`); new options `dtmK` (required), `dtmQ` (default 2.0), `dtmP` (default 1.0, `dtm-rips` only).
  `engine=ripser` refused for both (Ripser assumes vertex births at 0 and a diameter-only incremental formula);
  `engine=chunks` refused for `dtm-alpha` only (same known stall/OOM risk as plain `complex=alpha`) -- ALLOWED for
  `dtm-rips` and cross-validated against `naive` (`TDA4jSpec`), since it's an ordinary flag complex with no VR-
  specific assumption chunks would need proven separately.
- CLI (`cli.TDA4jConf`/`TDA4jCLI`) and docs mirrored per CLAUDE.md's four-surface convention -- see this file's
  own git history / the session's commits for exactly what landed on each page (kept out of this snapshot since
  it's mechanical 1:1 mirroring, not a design decision).

## Explicitly out of scope / not attempted

- `p = Infinity` (Def. 3.1's third named case) -- no user need identified.
- A `DTMDensity`-style normalized density estimator (GUDHI has one, `gudhi.point_cloud.dtm.DTMDensity`) -- not
  asked for, not built.
- Re-auditing `AlphaComplexDQPBuilder.cechNeighbours()`'s own Cech-neighbour-restriction logic for a DIFFERENT,
  unrelated correctness question that came up while reasoning through the vertex-attachment fix (whether an
  edge {i,j}'s own k=1 QP solve, restricted to `nbrs(i)` only, could in principle miss a constraint from a site
  that's a Cech-neighbour of `j` but not of `i`) -- worked out that this is NOT actually a gap (any witness point
  y* on the edge already satisfies `y* in U_i` AND `y* in U_j` by construction, and the file's own documented
  "non-Cech-neighbours are provably irrelevant within U_x" argument then applies symmetrically to BOTH i and j,
  not just the base vertex) -- but this reasoning is specific to k=1 edges and was not re-verified for k>=2
  candidate generation in general. Flagging as a place a future session should look again if a k>=2 correctness
  question ever comes up on Cech-neighbour-restricted candidate generation, not as a known bug.
