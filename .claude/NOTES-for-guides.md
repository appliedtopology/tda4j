# Notes toward a developer's guide and a user's guide

Collected while implementing phase 2 (persistent cohomology, clear&compress audit, apparent pairs) on top
of phase 1 (naive homology). Not a draft of either guide — a foundation of observations, pitfalls, and
codebase facts that would otherwise need to be re-derived from scratch or re-discovered by a future reader
the hard way. Organized by what kind of guide each note feeds.

## For the developer's guide: invariants you must not break

These are the load-bearing, non-obvious rules this codebase depends on. Every one of them was found by a
bug, not by reading a spec — which is itself the pattern worth stating explicitly in the guide (see below).

### 1. `given`/implicit resolution happens once, at construction, not per call

A `given [CellT: Ordering, CoefficientT: Field] => (Chain[CellT, CoefficientT] is RingModule)` instance
resolves its `Ordering[CellT]` context-bound parameter **exactly once**, when the instance is first
summoned — and every method on that instance (`plus`, `scale`, `negate`, and the `+`/`-`/`⊠` operators
built on them) permanently closes over that one resolution. Calling those methods later, from a scope where
a *different* `Ordering[CellT]` given is available, does **not** re-resolve anything — Scala's implicit
search is static (compile-time, lexical), not dynamic.

Concretely, in this codebase: `Simplex[VertexT] is OrderedCell`'s own `.ordering` is a fixed lexicographic
order on the vertex set (`Chain.scala`'s `given [CellT: OrderedCell] => Ordering[CellT] = oCell.ordering`
fallback). Any `chainRM = summon[Chain[Simplex[VertexT], CoefficientT] is RingModule]` written *before* a
stream-specific `given Ordering[Simplex[VertexT]] = stream.filtrationOrdering` is in scope will silently
capture that lexicographic fallback instead — and every chain built through `⊠`/`.scale` afterward pivots
on vertex label order, not filtration order, regardless of what ordering is nominally "in scope" at the
call site.

