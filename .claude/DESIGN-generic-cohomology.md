# Generic persistent cohomology across cell types (2026-09-22)

Design note, written before any code, in response to: "I think that the main cohomology presence is the
highly optimized ripser branch, right? I would like for us to have persistent cohomology across most complex
types. Can you plan out the changes needed to do that, and which optimizations would apply to the engine
without reducing genericity."

## The premise, confirmed

Yes — cohomology in this codebase is exactly `RipserCohomologyContext`/`PackedRipserCohomologyContext`
(`Homology.scala`/`PackedRipserCohomology.scala`), and both are hardcoded to `Simplex[Int]` via
`SimplexIndexing`'s combinatorial number system. There is no cohomology engine generic over `CellT:
OrderedCell` the way homology has one (`CellularHomologyContext`) — the asymmetry is real, not imagined.
Concretely, `Cube`, `FiniteSimplicialSet` generators, and even `Simplex[Int]` complexes that aren't
Vietoris-Rips (Cech, Alpha) have **no cohomology option at all today**, even though Cech and Alpha already
use `Simplex[Int]` as their cell type — CLAUDE.md is explicit that neither New-VR's pruning nor packed
Ripser's `insertionDiameter`/apparent-pairs carry over to Cech (not a flag complex), and the same argument
applies to Alpha (circumradius, not max-pairwise-distance). Sharing a cell type is not the same as being able
to reuse the VR-specific engine.

## The key mathematical insight this plan turns on

The coboundary matrix persistent cohomology reduces (Bauer's algorithm, arXiv:1908.02518) is the **transpose**
of the ordinary boundary matrix, same coefficients: if `tau.boundary` contains `(sigma, c)`, then `sigma`'s
coboundary contains `(tau, c)`. `RipserCohomologyContext.coboundaryOf`'s own doc calls coboundary "extrinsic —
it depends on which higher-dimensional simplices exist in this (possibly truncated) complex," which is exactly
why VR needs the elaborate `SimplexIndexing`/`insertionDiameter`/`sparseCofacets` apparatus: the full flag
complex is too large to materialize, so cofacets have to be generated combinatorially, on the fly, without ever
holding "the whole complex" at once.

**That problem doesn't exist for Cube, `FiniteSimplicialSet`, Cech, or Alpha.** Every stream over these types
already gets fully materialized before homology runs today — `CellularHomologyContext.HomologyState.
CellIterator` does `stream.iterator.toVector.sorted(...)`, `CellularPersistenceInChunksContext.HomologyState.
allCells` does the dimension-bucketed equivalent. Once the whole complex is already in memory, "coboundary of
sigma" is not a search problem at all: invert every materialized cell's own (already-generic) `.boundary`
call once, and you have the transpose for free, with **zero cell-type-specific code** — `Cell`/`OrderedCell`
already provides everything needed.

This is the whole plan in one sentence: **build `CellularCohomologyContext[CellT: OrderedCell, CoefficientT:
Field, FiltrationT: Ordering]`, mirroring `CellularHomologyContext`'s genericity exactly, running Bauer's
algorithm over a coboundary relation derived by inverting `boundary`** — not a new per-cell-type coboundary
formula, and not a per-cell
`coboundary` typeclass method (see "`Cocell`/`OrderedCocell`, removed" below for why that shape is wrong and
what was done about it).

## Advisor review, incorporated below

Five corrections came back from `advisor()` on the first draft of this plan; all five are load-bearing in
what follows, not optional polish. (Point 4's own "reframe, don't drop" instinct was itself superseded in
review — see the note after it.)

1. Consume `CellStream[CellT, FiltrationT]`, not `StratifiedCellStream` — materializing everything anyway
   means `stream.iterator.toVector.groupBy(_.dim)` gets dimension bands without `iterateDimension`'s
   contiguous-domain contract, making this a drop-in for *every* stream the naive engine accepts (including
   `ExplicitStream`/`ExplicitCubicalStream`), not just stratified ones. No `maxDim` parameter on the engine at
   all — see "No `maxDim` parameter" below.
2. Do not build `cohomologyOrdering` as `stream.filtrationOrdering.reverse` — `.reverse` flips the dimension
   and within-dimension tie-break too, not just the filtration-value key (the exact, previously-reverted bug
   documented on `CellularHomologyContext.processingOrder`). Build it explicitly, the way
   `RipserCohomologyContext.cohomologyOrdering` is built (ascending fv, then a fixed tie-break) — see "The
   ordering" below. Also: `Chain.scala:28`'s ambient `given [CellT: OrderedCell] => Ordering[CellT] =
   oCell.ordering` (filtration-blind) will silently win at any `Chain.from`/`Chain.apply` site where
   `cohomologyOrdering` isn't explicitly in scope — the exact `chainRM`-summoned-too-early bug class already
   shipped once in `CellularHomologyContext`'s history. Scope `cohomologyOrdering` locally, not at class scope.
3. Build the transpose one dimension-block (d → d+1) at a time and discard it once dimension d's pass is done
   — `basis`/`generators` are already per-dimension-reset in both Ripser engines, so this is a natural fit, and
   it bounds peak memory to the largest single block rather than the whole matrix, honoring the project's
   stated memory-frugality goal.
4. Reframe what apparent pairs buys here: with a materialized transpose, `coboundaryOf` is already a map
   lookup, so AP isn't avoiding enumeration the way it does in the packed engine. What it still buys is
   skipping a `basis` write (real memory, on a large fraction of cells) and one `Chain.reduceBy` call — a
   smaller, different benefit that needs its own measurement, not an inferred one.

   **Superseded on review, not merely reframed: apparent pairs is dropped from this plan entirely.** The
   project lead's own read is sharper than "measure a smaller benefit" — once coboundary must already be
   fully materialized to exist at all (the precondition for this whole engine), there is no enumeration left
   to avoid, which was apparent pairs' entire reason for existing. What's left over (skip one `basis` write,
   skip one call into `Chain.reduceBy` that would immediately no-op on a leading term with no `basis` entry
   anyway) is noise, arguably not even a net win once `zeroPivotCofacet`/`zeroPivotFacet`/`zeroApparentCofacet`/
   `zeroApparentFacet`'s own scan cost is counted against it — not worth porting four methods' worth of
   machinery for. See "What does NOT carry over" below, where this now lives alongside `insertionDiameter`/
   `sparseCofacets` as a VR-specific optimization that has no problem left to solve once the complex is
   materialized.

   Clearing stays a single `mutable.Set[CellT]` accumulated across all dimensions (`RipserCohomologyContext`'s
   style) — the packed engine's per-dimension clearing rotation exists only because a bare `Long` index
   collides across different simplex sizes, which doesn't apply to `CellT` (proper per-dimension-safe
   `equals`/`hashCode`).
5. The genuinely new deliverable is the **cocycle representative**, not the barcode — over a field, the
   cohomology barcode is identical to the homology barcode (this is the whole reason Ripser computes
   cohomology at all: same answer, cheaper algorithm), so a bars-only engine would be redundant with
   `CellularHomologyContext`, which already covers every one of these cell types. Expose a generic
   `coboundaryOfChain` and verify `coboundaryOfChain(rep).isZero()` per bar; use barcode-value-equality against
   the already-trusted `CellularHomologyContext` as the correctness oracle for Cube/`FiniteSimplicialSet`/
   Cech/Alpha, none of which have ever had a cohomology cross-check before.

## What the new engine looks like

`CellularCohomologyContext[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]` — own file,
`homology/Cohomology.scala` (see "Where this lives" below) — mirroring `CellularHomologyContext`'s shape and
genericity exactly, including `FiltrationT`:

```scala
class CellularCohomologyContext[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]:
  def persistentCohomology(
    stream: => CellStream[CellT, FiltrationT]
  ): List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]]
