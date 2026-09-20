# Cech complex, built on the genericized VR coface machinery (2026-09-19)

Asked whether the packed Ripser engine and New-VR ("newVR") carry over to Cech complexes, and to plan/build a
Cech implementation. Both prior engines turned out NOT to carry over directly, for different, specific reasons
-- but the plan that emerged (genericize `RipserCofaceSimplexStream`'s coface-generation loop, per the user's
own instinct to skip New-VR and "build cofacets of whatever was in the next lower dimension directly") worked
cleanly, modulo two real floating-point correctness issues found and fixed along the way.

## Why New-VR and packed Ripser don't carry over

**New-VR** (`IncrementalVietorisRipsSimplexStream`, Rieser's algorithm) is structurally a flag/clique-complex
construction algorithm: its whole speed advantage (Algorithm 2's Table-Lookup) prunes candidate vertices using
graph structure, valid only because VR membership is fully determined by pairwise edges. Cech is not a flag
complex (three balls can pairwise-overlap in three different places with no common triple intersection), so
this pruning has no valid analogue for Cech.

**Packed Ripser** (`PackedRipserCohomologyContext`) doesn't carry over either, but for a different reason: its
headline optimizations (`insertionDiameter`'s O(d) incremental recurrence, the apparent-pairs shortcut) are
proven specifically for the max-pairwise-distance functional. Nothing establishes they still hold for the
circumradius functional, so a Cech-adapted packed engine would need those disabled (degrading to base
clearing+reduction) or independently re-derived -- explicitly out of scope for this session; only the naive
engine (`CellularHomologyContext`/`SimplicialHomologyContext`) is used here.

## The actual construction: genericize, don't duplicate

`EnumeratingCofaceSimplexStream`'s general fallback brute-forces the entire `binomial(n, d+1)` power set at
every dimension -- exactly the "reject an entire pruned subtree one candidate at a time" waste the user wanted
to avoid. Its subclass `RipserCofaceSimplexStream` already does the right thing: builds dimension `d+1`
candidates only as cofaces of dimension-`d` survivors, and its coface loop touches `filtrationValue` only
through the already-generic `keptByThresholdAndCriterion`/`sortedByFiltration` -- zero actual VR-specific logic,
the same signature that justified genericizing `PersistenceInChunksContext` earlier.

**Genericized `filtrationValue` into a constructor parameter** on `EnumeratingCofaceSimplexStream`/
`RipserCofaceSimplexStream` (`filtrationValueOverride: Option[PartialFunction[Simplex[Int], Double]] = None`,
defaulting to the existing VR diameter computation). Every existing VR call site is unaffected -- confirmed by a
clean compile with zero call-site changes needed. Named `filtrationValueOverride`, not `filtrationValue`: a
constructor parameter can't share a name with the class's own `override val filtrationValue` member.

**Why this coface-generation shape is valid for Cech, not just VR, and not merely by analogy**: Cech is
downward-closed -- if a point witnesses a simplex's balls having a common intersection, that same point
trivially witnesses every subset's balls having one too. So a valid Cech `(d+1)`-simplex's canonical generating
facet (obtained by removing its own minimum vertex, `RipserCofaceSimplexStream`'s own convention) is guaranteed
to already be sitting in the accepted `d`-dimensional cache -- the enumeration cannot silently skip a real Cech
simplex. Checked empirically, not just trusted from the proof: `CechStreamSpec`'s enumeration-completeness test
compares the coface-loop's per-dimension simplex count against a full brute-force
`combinations(d+1).filter(cechValid)` count on a small cloud -- exact match at every dimension.

## The NaN-sentinel side-quest

Before adding the new `filtrationValueOverride` parameter, the project lead asked to fix the pre-existing
`maxFiltrationValue: Double = Double.NaN` sentinel convention (used across six constructors) to `Option[Double]
= None` instead, on the grounds that a second need for the same "optional, can't reference an earlier
same-list parameter" pattern was reason enough to stop compounding a code smell. `None` is an ordinary constant
default, so it sidesteps Scala 3's restriction on a default referencing an earlier same-list parameter (the
actual reason the NaN sentinel existed) without needing a curried parameter list (which would have forced a
trailing `()` onto every existing call site). Fixed across `EnumeratingCofaceSimplexStream`,
`RipserCofaceSimplexStream`, `InorderCofaceSimplexStream`, `IncrementalVietorisRipsSimplexStream`,
`RipserCohomologyContext`, `PackedRipserCohomologyContext`, plus `Tda4j.scala`'s MATLAB-facing option parsing
(now threads a genuine `Option[Double]` instead of its own separate NaN default). ~25 call sites updated to
`Some(...)`; callers relying on the default were unaffected. Committed separately (`a27d644`) before any Cech
code was written, full `sbt test` clean throughout (238 examples, unchanged).

## Miniball: the actual geometric primitive, and why the historical rip-out doesn't apply here

Cech radius of a simplex = the true minimum-enclosing-ball radius of its vertices' real coordinates -- a
genuinely simpler question than alpha shapes' (which additionally needs a Delaunay/Voronoi feasibility check
against every OTHER point in the cloud). This does not need `AlphaComplexDQP`'s dual active-set QP machinery at
all; it needs `com.dreizak:miniball` directly, already a `build.sbt` dependency but never actually invoked
anywhere in this codebase before this session (confirmed: `grep "new Miniball\|Miniball("` across `src/`
returned nothing prior to this work).

Checked why the one prior Miniball-based construction (`MiniballDelaunay`, commit `87172ef`, "Miniball delaunay
implementation was very wrong; ripped out completely") failed, before trusting the library here -- the actual
diff showed the bug was NOT in Miniball's own computation: `isDelaunay` correctly used Miniball's
`squaredRadius()` for the empty-circumsphere Delaunay test, but `filtrationValue` was then hardcoded to
`FiniteMetricSpace.MaximumDistanceFiltrationValue` (plain VR diameter) -- an entirely different, wrong quantity
reported as the persistence value, unrelated to whether Miniball's own answer was correct. That class also
almost certainly had the ascending-vs-reversed `filtrationOrdering` bug this codebase's history documents
repeatedly, since it used `FilteredSimplexOrdering` unreversed. Neither bug is a Miniball correctness problem.
But it's equally true that Miniball itself was, and until this session remained, completely unvalidated in this
codebase -- this session's fixtures are what actually establish trust, not the history.

`MiniballPointSet` (a 3-line `PointSet` adapter) is declared fresh in `streams/CechStream.scala` rather than
reusing `alpha.ScalaPointSet` (structurally identical) -- `streams` is the more foundational package (`alpha`
already depends on `streams`, not the reverse, per CLAUDE.md's package layout), so importing from `alpha` here
would be a backwards cross-package dependency for a trivial adapter.

## Two real floating-point correctness bugs found and fixed, not just test flakiness

Cech radius is mathematically monotone non-decreasing under vertex insertion (adding a ball-intersection
constraint can never shrink the minimum enclosing ball) -- but validating this empirically immediately found
that Miniball's raw floating-point output can violate it by an ULP or two on near-degenerate inputs. A concrete
random point cloud produced a facet radius of `0.3887884477377332` and its own coface's radius as
`0.3887884477377331`, one ULP SMALLER -- not a hypothetical, a directly reproduced case.

This is not merely a test-tolerance nuisance: `CellularHomologyContext`'s reduction requires this monotonicity
to hold EXACTLY (its ascending-filtration processing order is the same invariant behind three prior
"reduction pivot ... was not a recorded open class" crashes elsewhere in this codebase). Confirmed directly:
before the fix below, the H0/structural-invariant property test reproduced that exact crash on a random cloud.

**Fixed by explicitly enforcing monotonicity, not trusting Miniball's raw precision**: `CechFiltration` now
clamps every computed radius to be at least the max of its own facets' ALREADY-CACHED radii (a plain lookup,
never a fresh Miniball call). This is airtight, not just a patch, because of the same downward-closure argument
above: any simplex `filtrationValue` is ever asked about is one `RipserCofaceSimplexStream`'s coface loop is
about to accept or has already generated, and every facet of an accepted simplex is (by that same completeness
argument, applied one dimension down) guaranteed to already be cached. Re-tested: the crash is gone, 6
consecutive fresh-seed runs of the full spec clean.

Separately, `CechFiltration`'s cache exists specifically because Miniball's determinism across repeated calls
on the identical input was never verified -- rather than test for it, the design sidesteps the question
entirely: cache once, read forever, so cross-call inconsistency (if it exists) can never be observed.

## Three bugs in the test fixtures themselves, distinct from the two real implementation bugs above

While debugging the above, three more issues turned out to be in the validation code, not the implementation --
worth recording as a caught-and-fixed set, not silently corrected:

1. An `advanceTo` ordering mistake: the H0 property test called `state.diagramAt(Double.PositiveInfinity)`
   (which fully advances the state) BEFORE its own incremental `advanceTo(r)` sweep -- since `advanceTo` only
   moves forward, every subsequent call was a no-op on an already-finished stream. Fixed by moving the
   structural-invariant check (which needs `diagramAt` at +Infinity) to run AFTER the incremental sweep, not
   before.
2. The H0 oracle's thresholds were computed independently as `ms.distance(i,j) / 2.0`, not read from the
   stream's own `filtrationValue` -- for the SAME reason as the ULP finding above (Miniball's 2-point
   computation isn't always bit-identical to a direct `d/2.0`, confirmed directly: one case had
   `d/2.0 = 0.19216296992354814` vs. the stream's own reported edge radius `0.19216296992354817`), this made
   the independent oracle's `advanceTo(r)` threshold not quite match what the stream would use internally to
   decide inclusion, producing a false mismatch. Fixed by deriving thresholds from `stream.filtrationValue`
   directly, and adding a small epsilon to the independent oracle's own `<= 2r` comparison for the same reason.
3. `cellCount` (needed for the `totalBarsAccountForAllCells` structural invariant) was hardcoded to sum only
   dimensions `0 to 3` -- silently wrong (an undercount) for any generated point cloud with more than 4 points,
   since `maxFiltrationValue = +Infinity` here means every combinatorial subset up to `points.length - 1` is a
   real cell. Fixed by summing `0 until points.length`.

None of these three needed a code change in `CechStream.scala` itself -- confirmed by re-running the corrected
tests and seeing them pass without touching the implementation again.

## Validation summary

`CechStreamSpec.scala` (`streams` package, alongside `CechCofaceSimplexStream`/`CechFiltration` in
`CechStream.scala`): hand-derived single-simplex fixtures (edge = d/2 exactly; equilateral triangle =
circumradius s/sqrt(3), discriminating a "just used VR diameter" regression -- the actual historical
`MiniballDelaunay` bug; right/obtuse triangle = half the longest side, discriminating a naive closed-form
circumradius formula that doesn't fall back to the enclosing-ball case for obtuse configurations; collinear
triple, checking Miniball doesn't choke on the degeneracy; a repeated-call determinism check), a monotonicity
property test (with an explicit, justified floating-point tolerance), the enumeration-completeness check
described above, the `totalBarsAccountForAllCells` structural invariant plus an independent H0-via-union-find
oracle at threshold `2r` (valid because an edge exists in Cech_r iff `d(x,y) <= 2r`, a fact needing only the
triangle inequality, holding in any metric space), and a full hand-derived barcode (unit equilateral triangle:
2 finite H0 bars dying at radius 0.5, 1 essential H0, 1 genuinely non-zero-length H1 bar dying at `1/sqrt(3)` --
discriminating from VR's own zero-length-bar answer on the identical point cloud). 11 examples, 221
expectations, clean across 6 consecutive fresh-seed runs. Full `sbt test`: 249 examples, 244/0/5/1, unchanged
baseline plus these 11.

## What's still open, deliberately not attempted this session

- Packed-Ripser-speed Cech (apparent pairs / insertionDiameter-equivalent for the circumradius functional) --
  genuinely open math, not an engineering gap.
- New-VR-style candidate pruning for Cech using the metric-only necessary condition (Cech_r subseteq VR_2r,
  provable from the triangle inequality alone, no coordinates needed) as a cheap pre-filter before invoking
  Miniball -- a real, scoped future speedup, not attempted here since this session's priority was correctness
  on the naive engine first.
- Testing `CechCofaceSimplexStream` at a genuinely finite (non-`+Infinity`) `maxFiltrationValue` beyond the one
  fixed-threshold enumeration-completeness check -- the downward-closure completeness argument is proven for
  any threshold (see above), but a dedicated property-test sweep across varying finite thresholds was not run.
