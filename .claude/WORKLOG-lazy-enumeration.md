# Session worklog — full Ripser-style lazy enumeration for RipserCohomologyContext

Continuation of `WORKLOG-cohomology.md`'s "Apparent pairs: resolved" section (read that first — it has
the full apparent-pairs derivation, the counterexample that killed the naive singleton-`basis(tau)`
draft, and the explicit "Performance" note this session picks up from verbatim):

> This is NOT Ripser's lazy optimization (never building an apparent sigma's coboundary at all unless
> something else needs it): `persistentCohomology` still eagerly enumerates and materializes every
> simplex at every dimension up front... Getting the full lazy version would need that enumeration
> strategy changed first — a separate, larger, not-attempted-here project.

This file tracks that project. Written to survive a `/clear` or session gap — the user is stepping away
shortly after kicking this off, with instructions to keep going and log decisions here.

## Orientation done before writing any code

Re-read (from this same day's earlier session, still fresh): `WORKLOG-cohomology.md` in full,
`WORKLOG-naive-homology.md`'s Phase 2 plan section, `.claude/NOTES-for-guides.md`. Read the current
`RipserCohomologyContext`/`persistentCohomology` (`Homology.scala:563-786`), `Chain.scala` in full
(`reduceLoop`/`reduceByUntil`/`reduceBy`, all private-to-object except the two `final def`s), and
`SimplexIndexing`/`RipserStreamBase` (`RipserStream.scala`) for the existing `cofacetIterator`/
`topCofacetIterator`/`zero*` primitives.

**Fetched `ripser.cpp` (github.com/Ripser/ripser, MIT, Ulrich Bauer) directly for three specific
questions**, rather than trusting the previous session's already-fairly-detailed paraphrase, because the
one detail that actually changes this session's design (does the on-the-fly substitution cache its
result anywhere) wasn't nailed down verbatim before:

1. **`assemble_columns_to_reduce`**: expands cofacets of the *previous round's full simplex list*
   (`simplices`/`next_simplices` — every dimension-d simplex that survived the `threshold` cutoff, NOT
   just the filtered `columns_to_reduce` subset), not direct binomial-index enumeration. A generated
   cofacet is added to `columns_to_reduce` (the set that gets independently, explicitly reduced) only if
   it's below `threshold`, not already `is_in_zero_apparent_pair`, and not already a claimed pivot.
   Critically: **the exclusions only shrink `columns_to_reduce` (what gets independently reduced), not
   the cofacet-generation frontier itself** — `next_simplices.push_back(...)` for the *next* round
   happens unconditionally, before any exclusion check.
2. **`compute_pairs`'s on-the-fly substitution**: confirmed **no caching, anywhere** — when some other
   column's reduction hits a pivot missing from `pivot_column_index`, it calls
   `get_zero_apparent_facet(pivot, dim+1)`, and if found, recomputes that facet's *raw* coboundary fresh
   via `add_simplex_coboundary` right there, on every single occurrence, including if the *same* pivot is
   hit again later by a *different* column's reduction. This directly settles the design question of
   whether to add a cache to tda4j's version: real Ripser doesn't, so neither will this implementation
   unless a measurement says otherwise.
3. **`init_coboundary_and_get_pivot`'s emergent-pair fast path**: confirmed matches
   `WORKLOG-cohomology.md`'s prior paraphrase exactly (diameters tied, cofacet not already a claimed
   pivot, cofacet has no zero-apparent-facet of its own) — Definition 3.11, weaker than the mutual
   Definition 3.2 check. Not implemented this session (see scope decision below) but confirmed correct
   for later reference.

## The real complexity finding, worth stating plainly before designing anything

Ripser's cofacet-expansion enumeration (`topCofacetIterator`-style: only insert vertices strictly above
the simplex's current maximum, so each higher-dimensional simplex is generated from exactly ONE of its
facets, not `dim+2` of them) is a genuine constant-factor win over this codebase's current
`(0 until binomial(n, d+1)).map(idx => si(idx, d+1))` — it avoids per-index binary-search decoding
(`SimplexIndexing.apply`, O(d log n) each) in favor of incremental cofacet stepping directly off an
already-known index, and it can be driven straight off the previous dimension's simplex list instead of
mass-generating and mass-sorting a fresh `binomial(n,d+1)`-sized array.

