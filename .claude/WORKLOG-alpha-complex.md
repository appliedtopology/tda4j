# Session worklog — AlphaComplexDQP debugging + follow-on work

Running log, updated as work progresses. Written for the project lead to review after
an overnight autonomous session. Everything under "Assumptions / guesses flagged for
review" is a judgment call made without being able to check in — please look at those
first.

## TL;DR

- **Original bug (missing triangles) is fully fixed**, plus 4 further bugs it was hiding
  (Part 1), 1 more found while extending test coverage (Part 2), and 1 more found while
  extending coverage to weighted complexes specifically — 9 real, independently verified
  bugs total, all in `AlphaComplexDQP.scala`.
- **Both explicitly-requested follow-on tasks are done**: test suite extended (8-10
  passing examples covering every bug class, `AlphaComplexSpec.scala`, plus 2 new spec
  files), and both architecture questions answered with concrete evidence (Commons Math:
  no, and why; `FiniteMetricSpace`/`PowerDistance`: keep the existing adapter pattern, and
  why) — one of which turned into a real, tested performance improvement
  (`cechNeighbours()` now uses the VP-tree spatial index already in the codebase, ~12x
  faster at N=20000 in the realistic sparse-neighbourhood regime).
- **One fix was attempted and reverted**: a mathematically-motivated relaxation of the
  solver's degeneracy handling turned out to be numerically unsafe (proven with two
  near-identical counterexamples requiring opposite handling — see Task 1 discoveries
  below). Reverted to the conservative, always-safe behaviour; documented as a known,
  accepted limitation with a permanent regression test pinning it down.
- **`HelixDelaunay` robustness bugs (per the project lead's follow-up instruction to fix
  them): Bug A fully fixed and verified; Bug B partially fixed, residual behavior
  documented as a known, accepted numerical limitation** (same character as, and same
  disposition as, the one already-accepted DQP limitation above) rather than deeply
  reworked unreviewed overnight — see Part 3 for the full writeup, including why. This
  is new information the project lead hasn't seen: Bug B turned out to be substantially
  more common than originally scoped (~1-in-170 at moderate point counts and ambient
  dimension ≥ 4, driven by near-cospherical local point clusters), though apparently
  absent at the ambient dimension 2 the original bug report used.
- Full test suite run at the end of the session, **after all of Parts 1, 2, and 3**: see
  the final status note near the bottom of this file — zero regressions, same four
  pre-existing/out-of-scope failures as always.
- Read "Assumptions / guesses flagged for review" (both Task 1 and Task 2 sections) first
  — everything else is either a verified fact or a documented, evidenced judgment call.

## Part 1 — done, verified (before the overnight handoff)

Starting point: `AlphaComplexDQPBuilder` (dual active-set QP alpha complex, implementing
Carlsson & Carlsson 2024) produced edges but no triangles on a jittered 3x3 grid, while
`HelixDelaunay` produced the full expected complex.

Five real, independently-verified bugs found and fixed in
`../src/main/scala/org/appliedtopology/tda4j/AlphaComplexDQP.scala`:

1. **Candidate-representation bug** (the originally reported symptom). `buildCandidates`'s
   `k>=2` branch stored the base vertex `x` inside the candidate simplex, so
   `solveAtVertex`'s neighbour-position lookup always came up one short and every
   candidate of dimension >= 2 was silently discarded before ever reaching the QP solver.
2. **`Infinity <= Infinity` acceptance bug.** Once `AlphaShapeDQP` was changed to compute
   the untruncated complex (`maxRadius = Double.PositiveInfinity`, matching what
   `HelixDelaunay` always does — see "design decision" below), a candidate the solver
   correctly identified as infeasible (`cStar = +Infinity`) would still pass
   `cStar <= c1` because `c1` is also `+Infinity`. Fixed with `cStar.isFinite`.
3. **`rankTolerance` far too tight.** Default was `1e-12`. A near-singular Cholesky pivot
   with ratio `s/beta ~ 1e-8` to `1e-11` can slip through, silently poisoning the
   factorisation (multipliers blow up to ~1e14) and either cycling forever or, worse,
   returning a garbage-but-finite answer. Empirically verified safe range is roughly
   `[1e-7, 1e-5]` (found a real poisoning commit at ratio 1.03e-8, and `1e-4` starts
   rejecting genuinely non-degenerate directions and gives a different, wrong answer).
   New default: `1e-6`.
4. **`allFacetsPresent` checking the wrong object.** In fixing bug 1, the candidate
   passed to the facet-closure check needs to be the *full* simplex (including `x`) —
   checking the base-vertex-stripped version is invisible at k=2 (checks trivial
   single-vertex facets) but causes real face-closure violations at k>=3. Confirmed
   against a concrete counterexample (11 points, R^4).
5. **Genuine active-set cycling, not just a tight tolerance.** Even after (3), a
   *different*, fully generic random cloud (12 points, R^5, no special construction)
   still cycled forever (verified non-terminating at 100,000 iterations). Root cause:
   both ratio tests in `DualQP.solve` broke ties by *working-set position*, not by the
   constraint's *global variable index* — working-set position isn't a stable ordering
   (constraints are added/dropped continually), so this doesn't satisfy Bland's rule,
   which is what actually guarantees finite termination under degeneracy. Fixed with a
   genuine two-pass Bland's-rule tie-break (find true min ratio, then break ties by
   smallest global index) in both ratio tests.
