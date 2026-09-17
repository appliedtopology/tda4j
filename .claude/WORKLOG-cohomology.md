# Session worklog — Phase 2: persistent cohomology + clear&compress + apparent pairs

Continuation of `WORKLOG-naive-homology.md`'s "Phase 2 plan" section (read that first — it has the full
scope decisions, staging plan, and hard-earned lessons from phase 1 that carry over). This file picks up
directly from there. Written to survive a `/clear` or a session gap; the user stepped away for about an
hour at the start of this session with instructions to keep going and log decisions here.

## Orientation done before writing any code

Read in full: `Homology.scala` (all three existing contexts + the commented-out prior art),
`Cofacets.scala` (`CofacetIterator`), `RipserStream.scala` (`SimplexIndexing`, `RipserStreamBase`/
`Sparse`'s `zero*` apparent-pair helpers), `Chain.scala`, `Barcode.scala`, `SimplexStream.scala`,
`FiniteMetricSpace.scala`, `Simplex.scala`, `HomologyFixtures.scala`, `HomologySpec.scala`,
`SimplexIndexingSpec.scala`. Also fetched Ulrich Bauer's Ripser paper (arXiv:1908.02518, HTML version)
directly for Definition 3.2 (apparent pairs), Proposition 3.1 (pivot/persistence-pair definition),
Proposition 3.9 (zero-persistence apparent pair combinatorial characterization), and Algorithm 1
(matrix reduction) verbatim, rather than relying on memory of the paper — CLAUDE.md flags this exact
area (`CofacetIterator.apparentVertex`'s own TODO comment) as historically under-verified in this
codebase, so treating fuzzy recall as authoritative here would be exactly the mistake to avoid.

Consulted the advisor once, before writing code, with this orientation in context. Its findings (see
below) materially changed the plan versus my first draft — recorded inline at each point they apply.

## Design decisions for Stage 1 (plain persistent cohomology, no clearing, no apparent pairs)

### Scope and API shape

Per the phase-2 interview already recorded in `WORKLOG-naive-homology.md`: specialize to `Simplex[Int]`
via `SimplexIndexing` (the combinatorial number system), one-shot (no `advanceTo`/incremental query),
representative cocycles required. Concretely: a new class `RipserCohomologyContext[CoefficientT: Field]
(metricSpace: FiniteMetricSpace[Int], maxDimension: Int)` in `Homology.scala`, alongside the other three
contexts. No `maxFiltrationValue` truncation parameter — deliberately dropped from the design (not
required by the phase-2 plan, and `AlphaShapeDQP` already established the "always-untruncated by
default" precedent in this codebase; adding a truncation knob nobody asked for is exactly the kind of
scope creep CLAUDE.md says to avoid). `maxDimension` has the same meaning as `LimitedCofaceSimplexStream`'s
existing `maxDim` parameter elsewhere in this codebase: the top simplex dimension actually enumerated.
Bars at `dim == maxDimension` are computed correctly for the *truncated* (maxDimension-skeleton)
complex, same as homology's existing VR tests already accept (see `torusExpected`'s genuine essential
2-bar) — no special-casing needed, this falls out of the algorithm for free.

### The four things the advisor's first pass caught that a naive port of phase 1 would have missed

1. **Zero-length bars.** The naive engine (`CellularHomologyContext`) emits `(pivot.dim, pivotFv,
   deathFv, rep)` unconditionally, including when `pivotFv == deathFv` (a tied face/coface pair, e.g.
   the square fixture's `∆(0,2)`/`∆(0,1,2)` both at √2). Ripser drops zero-persistence intervals, and
   apparent pairs (stage 4) *are* the zero-persistence pairs — a faithful apparent-pairs shortcut will
   naturally omit them. **Policy fixed now, before writing the reduction loop**: the new engine emits
   zero-length bars too (so `totalBarsAccountForAllCells`-style per-engine invariants still hold and
   the apparent-pairs toggle test in stage 4 has something to diff against). Cross-engine comparison
   against the naive engine (the correctness oracle) is **only valid on `birth < death` bars** — not
   just an emission-policy mismatch, but a real one: with ties present, the two engines' tie-breaks
   differ (naive: `FilteredSimplexOrdering`, reversed-filtration then dim then lex; new: combinatorial
   index), so zero-length bars can genuinely disagree between the two even though both are correct.
   Within-engine (apparent pairs off vs. on, clearing off vs. on) comparisons must include *everything*,
   zero-length bars included — same ordering, same pairing, must be bit-identical, which is exactly
   what makes those toggle tests strong.
2. **Pivot orientation must be calibrated, not derived from paper prose alone.** `Chain.from`/`Chain`'s
   `RingModule` build their internal `PriorityQueue` using `ord.reverse`, so `leadingCell` = **minimum**
   under whatever `Ordering[CellT]` is in scope (confirmed by rereading `Chain.scala:104-107` directly,
   not assumed). Re-derived from the fetched paper text (see "Derivation" section below) that persistent
   cohomology wants `Pivot(R_j)` = the *oldest* cofacet in the reduced coboundary chain — so the
   `Ordering[Simplex[Int]]` handed to `Chain`/`Chain.reduceBy` for the coboundary-side chains must treat
   *smaller filtration value as ordering-smaller* (ascending, i.e. the **opposite** convention from the
   naive engine's `stream.filtrationOrdering`, which is deliberately reversed so its `leadingCell` means
   *youngest*). Getting this backwards would silently produce wrong pairings, not a crash — so this is
   exactly the kind of thing to nail down before the full engine exists, not after.
3. **Cocycle-is-a-cocycle check is vacuous at `d == maxDimension`.** `Chain.scala` has a `boundary`
   extension for chains but no `coboundary` one (needed to write) — and unlike `boundary` (intrinsic to
   a cell), a coboundary is extrinsic: it depends on which higher-dimensional simplices exist in the
   (possibly truncated) complex. At the top dimension there are none, so every representative there
   trivially "satisfies" `δ(rep) == 0` regardless of whether the algorithm is even correct. The
   zero-coboundary representative-cycle test (mirroring phase 1's zero-boundary check) must only assert
   this for `d < maxDimension`, or it passes for the wrong reason.
4. **`flattenToCellStream` (currently `private` in `HomologySpec.scala`) should move to
   `HomologyFixtures.scala`** so both the naive engine's tests and the new cohomology engine's tests can
   build comparable VR-derived cell streams from the same helper, rather than duplicating it.

### Derivation: birth/death/dimension mapping, and why the pivot ordering must be ascending

Worked this out explicitly rather than trusting memory, since this is precisely the area prior sessions
in this codebase have found silently-wrong existing code. Recorded here so a future reader (or a future
me) doesn't have to re-derive it.

Fetched from the paper (Section 3.2, verbatim): "the filtration coboundary matrix for
δ: C^d(K,K∙)→C^{d+1}(K,K∙) is given as the transpose of the filtration boundary matrix with rows and
columns ordered in **reverse filtration order**." Combined with Proposition 3.1's pivot definition
("largest row index of any nonzero entry") and the persistence-pair rule ("pairs are {(i,j) | i =
Pivot(R_j) ≠ 0}"):

- In this transposed-and-reversed matrix, **rows are dimension-(d+1) simplices and columns are
  dimension-d simplices**, both indexed in *reverse* real filtration order.
- A pivot pair (i, j) found by Algorithm 1 requires i to precede j in the matrix's own index order
  (upper-triangularity). Since the matrix's own order is the *reverse* of real time, "i precedes j in
  matrix order" means **i is younger than j in real time**. i is the row (a (d+1)-simplex τ), j is the
  column (a d-simplex σ) — so τ (dimension d+1) is younger than σ (dimension d) in real time, which is
  just the ordinary fact that a cofacet's filtration value is ≥ its facet's. Not a contradiction, a
  sanity check.
- Whether the *row* or *column* plays "birth" or "death": in the ordinary (non-transposed,
  non-reversed) boundary-matrix reduction, the row (earlier in the matrix's own order) is birth and the
  column (later) is death. Under the reversal, "earlier in matrix order" = "later in real time" and vice
  versa — so the mapping flips: **the column (σ, dimension d) is birth in real time, and the row (τ,
  dimension d+1, the pivot) is death.** Bar dimension = dim(σ) = d (the lower of the two). This matches
  the advisor's independent statement of the same conclusion, and sanity-checks correctly against
  ordinary intuition for H_0 (vertex = birth at 0, edge = death at its length, dimension 0).
- Consequence for pivot direction: `Pivot(R_j)` = largest row index *in the matrix's own reversed
  order* = **smallest row index in real filtration order** = the row (a (d+1)-simplex) with the
  smallest real filtration value among R_j's nonzero support = **the oldest cofacet** — matching
  Definition 3.2's "τ is the oldest cofacet of σ" directly. Since `Chain`'s `leadingCell` is the
  *minimum* under the supplied `Ordering[CellT]` (point 2 above), the ordering handed to the
  coboundary-side `Chain`/`Chain.reduceBy` machinery must make "oldest" sort as smallest — i.e. plain
  ascending-by-filtration-value, with ties broken so that **larger combinatorial index sorts as older**
  (matching Definition 3.2/Proposition 3.9's "lexicographically-refined" tie-break, where the
  lex-*maximal* cofacet among ties is the one that's actually oldest under the refined order). Concrete
  ordering used: primary key filtration value ascending; secondary key `si(y) compareTo si(x)` (i.e.
  larger raw combinatorial index compares as smaller/older).
- Within-dimension processing order (the outer loop over d-simplices) is **youngest-first** — this
  directly matches "process columns j in increasing [matrix] order," which under the reversal means
  decreasing real filtration order. This is structural, not a performance tweak: reducing against
  *already-processed* columns only makes sense if "already processed" means "younger," matching
  Algorithm 1's `while there exist k<j` exactly. Implemented as `.sorted(using
  cohomologyOrdering.reverse)` (i.e. descending under the ascending ordering above) — a plain
  `Seq.sorted` call, not routed through `Chain`'s typeclass machinery, since this is just an enumeration
  order, not a chain-arithmetic pivot rule.
- Representative cocycle bookkeeping reuses phase 1's exact V-column-via-`Chain.reduceBy`-log-fold
  pattern (`WORKLOG-naive-homology.md`'s "critical performance bug" section — `Chain.reduceBy`, not
  hand-rolled raw `Chain` arithmetic, plus an explicit `collapseAll()` before storing). Verified
  algebraically that this carries over correctly: Algorithm 1's `V_j` (started at `e_j`, updated in
  lockstep with `R_j`) satisfies `R_j = δ(V_j)` throughout by construction, so when `R_j` reduces to
  zero, `V_j` *is* a genuine cocycle (not just a formal witness) — no separate cocycle-reconstruction
  step needed, unlike phase 1's homology case which needed to reach back into a *different* dimension's
  pivot table. This also answers advisor point 3 above: it only actually proves `δ(V_j) = 0` for
  cofacets that were considered during reduction, i.e. within whatever `coboundaryOf` actually
  enumerated — trivially true at `d == maxDimension` where no cofacets exist to enumerate at all, hence
  that dimension being excluded from the check.
- Coboundary sign convention: dual to `Simplex.scala`'s boundary convention (`(-1)^i` for the `i`-th
  smallest vertex removed, `i` counted via ascending `SortedSet` iteration order). Coboundary of σ by a
  cofacet τ = σ ∪ {v}: sign = `(-1)^(number of σ's vertices smaller than v)`. This is also what the
  commented-out, never-compiled `RipserHomology` prior art at the bottom of `Homology.scala` already
  assumed (`spx.count(_<w) % 2`) — corroborating, though that code was never trusted or tested, so this
  is independent derivation with a consistency check, not an appeal to that code's authority.

### `topCofacetIterator` finding (recorded regardless of whether reused)

`SimplexIndexing.cofacetIterator(index, size, allCofacets = false)` (aka `topCofacetIterator`) only
enumerates cofacets formed by inserting a vertex *strictly greater than* the simplex's own maximum
vertex — confirmed against `SimplexIndexingSpec`'s existing passing test ("top cofacets of [1,3]" in
`SimplexIndexing(5)` = `{[1,3,4]}` only, not `[0,1,3]` or `[1,2,3]`). If the simplex already contains
`vertexCount - 1` (no room above), this iterator yields **zero** candidates immediately, even if a valid
same-diameter cofacet exists by inserting a vertex *below* the current max. Consequently
`RipserStreamBase.zeroPivotCofacet` (which is built on this) returns `None` in that case even when a
real zero-pivot cofacet exists — a **conservative false negative** (the apparent-pairs shortcut gets
skipped and a full reduction runs instead), not a correctness bug: the pair still gets found by ordinary
reduction, just without the speedup. This is exactly why stage 4 writes its own apparent-pair check
directly against Definition 3.2/Proposition 3.9 using the full, well-tested `cofacetIterator(..., true)`
rather than reusing `RipserStreamBase`'s `zero*` helpers (which the existing `WORKLOG-naive-homology.md`
already flagged as unverified end-to-end — the one broader integration test that would check it is
`.pendingUntilFixed`).

## Correction found before writing any tests: clearing is required for correctness, not stage 3

The original plan above staged clearing as a later, optional performance optimization on top of an
already-correct "plain reduction" baseline (mirroring `PersistenceInChunksContext`'s framing and a loose
reading of the paper's own "clearing optimization" language). **This was wrong**, caught by hand-deriving
a calibration example before writing the cross-validation test (per the advisor's suggestion to calibrate
concretely rather than trust the derivation above in the abstract) -- specifically before, not after,
committing to the "Stage 1 = plain, no clearing" implementation as a milestone.

**The concrete counterexample**: 3 points on a line at positions 0, 1, 3 (pairwise distances 1, 2, 3 --
all distinct, no ties). At `maxDimension = 1` (plain 3-cycle graph, no filled triangle): elementary linear
algebra (`dim H^1 = dim C^1 - rank(im delta^0) = 3 - 2 = 1`, matching the standard graph-theory cycle-rank
formula `edges - vertices + components = 3 - 3 + 1 = 1`) says H^1 has rank exactly 1. A first draft of
`persistentCohomology()` that reduced each dimension's coboundary matrix in isolation (fresh `basis`/
`generators` per dimension, matching the paper's literal "reduce filtration d-coboundary matrices...in
order of increasing dimension" description taken too literally) reports **all three edges as
independently essential** -- rank 3, not 1. At `maxDimension = 2` (triangle included), the same
isolated-per-dimension version reports 2 spurious essential H^1 bars instead of the correct 0 (a filled
triangle is contractible).

**Root cause**: Proposition 3.1 (fetched earlier, see above) defines essential indices as `{i | R_i = 0
AND i is not a pivot anywhere}` -- the *whole* reduction's pivot set, not just dimension d's own matrix.
My first draft checked only `R_j == 0` and dropped the second clause entirely. Concretely: in the 3-point
example, edges `e01` and `e12` get claimed as PIVOTS during dimension 0's reduction (they're the death
side of two of the three vertices' bars) -- so even though *their own* dimension-1 columns independently
reduce to zero (nothing else in dimension 1 to reduce against), they must NOT be reported essential,
because they're already accounted for as the image of `delta^0`. Confirmed via the advisor: this matches
the standard "clear and compress" theorem this codebase's own `PersistenceInChunksContext` already
implements for homology (Chen-Kerber) -- a simplex already claimed as a pivot one dimension down is
*guaranteed* to reduce to zero if its own column were honestly computed, which is exactly why skipping it
outright ("clearing") is valid, but the skip has to actually happen -- it is not optional bookkeeping on
top of an already-correct answer, it IS the mechanism that produces the correct answer at all.

**Fix applied directly to `persistentCohomology()`** (already in the code, not left as a TODO): a single
`cleared: mutable.Set[Simplex[Int]]`, declared *outside* the `for d` loop (carried across dimensions,
unlike `basis`/`generators` which reset per dimension). When a pivot is found reducing dimension d, it's
added to `cleared`. The outer per-dimension loop skips any simplex already in `cleared` entirely -- no
bar emitted, no `basis`/`generators` entry written, since its bar was already recorded when it was
claimed as a pivot.

**Consequences for the plan and invariants, corrected now rather than discovered later**:
- The staging plan's "Stage 1 (plain) -> Stage 3 (clearing)" split is retired. Clearing is part of the
  minimum viable correct implementation. Only apparent pairs (Definition 3.2/Proposition 3.9) remains as
  a genuinely-optional later addition -- that one really is a pure shortcut (its own toggle test, on vs.
  off, should assert *identical* output, unlike clearing where "off" is simply wrong).
- **No `useClearing` toggle** was added to the constructor for exactly that reason -- there is no correct
  "off" state to toggle to, so a toggle test would just assert two different wrong answers disagree with
  each other, which proves nothing.
- The bars-account-for-cells structural invariant is NOT `bars.size == totalSimplices` (my first-draft
  assumption, before clearing existed) -- a cleared simplex contributes zero bars. The correct form is the
  *same shape* `HomologyFixtures.totalBarsAccountForAllCells` already uses for homology: every simplex is
  exactly one of essential (1 bar), a column that found a pivot (1 bar, and its pivot is cleared, itself
  contributing 0), or cleared (0 bars, but it's the pivot of exactly one finite bar) -- so
  `finite*2 + essential == totalSimplices` still holds, and the existing homology helper is reused as-is
  rather than writing a cohomology-specific variant.
- At `d == maxDimension`, `coboundaryOf` is empty for every sigma regardless of clearing (no cofacets
  exist to enumerate at all beyond `maxDimension`) -- so every *non-cleared* top-dimension simplex reports
  essential. This is the same accepted truncated-complex convention homology's own `torusExpected` fixture
  already relies on (its genuine essential 2-bar) -- not a new caveat, but double-check the *count* at that
  dimension against `C(n, maxDimension+1)` minus however many got cleared from dimension `maxDimension-1`,
  rather than assuming every top-dimension simplex is essential.

## Bug found in phase-1 shipped code while building the cross-validation oracle (now fixed)

While wiring up the cross-validation test against `CellularHomologyContext`, the hand-verified
`threePointLine` calibration example (above) disagreed between the two engines -- and the *naive*
engine's own output was the one that was garbled: three separate `(0,0.0,+inf)` essential bars for a
3-point *connected* complex (impossible; there is exactly one connected component) and three
inconsistent dimension-1 bars where exactly one finite zero-length bar was expected. My cohomology
engine's output exactly matched the hand-derivation both times, so this was not the new code.

**One underlying finding, with two manifestations** (initially misdiagnosed as two separate bugs during
this session -- corrected here, since writing it up as two would send a future reader looking for two
separate fixes): for `SimplexStream`-family types, **a stream's `.iterator` order and its
`.filtrationOrdering` are independently defined, and nothing keeps them consistent under a genuine
filtration-value tie.** `CellularHomologyContext.HomologyState` needs both to cooperate correctly: the
iteration order must visit every cell's proper faces before the cell itself (a structural precondition
of boundary-matrix reduction), and `stream.filtrationOrdering` must be a genuine total order usable as
`Chain`'s `Ordering[CellT]` for pivot selection (`Chain.reduceBy` routes through a `SortedMap[CellT,...]`
keyed by exactly this ordering, so two cells that compare *equal* under it collide as a single map key
and the reduction silently garbles pairings for that complex -- confirmed by directly testing this, not
merely inferred).

- **Manifestation 1**: `EnumeratingCofaceSimplexStream.filtrationOrdering` (`SimplexStream.scala:230-231`)
  is `Ordering.by(filtrationValue)` -- no secondary tie-break at all. Confirmed directly: `vrStream
  .filtrationOrdering.compare(Simplex(0,2), Simplex(0,1,2))` returns `0` on the `threePointLine` fixture,
  i.e. the edge `{0,2}` and the triangle `{0,1,2}` (two *different* simplices, tied at filtration value
  3.0) compare as *equal* -- not a genuine total order, so it's unsafe to use as `Chain`'s `Ordering[CellT]`
  regardless of iteration order.
- **Manifestation 2** (found while trying to route around manifestation 1 by rebuilding the stream through
  `ExplicitStreamBuilder`, which inherits `SimplexStream`'s trait-level `FilteredSimplexOrdering`): that
  fix ALSO produced a wrong/crashing result (`IllegalStateException: reduction pivot ... was not a
  recorded open class`), because `ExplicitStreamBuilder`'s own internal sort uses `filtrationOrdering
  .reverse` to get an "oldest first" iteration order, and `FilteredSimplexOrdering`'s dimension tie-break
  (`Ordering.Int.compare(x.size, y.size)`, plain ascending) is NOT reversed along with the (deliberately
  reversed) primary filtration-value key. Reversing the whole comparator for iteration purposes therefore
  ALSO flips the dimension tie-break, so a higher-dimensional coface can sort *before* its own tied facet
  -- confirmed directly: for `threePointLine` at `maxDim=2`, the resulting order put the triangle
  `{0,1,2}` before its own facet `{0,2}`, violating faces-before-cofaces and crashing the reduction.

Neither manifestation is rare: on **any** Vietoris-Rips complex with `maxDimension >= 2`, every triangle
ties with its own longest edge by construction (a simplex's filtration value, the max pairwise distance
among its vertices, is always realized by some face) -- so this isn't specific to the hand-picked
`threePointLine` fixture. This means **`HomologySpec`'s own existing VR-stream tests, which build their
stream via `LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(...), 2)` (manifestation 1's exact
shape) and iterate it via `HomologyFixtures.flattenToCellStream` (which passes that broken ordering
straight through), have likely been silently computing wrong bars on every complex with a genuine
triangle-vs-longest-edge tie** -- undetected until now because those tests only ever check `birth <=
death` and a chain-size bound, never exact bar values against an independent oracle (see
`WORKLOG-naive-homology.md`'s own final-status section, which already notes these tests as passing on
exactly that weaker basis).

**Fixed directly at the source, on explicit instruction from the project lead** ("fix the ordering --
tiebreak on dimensionality and lexicographic ordering worst case, or maybe colexicographic; a bunch of
Ripser's optimizations boil down to cleverly playing off the exact ordering choices"). Final fix, in two
parts:

1. `EnumeratingCofaceSimplexStream.filtrationOrdering` (`SimplexStream.scala`) is now a real total order:
   primary key filtration value (reversed, so smaller-under-this-ordering means younger, matching
   `Chain`'s `leadingCell = min = youngest` convention), secondary key dimension, tertiary key
   **colexicographic** via `simplexIndexing`'s own combinatorial-number-system index -- deliberately
   colex, not `FilteredSimplexOrdering`'s plain lex, to match Ripser's own Definition 3.2/Proposition 3.9
   "lexicographically refined" tie-break (the same convention `RipserCohomologyContext` already relies
   on), keeping this stream internally consistent with the rest of the Ripser-flavored machinery rather
   than merely self-consistent.

2. A second, distinct bug surfaced *by* fixing (1): giving `filtrationOrdering` a real tie-break exposed
   that `EnumeratingCofaceSimplexStream.iterateDimension` sorted each dimension's bucket with its own,
   independent `.sortBy(filtrationValue)` (raw ascending, stable, tie-break = simplex-enumeration order)
   -- a *different* total order from the newly-fixed `filtrationOrdering` on exactly the cells that tie.
   Before (1), both orderings were "no real tie-break" in their own way, so nothing crashed; the two
   silently-different arbitrary tie-breaks just fed `Chain.reduceBy`'s `SortedMap` inconsistent keys,
   producing the same silent corruption as manifestation 1 above. After (1) alone, the square-fixture
   regression test (`HomologySpec`, 4 points on a unit square, `maxDimension=2`) started **crashing**
   instead: `IllegalStateException: reduction pivot TreeSet(1, 2) was not a recorded open class`. Hand
   trace confirmed why: `EnumeratingCofaceSimplexStream`'s reduction requires columns (iteration order)
   and rows (pivot order, `filtrationOrdering`) to be indexed by **one shared total order** -- this is not
   an optional nicety, it's the actual precondition Algorithm 1's "only positive cells become pivots"
   theorem depends on. Two independently-valid total orders that disagree on a tie violate that
   precondition even though each one alone is perfectly consistent.

   **The general lesson, worth keeping above the debugging narrative**: a stream's `iterateDimension`
   order and its `filtrationOrdering` must be the *same* total order, one the consistent `.reverse` of the
   other -- not merely "each independently a valid total order." Fixed by replacing every
   `.sortBy(filtrationValue)` in `EnumeratingCofaceSimplexStream`, `RipserCofaceSimplexStream`, and
   `InorderCofaceSimplexStream`'s `iterateDimension` with `.sorted(using filtrationOrdering.reverse)`
   (`.reverse` on the *same* `Ordering` object -- not a second, independently-built "oldest first"
   comparator, which is exactly how manifestation 2 above broke `ExplicitStreamBuilder`'s attempt at this
   same idea). Caught along the way: `RipserCofaceSimplexStream` already had one `.sorted(using
   filtrationOrdering)` call (no `.reverse`) that had been silently correct only because the *old*,
   pre-fix `filtrationOrdering` happened to be non-reversed (plain ascending); once `filtrationOrdering`
   was correctly reversed in (1), that call became silently backwards too, and needed the same
   `.reverse` fix.

   `InorderCofaceSimplexStream`'s own coface generation (`inOrderCofaceIterator`, used inside its
   `iterateDimension`'s `case d` branch) doesn't sort at all -- it emits cofaces in an order derived from
   the metric-space structure directly. Checked independently (a throwaway debug spec, since deleted): on
   the same square fixture, `InorderCofaceSimplexStream` produces the *identical* 8-bar barcode as
   `EnumeratingCofaceSimplexStream`, with no crash -- so its own algorithmic order happens to already
   agree with `filtrationOrdering` on this input. Not a proof for all inputs, but no evidence of the same
   bug there either.

Verified: the square-fixture crash is gone, and the resulting barcode is independently correct by hand
count -- 3 finite H_0 deaths + 1 essential (spanning tree + one survivor), 1 finite H_1 bar (birth 1.0,
death sqrt(2)) + 2 zero-length H_1 bars (both at sqrt(2), the tied diagonal-edge/triangle pairs -- K4's
cycle space has rank 3, matching 3 independent H_1 births before any triangle fills one in), 1 essential
H_2 class (the 4 triangles form a hollow-tetrahedron boundary with no 3-cell to kill it) -- 14 cells total,
`finite*2 + essential = 6*2 + 2 = 14`, exactly accounting for all of them. Full targeted regression
(`HomologySpec`, `PersistenceInChunksSpec`, `RipserCohomologySpec`, `SimplexStreamSpec`,
`SimplexIndexingSpec`, `RipserStreamSpec`, `VietorisRipsSpec`, `CofaceSimplexStreamSpec`) re-run clean
afterward: 39 examples, 0 failures, 0 errors, 1 pending (pre-existing, unrelated).

**Still not exercised by any test, flagged but out of scope for this fix**: `SimplicialHomologyByDimensionContext`
(the third of the three "live" persistence engines) has zero test coverage anywhere in the repo -- not
just missing from this regression run, `grep -rl SimplicialHomologyByDimensionContext src/` finds only its
own definition in `Homology.scala`. It shares the same stream/pivot-ordering machinery as
`CellularHomologyContext`, so it's plausible (not confirmed) that it would have the same class of ordering
sensitivity. Needs its own spec before that can be checked either way.

## Validation strategy (staged, per advisor)

- **Cross-engine (new engine vs. `CellularHomologyContext`)**: multiset-equality of `(dim, birth,
  death)` triples, filtered to `birth < death` only, on (a) hand-built small metric spaces built to be
  directly comparable to `HomologyFixtures`' existing hand-verified examples where a VR realization
  exists, and (b) randomized VR point clouds via the same `matrixGen`-based ScalaCheck property
  `HomologySpec`'s own VR test already uses. This is the primary oracle, per the theorem that homology
  and cohomology barcodes agree over a field.
- **Toggle tests** (stage 3 clearing, stage 4 apparent pairs): same engine, same input, optimization
  flag off vs. on, assert *bit-identical* full bar lists (zero-length bars included) across the same
  randomized VR inputs. This is the strongest available check for the two riskiest pieces, precisely
  because it sidesteps needing to independently re-verify the subtle reversed-order/tie-break reasoning
  above from scratch a second time — if the shortcut's output ever differs from plain reduction's, that
  is unambiguously a bug, regardless of which one is "right."
- **Structural**: `totalBarsAccountForAllCells`-equivalent per dimension (every simplex opens or closes
  exactly one bar), representative-is-a-genuine-cocycle (guarded to `d < maxDimension`, see above), and
  the same `rep.items.size <= totalCells`-style bound phase 1 used as a non-flaky proxy for the
  uncollapsed-duplicate-entries performance bug (recorded in `WORKLOG-naive-homology.md`) — apply the
  same guard here since the reduction machinery is the same `Chain.reduceBy`/fold-with-`collapseAll()`
  shape.

## Status

- [x] Orientation, paper fetch, advisor consult, this worklog.
- [x] Move `flattenToCellStream` to `HomologyFixtures.scala`; add `correctlyOrderedCellStream` next to it
      once the ordering bug (above) was found.
- [x] Implement `RipserCohomologyContext` (plain reduction + clearing -- see correction above; no
  apparent pairs yet). `../src/main/scala/org/appliedtopology/tda4j/Homology.scala`.
- [x] `RipserCohomologySpec.scala`: hand-verified 3-point calibration example (`threePointLine`, both
      `maxDimension=1` and `=2`) as the primary regression pin; cross-validated against
      `CellularHomologyContext` on the calibration example AND 200 random VR point clouds (`birth <
      death` bars only, exact multiset equality -- passing); structural invariant (`finite*2 + essential
      == totalSimplices`, reusing `HomologyFixtures.totalBarsAccountForAllCells`, 200 random trials --
      passing); essential-representative-is-a-genuine-cocycle check over `Fp(11)` (guarded to `dim <
      maxDimension` AND `upper == +infinity` -- see below for why the second guard was also needed, 200
      random trials -- passing). **All 6 examples / 603 expectations passing.**
  - Found, in the course of writing this test's oracle (not in `RipserCohomologyContext` itself): the
    `EnumeratingCofaceSimplexStream`/`FilteredSimplexOrdering`-reversal bug documented above in full,
    plus my own test-writing mistake (asserting zero-coboundary for FINITE bars' representatives, not
    just essential ones -- `delta(V_j) = R_j` is only zero for essential bars by construction; a finite
    bar's representative has coboundary exactly equal to its own nonzero reduced pivot chain, confirmed
    by direct inspection, not a bug in the algorithm).
- [ ] Apparent pairs (own Def 3.2/Prop 3.9 implementation, not reusing `RipserStreamBase.zero*` or
      `Cofacets.scala`'s `apparentVertex`), with an off/on toggle test (this one IS a valid identity
      check, unlike clearing) and the hand-verified apparent-pair example from the tied square fixture.
      **Started, not landed** -- see "Apparent pairs: negative result, two designs ruled out" below. The
      Definition 3.2 check itself is implemented and verified correct against the hand-derived
      `threePointLine` example; the two obvious ways to *use* it to skip work are both confirmed unsound
      by direct counterexample. What's left needs Proposition 3.9's actual proof/algorithm from the paper,
      not a reconstruction from the definition alone -- next session's starting point, not attempted here
      per the advisor's explicit guidance (reading the paper under end-of-session budget pressure is how
      the two previously-documented wrong drafts happened).
- [x] `scalafmtAll`/`scalafmtCheck`/`scalafmtSbtCheck` clean.
- [x] Regression check: `sbt test` (full suite) confirmed clean through `HomologySpec` (11/11, 110
      expectations), `PersistenceInChunksSpec` (3/3), and `RipserCohomologySpec` (6/6, 603 expectations)
      before the full run hung on `BarcodeRegressionSpec` -- a pre-existing, already-documented OOM-prone
      spec in this sandbox (CLAUDE.md: "OOM'd under the sandbox's default 1GB heap... confirmed by
      re-running with more heap"; still true even at `-Xmx4g` here, unrelated to anything touched this
      session). Killed that hung run after 20+ minutes of zero progress at 660%+ CPU and 4GB heap fully
      pinned, then separately confirmed a clean, complete, non-interrupted run of exactly the three specs
      that could plausibly be affected by this session's changes (`HomologyFixtures.scala`'s two new
      methods, `flattenToCellStream` left behaviorally unchanged): **20/20 examples passed, 0 failures, 0
      errors.** No regression from this session's work.
      (Side note, unrelated to the actual work: while investigating the hang, mistakenly killed IntelliJ
      IDEA's own background `idea-shell` SBT process, thinking it was a stray leftover of my own. It
      wasn't consuming meaningful resources and IDEA will simply respawn it when next needed, but flagging
      it since it wasn't mine to kill without checking first.)
- [ ] Update `CLAUDE.md` with `RipserCohomologyContext`, once apparent pairs are also done, so the
  write-up covers the finished engine rather than needing a second pass.

## Apparent pairs: negative result, two designs ruled out

Definition 3.2 (apparent pair), restated directly against `SimplexIndexing`'s full facet/cofacet
iterators (not `RipserStreamBase.zero*`, see the reuse-vs-reimplement decision below): for a `d`-simplex
sigma, let tau be sigma's cofacet with the **largest** combinatorial index among sigma's cofacets tied at
sigma's own filtration value (this is exactly `coboundaryOf(sigma)`'s unreduced head under
`cohomologyOrdering` -- no reduction needed to find it). The pair (sigma, tau) is *apparent* if,
additionally, sigma is tau's facet with the **smallest** combinatorial index among tau's facets tied at
tau's own filtration value. Implemented and verified correct in isolation against the hand-derived
`threePointLine` example: it identifies exactly `({0,2}, {0,1,2})` as apparent, matching the known
`(1, 3.0, 3.0)` zero-length bar from `RipserCohomologySpec`.

**Reuse-vs-reimplement, decided and not revisited**: `RipserStreamBase.zeroPivotCofacet`/`zeroApparentCofacet`
(`RipserStream.scala`) are built on `SimplexIndexing.cofacetIterator(..., allCofacets = false)` (aka
`topCofacetIterator`), which only enumerates cofacets formed by inserting a vertex *strictly greater than*
the simplex's own maximum vertex -- confirmed against `SimplexIndexingSpec`'s own passing test. If a
simplex already contains `vertexCount - 1`, this returns zero candidates even when a real same-diameter
cofacet exists via a *lower* inserted vertex: a conservative false negative, not a correctness bug on its
own, but not something to build a from-scratch apparent-pairs implementation on top of without first
re-verifying it end-to-end (the existing integration test for it is `.pendingUntilFixed`, i.e. already
flagged unverified). `Cofacets.scala`'s `apparentVertex` is unrelated: it's bookkeeping for lazy generic
coface generation (which vertex is common to every relevant neighbourhood), not a persistence-pairing
notion at all. Reimplemented from Definition 3.2 directly, against the full (unrestricted) iterators.

**The actual question, and how it was answered empirically rather than by proof**: knowing which pairs are
apparent doesn't by itself tell you what's safe to skip. Two designs were tried, both against
`RipserCohomologyContext`'s existing, cross-validated `persistentCohomology()` as the baseline oracle, on
300-500 random Vietoris-Rips point clouds (6-12 points, ambient dimension 2-3, seed 42, `maxDimension = 2`):

1. **Inline identification** (find tau via the apparent check instead of the reduceBy head-check, but
   still call `coboundaryOf` and populate `basis(tau)` exactly as today). Measured whether any pivot found
   this way is later reused as a reduction target by a *different*, later-processed same-dimension sigma
   (i.e. appears as a key in some other sigma's `reduceBy` log). **Found a collision on the second random
   trial** (11-point cloud, `d=1`, tau in `{{3,9,10}, {0,3,10}}`) -- meaning an apparent tau's full chain
   genuinely gets used by another column, so this variant reduces to "do the same work, just find the
   pivot via a more expensive combinatorial check instead of a cheap SortedMap head-check" -- a net
   slowdown with no correctness upside, not worth implementing.
2. **Pre-pass removal** (the version that would actually save the coboundary-enumeration cost that makes
   apparent pairs matter in real Ripser): before the main per-dimension loop, find every apparent pair
   *up front*, remove both sigma and tau from the processing pool, and drop any cofacet term landing in
   the removed-tau set from `coboundaryOf`'s output for every remaining sigma -- i.e. genuinely never
   build tau's chain. **Confirmed unsound by direct counterexample**, an 11-point cloud (same seed 42,
   second trial): baseline pairs sigma=`0.3774735122484958` with tau=`0.4192443983511833`; the pre-pass
   variant, having already removed `0.4192443983511833` as apparent-paired with a *different* sigma
   earlier in the same dimension's sweep, forces the first sigma onto a different (wrong) pivot,
   `0.43995313356383875`. A second, independent instance of the same failure mode: sigma=`0.3864282040846494`
   pairs with `0.3875467629026267` at baseline but `0.4141259103931046` in the pre-pass variant. Both
   collisions are the same underlying fact as design 1's reachability measurement, now visible as an
   actual barcode divergence instead of a set intersection -- the two measurements corroborate each other.

**Conclusion**: a tau that is sigma's own apparent partner can *simultaneously* be some other,
non-apparent sigma-prime's legitimate reduction target. Neither "identify inline" nor "remove up front"
handles this correctly as stated; both need whatever additional condition Proposition 3.9's actual proof
supplies to sequence apparent-pair removal against the rest of the reduction safely (real Ripser's
`compute_pairs` clearly resolves this somehow, since apparent pairs are its primary optimization; the
resolution is not visible from Definition 3.2 alone). **Not attempted this session**: fetching
arXiv:1908.02518 to read Proposition 3.9's proof and the surrounding algorithm text is the correct next
step, deliberately deferred rather than rushed at the end of a long session -- this exact codebase has two
prior "plausible-but-wrong draft" apparent-pairs-adjacent mistakes on record (see `RipserCohomologyContext`'s
class doc), and reading the paper's actual resolution is now a targeted question ("how does Ripser avoid
this specific collision") rather than an open-ended re-derivation.

**Two harness bugs hit and fixed while building this measurement, worth recording since they're traps for
whoever implements this next**:
- The measurement's own helper code summoned `Chain[Simplex[Int], Double] is RingModule` without first
  bringing `ctx.cohomologyOrdering` into scope -- the exact `given`-capture-at-summon-time hazard this
  session's own audit (see "a different ordering bug" above) had *just* been documented as a general rule.
  Caught by the `threePointLine` sanity check producing a wrong dimension-0 death (2.0 became 3.0) with no
  apparent-pair activity at dimension 0 to explain it -- i.e. exactly the kind of "should be a no-op but
  isn't" symptom that rule warns about. Fixed by declaring `given Ordering[Simplex[Int]] =
  ctx.cohomologyOrdering` before summoning `chainRM`.
- `SimplexIndexing.cofacetIterator`/`facetIterator` are purely combinatorial over the full n-point abstract
  simplex and have no notion of `maxDimension` at all -- unlike `RipserCohomologyContext.coboundaryOf`,
  which explicitly truncates (`Chain.empty` when `sigma.dim + 1 > maxDimension`). An apparent-pairs check
  built directly on the raw iterators will "find" apparent cofacets for top-dimension simplices that don't
  actually exist in the truncated complex, wrongly demoting simplices that must stay essential. Fixed by
  guarding the apparent-pair check to `sigma.dim < maxDimension`. Anything built on `SimplexIndexing`'s
  iterators directly (not through `coboundaryOf`) needs this same guard -- an API-boundary mismatch, not a
  bug in either piece individually.

## Flag for the project lead (read this even if skimming the rest)

**Item 1 (below) is now fixed and committed** -- see "Bug found in phase-1 shipped code while building the
cross-validation oracle (now fixed)" above for the full repro, root cause (a *second*, distinct
inconsistency this fix exposed: iteration order and pivot order disagreeing on ties), and verification.
Kept here, marked resolved, so this section's history stays intact rather than being deleted and losing
the trail.

1. ~~`EnumeratingCofaceSimplexStream.filtrationOrdering` is not a total order~~ -- **fixed**: real
   tie-break (dimension, then colex via `simplexIndexing`), and `iterateDimension` in all three coface
   stream implementations now sorts via `.sorted(using filtrationOrdering.reverse)` instead of an
   independently-tie-broken `.sortBy(filtrationValue)`, so iteration order and pivot order are
   provably the same total order.

## A *different* ordering bug, found auditing the other two persistence engines (do not conflate with the one above)

After the `filtrationOrdering`/`iterateDimension` fix above landed, the project lead asked to also "fix
the total order issue" before starting apparent pairs. This turned out to mean auditing whether
`PersistenceInChunksContext` and `SimplicialHomologyByDimensionContext` share the *other* known ordering
hazard already documented in `CellularHomologyContext`'s own class doc and flagged in CLAUDE.md as
"not yet audited": summoning `Chain[CellT, CoefficientT] is RingModule` (`chainRM`) before a
stream-specific `given Ordering[CellT] = stream.filtrationOrdering` is in scope, which silently falls
back to `Chain.scala:27`'s generic `Simplex[VertexT] is OrderedCell`-derived *lexicographic* ordering
instead. This is unrelated to the iteration/pivot-order-consistency bug above -- same *category*
(ordering), different mechanism (implicit-scope capture at `given`-construction time, not two
disagreeing total orders), so keeping the two write-ups separate is deliberate: a future reader
searching for "the ordering bug" should find two, not conflate them into one fix.

Audited both by construction, using `HomologyFixtures.elderRuleCells` (vertex 1 born at 0.0, vertex 9 born
at 10.0, edge `{1,9}` born at 20.0 -- lexicographic order and filtration order disagree about which vertex
dies, so this fixture discriminates the two conventions directly, same as it does for
`CellularHomologyContext`'s own already-fixed version of this bug):

2. **`PersistenceInChunksContext`: audited, confirmed correct, not touched.** `chainRM` is summoned at
   class scope (`Homology.scala:184`), before any stream exists -- structurally identical to the bug
   pattern. But empirically, `persistentHomology(stream).diagramAt(...)` on `elderRuleCells` returns the
   correct answer (`(0, 10.0, 20.0)`, `(0, 0.0, +inf)`). Reading why: every place `HomologyState`'s
   reduction logic needs a pivot (`Chain.reduceByUntil` at `processCell`/`globalReduce`) calls
   `Chain.reduceByUntil[CellT: Ordering, ...]` directly -- a `def` with its own context-bound type
   parameter, resolved fresh at each call site inside `HomologyState`, where `given Ordering[Simplex[VertexT]]
   = stream.filtrationOrdering` (`Homology.scala:193`) *is* in scope. The class-scope `chainRM`'s `⊠`/`-`
   operators are only used in `compress` (lines 328, 334) to build intermediate `Chain` values that get
   fed back into a `Chain.reduceByUntil` call immediately after (in `globalReduce`) -- so even though those
   two operators do carry the stale summon-time-captured ordering internally (confirmed by reading
   `RingModule`'s `scale` implementation, which calls `Chain.from` inside a closure fixed at `chainRM`'s
   own construction), the result's pivot identity is never trusted directly; `reduceByUntil`'s own fresh
   `SortedMap` re-establishes correct pivot order before anything reads `.leadingCell`. Net effect: the
   stale ordering is present but inert on the paths that matter for correctness. Verified empirically, not
   just argued -- do not treat this reasoning as license to skip an empirical check next time a similar
   pattern shows up; the mechanism is subtle enough that "should be inert" is exactly the kind of claim
   that needs a discriminating test, not a read-through.
3. **`SimplicialHomologyByDimensionContext`: audited, found non-functional, not fixed -- flagged as its
   own task.** Constructing `HomologyState` on `elderRuleCells` (any complex with at least one MST edge)
   throws `NoSuchElementException: key not found: 0` at `Homology.scala:427` --
   `barcode(0) = barcode(0).appended(...)` reads a key from `barcode: mutable.Map[Int, immutable.Queue[...]]`
   that `persistentHomology`'s constructor initializes as `mutable.Map.empty`, with no
   `.getOrElse(dim, immutable.Queue.empty)` guard (contrast `PersistenceInChunksContext.recordPair`,
   which has exactly this guard). The same unguarded pattern recurs at line 490 for `currentDim` inside
   `advanceOne`. This throws unconditionally, inside the constructor, for any connected complex with two or
   more vertices -- meaning **this class cannot successfully construct on virtually any real input**, and
   has evidently never been run end-to-end. This means the ordering question that started this audit is
   moot for this class: it never reaches the point of selecting a pivot to be wrong about. For the record,
   once the constructor crash is fixed, the ordering bug would *also* need fixing before this class is
   trustworthy -- it declares no `given Ordering[Simplex[VertexT]] = stream.filtrationOrdering` anywhere,
   so `Chain.from(edge.boundary).leadingCell` at lines 422/435/458 would fall back to the same generic
   lexicographic ordering the other two classes' bug pattern warns about. Both bugs need fixing together,
   with a real spec built from scratch (this class currently has zero test coverage anywhere in the repo),
   as its own scoped task -- not attempted here, since it is feature-sized work on dead code, not "fix the
   ordering issue."

CLAUDE.md updated to match: `PersistenceInChunksContext`'s entry now notes the audit outcome;
`SimplicialHomologyByDimensionContext`'s entry now says "non-functional," not merely "zero test coverage."

## Apparent pairs: resolved (2026-09-15 session)

Picks up directly from "Apparent pairs: negative result, two designs ruled out" above -- read that first,
it's not repeated here. The open question left there: how does Ripser's real `compute_pairs` sequence
apparent-pair removal so a tau that's one simplex's apparent partner is never also needed as a different
simplex's legitimate reduction target? Answered by reading Ripser's actual source
(`github.com/Ripser/ripser`, `ripser.cpp`, MIT license, Ulrich Bauer) rather than re-deriving from
Definition 3.2 alone -- the thing the project lead explicitly asked for.

### What Ripser's C++ actually does

Three pieces, all in `ripser.cpp`:

- **`get_zero_pivot_facet`/`get_zero_pivot_cofacet`** (lines 513-531): the diameter-tied extremal facet/
  cofacet, found by walking the *full* facet/cofacet enumerator and returning the first hit tied in
  diameter. **`get_zero_apparent_facet`/`get_zero_apparent_cofacet`** (533-547): the *mutual* Definition
  3.2 check -- cofacet's own zero-pivot-facet must point back to the original simplex, and vice versa.
  This is exactly what this codebase's `zeroPivotCofacet`/`zeroPivotFacet`/`zeroApparentCofacet` (added
  this session, `Homology.scala`) reimplement, confirmed matching term-for-term against the source.
- **`assemble_columns_to_reduce`** (554-602) excludes any simplex in `is_in_zero_apparent_pair` (either
  role -- birth *or* death) from `columns_to_reduce` entirely, for the *next* dimension. So Ripser never
  separately, explicitly reduces an apparent pair's sigma as its own column, and correspondingly never
  populates `pivot_column_index` for its tau.
- **`compute_pairs`'s main reduction loop** (719-809), specifically line 769: when some *other* column's
  reduction produces a working-chain pivot that is NOT found in `pivot_column_index` (because it was
  excluded from ever being separately reduced), it checks `get_zero_apparent_facet(pivot, dim + 1)` right
  there. If that returns some `e`, it directly folds `e`'s own coboundary into the working reduction
  (`add_simplex_coboundary`) and continues -- computing what would have been `e`'s reduced column ON THE
  FLY, lazily, only because and only when some other column's reduction actually needed it.
- **`init_coboundary_and_get_pivot`** (668-690) additionally has an "emergent pair" fast path (Definition
  3.11 in the paper -- explicitly weaker than Definition 3.2: only "tau is sigma's oldest cofacet," not the
  full mutual condition) for the very *first* tied cofacet found when a column starts: usable as a
  cost-free pivot only if it isn't already a claimed pivot AND doesn't itself have a *different*,
  lower-index apparent facet (`get_zero_apparent_facet(cofacet, dim+1) == -1`) -- a guard specifically
  against exactly the collision Design 1 above found, present in Ripser's own source, not something this
  session invented.

Cross-referenced against arXiv:1908.02518's ar5iv HTML rendering for the paper text: **Definition 3.2**
(apparent pair: sigma youngest facet of tau AND tau oldest cofacet of sigma), **Definition 3.11** (emergent
pair: only ONE of those two conditions, matching the weaker fast-path above), **Lemma 3.3** ("any apparent
pair of a simplexwise filtration is a persistence pair" -- proved via "the boundary matrix has zeros to the
left of an apparent pair, [so] the simplex index is already the column pivot" i.e. the column is "already
reduced from the beginning"), and **Proposition 3.9** (apparent pairs in Rips filtrations are exactly the
lexicographically-maximal-cofacet/lexicographically-minimal-facet mutual pairs -- what `zeroApparentCofacet`
checks).

**The actual resolution to the collision, stated plainly**: Ripser avoids it by NEVER letting a tau that's
someone's true apparent partner be independently claimable by anyone else in the first place
(`assemble_columns_to_reduce`'s exclusion), and compensates for that exclusion with the `compute_pairs`
on-the-fly substitution so correctness is preserved when tau's *value* is still needed elsewhere. This is a
genuinely different mechanism from either design tried and rejected previously in this file -- not a
refinement of either.

### Why tda4j doesn't need Ripser's full machinery -- and where that reasoning nearly went wrong

`RipserCohomologyContext.persistentCohomology` never excludes anything from the dimension-`d` simplex list
it iterates (`cleared` only skips a *later* dimension's redundant reprocessing of an already-claimed pivot,
which predates this session and was already correct). Given that, the claim is: the plain, mutual
Definition 3.2 check (`zeroApparentCofacet`) is sufficient on its own, with no exclusion step and no
on-the-fly substitution fallback needed. The argument: `simplicesAtD` is sorted `fv`-descending with
ascending-index tie-break (`cohomologyOrdering.reverse`, matching Ripser's own
`greater_diameter_or_smaller_index`) -- tau's only possible facets are its own `dim` facets, all strictly
lower `fv` than tau except any tied at tau's own value; a lower-`fv` facet is processed strictly *later*
(descending order), so it cannot reach tau before a tied one does; and among facets tied with tau, the
smallest-index one (`zeroPivotFacet(tau)`, i.e. sigma) is processed first by the tie-break. So sigma is
provably the very first simplex, globally, that can reach tau as a raw-coboundary term at all -- `basis(tau)`
is unclaimed at sigma's turn, by construction, not by luck.

**This reasoning is correct, but a first implementation attempt violated a DIFFERENT, unrelated invariant
and produced a wrong barcode anyway** -- caught empirically, not by re-reading the argument above (which
remains valid and unchanged). First draft, on finding `zeroApparentCofacet(sigma) = Some(tau)`, wrote
`basis(tau) = Chain.from(Seq((tau, sign)))` -- a SINGLE-term chain holding just tau -- reasoning "no
reduction happens, so store the trivial result." That's wrong: Lemma 3.3's "no reduction happens" means
`reduced = z` (sigma's FULL raw coboundary, unchanged, log = Nil) -- not "z only has one term." `z` can and
does have other, non-leading terms: other cofacets of sigma, either tied with tau at the same `fv` (a
different simplex than tau, sharing sigma as a common facet) or at a strictly higher one. Those terms are
exactly what a *different*, later-processed column's own reduction needs to pick up when IT later subtracts
`basis(tau)` to cancel tau out of its own working chain -- storing only `{tau}` silently drops them.

**Confirmed by a direct 12-point counterexample**, found by fuzzing `RipserCohomologySpec`'s own property
(`matrixGen[Double](Gen.double, Gen.chooseNum(2,3), Gen.chooseNum(6,12))`, `minTestsOk=200`) rather than a
hand-built fixture -- the bug survived every hand-built fixture including `threePointLine`'s own apparent
pair:

```
points = Array(Array(0.6840899819795643, 0.8632191311777603), Array(0.7925816891415143, 0.5159681790435516),
  Array(0.28417731332368257, 0.4559405417686636), Array(0.35675429103128775, 0.4806622567817712),
  Array(0.9029384857840582, 0.692961159818487), Array(0.20118350789432415, 0.06900108902109969),
  Array(0.1158610163249012, 0.5096602272996563), Array(0.12795009537546453, 0.906743133696058),
  Array(0.11613774851681025, 0.024181302854615505), Array(0.8012044768533325, 0.5021171135882059),
  Array(0.8937233905719311, 0.16065156938011227), Array(0.5637341324741952, 0.8352605284654816))
```

Edge `{2,7}` (born 0.47710577497688794) and triangle `{2,7,11}` (also born 0.47710577497688794) are a
genuine, mutual Definition 3.2 apparent pair. But edge `{2,7}`'s coboundary also contains triangle `{2,6,7}`
-- born at the SAME value, 0.47710577497688794 (a *different* cofacet of `{2,7}`, tied with `{2,7,11}`, not
apparent-paired with it). Separately, edge `{7,11}` (born 0.4416078462172273, a strictly lower value, hence
processed strictly *later* under the descending-`fv` order) also has `{2,7,11}` as one of its own cofacets
(non-tied for `{7,11}` itself). With the buggy singleton `basis({2,7,11})`, `{7,11}`'s later reduction
subtracted `basis({2,7,11})`, cancelled the `{2,7,11}` term, but -- because the singleton silently dropped
the `{2,6,7}` term that a *correct* `basis({2,7,11})` would also have carried -- did NOT pick up `{2,6,7}`
in its place, and instead reduced through to a wrong, later pivot. Observed: cohomology reported
`(1, 0.4416078462172273, 0.8477007042116179)` where the naive-engine oracle (cross-validated ground truth)
says `(1, 0.4416078462172273, 0.47710577497688794)` -- i.e. `{7,11}` should have died against `{2,6,7}`,
not survived to 0.8477. **Fix**: `basis(tau) = coboundaryOf(sigma)` (the full chain), not a
single-term stand-in. All existing tests plus this specific counterexample pass after the fix; see
`persistentCohomology`'s `Some(tau)` branch in `Homology.scala` for the corrected code and its comment,
which records this exact counterexample for whoever touches this next.

**Lesson for the guide, sharpened by this**: the "provably correct by construction" claim about the CLAIM
ORDER (who gets to `basis(tau)` first) was right and needed no revision. The bug was a completely separate
mistake about the claimed VALUE (what gets stored there) -- proving one part of a design sound does not
make the whole design sound; each moving piece needs its own check. This is consistent with, not a
counterexample to, this file's repeated "obviously sound by inspection still needs a discriminating test"
lesson -- the discriminating test here was the existing property-based spec's own random-input generation,
which had never before hit a point cloud with two triangles tied at the same value as an edge's own
apparent-pair partner. `threePointLine` and the tied-square fixture, both hand-built for this project, are
too small/simple to contain this shape.

### Performance: a real but partial win, not the full Ripser optimization

What's implemented, precisely: `zeroApparentCofacet(sigma)` (the mutual Definition 3.2 check, built fresh
against `si.cofacetIterator`/`si.facetIterator`'s full unrestricted enumeration -- NOT
`RipserStreamBase`'s `zeroPivotCofacet`/`Cofacets.scala`'s `apparentVertex`, both flagged in CLAUDE.md as
using the restricted `allCofacets=false` iterator, which has a confirmed false-negative when a simplex
already contains `vertexCount-1`) gates the main reduction loop: when it fires, `Chain.reduceBy`'s
recursive `SortedMap`-fold reduction pass is skipped (guaranteed by Lemma 3.3 to be a no-op), but
`coboundaryOf(sigma)` is still called in full, because -- per the bug above -- `basis(tau)` needs the
complete chain.

This is NOT Ripser's lazy optimization (never building an apparent sigma's coboundary at all unless
something else needs it): `persistentCohomology` still eagerly enumerates and materializes every simplex at
every dimension up front (`(0 until binomial(n, d+1)).map(idx => si(idx, d+1))`, no threshold/sparsity
filter, unlike Ripser's own incrementally-assembled `columns_to_reduce`), so the enumeration this shortcut
still pays for is not close to Ripser's own baseline cost either. Getting the full lazy version would need
that enumeration strategy changed first -- a separate, larger, not-attempted-here project, and one an
advisor consultation this session specifically recommended against attempting in the same pass as this fix
(see below).

Despite still paying for the full coboundary enumeration, A/B wall-clock measurement (shortcut forced off
via `case None` vs. the real code, `persistentCohomology()` on random Euclidean point clouds, `maxDimension
= 2`, 15-60 warmed-up repetitions per point) shows a real, reproducible, and apparently *widening* speedup:

| n (points) | ambient dim | shortcut OFF (avg) | shortcut ON (avg) | ratio |
|---|---|---|---|---|
| 12 | 3 | 32.4ms | 23.9ms | 1.35x |
| 16 | 2 | 78.8ms | 46.1ms | 1.71x |
| 16 | 3 | 78.6ms | 46.9ms | 1.68x |
| 20 | 3 | 179.1ms | 99.0ms | 1.81x |

Why, given the enumeration cost is unchanged: `Chain.reduceLoop` (`Chain.scala`) is recursive, and each
step does a full `basis(sigma).entries.foldLeft(z)(updateMap)` over a `SortedMap` -- for a non-apparent
sigma, potentially several such steps chained together (cascading reduction), each touching every term of
the accumulator. That cost, not the cofacet enumeration, dominates this loop, and apparent-pair sigmas skip
it entirely. An initial back-of-envelope worry that `zeroApparentCofacet` doubles the enumeration cost (it
calls `si.cofacetIterator` once via `zeroPivotCofacet`, and the `Some` branch's own `coboundaryOf` call
walks it again) turned out to be true but not the dominant term -- caught by measuring rather than trusting
that argument, after an advisor consultation raised it as a reason to expect no win or a regression; a
second consultation, brought back with the A/B numbers, corrected course again. Worth recording as its own
instance of this file's process lesson: measure the actual claim, both directions, before trusting either a
plausibility argument FOR a change or one AGAINST it.

### Status

Implemented, correct (full existing suite green -- `RipserCohomologySpec`, `HomologySpec`,
`SimplexIndexingSpec`, `RipserStreamSpec`, `CofaceSimplexStreamSpec`, `PersistenceInChunksSpec`,
`VietorisRipsSpec`, `SimplexStreamSpec`, 39 examples total across the targeted run plus the pre-existing
`RipserStreamSpec` pending-until-fixed case, unaffected), and a measured performance win. Not the full
Ripser optimization -- see "Performance" above for exactly what's missing and why it's a separate project.
`CLAUDE.md`'s `RipserCohomologyContext` entry should be updated to drop "Apparent pairs are NOT yet
implemented" and reference this section instead (deferred to whoever does the next CLAUDE.md pass, per its
own "update once apparent pairs are also done" note).
