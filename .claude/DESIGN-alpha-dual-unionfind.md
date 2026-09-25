# Design note: dual union-find for alpha complexes (HelixDelaunay, d ∈ {2,3})

2026-09-25. Follow-on to `.claude/DESIGN-fast-cubical-engine.md`/`WORKLOG-fast-cubical-engine.md` (item 6 of
`.claude/WORKLOG-mainstream-feature-gap-analysis.md`), picking up that worklog's own item 7: "write this up as
its own design note follow-on once the cubical version is built and validated — not concurrent work, and
specifically scoped to `HelixDelaunay` at `d ∈ {2,3}`." Cubical is now done (commits `ae6ffe8`/`a8642b0`); this
is that follow-on.

## The mechanism, restated for a Delaunay triangulation

Exactly the same Alexander-duality construction as the cubical engine, with `HelixDelaunay`'s own full,
untruncated Delaunay triangulation in place of a cubical grid: top-dimensional simplices (dimension `d` =
ambient dimension) become dual vertices, codimension-1 simplices ("facets," dimension `d-1`) become dual edges
connecting the 1 or 2 top simplices containing them, with a shared `∞` sentinel standing in for a facet on the
triangulation's own convex-hull boundary. Primal `H_{d-1}` of the sublevel alpha filtration is ordinary `H_0`
of that dual graph's own superlevel filtration, computed by the same elder-rule union-find, descending primal
order, birth/death swapped. Combined with an ordinary primal `H_0` union-find (vertices + edges), this covers
every nontrivial dimension a 2D alpha complex has completely (a triangle is dimension 2 = `d`, so `H_0`+`H_1`
account for dimensions 0/1/2 with no gap) — the exact same "2D is free, 3D needs H1 via general reduction on
the residual" scope split the cubical engine already established, for the identical reason (in 3D, triangles
are codimension-1, used by the dual `H_2` construction, not directly available to resolve `H_1`).

**Why `HelixDelaunay`, not `AlphaComplexDQP`** (the source worklog's own reasoning, restated): the dual graph
needs the FULL, untruncated triangulation — every top simplex must eventually enter the filtration, which rules
out `AlphaComplexDQP.euclidean(points, maxRadius, ...)`'s truncated mode, and needs "every facet has ≤2
cofaces," which `AlphaShapeDQP`'s own documented degeneracy hazard can violate directly (a `k`-cospherical
cluster can make it emit a `(k-1)`-simplex, a simplex of dimension `> d`). `HelixDelaunay` is unconditionally
untruncated (no truncation parameter exists), and its own `handleCosphericalPoints` machinery always TILES a
cospherical cluster into properly-sized `d`-simplices rather than emitting an oversized one — so the ≤2-cofaces
precondition is not violated by cosphericity *by construction*, only by a genuine bug in that tiling (below).

## New finding this session: measuring the precondition's real failure rate at d ∈ {2,3}

`AlphaCrossValidationSpec`'s own doc comment (not new to this session, but not previously connected to this
specific precondition) records that `HelixDelaunay` is not fully robust: fuzzing found both an outright
assertion failure (`AlphaShapes.scala`'s `assert(validated.nonEmpty)` in the bootstrap, ~1-in-600 on
`AlphaComplexSpec`'s own generator, which spans ambient dimension 2 through 5) and, separately, a case where
the frontier walk silently produces an *incomplete* complex — missing a whole connected sub-chain of
genuinely-Delaunay faces — with **no exception raised**. That second failure mode is exactly the shape of bug
that could violate "every facet has ≤2 cofaces" without any signal at all: a facet whose second, missing
cofacet was never visited would look, to this construction, exactly like a genuine convex-hull boundary facet.

This session measured the precondition directly (not the historical proxy of "does DQP disagree with Helix"),
restricted to the two dimensions this design actually targets, via two throwaway scripts (not committed):

- 4000 trials (2000 each at `d=2`/`d=3`), points drawn i.i.d. uniform on `[-1,1]^d`, `n ∈ [6,12]`: **zero**
  exceptions, **zero** facet-multiplicity violations.
- 3000 trials using `matrixGen[Double](Gen.double, Gen.oneOf(2,3), Gen.chooseNum(6,12))` — the *exact* generator
  `AlphaComplexSpec`'s own historical rate was measured with, restricted to `d ∈ {2,3}` via `Gen.oneOf` in place
  of that generator's own `Gen.chooseNum(2,5)`: **zero** exceptions, but **one** facet-multiplicity violation
  (a facet shared by 3 top simplices, at `d=3`) — roughly **1-in-3000** combined across `d ∈ {2,3}`, using
  `Gen.double`'s own wider/less well-behaved coordinate distribution (the all-zero-violation result from the
  `[-1,1]`-uniform run above suggests `Gen.double`'s occasional extreme-magnitude values, not dimension alone,
  are what actually trigger it).
