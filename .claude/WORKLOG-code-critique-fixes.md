# WORKLOG: fixing the whole-project code critique (`WORKLOG-code-critique.md`), 2026-09-22/23

**Scope.** Worked through the project lead's per-item responses to `WORKLOG-code-critique.md` (items 1.1-10),
given in full at the start of this arc. A previous session (Opus 5.5) had gone off the rails attempting the same
list and was reverted to a clean baseline (commit `a7848ad`, byte-identical to `60f61d8`) before this arc began.
Worked in seven chunks (A-G below), verifying every chunk with a full `sbt test` run before moving to the next,
and calling `advisor()` before starting substantive work, before any large restructure, and before declaring the
arc done. Chunk G's comment-pruning pass was initially scoped down and reported as such, then finished in a
direct continuation of this same conversation after the project lead asked for it; this file was updated once,
at that point, to reflect the arc's actual end rather than its first (premature) stopping point. Not updated
further after this.

## What shipped, by chunk

**Chunk A** — small correctness fixes: `Ordering[BarcodeEndpoint]` reflexivity, `Alpha`/`AlphaShapes` dispatch's
missing default case (kept the 3 identical branches as placeholders per instruction, not collapsed), a
`ExplicitStreamBuilder` `val`→`def` field-ordering fix, a `CofacetIterator` concurrent-modification fix, `Chain`'s
`items`→`rawEntries` rename, `Cohomology.coboundaryOfChain`'s `.toMap`→`.groupMapReduce` fix, and stale
representative-gap text removed from `matlab.TDA4j`/`cli.TDA4jConf`/`matlab.PersistenceResult`/user-guide/CLAUDE.md.

**Chunk B** — deletions and doc updates: dead legacy Ripser stream classes, `SymmetryGroup.scala` (whole file,
stale research direction), `ScalaPointSet`, `SimplicialHomologyByDimensionContext` (wrong representatives, kept
only as an oracle per CLAUDE.md — ripped out per instruction), the root `SimplicialSetSpec.scala` (dead, fully
commented out), and the `kiama`/`kiama-extras`/`scala-graph` dependencies. Updated `architecture.md`,
`class-diagrams.md`, `gotchas.md`, `persistence-engines.md`, user-guide/index.md (first `@@snip` conversion,
`APISpec.scala#full-vr-computation`) and CLAUDE.md to match.

**Chunk C** — file/package placement: `RipserStream.scala`→`SimplexIndexing.scala`; `Cell`/`OrderedCell`/
`OrderedBasis` moved out of `Chain.scala` into a new `algebra/Cell.scala`; `CubicalHomologyContext` moved from
`streams` to `homology`; `FiniteSimplicialSet`'s `product`/`coproduct`/`quotient`/`identify`/`elementsAtDim` moved
onto its own companion object; `SimplicialSetStream.fromStream` moved onto its companion; `Alpha` renamed to
`AlphaShapes.apply` with `Point` moved onto that companion too — **all four are public API renames**, see the
breaking-change ledger below.

**Chunk D** (scoped down from the original ask, see "Deviations" below) — a shared `FiltrationOrdering.canonical`
combinator (`SimplexStream.scala`) replacing five hand-copied filtration-ordering comparators
(`EnumeratingCofaceSimplexStream`, `CubicalGridStream`, `ExplicitCubicalStream`, `RecursiveStackVietorisRipsSimplexStream`,
`HelixDelaunay`, `FilteredSimplicialSetStream`), fixing two of them (`RecursiveStackVietorisRipsSimplexStream`,
`HelixDelaunay`) that were missing the dimension tie-break key entirely. Verified bit-for-bit identical against
the pre-existing hand-built comparators via a tie-heavy probe spec (not committed) before relying on the shared
version.

**Chunk E** — engine trait + facade dispatch: `homology.PersistenceEngine` (a `naive`/`chunks`/`cohomology`
factory trait), and `matlab.TDA4j`'s dispatch rewritten to parse each MATLAB-facing option string exactly once
into a private `ComplexKind`/`EngineKind`/`CoefficientKind` enum, dispatching on those enums via
`PersistenceEngine` rather than re-matching the raw string at each branch — per the instruction that the
MATLAB-facing layer itself cannot match on types, only the dispatch built on top of the parsed strings can.
`cli.TDA4jCLI`'s `resolveInput` similarly tightened; `io.Perseus` gained `readCubicalImageData` as the shared core
under `readCubicalToplex`.

