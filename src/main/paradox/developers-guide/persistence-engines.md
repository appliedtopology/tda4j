# Persistence engines: what to trust, and why

`Homology.scala` contains **four independently-implemented** persistence algorithms. They share the
`Chain` reduction primitives from @ref:[Architecture](architecture.md) (`Chain.reduceBy`/`reduceByUntil`), but
they are not variants of one shared engine — a fix or bug found in one does not imply anything about the
others, and this has been confirmed the hard way more than once (see
@ref:[Hard-won invariants](gotchas.md)). Read this page before choosing which engine to build on, and before
assuming any given engine's output is trustworthy just because it's in the file and compiles.

## 1. `CellularHomologyContext` / `SimplicialHomologyContext` — reference-grade, trustworthy

`CellularHomologyContext[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]` is the naive
single-pivot-table boundary-reduction algorithm: process cells in filtration order, reduce each cell's
boundary against pivots recorded so far via `Chain.reduceBy`, and the cell either opens a class (reduced
boundary is zero) or closes one (reduced boundary is nonzero; its leading cell is the pivot it kills). No
clearing, no chunking, no cohomology/twist optimization. This is the **oracle every other engine in this
file gets cross-validated against** — when in doubt about whether some other engine's output is right,
this is the one to compare against, not the reverse. `SimplicialHomologyContext[VertexT, CoefficientT,
FiltrationT]` is a thin `Simplex[VertexT]`-specialized subclass; `TDAContext` (see
@ref:[Architecture](architecture.md)) extends this one.

It's also the only engine that supports genuine incremental querying: `HomologyState` exposes
`advanceOne()`/`advanceTo(f)`/`advanceAll()`, and `diagramAt(f)`/`barcodeAt(f)` can be called mid-stream to
get the diagram *as of* filtration value `f` without finishing the whole stream. `barcodeAt` additionally
annotates each bar with an actual representative cycle, tracked via a parallel "V-column"
(`generators`/`positives` maps in `HomologyState`) alongside the ordinary boundary-matrix reduction — read
the class doc at the top of `CellularHomologyContext` in `Homology.scala` for the full derivation of why
that reconstruction is correct.

