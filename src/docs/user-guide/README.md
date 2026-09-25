# User Guide for TDA4j

TDA4j implements persistent homology and related techniques from computational and applied topology. This
guide assumes you know what a simplicial complex, a filtration, and a persistence barcode are — it does not
assume you know Scala. If you want to understand *why* the library is built the way it is, or you're
planning to write new code against it, see the [Developer's Guide](../developers-guide/README.md)
instead; this page is about getting things done as a caller.

## Vision and goals

TDA4j exists to make persistent (co)homology computations that are correct and inspectable first, fast
second — not the other way around. A handful of commitments run through essentially every engine and
construction in this library, and they explain a lot of choices that might otherwise look like unnecessary
extra work:

- **Generic over coefficients, always.** Every homology engine here is generic over the coefficient field,
  not hardcoded to `Z/2` the way it's tempting to be for a first implementation. This isn't cosmetic: `Z/2`
  provably hides sign errors that a signed field (`Double`, or an odd prime `Fp`) exposes, and this codebase
  treats agreement across both as a real correctness check that's run routinely, not as a nice-to-have.
- **Representatives, not just barcodes.** Every engine returns a genuine chain witnessing each bar, not only
  a birth/death pair. A barcode alone can't answer "which part of my data does this feature correspond to";
  a representative cycle can. An optimization that can't produce one is treated as incomplete, not as a
  reasonable speed/completeness tradeoff — this is a standing, foundational design principle, not a
  per-engine judgment call.
- **Cross-validated by construction, not by convention.** TDA4j deliberately keeps independent
  implementations of the same computation side by side — four persistent homology engines, two independent
  Delaunay/alpha-complex backends, half a dozen Vietoris-Rips streaming strategies — and checks them against
  each other on hand-derived and randomized fixtures rather than trusting a single implementation and hoping
  it's right. A bug or fix found in one engine is never assumed to carry over to the others. This costs more
  engineering time up front than committing to one "best" implementation; that cost is accepted deliberately,
  because agreement between independently-derived engines is real evidence in a way that internal consistency
  of a single engine never can be.
- **Honest, measured performance claims.** Where this library is genuinely competitive, that's stated with
  real numbers; where it isn't, that's stated too, not glossed over. The packed Ripser engine's own
  documentation states plainly that it remains ~19–64x behind real `ripser.cpp` on `sphere3_*` benchmarks,
  with the gap growing with `n`; the alpha-complex DQP backend's paper-level benchmarks against Ripser and
  qhull are reported as mixed, because they are. Every performance claim here is expected to survive an
  isolated A/B measurement before it's written down anywhere, and an unconfirmed effect is reported as
  unconfirmed rather than asserted.
- **A usable surface beyond Scala.** The MATLAB-facing facade (`matlab.TDA4j`) and the standalone CLI
  executable exist so the library is directly usable by people who will never write a line of Scala, not as
  an afterthought bolted onto an internal API.

### What TDA4j deliberately does not do

- **It does not chase raw throughput as the primary goal.** Where a faster, more mature external tool exists
  for a specific job (`ripser.cpp` on plain Vietoris-Rips, qhull-based Delaunay at low ambient dimension),
  TDA4j does not try to win that benchmark outright. Its own value is genericity (any `Field`, several cell
  types), representatives on every bar, and cross-validated correctness — not being the fastest tool for one
  narrow job.
- **It does not implement every optimization in the literature.** Ripser's own "emergent pairs" (Def 3.11) are
  a known, deliberately-skipped optimization; Cavanna-Jahanseir-Sheehy's own faster `O(n log n)` neighbor
  search for the sparse Vietoris-Rips construction is deliberately not implemented here (this library's own
  `O(n²)` version produces a smaller complex to *reduce*, which is the part that was slow, rather than a
  faster complex to *build*, which wasn't). Skipping a known optimization is a recorded decision with its own
  reasoning, not an oversight to eventually get around to.
- **It does not treat "the one fastest engine" as a target to converge on.** Keeping multiple independent
  engines for the same computation is a permanent architectural choice, not technical debt awaiting
  consolidation — the redundancy is the point.
- **It does not guess at unverified file formats.** A format with no primary source to check an implementation
  against (Perseus's own simplicial toplex format, PHAT, sparse triplet distance matrices) is left
  unimplemented rather than shipped as a plausible-but-unverified parser; a wrong parser is worse than none.
- **It does not force every construction into one shared geometric abstraction.** Alpha complexes and the
  Vietoris-Rips/Ripser machinery are kept as separate, only minimally-interacting parts of the codebase by
  deliberate choice, rather than unified into a common framework where the fit would be awkward for both.
- **It is not (yet) a general algebraic-topology toolkit.** Simplicial sets, for instance, have real support
  for filtrations and homology but no MATLAB/CLI surface yet, and no bar-construction or classifying-space
  machinery — genuinely useful future directions, but currently out of scope rather than silently missing.

## Quick-start: Scala

Snippets included via `@:snip` (with a source-file link) are compiled and exercised directly by the test
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

@:snip(/src/test/scala/org/appliedtopology/tda4j/APISpec.scala, full-vr-computation)

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

#### A faster engine for alpha complexes

```scala 3
val helix = HelixDelaunay(points)
val bars = FastAlphaHomologyContext[Double]().persistentHomology(helix) // H0 and H1, that's everything at 2D
```

For a point cloud built via `"helix"` (never `"DQP"` — it never builds an adjacency-aware triangulation at all,
so it can't supply what this engine needs), `FastAlphaHomologyContext` computes the same barcode (with real
representatives) as the naive engine, via a dual-graph union-find rather than general `Chain` reduction, at any
ambient dimension `>= 2`. At 2D specifically the two union-finds (`H_0`/`H_1`) cover everything; at 3D and
beyond, the "middle" dimensions are handed to `PersistenceInChunksContext` on a view that hides the real
top-dimensional simplices, the same hybrid `FastCubicalHomologyContext` uses above. On a fraction of point
clouds — more likely at higher ambient dimension and point count (measured at roughly 1-in-18700 at ambient
dimension 2, but roughly 1-in-1666 at ambient dimension 3 with 20-30 points) — it throws
`FastAlphaTriangulationException` — a message written for you, not just for a developer: it says plainly that
this is not an error in your data, explains the `HelixDelaunay` limitation and the measured rates, and names
the fix (retry with `engine="naive"`/`"chunks"`/`"cohomology"`, none of which are affected). `matlab.TDA4j`'s
`engine="fast-alpha"` option (and the CLI's `--engine fast-alpha`) use this automatically for `complex=alpha`
with the default `alphaBackend=helix`, at any ambient dimension `>= 2`.

### Cech complexes

```scala 3
val cechStream = CechCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(2.0))
val homology = SimplicialHomologyContext[Int, Double, Double]().persistentHomology(cechStream)
```

`maxFiltrationValue` here is a Cech **radius**, not a Vietoris-Rips diameter — the two aren't
interchangeable units. Only the naive engine (`SimplicialHomologyContext`/`CellularHomologyContext`) is used
for Cech complexes; the packed Ripser engine's optimizations don't carry over (see the
[Developer's Guide](../developers-guide/architecture.md)).

### Sheehy's sparse/approximate Vietoris-Rips filtration

```scala 3
val sheehyStream = SheehyRipsSimplexStream(metricSpace, epsilon = 0.5)
val homology = SimplicialHomologyContext[Int, Double, Double]().persistentHomology(sheehyStream)
```

A `(1+epsilon)`-multiplicative approximation to plain Vietoris-Rips's own barcode, built from a complex
that's linear-sized (for point sets of bounded doubling dimension) rather than the full Vietoris-Rips
complex — fewer simplices to reduce at the same scale range, at the cost of a controlled, quantified loss of
precision (Cavanna, Jahanseir & Sheehy 2015). Smaller `epsilon` means less sparsification and a closer
approximation to plain Vietoris-Rips; `epsilon` must be strictly between `0` and `1`. Like Cech above, only
`naive`/`chunks`/`cohomology` are used — the packed Ripser engine's optimizations assume a plain
max-pairwise-distance filtration functional, which this construction's own sparsification and vertex
"vanishing" don't satisfy (see the [Developer's Guide](../developers-guide/architecture.md)).

### Flag-complex edge collapse

```scala 3
val collapsed = EdgeCollapse.collapse(metricSpace) // a FiniteMetricSpace[Int], drop-in for any VR-consuming stream
val stream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(collapsed), 3)
val homology = SimplicialHomologyContext[Int, Double, Double]().persistentHomology(stream)
```

Unlike Sheehy's construction above, this is not an approximation: Boissonnat-Pritam/Glisse-Pritam edge
collapse reduces a Vietoris-Rips filtration's own 1-skeleton to a smaller weighted graph with the EXACT same
persistent homology at every filtration level, before anything is built on top of it. `EdgeCollapse.collapse`
returns an ordinary `FiniteMetricSpace[Int]` (`EdgeCollapsedMetricSpace`), so it plugs into
`EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream` — and every engine that consumes them — with no
other code changes; representatives transfer for free (the collapsed complex is a literal subcomplex of the
original at every level). Measured 73-76% of edges removed and a 43-47x reduction-phase speedup on random
point clouds — see the [Developer's Guide](../developers-guide/architecture.md)'s "Flag-complex edge collapse"
section for the construction-vs-reduction breakdown and why they differ so much. `matlab.TDA4j`'s
`edgeCollapse=true` option (and the CLI's `--edge-collapse`) apply this automatically for `complex=vr` — see
"Calling from MATLAB or Java" below.

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

#### A faster engine for cubical images

```scala 3
val stream = CubicalGridStream(IndexedSeq(rows, cols), topValue)
val bars = FastCubicalHomologyContext[Double]().persistentHomology(stream) // H0 and H1, that's everything at 2D
```

`FastCubicalHomologyContext` computes the exact same barcode (with real representatives) as
`CubicalHomologyContext` above, via a different algorithm entirely — a dual-graph union-find (Alexander
duality) rather than general `Chain` reduction, valid at any ambient dimension `>= 2` (it throws
`IllegalArgumentException` only for a degenerate 1-axis grid). At a 2D grid specifically, the two union-finds
(`H_0` and `H_1`) cover everything; at 3D and beyond, the "middle" dimensions (no duality shortcut applies to
them) are handed to `CellularPersistenceInChunksContext` on a view that hides the real top-dimensional cells,
so the top dimension still skips general `Chain` reduction entirely — still a real win, though a shrinking one
as the ambient dimension grows, since the fraction of dimensions the two union-finds can cover for free shrinks
with it. See the [Developer's Guide](../developers-guide/persistence-engines.md)'s engine 6 section for the
full picture. `matlab.TDA4j`'s `engine="fast-cubical"` option (and the CLI's `--engine fast-cubical`) use this
automatically for any `computeFromCubicalImage`/`computeFromImage` call at ambient dimension `>= 2`.

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
`witness`/`dtm-rips`/`dtm-alpha`/`sheehy-rips`), `--engine`, `--max-dimension`, `--max-filtration-value`,
`--field`, `--representatives` (also print each bar's representative chain), and (for `--complex=witness`)
`--num-landmarks`, `--witness-variant`, `--landmark-selector`, `--landmark-seed`, `--nu`. For
`--complex=dtm-rips` or `--dtm-alpha`, use `--dtm-k` (required), `--dtm-q` (default 2.0), and `--dtm-p`
(default 1.0, only for `dtm-rips`). For `--complex=sheehy-rips`, use `--sheehy-epsilon` (required, strictly
between `0` and `1`). `--select-landmarks`/`--landmarks-file` split that same witness-complex computation
into the two-step recipe described above. `--distance-to <file>` (`--distance-format csv`/`gudhi`/`dipha`,
`--distance-order`, `--distance-ground-norm`) compares the freshly-computed diagram against one already saved
to a file, printing bottleneck/Wasserstein distance per dimension instead of writing a diagram — see
"Comparing diagrams and turning them into vectors" below for the underlying `PersistenceResult` methods this
mirrors.

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

Entry points: `computeFromPoints`/`computeFromDistanceMatrix` (Vietoris-Rips/alpha/Cech/witness/dtm-rips/
dtm-alpha/sheehy-rips, from a point cloud or a precomputed distance matrix — alpha, Cech, and dtm-alpha need
real coordinates, so they're only available from the points overload; witness/dtm-rips/sheehy-rips work from
either, exactly like `vr`, since none of the three needs real coordinates, only a metric),
`computeFromCubicalImage`/`computeFromImage` (cubical persistence from a flat array + shape, 
or a 2D pixel matrix directly), and the two-step witness recipe's own four entry points -- 
`selectLandmarksFromPoints`/`selectLandmarksFromDistanceMatrix` (→ `LandmarkSelectionResult`) and 
`computeFromPointsAndLandmarks`/`computeFromDistanceMatrixAndLandmarks`, plus the 
`coveringRadiusFromPoints`/`coveringRadiusFromDistanceMatrix` query pair -- covered in their own section
above rather than the table below, since they take an explicit `int[] landmarks` parameter and each has its OWN
(stricter) recognized-options set rather than sharing the table's. Every method here has a no-options overload
and one taking a flat, alternating key/value `String[]` of options — so adding a new option in the future never
changes a method's call signature:

| Option | Values | Default |
|---|---|---|
| `complex` | `vr`, `alpha`, `cech`, `witness`, `dtm-rips`, `dtm-alpha`, `sheehy-rips` | `vr` |
| `engine` | `ripser`, `naive`, `chunks`, `cohomology`, `fast-cubical`, `fast-alpha` | `ripser` for `vr` and `witness`/`witnessVariant=lazy`; `naive` for `alpha`/`cech`/`dtm-rips`/`dtm-alpha`/`sheehy-rips`/`witness`/`witnessVariant=general`/cubical images. `fast-cubical` is valid ONLY for `computeFromCubicalImage`/`computeFromImage`, for any ambient dimension `>= 2`. `fast-alpha` is valid ONLY for `complex=alpha` with `alphaBackend=helix`, for any ambient dimension `>= 2` |
| `alphaBackend` | `helix`, `DQP` | `helix` (only consulted for `complex=alpha`) |
| `dtmK` | integer | REQUIRED for `complex=dtm-rips` or `complex=dtm-alpha`, no default |
| `dtmQ` | double | `2.0` (only consulted for `complex=dtm-rips` or `complex=dtm-alpha`) |
| `dtmP` | double | `1.0` (only consulted for `complex=dtm-rips`; must be `1.0` or `2.0`) |
| `sheehyEpsilon` | double | REQUIRED for `complex=sheehy-rips`, no default; strictly between `0` and `1` |
| `maxDimension` | integer | `2` — highest H_k reported, not highest simplex dimension built |
| `maxFiltrationValue` | double | the point cloud's own minimum enclosing radius (`+Infinity` for `witness`/`witnessVariant=general`; `SheehyRipsSimplexStream`'s own `maxFiniteFiltrationValue` for `complex=sheehy-rips`) |
| `field` | `Z` (finite field), `R` (floating point) | `Z`, `prime=2` |
| `prime` | integer | `2` (only for `field=Z`) |
| `epsilon` | double | `1e-9` (only for `field=R`; unrelated to `sheehyEpsilon` above) |
| `numLandmarks` | integer | REQUIRED for `complex=witness`, no default |
| `witnessVariant` | `lazy`, `general` | `lazy` (only consulted for `complex=witness`) |
| `landmarkSelector` | `maxmin`, `random` | `maxmin` (only consulted for `complex=witness`) |
| `landmarkSeed` | integer | `0` (only for `complex=witness`/`landmarkSelector=random`) |
| `nu` | `0`, `1`, `2` | `2` (only for `complex=witness`/`witnessVariant=lazy`) |
| `edgeCollapse` | `true`, `false` | `false` (only consulted for `complex=vr`; `true` rejected for every other `complex`) |

`alpha` refuses `engine=ripser` and `engine=chunks` (neither engine understands alpha complexes, and the
chunks/alpha combination is a known stall risk in the underlying library); `cech`, `dtm-rips`, and
`sheehy-rips` all refuse `engine=ripser` (the packed Ripser engine's optimizations are proven for
Vietoris-Rips's plain max-pairwise-distance functional specifically, not for Cech's circumradius, DTM's
weighted filtration, or Sheehy's sparsified/vanishing one); `dtm-alpha` refuses both `engine=ripser` and
`engine=chunks`; `witness` with `witnessVariant=general` refuses both `engine=ripser` and `engine=chunks` for
the same reason as `cech` (the general witness complex isn't a flag complex either) — use `witnessVariant=lazy`
(the default) for `engine=ripser`/`chunks`. `engine=cohomology` is accepted everywhere `engine=naive` is
(`vr`, `alpha`, `cech`, `dtm-rips`, `dtm-alpha`, `sheehy-rips`, and `witness` alike). `engine=fast-cubical` is
the mirror image: refused everywhere EXCEPT `computeFromCubicalImage`/`computeFromImage`, and even there
refused only for a degenerate 1-axis image (ambient dimension `< 2`) — no other ambient-dimension restriction.
`engine=fast-alpha` is likewise refused everywhere except `complex=alpha` with `alphaBackend=helix` (the
default; `alphaBackend=DQP` is refused too — `FastAlphaHomologyContext` cannot consume `AlphaShapeDQP`'s
output), with the same "any ambient dimension `>= 2`" rule as `fast-cubical` — though at higher ambient
dimension and point count it's noticeably more likely to throw `FastAlphaTriangulationException` on a given
point cloud (see "Which persistence engine?" below). Both `fast-*` exceptions name the actual mismatch
(dimension, backend, or complex) rather than throwing a bare `IllegalArgumentException`. See the
[Developer's Guide](../developers-guide/persistence-engines.md)'s streams-vs-engines table for the full
picture, complex by complex. Unrecognized keys or values throw `IllegalArgumentException` immediately rather
than silently falling back to a default.

`edgeCollapse=true` (`complex=vr` only) preprocesses the point cloud's own Vietoris-Rips 1-skeleton with edge
collapse (Boissonnat-Pritam/Glisse-Pritam) before building anything on top of it — a smaller weighted graph
with the SAME persistent homology at every filtration level, so the resulting `PersistenceResult` is identical
to what `edgeCollapse=false` (the default) would have produced, just computed from a much smaller complex.
Applies uniformly to every `engine` value. Measured 73-76% of edges removed and a 43-47x reduction-phase
speedup on random point clouds — construction-phase speedup is far smaller (1.45-1.74x), since the dominant
cost this removes is REDUCING the resulting chain complex, not enumerating candidate simplices in the first
place; see the [Developer's Guide](../developers-guide/architecture.md)'s "Flag-complex edge collapse" section
for the full construction and measurement. `--edge-collapse` on the CLI mirrors this option exactly (unlike
`--distance-to`/the vectorizations/the boundary-matrix export above, this one changes nothing about the output
shape, so it needs no special CLI-side handling at all).

Representative-chain vertex indices for `complex=witness` are **ambient point-cloud indices**, already
mapped back from the stream's own local `0 until numLandmarks` landmark indices — `cycleVertices` never
reports a raw local landmark index.

`PersistenceResult.cycleVertices`/`cycleCoefficients` give you each bar's representative chain: every engine
records one for every bar — though for `engine=cohomology`, only an *essential* bar's representative is
guaranteed to be a genuine cocycle (zero coboundary); a finite bar's is a valid witness on its own living
interval, not over the whole complex (see the developer's guide's persistence-engines page for why).

### Comparing diagrams and turning them into vectors

`PersistenceResult` also answers "how different are these two barcodes" (bottleneck/Wasserstein distance) and
"turn this barcode into a fixed-size array" (persistence landscapes/images, for feeding into ordinary ML
tooling) — both compare/summarize an *already-computed* result, so they're instance methods, not part of the
`computeFrom*` option table above:

```java
double[][] points1 = { {0.0, 0.0}, {1.0, 0.0}, {0.5, 0.8} };
double[][] points2 = { {0.0, 0.0}, {1.05, 0.0}, {0.5, 0.85} }; // a small perturbation of points1
PersistenceResult r1 = TDA4j.computeFromPoints(points1, new String[]{"maxDimension", "1"});
PersistenceResult r2 = TDA4j.computeFromPoints(points2, new String[]{"maxDimension", "1"});

double d0 = r1.bottleneckDistance(r2, 0);       // dimension 0, L-infinity ground norm (the usual TDA default)
double w1 = r1.wassersteinDistance(r2, 1, 2.0); // dimension 1, order 2

// 5 landscape levels, sampled at 100 points across [0.0, 2.0]
double[][] landscape = r1.landscape(1, 5, 0.0, 2.0, 100);

// a 20x20 persistence image, sigma=0.1, weight cap defaulted to the diagram's own max persistence
double[][] image = r1.persistenceImage(1, 0.1, 0.0, 2.0, 0.0, 2.0, 20, 20);
```

`bottleneckDistance`/`wassersteinDistance` return `Double.POSITIVE_INFINITY` when the two diagrams have
different numbers of essential (never-dying) bars in that dimension — a real answer ("no finite matching
exists"), not a failure. `--distance-to <file>` mirrors the distance methods on the `tda4j` command line
(comparing the diagram just computed against one already saved as `csv`/`gudhi`/`dipha`; `--distance-format`
selects which), printing one `dim <k>: bottleneck=... wasserstein=...` line per dimension instead of writing
a diagram; the two vectorizations are MATLAB/Java-only for now (they produce a matrix, not a diagram, which
doesn't fit the CLI's diagram-in-diagram-out shape). From plain Scala, use
`org.appliedtopology.tda4j.barcode.BarcodeDistance`/`Vectorization` directly on `List[PersistenceBar[Double,
_]]` — see the [Developer's Guide](../developers-guide/architecture.md)'s "`Barcode.scala`" section for the
ground-metric convention, the essential-bar policy (the two vectorizations handle it differently, on
purpose), and the literature this follows (Kerber-Morozov-Nigmetov 2017 for the distances; Bubenik 2013 for
landscapes; Adams et al. 2017 for persistence images).

### Boundary-matrix export

`PersistenceResult` also exports the boundary matrix of the full complex it was computed from, for anything
that wants to do its own linear algebra over it (an optimal-cycle solver, harmonic smoothing for circular
coordinates, ...) rather than TDA4j's own reduction:

```java
PersistenceResult result = TDA4j.computeFromPoints(points, new String[]{"maxDimension", "1"});
int n = result.numCells();               // NOT result.size() -- cells, not bars
int[] rows = result.boundaryRows();      // 0-based
int[] cols = result.boundaryCols();
double[] values = result.boundaryValues();
int dim7 = result.columnDimension(7);
int[] verts7 = result.columnVertices(7); // same per-complex-type shape cycleVertices documents
double fv7 = result.columnFiltrationValue(7);
```

```matlab
n = result.numCells();
M = sparse(double(result.boundaryRows())+1, double(result.boundaryCols())+1, result.boundaryValues(), n, n);
```

Computed lazily (nothing is built until the first `numCells`/`boundaryRows`/... call) and cached after that,
and is the SAME matrix regardless of which `engine` actually computed this result's own bars — the boundary
matrix is a property of the complex, not of which reduction algorithm ran over it. Column `j`'s own dimension/
vertices/filtration value describe cell `j`, not bar `j` — there are generally far more cells than bars, and
this is a different indexing than `dimension(i)`/`cycleVertices(i)`/etc. above. Not mirrored on the CLI (a
sparse matrix doesn't fit its diagram-in-diagram-out shape) — see the [Developer's
Guide](../developers-guide/architecture.md)'s "`Barcode.scala`" section for the full construction.

### Circular coordinates

For a point cloud with cyclic/periodic structure (e.g. samples along a loop), `TDA4j.h1Bars`/
`circularCoordinates` (de Silva-Morozov-Vejdemo-Johansson 2011) turn a persistent H¹ class into a map from
each point to an angle in `[0, 1)` — a genuinely topological coordinate, not a barcode, so these are their own
entry points rather than a new `complex=` value on `computeFromPoints`:

```java
double[][] bars = TDA4j.h1Bars(points);      // row i: (birth_i, death_i), sorted by persistence descending
double r = bars[0][0] + (bars[0][1] - bars[0][0]) * 0.5; // pick r inside the most persistent bar's own range

CircularCoordinatesResult result = TDA4j.circularCoordinates(points, r); // cocycleIndex=0, prime=47 defaults
double[] theta = result.theta();             // one entry per input point, Double.NaN outside the class's own
                                              // connected component -- not every point necessarily gets one
boolean covered = result.hasCoordinate(3);
```

`h1Bars` is the required first call: there is no way to pick a meaningful `r` without first knowing a target
bar's own `[birth, death)` range. `circularCoordinates` throws `IllegalArgumentException` for an `r` outside
that range (or a bad `cocycleIndex`/`prime`), and `NoIntegerCocycleException` (also a plain `RuntimeException`,
so it crosses the MATLAB bridge the same way) if the chosen class has no exact integer lift at `prime` —
usually resolved by retrying with a larger odd prime; a genuinely torsion class (no real/integer lift at any
prime, RP²'s own fundamental class being the standard example) will keep failing regardless. Not mirrored on
the CLI, for the same reason as the vectorizations and boundary-matrix export above (its output is a per-point
array, not a diagram) plus the inherently two-step, data-dependent nature of picking `r` — see the [Developer's
Guide](../developers-guide/architecture.md)'s `homology.CircularCoordinates` section for the full construction
(the truncated-complex `K_r` reframing, the harmonic-smoothing linear system, and the integer-lift check).

## Which persistence engine?

| Need | Engine (`engine=` for MATLAB/CLI) |
|---|---|
| Exploration, intermediate-filtration queries, representative cycles | `naive` (`CellularHomologyContext`/`TDAContext`) |
| Fastest, most memory-efficient — the default for `complex=vr` | `ripser` (`PackedRipserCohomologyContext`) |
| Large complex, want representatives for every bar including essential ones | `chunks` (`CellularPersistenceInChunksContext`) |
| Cohomology (cocycle representatives) on `Cube`/`FiniteSimplicialSet`, or on Alpha/Cech/DTM/Sheehy/witness, where `ripser` doesn't apply | `cohomology` (`CellularCohomologyContext`) |
| Alpha or Cech or DTM or Sheehy complexes, or a general (non-flag) witness complex | `naive` or `cohomology` (`chunks` also works for Cech, DTM-Rips, and Sheehy-Rips — not Alpha/DTM-Alpha) |
| A lazy witness complex (the flag-complex variant) | `ripser` (`PackedRipserCohomologyContext`, run directly on `WitnessMetricSpace`) or `naive`/`chunks`/`cohomology` |
| A cubical image, any ambient dimension `>= 2` — fastest option there | `fast-cubical` (`FastCubicalHomologyContext`; H0/H1 only, no `Chain` reduction at all, in 2D specifically; a `chunks` hybrid for the residual middle dimensions at 3D+) |
| An alpha complex via `"helix"`, any ambient dimension `>= 2` — fastest option there | `fast-alpha` (`FastAlphaHomologyContext`; H0/H1 only, no `Chain` reduction at all, in 2D specifically; a `chunks` hybrid for the residual middle dimensions at 3D+; `"DQP"` needs `naive`/`chunks`/`cohomology` instead; higher ambient dimension and point count make `FastAlphaTriangulationException` noticeably more likely — see `.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`) |

All engines are generic over the coefficient field (a prime finite field or floating point); `naive`,
`chunks`, and `cohomology` are also generic over the cell type (simplices, cubes, or simplicial-set
generators) — only `ripser`, `fast-cubical`, and `fast-alpha` are specialized (to Vietoris-Rips, to cubical
grids, and to `HelixDelaunay` triangulations, respectively). See the
[Developer's Guide's persistence-engines page](../developers-guide/persistence-engines.md) for the full
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

[Tutorials](../tutorials/README.md) — currently a placeholder; porting Henry Adams' JavaPlex tutorials
to TDA4j is tracked there as future work, not yet done. The witness-complex construction those tutorials
lean on heavily is now implemented (`LandmarkSelector`/`WitnessGeometry`/`LazyWitnessSimplexStream`/
`WitnessCofaceSimplexStream`, see "Witness complexes" above) — a building block for that port, not the port
itself.