```

**Resolved: fully generic `FiltrationT`, not `Double`-only** — matches `CellularHomologyContext`'s own
genericity rather than `RipserCohomologyContext`'s narrower `Double` choice, on the project lead's own call.
Only `FiltrationT: Ordering` needs declaring explicitly on the class itself; `Filterable` (`smallest`/`largest`,
needed for essential-bar endpoints and the missing-filtration-value fallback below) comes bundled via the
`stream: CellStream[CellT, FiltrationT]` parameter's own `Filtration[CellT, FiltrationT]` supertype, exactly
the way `CellularHomologyContext.HomologyState` accesses `stream.smallest`/`stream.largest` rather than
summoning `Filterable[FiltrationT]` as a second ambient given — no new typeclass machinery needed, just
reusing what `CellStream`'s own contract already guarantees.

### Materialization

`val cellsByDim: Map[Int, Vector[CellT]] = stream.iterator.toVector.groupBy(_.dim)` — one pass, no
`iterateDimension` dependency (advisor point 1). Top dimension is `cellsByDim.keys.max` — derived, not
supplied.

### No `maxDim` parameter

This deletes the entire "`maxDim` means top *reported* degree, not top *built* degree" footgun class rather
than reimplementing it a fourth time (homology-chunks and both Ripser-cohomology engines each had to fix this
exact bug once already — `.claude/WORKLOG-maxdim-semantics-fix.md`). The engine simply computes cohomology up
to whatever top dimension the materialized stream actually contains. A caller wanting only `H_0..H_k` wraps
the *input* stream first — `LimitedCofaceSimplexStream(stream, k + 1)`, the exact mechanism
`RipserCohomologySpec`'s own oracle and the MATLAB facade's `engine=naive` path already use for this — so that
real `(k+1)`-dimensional cells exist to correctly resolve whether a `k`-born class is finite or essential, and
then drops any `dim == k + 1` bars from the *returned list* itself, as post-processing. No dimension-cap
concept needs to live inside this engine at all.

### The ordering

Built explicitly, not via `.reverse`:

```scala
val fv: PartialFunction[CellT, FiltrationT] = stream.filtrationValue
val cohomologyOrdering: Ordering[CellT] =
  Ordering.by[CellT, FiltrationT](c => fv.applyOrElse(c, _ => stream.smallest)).orElse(stream.filtrationOrdering)