**But it is NOT an asymptotic win for this specific codebase's design**, and this needs to be said before
implementing anything rather than discovered afterward: `RipserCohomologyContext` deliberately has no
`maxFiltrationValue`/threshold parameter (CLAUDE.md: "deliberately dropped... not required by the
phase-2 plan," matching `AlphaShapeDQP`'s established untruncated-by-default precedent). Ripser's real
enumeration savings come from `threshold` pruning cofacets *before* they're ever counted — with no
threshold (this codebase's actual configuration), the cofacet-expansion frontier still has to visit every
one of the `binomial(n, d+1)` simplices at each dimension, because in an untruncated complex every one of
them genuinely exists. The `is_in_zero_apparent_pair`/already-a-pivot exclusions in
`assemble_columns_to_reduce` shrink `columns_to_reduce` (what gets independently, explicitly reduced) —
but explicitly do NOT shrink the cofacet-generation frontier itself (`next_simplices` grows
unconditionally), so they don't reduce the total enumeration count either.

**Where the real, threshold-independent win actually is**: not in changing which simplices get
enumerated, but in changing WHEN their coboundaries get computed. The existing apparent-pairs shortcut
(landed last session) still unconditionally calls `coboundaryOf(sigma)` in full for every apparent pair,
purely so `basis(tau)` is populated in case some *other* column's reduction ever needs it — but per this
same session's own counterexample-hunting, that reuse is the *exception*, not the rule (found on the
*second* random trial out of hundreds, not on most of them). Deferring that computation — only building
`coboundaryOf(sigma)` when a different column's reduction actually reaches `tau` as an unresolved pivot —
is the piece that's genuinely, unconditionally cheaper, independent of any threshold, and is exactly
Ripser's own `compute_pairs` substitution mechanism (confirmed no-cache, per point 2 above).

## Scope decision for this session (recorded before writing code, not after)

Given the finding above, "the enumeration changes needed for full Ripser-style lazy generation" splits
into two genuinely separate pieces with very different payoffs in this untruncated codebase:

1. **Deferred/lazy `basis(tau)` construction via on-the-fly substitution** (Ripser's `compute_pairs`
   mechanism, no caching, matching the confirmed-verbatim semantics above). Real, unconditional,
   threshold-independent win: skips `coboundaryOf(sigma)` entirely for every apparent pair that no other
   column's reduction ever actually reaches. **In scope, implementing this session.**
2. **Cofacet-expansion-based `columns_to_reduce` assembly, replacing the direct
   `(0 until binomial(n,d+1))` enumeration+sort.** Architecturally the "right" Ripser-shaped structure,
   and a real constant-factor win (avoids per-index binary-search decode) — but NOT the asymptotic win
   the name suggests without a threshold, and re-plumbing the per-dimension loop to consume an
   incrementally-assembled candidate set instead of a directly-indexed range is a materially larger,
   separate change with its own correctness risk (a fresh enumeration mechanism to cross-validate, not
   just a reduction-loop tweak to an already-proven-correct engine). Flagged for the project lead rather
   than silently done or silently skipped — see "Flag for the project lead" below.

Not proceeding with (2) without checking in, and not silently adding a `maxFiltrationValue`/threshold
parameter either (that would be the thing that actually makes (2) pay off, but it's a scope decision
this session isn't authorized to make unilaterally — CLAUDE.md records the "always untruncated" choice
as deliberate).

## Advisor consult (before writing code) and how it changed the plan

Called before touching `Chain.scala`/`Homology.scala`. Two corrections, both adopted:

1. **Don't defer the named deliverable and hand back a memo instead** — the user asked specifically for
   "the enumeration changes needed for full Ripser-style lazy generation," and my first-draft plan above
   (skip enumeration restructuring entirely, do only the substitution piece) would have ended the session
   with that literally undone. Correction: check whether `filtrationValue` memoizes first, since if it
   doesn't, THAT'S the real enumeration cost this session should address, not the (threshold-gated)
   cofacet-expansion restructuring. Checked directly (`FiniteMetricSpace.scala`,
   `MaximumDistanceFiltrationValue.apply`): confirmed **no memoization at all** -- every call recomputes
   an O(d^2) max over pairwise vertex distances from scratch, and `cohomologyOrdering`'s comparator calls
   it twice per comparison across every `O(C(n,d+1) log C(n,d+1))`-comparison sort. This was the largest
   single, unconditional, threshold-independent cost in `persistentCohomology`'s enumeration -- fixed
   this session (see Status below), and it's the concrete "enumeration change" landed here.
2. **Recompute the substitution's partner combinatorially, don't consult a recorded map.** My first draft
   planned a `mutable.Map[Simplex[Int], Simplex[Int]]` (tau -> sigma) built up during the sweep. The
   advisor's objection: `zeroApparentCofacet`'s existing soundness proof only establishes that sigma is
   the first simplex whose RAW, unreduced coboundary can reach tau -- it says nothing about a mid-cascade
   column reaching tau as an ALREADY-partially-reduced intermediate pivot before sigma's own turn in the
   sweep. A recorded map would need a second, harder soundness argument to be trustworthy in that case; a
   pure combinatorial recomputation (`zeroApparentFacet`, mirroring `zeroApparentCofacet` from the other
   direction) doesn't, because the mutual Definition 3.2 condition is a fact about filtration values and
   indices alone, independent of when or how tau is reached. Adopted directly -- see `zeroApparentFacet`'s
   doc in `Homology.scala` for the fuller version of this argument. This also happens to exactly match
   Ripser's own confirmed-from-source behavior (recompute, never cache), so fidelity and the correctness
   argument point the same way here.

## What shipped this session

1. **`filtrationValue` memoization** (`RipserCohomologyContext`): a `mutable.HashMap` wrapper around the
   existing `MaximumDistanceFiltrationValue` partial function. `Simplex[Int]` is an opaque type over
   `SortedSet[Int]`, so it has correct structural `hashCode`/`equals` for free as a map key -- no bridging
   needed. The single biggest, unconditional win: median times at the same benchmark sizes dropped by
   roughly 3x across the board (both `useApparentPairs` on AND off), confirming this cost was shared by
   both arms and was the dominant one, not a niche apparent-pairs-specific cost.
2. **On-the-fly apparent-pair substitution**, matching Ripser's `compute_pairs` exactly (confirmed via
   direct `ripser.cpp` fetch, not recalled): `Chain.scala`'s `reduceLoop`/`reduceByUntil`/`reduceBy` gained
   an optional `fallback: CellT => Option[Chain[CellT, CoefficientT]]` parameter (default
   `(_: CellT) => Option.empty[...]`, so every pre-existing call site -- `CellularHomologyContext`,
   `PersistenceInChunksContext`, `SimplicialHomologyByDimensionContext` -- is unaffected). Consulted only
   when a working chain's leading pivot has no `basis` entry. `RipserCohomologyContext` wires this to a
   new `zeroApparentFacet` (the mirror-image of `zeroApparentCofacet`) that recomputes the apparent pair's
   birth-side partner combinatorially, then recomputes `coboundaryOf` on that partner FRESH, every time --
   no caching, matching the confirmed Ripser source behavior. The apparent-pair branch in
   `persistentCohomology` no longer calls `coboundaryOf(sigma)` at all, and never writes `basis(tau)` --
   only `generators(tau)` (a trivial single-term V-column, still required so later columns' V-column folds
   don't hit the "pivot has a basis entry but no generators entry" throw).
3. **Discriminating tests, not just a barcode check**: `RipserCohomologyContext.substitutionCount`
   (reset per `persistentCohomology()` call, incremented only when the fallback actually fires) plus
   three new `RipserCohomologySpec` examples -- asserting it's `> 0` on the known-collision 12-point cloud
   from `WORKLOG-cohomology.md`, `== 0` with `useApparentPairs = false`, and (per a SECOND advisor pass,
   after the first draft of this session's tests only covered the one pinned cloud) `> 0` in total across
   200 sampled random Vietoris-Rips clouds from the SAME generator the cross-validation property already
   uses -- without this third test, that 200-trial property could pass every time while never once
   exercising the substitution path, which would make it useless as a regression guard for this session's
   own change. Per the advisor's point: a test that only checks the final barcode can't distinguish "the
   fallback fired and computed correctly" from "the fallback never fired at all," and this session's own
   predecessor bug (the singleton-`basis(tau)` mistake) is exactly the shape of thing that gap misses.

### Type-inference wrinkle hit while implementing (worth recording, not just fixing silently)

Adding `fallback: CellT => Option[Chain[CellT, CoefficientT]] = (_: CellT) => None` as a new default
parameter on `reduceByUntil`/`reduceBy` broke compilation at every EXISTING call site that relies on the
default (`SimplicialHomologyByDimensionContext`'s four call sites) with `Found: Chain[Nothing, Nothing]`
errors -- Scala 3 monomorphizes a default-argument expression built from a bare `None` literal
(`Option[Nothing]`) independently of the enclosing generic method's own type parameters, and
`Chain[Nothing, Nothing]` isn't a subtype of `Chain[CellT, CoefficientT]` (`Chain` is invariant in both
parameters). Fixed by writing the default as `Option.empty[Chain[CellT, CoefficientT]]` instead of bare
`None` -- an explicit type ascription using the method's own type parameters, which resolves correctly.
Worth keeping in mind for any future generic default parameter of `Option[F[CellT, ...]]` shape in this
codebase.

## Status

- [x] Orientation: worklogs, current code, `ripser.cpp` fetch for the three unresolved semantic
      questions above.
- [x] This worklog file.
- [x] Advisor consult on the scope split above and the substitution-loop design, before writing code --
      changed the plan twice (see above).
- [x] `filtrationValue` memoization (the actual enumeration-cost fix, per the advisor's redirection).
- [x] Design + implement deferred `basis(tau)` / on-the-fly substitution in `persistentCohomology`, via a
      new `fallback` parameter on `Chain.reduceByUntil`/`reduceBy` and a new `zeroApparentFacet`.
- [x] Cross-validate: full targeted regression (`RipserCohomologySpec`, `HomologySpec`,
      `PersistenceInChunksSpec`, `SimplexIndexingSpec`, `RipserStreamSpec`, `CofaceSimplexStreamSpec`,
      `VietorisRipsSpec`, `SimplexStreamSpec`) -- 42 examples, 0 failures, 0 errors, 1 pending
      (pre-existing, unrelated). New discriminating test (`substitutionCount > 0` on the known-collision
      cloud, `== 0` with apparent pairs off) added and passing.
- [x] Measure, isolated (per a second advisor pass that specifically flagged the first draft's "dropped
      ~3x" claim as inferred, not measured -- comparing two DIFFERENT n-ranges run at different times isn't
      a controlled A/B, and this codebase's own history is explicit about not trusting an unmeasured causal
      claim). Re-ran `ApparentPairsBenchmarkSpec` at the SAME sizes (n=12,16,20; 5 trials) three ways,
      toggling only `filtrationValue`'s memoization (temporarily reverted to `= rawFiltrationValue`, ran,
      restored -- not left as a permanent flag in shipped code, on the same "don't keep dead code for a
      one-off measurement" reasoning as the decision not to keep an eager-vs-lazy toggle):

      | n  | unmemoized on (ms) | unmemoized off (ms) | ratio | memoized on (ms) | memoized off (ms) | ratio |
      |----|---------------------|----------------------|-------|-------------------|---------------------|-------|
      | 12 | 10.898              | 14.756                | 1.35x | 9.167             | 11.774              | 1.28x |
      | 16 | 14.873              | 23.332                | 1.57x | 11.870            | 15.845              | 1.33x |
      | 20 | 23.437              | 39.465                | 1.68x | 14.322            | 26.200              | 1.83x |

      The unmemoized column reproduces last session's original 1.35x/1.57x(1.71x)/1.68x numbers almost
      exactly (small variance expected, different run) -- a useful sanity check that nothing else changed
      underneath. Memoization's own effect, isolated: BOTH arms get faster at every size (e.g. at n=20,
      "on" drops 23.4ms -> 14.3ms, "off" drops 39.5ms -> 26.2ms -- roughly 1.5-1.6x each), confirming it's
      a shared, not apparent-pairs-specific, cost as expected; the on/off RATIO itself doesn't move in any
      single clean direction at this sample size (down slightly at n=12/16, up at n=20) -- consistent with
      it being a second-order effect on top of the ratio, not the main story. The separately-run n=24-32
      sweep from before this correction (1.6x/1.78x/1.78x, memoized) stands as evidence the ratio holds up
      and grows at larger n, just not attributed to memoization specifically -- corrected from the earlier,
      overreaching "the memoization is why the ratio changed" framing.
- [x] `scalafmtAll`/`scalafmtCheck`/`scalafmtSbtCheck` clean.
- [x] `CLAUDE.md` updated: `RipserCohomologyContext`'s entry now describes the lazy substitution and the
  memoization, and points at this file for the deferred threshold/enumeration-restructuring piece.
- [x] This section: the distance-threshold API-redesign note for the next session (see below), per the
      project lead's explicit request mid-session.

## Flag for the next session: adding a distance threshold (sparse Rips), and what it changes

The project lead asked, mid-session, to eventually carry over Ripser's distance-threshold
(`maxFiltrationValue`) machinery too, and for this to be left as a concrete note rather than something
the next session has to re-derive. This is NOT attempted here -- it's a genuinely separate, larger piece
(see the scope-split reasoning above: it's what would make cofacet-expansion-based enumeration an
asymptotic win rather than just an architectural one). Concretely, what it would touch:

1. **`RipserCohomologyContext`'s constructor** gains a `maxFiltrationValue: Double =
   Double.PositiveInfinity` parameter, matching `RipserStreamBase`'s existing convention elsewhere in this
   codebase (`RipserStream.scala`) -- default preserves today's always-untruncated behavior exactly, so
   every existing call site is unaffected (the same "no-op default" pattern `useApparentPairs` already
   uses).
2. **`coboundaryOf`** currently truncates only by `sigma.dim + 1 > maxDimension`. It needs a second guard:
   any candidate cofacet `tau` with `filtrationValue(tau) > maxFiltrationValue` doesn't exist in the
   thresholded complex either, and must be filtered out of the `Chain` it builds -- not just skipped for
   enumeration purposes, since a coboundary chain containing a nonexistent simplex would corrupt reduction
   silently, the same class of bug this codebase has hit before with truncation boundaries.
3. **The per-dimension enumeration itself** (`simplicesAtD`, currently
   `(0 until binomial(n, d+1)).map(idx => si(idx, d+1)).sorted(using cohomologyOrdering.reverse)`) is where
   the real restructuring lives, and where it becomes worth doing once a threshold exists: direct
   binomial-indexing generates and evaluates the filtration value of every one of the `C(n,d+1)` simplices
   even though a sparse threshold would exclude most of them at dimension >= 2 or so. Replace with
   Ripser's own mechanism: build dimension d+1's candidates from the cofacets of dimension d's FULL
   simplex list (not just the ones actually reduced -- Ripser's `next_simplices`/`simplices` split,
   confirmed from source this session: `assemble_columns_to_reduce`'s exclusions only shrink
   `columns_to_reduce`, i.e. what gets independently reduced, never the cofacet-generation frontier used
   for the dimension after that), filtered to `filtrationValue <= maxFiltrationValue` as they're
   generated, so simplices past the threshold are never even constructed.
4. **Use `SimplexIndexing.topCofacetIterator` (the restricted "insert only above current max vertex"
   iterator, `allCofacets = false`) for this expansion, NOT the full/unrestricted one.** This is the
   actual efficiency trick: it generates each (d+1)-simplex from exactly one of its facets (the one
   missing its own top vertex), so no deduplication step is needed, unlike a naive "cofacets of every
   facet" expansion which would generate each higher simplex `d+2` times. This restricted iterator has a
   documented false-negative for the APPARENT-PAIRS mutual check specifically (misses a same-diameter
   cofacet formed by inserting a vertex below the current max -- see `zeroPivotCofacet`'s doc and
   CLAUDE.md) -- that caveat is about using it to detect ties, not about using it for plain enumeration,
   where it's exactly the right, already-proven-in-Ripser primitive. Don't let that caveat block reusing
   it here; keep `coboundaryOf`/`zeroPivotCofacet`/`zeroApparentCofacet`/`zeroApparentFacet` on the full
   iterator as they are now, and use the restricted one only for the NEW enumeration-assembly code path.
5. **`SparseMetricSpace`** (`FiniteMetricSpace.scala`, already exists, used elsewhere to bound Vietoris-
   Rips construction) is the natural fit for dimension-1's own bootstrap (edges within the threshold) --
   worth wiring `RipserCohomologyContext` to accept or construct one internally rather than reinventing
   edge-cutoff logic, but the SAME single `maxFiltrationValue` threshold applies uniformly at every higher
   dimension too (a simplex's filtration value is the max of its pairwise distances, so the one cutoff
   value is enough; no separate per-dimension threshold is needed).
6. **Cross-validation gets a new dimension.** Every existing test compares two engines running on the SAME
   (untruncated) complex. A threshold changes WHICH simplices exist at all, so validating a thresholded
   `RipserCohomologyContext` against `CellularHomologyContext`/`PersistenceInChunksContext` requires
   feeding those engines an equivalently-thresholded stream (e.g. via `SparseMetricSpace` combined with
   `LimitedCofaceSimplexStream`, or an explicit filter) -- not just re-running the existing fixtures with a
   new constructor argument, since those fixtures were built assuming the full complex exists.
7. **This is a bigger, riskier piece than this session's change**: it's a new enumeration MECHANISM (not a
   tweak to an already-proven-correct reduction loop), so treat it with the same skepticism this codebase's
   history recommends for "existing but unexercised code in this area" -- build a fresh discriminating
   fixture (something that puts the threshold cutoff in genuine tension with a filtration-value tie, the
   same way `elderRuleCells`/the tied-square fixture did for the ordering bugs) before trusting it, not
   just a structural bar-count check.

## Session 2 (2026-09-16): sparse Rips + optional memoization -- now in progress

The project lead came back and asked for exactly this deferred piece, plus a correction to the previous
session's memoization work: **memoization should be optional, not the default**, because Ripser's actual
historical design goal was memory frugality (the classic bottleneck for persistent homology
implementations) -- the speedups were a side effect of THAT, not the primary goal. A global
`mutable.HashMap` cache of every filtration value ever touched runs directly against that goal for large
complexes. Also explicitly approved: changing `RipserCohomologyContext`'s constructor signature, and
NOT trying to keep this compatible with `AlphaShapeDQP`'s always-untruncated convention -- "alpha shapes
and ripser reproduction are different sections of the library with minimal interactions."

### Orientation done before writing code

Re-read `Cofacets.scala`'s `CofacetIterator` (a multi-way merge across `SparseMetricSpace.neighborhoods`'
pre-sorted, threshold-bounded neighbor lists, lazily emitting cofacet vertices in increasing-distance
order) and `FiniteMetricSpace.scala`'s `SparseMetricSpace` (VP-tree-backed, precomputes each vertex's
sorted within-threshold neighbor list once). **Decision: build the new sparse machinery fresh on
`SimplexIndexing`/`Simplex[Int]` + `SparseMetricSpace`, NOT on `Cofacets.scala`'s `CofacetIterator`.**
`CofacetIterator` is more sophisticated (a genuine lazy multi-way merge, not a brute-force scan) but its
own code comment flags it as unverified against the paper's actual apparent-pair definition, and
`RipserCohomologyContext`'s entire existing machinery (this session's predecessor and the one before it)
is already built on `SimplexIndexing`'s combinatorial-index primitives, which ARE independently verified
(`SimplexIndexingSpec` against the paper's own worked examples). Reusing an unverified primitive here
would undercut the same "verify before trusting" discipline this codebase's history keeps re-learning the
hard way. `SparseMetricSpace`'s precomputed neighbor lists are still a reasonable building block for a
LATER optimization pass (see below) -- just not for the first, correctness-first version.

### The real design realization: diameter-carrying enumeration, not a cache

Ripser's actual cofacet enumerator (`simplex_coboundary_enumerator` in `ripser.cpp`) never recomputes a
simplex's diameter from its raw vertex set. It carries `diameter_index_t = (diameter, index)` pairs
through the whole recursive expansion: a cofacet's diameter is `max(parent's already-known diameter,
max over parent's vertices of distance-to-the-newly-inserted-vertex)` -- an O(d) computation given the
parent's diameter, not this codebase's current O(d^2) `MaximumDistanceFiltrationValue.apply` (a full
pairwise max from scratch, ignorant of any already-known partial answer). This is BETTER than a cache on
every axis that matters here: O(1) extra space per currently-live simplex (not an ever-growing global
map), no hashing overhead, and it eliminates the expensive computation entirely rather than paying for it
once and reusing the answer. This is the piece last session's HashMap-memoization design didn't have, and
it's the reason "optional memoization" isn't just "add a boolean to keep or drop a nice-to-have" -- the
diameter-carrying design makes memoization unnecessary for the NEW enumeration/assembly code path
specifically, while the flag still matters for `cohomologyOrdering`'s OTHER call sites (see below).

### Where `filtrationValue`/`cohomologyOrdering` is still called after this change, and why memoization still matters there

`cohomologyOrdering: Ordering[Simplex[Int]]` stays as the ambient `given Ordering[Simplex[Int]]` for
`Chain`/`chainRM`'s `SortedMap`/`PriorityQueue`-based reduction machinery throughout `persistentCohomology`
-- `Chain.reduceBy`'s internal `updateMap`/`toSortedMap` compare simplices via whatever `Ordering[CellT]`
is in scope on EVERY pivot lookup/insertion during reduction, not just once per dimension's outer sort.
Replacing the outer per-dimension enumeration with diameter-carrying assembly does NOT eliminate these
calls -- they're a separate, likely much larger, set of `filtrationValue` invocations (every reduction
step, not once per dimension). So the `memoizeFiltrationValue` flag remains meaningful and independently
useful even after the enumeration rewrite: default `false` (memory-frugal, matching the project lead's
explicit intent) means `cohomologyOrdering` recomputes O(d^2) on every such comparison during reduction;
`true` opts back into this session's earlier HashMap-cache behavior for anyone who wants the speed/memory
tradeoff on a smaller complex.

### Plan (to be sanity-checked with the advisor before implementing)

1. `RipserCohomologyContext` constructor gains `maxFiltrationValue: Double = Double.PositiveInfinity` and
   `memoizeFiltrationValue: Boolean = false`.
2. Factor `cohomologyOrdering`'s comparator logic (fv primary, `si(y) compareTo si(x)` tie-break) into a
   shared, reusable function of `(fv, index)` pairs, so a NEW ordering over diameter-carrying candidates
   can reuse the identical tie-break logic rather than being a second, independently-written comparator
   (the exact trap `NOTES-for-guides.md` item 3 and this file's own earlier advisor correction both flag).
3. A small carrier, e.g. `case class DiameterSimplex(diameter: Double, simplex: Simplex[Int])`, plus an
   `insertionDiameter(sigma, sigmaFv, newVertex)` helper implementing the O(d) incremental formula above.
4. A new cofacet-assembly primitive built on "insert a vertex strictly above the simplex's own max" (the
   same canonical-facet convention `SimplexIndexing.topCofacetIterator` already uses and
   `SimplexIndexingSpec` already verifies) -- generates each higher simplex from EXACTLY one lower facet,
   no deduplication needed. First cut: scan candidate vertices `v > sigma.max`, filtered by
   `maxFiltrationValue` via the incremental formula -- O(n) candidates per simplex, no worse
   asymptotically than what `SimplexIndexing.cofacetIterator`'s own unfold already does today. Flagged as
   a later optimization, not attempted in the first cut: restricting candidates via
   `SparseMetricSpace.neighborhoods` intersection instead of scanning all `v > sigma.max`, which would
   pay off specifically on genuinely sparse configurations.
5. `persistentCohomology`'s per-dimension candidate list gets assembled incrementally, dimension by
   dimension, carrying `DiameterSimplex` forward -- **critically, from the FULL previous dimension's
   simplex list, not just the ones actually reduced.** Confirmed from `ripser.cpp`'s own
   `assemble_columns_to_reduce` (fetched last session): `cleared`/apparent-pair exclusion shrinks which
   simplices get independently reduced at a dimension, but NOT which simplices are used as a source for
   generating the NEXT dimension's candidates -- a cleared simplex's own higher cofacets still exist in
   the complex and must still be reachable. Getting this backwards (assembling only from non-cleared
   simplices) would silently omit real simplices from higher dimensions -- a correctness bug, not a
   missed optimization, so it needs its own discriminating test, not just a bar-count check.
6. `coboundaryOf`/`zeroPivotCofacet`/`zeroPivotFacet` all gain a `maxFiltrationValue` guard alongside
   their existing `maxDimension` guard, using the same incremental-diameter formula rather than
   `filtrationValue(tau)`'s full recompute, for consistency with the new design's whole point.
7. Open question for the advisor: whether to also land Definition 3.11's emergent-pairs fast path
   (confirmed semantics from `ripser.cpp` last session, not yet implemented) as part of this "aggressive
   optimizations" pass, or scope it out as a further, separate piece the way apparent-pairs substitution
   and this enumeration rewrite were each their own session.

### Advisor verdicts on the plan (both adopted before writing code)

The canonical-facet uniqueness argument (only the facet missing tau's own max vertex can regenerate tau
under a strictly-above-max insertion rule) and the full-list-vs-`columns_to_reduce` distinction (item 5
above) were both confirmed correct as stated -- the full-list point is grounded directly in the fetched
`ripser.cpp` source (`next_simplices.push_back(...)` executes before the exclusion checks). Two changes
adopted: **the oracle** -- not thresholding the naive engine's own stream (a real trap:
`SparseMetricSpace.distance` returns `+Infinity` past its cutoff rather than excluding the simplex, so
`EnumeratingCofaceSimplexStream` over it emits simplices the sparse engine excludes outright, for reasons
unrelated to any bug) -- use instead the free, exact oracle that a thresholded VR filtration's persistence
is the untruncated filtration's own persistence restricted to `[0,t]`, both sides from the SAME engine so
tie-breaks agree and the FULL bar list (zero-length bars included) can be compared, a strictly stronger
check than the cross-engine `birth < death`-only comparison. **Emergent pairs: explicitly deferred** -- its
guard is exactly an interaction between `basis` occupancy and apparent-pair exclusion, the class of
interaction two prior designs in this file already died on; pure speed with zero correctness content, so
it layers cheaply onto a verified base later rather than risking it in the same pass as a new enumeration
mechanism. Also: never key a `Set`/`Map` by `DiameterSimplex` (its case-class equality includes the
`Double` diameter) -- always key by `.simplex`; use `<=` consistently for every threshold comparison.

## Session 2: implementation, measurement, and final status

Implemented exactly as planned above (Sections 1-6; emergent pairs deferred per the advisor verdict).
`RipserCohomologyContext` gained `maxFiltrationValue: Double = Double.PositiveInfinity` and
`memoizeFiltrationValue: Boolean = false` as new trailing constructor parameters (both defaulted, so no
existing call site needed updating); `Chain.scala`'s `reduceLoop`/`reduceByUntil`/`reduceBy` were untouched
this session (already had the `fallback` parameter from Session 1). New: `compareFvThenIndex` (factored
out of `cohomologyOrdering` so `diameterSimplexOrdering` shares the identical tie-break, not a second
independently-written comparator), `DiameterSimplex`, `insertionDiameter`, `sparseCofacets`. `coboundaryOf`
and `zeroPivotCofacet` were rewritten to use `insertionDiameter` instead of `filtrationValue(tau)` per
candidate; `zeroPivotFacet` was deliberately left alone (documented scope boundary: no equally-cheap
incremental formula exists for the facet direction). `persistentCohomology`'s per-dimension loop now
carries `var currentLevel: Seq[DiameterSimplex]` forward across dimensions instead of directly indexing
`(0 until binomial(n, d+1))`, assembling the next level from `simplicesAtD.iterator.flatMap(sparseCofacets)`
-- from every simplex actually in `simplicesAtD`, cleared ones included, matching item 5's plan exactly.
Added `totalSimplexCount` (a public accessor, mirroring `substitutionCount`'s pattern) since the binomial
formula `RipserCohomologySpec`'s existing structural-invariant test relied on is wrong once a threshold
exists.

**First-pass regression guard, the cheapest and most important check**: re-ran the ENTIRE pre-existing
`RipserCohomologySpec`/`HomologySpec`/`PersistenceInChunksSpec` suite (all at the new code's
`maxFiltrationValue = +Infinity` default) immediately after the rewrite compiled, before writing a single
new test. Bit-identical: every pinned fixture, every cross-validation property (200 random clouds each),
the apparent-pairs substitution-firing counters, all passed unchanged. This is what actually pins the
whole enumeration rewrite -- a materially different mechanism (incremental diameter-carrying assembly
instead of direct combinatorial indexing) producing the exact same answer on inputs already independently
verified correct.

**New tests, all passing** (`RipserCohomologySpec`, 16 examples / 1409 expectations total after this
session): the free-oracle restriction property (200 random clouds, full bar list including zero-length
bars); a threshold larger than the cloud's own diameter matches the untruncated barcode exactly (200
random clouds); `maxFiltrationValue = +Infinity` passed explicitly changes nothing (pinned cloud); a hand-
verified concrete case (`threePointLine` at `t=2.5`, strictly between the 2.0 and 3.0 pairwise distances --
the longest edge and the triangle, both born at 3.0, are excluded OUTRIGHT, not merely truncated at death,
leaving a plain path graph with trivial H^1); the bars-account-for-cells structural invariant using the new
`totalSimplexCount` instead of the now-wrong binomial formula (200 random clouds); `memoizeFiltrationValue`
toggled true/false changes nothing about the computed barcode (200 random clouds).

### Measured performance -- both numbers the advisor's second pass asked for, not inferred

**Sparse-vs-dense, `SparseRipsBenchmarkSpec` (new, mirrors `ApparentPairsBenchmarkSpec`'s style)**, threshold
scaled as `2.5/sqrt(n)` so the expected neighborhood size stays roughly constant as `n` grows (a FIXED
threshold would make the complex denser as `n` grows, defeating the point of measuring sparse behavior at
increasing scale), n=40-80, 5 trials, maxDim=2:

| n  | sparse (ms) | dense (ms) | time ratio | sparse # simplices | dense # simplices | size ratio |
|----|-------------|------------|------------|---------------------|---------------------|------------|
| 40 | 34.3        | 132.2      | 3.85x      | 1,017               | 10,700              | 10.52x     |
| 60 | 44.7        | 416.6      | 9.32x      | 1,869               | 36,050              | 19.29x     |
| 80 | 63.3        | 956.4      | 15.10x     | 2,632               | 85,400               | 32.45x     |

**The advisor's first-pass number here (before `totalSimplexCount` was added to the benchmark output) was
correctly flagged as an artifact risk**: a raw time-ratio comparison conflates "the new mechanism does less
wasted work" with "it's simply computing a smaller complex." Adding the simplex counts settles the
question directly: the size ratio EXCEEDS the time ratio at every `n` (10.52x vs 3.85x at n=40, all the way
to 32.45x vs 15.10x at n=80) -- meaning the observed speedup is not just explained by, but actually runs
*below*, the complex-size reduction. Honest framing, stated plainly rather than claimed as "the enumeration
mechanism is smarter": the threshold lets the engine compute a genuinely smaller complex, and per-simplex
enumeration cost scales with what's actually assembled rather than with `binomial(n, d+1)` -- exactly what
the architecture was built to do, no more and no less.

**`memoizeFiltrationValue`'s own cost, previously unmeasured (the second gap the advisor's post-
implementation pass caught)**: `SparseRipsBenchmarkSpec` extended with a `-Dmemoize` flag, same n=40-80
range, comparing the DENSE (`maxFiltrationValue = +Infinity`) column only (the enumeration/assembly path
never touches memoized `filtrationValue` at all regardless of this flag -- `cohomologyOrdering`'s use
inside `Chain.reduceBy`'s `SortedMap` comparisons, present at every reduction step regardless of threshold,
is what this flag actually controls the cost of):

| n  | dense, memoize=false (ms) | dense, memoize=true (ms) | slowdown from defaulting to false |
|----|----------------------------|----------------------------|------------------------------------|
| 40 | 132.2                      | 105.7                      | 1.25x                               |
| 60 | 416.6                      | 360.7                      | 1.16x                               |
| 80 | 956.4                      | 912.8                      | 1.05x                               |

A real but modest, and shrinking-with-n, cost -- 5%-25%, not a multiplicative regression. Stated plainly
per the advisor's instruction ("if it's a 1.5x regression... that's fine and defensible given the stated
memory priority -- but it needs to be stated, not discovered by the user later"): defaulting
`memoizeFiltrationValue` to `false` costs real wall-clock time on the dense path, and that's an accepted,
explicit tradeoff for the project lead's stated memory-frugality goal, not a free lunch.

### Smaller corrections made along the way

- `midThreshold`'s doc comment originally overclaimed ("no simplex's own filtration value ever lands
  exactly on the threshold," phrased as if general) -- tightened to state the guarantee is specific to `t`
  being a strict interior midpoint of two adjacent sorted DISTINCT pairwise distances in THIS cloud, not a
  property of any threshold construction in general.
- `args.commandLine.doubleOr`/`.boolOr` (specs2's `CommandLine`, used for `SparseRipsBenchmarkSpec`'s new
  `-DthresholdScale`/`-Dmemoize` flags) were confirmed to exist and work by actually running the benchmark
  with them, not assumed from `ApparentPairsBenchmarkSpec`'s existing `.intOr` usage alone.

### Status: done for this session

- [x] `maxFiltrationValue` (sparse Rips threshold) and `memoizeFiltrationValue` (opt-in, default off) both
      implemented, per the project lead's explicit direction.
- [x] Diameter-carrying incremental enumeration (`DiameterSimplex`/`insertionDiameter`/`sparseCofacets`),
      replacing direct `(0 until binomial(n,d+1))` indexing -- the actual "enumeration change" the whole
      two-session arc was aimed at.
- [x] Free-oracle correctness validation (thresholded = untruncated restricted to `[0,t]`) plus every
      existing regression fixture confirmed bit-identical at the default threshold.
- [x] Both benchmark numbers the advisor's post-implementation pass specifically asked for, measured (not
      inferred), with an honest read of what each one actually shows.
- [x] `scalafmtAll`/`scalafmtCheck`/`scalafmtSbtCheck` clean; full targeted regression suite green (49
      examples across `RipserCohomologySpec`, `HomologySpec`, `PersistenceInChunksSpec`,
      `SimplexIndexingSpec`, `RipserStreamSpec`, `VietorisRipsSpec`, `SimplexStreamSpec`,
      `CofaceSimplexStreamSpec` -- 0 failures, 0 errors, 1 pending, pre-existing and unrelated).
- [x] `CLAUDE.md` updated to describe the shipped state (sparse Rips implemented, memoization optional and
  defaulted off, both with measured numbers) rather than the "next session" framing Session 1 left it
  in.
- [ ] Not attempted, deferred on purpose per the advisor's verdict: Definition 3.11 emergent pairs (pure
      speed, its own correctness-interaction risk, layers cleanly onto this now-verified base later).