- A separate, larger, `d=2`-only search (`Gen.double`, `n ∈ [6,16]`, 50000 trials) to isolate the rate at
  exactly this design's own first-implemented dimension: **one** violation, at trial 18747 — roughly
  **1-in-18700** at `d=2` specifically, rarer than the combined `d ∈ {2,3}` figure above suggests (consistent
  with the single `d ∈ {2,3}` violation found having landed at `d=3`, not `d=2`). This exact point set (12
  points) is pinned as `AlphaDualUnionFindRegressionSpec`'s own deterministic regression fixture for the
  exception path, the same "pin the exact failing input, don't rely on a seed rediscovering it"
  discipline `AlphaComplexDQPRegressionSpec` already uses in this codebase for comparably rare failures.

**Conclusion**: the risk is real, not hypothetical, but genuinely rare at `d ∈ {2,3}` specifically (roughly
three orders of magnitude rarer than the dimension-mixed historical figure would suggest on its own) — this is
new, actionable evidence beyond what the source worklog had, not a reason to abandon the approach, but a firm
requirement to **validate the precondition explicitly and fail loudly**, not assume it. The check itself is
cheap: one pass building a `Map[Simplex[Int], Int]` of facet → coface count over the (small, already-bounded)
top-simplex set, `O(|topSimplices| * (d+1))`.

## Hand-verified worked example

