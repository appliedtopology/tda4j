# `Show` instances beyond simplices, chains and field elements (2026-10-10)

Point-in-time snapshot. The project lead: there are `Show` instances for chains, field elements and simplices; most
other types (barcodes above all) need derived ones.

## What was there

`Simplex` (`∆(1,2,3)`), `Fp` (`Fp(2)`, by the field's `showForSelf`), `Chain` (`Chain.chainShow`, requiring
`CellT: {OrderedCell, Show}` and printing `rawEntries`). Nothing for `Cube` -- so no cubical chain, bar or diagram could
be shown at all -- nor for endpoints, bars, diagrams, the involution's pairs or the simplicial-set generators.

## What was added (all in the data types' companions: implicit scope, no import)

| type | `show` | how |
|---|---|---|
| `Cube` | `Cube([0,1]x{2})` (the existing `cube.show` string) | `CubeInstances.cubeShow` |
| `BarcodeEndpoint` and each case | `[v]`, `(v)`, `+∞`, `-∞` | `endpointShow: [F: Show, E <: BarcodeEndpoint[F]] => Show[E]` |
| `PersistenceBar[F, A]` | `1: [0.5, 1.5)  <representative>` | `barShow` (needs `Show[F]`, `Show[A]`); `unannotatedBarShow` for `A = Nothing` |
| `PersistenceDiagram` and `Of[...]` | header line, then every bar with its representative, by degree | `diagramShow: [CellT: Show, D <: PersistenceDiagram[CellT]] => Show[D]` |
| `Involution.Pair` | `Pair(dim = 1, birth = ∆(1,2), death = Some(∆(0,1,2)))` | kittens `semiauto.show` |
| `SSetElement` | `s1 s0 g` | hand-written (normal-form notation) |
| `ProductGenerator` | `(s0 7, 8)` | hand-written |
| `NerveSimplex` | `[1|2]` (bar notation) | hand-written |
| `ConeGenerator`, `JoinGenerator` | `Cone(g = ∆(0,1))`, `Both(x = 1, y = y)` | kittens `semiauto.show` |
| catalog enums, `PresentationCell` | the case name | `Show.fromToString` |

Choices:
- **cats' `Show` is invariant.** An instance for exactly `PersistenceDiagram[C]` is not found for
  `PersistenceDiagram.Of[C, F]` (what `dim`, `at`, `significant`, `longerThan` return), nor one for
  `BarcodeEndpoint[F]` for `ClosedEndpoint[Double]`. Both are typed for every subtype (`[..., D <: Base] => Show[D]`);
  `ShowInstancesSpec`/`ShowFromUserCodeSpec` check the static types a user holds.
- **`PersistenceBar[F, Nothing]`** (every hand-built bar, every bar read from a file) has its own instance rather than a
  `Show[Nothing]` somewhere.
- **`chainShow` now prints `terms`** (each cell once, coefficients summed, zeros dropped, in the chain's order) and `0`
  for the zero chain. It printed `rawEntries`: a heap chain's deferred entries in heap order, so `a + a` showed twice
  and a cancelled term showed with coefficient 0. Output of the existing `ShowSpec` case is unchanged.
- **`chainShow` needs only `Show[CellT]`**, not `OrderedCell` (it never used it): chains over simplicial-set generators
  have no given `OrderedCell` (theirs comes from the set). Signature change: MiMa filter in `build.sbt`, commented.
- **Kittens where a field dump is the right reading and a type parameter's `Show` matters** (`Involution.Pair` of
  simplices printed `TreeSet(1, 2)` through `toString`); hand-written where mathematics has a notation (degeneracies,
  bar notation, intervals). Kittens is called as `cats.derived.semiauto.show`, never `import cats.derived.*`: a
  wildcard import of an external library in a package file is the flat-package shadowing hazard.
- Bars and diagrams compose the parts' instances, so a user's own `given Show[Double]` (say, two decimals) changes how
  every bar prints (`ShowInstancesSpec` "use the filtration type's own Show").
- `toString` is unchanged everywhere: the diagram's `toString` stays the short summary, `show` is the full listing.

User guide: a short paragraph and fence in `quickstart.md` ("Reading a diagram").