- [ ] Not attempted, named as a live option rather than an oversight: Ripser's own compact `(Double, Int)`
      `diameter_index_t` representation -- `DiameterSimplex` carries a full `Simplex[Int]`/`SortedSet[Int]`
      instead, a speed/simplicity choice made against the project's stated memory goal.
- [ ] `SparseMetricSpace.neighborhoods`-based candidate pruning for `sparseCofacets` (currently scans every
      `v > sigma.max` and filters by distance, same asymptotic shape `SimplexIndexing.cofacetIterator`'s
      own unfold already had) -- a further win specifically on very sparse configurations, not attempted
      in this pass.

Nothing committed to git this session -- the project lead did not ask for a commit.

**Update**: committed by the project lead directly as `e5281bf` ("More of the apparent pairs optimizations
added.") -- all of this file's code/test changes (`Chain.scala`, `Homology.scala`,
`ApparentPairsBenchmarkSpec.scala`, `RipserCohomologySpec.scala`, `SparseRipsBenchmarkSpec.scala`) landed in
that one commit. A separate, unrelated commit `b24f0b2` ("Tuning the test suites.") followed, made by the
project lead directly (not this session): filled in `APISpec.scala`'s `???` placeholder and replaced
`BarcodeRegressionSpec`'s dead `"Alpha Miniball"` case with `"Alpha DQP"` -- both pre-existing, already-
documented issues from the alpha-complex work (see `WORKLOG-alpha-complex.md`/`WORKLOG-naive-homology.md`),
now fixed. Those two worklogs are left as-is (point-in-time historical records, not living docs -- see
`.claude/NOTES-for-guides.md`'s own framing) rather than retroactively edited to say "fixed."

Documentation files (`CLAUDE.md`, this file, and the other `WORKLOG-*.md`s) remain uncommitted as of this
update -- consistent with this project's established pattern of the project lead reviewing and committing
personally rather than delegating that to Claude.

**Next stated task, for whoever picks this up**: a "plain Vietoris-Rips simplex stream generator" -- named
by the project lead at the end of this session with no further detail yet. Worth clarifying scope against
the existing VR stream implementations (`EnumeratingCofaceSimplexStream`, `RipserStream`/`RipserStreamBase`,
`InorderCofaceSimplexStream`, `RipserCofaceSimplexStream`) before assuming what "plain" means -- possibly
related to this file's own deferred `SparseMetricSpace`-based candidate pruning item, possibly an
independent piece. Don't guess; ask.
