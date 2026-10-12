# Openness audit: `private`, `sealed`, `final` (2026-10-10)

Point-in-time snapshot. The project lead: the library should be a good platform for experiments and research, and
excessive `private`/`sealed` declarations work against that (the same call that opened `Chain` to new storages,
`WORKLOG-compact-representatives.md` step 6). Asked for an audit, the fixes, and the policy written down as a principle.

## What was there

Counted in `src/main` before the change: 116 `private[...]` qualifiers (most `private[tda4j]`, which in the flat
package means "the whole library, but no user"), 4 `sealed`, 54 `final` classes/defs, ~600 plain `private` members.

The restrictions fell into groups:

| group | examples | verdict |
|---|---|---|
| construction streams hidden behind the dispatchers (`DESIGN-stream-naming.md` hid them to keep the surface small) | `EnumeratingCofaceSimplexStream`, `RipserCofaceSimplexStream`, `CofaceSimplexStream`, `CechCofaceSimplexStream`, `WitnessCofaceSimplexStream`, `LazyWitnessSimplexStream`, `DowkerCofaceSimplexStream`, `DtmRipsSimplexStream`, `SheehyRipsSimplexStream`, `IncrementalVietorisRipsSimplexStream`, `InorderCofaceSimplexStream`, `RecursiveStackVietorisRipsSimplexStream`, `LimitedCofaceSimplexStream`, `TruncatedSimplexStream`, `FlatCubicalGridStream`, `Truncated.ofCofaces` | **opened**: a new filtered complex is most easily built on `RipserCofaceSimplexStream`'s coface loop (`filtrationValueOverride`, `keepCriterion`), which is exactly what Cech, witness, Dowker, DTM and sparse Rips do inside the library; hiding it left users only the dispatchers' fixed parameter lists |
| engines and their data | `PackedCubicalCohomologyEngine`, `GridRanks`, `GridCubes`, `GridCellOrder`, `DoubledGrid`, `CubeBoxes`, `SignedUnionFind`, `UnitSignedUnionFind`, `NeighbourLists`, `SortIndices` | **opened**: benchmarking or comparing engines needs to call one directly; the union-finds and the rank table are reusable algorithms |
| pairings and the involution | `HomologyState.pairing` (naive and chunks), `pairedCohomology` (both cohomology engines), `Involution` (`cycles`, `cocycles`, `Pair`), `PackedRipserCohomologyEngine.boundaryOf`, `harmonicSmoothOnComponent`, `insertionDiameter` | **opened**: the persistence pairing in cell terms and "cycles from a pairing" are research tools in their own right |
| geometry and validation | `BowyerWatsonTriangulation` (+ `invariantProblems`), `DelaunayPredicates` (+ `sphereDeterminantSign`), `RadiusLimitedAlphaShapes`, `DelaunayAlphaShapes` object (`FaceTable`), `HelixDelaunay.projectToAffineRank`/`looksValid`/`badFacetsOf`/`interiorVoidVertices`, `AlphaShapes.meanNeighbourCost`/`dqpNeighbourThreshold`, `FastAlphaHomologyEngine.requireDualGraph` | **opened**; diagnostic `var` counters (`insertionErrors`, `exactOrientations`, ...) became private vars behind public read-only `def`s |
| barcode helpers | `DiagramPoint`, `HopcroftKarp`, `Hungarian`, `GroundNorm.require1` | **opened** |
| io helpers | `BinaryIO`, `DistanceMatrices`, `Endpoints` | **opened** (a reader for a new format reuses them) |
| chain storages | `HeapChain`, `PackedChain`, `CellDecoder`, `Chain.packed`, `isPacked` | `PackedChain`, `CellDecoder`, `Chain.packed`, `isPacked` **opened** (packed storage for one's own cells); `HeapChain` public but its constructor and `queue` stay `private[tda4j]` and both stay `final` (below) |
| `Persistence.Input` | `into sealed trait` with `private[tda4j]` hooks | **opened** (unsealed, hooks public and documented): a `Conversion[YourData, Persistence.Input[C]]` now teaches the verb a new kind of input |
| sset ordering helpers | `ssetElementOrdering`, `productGeneratorOrdering`, `eitherOrdering`, `isNonDegeneratePair` | **opened** (a user's own generator types need these orders) |
| `final` on algorithm classes | `DualQP`, `CholeskyWorkspace`, `AlphaComplexDQP`, `SSetMap`, `FiniteGroup`, `Nerve`, `GridRanks`, the `SimplexIndexing` cursors, `FaceTable`, `PowerDistance.gram`/`dualLinear`/`ballRadius` | **`final` removed** (a faster `gram` for a structured space is a legitimate override) |

Kept, each with a `//` comment at the declaration saying why:
- `ClearedSet`, `Level`, `LongIntMap` (`private[tda4j]`): primitive collections with generic names. In the flat package
  a public `Level` would sit in every `import org.appliedtopology.tda4j.*` and silently shadow a user's own `Level`
  (the hazard `CLAUDE.md` describes for external wildcards, in the other direction). Nest in a companion before opening.
- `FastCubicalHomologyEngine.barsWithoutTopRepresentatives` (`private[tda4j]`): a measurement hook that drops
  representatives; every public path keeps them (the representatives design principle).
- `CubicalGridStream.topCellValues`: now `protected[tda4j]` (was `private[tda4j]`), so a subclass can supply its own
  array as `FlatCubicalGridStream` does, while callers read the new `topValues` (`ArraySeq.unsafeWrapArray`, no copy):
  writing into the array would change the grid behind the cached `cellValues`.
- `EdgeCollapsedMetricSpace`'s constructor (`private[tda4j]`): takes the collapse's own mutable maps uncopied, and
  `validUpTo` is a claim only `EdgeCollapse` can make. New public `edges` iterator reads the collapsed graph.
- `HeapChain`: `final`, constructor and `queue` `private[tda4j]`. The reductions and arithmetic match on `HeapChain`
  and read `queue` directly, so a subclass overriding its reads would be silently bypassed; a queue not ordered by the
  reversed cell order gives wrong leading terms. `PackedChain` is `final` for the same reason (`isPacked`).
- `BarcodeEndpoint` stays `sealed`: the four endpoint kinds are a closed classification that the ordering and every
  reader match on exhaustively. `FastAlphaHomologyEngine`'s local `DualEvent` likewise.
- File-private: `MiniballPointSet` (adapter to Miniball's interface; radii public via `CechFiltration`),
  `DowkerPlaceholderMetricSpace` (answers 0 for every distance, fit only for its constructor slot), `rankAtEpsilon`
  (a top-level def would join every wildcard import), `HelixDelaunayBuilder` (the walk's mutable state; `HelixDelaunay`
  is its face), `FacetSums` (one method's scratch).
- `sset.Constructions` and `SimplicialSetStream.fromStream` (`private[sset]`): every member is public another way
  (`FiniteSimplicialSet` methods, `SimplicialSetCatalog.fromStream`).
- Facade leaves: `matlab`'s option enums, `ThresholdSpec`, the result classes' constructors; `cli`'s `private[cli]`
  helpers. Their contract is strings and Java arrays; everything they dispatch to is public in the core.
- Engine-internal scratch (`WorkingColumn`, `Column`, `Pairing`, `Reduction`, `Entry`, private helper defs): plain
  `private`, no comment needed. Member-level `private` was NOT audited one by one (~600); the rule below says when to
  promote one (`protected` for a subclass hook, public when it computes something a caller wants).
- `final def`s that `@tailrec` needs (`SimplexIndexing.apply`, `CofacetIterator.fillQueue`, `hasNext`).

## `open`: what a user's subclass actually sees

The library is compiled with `-language:adhocExtensions`; users are not. Checked by compiling a user file against
`target/.../classes` with the Scala 3.9.0 compiler directly (`dotty.tools.dotc.Main`, classpath from the coursier
cache):

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
class MyRips(ms: FiniteMetricSpace[Int]) extends RipserCofaceSimplexStream(ms)
class MyGrid extends CubicalGridStream(IndexedSeq(2, 2), _ => 0.0)
```

With `-source:future -feature`: two Feature Warnings, "Unless class RipserCofaceSimplexStream is declared 'open', its
extension in a separate file should be enabled by adding the import clause 'import scala.language.adhocExtensions'".
Without `-source:future`: silent. Not an error, but fatal under `-Werror`, and it tells the user the library did not
mean the class to be extended. So every concrete public class of the core and `sset` is now `open` (79 classes), except
the two whose constructors are restricted (`GridRanks`, `EdgeCollapsedMetricSpace`). `open` is a TASTy flag only, no
bytecode change.

Re-run after adding `open` (same command, `-source:future -feature`): no output, `MyRips.class` and `MyGrid.class`
written. Dropping `final` from the `SimplexIndexing` cursors (on the packed-Ripser cofacet path) was not A/B-timed: the
expectation that HotSpot devirtualizes a class with no loaded subclass is the usual one, not measured here.

## Compatibility

MiMa: opening a `private[tda4j]` declaration changes nothing in bytecode (Scala emits it public already); removing
`final` and adding `open` are compatible. The renamed diagnostic vars (`insertionErrors`, `exactOrientations`,
`exactSpheres`, `perturbedSpheres`) were `private[tda4j]`. The `TDAlab` re-export block grew by 38 names (regenerated).

## Follow-ups noticed, not done

- `BarcodeDistance` returns the distance only, not the optimal matching; a researcher comparing diagrams usually wants
  the matching too (`HopcroftKarp`/`Hungarian` are now public, so it can be rebuilt, but the distance code should
  return it).
- Member-level hooks in the engines (clearing policy, cofacet enumeration) are still plain `private`; promote on demand.