Not re-deriving `HelixDelaunay`'s own geometry (already validated elsewhere) — verifying the NEW dual-graph
logic against `HelixDelaunay`'s own already-computed output, the same division of labor the cubical example
used (trusting `CubicalGridStream`'s own filtration values, verifying only the new dual construction on top).

5 points: `(0,0)`, `(4,0)`, `(4,4)`, `(0,4)`, `(1.8,2.1)` (an off-center interior point, chosen specifically to
avoid a cospherical tie among the 4 corners — a perfect square's own 4 corners are cospherical by construction,
which would make the "first" worked example ambiguous). `HelixDelaunay` on these 5 points produces a fan
triangulation from the interior point to all 4 corners — 4 triangles, no diagonal between opposite corners:

```
T1=(2,3,4) fv=2.002119721570461      T2=(0,1,4) fv=2.002867841829993
T3=(1,2,4) fv=2.009308143335919      T4=(0,3,4) fv=2.010821418668943
```

Facet (edge) → containing-triangle map (computed directly, by hand, from each `Ti`'s own 3 facets):

| Facet | fv | Triangles | Dual edge |
|---|---|---|---|
| `{3,4}` | 1.308625 | T1, T4 | T1–T4 |
| `{2,4}` | 1.453444 | T1, T3 | T1–T3 |
| `{1,4}` | 1.520691 | T2, T3 | T2–T3 |
| `{0,4}` | 1.382932 | T2, T4 | T2–T4 |
| `{2,3}` | 2.002120 | T1 only | T1–∞ |
| `{0,1}` | 2.0 | T2 only | T2–∞ |
| `{1,2}` | 2.0 | T3 only | T3–∞ |
| `{0,3}` | 2.010821 | T4 only | T4–∞ |

The dual graph is a 4-cycle (T1–T3–T2–T4–T1, via the four interior facets) plus each `Ti` also connected
directly to `∞` (via its own single boundary facet) — a wheel graph, 5 vertices, 8 edges. Tracing the
descending-value union-find by hand (`∞` pre-seeded at `+Infinity`; ties broken vertex-before-edge, matching
the cubical engine's own convention):

1. `T4` seeds (2.010821); edge `T4–∞` (2.010821, same value — `{0,3}`'s own fv equals `T4`'s own fv exactly,
   since `{0,3}` belongs to no other triangle so its value IS `min(T4's circumradius)`) merges `T4` into `∞`.
   Bar: `(1, 2.010821, 2.010821)` — zero persistence.
2. `T3` seeds (2.009308); `T2` seeds (2.002868); `T1` seeds (2.002120); edge `T1–∞` (2.002120, same reasoning as
   step 1) merges `T1` into `∞`. Bar: `(1, 2.002120, 2.002120)`.
3. Edges `T2–∞` and `T3–∞` (both 2.0, tied — order between them doesn't affect the resulting VALUES, per this
   codebase's own "which tied cell dies at a tied time is order-dependent" testing lesson) merge `T2` and `T3`
   into `∞` in turn. Bars: `(1, 2.0, 2.002868)` and `(1, 2.0, 2.009308)`.
4. All four interior edges (`T2–T3`, `T1–T3`, `T2–T4`, `T1–T4`) are now REDUNDANT — both endpoints already
   share `∞`'s component — and are skipped, exactly like any already-connected edge in an ordinary union-find
   over a graph denser than a spanning tree (the wheel's 4-cycle has one more edge than a tree needs). No bars
   from these.

Predicted dual-`H_1` bars: `{(1,2.010821,2.010821), (1,2.002120,2.002120), (1,2.0,2.002868), (1,2.0,2.009308)}`.
Running `SimplicialHomologyContext` directly on this exact `HelixDelaunay` stream gives dimension-1 bars
`{(1,2.0,2.002867841829993), (1,2.0,2.009308143335919), (1,2.002119721570461,2.002119721570461),
(1,2.010821418668943,2.010821418668943)}` — an **exact match**, multiset-for-multiset, full precision (two of
the four bars have genuine, if small, nonzero persistence — `2.0 -> 2.002868`/`2.0 -> 2.009308` — not zero-
persistence as an earlier draft of this note wrongly claimed; caught by a failing test assertion, not by
re-reading the numbers, which is exactly why the test asserted it explicitly rather than trusting the prose).
Still true, and still a real gap: this example never needs a merge between two non-`∞` dual components (all
four triangles merge directly into `∞`, since every triangle's own boundary-facet value happens to be on the
`∞` side, before any interior edge is ever reached) — so the two-real-component-merge code path, the
orientation-flip arithmetic, and the resolved-root/raw-id and `+Infinity`-tie fixes item 6 needed both stay
unexercised by this example alone; the actual test suite needs a second fixture for that (found by search, not
hand-derived, in `FastAlphaHomologySpec` — cross-validated against the naive engine rather than hand-traced,
since the core mechanism is already hand-verified here).

## What's new relative to the cubical engine (not a copy-paste port)

- **Facet enumeration is brute-force, not coordinate-derived.** `CubicalGridStream` computes facet-to-top-cell
  adjacency directly from grid coordinates (no search needed). A Delaunay triangulation has no such regular
  structure, so this needs an explicit `Map[Simplex[Int], Vector[Int]]` built by iterating every top simplex's
  own `d+1` facets once (`O(|topSimplices| * d)`) — cheap, but a real, new piece of code, not reused machinery.
- **A facet's own dual-edge VALUE must come from `helix.filtrationValue(facet)` directly, NOT `min over its
  containing top simplices`, unlike cubical.** `CubicalGridStream.filtrationValue` for a non-top cube is
  *defined* as the min over its containing top cells, so cubical's own `ids.map(topValue).min` and
  `stream.filtrationValue(facet)` are the same quantity by construction. `HelixDelaunay.computeFVal` for an
  edge is NOT always that: `edgeIsDelaunay`'s own direct empty-diametral-circle check can give a genuinely
  SMALLER value than either containing triangle's own circumradius (this is standard alpha-shape theory, not a
  quirk) — confirmed directly in the worked example above, where facets `{0,1}` and `{1,2}` both take value
  `2.0` from their own direct check, strictly less than either containing triangle's own value (`T2`'s
  `2.002868`, `T3`'s `2.009308`). Using `min over cofaces` there instead would have shifted those two dual
  merges' own birth values from `2.0` up to `2.002868`/`2.009308` — a real, silent bar-value bug, not a
  cosmetic difference; caught by hand-tracing before any code was written. Monotonicity (`fv(facet) <=
  fv(coface)`, guaranteed by `HelixDelaunay`'s own established contract, independent of which formula computed
  either side) is the only property this construction's own descending-sweep ordering actually needs, and it
  holds regardless of which of the two formulas produced the facet's value — so using the TRUE, possibly
  tighter `helix.filtrationValue(facet)` is both safe and required, never `min over cofaces` recomputed
  independently.
- **The precondition is not free** (new finding above): cubical's own "every facet has 1 or 2 top cells" holds
  unconditionally, by construction, for a regular grid — no check was ever needed there. Here it needs an
  explicit, up-front validation pass, with a clear, actionable exception (naming the offending facet(s) and
  their multiplicity, and pointing at `HelixDelaunay`'s own known frontier-walk incompleteness limitation as the
  likely cause) if it ever fails — not a silent wrong answer, and not a crash with an unrelated stack trace.
- **`HelixDelaunay` itself can throw** (its own `assert(validated.nonEmpty)`, unrelated to this construction) —
  nothing extra to do about that; it is already a loud failure, not a silent one.
- **Representatives**: the same running-signed-sum-of-top-cells construction as cubical, using
  `Simplex[Int]`'s own boundary sign convention (alternating by vertex position) in place of `Cube`'s
  rank-among-non-degenerate-axes rule — a different sign RULE, same SHAPE of algorithm.

## Scope for the first implementation

`d = 2` only, mirroring the cubical engine's own first cut exactly (and for the identical reason: 3D needs an
additional, genuinely harder piece — general reduction for `H_1` on whatever the `H_0`/`H_2` union-finds don't
already resolve — deferred, not half-implemented). Input: a `HelixDelaunay` instance directly (not
`AlphaShapes`/`AlphaComplexDQP` — the `require`d precondition is `ambientDimension == 2`, checked the same way
cubical's `require`d `ambientDim == 2`, is, plus the new facet-multiplicity validation above). Validate against
`SimplicialHomologyContext` on: this hand-derived fixture; a hand-built "ring stays open" fixture with a
genuine nonzero-persistence `H_1` bar; a hand-built fixture forcing a real merge between two non-`∞` dual
components (to exercise the orientation-flip arithmetic and the two id/root-resolution fixes item 6 needed,
which this session's own hand example did not); `Fp(3)` sign-genericity on all of the above; and a random-point
property test large enough to have a realistic chance of hitting the ~1-in-3000 precondition violation found
above at least once (so the validation-and-clean-exception path is exercised for real, not just reasoned
about) — assert only that it throws the specific, named exception, not that it never fires.

## Sources

- `.claude/DESIGN-fast-cubical-engine.md`, `.claude/WORKLOG-fast-cubical-engine.md` — the cubical precedent this
  design ports from.
- `AlphaCrossValidationSpec` (`src/test/scala/org/appliedtopology/tda4j/alpha/AlphaComplexSpec.scala`) — the
  pre-existing `HelixDelaunay` robustness findings this design's own "new finding" section builds on.
- CLAUDE.md's "Alpha complex: DQP vs Helix" section — `HelixDelaunay`'s own accepted near-cospherical
  order-dependence limitation (a different failure mode from the incompleteness bug above: a valid-but-
  order-dependent CHOICE of tiling, not a wrong or missing one).