```

Ascending fv (the direction `RipserCohomologyContext.cohomologyOrdering`'s own doc establishes cohomology
needs — opposite of homology's reversed convention), falling through to the stream's own
`filtrationOrdering` **unreversed** as tie-break only when fv is genuinely tied. This is safe specifically
because — and this must be checked, not assumed, the same way `CellularPersistenceInChunksContext`'s
class-scope `chainRM` was checked against `Chain.reduceByUntil`'s per-call `Ordering` resolution before being
trusted (CLAUDE.md, "Persistent homology" item 1) — **this algorithm never compares cells of different
dimensions under `cohomologyOrdering`**: `simplicesAtD`'s own sort is within one dimension band, and every
`Chain.reduceBy` call's working chain (`coboundaryOf(sigma)`, for `sigma` of dimension d) contains only
dimension-`(d+1)` terms. `cleared` is set membership, not an ordering comparison, so it's unaffected either
way. Unlike `CellularHomologyContext.processingOrder`, which genuinely needs cross-dimension comparisons and
therefore needs the dimension-tie-break-safe construction documented there, this engine's per-dimension-reset
structure (matching both existing cohomology engines) means the cross-dimension hazard `.reverse` creates
simply never gets exercised — but this is an invariant of the algorithm's *structure*, not of the specific
`Ordering` expression, so it needs to be re-verified for this class, not inherited by assumption.

`cohomologyOrdering` must be brought into scope **inside** the per-dimension-block processing (the way
`CellularHomologyContext.HomologyState` scopes `given Ordering[CellT] = stream.filtrationOrdering` locally,
not at outer class scope) — every `Chain.from`/`Chain.apply`/`summon[Chain[CellT, CoefficientT] is RingModule]`
call site needs it explicitly in scope, or `Chain.scala:28`'s ambient, filtration-blind `given Ordering[CellT]
= oCell.ordering` silently wins instead. This is exactly the `chainRM`-summoned-too-early bug
`CellularHomologyContext` shipped once (CLAUDE.md, item 1's opening paragraph) — the fix pattern is known, just
needs to be applied here too, not re-discovered.

### The per-dimension loop

For `d` from `0` to `cellsByDim.keys.max - 1` (there's nothing to reduce past the top dimension: no cell has a
nonempty coboundary there):

1. **Build this dimension's coboundary block** (advisor point 3): iterate `cellsByDim(d + 1)`, call
   `.boundary[CoefficientT]` on each (d+1)-cell, and for every `(faceCell, coeff)` term append
   `(coface, coeff)` into `coboundaryMap.getOrElseUpdate(faceCell, ArrayBuffer.empty)`. This *is* the
   coboundary matrix, transpose-of-boundary, same coefficients, same sign convention — no cell-type-specific
   sign math needed anywhere, unlike `RipserCohomologyContext.coboundaryOf`'s own hand-derived
   "`(-1)^(vertices smaller than the inserted vertex)`" formula, which was VR-specific and is now unnecessary.
2. Sort `cellsByDim(d)` by `cohomologyOrdering.reverse` (**youngest first** — the same "Algorithm 1 processes
   columns in increasing [matrix] order, which under the reversed coboundary matrix means decreasing real
   filtration order" fact `RipserCohomologyContext.persistentCohomology`'s own comment documents; a `.reverse`
   here is fine, since it's applied to already-single-dimension data, the same non-hazard as sorting
   `simplicesAtD` in the existing engine).
3. For each `sigma` in that order, skip if `cleared.contains(sigma)`; otherwise:
   `z = Chain.from(coboundaryMap.getOrElse(sigma, Seq.empty))`; `Chain.reduceBy(z, basis, Chain.empty)` — no
   `fallback` needed (its default, `_ => None`, is exactly right, since there's no apparent-pairs substitution
   to wire in — see "What does NOT carry over" below). If `reduced.isZero()`, `sigma` opens an essential class;
   otherwise its leading cell is the pivot that kills it. V-column (`generators`) tracked the same way
   `RipserCohomologyContext.persistentCohomology` already does, satisfying the standing representatives
   principle for free.
4. **Discard `coboundaryMap` here** (advisor point 3) — `cellsByDim(d + 1)` itself is not discarded, it's
   already the next iteration's outer-loop input.

`cleared: mutable.Set[CellT]`, accumulated across the whole run, exactly like `RipserCohomologyContext`
(advisor point 4) — not per-dimension-rotated the way `PackedRipserCohomologyContext` has to, since that
rotation exists only to work around `DiameterIndex`'s bare-`Long`-index collision across differently-sized
simplices, a representation-specific problem `CellT` doesn't have.

### `coboundaryOfChain`, and why it matters more than the barcode

A generic linear extension of "coboundary of one cell," for verification: given a chain of dimension-d cells,
return its dimension-(d+1) coboundary. This is the method that makes the new engine's *actual* deliverable
checkable: `coboundaryOfChain(representative).isZero()` for every bar, over every `CellT` this engine now
supports — something no engine in this codebase can currently verify for Cube, `FiniteSimplicialSet`, Cech, or
Alpha, because none of them has ever had a cocycle representative to check. (This is a testing/verification
entry point, not a hot-path method — it may rebuild a local coboundary block on demand rather than assuming
`persistentCohomology`'s own per-dimension block, discarded per point 3 above, is still around.)

## Where this lives

**Resolved: own file, `homology/Cohomology.scala`** — not a fifth item folded into the already-1700-line
`Homology.scala`. On the project lead's own call, made alongside a broader observation worth recording even
though it's not this plan's job to act on: `Homology.scala` currently holds all four canonical homology
engines plus commented-out prior art, and it might be worth splitting *every* engine out into its own file
(mirroring `PackedRipserCohomology.scala`'s existing precedent) rather than only the new one. That's a real,
separate refactor of already-working code — moving `CellularHomologyContext`, `CellularPersistenceInChunksContext`,
`SimplicialHomologyByDimensionContext`, and `RipserCohomologyContext` each into their own file — genuinely
independent of shipping this new engine and not bundled into this plan. `Cohomology.scala` is named to fit
that eventual split cleanly (parallel to `Homology.scala`, not `RipserCohomology.scala`/
`CellularCohomology.scala`-style narrower naming) if and when it happens.

## What does NOT carry over, and why that's not a genericity loss

- **`insertionDiameter`'s O(d) incremental cofacet-diameter recurrence.** This exists to avoid recomputing a
  filtration value for a candidate simplex that might not even survive threshold — i.e. it solves the "don't
  materialize the whole complex" problem. The generic engine already has every filtration value it needs,
  read once off the already-materialized stream (which itself may already memoize `filtrationValue`, e.g.
  `CubicalGridStream`'s own cache) — there's no candidate-that-might-not-survive to avoid computing for.
- **`sparseCofacets`'s incremental "insert-vertex-above-max, filtered by `maxFiltrationValue`" candidate
  assembly, and the whole `SimplexIndexing`/`CofacetCursor`/`FacetCursor` combinatorial-number-system
  apparatus.** This exists to avoid materializing an exponentially-large full flag/Cech complex. Cube/
  `FiniteSimplicialSet`/Cech/Alpha streams are already bounded (grid size, generator count, an already-built
  simplicial complex) and already fully materialized by their own construction *today*, independent of this
  engine. There is no exponential-blowup problem here to solve, so this machinery has no problem to solve
  outside `Simplex[Int]`-over-a-metric-space VR — framed honestly, this is "doesn't apply," not "left on the
  table."
- **`maxFiltrationValue`-based sparse thresholding of VR/Cech itself.** Orthogonal to this engine: a caller
  wanting a genuinely truncated VR/Cech complex through the generic engine truncates at the *stream* level
  (every stream here already supports this — `LimitedCofaceSimplexStream`, `maxFiltrationValue` defaults),
  not inside the cohomology engine.
- **Apparent pairs** (Definition 3.2/Proposition 3.9), including the on-the-fly substitution mechanism
  (`Chain.reduceBy`'s `fallback` hook). Not merely a smaller win here — genuinely not generalizable as an
  *optimization*, full stop: its entire reason for existing is avoiding coboundary enumeration for cells that
  turn out to be trivially paired, and this engine has no enumeration to avoid, because it must materialize
  the coboundary relation for *every* cell up front just to have "coboundary" exist at all (the extrinsic-vs-
  intrinsic point above). What would be left after porting `zeroPivotCofacet`/`zeroPivotFacet`/
  `zeroApparentCofacet`/`zeroApparentFacet`'s own scan logic — skip one `basis` write, skip one call into an
  already-O(1)-ish `Chain.reduceBy` miss — is noise-level, plausibly a net loss once the scan cost of finding
  the pair is counted, and not worth the four extra methods of machinery. Dropped from this plan; see the
  advisor-review section above for how this superseded an earlier "reframe it smaller" instinct.

## What does carry over

- **Clearing** — unchanged, zero genericity cost, still required for correctness (Proposition 3.1's
  essential-index definition, cell-type-independent — see `WORKLOG-cohomology.md`'s "clearing is required for
  correctness" finding, which this plan inherits rather than re-derives).
- **Representative (V-column) tracking** — falls out of mirroring the existing loop structure, satisfying
  CLAUDE.md's standing "every engine needs generic `Field` + real representatives" principle automatically.

## `Cocell`/`OrderedCocell`, removed

Were defined at `Chain.scala:18-30`, never implemented anywhere in this codebase (checked directly — zero hits
beyond the trait/given declarations themselves, across `src/main`, `src/test`, and the docs, before deletion).
They modeled coboundary as *intrinsic* to a cell (`extension (self: Self) def coboundary[CoefficientT: Field]:
Seq[(Self, CoefficientT)]`), the same shape as `Cell.boundary`. That's the wrong shape for what cohomology in
this codebase actually needs: coboundary is *extrinsic* — it depends on which higher-dimensional cells
actually exist in the ambient (possibly truncated) complex, exactly as `RipserCohomologyContext.coboundaryOf`'s
own doc already states. A per-cell `coboundary` method with no complex to consult can only be correct when the
complex is always the full combinatorially-possible one — not the general case this plan targets, and not a
foundation worth keeping around even unused. On the project lead's own instruction, given this was the point
that surfaced them as architecturally wrong rather than merely dead: **removed outright**, in this same
session — `Chain.scala` (the traits and the dual `given Ordering[CocellT]`), plus every doc reference
(`CLAUDE.md`, `src/main/paradox/developers-guide/architecture.md` and `class-diagrams.md`'s mermaid diagram).
`WORKLOG-package-reorg.md`'s own mention is left untouched, per this codebase's worklog convention of not
retroactively editing point-in-time records.

## Open design questions

Both prior open questions (`FiltrationT` genericity, file placement) are resolved above. One remains:

1. **Naming** — `CellularCohomologyContext` mirrors `CellularHomologyContext`/`CellularPersistenceInChunksContext`
   directly; open to a better name if one exists, but this keeps the existing convention.

## Validation plan

Same discipline as every other engine in this codebase:

1. **Cross-validate against `RipserCohomologyContext` on `Simplex[Int]` VR complexes** — bar VALUES
   (birth/death), on `threePointLine` and random point clouds. **This item's original plan (below, struck
   through) turned out to be wrong once implemented, and is corrected here rather than silently fixed** —
   ~~bar-for-bar, including representative cocycle content, not just birth/death: a materialized-transpose
   implementation is independent enough of the combinatorial-index implementation that this can catch real
   bugs on either side~~. Checked directly, not left as an assumption, and found false on the very first
   random point cloud tried: VR dimension-0 simplices (vertices) are ALWAYS tied at `fv = 0`, and
   `CellularCohomologyContext.cohomologyOrdering`'s tie-break (falling through to `stream.filtrationOrdering`)
   resolves ties in the OPPOSITE direction from `RipserCohomologyContext.cohomologyOrdering`'s own hand-built
   tie-break ("larger combinatorial index sorts as older") — confirmed two ways: by direct inspection of the
   resulting bar/vertex pairings, and, in a later advisor-prompted follow-up pass, by directly printing each
   engine's own processing order over the same tied vertices (generic `[2,1,0]`, Ripser `[0,1,2]`), not merely
   inferred from reading the two comparators' source. This is legitimate, not a bug: which specific tied cell
   gets reported as dying at which death time is
   tie-order-dependent by design (this codebase's own `HomologyFixtures.tetrahedronBoundaryDegenerateExpected`
   documents the identical point for homology's elder rule), and the canonical-reduced-matrix argument only
   guarantees a unique answer for a *single* fixed total order — never that two independently-chosen, each
   individually valid, total orders must agree. Representative-content cross-validation against
   `RipserCohomologyContext` specifically is therefore not a sound claim on VR input and isn't attempted; see
   `CohomologySpec.scala`'s own comment (written after this was discovered) for the full account.
2. **`coboundaryOfChain(rep).isZero()` for every ESSENTIAL bar** (the only bars whose V-column is a genuine
   cocycle by construction — see `Cohomology.scala`'s own doc), every cell type — the check that makes representatives
   (this plan's actual deliverable, per advisor point 5) trustworthy rather than merely present.
3. **Barcode-value cross-validation against `CellularHomologyContext`** (already trusted, generic, and the
   established oracle for exactly this purpose elsewhere in this codebase) on Cube, `FiniteSimplicialSet`
   (reusing the non-dimension-aligned `torus` filtration fixture that already discriminates a reversed
   `filtrationOrdering` — `.claude/WORKLOG-simplicial-set-filtration.md`), Cech, and Alpha — the free, strong
   oracle advisor pointed at: none of these four have ever had a cohomology cross-check before, so this is new
   coverage, not a repeat of existing coverage.
4. **Structural invariant**: `totalBarsAccountForAllCells`-style check (`HomologyFixtures`, already used
   throughout this codebase).

## Finalization pass (in scope, required)

Per the new standing `CLAUDE.md` "Session practices" rule this plan itself prompted — a feature isn't done
when the core engine is green — shipping this engine includes, not as a deferred follow-up:

1. **`matlab.TDA4j` dispatch.** A new `engine=` value (naming TBD at implementation time — something like
   `engine=cohomology`, distinct from `engine=ripser`/`naive`/`chunks`) routing `complex=cube`/`simplicialset`/
   `cech`/`alpha` (and, for completeness, `complex=vr` too, even though `engine=ripser` stays the faster choice
   there) through `CellularCohomologyContext`. Needs `fromBars`-style wiring for representatives
   (`cycleVertices`/`cycleCoefficients`), matching the `cellVertices: (Int, CellT) => Array[Int]` pattern
   already generalized for `engine=ripser`'s packed cells — here simply `(_, cell) => cell.underlying.toArray`
   for `Simplex`, and the equivalent accessor for `Cube`/`FiniteSimplicialSet` generators.
2. **`cli.TDA4jCLI`/`TDA4jConf` dispatch.** Mirrors (1) 1:1 by this codebase's own stated design (`cli` "does
   no validation of its own" — every flag is a direct mirror of a `matlab.TDA4j` option key) — the new
   `engine=` value just needs to be a legal value for the existing `--engine` flag, no new flag needed unless
   (1) introduces one.
3. **`src/main/paradox/developers-guide/persistence-engines.md`** — add this engine as a fifth entry alongside
   the four canonical ones, matching their existing treatment (what it computes, its genericity, its
   representative-tracking story, cross-validation summary). **`architecture.md`/`class-diagrams.md`** — confirm
   whether the `Cell`/`OrderedCell`/`OrderedBasis` section needs a mention of this engine's coboundary-via-
   boundary-inversion technique (likely yes, since it's a genuinely new construction on top of `Cell`, not just
   a new consumer of the existing one).
4. **`src/main/paradox/user-guide/index.md`** — a user-facing mention: cohomology (and real cocycle
   representatives) now available for Cube/`FiniteSimplicialSet`/Cech/Alpha, not just Vietoris-Rips.

## Explicitly out of scope for this plan

- A chunked/parallelizable cohomology engine mirroring `CellularPersistenceInChunksContext` (local/global
  clear-and-compress) — a real, natural follow-up once the naive generic cohomology engine above is shipped
  and trusted, but its own local/global-split complexity (see `.claude/WORKLOG-chunks-pairing-bug.md` for how
  subtle that split's own correctness can get) warrants a dedicated pass, not folding into this one.
- Emergent pairs (Ripser's Definition 3.11) — not implemented in either existing cohomology engine either,
  for reasons already on record (`CLAUDE.md`'s "Emergent pairs... deliberately NOT implemented" note); nothing
  about genericizing changes that calculus. Apparent pairs itself is not "out of scope" so much as actively
  excluded — see "What does NOT carry over" above.
- Splitting every existing `Homology.scala` engine into its own file — a real idea, recorded under "Where
  this lives" above, but a separate, larger refactor of already-working code, not bundled into this plan.
