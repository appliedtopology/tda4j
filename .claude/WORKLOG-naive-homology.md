# Session worklog — naive persistent homology + (later) persistent cohomology

Continuation of the alpha-complex work (now in `WORKLOG-alpha-complex.md`). This file tracks the
new, separate task: a clean naive persistent-homology engine, followed by a Ripser-style
persistent-cohomology engine with clear&compress + apparent-pairs. Written to be read cold by a
future session or by the project lead reviewing after the fact.

## Decisions made with the project lead (interview, before any code)

- **Coexistence, not replacement, in general**: new engine(s) should not silently absorb the other
  two existing algorithms (`PersistenceInChunksContext`, `SimplicialHomologyByDimensionContext`).
  Independent implementations have cross-validation value (same reasoning as keeping DQP and Helix
  separate) — a shared engine would let one bug corrupt both the oracle and the thing being checked
  against it.
- **Indexing flexibility = generic `CellT` via `OrderedCell`**, matching the existing pattern (not
  narrowed to `Simplex[VertexT]`, and not (yet) a Ripser-style combinatorial-index representation).
- **Keep incremental querying**: `advanceOne`/`advanceTo(f)`/`diagramAt(f)` style API is a real
  requirement, not a "specialization" to drop for naivety's sake. The naive engine must support
  querying the diagram at a partial filtration value without finishing the whole stream.
- **Persistent cohomology + apparent pairs (phase 2) is scoped to Vietoris-Rips / clique complexes**,
  matching Ripser's actual scope — general `OrderedCell` streams don't have cheap enough implicit
  cofacet enumeration for the apparent-pairs trick to pay off.
- **Mid-course correction**: rather than write a brand-new, structurally-separate class for the
  naive engine, rework `CellularHomologyContext` in place. Justification below — it turned out to
  be broken, and its broken state is the reason a from-scratch parallel class looked necessary in
  the first place. `PersistenceInChunksContext` (already passing 3/3 tests) is fine to serve as the
  independent cross-validation partner instead of needing a third redundant implementation.

## Pre-existing bug found in `CellularHomologyContext` (confirmed, root-caused)

`HomologySpec`'s one test ("Homology of a triangle") was already failing on `scala` HEAD, before
any of this session's changes — confirmed by running `sbt testOnly` before touching anything.
Expected `(1, 3.0, 4.0)`; got `(1,-Infinity,4.0)` and `(1,1.0,Infinity)` instead (two wrong bars,
not one wrong endpoint).

**Root cause** (confirmed by instrumenting `advanceOne` and tracing the triangle example — see
below, not guessed): `CellularHomologyContext` does

```scala
val chainRM = summon[Chain[CellT, CoefficientT] is RingModule]
import chainRM.*
```

at the *class* level, before any stream exists. `HomologyState` (constructed per-stream, nested
inside) separately defines `given Ordering[CellT] = stream.filtrationOrdering` — the filtration-
aware ordering that makes `leadingCell` mean "youngest cell by filtration value," which is what the
whole reduction algorithm depends on for correctness.

The problem: `chainRM`'s `summon` call resolves its `Ordering[CellT]` implicit *once*, at the point
the given `RingModule` instance is constructed (`CellularHomologyContext` class scope) — and at that
point `stream.filtrationOrdering` doesn't exist yet (no stream has been supplied). The only
`Ordering[CellT]` available at that scope is the generic `given [CellT: OrderedCell as oCell] =>
Ordering[CellT] = oCell.ordering` from `Chain.scala`, which for `Simplex[VertexT]` is a plain
lexicographic order on the vertex set — filtration-blind. Scala bakes this into `chainRM`'s method
bodies as a closure; it is not re-resolved later just because a more specific `given` appears in an
inner scope at the *call* site.

Consequence: every `⊠`/`-`/`+` operation in `advanceOne`/`reduceBy` (which go through
`import chainRM.*`) silently reduces using lexicographic cell order, while the handful of direct
`Chain.from(...)`/`Chain(sigma)` calls in the same method correctly pick up the filtration-aware
`Ordering[CellT]` from their own call site. Two incompatible orderings coexist in the same
computation; whichever operand of a `+` happens to be the "target" (whose `.entries` gets cloned)
determines which ordering the merged chain inherits. Confirmed directly: instrumented `advanceOne`
with debug prints, ran the triangle test, and verified `stream.filtrationOrdering.compare({1,2},
{2,3})` correctly reports `{1,2}` as older (filtration value 1 vs 3), while the coboundary chain
built via `⊠`/`+` inside `advanceOne` picks `{1,2}` as `leadingCell` of a 3-term chain containing
`{1,2}` (t=1), `{1,3}` (t=2), `{2,3}` (t=3) — i.e. picks the lexicographically-smallest term, not the
actually-youngest one. That is the direct cause of the wrong bars.

