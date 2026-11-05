---
layout: main
title: Contracts
---

TDA4j is a platform for experiments, so its extension points are open: you can hand an engine your own stream, give a
stream your own criterion, or write a new engine. Most of what makes that safe is a contract the caller keeps, and
most contracts are **documented, not checked**, because a check costs time in an inner loop. This page lists the
contracts, who checks each, and what you see when one is broken.

[Hard-won invariants](gotchas.md) is the other side: traps in the existing code. This page is what the code
asks of you.

## Streams

A stream is a filtered complex: cells in filtration order, a filtration value for each, and (for a stratified
stream) the cells of each dimension.

| Contract | If you break it | Checked by |
|---|---|---|
| **`filtrationOrdering` is a total order with the filtration value reversed**, built with `FiltrationOrdering.canonical(value, dim, tieBreak)`. Ties need a tie-break, or tied cells collide as one map key. | `IllegalStateException: reduction pivot ... was not a recorded open class`, or a quietly different barcode | `HomologyFixtures.respectsOrderingContract` |
| **A dimension's cells are sorted by the same `Ordering` object, reversed** (`.sorted(using filtrationOrdering.reverse)`), never by a second comparator that looks equivalent | the same | the same |
| **Filtration is monotone**: `value(face) <= value(coface)`, exactly. A computed value that is one ulp below a face's is a violation (Čech clamps each radius to its facets' maximum). | negative-length or lost bars | `respectsOrderingContract` |
| **Every face of a cell is in the stream** (closed under faces), with the boundary the cell type's `boundary` returns | `∂∂ ≠ 0`, wrong Betti numbers | `respectsOrderingContract` |
| **The dimensions are contiguous from 0**, and `isDefinedAt` is false past the top | `.iterator` never ends | `respectsOrderingContract` |
| **A truncated stream says so**: it overrides `homologyDegreeLimit` (the minimum with the stream it wraps). Its top-dimension "classes" are artifacts, since every unfilled top cell looks essential. | spurious essential bars in the top degree | `PersistenceVerbSpec`, `TruncatedTopDegreeSpec` |
| **`filtrationValue` is cheap**: it is called inside comparisons. Memoize it. | an order of magnitude in time | benchmark only |

A complete complex (explicit, cubical, alpha, simplicial set) reports no limit; a hand-built complex that wants its top
degree must not be a `Truncated` stream. `ExplicitStreamBuilder.fromFacets` closes a list of maximal cells under faces.

### `keepCriterion` and `filtrationValueOverride`

Coface streams (`VietorisRips`, `Cech`, the witness, Dowker, DTM and sparse constructions) take hooks that cut
or change the complex. They are public on purpose, and they give the caller the responsibility.

- **`keepCriterion` must be closed under faces.** Every face of a simplex it keeps, vertices and edges included, is kept.
  The generator cuts each dimension by the criterion and builds the next from what is left, which is the answer of the
  coface loop only when the criterion is closed. A criterion that is not gives unpredictable cells and bars, and nothing
  tells you: checking costs a facet lookup per simplex.
- A criterion is asked about a `Simplex` per candidate. The default is the shared constant `CofaceSimplexStream.keepAll`;
  a literal `{ case _ => true }` in its place silently takes the slow path, because the stream recognises the constant to
  build its layers on arrays.
- **`filtrationValueOverride` is valid when the new filtration is itself monotone** (Čech is: its radii are monotone
  along faces). It is not forwarded to the generator-backed layers yet, so an override is slower than the default value.
- **An edge-collapsed or sparse complex is a flag complex** of a weighted graph, and may take the generator route.
  Everything that reads only the edge list and the values of simplices is fine; a rule about a simplex not determined
  by its edges is not.
- **A flat filtration equals the stream's `filtrationValue` bit for bit.** Where a stream provides `flatFiltration` (array
  values for layers), `FlatLayersSpec` compares it with the stream built from cells. A new hook belongs in that spec's list.

### Metric spaces

- **A distance matrix is read from its lower triangle**: `distance(x, y)` is `d(max, min)`, as in ripser.cpp and GUDHI. An
  asymmetric input gives every tool the same complex.