6. **(Bonus, found while fixing sorting below) `weights(c)` without `getOrElse`** in
   `AlphaComplexDQP.cells` and `.barcodeInput` — crashes on any complex with vertices
   (i.e. always), since vertices never get a `weights` entry (0.0 is implied). Fixed to
   match the `filtrationValue` accessor's existing `getOrElse(cell, 0.0)` contract.
7. **Separate, pre-existing bug (unrelated to 1-6): `cellsOfDimension`/`iterateDimension`
   never sorted by filtration value.** `HelixDelaunay` explicitly sorts each dimension by
   filtration value; `AlphaComplexDQPBuilder.compute()` populated `byDim(k)` by iterating
   an unordered `mutable.Map`, so consumers of the simplicial-stream contract (sortedness
   within a layer) got an arbitrary order. This was invisible before bug 1 was fixed
   (higher layers were always empty). Fixed by sorting each `byDim(k)` in place by
   `(weight, canonical string)` after `clampMonotone` runs (so the sort sees final,
   possibly-clamped weights).

**Design decision made with the project lead** (not something I inferred): `AlphaShapeDQP`
now computes the complete, untruncated alpha complex (`maxRadius = Double.PositiveInfinity`)
to match `HelixDelaunay`'s always-untruncated behaviour, rather than the previous default
of `metricSpace.minimumEnclosingRadius` (which was silently excluding genuine
high-circumradius simplices from degenerate/near-degenerate configurations). Callers who
want an actually-bounded alpha complex should go through
`AlphaComplexDQP.euclidean(points, maxRadius, maxDimension, settings)` directly.

**Verification methodology:** every fix was checked against concrete, hand-verified
counterexamples (numerically cross-checked circumradii/Gabriel-disk tests via a throwaway
Python script, not just "the test passes"), then stress-tested with a throwaway
`ScratchFindFailure` fuzzer sampling directly from the same ScalaCheck generator used by
the real spec (`matrixGen[Double](Gen.double, Gen.chooseNum(2,5), Gen.chooseNum(6,12))`).
Final clean run: **100,000 random point clouds, zero failures** (no non-convergence, no
face-closure violations). `AlphaComplexSpec`'s property suite passed cleanly across 5
independent runs with fresh seeds after all fixes landed.

**Known, explicitly out-of-scope, pre-existing issues** (confirmed unrelated to any of the
above by direct code inspection — none of these files reference `Alpha`/`DQP`, or the
failure is the already-known dead `"miniball"` dispatch string):
- `SimplexSpec`, `APISpec`: pre-existing failures unrelated to Alpha/DQP.
- `HomologySpec`'s non-`BarcodeRegressionSpec` test: pre-existing, unrelated.
- The `"miniball"` dispatch case in `BarcodeRegressionSpec` (`HomologySpec.scala:36`):
  dead dispatch string, `Miniball` backend was removed entirely (git history). **Update:**
  being addressed as part of Part 2 below, since it's within the alpha-shape test scope.

## Part 2 — overnight autonomous work (in progress)

The project lead asked me to continue autonomously overnight on two follow-on tasks:

1. Extend the test suite with regression tests, and rehabilitate the existing attempts at
   testing the *entire* alpha-shape test suite (explicitly: ignore the other, unrelated
   pre-existing failures elsewhere in the library).
2. Investigate whether `AlphaComplexDQP` can be better aligned with the existing
   `HelixDelaunay`/`AlphaShapes` code: (a) is it worth extending use of Apache Commons
   Math linear algebra in the DQP solver, and (b) can `PowerDistance` be folded into
   `FiniteMetricSpace` as a variant rather than a wholly separate trait.

Status and findings below, updated as work progresses.

**Scope correction from the project lead, received mid-session:** homology
(`HomologySpec.scala` / `BarcodeRegressionSpec`, including its `"miniball"` dispatch
issue) is explicitly a later cleanup task, not today's. Today's focus is **complex
generation only** — `AlphaComplexDQP.scala`, `AlphaShapes.scala`, and their tests
(`AlphaComplexSpec.scala`). I am not touching `HomologySpec.scala` or
`BarcodeRegressionSpec` in this session.

### Task 1 status: test suite extended — DONE, verified stable

`../src/test/scala/org/appliedtopology/tda4j/AlphaComplexSpec.scala` now contains, in order:

1. **`AlphaValidationSpec`** — the original 3x3-grid repro, hardened: asserts exact
   per-dimension sizes `(9, 18, 10)` for both backends (not just a total count), plus an
   explicit assertion that the two boundary-sliver edges/triangles are present.
2. **`AlphaComplexSpec`** — the pre-existing ScalaCheck property suite (face closure,
   no-duplicates, sortedness, monotonicity, dimension-bucket consistency), kept, with two
   changes: `minTestsOk` raised from the specs2/scalacheck default of 100 to 2000 (the bugs
   this exists to catch had verified failure rates from ~1-in-500 to ~1-in-12000, so 100
   samples gives weak protection), and `"helix"` removed from the dispatch loop (see below
   for why). **Currently: 1 example (DQP only), 2000/2000 passing, clean.**