This is not patchable with a tiebreak fix — the `chainRM`-at-class-scope pattern is unsound whenever
the correct `Ordering[CellT]` is stream-dependent and only known later, which is exactly
`CellularHomologyContext`'s situation (that's the whole reason `HomologyState` redeclares the
`given` in the first place — that redeclaration just doesn't reach the already-baked `chainRM`).

**Separate, independent design smell found in the same method** (not itself the cause of the
observed failure, but unnecessary complexity worth removing while rewriting): the existing code does
a *second* `reduceBy(dsigmaReduced, cycles)` call to relocate which currently-open class is being
closed off, then reads off `cyclesBornBy` to get the birth filtration value. This is redundant: in
the standard single-pivot-table algorithm, the pivot cell *is* the birth cell (reduction only stops
at a cell that was never anyone else's pivot, which is exactly the definition of "an open class"),
so no second reduction or auxiliary `cyclesBornBy` map is needed.

## Plan for the rewrite

- [x] Interview the project lead on scope (coexist vs. replace, indexing flexibility, incremental
      query API, phase-2 scope).
- [x] Rename `WORKLOG.md` → `WORKLOG-alpha-complex.md`, `HANDOFF.md` → `.claude/HANDOFF-alpha-complex.md`.
- [x] Confirm current `sbt test` state: `HomologySpec` fails (1/1), `PersistenceInChunksSpec` passes
      (3/3) — establishes `PersistenceInChunksContext` as the cross-validation partner.
- [x] Root-cause the `CellularHomologyContext` failure with concrete evidence (this file, above).
- [x] Wrote a discriminating regression test (elder-rule fixture, non-tied filtration values) that
      pins the pivot-selection bug directly; confirmed it fails on unfixed code for the *original*
      triangle test (the elder-rule test itself didn't discriminate — see judgment-call note below —
      but the pre-existing triangle test already did, and is kept as the primary regression pin).
- [x] Rewrite `CellularHomologyContext`/`HomologyState`:
  - [x] Single unified pivot table (`boundaries: Map[CellT, Chain]`), no separate `cycles`/
        `coboundaries`/`boundariesBornBy`/`cyclesBornBy` bookkeeping duplicating what the pivot table
        already encodes.
  - [x] `chainRM`/`Ordering[CellT]` now summoned *inside* `HomologyState`, after `given
        Ordering[CellT] = stream.filtrationOrdering` — verified empirically (not just by reasoning)
        via the elder-rule test and the now-passing original triangle test.
  - [x] Preserved `advanceOne`/`advanceTo(f)`/`diagramAt(f)`/`barcodeAt(f)` incremental API.
  - [x] Representative-cycle annotation: **kept**, per explicit project-lead instruction mid-session
        (overriding my initial plan to drop it). Reimplemented cleanly as a V-column reconstruction
        (`generators: Map[CellT, Chain]`, one per pivot, holding the *producing* cell's own V-column;
        `positives` now stores `(birth, representative)` pairs) — see the class doc in Homology.scala
        for the derivation. This avoids the old code's redundant second `reduceBy` against a separate
        `cycles` map (unnecessary: the pivot cell *is* the birth cell already) and, since it's built
        from the same (now correctly-scoped) `chainRM`, doesn't reintroduce the ordering bug.
        `barcodeAt` now actually populates `PersistenceBar`'s `annotation` (`Some(chain)`) instead of
        the previous hardcoded `None`.
  - [x] Fixed `advanceTo`'s off-by-one: was strict `f > headFv` (a cell exactly at `f` never got
        consumed); now `f >= headFv` (closed-birth/open-death convention, documented inline).
  - [x] Fixed essential-bar upper endpoint: was unconditionally `filtration.largest`, even for a
        partial (non-exhausted-stream) query; now capped at the query value `f` unless the stream is
        actually exhausted (`CellIterator.hasNext` check).
