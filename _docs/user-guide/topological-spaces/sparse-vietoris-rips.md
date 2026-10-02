---
layout: main
---

### Sheehy's sparse/approximate Vietoris-Rips filtration

```scala 3
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

given Double is Field = Field.DoubleApproximated(1e-9)
val points: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8), Array(0.2, 0.5))
val metricSpace = EuclideanMetricSpace(points)

val sheehyStream = SheehyRipsSimplexStream(metricSpace, epsilon = 0.5)
val homology = SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(sheehyStream)
```

A `(1+epsilon)`-multiplicative approximation to plain Vietoris-Rips's own barcode, built from a complex
that's linear-sized (for point sets of bounded doubling dimension) rather than the full Vietoris-Rips
complex — fewer simplices to reduce at the same scale range, at the cost of a controlled, quantified loss of
precision (Cavanna, Jahanseir & Sheehy 2015). Smaller `epsilon` means less sparsification and a closer
approximation to plain Vietoris-Rips; `epsilon` must be strictly between `0` and `1`. Like Cech above, only
`naive`/`chunks`/`cohomology` are used — the packed Ripser engine's optimizations assume a plain
max-pairwise-distance filtration functional, which this construction's own sparsification and vertex
"vanishing" don't satisfy (see the [Developer's Guide](../../developers-guide/architecture.md)).


### Witness complexes

```scala 3
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

given Double is Field = Field.DoubleApproximated(1e-9)

val points: Array[Array[Double]] = Array.tabulate(30)(i => Array(math.cos(i * 0.9), math.sin(i * 0.9)))
val ambient = EuclideanMetricSpace(points)
val landmarks = LandmarkSelector.maxmin(ambient, numLandmarks = 20).landmarks

val lazyStream = LazyWitnessSimplexStream(ambient, landmarks)   // nu = 2, JavaPlex's own default
val homology = SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(lazyStream)
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
  directly with the packed Ripser engine (`PackedRipserCohomologyEngine`) by handing it a
  `WitnessMetricSpace` instead of a stream: `PackedRipserCohomologyEngine(WitnessMetricSpace(WitnessGeometry(ambient,
  landmarks), nu = 2), maxDimension)`. The `nu` parameter (`0`, `1`, or `2`, default `2`) controls how
  forgiving a witness's own threshold is — see `WitnessMetricSpace`'s own doc.
- **`WitnessCofaceSimplexStream`** (JavaPlex's plain `WitnessStream`) — NOT a flag complex, so `engine=ripser`/
  `chunks` don't apply; use the naive or cohomology engine instead. `maxFiltrationValue` here defaults to
  `+Infinity` (untruncated), not the point cloud's enclosing radius — the enclosing-radius shortcut is only
  valid for flag complexes.

```scala 3
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

given Double is Field = Field.DoubleApproximated(1e-9)

val points: Array[Array[Double]] = Array.tabulate(30)(i => Array(math.cos(i * 0.9), math.sin(i * 0.9)))
val ambient = EuclideanMetricSpace(points)
val landmarks = LandmarkSelector.maxmin(ambient, numLandmarks = 20).landmarks

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
indistinguishable from `lazy+naive` at single-trial resolution).

#### The two-step recipe from MATLAB/CLI: select landmarks, read R, then compute

The one-shot `complex=witness` path (below, in "Calling from MATLAB or Java") picks landmarks internally and
never reports back the covering radius `R` — so the JavaPlex tutorial's own "pick landmarks, read `R`, use `2R`
as the threshold" recipe isn't reproducible through it. `matlab.TDA4j` also offers the SAME construction split
into two steps for exactly this: `selectLandmarksFrom{Points,DistanceMatrix}` (step 1, returns a
`LandmarkSelectionResult` with both `landmarks()` and `coveringRadius()`) and
`computeFrom{Points,DistanceMatrix}AndLandmarks` (step 2, takes that landmark array directly). Both entry points
work from Scala too, not just MATLAB:

```scala 3
import org.appliedtopology.tda4j.matlab.TDA4j

val points: Array[Array[Double]] = Array.tabulate(60)(i => Array(math.cos(i * 0.9), math.sin(i * 0.9)))
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