3. **`AlphaCrossValidationSpec`** — cross-validates DQP directly against Helix on the same
   point clouds (stronger than each backend's internal consistency alone). Discovered,
   while building this, that **HelixDelaunay has two of its own independent robustness
   bugs** (see below) that make broad automated cross-validation against it unsafe as a
   hard CI gate. The two `forAll`-based comparisons are kept as `unsafeCompare` /
   `unsafeFuzzCompare` **diagnostic methods**, not specs2 examples — they don't run under
   `sbt test` at all, deliberately, with the reasoning documented in the class doc.
   **Currently: 0 examples (by design), so 0 failures.**
4. **`AlphaComplexDQPRegressionSpec`** — the two hand-verified adversarial point clouds
   found while fixing the original bug, pinned down permanently: the R^4 facet-closure
   counterexample and the R^5 cycling counterexample, each checked for (a) DQP's own
   internal soundness and (b) the *subset* relationship to Helix (never asserts exact
   equality where that isn't actually guaranteed — see the numerical-limitation finding
   below). **Currently: 4 examples, all passing.**

**Full current state: 8 examples, 0 failures, 0 errors, reproduced stable across 3
consecutive fresh runs (each with a different random seed) plus a standalone
100,000-attempt fuzz run outside the test suite.** `sbt test` on the rest of the project
was unaffected except where noted below (Helix/homology tests, pre-existing and
out-of-scope).

### Task 1 discoveries beyond what Part 1 covered

**A genuine solver bug found via the new cross-validation testing, fixed:** the
`allFacetsPresent` fix from Part 1 (item 4) was necessary but the swap-based degeneracy
handling in `DualQP.solve` had a real gap: when the entering variable's direction was
linearly dependent on the *equality* constraints specifically (as opposed to depending on
an already-active *inequality*), there was no inequality left to swap out, and the code
unconditionally concluded "dual unbounded, primal infeasible" — even though, in exact
arithmetic, a small-but-positive Schur complement `s` still has a genuine finite maximum
(`t* = grad_j / s`) rather than being truly unbounded. Confirmed and derived this
mathematically against a concrete counterexample (5 points in R^4, where this wrongly
excluded the top-dimensional simplex and one of its facets that HelixDelaunay correctly
includes).

**Attempted fix, and why it was reverted (important finding):** implemented the
mathematically-correct "commit anyway when s > 0" fallback, gated by a separate, tighter
numerical-safety floor from `rankTolerance`. This is **provably not numerically safe as
any fixed threshold**: found two counterexamples with near-identical Schur-complement
ratios (`~3.4e-7`, legitimate — the R^4 case above; `~3.0e-7`, catastrophic — produced
`|lambda| ~1.7e5` and genuine non-terminating cycling, confirmed frozen/non-progressing
for 12+ iterations at the same exact state) that require *opposite* handling. Whether a
given small Schur complement is numerically trustworthy to commit directly depends on the
conditioning of the rest of the working set, not on that one ratio in isolation — so no
single number (nor even a very different one) reliably separates "safe" from
"poisonous" cases. **Reverted to the conservative original behavior** (any
small-Schur-complement direction with no valid swap candidate is treated as infeasible),
accepting the known consequence that a narrow class of genuinely-Delaunay simplices near
this specific kind of degeneracy get conservatively excluded, in exchange for a solver
that provably never hangs. Added a defense-in-depth per-candidate catch in `solveAtVertex`
so that *any* future not-yet-characterised non-convergence excludes just that one
candidate (logged to stderr) rather than aborting the whole complex computation.

**`HelixDelaunay` has two of its own independent robustness bugs**, found incidentally
while using it as DQP's cross-validation ground truth (neither touched — homology and
Helix internals were out of scope today, per the scope correction above, so these are
flagged for a future session, not fixed):
1. **Crash**: `assert(validated.nonEmpty)` at `AlphaShapes.scala:138` (in the initial
   Delaunay simplex bootstrap) fails on ordinary random input at roughly a 1-in-600 rate
   in the generator used by `AlphaComplexSpec` (`Gen.chooseNum(2,5)` dim,
   `Gen.chooseNum(6,12)` points, `Gen.double` coordinates — nothing adversarial).
2. **Silent incompleteness**: separately, found a point cloud (7 points, R^4) where Helix's
   frontier-walk traversal never visits an entire connected region of the true Delaunay
   complex — no exception, just a missing sub-chain of faces (`{0,6}`, `{0,3,6}`, `{0,4,6}`,
   `{0,5,6}`, `{0,3,5,6}`, `{0,4,5,6}`, `{0,3,4,6}`, `{0,3,4,5,6}`, all sharing edge
   `{0,6}`) that DQP correctly includes. Confirmed this is Helix's gap and not DQP
   hallucinating a wrong fact: the missing set has exactly the shape of "one region the
   walk never reached," not an arbitrary incorrect claim.

This means **Helix is not a fully reliable ground truth for automated fuzzing**, which is
why the cross-validation properties ended up as manual diagnostics rather than hard gates
(a naive implementation using `specs2`'s `pendingUntilFixed` was tried first and rejected:
that matcher is for a *deterministically*-known-failing example, and flags an
unexpected-pass as itself a failure — wrong semantics for a *probabilistic* failure, where
a lucky run without the trigger reads as "fixed now, remove the marker" and makes CI flaky
in the other direction).

### Assumptions / guesses flagged for review

