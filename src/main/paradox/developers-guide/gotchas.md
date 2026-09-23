# Hard-won invariants you must not break

These are the load-bearing, non-obvious rules this codebase depends on. Every one of them was found by a
real bug, not by reading a spec — which is itself the pattern worth internalizing (see
[Process: validate empirically, not by inspection](#process-validate-empirically-not-by-inspection) at the
bottom). If you're extending the persistence engines, complex-construction streams, or anything
Ripser-flavored, read this page in full before you start.

## 1. `given`/implicit resolution happens once, at construction, not per call

A `given [CellT: Ordering, CoefficientT: Field] => (Chain[CellT, CoefficientT] is RingModule)` instance
resolves its `Ordering[CellT]` context-bound parameter **exactly once**, when the instance is first
summoned — every method built on it permanently closes over that one resolution. Scala's implicit search is
static (compile-time, lexical), not dynamic; calling those methods later, from a scope where a *different*
`Ordering[CellT]` given is available, does not re-resolve anything.

Concretely: `Simplex[VertexT] is OrderedCell`'s own `.ordering` is a fixed lexicographic order, and
`Chain.scala`'s `given [CellT: OrderedCell] => Ordering[CellT] = oCell.ordering` fallback means any
`chainRM = summon[Chain[Simplex[VertexT], CoefficientT] is RingModule]` written *before* a stream-specific
`given Ordering[Simplex[VertexT]] = stream.filtrationOrdering` is in scope silently captures that
lexicographic fallback instead — every chain built through `⊠`/`.scale` afterward pivots on vertex label
order, not filtration order, regardless of what's nominally in scope at the call site.

**The fix pattern, everywhere it's applied**: declare `given Ordering[CellT] = stream.filtrationOrdering`
as the *first* statement inside whatever state object holds the stream, before summoning `chainRM`.

This is genuinely dangerous rather than a style nit because it compiles cleanly either way and only
manifests as a wrong *answer* on inputs where lexicographic and filtration order actually disagree — most
hand-built fixtures don't exercise that unless someone deliberately constructs one (a fixture with vertex 1
born early and vertex 9 born late is the minimal discriminator). **Not every class-scope `chainRM` instance
is a live bug**: whether it matters depends on whether anything downstream trusts that instance's output
directly for pivot identity, or whether every consumer re-resolves ordering itself (e.g. via
`Chain.reduceByUntil`, a `def` that resolves fresh at each call site). This needs a case-by-case,
empirically-verified audit every time, not a blanket rule.

## 2. A stream's iteration order and its pivot order must be the *same* total order