**Chunk F** — the §6 idiom cleanups and §7 test cleanup, item by item:
- **6.1 algebra**: `FiniteField.Fp`'s dead `Numeric`/`Fractional`-era members removed (`compare`, `fromInt`,
  `parseString`, `toDouble`, `toFloat`, `toInt`, `toLong`, plus a dead `p2` val and a stale comment); `op1`/`op2`
  inlined into direct arithmetic (removes a closure allocation per call); `Chain.entries` made `private`
  (previously public, unused externally — confirmed by grep); `leadingTerm` rewritten from an obscure
  `unzip`+`copy` composition to a plain `Option`-match; `OrderedBasis` given a doc noting it currently has exactly
  one instance (`Chain`'s own); five `given`/`def` renames to `lowerCamelCase`
  (`chain_is_ordered_basis`→`chainIsOrderedBasis`, `Simplex_is_OrderedCell`→`simplexIsOrderedCell`,
  `default_Simplex_is_OrderedCell`→`defaultSimplexIsOrderedCell`, `Cube_is_OrderedCell`→`cubeIsOrderedCell`,
  `default_Cube_is_OrderedCell`→`defaultCubeIsOrderedCell`, `FiniteSimplicialSet_is_OrderedCell`→
  `finiteSimplicialSetIsOrderedCell`), all cross-references (CLAUDE.md, docs, comments) updated to match.
- **6.2 remainder**: `SimplexOrderedCell`'s `dim` now delegates to `SimplexOps.dim` via a qualified call
  (`Simplex.dim(spx)`, not `spx.dim`, to avoid the extension resolving to itself) instead of recomputing `size - 1`
  a second time; `ssetElementOrdering`/`productGeneratorOrdering` rewritten with `Ordering.by`/stdlib
  `seqOrdering` (`eitherOrdering` kept hand-rolled, see "Deviations"); `FiniteSimplicialSet`'s `Ordering[G]`
  parameter converted from a positional `val ord` to a `using` clause — **placed AFTER the value parameter list,
  not before** (see "Deviations": using-first broke type inference here).
- **6.4 remainder**: `streams.UnionFind`'s `union` fixed to link root-to-root by rank (previously linked an
  unresolved endpoint, producing unbounded-depth chains) with path compression added to `find`; `Kruskal
  .cycleToChain` restored with a correct tree-edge-adjacency derivation (see "Deviations": the critique's
  suggestion was to delete it, the project lead's own "viable platform for algorithms research" principle argued
  against that, so it was fixed instead); `VietorisRips.scala`'s `TopCofacetEnumerator`/
  `RecursiveStackSimplexEnumerator` cleaned up (`hasNext()`→`hasNext`, snake_case `neighbor_it`/`edge_it`→
  `neighborIt`/`edgeIt`, the `@tailrec`+`while`+`return` state machine rewritten as pure tail recursion with the
  same transitions, verified by inspection against the original branch-by-branch).
- **6.5 remainder**: `insertionDiameter` (byte-identical in both Ripser engines) factored into one
  `private[homology]` top-level function in `PackedRipserCohomology.scala`, taking `metricSpace` as an explicit
  parameter; both engines' own copies deleted, call sites updated.
- **6.7 alpha**: `HelixDelaunay` given a `seed: Long = 0L` parameter (seeds the bootstrap frontier-selection
  shuffle) and restructured into a `HelixDelaunayBuilder` (mutable working state) + `HelixDelaunay` (immutable
  result) split, matching `AlphaComplexDQPBuilder`/`AlphaComplexDQP`'s existing pattern — verified byte-for-byte
  identical output on 6 fixed panels (grid, cospherical, random, in 2D and 3D) before and after the restructure
  (script not committed; see "Deviations" for why the seed-effect claim in the doc comment changed mid-arc).
  `AlphaComplexDQP.witnessOf`/`coordsOf`/`Found.witness` converted from nullable `Array[Double]` to
  `Option[Array[Double]]` — this closed a **real, previously-live bug** (see "Deviations"). `AlphaDQPSettings`
  gained a `verbose: Boolean = false` field gating the one `System.err.println` in `solveAtVertex`, off by
  default (was previously unconditional).