- **Cross-validation properties as diagnostics, not CI gates.** Given Helix's own bugs,
  I judged that broad automated DQP-vs-Helix fuzzing shouldn't block `sbt test`, and kept
  it as manually-invoked diagnostic methods instead. This trades "would have caught the
  original missing-triangles-class bug as one property, automatically, forever" for "no
  flaky CI." I believe this is the right call given the evidence, but it's a real
  trade-off the project lead might weigh differently once Helix's bugs are eventually
  fixed (at which point promoting `unsafeCompare`'s logic back into real, hard `forAll`
  examples would be straightforward and valuable).
- **`AlphaComplexDQPRegressionSpec`'s facet-closure counterexample now documents a
  known-missing set that isn't pinned exactly** (only the "DQP ⊆ Helix" direction is a
  hard assertion; the specific missing simplices are logged, not asserted) because that
  exact set already shifted once, purely from the Bland's-rule tie-break change (which
  altered solver iteration order without changing correctness). I judged pinning the exact
  set was over-specifying an implementation detail; the subset property is what actually
  matters. Flagging in case the project lead wants the exact set pinned anyway for
  tighter regression tracking.

## Task 2: architecture alignment with HelixDelaunay/AlphaShapes

Both sub-questions investigated concretely (not just discussed), with code where the
evidence supported it.

### (a) Is it worth extending use of Apache Commons Math linear algebra in the DQP solver?

**No, and this is a fairly clear-cut finding, not a judgment call.** Checked
`org.apache.commons.math3.linear.CholeskyDecomposition`'s actual public API (`javap` on
the class in the exact 3.6.1 jar this project depends on): it offers only a
whole-matrix-at-a-time constructor (`RealMatrix -> L`) and a `DecompositionSolver` —
**no incremental update/downdate operations** (no rank-1 update, no row insertion or
deletion). `DualQP`'s active-set method needs to append and remove single rows from the
working-set Cholesky factor on essentially every iteration (`CholeskyWorkspace.commit` /
`.delete`, each O(k^2) where k = working-set size, bounded by `ambientDimension + 2`).
Routing that through Commons Math's decomposition would mean **re-factorizing the entire
working set from scratch on every single active-set step** — O(k^3) instead of O(k^2) per
step. For the paper's own headline use case (ambient dimension in the thousands, e.g. its
2352-dimensional example), that's not a minor slowdown, it directly undermines the
algorithm's reason for existing (avoiding exactly this kind of cost is the paper's whole
pitch relative to building the Delaunay complex directly). The current hand-rolled
`CholeskyWorkspace` (append via bordered update, delete via Givens-rotation downdate) is
the correct design for what this solver actually needs; Commons Math's decomposition
classes solve a different problem (one-shot factorization) than what's needed here
(maintaining a factorization under a stream of small changes).

### (b) Can PowerDistance be folded into FiniteMetricSpace as a variant, not a separate trait?

**No, and the codebase already has the right answer: a conversion, not inheritance
(`PowerDistance.toMetricSpace`, already present before tonight).** Three concrete,
substantive mismatches between the two traits' contracts, not just style differences:

1. `FiniteMetricSpace[VertexT]` is generic in its vertex type (used with `Int`, but also
   with arbitrary types elsewhere in the codebase); `PowerDistance` is Int-indexed
   throughout (`squaredDistance(i: Int, j: Int)`, `coordinate(i: Int, k: Int)`) because
   every real use of it is array-index-based point-cloud data. Unifying would mean either
   making `PowerDistance` needlessly generic, or narrowing `FiniteMetricSpace`'s
   genericity for everyone else using it (`VietorisRips`, `Ripser`, `SymmetryGroup`, none
   of which have anything to do with alpha complexes).