Boundary-matrix reduction requires columns (processing order) and rows (pivot order) to be indexed by one
shared total order. Concretely: a stream's `.iterator`/`iterateDimension` order and its `filtrationOrdering`
(used to key `Chain.reduceBy`'s map) must be *the same ordering*, one the consistent `.reverse` of the
other — not merely "each independently a valid total order." Two orderings that are each individually
consistent but disagree on which of two *tied* cells comes first corrupt reduction in a way that's easy to
miss on random test input, because Vietoris-Rips complexes have *structural* ties (every simplex of
dimension ≥ 2 ties with its own longest edge) while random fixtures often don't — see
@ref:[Degeneracies](degeneracies.md).

**Symptom**: `IllegalStateException: reduction pivot ... was not a recorded open class`. This means a cell
got selected as a pivot the algorithm's own invariants say should have been impossible — check whether
iteration order and pivot order for the stream in play are provably the same ordering before looking in the
reduction code itself.

`.reverse` on the *same* `Ordering` object is safe; building a *second*, independently-written "reversed"
comparator is not, even when it looks equivalent on paper — a hand-written second comparator's tie-break
direction doesn't automatically stay consistent with a separately-reversed primary key, and this has broken
more than one stream in this codebase's history for exactly that reason.

## 3. Colex vs. lex tie-breaks are not interchangeable in Ripser-flavored code

The generic default tie-breaks on plain lexicographic vertex-set order. `EnumeratingCofaceSimplexStream`'s
and `RipserCohomologyContext`'s own orderings deliberately use **colexicographic** order instead, via
`SimplexIndexing`'s own combinatorial-number-system index — the exact tie-break Ripser's Definition
3.2/Proposition 3.9 (apparent pairs) are stated in terms of. Don't "simplify" a colex ordering to plain lex
in code that touches `SimplexIndexing`/the Ripser engines; they need to agree with each other, not just each
independently be a valid tie-break.

## 4. `Chain.reduceBy`/`reduceByUntil`, never hand-rolled `Chain` arithmetic, inside a reduction loop

`Chain`'s `+`/`-`/`⊠` operators only lazily collapse the *head* of the underlying `PriorityQueue`, so a
hand-rolled fold that repeatedly subtracts terms builds an ever-growing backlog of uncollapsed duplicate
entries — confirmed directly by a first draft that hung on an 8-12 point complex that should take
milliseconds. `Chain.reduceBy`/`reduceByUntil` go through a `SortedMap` that collapses duplicates on every
insertion. Reserve raw chain arithmetic for small, one-shot combinations (building a V-column fold), never
for anything that accumulates over many reduction steps.

## 5. Combinatorial helpers over the full point set don't know about `maxDimension` truncation

`SimplexIndexing.cofacetIterator`/`facetIterator` operate purely combinatorially over the complete n-point
abstract simplex — no concept of any per-engine dimension cap. `RipserCohomologyContext.coboundaryOf`
truncates explicitly instead, which is what makes top-dimension simplices come out essential. Any new code
built directly on `SimplexIndexing`'s iterators inherits none of that truncation and needs its own manual
guard at the call site — `SimplexIndexing` itself has no way to know what any particular caller's cap is.
The symptom (wrong finite bars where essential ones were expected) looks like a reduction bug, not an
off-by-scope error two layers away, so it's easy to misdiagnose.

## 6. `iterateDimension`'s domain must be contiguous from 0

`StratifiedCellStream.iterator`'s default implementation is
`Iterator.from(0).takeWhile(iterateDimension.isDefinedAt).flatMap(iterateDimension)` — it stops at the
first dimension the stream doesn't define, and never asks about anything past it. A stream whose domain has
a *gap* (defined at 0 and 2 but not 1) silently truncates at the gap rather than skipping past it. Every
concrete stream in this codebase satisfies this for a structural reason, not by convention alone: a
simplicial or cubical complex can't have a `d`-cell without its `(d-1)`-dimensional faces, so "no cells at
`d`" implies "no cells beyond `d`" too. A hand-built or generated stream that doesn't have this structural
guarantee needs to enforce contiguity itself — an unbounded `case d => ...` catch-all with no upper bound is
also a hazard here (`isDefinedAt` always `true`, which defeats the "stops at the first gap" logic entirely
and can loop until `Int` wraps around).

## 7. Opaque-type extension methods belong in the type's own companion object

An `extension` whose *receiver* is an opaque type (`Simplex`, `Cube`) should live inside that type's own
companion object (`object Simplex`/`object Cube`), not as a top-level `extension` clause — extension-method
resolution checks a receiver type's companion object by nominal type, so two unrelated opaque types can
safely reuse the same method name this way (`Simplex.underlying`/`Cube.underlying` never collide). Two
hazards to know before leaning on this: opaque-type transparency is scoped to the *whole file* the
`opaque type` is declared in, so code in that same file calling the type's own extensions by dot-syntax can
resolve to a same-named member of the underlying representation type instead of the intended extension —
keep an opaque type's `OrderedCell`/similar instances in their own file when this matters. And a
companion-object extension can still lose to a same-named stdlib extension reachable via a wildcard import
at some call site (`min`/`max` deliberately stay top-level extensions on `Simplex` for exactly this reason,
to avoid losing to `scala.math.Ordering.Implicits`).

## Process: validate empirically, not by inspection

Every substantive design decision in this codebase's persistence-engine work (pivot orientation, whether
clearing is optional, whether an ordering fix is complete, whether an optimization is sound) has gone
through at least one empirical discriminating test before being trusted — never a plausibility argument
alone, even a careful one. "This class-scope `chainRM` should be inert because X" has been both correct and
wrong for structurally similar-looking classes in this codebase; only a discriminating test told the two
apart.

The pattern to carry into new work: **when a change to reduction/ordering logic seems obviously sound by
inspection, build the cheap discriminating test anyway before implementing it for real.** A good
discriminating fixture deliberately puts filtration order and some other natural order (vertex label,
insertion order) in conflict — a fixture where they happen to agree will not catch this class of bug.