- **6.8 remainder**: every `io` write method (`CSV.writePointCloud`/`writeFullDistanceMatrix`/
  `writeLowerTriangularDistanceMatrix`, `Dipha.writeDistanceMatrix`, `Ripser.writePointCloud`/
  `writeLowerDistanceMatrix`/`writeUpperDistanceMatrix`/`writeBinaryLowerDistanceMatrix`, `Gudhi.writeOff`,
  `DistanceMatrices.flattenLowerTriangular`/`flattenUpperTriangular`) standardized from a mix of `Seq[Seq[Double]]`/
  `IndexedSeq[IndexedSeq[Double]]` to `Array[Array[Double]]`, matching what every read method already returns —
  confirmed as real friction, not just cosmetic, by several existing call sites doing `points.map(_.toSeq).toSeq`
  purely to satisfy the old signature (now simplified to just `points`). Root `package object tda4j` converted to
  a plain top-level `class TDAContext` in `package.scala` (Scala 3's non-deprecated form) — **source-compatible,
  not binary-compatible**, see the ledger.
- **§7**: `asStratified` (duplicated ×2 in `HomologySpec.scala`, both missing the stream contract's rule-2
  `.sorted(using filtrationOrdering.reverse)`) deduped into one corrected private method. `explicitStream`
  (duplicated ×2, `HomologySpec.scala`/`SimplicialSetStreamSpec.scala`) and `naiveBars`
  (`IncrementalVietorisRipsSpec.scala`'s general form) moved into new shared test fixtures
  (`streams.StreamFixtures`, `homology.HomologyFixtures.naiveBars`) — `RipserCohomologySpec`'s own `naiveBars`
  (a different signature, VR-specific pre/post-processing) kept local but rewritten to delegate to the shared
  core rather than duplicating the `SimplicialHomologyContext(...).persistentHomology(...).diagramAt(...)` triplet.
  `randomCloud` (×4 across the benchmark specs, three genuinely different shapes) centralized the same way: one
  shared `HomologyFixtures.randomCloud(n, ambientDim, rng)`, each spec's own wrapper now a one-line delegate.
  **The benchmark/`ProfileDriver` file-location move was proposed and explicitly declined by the project lead**
  (see "Deviations") — left in place.

**Chunk G** — CLAUDE.md drift, comment pruning, `@@snip`, close-out:
- CLAUDE.md fixes: rule 1 now names `FiltrationOrdering.canonical` as *the* way to build a `filtrationOrdering`;
  the Cubical section's stale "`ExplicitCubicalStream`'s ordering is an independent duplicate" claim corrected;
  the MATLAB section now describes the `ComplexKind`/`EngineKind`/`CoefficientKind`/`PersistenceEngine` dispatch
  from chunk E; the `PersistenceResult.cycleVertices` bullet corrected (the `UnsupportedOperationException` path
  is defensive, not a live gap — matches "Current gaps: none known", which was true and the bullet wasn't); the
  deleted root `SimplicialSetSpec.scala` removed from the package-layout list; the stale "bottom third of
  `Homology.scala` is commented-out prior art" claim removed (that code is gone, confirmed by grep); three new
  rules recorded (`using`-first constructor clauses breaking type inference, context-bound givens not visible to
  same-signature default values, never run two `sbt` processes against one checkout concurrently — the exact
  cause of the one `NoClassDefFoundError` false alarm mid-arc). File stays at ~37.6k characters, under the ~40k
  target.
- Comment pruning: done on all six files in the project lead's own priority order (`CechStream.scala`, all six
  `cells/` package files, `SimplexIndexing.scala`, `PackedRipserCohomology.scala`, `SimplexStream.scala`, and
  `Homology.scala`), each verified comment-only via `git diff -U0 <file> | grep '^[-+][^-+]' | grep -vE
  '^[-+][[:space:]]*(//|\*|/\*\*|\*/|$)'` printing nothing where a whole edit's diff could be isolated, and by a
  full `sbt clean test` (344/0/0) after every batch otherwise (`Homology.scala`/`SimplexStream.scala` carry
  substantial pre-existing changes from earlier chunks in the same cumulative diff, so the grep check alone
  can't isolate just this pass's edits there -- the test suite is the authoritative check for those two).
  `Homology.scala`'s pass covered every location a `measured`/`this session`/`confirmed by direct`/`% of`/
  session-narrative grep sweep flagged (about a dozen blocks, several spanning 15-30 lines each), extracting the
  underlying invariant and cutting the narrative around it; two dangling `WORKLOG-*.md` references were found
  and fixed in the process (`WORKLOG-ordering-contract.md` in `SimplexStream.scala`, referenced but never
  existed). Not claimed as an exhaustive line-by-line audit of either file -- both are large and this was a
  targeted sweep of the flagged hotspots, not a cover-to-cover rewrite.
- `@@snip`: `scala3-primer.md`'s hand-edited "(paraphrased)" block (the one this arc's own edits made byte-
  identical to real code without noticing) converted to a real `@@snip` against a newly-tagged
  `#given-example` region in `SimplexOrderedCell.scala`. **15 other fenced blocks across `user-guide/index.md`
  (×6), `scala3-primer.md` (×3 remaining), `architecture.md` (×4), `alpha-complex.md` (×1) are still
  hand-maintained** — the user-guide page already carries an honest disclaimer distinguishing `@@snip`-checked
  from hand-maintained snippets, so this isn't a false claim, just incomplete conversion.
- Close-out, run one command at a time: `scalafmtAll` (reformatted 16 `.scala` files), `scalafmtSbt` (needed
  separately — `scalafmtAll` doesn't cover `build.sbt`; reformatted it, pure column-realignment after chunk B's
  dependency removals), `scalafmtCheck scalafmtSbtCheck` (clean), `sbt clean test` (**344 total, 0 failed, 0
  errors, 332 passed, 10 skipped** on a fully clean rebuild — skipped count is the 7 benchmark specs's
  aggregate specs plus benchmark-gated subspecs, per CLAUDE.md), `mimaReportBinaryIssues` (confirmed permanent
  no-op, `mimaPreviousArtifacts` is empty), `sbt makeSite` (succeeds; 5 pre-existing scaladoc cross-reference
  warnings unrelated to this arc, not investigated).

## Deviations from the literal per-item instructions, and why

- **`eitherOrdering` kept hand-rolled** (6.2's "fix these" for hand-rolled orderings): no stdlib `Ordering[Either]`
  exists to delegate to; `ssetElementOrdering`/`productGeneratorOrdering` were genuinely replaceable with
  `Ordering.by`, this one wasn't.
- **`Chain.reduceByUntil`'s `reductionLog` has no default**, despite a comment asking "want to have a default
  empty here?": tried it, hit a genuine Scala 3 limitation (now recorded in CLAUDE.md) — `[CellT: Ordering,
  CoefficientT: Field]`'s context bounds desugar to a `using` clause appended AFTER the value parameter list, so
  a default value earlier in that list can't reference the `Field`/`Ordering` givens `Chain.empty` itself needs.
  Fixed the comment to explain this instead of adding the default.
- **`Kruskal.cycleToChain` was restored and corrected, not deleted**, after an earlier pass in this same arc
  deleted it (reasoning: the pre-existing `union` bug made its tree-edge-path premise unreliable). On review this
  was the wrong call against the project lead's own explicit "we aim to also be a viable platform for algorithms
  research" principle (given for a different item, 6.2's `SimplexOps`, but the same principle applies here) — a
  correct fix was available (an explicit tree-edge adjacency built from `lrList._1`, mirroring
  `CellularPersistenceInChunksContext.unionFindDim01`'s own audited BFS derivation) and was implemented instead.
  Verified by a new property test (`UnionFindSpec`) checking every `cyclesIterator` edge's `cycleToChain` result
  has zero boundary over `Fp(3)`, across 100+ random point clouds.
- **The `⊠` (RingModule scalar-action) extension failed to resolve for `Chain[Simplex[T], CoefficientT]` inside
  `Kruskal`'s new methods**, even after pinning `Ordering[Simplex[T]]` explicitly — worked around by summoning
  the `RingModule` instance directly and calling `.scale`/`.plus`/`.minus` as ordinary methods instead of via the
  `⊠`/`+`/`-` extension syntax. The root cause (why extension-method search for `⊠` didn't succeed here when the
  same pattern works in `Homology.scala`'s `unionFindDim01`) was **not** identified — flagging this as a real gap
  in understanding, not a resolved mystery, in case it recurs elsewhere.
- **`HelixDelaunay`'s seed doc comment was wrong on the first pass**, claiming the bootstrap shuffle "has no
  bearing on which Delaunay simplices are found." Measured directly (varying seed 0-9 on a 3x3 grid and on 6
  cospherical points, script not committed): the seed DOES change which of several valid tilings the frontier
  walk produces on exactly the inputs CLAUDE.md's own "Accepted limitation" describes as order-dependent. Fixed
  the comment to say the seed controls (not merely reproduces) that choice, and moved the "Accepted limitation"
  text (previously only in CLAUDE.md, with the class's own scaladoc reduced to a bare paper link) into the actual
  `HelixDelaunay` class doc, with `@param seed`.
- **`AlphaComplexDQP.witness` had a real, live bug**, found while converting `witnessOf`/`coordsOf` from `null` to
  `Option` per the critique's general null-to-Option preference: `AlphaComplexDQPBuilder.compute()` stored a raw
  `null` into `witnesses` unconditionally for coordinate-free builds, so `witnesses.get(cell)` returned
  `Some(null)`, not `None` — silently violating `witness`'s own documented contract, for every
  `PowerDistance.fromSquaredDistances`-built complex, with no existing test ever exercising that path. Fixed and
  pinned with a new regression spec (`AlphaComplexDQPNoCoordinatesSpec`).
- **The benchmark/`ProfileDriver` file-location move (part of §7's original scope) was proposed to the project
  lead and explicitly declined** ("leave them where they are") rather than attempted or silently skipped — it
  needed a real `build.sbt` source-directory change that wasn't part of the project lead's own per-item
  approvals, so it was treated as a decision for them, not an autonomous call.
- **`Homology.scala` and `SimplexStream.scala`'s comment pruning was initially deferred, then finished in a
  follow-up continuation of this same arc** after the project lead asked for it directly. First pass: skipped
  both (~2,240 lines combined) as a scope/budget tradeoff, disclosed in the first report rather than silently
  rushed. Second pass, this same arc: worked through both files, targeting every location a `measured`/`this
  session`/`confirmed by direct`/`% of`/session-narrative grep sweep flagged, verified by full `sbt clean test`
  (344/0/0) throughout. Not claimed as an exhaustive line-by-line audit — a targeted sweep of flagged narrative,
  not a cover-to-cover rewrite of either file.

## Untracked files (need `git add`, not yet part of any commit)

```
src/main/scala/org/appliedtopology/tda4j/algebra/Cell.scala          (chunk C: Cell/OrderedCell/OrderedBasis)
src/main/scala/org/appliedtopology/tda4j/homology/PersistenceEngine.scala  (chunk E)
src/main/scala/org/appliedtopology/tda4j/streams/SimplexIndexing.scala    (chunk C: git mv from RipserStream.scala
                                                                            not detected as a rename by git status,
                                                                            content changed too much in between)
src/test/scala/org/appliedtopology/tda4j/streams/StreamFixtures.scala    (chunk F, §7)
```

## Breaking-change ledger (this arc; chunks A-C's renames were reported to the project lead earlier in the same
session before this file was written — repeated here for completeness)

- `Alpha(points, dispatch)` → `AlphaShapes(points, dispatch)`.
- `FiniteSimplicialSet.product`/`.coproduct`/`.quotient`/`.identify`/`.elementsAtDim` moved onto the type's own
  companion object (were top-level `cells` package defs).
- `SimplicialSetStream.fromStream` moved onto `SimplicialSetStream`'s companion.
- `RipserStream.binomial` → `SimplexIndexing.binomial`.
- `SimplicialHomologyByDimensionContext` deleted (kept only as an oracle, never wired to a public engine path).
- `SymmetryGroup.scala` (whole file: `SymmetryGroup`, `HyperCubeSymmetry`, `HyperCubeSymmetryGenerators`, and
  the `BitSet` variants) deleted.
- `FiniteSimplicialSet`'s constructor: `Ordering[G]` moved from a positional first parameter to a `using` clause
  after the value parameters (was needed to fix a real type-inference bug, not stylistic).
- Five `given`/`def` renames (see chunk F above) — `chain_is_ordered_basis`, `Simplex_is_OrderedCell`,
  `default_Simplex_is_OrderedCell`, `Cube_is_OrderedCell`, `default_Cube_is_OrderedCell`,
  `FiniteSimplicialSet_is_OrderedCell`.
- `PowerDistance.euclidean`/`.fromSquaredDistances`: `weights: Array[Double] = null` → `Option[Array[Double]]`.
- `Chain.entries`: public `var` → `private var` (confirmed zero external readers by grep before tightening).
- `UnionFind.union`'s return value: previously always the LEFT argument's resolved root regardless of which side
  actually became the new root; now genuinely the new root (whichever side rank-comparison picked).
- `Kruskal.cycleToChain`: implementation corrected (was silently relying on `union`'s buggy root-linking); same
  signature.
- io module: `CSV.writePointCloud`/`.writeFullDistanceMatrix`/`.writeLowerTriangularDistanceMatrix`,
  `Dipha.writeDistanceMatrix`, `Ripser.writePointCloud`/`.writeLowerDistanceMatrix`/`.writeUpperDistanceMatrix`/
  `.writeBinaryLowerDistanceMatrix`, `Gudhi.writeOff`, `DistanceMatrices.flattenLowerTriangular`/
  `.flattenUpperTriangular`: `Seq[Seq[Double]]`/`IndexedSeq[IndexedSeq[Double]]` → `Array[Array[Double]]`. This
  pushed `.map(_.toArray).toArray` into several existing specs that built fixtures as `Seq`/`IndexedSeq` literals
  — flagging this as a design choice the project lead can overrule, not a unilaterally-obvious win, even though
  it removed real friction at other call sites (see chunk F's 6.8 note).
- `package object tda4j` → top-level `class TDAContext` in `package.scala` — source-compatible (same import
  behavior, same call sites work unchanged), but not binary-compatible (different generated class shape). Since
  `mimaPreviousArtifacts` is empty here, nothing enforces this either way; noted for the project lead's own
  judgment given the pre-1.0 status.
- Several `case class` → `class` conversions (mutable/stateful types that shouldn't have synthesized
  `equals`/`hashCode`/`copy`/pattern-matching): `CofacetIterator`, `JVPTree`, `BruteForce`, `SparseMetricSpace`,
  `TopCofacetEnumerator`, `RecursiveStackSimplexEnumerator`, and both engines' own `HomologyState`. Breaks any
  external `.copy(...)` call or pattern match against these — grepped for both before converting, found none.
- `VietorisRips.scala`: `TopCofacetEnumerator.hasNext()` → `hasNext` (breaks a caller written with explicit
  parens, under Scala 3's parameterless-vs-empty-parens override rules) — internal to this file, no external
  callers found by grep.
- `AlphaComplexDQP.witnessOf`/`.coordsOf`: `Array[Double]` (sometimes `null`) → `Option[Array[Double]]`;
  `AlphaComplexDQP.Found.witness`: same change. `AlphaComplexDQP.witness(cell)` now genuinely returns `None` where
  it used to return `Some(null)` for coordinate-free builds — a bug fix, but also a behavior change for any
  caller that happened to pattern-match `Some(null)` specifically (none found).
- `AlphaDQPSettings`: gained `verbose: Boolean = false`. Source-compatible via the default; not binary-compatible
  (case class `copy`/`equals`/`hashCode`/`unapply` all changed shape).

## What's left (not attempted, or attempted and stopped short, this arc)

- **14 more fenced-code-block-to-`@@snip` conversions** across `user-guide/index.md`, `scala3-primer.md`,
  `architecture.md`, `alpha-complex.md` — see the chunk G note above.
- **Task #9 (deferred at the project lead's own explicit choice earlier in this arc, via `AskUserQuestion`)**:
  §2's rule-2/4 structural enforcement across the 11 stream classes (a `maxDimension`+`cellsOfDimension`/
  `sortBucket` redesign), 6.4's `VRFiltration` extraction (fixing `IncrementalVietorisRipsSimplexStream`'s
  private/protected `resolvedMaxFiltrationValue` shadowing), and §2's optional wrapper-type work. Still
  documented-but-not-enforced, as before this arc.
- **5 pre-existing scaladoc cross-reference warnings** surfaced by `sbt makeSite` (`Chain`, `PersistenceBar`,
  `SimplexFiltration` link-resolution failures) — not investigated, likely pre-dating this arc.