- [x] Test suite (`HomologySpec.scala`, `HomologyFixtures.scala`):
  - [x] Shared fixture object (`HomologyFixtures.scala`) with the triangle/tetrahedron/torus
        complexes hand-verified in `PersistenceInChunksSpec`, plus the new elder-rule fixture.
  - [x] Tetrahedron + torus now also run through the naive engine (previously only exercised via
        `PersistenceInChunksContext`).
  - [x] Bars-account-for-cells structural invariant (`2*finite + essential == totalCells`) — project
        lead corrected my first draft of this (I initially wrote a stray comment implying `bars ==
        cells`; the actual check I coded was already right).
  - [x] `diagramAt` exact-query-value boundary convention test.
  - [x] Representative-cycle correctness: (a) general zero-boundary property across all three
        fixtures, run over `Fp(11)` (exact arithmetic, not `Double`, per the numerical-robustness
        precedent already established for this codebase's alpha-complex work); (b) an exact,
        hand-derived check on the elder-rule fixture (dying class's representative is exactly
        `Chain(∆(9))`, essential class's is exactly `Chain(∆(1))` — worked out by hand from the
        algorithm's own recursion, not just asserting whatever the code happened to produce).
  - [x] Cross-validation against `PersistenceInChunksContext` on all three fixtures.
  - [x] Field-independence property (`F_2`, `F_3`, `Q`/`Double` agree) on all three fixtures.
- [x] Full `sbt test` run to check for regressions elsewhere.
- [x] Update `CLAUDE.md`'s description of `CellularHomologyContext` to reflect the rewrite.
- [x] `scalafmtAll` run and `scalafmtCheck`/`scalafmtSbtCheck` verified clean.
- [x] Fixed a birth/death filtration-value fallback bug found in advisor review (see below).
- [x] Added real Vietoris-Rips stream coverage (advisor review flagged this as the load-bearing gap:
      every prior test used a hand-built `ExplicitStream`) — which immediately surfaced and let me fix
      a second, more serious bug (see "Critical performance bug" below).
- [ ] Phase 2 (separate future session/task, not started): persistent cohomology + clear&compress +
      Ripser apparent-pairs removal, scoped to VR/clique complexes. **Full plan below** — written up
      so the next session doesn't need to re-derive or re-discuss any of this before starting.

## Phase 2 plan: persistent cohomology + clear&compress + apparent pairs

Written before a `/clear`, specifically so the next session can start directly from this rather than
re-covering ground already settled. Read this whole section before writing any code.

### Goal, restated precisely

A persistent-*co*homology engine, following Ulrich Bauer's Ripser algorithm (*Ripser: efficient
computation of Vietoris–Rips persistence barcodes*, arXiv:1908.02518 — the standard reference; the
existing `CofacetIterator` in `Cofacets.scala` already has a comment flagging exactly this paper as the
thing to check its `apparentVertex` logic against). Two optimizations on top of the plain reduction
algorithm phase 1 implements:

1. **Clear&Compress**: once a `(d-1)`-cell is identified as the birth side of a persistence pair
   (paired with a `d`-cell), its own `d`-dimensional column can be cleared/skipped in higher-dimension
   reduction, since it's already known to be non-essential. `PersistenceInChunksContext` already
   implements a *homological* version of clear&compress (chunk-local reduction + global column
   compression) — phase 2 needs the *cohomological* analogue, which is structurally different (Ripser
   reduces coboundary columns dimension-by-dimension in increasing order, clearing forward into the
   *next* dimension up, not compressing within one dimension).
2. **Apparent pairs (zero-persistence pairs)**: a combinatorial shortcut that identifies certain
   persistence pairs *without doing any reduction at all*, by checking whether a simplex's coboundary
   is dominated by a single cofacet that is, symmetrically, dominated by that simplex among its own
   facets. This is what makes Ripser fast — most pairs in a typical Vietoris–Rips filtration are
   apparent pairs.

**Scope, per the interview before phase 1 started**: Vietoris–Rips / clique complexes specifically, not
general `OrderedCell` streams — apparent pairs need cheap *implicit* cofacet enumeration (no
materialized coboundary), which only clique complexes give you cheaply via the combinatorial number
system. This is a deliberate narrowing from phase 1's "generic `CellT: OrderedCell`" scope, agreed
before phase 1 began — see "Decisions made with the project lead" at the top of this file.

### Existing building blocks — evaluate before reusing, don't assume correct

This codebase's track record this session (and the alpha-complex session before it, see
`WORKLOG-alpha-complex.md`) is that existing-but-unexercised code in this area has repeatedly turned
out to be subtly wrong when actually scrutinized (the `chainRM`-scope bug, `HelixDelaunay`'s two bugs,
the dead `"miniball"` dispatch, `DualQP`'s degeneracy handling). Apply the same skepticism here — don't
assume any of the following is correct just because it compiles and existed before this session:

- **`Cofacets.scala`'s `CofacetIterator[VertexT]`**: lazy VR cofacet enumeration with an
  `apparentVertex: Option[VertexT]` field computed as a side effect of the enumeration. Its own code
  comment (`Homology.scala:626`, inside the commented-out prior art below) says explicitly: "TODO
  Check with Ulrich Bauer carefully that this is the right thing to check" — i.e. even its original
  author wasn't confident this was right. Verify against the paper's actual apparent-pair definition
  before trusting it.
- **`RipserStream.scala`'s `SimplexIndexing`**: the combinatorial-number-system simplex <-> integer
  index mapping (binomial coefficient tables, `facetIterator`/`cofacetIterator` by index). This is the
  "Ripser-style combinatorial index" option that was *not* chosen for phase 1's indexing-flexibility
  question — worth reconsidering for phase 2 specifically, since apparent-pairs' actual performance
  payoff depends on implicit (non-materialized) cofacet enumeration, which is exactly what this gives
  you. **Better-attested than most of the rest of this list**: `SimplexIndexingSpec.scala` checks it
  directly against Ulrich Bauer's own paper's worked examples (`si(∆(0,3,5)) must be_==(13)` and
  similar, plus facet/cofacet-iterator checks) — real, if not exhaustive, verification against the
  primary source. A reasonable building block to actually trust, modulo the usual "re-verify on your
  own adversarial cases before leaning on it hard" caution.
- **`RipserStream.scala`'s `RipserStreamBase`/`RipserStreamSparse`**: already implement
  `zeroPivotCofacet`/`zeroPivotFacet`/`zeroApparentCofacet`/`zeroApparentFacet` — i.e. apparent-pair
  detection via the combinatorial index, used today only to *filter which simplices appear in a
  stream* (`iteratorByDimension`), not to actually compute a persistence pairing. The file's own
  comment above this code says **"Maybe @deprecate or outright everything below here?"** — flagged by
  a previous author as being of uncertain value/correctness. `RipserStreamSpec.scala` has one small
  spot-check of `zeroPivotCofacet`/`zeroPivotFacet` on the square (`HyperCube(2)`) that passes, but the
  broader integration test one might actually want — "Ripser and Vietoris-Rips find the same
  simplices," comparing `RipserStream`'s apparent-pairs-filtered output against an independent
  `SymmetricZomorodianIncremental`-based enumeration — **is marked `.pendingUntilFixed`, i.e. it is
  currently NOT passing.** So: the low-level primitive has a real (if thin) spot-check; whether
  apparent-pairs filtering actually produces a *correct* stream end-to-end is explicitly
  known-unverified by the existing suite, not just untested. Read the four `zero*` methods for the
  ideas, but the `.pendingUntilFixed` test is a direct signal not to trust the integration without
  redoing that verification.
- **`Homology.scala` lines 519-740 (commented out)**: `RipserHomology` and `computePersistentHomology`
  — an earlier, abandoned attempt at exactly this task, explicitly kept as "prior art" per
  `CLAUDE.md`'s existing framing. Uses `CofacetIterator.apparentVertex` (line 626). Worth reading for
  the general shape of a cohomology reduction loop (it tracks `cocycleMaps`/`coboundaryMaps` per
  dimension), but it's unfinished/abandoned code, never tested, and inherits whatever's uncertain about
  `CofacetIterator.apparentVertex` above. Do not resurrect by uncommenting without re-deriving the
  logic against the paper first — this is exactly the kind of code this session found bugs in when
  actually exercised.

### Hard-earned lessons from phase 1 that must carry over

1. **The `chainRM`/`Ordering[CellT]` scope hazard.** Any `given RingModule`/`Ordering[CellT]` instance
   whose correctness depends on a stream that doesn't exist yet must be summoned *inside* whatever
   holds the stream, never at an enclosing class scope. This exact mistake caused phase 1's original
   bug; the same shape of mistake would be easy to reintroduce in a from-scratch cohomology engine.
   Two other latent instances of this same pattern are already known and documented but NOT fixed:
   `PersistenceInChunksContext` (`Homology.scala`, `chainRM` at class scope) and `package.scala`'s
   `TDAContext`. A third, different manifestation (a specific `given Ordering[BarcodeEndpoint[Double]]`
   not being found due to a more generic `given` intercepting the search) is noted in the judgment-
   calls list below. If phase 2 needs a coboundary/cochain analogue of `RingModule`, watch for this
   from the start rather than discovering it after the fact.
2. **Use the object-level `Chain.reduceBy`/`reduceByUntil` (`SortedMap`-based), never hand-rolled
   reduction over raw `Chain` `+`/`-`/`⊠`.** Raw `Chain` arithmetic only collapses the *head* of its
   internal priority queue lazily; hand-rolling a reduction loop (or any fold accumulating many chain
   operations, like phase 1's V-column reconstruction) over raw arithmetic silently reintroduces
   superlinear blowup from uncollapsed duplicate entries. This cost phase 1 a genuine multi-minute
   hang on a complex that should take milliseconds, caught only because real VR-stream tests existed.
   If a coboundary-side reduction primitive doesn't already exist as a `SortedMap`-based function,
   write one in that style before using it inside any loop, not raw-`Chain`-arithmetic style.
3. **Test against real Vietoris–Rips streams from the start, not just hand-built tiny fixtures.**
   Every phase-1 bug that mattered in practice (the performance bug, the tied-filtration-value
   ordering concern) was invisible on hand-built `ExplicitStream` fixtures and only surfaced once a
   real `EnumeratingCofaceSimplexStream`/`LimitedCofaceSimplexStream` was fed through. Phase 2's
   *entire point* is Vietoris–Rips complexes, so this should be the primary testing mode from day one,
   not an afterthought added under review pressure the way it was in phase 1.
4. **`Fp` (exact arithmetic) as the primary correctness coefficient, `Double` only for an interop
   smoke test.** Zero-detection during reduction must not be confused with floating-point noise.
5. **`StratifiedCellStream`'s default `.iterator` hangs** (documented in `PersistenceInChunksContext`'s
   and `PersistenceInChunksSpec`'s comments, and worked around directly in
   `HomologySpec.flattenToCellStream`). If phase 2's engine consumes a stream via `.iterator`, either
   feed it something with a correct `.iterator` override, or fix the default — don't rediscover this
   the hard way a third time.

### Validation strategy

- **Primary oracle: agreement with phase 1's naive engine and/or `PersistenceInChunksContext` on the
  *same* Vietoris–Rips streams.** Persistent homology and persistent cohomology over a field have
  provably identical barcodes (standard duality result) — so any real VR complex's cohomology barcode
  from the new engine must exactly match `SimplicialHomologyContext`'s (or `PersistenceInChunksContext`'s)
  barcode on the same input. This is a strong, free correctness check, analogous to how DQP was
  cross-validated against Helix for alpha complexes — with the same caveat: `PersistenceInChunksContext`
  has its own not-yet-audited latent `chainRM`-scope issue (see above), so treat agreement with it as
  weaker evidence than agreement with phase 1's engine specifically (which has now had its ordering
  bug fixed and confirmed via the elder-rule regression test).
