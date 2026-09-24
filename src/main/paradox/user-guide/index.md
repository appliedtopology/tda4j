# User Guide for TDA4j

TDA4j implements persistent homology and related techniques from computational and applied topology. This
guide assumes you know what a simplicial complex, a filtration, and a persistence barcode are — it does not
assume you know Scala. If you want to understand *why* the library is built the way it is, or you're
planning to write new code against it, see the @ref:[Developer's Guide](../developers-guide/index.md)
instead; this page is about getting things done as a caller.

## Quick-start: Scala

Snippets included via `@@snip` (with a source-file link) are compiled and exercised directly by the test
suite. The rest are illustrative and hand-maintained, not mechanically checked — if you find one has
drifted, trust the source over this page.

### Imports

TDA4j's package is split into subpackages (`algebra`, `cells`, `streams`, `homology`, `alpha`, ...). Bring
in what you need with the `{given, *}` form — a plain `import pkg.*` does **not** bring `given` instances
(coefficient fields, orderings) into scope in Scala 3:

```scala 3
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
```

The rest of this guide assumes these four imports (plus `alpha.{given, *}` where alpha complexes come up).

### Building and taking the boundary of a simplex

```scala 3
// Coefficients need an explicit Field instance in scope -- there is no default one for Double.
// DoubleApproximated treats two coefficients as equal within epsilon, which matters for the
// zero-checks that drive chain reduction.
given Double is Field = Field.DoubleApproximated(1e-9)

val triangle = Simplex(1, 2, 3)      // same as ∆(1, 2, 3)
triangle.boundary[Double]            // Seq((Simplex(2,3), 1.0), (Simplex(1,3), -1.0), (Simplex(1,2), 1.0))
```

### A full Vietoris-Rips persistence computation

@@snip [APISpec.scala](/src/test/scala/org/appliedtopology/tda4j/APISpec.scala) { #full-vr-computation }

`TDAContext[VertexT, CoefficientT, FiltrationT]` bundles the naive, reference-grade persistence engine
together with chain-arithmetic operators, so `1.0 ⊠ ∆(1,2) - ∆(2,3)` works directly once `ctx`'s members are
imported. It's a good default for exploration and for anything where you want to query the diagram at
intermediate filtration values or get representative cycles back (`state.diagramAt(f)`/`state.barcodeAt(f)`)
— see "Which persistence engine?" below for when a different engine is worth reaching for instead.

**A default worth knowing**: `EnumeratingCofaceSimplexStream` and the other Vietoris-Rips stream
implementations default `maxFiltrationValue` to the point cloud's own *minimum enclosing radius*, not
unbounded, since nothing past that radius contributes new homology. Pass `Some(Double.PositiveInfinity)`
explicitly if you want the old always-unbounded behavior.

### Alpha complexes

```scala 3
import org.appliedtopology.tda4j.alpha.{given, *}

val shape = AlphaShapes(points.toSeq, dispatch = "helix")   // or "DQP"
```

`AlphaShapes(points)` with no `dispatch`, or `dispatch = "default"`, always resolves to `"helix"` — ask for
`"DQP"` explicitly if you want it. See "Which alpha-complex backend?" below for the tradeoffs.

### Cech complexes

```scala 3
val cechStream = CechCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(2.0))
val homology = SimplicialHomologyContext[Int, Double, Double]().persistentHomology(cechStream)
```

`maxFiltrationValue` here is a Cech **radius**, not a Vietoris-Rips diameter — the two aren't
interchangeable units. Only the naive engine (`SimplicialHomologyContext`/`CellularHomologyContext`) is used
for Cech complexes; the packed Ripser engine's optimizations don't carry over (see the
@ref:[Developer's Guide](../developers-guide/architecture.md)).

### Witness complexes

```scala 3
val ambient = EuclideanMetricSpace(points)
val landmarks = LandmarkSelector.maxmin(ambient, numLandmarks = 20).landmarks

val lazyStream = LazyWitnessSimplexStream(ambient, landmarks)   // nu = 2, JavaPlex's own default
val homology = SimplicialHomologyContext[Int, Double, Double]().persistentHomology(lazyStream)
```

De Silva & Carlsson's witness complex (2004; the construction behind most of the JavaPlex tutorials) builds
a complex over a small **landmark** subset of your point cloud, using every point (landmarks included) as a
**witness** — useful when the full point cloud is too large to build a Vietoris-Rips complex over directly.
`LandmarkSelector.maxmin` (sequential furthest-point sampling, a covering-radius guarantee) and
`LandmarkSelector.random` (seeded, cheaper, no coverage guarantee) both work over any `FiniteMetricSpace[Int]`
— a point cloud or a precomputed distance matrix.

Every emitted `Simplex[Int]`'s vertices are **local landmark indices** (`0 until landmarks.size`), not indices
into your original point cloud — map back through `landmarks(i)` yourself (`matlab.TDA4j` does this for you).

Two variants, matching JavaPlex's own two classes:

- **`LazyWitnessSimplexStream`** (JavaPlex's `LazyWitnessStream`) — a flag/clique complex, so it also works
  directly with the packed Ripser engine (`PackedRipserCohomologyContext`) by handing it a
  `WitnessMetricSpace` instead of a stream: `PackedRipserCohomologyContext(WitnessMetricSpace(WitnessGeometry(ambient,
  landmarks), nu = 2), maxDimension)`. The `nu` parameter (`0`, `1`, or `2`, default `2`) controls how
  forgiving a witness's own threshold is — see `WitnessMetricSpace`'s own doc.
- **`WitnessCofaceSimplexStream`** (JavaPlex's plain `WitnessStream`) — NOT a flag complex, so `engine=ripser`/
  `chunks` don't apply; use the naive or cohomology engine instead. `maxFiltrationValue` here defaults to
  `+Infinity` (untruncated), not the point cloud's enclosing radius — the enclosing-radius shortcut is only
  valid for flag complexes.

```scala 3
val geometry = WitnessGeometry(ambient, landmarks)
val generalStream = WitnessCofaceSimplexStream(geometry, maxFiltrationValue = 2.0)
```

**Pick a finite `maxFiltrationValue` for the general variant, or cap dimension with
`LimitedCofaceSimplexStream`** — its default is `+Infinity`, and nothing prunes an unbounded enumeration, so
a direct call can reach the full `2^landmarks.size` power set. `matlab.TDA4j` always caps dimension for you.
Prefer `witnessVariant=lazy` (the default) when the flag-complex behavior is acceptable — it unlocks
`engine=ripser`, and at tutorial scale (1000 points, 50 landmarks, threshold `2R` — `R` the landmark
selection's own covering radius, the JavaPlex tutorial's own recommended threshold) that engine choice is
where nearly all the speed difference actually is: `lazy+ripser` ~0.2s vs. `lazy+naive` ~11.2s (same
complex, same bar count, engine alone) vs. `general+naive` ~11.5s (variant alone, engine held to `naive`,
indistinguishable from `lazy+naive` at single-trial resolution) — measured, not inferred (single-trial
numbers on one machine; see `.claude/WORKLOG-witness-complex.md`).

#### The two-step recipe from MATLAB/CLI: select landmarks, read R, then compute

The one-shot `complex=witness` path (below, in "Calling from MATLAB or Java") picks landmarks internally and
never reports back the covering radius `R` — so the JavaPlex tutorial's own "pick landmarks, read `R`, use `2R`
as the threshold" recipe isn't reproducible through it. `matlab.TDA4j` also offers the SAME construction split
into two steps for exactly this: `selectLandmarksFrom{Points,DistanceMatrix}` (step 1, returns a
`LandmarkSelectionResult` with both `landmarks()` and `coveringRadius()`) and
`computeFrom{Points,DistanceMatrix}AndLandmarks` (step 2, takes that landmark array directly). Both entry points
work from Scala too, not just MATLAB:

```scala 3
val selection = TDA4j.selectLandmarksFromPoints(points, Array("numLandmarks", "50"))
val result = TDA4j.computeFromPointsAndLandmarks(
  points,
  selection.landmarks(),
  Array("maxFiltrationValue", (2 * selection.coveringRadius()).toString)
)
```

From MATLAB:

```matlab
javaaddpath('target/scala-3.9.0/TDA4j-<version>-assembly.jar');
selection = org.appliedtopology.tda4j.matlab.TDA4j.selectLandmarksFromPoints(points, {'numLandmarks', '50'});
landmarks = selection.landmarks();   % int32 array, 0-BASED ambient indices into `points`
R = selection.coveringRadius();

result = org.appliedtopology.tda4j.matlab.TDA4j.computeFromPointsAndLandmarks(points, landmarks, ...
    {'maxFiltrationValue', sprintf('%.17g', 2 * R)});
bars = result.toArray();
```

Two MATLAB-specific traps worth calling out explicitly:

- **`landmarks` is 0-based** (matching `cycleVertices`'s own convention), but MATLAB arrays are 1-based — index
  `points` with `points(landmarks + 1, :)`, not `points(landmarks, :)`.
- **Use `sprintf('%.17g', 2 * R)`, not `num2str(2 * R)`**, to build the `maxFiltrationValue` option string.
  `num2str`'s own default precision silently rounds most covering radii, which can shift which simplices
  actually fall under the threshold — `sprintf('%.17g', ...)` round-trips a `double` exactly.

`coveringRadiusFromPoints`/`coveringRadiusFromDistanceMatrix` compute `R` for a landmark set you didn't get
from `selectLandmarksFrom*` (hand-picked, or reused from elsewhere) — same `2R` recipe, different source for
the landmarks. Every entry point in this two-step family validates its own `landmarks` array eagerly (non-empty,
0-based in range, no duplicates), with a message that specifically flags an index equal to `points.length` as a
likely 1-based-indexing mistake.

From the CLI, the same two steps are two separate invocations:

```
tda4j --select-landmarks --num-landmarks 50 --output landmarks.txt points.csv
# tda4j: covering radius R = 0.12081466774937936 -- e.g. pass '0.24162933549875873' as --max-filtration-value...

tda4j --landmarks-file landmarks.txt --complex witness --max-filtration-value 0.24162933549875873 points.csv
```

The first writes one 0-based landmark index per line to `landmarks.txt` (plus a `# coveringRadius=...` comment
line) and prints `R` — and the exact `2R` string to pass on — to stderr; **use that printed string verbatim**,
not a value you round or retype by hand (the same truncation trap `sprintf('%.17g')` avoids above applies
here too). The second invocation reads the landmarks file back in and computes the barcode. `--complex witness`
on the second line is optional (that combination of flags can only ever mean a witness complex) but
accepted if you type it out of habit from the one-shot form.

### Cubical complexes and images

```scala 3
val stream = CubicalImage.fromFlatArray(shape = IndexedSeq(3, 3), flatValues = pixelValues, sublevel = true)
val homology = CubicalHomologyContext[Double, Double]().persistentHomology(stream)
homology.diagramAt(Double.PositiveInfinity)
```

`CubicalImage` also has `fromGrayscale2D`, `fromVoxelGrid3D`, `fromBufferedImage`, and `fromFile` for loading
real images/volumes. `sublevel = false` computes superlevel-set persistence instead (ascending vs.
descending intensity) via the standard "negate the values" trick — reported filtration values under
`sublevel = false` are in negated-intensity units, not raw pixel values.

### Simplicial sets

For homology of a space presented combinatorially (not as a metric-space complex), build a
`FiniteSimplicialSet[G]` by giving each generator's faces directly. A minimal circle (one vertex, one loop
edge):

```scala 3
enum CircleGen { case V, E }
import CircleGen.*
given Ordering[CircleGen] = Ordering.by(_.ordinal)

val circle = FiniteSimplicialSet[CircleGen](summon[Ordering[CircleGen]])(
  generatorsByDim = IndexedSeq(Set(V), Set(E)),
  faces = {
    case V => IndexedSeq.empty
    case E => IndexedSeq(SSetElement(Nil, V), SSetElement(Nil, V)) // both faces of the loop are V
  }
)
circle.validate()   // Seq.empty -- no errors

given (CircleGen is OrderedCell) = circle.cellInstance
val stream = FilteredSimplicialSetStream(circle, { case V => 0.0; case E => 1.0 })
CellularHomologyContext[CircleGen, Double, Double]()
  .persistentHomology(stream)
  .diagramAt(Double.PositiveInfinity)
// List((1, 1.0, Infinity), (0, 0.0, Infinity)) -- H0 = H1 = one essential class each, as expected for S^1
```

`FiniteSimplicialSet`'s companion object builds new simplicial sets from existing ones instead of by hand:
`FiniteSimplicialSet.product`/`.coproduct` (the categorical product/coproduct) and `.quotient`/`.identify`
(attaching maps — glue generators together, or collapse one down onto a lower-dimensional target).
`validate()` checks that hand-written or constructed face data actually satisfies the simplicial identities;
it's a necessary sanity check, not proof the resulting space is the one you intended.

## Loading and saving data: the `io` module

`org.appliedtopology.tda4j.io` reads and writes the file formats the wider TDA ecosystem uses, so you don't
have to hand-roll parsing:

| Object | Formats |
|---|---|
| `CSV` | plain CSV point clouds, full/lower-triangular distance matrices, persistence diagrams |
| `Ripser` | Ripser's point-cloud/lower/upper/full/binary distance-matrix formats |
| `Dipha` | DIPHA's distance-matrix, cubical-image, and persistence-diagram formats |
| `Gudhi` | GUDHI's OFF point-cloud format and persistence-diagram format |
| `Perseus` | Perseus's cubical toplex and persistence-interval formats |

Each object offers a raw loader (`readPointCloud`/`readFullDistanceMatrix`/...) and a one-line convenience
constructor on top (`readEuclideanMetricSpace`/`readExplicitMetricSpace`/`readCubicalGridStream`/...):

```scala 3
import org.appliedtopology.tda4j.io.{given, *}

val metricSpace = Ripser.readEuclideanMetricSpace("points.txt")
val stream = Perseus.readCubicalToplex("image.txt")
```

Two format details worth knowing if you're comparing output against another tool: Ripser's binary
distance-matrix format is 32-bit `float`, not 64-bit `double`. DIPHA's and Perseus's cubical-grid axis order
is the opposite of `CubicalImage`'s own convention (first axis fastest-varying vs. last); both readers/
writers here handle the transposition for you.

## Command-line tool: `tda4j`

`sbt assembly` builds a runnable fat jar exposing the whole library as a command-line tool, without writing
any Scala:

```
java -jar target/scala-3.9.0/TDA4j-<version>-assembly.jar [options] <input-file>
```

It loads a point cloud, distance matrix, or cubical image in one of several formats (`--input-format`),
computes persistence via the same facade the MATLAB bridge uses (below), and writes the result in one of
several formats (`--output-format`: `text`, `csv`, `gudhi`, `dipha`, `perseus`). Run with `--help` for the
full flag list; the main ones mirror the MATLAB options one-to-one: `--complex` (`vr`/`alpha`/`cech`/
`witness`), `--engine`, `--max-dimension`, `--max-filtration-value`, `--field`, `--representatives` (also
print each bar's representative chain), and (for `--complex=witness`) `--num-landmarks`, `--witness-variant`,
`--landmark-selector`, `--landmark-seed`, `--nu`. `--select-landmarks`/`--landmarks-file` split that same
witness-complex computation into the two-step recipe described above.

## Calling from MATLAB or Java

`org.appliedtopology.tda4j.matlab.TDA4j`/`PersistenceResult` is a real, tested facade for calling TDA4j from
MATLAB's built-in Java interface, or from any plain-Java caller — every public method and return type is a
plain `int`, `double`, `String`, `double[][]`, or `String[]`; no Scala types, no generics, no context
parameters, no Unicode operator names.

```java
double[][] points = { {0.0, 0.0}, {1.0, 0.0}, {0.5, 0.8} };
PersistenceResult result = TDA4j.computeFromPoints(points);

double[][] bars = result.toArray();   // one row per bar: [dimension, birth, death], death = Inf if essential
double[] coeffs = result.cycleCoefficients(0);
int[][] simplices = result.cycleVertices(0);   // each row: a simplex's sorted vertex indices
```

```matlab
javaaddpath('target/scala-3.9.0/TDA4j-<version>-assembly.jar');
points = [0.0 0.0; 1.0 0.0; 0.5 0.8];
result = org.appliedtopology.tda4j.matlab.TDA4j.computeFromPoints(points);
bars = result.toArray();
```

Entry points: `computeFromPoints`/`computeFromDistanceMatrix` (Vietoris-Rips/alpha/Cech/witness, from a point
cloud or a precomputed distance matrix — alpha and Cech need real coordinates, so they're only available from
the points overload; witness works from either, exactly like `vr`), `computeFromCubicalImage`/
`computeFromImage` (cubical persistence from a flat array + shape, or a 2D pixel matrix directly), and the
two-step witness recipe's own four entry points -- `selectLandmarksFromPoints`/`selectLandmarksFromDistanceMatrix`
(→ `LandmarkSelectionResult`) and `computeFromPointsAndLandmarks`/`computeFromDistanceMatrixAndLandmarks`, plus
the `coveringRadiusFromPoints`/`coveringRadiusFromDistanceMatrix` query pair -- covered in their own section
above rather than the table below, since they take an explicit `int[] landmarks` parameter and each has its OWN
(stricter) recognized-options set rather than sharing the table's. Every method here has a no-options overload
and one taking a flat, alternating key/value `String[]` of options — so adding a new option in the future never
changes a method's call signature:

| Option | Values | Default |
|---|---|---|
| `complex` | `vr`, `alpha`, `cech`, `witness` | `vr` |
| `engine` | `ripser`, `naive`, `chunks`, `cohomology` | `ripser` for `vr` and `witness`/`witnessVariant=lazy`; `naive` for `alpha`/`cech`/`witness`/`witnessVariant=general` |
| `alphaBackend` | `helix`, `DQP` | `helix` (only consulted for `complex=alpha`) |
| `maxDimension` | integer | `2` — highest H_k reported, not highest simplex dimension built |
| `maxFiltrationValue` | double | the point cloud's own minimum enclosing radius (`+Infinity` for `witness`/`witnessVariant=general`) |
| `field` | `Z` (finite field), `R` (floating point) | `Z`, `prime=2` |
| `prime` | integer | `2` (only for `field=Z`) |
| `epsilon` | double | `1e-9` (only for `field=R`) |
| `numLandmarks` | integer | REQUIRED for `complex=witness`, no default |
| `witnessVariant` | `lazy`, `general` | `lazy` (only consulted for `complex=witness`) |
| `landmarkSelector` | `maxmin`, `random` | `maxmin` (only consulted for `complex=witness`) |
| `landmarkSeed` | integer | `0` (only for `complex=witness`/`landmarkSelector=random`) |
| `nu` | `0`, `1`, `2` | `2` (only for `complex=witness`/`witnessVariant=lazy`) |

`alpha` refuses `engine=ripser` and `engine=chunks` (neither engine understands alpha complexes, and the
chunks/alpha combination is a known stall risk in the underlying library); `cech` refuses `engine=ripser`
(the packed Ripser engine's optimizations are proven for Vietoris-Rips's diameter functional specifically,
not Cech's circumradius); `witness` with `witnessVariant=general` refuses both `engine=ripser` and
`engine=chunks` for the same reason as `cech` (the general witness complex isn't a flag complex either) —
use `witnessVariant=lazy` (the default) for `engine=ripser`/`chunks`. `engine=cohomology` is accepted
everywhere `engine=naive` is (`vr`, `alpha`, `cech`, and `witness` alike). Unrecognized keys or values throw
`IllegalArgumentException` immediately rather than silently falling back to a default.

Representative-chain vertex indices for `complex=witness` are **ambient point-cloud indices**, already
mapped back from the stream's own local `0 until numLandmarks` landmark indices — `cycleVertices` never
reports a raw local landmark index.

`PersistenceResult.cycleVertices`/`cycleCoefficients` give you each bar's representative chain: every engine
records one for every bar — though for `engine=cohomology`, only an *essential* bar's representative is
guaranteed to be a genuine cocycle (zero coboundary); a finite bar's is a valid witness on its own living
interval, not over the whole complex (see the developer's guide's persistence-engines page for why).

## Which persistence engine?

| Need | Engine (`engine=` for MATLAB/CLI) |
|---|---|
| Exploration, intermediate-filtration queries, representative cycles | `naive` (`CellularHomologyContext`/`TDAContext`) |
| Fastest, most memory-efficient — the default for `complex=vr` | `ripser` (`PackedRipserCohomologyContext`) |
| Large complex, want representatives for every bar including essential ones | `chunks` (`CellularPersistenceInChunksContext`) |
| Cohomology (cocycle representatives) on `Cube`/`FiniteSimplicialSet`, or on Alpha/Cech/witness, where `ripser` doesn't apply | `cohomology` (`CellularCohomologyContext`) |
| Alpha or Cech complexes, or a general (non-flag) witness complex | `naive` or `cohomology` (`chunks` also works for Cech) |
| A lazy witness complex (the flag-complex variant) | `ripser` (`PackedRipserCohomologyContext`, run directly on `WitnessMetricSpace`) or `naive`/`chunks`/`cohomology` |

All engines are generic over the coefficient field (a prime finite field or floating point); `naive`,
`chunks`, and `cohomology` are also generic over the cell type (simplices, cubes, or simplicial-set
generators) — only `ripser` is Vietoris-Rips-specialized. See the
@ref:[Developer's Guide's persistence-engines page](../developers-guide/persistence-engines.md) for the full
detail.

## Which alpha-complex backend?

- **`"helix"`** (`HelixDelaunay`) — an actual Delaunay triangulation, computed incrementally. What
  `dispatch = "default"` resolves to. Has a known, quantified failure mode on point clouds with a
  near-cospherical local cluster: zero failures across 20,000-trial fuzz testing at ambient dimension 2 and
  5, but roughly 1-in-170 at ambient dimension 4 with 20-30 points, on ordinary-looking input. Don't treat
  its output as unconditionally reliable ground truth at ambient dimension 4 or higher.
- **`"DQP"`** (`AlphaShapeDQP`) — a dual active-set quadratic-programming method (Carlsson & Carlsson 2024)
  that never builds a Delaunay triangulation at all. Its real strength is high ambient dimension, where
  Delaunay becomes infeasible, and exact homology rather than an approximate persistence diagram. The
  paper's own published benchmarks are mixed — it loses to Ripser on 2 of 4 of its own persistence examples,
  and to qhull-based Delaunay on some inputs. The honest value proposition is high-dimensional feasibility
  and exactness, not raw speed.

Both backends agree that in degenerate (cospherical) point configurations — e.g. points on a regular grid —
the alpha complex genuinely contains higher-dimensional simplices than a triangulation-based mental model
would suggest (a unit grid in the plane produces 3-simplices, one per unit square, not just triangles). This
is correct behavior, not a bug in either backend; users coming from CGAL or GUDHI may find it surprising.

## Performance: opt-in parallelism

A few of the more expensive per-cell computations can run on the JVM's common thread pool, opt-in via a
constructor flag (`AlphaDQPSettings.parallel`, `CubicalGridStream.parallelFiltrationValue`,
`CechCofaceSimplexStream.parallelFiltrationValue`), all defaulting to `false`, with deterministic output
either way. Worth turning on for a large alpha-complex computation (each vertex's own QP solve is genuinely
expensive — measured 2-4x speedup at a few hundred points and above); cubical images and Cech complexes see
a smaller win (a few percent) since the per-cell cost there is lighter.

## Tutorials

@ref:[Tutorials](../tutorials/index.md) — currently a placeholder; porting Henry Adams' JavaPlex tutorials
to TDA4j is tracked there as future work, not yet done. The witness-complex construction those tutorials
lean on heavily is now implemented (`LandmarkSelector`/`WitnessGeometry`/`LazyWitnessSimplexStream`/
`WitnessCofaceSimplexStream`, see "Witness complexes" above) — a building block for that port, not the port
itself.