**The fix pattern, everywhere it's been applied correctly**: declare `given Ordering[CellT] =
stream.filtrationOrdering` as the *first* statement inside the state object that holds the stream, before
summoning `chainRM`. See `CellularHomologyContext.HomologyState` (`Homology.scala:49`) for the canonical
example.

**Why this is genuinely dangerous, not just a style nit**: it compiles cleanly either way, produces a
runtime object of the correct type either way, and only manifests as wrong *answers* on inputs where
lexicographic vertex order and filtration order actually disagree — which most hand-built test fixtures
don't exercise unless someone deliberately constructs one (see `HomologyFixtures.elderRuleCells`, built
specifically to discriminate the two conventions: vertex 1 born at t=0, vertex 9 born at t=10, so a
lex-order bug and a filtration-order-correct implementation disagree about which component dies). A test
suite that never builds such a fixture can stay green for a long time on top of this bug.

**This is not a hazard you internalize once and then stop guarding against.** Minutes after writing the
paragraph above, in scratch code built to measure something unrelated (an apparent-pairs feasibility
check, see the worklog), the exact same mistake happened again: a standalone helper function summoned
`Chain[...] is RingModule` without first bringing the relevant stream's ordering into scope, silently fell
back to the lexicographic default, and produced a wrong dimension-0 death in a sanity check with no
apparent-pair activity anywhere near it to explain the discrepancy. It was caught only because the sanity
check happened to be small enough to hand-verify. The lesson for the guide is specifically this: knowing
the rule does not prevent writing the bug again in new code, including code written by someone who just
finished documenting the rule. Every new `summon[... is RingModule]` call, anywhere, in any throwaway
script or permanent module, needs the ordering-in-scope check applied freshly — treat it as a checklist
item for code review, not a thing you can rely on having learned.

**Audit needed, but genuinely inert where checked**: not every `chainRM`-at-wrong-scope instance is
actually a live bug. `PersistenceInChunksContext` summons `chainRM` at class scope (before any stream
exists) but is *confirmed correct* by direct testing, because its actual reduction path goes through
`Chain.reduceByUntil`, a `def` with its own `[CellT: Ordering]` context bound resolved fresh at each call
site — the stale class-scope `chainRM`'s `⊠`/`-` operators are only used to build intermediate values that
get fed straight back into a fresh `reduceByUntil` call, which re-establishes correct pivot order before
anything trusts a `.leadingCell`. This is subtle enough that "should be inert" needs an empirical
discriminating test, not a read-through — trust but verify, every time this pattern shows up, even when
the reasoning "seems" sound. `TDAContext` (`package.scala`) has the same class-scope pattern but is a
different case again: its `chainIsRingModule` is exported purely for user-facing chain-arithmetic
convenience, never consumed by any engine's own reduction path, so a stale ordering there is a
non-issue for correctness (though it could confuse a user manually combining chains outside the engine).
**The general lesson for the guide**: this pattern needs a case-by-case audit — "is the class-scope
`chainRM`'s output ever trusted directly for pivot identity, or does every consumer re-resolve ordering
itself" — not a blanket rule. Three instances of the pattern in this codebase, three different outcomes
(broken and fixed, present-but-inert, present-but-irrelevant).

### 2. A stream's iteration order and its pivot order must be the *same* total order

Boundary-matrix reduction (Algorithm 1, and its coboundary dual) requires columns (processing order) and
rows (pivot order) to be indexed by one shared total order. In this codebase that means: a `SimplexStream`'s
`.iterator`/`iterateDimension` order and its `.filtrationOrdering` (used to key `Chain.reduceBy`'s
`SortedMap`) must be *the same ordering*, one the consistent `.reverse` of the other — not merely "each
independently a valid total order." Two orderings that are each individually consistent but disagree on
which of two *tied* cells comes first will corrupt reduction in a way that's easy to miss, because most
random test inputs don't have exact filtration-value ties. Vietoris-Rips complexes do, structurally: every
simplex of dimension ≥ 2 ties with its own longest edge by construction (a simplex's filtration value —
max pairwise distance among its vertices — is always realized by some face).

Symptom to watch for: `IllegalStateException: reduction pivot ... was not a recorded open class`. That
specific message means a cell got selected as a pivot that the algorithm's own invariants say should have
been impossible — which almost always traces back to two orderings disagreeing on a tie, not a logic bug
in the reduction loop itself. When you see it, the first thing to check is not the reduction code — it's
whether iteration order and pivot order for the stream in play are provably the same ordering.

`.reverse` on the *same* `Ordering` object is safe for this; building a *second*, independently-written
"reversed" comparator is not, even if it looks equivalent on paper — it broke twice in this codebase
(`ExplicitStreamBuilder`'s interaction with `FilteredSimplexOrdering`, and an early attempt to fix
`EnumeratingCofaceSimplexStream` the same way) because a hand-written second comparator's tie-break
direction doesn't automatically stay consistent with a separately-reversed primary key.

### 3. Colex vs. lex tie-breaks are not interchangeable once Ripser-flavored code is involved

`FilteredSimplexOrdering` (the generic, trait-level default) tie-breaks on plain lexicographic vertex-set
order. `EnumeratingCofaceSimplexStream.filtrationOrdering` and `RipserCohomologyContext.cohomologyOrdering`
both deliberately use **colexicographic** order instead, via `SimplexIndexing`'s own combinatorial-number-
system index — because that's the exact tie-break Ripser's Definition 3.2/Proposition 3.9 (apparent pairs)
are stated in terms of. Don't casually "simplify" a colex ordering to the generic lex one in code that
touches the Ripser-derived machinery (`SimplexIndexing`, `RipserCohomologyContext`, apparent pairs); they
need to agree with each other, not just each be "a valid tie-break."

### 4. `Chain.reduceBy`/`reduceByUntil`, never hand-rolled `Chain` arithmetic, inside a reduction loop

`Chain`'s `+`/`-`/`⊠` operators are correct but not efficient for iterative reduction: they only lazily
collapse the *head* of the underlying `PriorityQueue`, so a hand-rolled fold that repeatedly subtracts
terms builds an ever-growing backlog of uncollapsed duplicate entries. Confirmed directly: a first draft of
`CellularHomologyContext.advanceOne` written this way hung/burned CPU for minutes on an 8-12 point VR
complex that should take milliseconds. `Chain.reduceBy`/`reduceByUntil` go through a `SortedMap` that
collapses duplicates on every insertion — always use these for actual reduction, and reserve raw chain
arithmetic (`+`/`-`/`⊠`) for small, one-shot combinations like building a V-column fold, not for anything
that accumulates over many reduction steps.

### 5. Combinatorial helpers over the full point set don't know about `maxDimension` truncation

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
guard. Worth a callout in the guide specifically because the symptom (wrong finite bars where essential
ones were expected) looks like a reduction bug, not an off-by-scope error in helper code two layers away.

## For the developer's guide: which engines are trustworthy right now

As of this session, `Homology.scala` has four persistence engines. They are independently implemented, not
layered on a shared core — a fix or bug in one does not imply anything about the others. Status matters
enough to a new contributor that it belongs up front in a guide, not buried in a class doc:

- **`CellularHomologyContext`/`SimplicialHomologyContext`** — reference-grade. Naive single-pivot-table
  reduction, no clearing/chunking/optimization, incremental querying. This is the oracle every other engine
  gets cross-validated against. Trustworthy.
- **`PersistenceInChunksContext`** — trustworthy, audited twice now (once for the `chainRM`-at-class-scope
  pattern in phase 1's own history, once this session for the same pattern found in a sibling class).
  Confirmed correct both times by discriminating test, not by inspection alone.
- **`SimplicialHomologyByDimensionContext`** — **non-functional**. `HomologyState`'s constructor throws
  unconditionally (`NoSuchElementException`, an unguarded `barcode(0)` read on an empty map,
  `Homology.scala:427`/`490`) for any complex with at least one MST edge — i.e., almost any real input. Has
  never run end-to-end; zero test coverage anywhere in the repo. Separately, once that's fixed, it will
  also need the `given Ordering = stream.filtrationOrdering` fix from item 1 above before its output can be
  trusted — it currently has neither. Don't point a new user at this engine; don't assume "it's in the
  file, so it must work."
- **`RipserCohomologyContext`** — trustworthy for what it currently does (persistent cohomology with
  clearing, cross-validated against the naive engine on hundreds of random inputs plus hand-derived
  fixtures). Apparent pairs (a further optimization, not needed for correctness) is explicitly not landed:
  two candidate designs (identify a pivot inline via the Definition 3.2 check; remove both pair members in
  a pre-pass before the main loop) were both tried and both **confirmed unsound by direct counterexample**
  — a tau that's one simplex's apparent partner can simultaneously be a different, non-apparent simplex's
  legitimate reduction target, and neither design accounts for that. See `WORKLOG-cohomology.md`'s
  "Apparent pairs: negative result" section for the counterexamples and what's needed next (Proposition
  3.9's actual proof from the paper, not a reconstruction from the definition). The engine's shipped
  behavior is unaffected — this was all measured in throwaway scratch code, never wired into
  `persistentCohomology()`.

The pattern worth stating explicitly in the guide: **in this codebase, "compiles and has a passing test
suite" has not been a reliable signal of correctness for the persistence engines** — every ordering bug
found so far (three, across two different mechanisms) was caught by a specifically-constructed
discriminating fixture (`elderRuleCells`, the tied-square fixture, the three-point-line calibration
example), not by the existing test suite noticing on its own, because the existing tests mostly didn't
happen to exercise the specific tie or label-order mismatch that triggers the bug. A guide should tell a
new contributor: when adding a new engine or touching reduction logic, build a fixture that deliberately
puts filtration order and some other natural order (vertex label, insertion order) in conflict, and check
against it — a fixture where they happen to agree will not catch this class of bug.

## For the developer's guide: degeneracy behaviors that look like bugs but aren't

Worth a dedicated section so a new contributor doesn't "fix" correct-but-surprising behavior:

- **Alpha complexes in degenerate (cospherical) position are not Delaunay subcomplexes.** `k` cospherical
  sites sharing a Voronoi vertex contribute a `(k-1)`-simplex — a unit grid in the plane produces
  3-simplices (one per unit square), not just triangles from a triangulation. Truncating at ambient
  dimension gives the wrong homotopy type. CGAL/GUDHI users will not expect this; it's correct.
- **Zero-persistence (zero-length) bars are real output, not noise to filter.** `RipserCohomologyContext`
  emits them deliberately (e.g. an edge tied with the triangle that immediately kills it) — Definition
  3.2/Proposition 3.9's apparent pairs *are* zero-persistence pairs, and dropping them silently would be
  wrong at this stage of the pipeline, even though a downstream visualization might reasonably filter them.
- **A Vietoris-Rips complex with maxDimension ≥ 2 always has ties.** Every simplex ties in filtration value
  with its own longest edge by construction. This is not evidence of a degenerate/adversarial input; it's
  the normal case, and any reduction code path that assumes "ties are rare" will be wrong on ordinary data.

## For the developer's guide: the `advisor` workflow, as actually used this session

Worth documenting as a *process* note, since it materially changed what got shipped correctly this
session: every substantive design decision in this codebase's persistence-engine work (pivot orientation,
whether clearing is optional, whether an ordering fix is complete, whether an optimization is sound) went
through at least one empirical discriminating test before being trusted — never a plausibility argument
alone, even when the plausibility argument came from a careful re-derivation. Concretely: reasoning that
"the class-scope `chainRM` should be inert here because X" was correct in one case
(`PersistenceInChunksContext`) but the *same style* of reasoning about a "full apparent-pairs skip" was
contradicted by a two-minute empirical check on random input. The pattern to carry into a guide: **when a
change to reduction/ordering logic seems obviously sound by inspection, build the cheap discriminating
test anyway before implementing** — this codebase's history is that "obviously sound" has been wrong at
roughly the same rate as "seems fine, ship it."

## For the user's guide: honest framing already established, worth carrying forward

- The alpha-complex DQP backend's own paper benchmarks are mixed against Ripser and qhull-based Delaunay on
  some inputs — the real value proposition is high ambient dimension (Delaunay infeasible), exact homology
  rather than diagrams, and smaller complexes than VR when data sits near a low-dimensional subspace, not
  raw speed. A user's guide should say this plainly rather than imply DQP is a strict upgrade.
- `dispatch = "default"` for `Alpha(points, dispatch)` always resolves to `"helix"` regardless of point-
  cloud shape as of now — a user wanting DQP must ask for it explicitly. Worth a clear callout, since the
  name "default" invites the opposite assumption.
- `HelixDelaunay` has a known, quantified failure mode on near-cospherical local clusters at ambient
  dimension ≥ 4 (~1-in-170 in fuzz testing, vs. zero failures at dimension 2/5) — not a hypothetical caveat,
  a measured one. A user's guide operating at higher ambient dimension should mention this rather than
  assume Helix is unconditionally reliable there.
- Four persistence engines exist; a user's guide needs to point people at the right one for their use case
  (incremental querying → `CellularHomologyContext`; large complexes/parallelism →
  `PersistenceInChunksContext`; cohomology → `RipserCohomologyContext`) and explicitly warn off
  `SimplicialHomologyByDimensionContext` until it's fixed (see above) — a user's guide that lists all four
  without that caveat would actively mislead someone into using the broken one.

## Open threads to hand to whoever picks up the guide-writing task

- `WORKLOG-naive-homology.md`, `WORKLOG-cohomology.md`, `WORKLOG-alpha-complex.md`, and this codebase's
  `CLAUDE.md` collectively already contain most of the *specific* bug histories and design derivations a
  developer's guide would want to cite or summarize — this file is meant to sit alongside them as the
  "patterns across sessions" layer, not to duplicate their detail. Read those worklogs for the full
  derivations; read this file for what generalizes.
- Apparent pairs did **not** land this session — both candidate designs were ruled out by counterexample
  (see above and `WORKLOG-cohomology.md`), and the next step is reading arXiv:1908.02518's Proposition 3.9
  proof, deliberately deferred rather than attempted under end-of-session time pressure. It's already a
  good worked example for the guide's process note even unfinished: two "obviously sound" designs, both
  caught by a cheap empirical test before either was shipped.
- Next session picking up apparent pairs should start by reading Proposition 3.9's proof and the
  surrounding algorithm text with a specific question in hand: how does Ripser's actual `compute_pairs`
  sequence apparent-pair removal so that a tau which is one simplex's apparent partner is never also
  needed as a different simplex's legitimate reduction target? That collision (documented with two
  concrete counterexamples in `WORKLOG-cohomology.md`) is what sank both designs tried here, and the paper
  must resolve it somehow, since apparent pairs are real Ripser's primary optimization.