- **Hand-verified small examples**, same style as phase 1's `HomologyFixtures.scala` — reuse those
  fixtures directly where they're Vietoris–Rips-compatible (the square fixture from
  `HomologySpec.scala`, built specifically to exercise a tied face/coface filtration value, is exactly
  the kind of adversarial-but-small case apparent-pairs logic needs to be checked against).
  **Apparent-pairs-specific test**: construct a small complex with a *known, hand-verified* apparent
  pair (e.g. exploit the tied square fixture — the diagonal edge and one of its cofacet triangles tie
  in filtration value, which is the precondition for an apparent pair) and assert both (a) the
  shortcut actually fires (detects the pair without doing a real reduction) and (b) skipping the real
  reduction for that pair doesn't change the final barcode versus phase 1's naive engine.
- **Structural invariants carried over from phase 1**: bars-account-for-cells
  (`HomologyFixtures.totalBarsAccountForAllCells`), representative-(co)cycle zero-coboundary property
  if cocycle representatives are kept (see open question below), chain-size-bounded-by-complex-size as
  the non-flaky performance-regression proxy.

### Decisions from the project lead on the three open questions above (resolved before /clear, same session)

1. **Indexing type: specialize to `Simplex[Int]` / the combinatorial number system.** Explicitly
   confirmed: *"Yes, specialize as much as you want - the binomial representation is appropriate."*
   Note this is a deliberate narrowing of phase 1's stated goal ("coefficient field and simplex
   indexing type both should be flexible") — not a peer-level open question resolved the same way as
   the other two, but an explicit, informed sign-off on trading away indexing-type genericity for
   phase 2 specifically, scoped to VR/clique complexes only. Coefficient field genericity (`Field`
   typeclass) is unaffected and should remain flexible as in phase 1.