- **The default `maxFiltrationValue` is the minimum enclosing radius**; the barcode does not change beyond it. Pass
  `Some(Double.PositiveInfinity)` to build all of it. The radius of a Čech or alpha complex is not a Vietoris-Rips diameter,
  so a bar count of one against the other means nothing.
- **Never collect all pairwise distances into one collection**: at tens of thousands of points it does not fit. A loop over
  pairs `a < b` asks for `distance(b, a)`.
- A metric that is not a real metric (a witness complex's edge weights) must not be given to the spatial search or to
  alpha.

## Engines

- **Every bar has a representative** (a chain that witnesses it), over any `Field`. An engine that cannot return one is a
  research tool, not an engine of the `Persistence` verb. Cycles and cocycles may differ between engines on ties: compare
  them for validity (a cycle's boundary is zero; it is present at its birth), never cell for cell.
- **Zero-length bars are dropped by default**, on the true pairing and not after truncation. An engine takes
  `includeZeroLength` (default `false`). `diagramAt(f)` reports a class alive at `f` as `[birth, f]`.
- **An engine stops at the stream's `homologyDegreeLimit`**, whatever degree it was asked for: it computes
  `min(asked, limit)`.
- **`maxDimension` is the top homological degree.** Engines build one dimension higher internally. A stream constructor
  may speak in simplex dimension instead; use the dispatchers (`VietorisRips`, `Cech`, ...), which speak in degrees.
- **Pairing and order agree.** An engine that reads a stream reduces under the order the stream declares. A pivot that is
  not the paired birth cell throws; it is never worked around.
- **Degree 0 by union-find needs edges of the shape `c·(v - u)`** with both ends in the stream, else the engine reduces it
  by columns. The `useUnionFind = false` switch exists so that tests can compare the two.
- **The query contract of the cursor engines:** `diagramAt(f)` is exact at any `f`, wherever the cursor is. `advanceFor`,
  `processedCells` and `totalCells` are kept for long runs.
- **Reductions are deterministic in the thread count**: a parallel step gives the same bars for any split. `Parallel` runs
  in order on Scala.js.

## Coefficients and chains

- **Generic over `Field`.** Never test the runtime class of a coefficient (`case d: Double`): on Scala.js every number is
  a `Double`, so an `Fp` matches. Ask the field (`Field.toDouble`).
- **A new `Field` with exact equality overrides `hash`.** `Chain.hashCode` must agree with `equals`, which compares formal
  sums with `field.isEqual`; the default hash of `0` is correct for every field and slow for a set of chains.
- **A `Chain` storage passes its order and field up and implements `entryIterator`**; the rest has defaults. No nulls.
- **Summon a chain's `RingModule` where the stream's `Ordering` is in scope**, or it pivots on the cell's own order
  ([Hard-won invariants](gotchas.md)).

## Public API

- **No top-level givens in the core package.** A default instance lives in the companion of the data type it serves, never in
  a typeclass's companion.
- **A restriction (`private[tda4j]`, `sealed`, `final`) carries a comment naming its reason**: an invariant-bearing mutable
  state, a library-wide guarantee, a name that would shadow the user's in the flat package, a facade internal. "It keeps the
  API small" is not one. Concrete public classes are `open`.
- **Numeric options take `Optional[Double]`** and point inputs the `PointCloud` `into` type, so `2`, `2.0` and `Some(2.0)` work.
- **Facade methods take only `double`, `int`, `String` and arrays**, and unknown option keys are errors
  ([Extending the facades](facades.md)).

## Why most contracts are not checked

A check costs a lookup per simplex or per cell, in code whose speed is the point. Checking is moved to a test that runs
on the contract's fixtures: `respectsOrderingContract` on a stream, a reference engine compared term for term, a
discriminating example (a fixture with vertex 1 born early and vertex 9 late) where the plausible wrong answer fails.
When you write an extension, run its stream through `respectsOrderingContract` and its engine against the reference
engines before you trust a barcode; see [Testing conventions](testing.md).
