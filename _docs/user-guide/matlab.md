---
layout: main
title: Calling from MATLAB or Java
---


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
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-<version>-assembly.jar');
points = [0.0 0.0; 1.0 0.0; 0.5 0.8];
result = org.appliedtopology.tda4j.matlab.TDA4j.computeFromPoints(points);
bars = result.toArray();
```

### Which bars are reported

A barcode computed from real data is mostly noise: thousands of bars of negligible length next to the few that
carry the shape. So by default `toArray()`/`size()`/`birth`/`death`/`cycleVertices`/... report a bar only if it
is **essential** (never dies) or its persistence `death - birth` is **greater than 1% of the input's minimum
enclosing radius** — Ripser's enclosing radius, `min over points of (max distance to another point)`: beyond it the
Vietoris-Rips complex is a cone, so every bar lives between 0 and it. The scale is in the units the complex
reports (diameters for `vr`, radii for `cech`/`alpha`). A cubical image and a Dowker relation have no metric, so
their own value range (max − min) is the scale instead. A single point has scale 0, so nothing is hidden, and an
infinite enclosing radius (a disconnected distance matrix with infinite entries) falls back to the span of the
barcode's own finite endpoints.

```java
// a different cut: 5% of the scale, or an absolute persistence of 0.1, or no cut at all
TDA4j.computeFromPoints(points, new String[] {"minPersistenceFraction", "0.05"});
TDA4j.computeFromPoints(points, new String[] {"minPersistence", "0.1"});
PersistenceResult everything = TDA4j.computeFromPoints(points, new String[] {"minPersistence", "0"});