2. **Incremental query API: one-shot.** Confirmed: *"your point is well taken on one-shot vs.
   streaming, make it one-shot."* Phase 2 does not need `advanceTo`/`diagramAt(f)`-style incremental
   querying; full-stream-in, full-barcode-out is sufficient.
3. **Representative cocycles: keep them.** Confirmed: *"it is important to get hold of cocycles."*
   Phase 2 must track and expose representative cocycles alongside the barcode, matching phase 1's
   representative-cycle requirement (the mechanism will differ — cohomological V/U-style bookkeeping
   under clear&compress, not phase 1's direct V-column reconstruction — but the deliverable
   requirement is the same: no bar without a witness).

## Critical performance bug found and fixed (via advisor-prompted VR-stream testing)

The first version of the representative-cycle rewrite (V-column reconstruction) hand-rolled its own
`reduceBy`, operating directly on raw `Chain` objects via the `⊠`/`-` operators, mirroring the *style*
of the original (buggy) code. This passed all hand-built-fixture tests (triangle/tetrahedron/torus/
elder-rule) in well under a second. The moment it was pointed at an actual small Vietoris-Rips stream
(8-12 points, via the VR smoke test added in response to advisor review below) it consumed a full CPU
core for 8+ minutes without finishing (confirmed via `ps` showing sustained 115% CPU on the test JVM,
not a blocked/idle process) — a complex with under 300 cells that should take milliseconds.