**History worth knowing**: this class was rewritten from a "twist"-with-cohomology-bookkeeping variant that
was silently wrong, due to exactly the `given`-summon-timing bug described in
@ref:[Hard-won invariants #1](gotchas.md).
`HomologyState` now declares `given Ordering[CellT] = stream.filtrationOrdering` as its first statement,
before summoning `chainRM` — this ordering matters, don't reorder it.

## 2. `PersistenceInChunksContext` — trustworthy, audited twice

`PersistenceInChunksContext[VertexT: Ordering, CoefficientT: Field](maxDim: Int = 5)` implements the
parallelizable "clear-and-compress" chunked algorithm from the persistence-in-chunks literature: local
reduction per chunk (Algorithm 2), active-entry marking (Algorithm 3), then global column
compression/reduction (Algorithms 4-5). Built for larger complexes where cross-chunk work can be batched;
`advanceAll()` runs the whole pipeline in one shot (no incremental querying like engine 1 has).

This class summons its `chainRM` (`Chain[Simplex[VertexT], CoefficientT] is RingModule`) at *class* scope,
before any stream exists — structurally the same pattern that broke engine 1. It has been **audited twice
and confirmed correct both times, empirically, not just by inspection**: every pivot-relevant reduction
step actually goes through `Chain.reduceByUntil`, a `def` whose own `[CellT: Ordering]` context bound
resolves fresh at each call site (where the correct `stream.filtrationOrdering` given, declared inside
`HomologyState`, is in scope) — the stale class-scope `chainRM`'s `⊠`/`-` operators are only ever used to
build intermediate values (in `compress`) that get fed straight back into a fresh `reduceByUntil` call
before anything reads `.leadingCell`. See `WORKLOG-cohomology.md`'s "a different ordering bug" section for
the discriminating-fixture methodology (`HomologyFixtures.elderRuleCells`, built specifically so
lexicographic and filtration order disagree) used to confirm this rather than just reason through it.

## 3. `SimplicialHomologyByDimensionContext` — non-functional, do not use

Dimension-0 handled directly via `UnionFind`/Kruskal MST (`UnionFind.scala`); higher dimensions via a
similar reduction approach to engine 1, processed strictly dimension-by-dimension.

**This class currently crashes unconditionally on any complex with at least one MST edge** — i.e. almost
any real input with two or more connected vertices. `HomologyState`'s constructor calls
`barcode(0) = barcode(0).appended(...)` (`Homology.scala:426-427`) against a `mutable.Map[Int,
immutable.Queue[...]]` initialized empty, with no `.getOrElse(dim, immutable.Queue.empty)` guard — contrast
`PersistenceInChunksContext.recordPair`, which has exactly that guard. The same unguarded pattern recurs at
`Homology.scala:490`. This throws `NoSuchElementException: key not found: 0` from inside the constructor,
which means **this class has, as far as anyone can tell, never successfully run end-to-end** — `grep -rl
SimplicialHomologyByDimensionContext src/` finds only its own definition, zero test coverage anywhere.

Separately, once that crash is fixed, it will *also* need the ordering fix from
@ref:[Hard-won invariants #1](gotchas.md):
it declares no `given Ordering[Simplex[VertexT]] = stream.filtrationOrdering` anywhere, so
`Chain.from(edge.boundary).leadingCell` (used to pick the dying vertex at `Homology.scala:423,435,458`)
falls back to the generic lexicographic `Simplex is OrderedCell` ordering rather than filtration order —
the same bug class engine 1 had and fixed. **Both fixes, plus a real test suite from scratch, are their
own bounded task** — don't casually "fix the crash" without also fixing the ordering, or you'll ship a
class that runs but gives wrong answers on any input where lex and filtration order disagree.

If you're tempted to point a new user or a new algorithm at this class because "it's in the file, so it
must work" — it doesn't. Don't.

## 4. `RipserCohomologyContext` — trustworthy for cohomology + clearing; apparent pairs not yet landed

Persistent *co*homology via Ulrich Bauer's Ripser algorithm (arXiv:1908.02518), specialized to
`Simplex[Int]` Vietoris-Rips/clique complexes via `SimplexIndexing`'s combinatorial number system — a
deliberate narrowing from the other three engines' generic `CellT: OrderedCell`, agreed with the project
lead as in-scope. One-shot only: `persistentCohomology()` computes the full barcode in a single pass, no
incremental `advanceTo`-style querying (also agreed scope).

Structural points worth internalizing before touching this class:

- **`cohomologyOrdering`** (`Homology.scala`, class-scope `given`) is ascending by filtration value, tied
  broken so a *larger* combinatorial index sorts *older* (smaller) — the opposite convention from
  `CellularHomologyContext`'s reversed `filtrationOrdering`, because `Chain.leadingCell` is always the
  minimum under whatever ordering backs it, and cohomology's pivot is the *oldest* cofacet in a reduced
  coboundary chain (the dual of homology's "pivot is the youngest boundary term"). This is safe to declare
  at class scope, unlike the `chainRM` hazard elsewhere — it's self-contained (built directly from
  `filtrationValue`/`si`, not by summoning some other ambient given), so there's no stream-not-yet-available
  timing issue.
- **`coboundaryOf(sigma)`** explicitly truncates: `Chain.empty` when `sigma.dim + 1 > maxDimension`. This is
  what makes top-dimension simplices come out essential rather than needing a special case, and it's the
  behavior @ref:[Hard-won invariants #5](gotchas.md)
  warns you to replicate if you build anything new directly on `SimplexIndexing`'s raw iterators instead of
  going through `coboundaryOf`.
- **Clearing is required for correctness here, not an optional speedup layered on an already-correct
  baseline.** A `cleared: mutable.Set[Simplex[Int]]` set tracks every simplex already claimed as a pivot
  one dimension down; those are skipped entirely when reducing the next dimension up. An early draft
  without this passed every hand-built fixture but reported spurious essential cohomology classes on real
  input — confirmed by hand-deriving H¹ of a plain 3-cycle graph (3 reported vs. the correct 1) — because
  Proposition 3.1's essential-index definition requires excluding any simplex already claimed as a pivot at
  a lower dimension, not just checking that its own column reduces to zero. See `WORKLOG-cohomology.md`'s
  "clearing is required for correctness" section for the full derivation.
- Cross-validated against engine 1 (`CellularHomologyContext`) on hundreds of random inputs plus hand-
  derived fixtures — see `WORKLOG-cohomology.md` for the pivot-orientation/birth-death-dimension derivation,
  re-derived directly from the paper rather than from memory (this area of the codebase has a documented
  history of subtly-wrong unverified code; don't "simplify" the reversed-order reasoning here without
  rereading that derivation first).

**Apparent pairs status as of this writing**: not yet landed. Two candidate designs — (1) find the pivot
via the Definition 3.2 apparent-pair check instead of the `reduceBy` head-check, still populating `basis`
normally, and (2) a genuine pre-pass that removes both members of every apparent pair before the main loop,
never building the removed simplex's coboundary at all — were both tried and **confirmed unsound by direct
counterexample**: a cofacet `tau` that is one simplex's apparent partner can simultaneously be a different,
non-apparent simplex's legitimate reduction target, and neither design accounts for that. See
`WORKLOG-cohomology.md`'s "Apparent pairs: negative result" section for the two concrete counterexamples.
If you're picking this up: the next step (per that worklog and the project's own working notes) is reading
Ripser's actual `compute_pairs` implementation/Proposition 3.9's proof to see how real Ripser sequences
apparent-pair removal to avoid this exact collision — this may be in progress or already resolved by the
time you read this, so check `WORKLOG-cohomology.md`'s current state rather than trusting this paragraph
alone.

Don't confuse this with `RipserStreamSparse`/`RipserStreamBase`'s `zeroApparentCofacet`/`zeroApparentFacet`
(`RipserStream.scala`) — those are a *stream-generation-time* filter (skip generating a simplex at all if
it's zero-persistence-paired) built on `topCofacetIterator`, a restricted iterator that only looks at
cofacets formed by inserting a vertex strictly *greater* than the simplex's own maximum — a different
mechanism from, and not verified equivalent to, `RipserCohomologyContext`'s own from-scratch Definition 3.2
check. `Cofacets.scala`'s `apparentVertex` is unrelated to either: it's bookkeeping for lazy generic coface
generation (which vertex is common to every relevant neighborhood), not a persistence-pairing notion.

## Choosing an engine

- Need to query the diagram at intermediate filtration values, or want representative cycles for homology
  classes? **`CellularHomologyContext`/`SimplicialHomologyContext`** (or `TDAContext`, which wraps it).
- Large complex, want to exploit parallelism across chunks? **`PersistenceInChunksContext`**.
- Need cohomology specifically, and your complex is a `Simplex[Int]` Vietoris-Rips/clique complex?
  **`RipserCohomologyContext`**.
- Never **`SimplicialHomologyByDimensionContext`** until someone has fixed both the constructor crash and
  the ordering bug, and written a real test suite for it.

## Dead/experimental code kept intentionally

The bottom third of `Homology.scala` (below `RipserCohomologyContext`) is commented-out prior art
(`RipserHomology`, `computePersistentHomology`) kept for reference while the four live engines above were
developed — not a fifth engine to consider using. `SimplicialSet.scala` is entirely commented out, a sketch
for a future simplicial-set (as opposed to simplicial complex) representation; `Deferred.scala` is an
experiment representing arithmetic as an AST evaluated by a pluggable handler, exploring algebraic-effect-
style deferred coefficient choice, not wired into the rest of the library. Neither should be deleted
without checking with the maintainer first — they're placeholders, not cruft.