int hidden = result.hiddenCount();                 // how many bars the threshold hid
double cut = result.persistenceThreshold();        // the persistence a bar had to exceed
double[][] all = result.toArrayUnfiltered();       // the complete barcode, same layout as toArray()
```

The threshold only changes what is *reported*: bottleneck/Wasserstein distances, landscapes and persistence
images always use the complete barcode, so they do not depend on each result's own scale. It
is applied after the computation, so it does not make the computation itself cheaper (`maxFiltrationValue` and
`maxDimension` do that). `circularCoordinates`/`toroidalCoordinates`' `h1Bars` are not filtered.

Entry points: `computeFromPoints`/`computeFromDistanceMatrix` (Vietoris-Rips/alpha/Cech/witness/dtm-rips/
dtm-alpha/sparse-rips, from a point cloud or a precomputed distance matrix — alpha, Cech, and dtm-alpha need
real coordinates, so they're only available from the points overload; witness/dtm-rips/sparse-rips work from
either, exactly like `vr`, since none of the three needs real coordinates, only a metric),
`computeFromCubicalImage`/`computeFromImage` (cubical persistence from a flat array + shape,
or a 2D pixel matrix directly), `computeFromRelation` (Dowker complex persistence from a general relation
matrix — see "Dowker complexes" above; its own OWN, much smaller options set, `"engine"`/`"maxDimension"`/
`"maxFiltrationValue"`/`"dual"`/`"field"`/`"prime"`/`"epsilon"`, is not in the table below either, for the same
reason the two-step witness recipe's own options aren't), and the two-step witness recipe's own four entry
points --
`selectLandmarksFromPoints`/`selectLandmarksFromDistanceMatrix` (→ `LandmarkSelectionResult`) and
`computeFromPointsAndLandmarks`/`computeFromDistanceMatrixAndLandmarks`, plus the
`coveringRadiusFromPoints`/`coveringRadiusFromDistanceMatrix` query pair -- covered in their own section
above rather than the table below, since they take an explicit `int[] landmarks` parameter and each has its OWN
(stricter) recognized-options set rather than sharing the table's. Every method here has a no-options overload
and one taking a flat, alternating key/value `String[]` of options — so adding a new option in the future never
changes a method's call signature:

| Option | Values | Default |
|---|---|---|
| `complex` | `vr`, `alpha`, `cech`, `witness`, `dtm-rips`, `dtm-alpha`, `sparse-rips` | `vr` |
| `engine` | `ripser`, `naive`, `chunks`, `cohomology`, `fast-cubical`, `fast-alpha` | `ripser` for `vr` and `witness`/`witnessVariant=lazy`; `naive` for `alpha`/`cech`/`dtm-rips`/`dtm-alpha`/`sparse-rips`/`witness`/`witnessVariant=general`/cubical images. `fast-cubical` is valid ONLY for `computeFromCubicalImage`/`computeFromImage`, for any ambient dimension `>= 2`. `fast-alpha` is valid ONLY for `complex=alpha` with `alphaBackend=helix`, for any ambient dimension `>= 2` |
| `alphaBackend` | `helix`, `DQP` | `helix` (only consulted for `complex=alpha`) |
| `requireValidTriangulation` | `true`, `false` | `false` (only consulted for `complex=alpha`/`alphaBackend=helix`; rejected for any other `complex` or `alphaBackend=DQP`) — repairs a `HelixDelaunay` facet-multiplicity violation instead of letting it surface as `FastAlphaTriangulationException`; validated at ambient dimension 2 and 3, not yet at `d >= 4` |
| `dtmK` | integer | REQUIRED for `complex=dtm-rips` or `complex=dtm-alpha`, no default |
| `dtmQ` | double | `2.0` (only consulted for `complex=dtm-rips` or `complex=dtm-alpha`) |
| `dtmP` | double | `1.0` (only consulted for `complex=dtm-rips`; must be `1.0` or `2.0`) |
| `sparseEpsilon` | double | REQUIRED for `complex=sparse-rips`, no default; strictly between `0` and `1` |
| `maxDimension` | integer | `2` — highest H_k reported, not highest simplex dimension built |
| `maxFiltrationValue` | double | the point cloud's own minimum enclosing radius (`+Infinity` for `witness`/`witnessVariant=general`; `SparseRips`'s own maximum finite filtration value for `complex=sparse-rips`) |
| `minPersistence` | double | unset — a bar is reported only if essential or its persistence exceeds 1% of the minimum enclosing radius (see "Which bars are reported" above); an absolute threshold in the barcode's own units, `0` reports every bar. Accepted by every `computeFrom*` method (not the landmark-selection ones, which produce no barcode) |
| `minPersistenceFraction` | double | `0.01` — the same threshold as a fraction of the minimum enclosing radius; `0` reports every bar. Give at most one of the two |
| `field` | `Z` (finite field), `R` (floating point) | `Z`, `prime=17` |
| `prime` | integer | `2` (only for `field=Z`) |
| `epsilon` | double | `1e-9` (only for `field=R`; unrelated to `sparseEpsilon` above) |
| `numLandmarks` | integer | REQUIRED for `complex=witness`, no default |
| `witnessVariant` | `lazy`, `general` | `lazy` (only consulted for `complex=witness`) |
| `landmarkSelector` | `maxmin`, `random` | `maxmin` (only consulted for `complex=witness`) |
| `landmarkSeed` | integer | `0` (only for `complex=witness`/`landmarkSelector=random`) |
| `nu` | `0`, `1`, `2` | `2` (only for `complex=witness`/`witnessVariant=lazy`) |
| `edgeCollapse` | `true`, `false` | `false` (only consulted for `complex=vr`; `true` rejected for every other `complex`) |

`alpha` refuses `engine=ripser` and `engine=chunks` (neither engine understands alpha complexes, and the
chunks/alpha combination is a known stall risk in the underlying library); `cech`, `dtm-rips`, and
`sparse-rips` all refuse `engine=ripser` (the packed Ripser engine's optimizations are proven for
Vietoris-Rips's plain max-pairwise-distance functional specifically, not for Cech's circumradius, DTM's
weighted filtration, or Sheehy's sparsified/vanishing one); `dtm-alpha` refuses both `engine=ripser` and
`engine=chunks`; `witness` with `witnessVariant=general` refuses both `engine=ripser` and `engine=chunks` for
the same reason as `cech` (the general witness complex isn't a flag complex either) — use `witnessVariant=lazy`
(the default) for `engine=ripser`/`chunks`. `engine=cohomology` is accepted everywhere `engine=naive` is
(`vr`, `alpha`, `cech`, `dtm-rips`, `dtm-alpha`, `sparse-rips`, and `witness` alike). `engine=fast-cubical` is
the mirror image: refused everywhere EXCEPT `computeFromCubicalImage`/`computeFromImage`, and even there
refused only for a degenerate 1-axis image (ambient dimension `< 2`) — no other ambient-dimension restriction.
`engine=fast-alpha` is likewise refused everywhere except `complex=alpha` with `alphaBackend=helix` (the
default; `alphaBackend=DQP` is refused too — `FastAlphaHomologyEngine` cannot consume `AlphaShapeDQP`'s
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