**Root cause**: `Chain`'s `+`/`-`/`⊠` operators only collapse the *head* of their internal priority
queue lazily (via `collapseHead()`, called implicitly by `.leadingCell`/`.isZero()`); they never
collapse the *whole* structure. Building up a representative cycle (`vcol`) via repeated raw
subtraction in a fold -- and, symmetrically, my hand-rolled `reduceBy` doing the same for the ordinary
boundary reduction -- accumulates an ever-growing backlog of duplicate, uncollapsed `(cell, coeff)`
entries every generation, since each new `vcol`/reduced-column references *previous*, themselves
uncollapsed, `vcol`/boundary values. For the tiny fixtures (≤45 cells, mostly non-interacting classes)
this backlog never gets large enough to matter. For a real VR complex, where many cells' reductions
chain through several previously-recorded pivots, the backlog compounds across cells and blows up
superlinearly.

**Fix**: replaced the hand-rolled `reduceBy` with the existing `Chain.reduceBy` (the object-level,
`SortedMap`-based primitive `PersistenceInChunksContext` already uses) — which collapses duplicates by
construction on every insertion, since a `SortedMap` update can't accumulate stale entries the way a
raw priority-queue merge can. Also added an explicit `vcol.collapseAll()` before storing/using it,
since the `vcol` fold itself is separate, still-raw-`Chain`-arithmetic code that `Chain.reduceBy`
doesn't cover. After the fix, the full `HomologySpec` (11 examples, including 100 ScalaCheck-generated
VR complexes) runs in ~2.6 seconds.

**Follow-up hardening from a second advisor pass**: the collapse-before-store invariant is load-
bearing but easy to silently break in a future refactor (moving `vcol.collapseAll()` after the
`positives`/`generators` writes would reintroduce the blowup) — pinned with an explicit comment at the
call site. Added a non-flaky, deterministic regression proxy for the same bug class: every
representative-cycle test now also asserts `rep.items.size <= totalCells` (a chain over the complex
can't legitimately have more distinct raw entries than the complex has cells; this fails hard under
the uncollapsed-duplicate regime instead of merely running slowly, so a future regression shows up as
a test failure, not just "the suite got slow"). Also tightened the square-fixture tie assertion from a
vague "some filtration value is shared across two dimensions" check to the specific, hand-verified
pair it's meant to exercise (`∆(0,2)` and `∆(0,1,2)` both at filtration value √2), and switched both VR
tests from calling `persistentHomology` twice (two independent `HomologyState`s, one per assertion) to
once, so both assertions are checked against the exact same computation.

**Why this matters beyond just this bug**: `Chain.scala`'s own doc language already says the object-
level `reduceBy`/`reduceByUntil` are "the generic matrix-reduction primitives... the homology
algorithms build on" — i.e., the intended design was already to reuse these, not hand-roll fresh
reduction logic per algorithm. The original (buggy) `CellularHomologyContext` also hand-rolled its own
`reduceBy` over raw `Chain` arithmetic rather than using the object-level one; this may well have had
the *same* latent performance characteristic (impossible to say for certain post-rewrite, since it's
gone now, but the mechanism -- raw `Chain` `-`/`⊠` never fully collapsing -- was identical). Worth
remembering if `SimplicialHomologyByDimensionContext` (which also hand-rolls chain arithmetic in
places) is ever profiled and found slow on real streams.

