# Hard-won invariants you must not break

These are the load-bearing, non-obvious rules this codebase depends on. Every one of them was found by a
real bug, not by reading a spec — which is itself the pattern worth internalizing (see
[Process: validate empirically, not by inspection](#process-validate-empirically-not-by-inspection) at the
bottom of this page). If you're extending the persistence engines, complex-construction streams, or
anything Ripser-flavored, read this page in full before you start, and again before you trust your own
"this should obviously be fine" reasoning about a change.

## 1. `given`/implicit resolution happens once, at construction, not per call

A `given [CellT: Ordering, CoefficientT: Field] => (Chain[CellT, CoefficientT] is RingModule)` instance
resolves its `Ordering[CellT]` context-bound parameter **exactly once**, when the instance is first
summoned — and every method on that instance (`plus`, `scale`, `negate`, and the `+`/`-`/`⊠` operators
built on them) permanently closes over that one resolution. Calling those methods later, from a scope where
a *different* `Ordering[CellT]` given is available, does **not** re-resolve anything — Scala's implicit
search is static (compile-time, lexical), not dynamic.

Concretely: `Simplex[VertexT] is OrderedCell`'s own `.ordering` is a fixed lexicographic order on the
vertex set, and `Chain.scala`'s `given [CellT: OrderedCell] => Ordering[CellT] = oCell.ordering` fallback
means any `chainRM = summon[Chain[Simplex[VertexT], CoefficientT] is RingModule]` written *before* a
stream-specific `given Ordering[Simplex[VertexT]] = stream.filtrationOrdering` is in scope will silently
capture that lexicographic fallback instead — and every chain built through `⊠`/`.scale` afterward pivots
on vertex label order, not filtration order, regardless of what ordering is nominally "in scope" at the
call site.

**The fix pattern, everywhere it's applied correctly**: declare `given Ordering[CellT] =
stream.filtrationOrdering` as the *first* statement inside whatever state object holds the stream, before
summoning `chainRM`. See `CellularHomologyContext.HomologyState` (`Homology.scala:49`) for the canonical
example.

**Why this is genuinely dangerous, not just a style nit**: it compiles cleanly either way, produces a
runtime object of the correct type either way, and only manifests as wrong *answers* on inputs where
lexicographic vertex order and filtration order actually disagree — which most hand-built test fixtures
don't exercise unless someone deliberately constructs one. `HomologyFixtures.elderRuleCells` in the test
suite was built specifically to discriminate the two conventions (vertex 1 born at t=0, vertex 9 born at
t=10, so a lex-order bug and a filtration-order-correct implementation disagree about which component
dies). A test suite that never builds such a fixture can stay green for a long time on top of this bug.

**This is not a hazard you internalize once and then stop guarding against.** It has recurred in scratch
code written specifically to measure something *about* this bug — a standalone helper summoned
`Chain[...] is RingModule` without first bringing the relevant stream's ordering into scope, silently fell
back to the lexicographic default, and produced a wrong dimension-0 death with no apparent-pair activity
anywhere near it to explain the discrepancy. Every new `summon[... is RingModule]` call, anywhere, needs
the ordering-in-scope check applied freshly — treat it as a code-review checklist item, not a thing you can
rely on having internalized.

**Not every class-scope `chainRM` instance is actually a live bug** — see
@ref:[Persistence engines](persistence-engines.md) for the audit of all three engines that use this pattern:
one was broken and fixed (`CellularHomologyContext`), one is present but empirically confirmed inert
(`PersistenceInChunksContext` — every pivot-relevant reduction re-resolves ordering itself via
`Chain.reduceByUntil`), and one is present but genuinely irrelevant (`TDAContext`'s own class-scope
`chainIsRingModule`, exported purely for user-facing chain-arithmetic convenience, never consumed by any
engine's own reduction path). The general lesson: **this pattern needs a case-by-case audit** — "is the
class-scope instance's output ever trusted directly for pivot identity, or does every consumer re-resolve
ordering itself" — not a blanket rule, and "should be inert" needs an empirical discriminating test, not
just a read-through, every time.

## 2. A stream's iteration order and its pivot order must be the *same* total order

Boundary-matrix reduction (Algorithm 1, and its coboundary dual) requires columns (processing order) and
rows (pivot order) to be indexed by one shared total order. In this codebase that means: a `SimplexStream`'s
`.iterator`/`iterateDimension` order and its `.filtrationOrdering` (used to key `Chain.reduceBy`'s
`SortedMap`) must be *the same ordering*, one the consistent `.reverse` of the other — not merely "each
independently a valid total order." Two orderings that are each individually consistent but disagree on
which of two *tied* cells comes first will corrupt reduction in a way that's easy to miss, because most
random test inputs don't have exact filtration-value ties. Vietoris-Rips complexes do, structurally — see
@ref:[Degeneracies that look like bugs](degeneracies.md).

Symptom to watch for: `IllegalStateException: reduction pivot ... was not a recorded open class`. That
message means a cell got selected as a pivot that the algorithm's own invariants say should have been
impossible — which almost always traces back to two orderings disagreeing on a tie, not a logic bug in the
reduction loop itself. When you see it, check whether iteration order and pivot order for the stream in
play are provably the same ordering *before* you go looking in the reduction code.

`.reverse` on the *same* `Ordering` object is safe for this; building a *second*, independently-written
"reversed" comparator is not, even if it looks equivalent on paper — this has broken this codebase twice
(`ExplicitStreamBuilder`'s interaction with `FilteredSimplexOrdering`, and an early attempt to fix
`EnumeratingCofaceSimplexStream` the same way) because a hand-written second comparator's tie-break
direction doesn't automatically stay consistent with a separately-reversed primary key.

## 3. Colex vs. lex tie-breaks are not interchangeable once Ripser-flavored code is involved

`FilteredSimplexOrdering` (the generic, trait-level default) tie-breaks on plain lexicographic vertex-set
order. `EnumeratingCofaceSimplexStream.filtrationOrdering` and `RipserCohomologyContext.cohomologyOrdering`
both deliberately use **colexicographic** order instead, via `SimplexIndexing`'s own combinatorial-number-
system index — because that's the exact tie-break Ripser's Definition 3.2/Proposition 3.9 (apparent pairs)
are stated in terms of. Don't casually "simplify" a colex ordering to the generic lex one in code that
touches the Ripser-derived machinery (`SimplexIndexing`, `RipserCohomologyContext`,
`RipserStreamBase`/`RipserStreamSparse`'s zero-persistence checks); they need to agree with each other, not
just each independently be "a valid tie-break."

## 4. `Chain.reduceBy`/`reduceByUntil`, never hand-rolled `Chain` arithmetic, inside a reduction loop

`Chain`'s `+`/`-`/`⊠` operators are correct but not efficient for iterative reduction: they only lazily
collapse the *head* of the underlying `PriorityQueue`, so a hand-rolled fold that repeatedly subtracts terms
builds an ever-growing backlog of uncollapsed duplicate entries. Confirmed directly: a first draft of
`CellularHomologyContext.advanceOne` written this way hung/burned CPU for minutes on an 8-12 point VR
complex that should take milliseconds. `Chain.reduceBy`/`reduceByUntil` go through a `SortedMap` that
collapses duplicates on every insertion — always use these for actual reduction, and reserve raw chain
arithmetic (`+`/`-`/`⊠`) for small, one-shot combinations like building a V-column fold, not for anything
that accumulates over many reduction steps.

## 5. Combinatorial helpers over the full point set don't know about `maxDimension` truncation

`SimplexIndexing.cofacetIterator`/`facetIterator` operate purely combinatorially over the complete n-point
abstract simplex — they have no concept of any per-engine dimension cap. `RipserCohomologyContext
.coboundaryOf`, by contrast, explicitly truncates (`Chain.empty` whenever `sigma.dim + 1 > maxDimension`),
which is what makes top-dimension simplices come out essential. Any new code built directly on
`SimplexIndexing`'s iterators rather than going through `coboundaryOf` inherits none of that truncation —
concretely, a from-scratch Definition-3.2 apparent-pair check built directly on `cofacetIterator` "found"
apparent cofacets for top-dimension simplices in a truncated complex that don't actually exist there,
wrongly demoting simplices that must stay essential. The fix is a manual guard (`sigma.dim < maxDimension`)
at the call site — `SimplexIndexing` itself has no way to know what any particular caller's cap is, so this
isn't something to fix once at the source; every future direct consumer of these iterators needs its own
guard. Worth watching for specifically because the symptom (wrong finite bars where essential ones were
expected) looks like a reduction bug, not an off-by-scope error in helper code two layers away.

## Process: validate empirically, not by inspection

Every substantive design decision in this codebase's persistence-engine work (pivot orientation, whether
clearing is optional, whether an ordering fix is complete, whether an optimization is sound) has gone
through at least one empirical discriminating test before being trusted — never a plausibility argument
alone, even when the plausibility argument came from a careful re-derivation. Concretely: reasoning that
"the class-scope `chainRM` should be inert here because X" was correct for `PersistenceInChunksContext`,
but the *same style* of reasoning about a "full apparent-pairs skip" in `RipserCohomologyContext` was
contradicted by a two-minute empirical check on random input (see
@ref:[Persistence engines](persistence-engines.md)).

The pattern to carry into new work: **when a change to reduction/ordering logic seems obviously sound by
inspection, build the cheap discriminating test anyway before implementing it for real.** This codebase's
history is that "obviously sound" has been wrong at roughly the same rate as "seems fine, ship it." A good
discriminating fixture deliberately puts filtration order and some other natural order (vertex label,
insertion order) in conflict — a fixture where they happen to agree will not catch this class of bug (see
`HomologyFixtures.elderRuleCells`, the tied-square fixture used to catch invariant #2, and the
three-point-line calibration example used to catch invariant #3, all in `../../../../.claude/WORKLOG-naive-homology.md`/
`../../../../.claude/WORKLOG-cohomology.md` if you want to see the actual repros).