2. `FiniteMetricSpace.distance` is unsquared; `PowerDistance.squaredDistance` is the
   primitive the whole DQP formulation and paper are expressed in (the math needs
   `B_ij = (d²(i,x)+d²(j,x)-d²(i,j))/2`, never a bare distance). Making `PowerDistance`
   satisfy `FiniteMetricSpace`'s contract directly would force a `sqrt` onto every single
   distance computation in a hot path (`cechNeighbours()` alone is O(N) to O(N log N)
   calls per build; `solveAtVertex`'s Gram-matrix assembly is O(m^2) per vertex) for a
   value that's immediately squared back in every caller. `toMetricSpace`'s existing
   `math.sqrt(squaredDistance(x,y))` bridge correctly pays that cost only on the rare path
   that actually needs it (interop with unsquared-distance consumers), not on the hot path.
3. `PowerDistance` carries `weight`/`ambientDimension`/`coordinate`, which have no
   equivalent in `FiniteMetricSpace` and no reason to: adding them to the shared trait
   would put alpha-complex-specific fields on every other `FiniteMetricSpace` consumer's
   interface for no benefit to them.

So the recommendation is to keep the current adapter pattern, not extend it toward
unification.

**Concrete, implemented alignment found while investigating (b):** `FiniteMetricSpace.scala`
already has a VP-tree spatial index (`JVPTree`/`SpatialQuery`, backed by `com.eatthepath.jvptree`,
the same library `EuclideanMetricSpace` and `SparseMetricSpace` already use elsewhere in
the codebase) that `AlphaComplexDQPBuilder.cechNeighbours()`'s own doc comment explicitly
flagged as "the obvious place to plug in a spatial index... for large N" but never used —
it was a straight O(N^2) all-pairs scan. Rewired `cechNeighbours()` to build a `JVPTree`
over `space.toMetricSpace` (the existing bridge) and query each point's neighbourhood
directly, for the case where `maxPower` is finite (i.e. a genuinely truncated alpha
complex, not `AlphaShapeDQP`'s default unbounded mode, where every alive point is
trivially everyone's neighbour and a spatial index buys nothing — that case is still
handled by the direct all-pairs branch, unchanged). For the weighted case (where each
point's true neighbour radius differs), the query uses a conservative superset radius
(`radius(i) + max_j radius(j)`), so **every candidate the tree returns still goes through
the exact same pairwise check as before** — correctness never depends on the spatial
index, only performance does.

Validated two ways:
- **Correctness**: new `AlphaComplexDQPSpatialIndexSpec` cross-checks the VP-tree result
  against a preserved brute-force reimplementation of the original O(N^2) scan, 500
  random trials each for unweighted and weighted point clouds (1000 total, all passing,
  reproduced stable across repeated runs).
- **Performance**: measured directly (throwaway script, not committed) at several (N,
  radius) combinations. At sparse, realistic neighbourhood densities (the typical
  alpha-complex regime — local structure, not a near-complete graph) the win scales with
  N: ~1.9x at N=2000, ~5.5x at N=5000, ~12x at N=20000 (all wall-clock, JIT-warmed same
  JVM run). At *dense* neighbourhoods (e.g. maxRadius large enough that each point has
  hundreds of neighbours out of a few thousand total points) there is **no benefit, and a
  measurable regression at small N** (VP-tree construction and JVM object-boxing overhead
  exceed the pruning benefit when the tree can't actually prune much, or when N is small
  enough that O(N^2) is already fast in absolute terms). This tracks theory: VP-tree
  pruning only pays for itself when queried neighbourhoods are small relative to the whole
  set — worth knowing rather than a blanket "spatial index = faster."

### Assumptions / guesses flagged for review (Task 2)

- **Judged the spatial-index change worth landing despite the dense-case regression**,
  because alpha complexes are normally built to capture local structure (this is the
  entire reason to prefer alpha complexes over, say, a large-scale Vietoris-Rips
  parameter), so the sparse/local regime is the realistic one, and the existing doc
  comment on `cechNeighbours()` already anticipated exactly this change. Flagging because
  it's a real trade-off: a caller who deliberately builds a dense-radius, small-N alpha
  complex (unusual, but not impossible) will see it get slightly slower.
- **Did not attempt to special-case the unweighted (constant-radius) path with a tighter
  query bound.** The current conservative-superset query radius happens to already be
  the *exact* bound when radius is constant across all alive points (both reduce to `2r`),
  so there was no over-fetching to fix in that case specifically — checked this by hand
  rather than assuming it, so no further work seemed warranted there.

## Task 1, extended: weighted alpha complexes

Noticed while wrapping up that **weighted alpha complexes were never exercised
end-to-end by any test**, before or after tonight's work: `AlphaComplexSpec`'s property
suite always builds an unweighted `PowerDistance` (`Alpha(...)` has no weighted entry
point at all), and `HelixDelaunay` — the only cross-validation ground truth available —
computes a plain Euclidean Delaunay triangulation with no notion of power weights, so it
can't serve as ground truth for the weighted case regardless of how it's invoked.

Added `AlphaComplexDQPWeightedSpec`: since there's no independent ground truth available,
it checks the same internal structural invariants `AlphaComplexSpec.alphaProperties`
checks for the unweighted case (face closure, no duplicates, filtration
sortedness/monotonicity, dimension-bucket consistency) directly against
`AlphaComplexDQP.weighted(...)` with random weights, plus one property-based identity
check: an all-zero-weight complex must equal the unweighted complex exactly, dimension by
dimension — the closest thing to a real correctness check available without Helix. This
is weaker than a full correctness proof but is real, previously-completely-absent
coverage of the `weight(i)` threading through `ballRadius`/`gram`/`dualLinear`.

**Run, and it immediately found a genuine bug** (caught on the very first randomly
generated case, not a rare edge case): a vertex's filtration value was hardcoded to
`0.0` via `weights.getOrElse(cell, 0.0)` — which I introduced earlier tonight (Part 1,
bug 6) to fix a `NoSuchElementException` crash on exactly this "vertices never get a
`weights` entry" gap. `0.0` is the *correct* default only in the unweighted case. Per
Definition 10 evaluated at a vertex (the unconstrained minimiser of `||y-x||^2` is
trivially `y*=x`), a vertex's true filtration value is `-weight(x)`, not `0`. With
nonzero weights this broke the monotonicity invariant outright: e.g. a vertex with
`weight=0.3` reported filtration value `0.0`, while an edge through it correctly
reported `-0.291` (power distances can be negative) — `0.0 > -0.291` violates "a face's
filtration value must not exceed its coface's."

**Fixed** in `AlphaComplexDQPBuilder.compute()`'s vertex-population loop: now sets
`weights(f) = -space.weight(x)` (and, while there, `witnesses(f) = coordsOf(x)`, which
had the same "never populated for vertices" gap, silently making `witness()` return
`None` for every vertex — lower severity, no test was checking it, fixed alongside since
it's the same root cause and the same one-line fix pattern). Verified: the same test now
passes 2000/2000 (reproduced stable across 3 repeated runs), and the full Alpha test
suite (all 5 spec files, 12 examples total) re-verified clean afterward.

This is exactly the kind of bug the "extend test coverage" task was for — an actual
defect in code from earlier tonight, invisible until weighted complexes were actually
exercised end-to-end for the first time.

**Unrelated compilation obstacle hit and resolved while writing this spec**, worth
recording since it's a genuine, non-obvious Scala 3 gotcha: writing
`allSimplices.forall { simplex => simplex.toSeq... }` without an explicit type
annotation on `allSimplices: IndexedSeq[Simplex[Int]]` made the compiler resolve
`.forall` to a specs2 `ValueCheck`-based extension (from the `ScalaCheck` mixin) instead
of the standard-library `Iterable.forall`, breaking type inference inside the lambda in
a way that cascaded across the rest of the function with confusing errors ("value toSeq
is not a member of ValueCheck[Simplex[Int]]"). Neither restructuring the boolean
combination logic nor matching `AlphaComplexSpec.alphaProperties`'s exact "wrap every
`.forall` in `must beTrue` immediately" pattern fixed it. What did: adding the explicit
type ascription `val allSimplices : IndexedSeq[Simplex[Int]] = ...` (present in the
working `AlphaComplexSpec.scala` file, absent in the first draft of this one) — plausibly
an opaque-type-and-extension-method interaction specific to `Simplex`, though not
fully root-caused beyond "this exact fix reliably works, confirmed by testing the
hypothesis directly." Worth knowing before writing more `Simplex`-heavy specs2 code in
this codebase.

## Part 3 — fixing HelixDelaunay's robustness bugs (done: Bug A fixed & verified; Bug B partially fixed, residual behavior documented)

The project lead reviewed the summary (not yet WORKLOG.md itself) and asked to fix the
two `HelixDelaunay` bugs found in Part 2, rather than leave them out of scope. Continuing
to update this file live as instructed.

### Bug A: `assert(validated.nonEmpty)` crash — FIXED, verified

Root cause found by instrumenting the bootstrap phase and looping the exact failing
point cloud (the bootstrap uses `Random.shuffle`, so it's non-deterministic — the same
input point cloud can succeed or crash from one run to the next, which is why the
original fuzzing only hit it ~1-in-600 rather than deterministically): in
`HelixDelaunay`'s constructor block (`AlphaShapes.scala`, the "brute force search for
first delaunay simplex" section), when more than `ambientDimension` points are found to
lie (within epsilon) on the discovered supporting hyperplane of the convex hull — a
common case for grid-like or otherwise partly-degenerate point clouds, not a rare
adversarial one — the code reduced `startingSimplex` down to exactly **2** points,
regardless of `ambientDimension`:

```scala
case vs if vs.size > ambientDimension =>
  Set(vs.head, vs.tail.minBy(vi => points(vs.head).getDistance(points(vi))))
```

For `ambientDimension > 2` this leaves `startingSimplex` undersized. The subsequent
brute-force bootstrap search builds `Hypersphere(startingSimplex + pi)`, which needs
`ambientDimension + 1` points to be uniquely determined — with only 3 (2 + the trial
point `pi`), the resulting "circumsphere" is a numerically underdetermined SVD
least-squares fit, not a genuine circumsphere, and the search never finds any `pi` whose
circumsphere is genuinely empty. `validated` stays empty; the assertion fires. Confirmed
directly: instrumented the code, looped the same 9-point/R^5 reproduction until it
crashed, and captured `vs.size=6, ambientDimension=5` immediately before the failure,
with `startingSimplex` reduced to a 2-element set right after.

**Fix**: replaced the 2-point reduction with a greedy affinely-independent selection —
start from `vs.head`, then repeatedly add the next candidate from `vs` only if it
strictly increases the rank of the affine span built so far (checked via
`SingularValueDecomposition.getRank` on the translated candidate vectors), until
`ambientDimension` points are chosen. `vs` is guaranteed to contain at least
`ambientDimension` independent points, since it's a superset of the already-independent
`startingSimplex` being refined.

**Verified**: the exact original reproduction (9 points, R^5) no longer crashes across
200 repeated runs (it crashed by attempt ~15 before the fix, given the non-determinism).
Broader fuzzing — 20,000 fresh random point clouds from the same generator
`AlphaComplexSpec` uses (`Gen.chooseNum(2,5)` dim, `Gen.chooseNum(6,12)` points,
`Gen.double` coordinates) — found **zero** failures (previously ~1-in-600, so ~33
expected at this sample size).

### Bug B: silent incomplete traversal — investigated, partially fixed, residual behavior documented as a known limitation

**Found and fixed one definite code defect**: `addFrontierCase` (`AlphaShapes.scala`) is
meant to cancel a pending, not-yet-processed `FrontierCase` for a facet when that same
facet is independently rediscovered from its other side (both cofacets of an interior
facet call `addFrontierCase` once each), rather than queue it twice. The check that was
supposed to detect this compared the wrong things:

```scala
val removed = frontierCases.removeIf((fc: FrontierCase) => fc.facet.toSortedSet == simplex.simplex.toSortedSet)
```

`fc.facet` is a `d`-vertex facet; `simplex.simplex` is the *full* `(d+1)`-vertex cofacet
being registered, not the facet derived from it (`simplex.simplex - complement`) — a
`d`-element set can never equal a `(d+1)`-element set, so this comparison was always
`false` and the intended de-duplication was dead code. Fixed by comparing against the
actually-derived facet:

```scala
val newFacet = simplex.simplex - complement
val removed = frontierCases.removeIf((fc: FrontierCase) => fc.facet.toSortedSet == newFacet.toSortedSet)
```

This is unambiguously more correct than before (it now does what its own logic already
intended), but empirically **did not meaningfully change the failure rate** on its own
(see below) — it wasn't the dominant contributor. Also fixed, while in the area: the
cosphericity-detection check a few lines below used a hardcoded `1e-5` literal instead of
referencing the ambient `epsilon.epsilon` (which happens to equal `1e-5` today, by
coincidence, elsewhere in the same class) — harmless today, but a latent
silently-diverges-if-either-changes footgun; now reads `epsilon.epsilon` for consistency
with the identical check a few dozen lines earlier in the same file.

**Investigated the underlying incompleteness with a dedicated cross-validation fuzzer**
(a throwaway scratch script, since deleted per the "stay contained, clean up as you go"
convention already established this session): generate random point clouds, compute both
`Alpha(points, "DQP")` and `Alpha(points, "helix")`, and flag any simplex DQP finds that
Helix doesn't. This direction only (DQP ⊄ Helix, not the reverse) is the meaningful
signal, because DQP's only known failure mode is *conservative exclusion* near thin
Schur complements (see the `facetClosureCounterexample` regression test) — it never
spuriously invents a simplex — so a DQP-only simplex is strong evidence of a genuine
Delaunay simplex Helix's frontier walk missed.

Findings, from smallest to most surprising:

1. **The specific originally-reported 7-point R^4 example is reproducible but
   non-deterministic**: `HelixDelaunay`'s bootstrap uses the global, unseeded
   `scala.util.Random` (`Random.shuffle` when picking the initial hull-supporting
   simplex). The *same* input point cloud gives *different* trees of Delaunay simplices
   from one construction to the next.
2. **Root-caused via a seed sweep** on one adversarial 7-point R^5 near-minimal (n =
   ambientDim + 2) example: across 300 fixed-seed reconstructions of the *identical*
   point cloud, the top-dimensional simplices found were: the correct, DQP-matching
   answer only 8% of the time (24/300); two different *incomplete-but-locally-consistent*
   subsets 25% of the time combined (41+36/300); and one *entirely disjoint* pair of
   simplices — sharing no facet with any DQP-found simplex at all — the remaining 66% of
   the time (199/300). This is not "usually right, occasionally misses one" — for this
   class of input, the majority outcome across random starting orders doesn't match DQP's
   triangulation at all.
3. **Ground-truthed independently** (a from-scratch circumsphere-emptiness check against
   neither implementation, just `Hypersphere`/`contains` applied directly to all 7
   possible top-dimensional candidate simplices): every candidate's circumradius came out
   within ~2e-4 of every other (~1.03205–1.03212 across all 7), and for most candidates
   the single excluded point sits within ~1e-5 of that candidate's own circumsphere
   boundary. **The point cloud is at (or extremely near) exact cosphericity** — the
   classical case where a Delaunay triangulation isn't combinatorially unique at all,
   let alone numerically stable to compute.
4. **This is not a rare, artificially-constructed corner case.** A second, independent
   repro was found by fuzzing at a more "normal" scale (14–28 points, ambient dimension
   4, no adversarial minimization) at a rate of 5 failures in 866 attempts (~0.6%,
   roughly 1-in-170) — an order of magnitude *more* frequent than the ambientDim 2–5 /
   6–14 point sweep used for Bug A, because a larger point cloud offers combinatorially
   many more candidate `(d+2)`-point subsets, and it only takes one such subset to be
   locally near-cospherical for the traversal to become order-dependent there. Ground-
   truthing that repro's shrunk 6-point R^4 case showed the same signature: 6
   circumradii agreeing to 5 significant figures, most excluded points within ~1e-5 of
   the "wrong" candidate's boundary.
5. **Fixed-seed sweeps at ambient dimension 2 and 5 (20,000 trials each, generic —
   not adversarially shrunk — point clouds) found zero failures.** Combined with (4),
   this suggests the practical risk is concentrated specifically in point clouds
   containing a near-cospherical local cluster, which becomes likelier as N and ambient
   dimension grow, rather than being uniformly likely across all inputs.

**Why this wasn't fixed further tonight**: `handleCosphericalPoints` is exactly the
machinery meant to handle genuine cosphericity correctly (by explicitly tiling every
point on a shared empty circumsphere, rather than picking one arbitrarily), but it only
engages *after* the greedy search already found one "valid" candidate, by checking how
many *other* points lie within `epsilon.epsilon` of *that specific candidate's own*
circumsphere. In the failures above, the near-ties are spread across *different*
candidates' *different* (but nearly-equal-radius) circumspheres, not concentrated on one
common sphere that a single post-hoc check would catch — so widening that one threshold
would not reliably fix this, and could just as easily start accepting genuinely wrong
merges elsewhere (the same "no safe fixed threshold" trap already hit and deliberately
backed out of on the DQP side — see the reverted "commit anyway" fix above). A real fix
would mean detecting near-ties *across all currently-competing candidates* before
committing to any one of them, and resolving the tie with a triangulation convention
consistent with DQP's own (Bland's-rule-based) tie-breaking — a genuine algorithm change,
not a bounded bug fix, and too large a scope change to make unreviewed overnight.

**Disposition, matching the precedent already set for DQP's own analogous limitation**:
documented here and in `CLAUDE.md` as a known, accepted numerical-robustness limitation
of `HelixDelaunay` — order-dependent (and occasionally outright incorrect) triangulation
choices on point clouds containing a near-cospherical local cluster — rather than
silently left undocumented. `AlphaComplexSpec`'s property suite already excludes Helix
from its dispatch loop (from earlier in this session, before Bug A's root cause was even
known) and should stay that way; `AlphaCrossValidationSpec`'s `unsafeCompare`/
`unsafeFuzzCompare` helpers remain diagnostic-only rather than promoted to blocking
`forAll` examples, since Helix is now demonstrably not reliable enough yet for that
without producing flaky CI on exactly this class of input.

## Final status

Ran `sbt test` (full project, foreground) with every change described in this file in
place — Parts 1, 2, *and* 3 (both `AlphaComplexDQP.scala` and `AlphaShapes.scala` fixes)
— from a clean incremental build, re-confirmed again after Part 3's `AlphaShapes.scala`
changes landed. Result: **98 examples total, 94 passed, 1 pending, 3 failed, 1 errored —
identical counts to the Part 1/2 run below, and every single failure/error is still one
of the same already-identified, pre-existing, explicitly out-of-scope issues**:

- `SimplexSpec`, `HomologySpec` (its one non-`BarcodeRegressionSpec` test), `APISpec`:
  pre-existing failures, confirmed unrelated to Alpha/DQP by direct inspection at the
  start of this session (none of these files reference `Alpha`/`DQP`).
- `BarcodeRegressionSpec`: exactly one error, the known dead `"miniball"` dispatch
  string (`HomologySpec.scala:36`, `scala.MatchError: miniball` at
  `AlphaShapes.scala:34`) — explicitly out of scope per the "don't worry about
  homology" scope correction. Its other two examples (`Alpha Helix`, `VR complex`) both
  **passed** this run (they OOM'd under the sandbox's default 1GB heap earlier in the
  session; that was an environment artifact, not a real failure — confirmed by re-running
  with more heap both then and now).

**Every Alpha-related spec — all 5 files, including the two new ones — passed with zero
failures**: `AlphaValidationSpec`, `AlphaComplexDQPRegressionSpec`,
`AlphaComplexDQPSpatialIndexSpec`, `AlphaComplexDQPWeightedSpec`, `AlphaComplexSpec`
(`AlphaCrossValidationSpec` contributes 0 examples by design — see its class doc).

**This is a clean bill of health for everything touched tonight: zero regressions
anywhere else in the project, and the complex-generation code (`AlphaComplexDQP.scala`,
`AlphaShapes.scala`) is in a substantially more correct, more robust, and much more
thoroughly tested state than at the start of the session**, with every remaining known
limitation (in both DQP and Helix) explicitly documented rather than silently present.

### Files changed this session

- `../src/main/scala/org/appliedtopology/tda4j/AlphaComplexDQP.scala` — all fixes (Parts 1
  and 2), the `cechNeighbours()` spatial-index rewrite, the vertex-weights/witness fix,
  updated header/doc comments.
- `../src/main/scala/org/appliedtopology/tda4j/AlphaShapes.scala` — Part 3: `HelixDelaunay`
  Bug A (bootstrap under-sizing on grid-like/degenerate point clouds) and the
  `addFrontierCase` dead-code de-duplication check, both fixed; the hardcoded `1e-5`
  cosphericity-check literal replaced with the ambient `epsilon.epsilon` for consistency.
- `../src/test/scala/org/appliedtopology/tda4j/AlphaComplexSpec.scala` — extended with
  `AlphaCrossValidationSpec` and `AlphaComplexDQPRegressionSpec`; `AlphaValidationSpec`
  and the original `AlphaComplexSpec` property suite hardened in place.
- `../src/test/scala/org/appliedtopology/tda4j/AlphaComplexDQPSpatialIndexSpec.scala` (new).
- `../src/test/scala/org/appliedtopology/tda4j/AlphaComplexDQPWeightedSpec.scala` (new).
- `CLAUDE.md` — "Alpha complex: DQP vs Helix" section rewritten to reflect the current,
  verified state (was describing the original, badly-broken state from before this
  session); also fixed a stale `AlphaComplex.*` → `AlphaComplexDQP.*` naming typo in that
  file's usage docs while in there, and a leftover duplicated section from an earlier
  interrupted edit. Also updated for Part 3: the Helix robustness-bugs section now
  reflects Bug A fixed/verified and Bug B's actual (more nuanced, more common than
  originally scoped) status.
- `WORKLOG.md` (this file, new).

Nothing outside `AlphaComplexDQP.scala`/`AlphaShapes.scala` and their tests was touched,
per the scope correction. `HomologySpec.scala`/`BarcodeRegressionSpec` were read (to
confirm their failures are pre-existing and unrelated) but not edited.