## Fixed: birth/death filtration-value fallback had the wrong direction for deaths

`cellFiltrationValue`, a helper I introduced purely for readability, used a single fallback
(`filtration.smallest`) regardless of whether the cell being looked up was serving as a birth or a
death. The original code's death-side fallback was `filtration.largest` (`stream.filtrationValue
.orElse(_ => filtration.largest)(sigma)`) -- deliberately the *opposite* direction from a birth's
fallback, so that a cell with no assigned filtration value is treated as "hasn't happened yet" when
it's about to end a bar (conservative: never silently kill a class before it's known to have died) and
"already happened" when it's about to start one. Caught in advisor review before it could produce a
`death < birth` bar for any complex with a genuinely partial `filtrationValue` (e.g. `SparseMetricSpace`
-backed streams). Fixed by giving `cellFiltrationValue` an explicit `fallback` parameter, supplied
per call site.

## Final status (phase 1 — naive persistent homology)

Final `sbt test` run, against the actual final code (confirmed by grepping this run's own output for
`HomologySpec`'s block, not inferred): **108 examples, 103 passed, 2 failed, 3 errors, 1 pending**.
`HomologySpec` itself: **11/11, 0 failures, 0 errors**, confirmed both in this full run and in an
isolated re-run (110 expectations, ~2.6s). `PersistenceInChunksSpec`: 3/3, confirmed in an isolated
re-run against the same final code. Every failure/error in the full run is one of the same pre-
existing, already-documented, out-of-scope issues CLAUDE.md and `WORKLOG-alpha-complex.md` already
call out, none touched by this session's changes:

- `SimplexSpec`, `APISpec`: pre-existing failures, unrelated to homology (confirmed by CLAUDE.md's
  existing "known, explicitly out-of-scope" notes from the alpha-complex session).
- `BarcodeRegressionSpec`'s "Alpha Miniball" example: the already-known dead `"miniball"` dispatch
  string (`MatchError: miniball` in `AlphaShapes.scala`) — the Miniball backend was removed entirely
  in prior work; this dispatch case just hasn't been deleted from the test yet.
- `BarcodeRegressionSpec`'s "Alpha Helix"/"VR" examples: `OutOfMemoryError` under this sandbox's 1GB
  default heap — an environment artifact matching the exact pattern already documented in
  `WORKLOG-alpha-complex.md` ("OOM'd under the sandbox's default 1GB heap... confirmed by re-running
  with more heap"), not a real failure. (`CofaceSimplexStreamSpec`, which OOM'd in one earlier run this
  session, passed cleanly in the final run -- same heap-pressure flakiness, not a real failure either
  way, and unrelated to any file this session touched.)

`HomologySpec` (11/11, up from the original 2) and `PersistenceInChunksSpec` (3/3) — the two specs
this session's changes actually touch or depend on — both pass cleanly, verified both in the full run
and in isolated re-runs. `scalafmtCheck`/`scalafmtSbtCheck` clean after `scalafmtAll`.
`mimaReportBinaryIssues`: no previous artifacts configured (pre-1.0), nothing to check.

### Files changed this session

- `../src/main/scala/org/appliedtopology/tda4j/Homology.scala` — `CellularHomologyContext`/
  `HomologyState` rewritten (see above); `PersistenceInChunksContext` and
  `SimplicialHomologyByDimensionContext` untouched.
- `../src/test/scala/org/appliedtopology/tda4j/HomologySpec.scala` — extended with 9 new examples (kept
  the original 2, for 11 total); `BarcodeRegressionSpec` in the same file untouched.
- `../src/test/scala/org/appliedtopology/tda4j/HomologyFixtures.scala` (new) — shared triangle/
  tetrahedron/torus/elder-rule fixtures and the bars-account-for-cells invariant helper.
- `CLAUDE.md` — `CellularHomologyContext` description updated to reflect the rewrite and the
  confirmed root cause.
- `WORKLOG.md` → `WORKLOG-alpha-complex.md`, `HANDOFF.md` → `.claude/HANDOFF-alpha-complex.md` (renamed,
  content untouched) — both describe the prior, now-separate alpha-complex work.
- `WORKLOG-naive-homology.md` (this file, new).

Not touched: `PersistenceInChunksSpec.scala` (fixtures duplicated into `HomologyFixtures.scala`
rather than refactoring this already-passing file's own copies, to keep the diff to a known-good file
at zero — a judgment call, flagged below), `AlphaComplexDQP.scala`/`AlphaShapes.scala` and their
specs (untouched, unrelated to this task), `package.scala`'s `TDAContext` (has the same latent
class-scope-`chainRM` pattern, noted above, not fixed).

## Assumptions / judgment calls flagged for review

- **Kept representative-cycle computation** after initially planning to drop it (it was dead code in
  the old implementation — `barcodeAt` discarded it as `None` regardless). The project lead corrected
  this mid-session: representative cycles/cocycles should stay. Reimplemented from scratch rather than
  restoring the old logic, since the old logic's redundant second reduction was itself a design smell
  independent of the ordering bug. Not yet re-validated on the tetrahedron's *2-dimensional* bar by
  hand (only checked structurally via the zero-boundary property, plus an exact hand-check on the
  simpler elder-rule 0-dimensional case) — worth a closer look if representative cycles turn out to
  matter for phase 2's cohomology work.
- **My first attempt at a "discriminating" regression test (the elder-rule fixture) didn't actually
  discriminate the bug** when I first wrote it against the unfixed code — it passed even before the
  fix. Root cause of *that* (a smaller, separate finding): the corrupted ordering only manifests when
  a chain arithmetic operation combines ≥2 cells with distinct, non-tied filtration values *that were
  themselves already present together in one multi-term chain built via `⊠`/`+`* — a 2-vertex/1-edge
  complex never constructs such a chain (every intermediate step only ever touches one term at a
  time). The original triangle test's failure came specifically from the `coboundary` fold combining
  three already-multi-term chains. I kept the elder-rule fixture anyway since it's a real, useful,
  independent correctness check (elder rule under non-tied filtration values) — it just isn't the
  bug's regression pin; the original triangle test is.
- **`PersistenceInChunksContext` has the identical `chainRM`-at-class-scope pattern** (line ~148,
  same shape as the bug just fixed). Not touched — its 3/3 tests still pass, and cross-validating the
  new naive engine against it is part of this session's test suite, so if this latent pattern were
  actually causing wrong answers there too, agreement between the two engines would be weaker evidence
  than it looks. Flagging for a future session rather than fixing now (out of the agreed scope, and
  fixing it now would mean re-deriving whether its test fixtures happen to avoid triggering it, same
  as I initially got wrong for the elder-rule fixture above).
- **`package.scala`'s `TDAContext`** also summons a `Chain[...] is RingModule` at a class scope
  independent of any stream, for user-facing convenience chain arithmetic (`export chainIsRingModule.*`).
  Same latent pattern, lower severity since it's not itself running a persistence algorithm. Not
  touched; noted for awareness only.
- **A third instance of this session's recurring implicit-resolution hazard, found while writing
  tests**: `Ordering[BarcodeEndpoint[Double]]` (the given instance `Barcode.scala` defines explicitly)
  is not summonable from `HomologySpec.scala`, even with `import org.appliedtopology.tda4j.barcode.*`
  in scope. The compiler instead finds and fails on `Chain.scala`'s generic
  `given [CellT: OrderedCell as oCell] => Ordering[CellT]`, trying to satisfy it with `CellT =
  BarcodeEndpoint[Double]` (which has no `OrderedCell` instance) rather than finding the correct,
  specific instance. Worked around with a plain pattern-matching `endpointValue` helper rather than
  chasing the resolution order — this is the same class of "generic OrderedCell-derived given
  interferes with a more specific one" issue as the original `chainRM` bug and the earlier
  `.forall`/`ValueCheck` gotcha already documented in `CLAUDE.md`, just a third manifestation of it.
  Not root-caused (that's its own investigation); flagging so a future session doesn't waste time
  re-discovering it from scratch, and so it's on record as a recurring pattern worth root-causing once,
  broadly, rather than working around piecemeal each time it's hit.
- **Did not refactor `PersistenceInChunksSpec.scala` to source its fixtures from the new shared
  `HomologyFixtures.scala`**, even though its triangle/tetrahedron/torus literals are now duplicated
  there. That file is already passing and untouched by this session; I judged the value of DRY-ing it
  up didn't outweigh the (small but nonzero) risk of a copy/refactor mistake in an already-correct
  test file, versus just re-typing (and re-verifying byte-for-byte against the original) the same
  literals into the new shared file. If a future change to one of these fixtures is ever needed,
  both copies will need updating by hand — flagging so that isn't a surprise.
